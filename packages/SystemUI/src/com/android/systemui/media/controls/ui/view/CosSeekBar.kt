/*
 * Copyright (C) 2026 The PenguinOS Project
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.media.controls.ui.view

import android.animation.ValueAnimator
import android.app.WallpaperColors
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.graphics.ColorUtils
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.PI

class CosSeekBar
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle,
) : SeekBar(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val light =
        runCatching { CosSeekLight() }
            .onFailure { Log.w("CosSeekBar", "Could not create the seek light", it) }
            .getOrNull()
    private val glowHeight = AnimatedValue(Off.height)
    private val falloff = AnimatedValue(Off.heightFalloff)
    private val cone = AnimatedValue(Off.coneK)
    private val secondCone = AnimatedValue(Off.secondConeK)
    private val headWidth = AnimatedValue(Off.w0)
    private val firstAlpha = AnimatedValue(Off.alpha1)
    private val secondAlpha = AnimatedValue(Off.alpha2)
    private val thickness = AnimatedValue(4f)
    private var playing = false
    private var animationsAllowed = true
    private var aggregatedVisible = false
    private var touchScrubbing = false
    private var barColor: Int? = null
    private var artworkColor: Int? = null
    private var artwork: Drawable? = null
    @Volatile private var artworkGeneration = 0

    var isScrubbing = false
        set(value) {
            if (field == value) return
            field = value
            updateAnimation()
        }

    init {
        thumb = ColorDrawable(Color.TRANSPARENT)
        progressDrawable = ColorDrawable(Color.TRANSPARENT)
        progressTintList = null
        progressBackgroundTintList = null
        background = null
        splitTrack = false
        thumbOffset = 0
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(0, 0, 0, 0)
    }

    fun setBarColor(color: Int) {
        if (barColor == color) return
        barColor = color
        invalidate()
    }

    fun setArtwork(drawable: Drawable?) {
        if (artwork === drawable) return
        artwork = drawable
        artworkGeneration++
        artworkColor = null
        invalidate()
        if (isAttachedToWindow) loadArtworkColor()
    }

    private fun loadArtworkColor() {
        val drawable = artwork ?: return
        val generation = ++artworkGeneration
        artworkExecutor.execute {
            if (generation != artworkGeneration) return@execute
            val color = extractArtworkColor(drawable)
            post {
                if (isAttachedToWindow && generation == artworkGeneration) {
                    artworkColor = color
                    invalidate()
                }
            }
        }
    }

    fun setPlaybackState(isPlaying: Boolean, animate: Boolean = true) {
        if (playing == isPlaying && animationsAllowed == animate) return
        playing = isPlaying
        animationsAllowed = animate
        updateAnimation()
    }

    private fun updateAnimation() {
        val visible = isAttachedToWindow && aggregatedVisible
        val dragging = isScrubbing || touchScrubbing
        val target =
            when {
                !visible || !animationsAllowed -> Off
                dragging -> Dragging
                playing -> Playing
                else -> Off
            }
        val animate = visible && animationsAllowed && ValueAnimator.areAnimatorsEnabled()
        val shapeResponse = if (dragging) 0.4f else if (playing) 0.9f else 0.3f
        val alphaResponse = if (dragging) 0.4f else 0.3f
        glowHeight.moveTo(target.height, shapeResponse, animate)
        falloff.moveTo(target.heightFalloff, shapeResponse, animate)
        cone.moveTo(target.coneK, shapeResponse, animate)
        secondCone.moveTo(target.secondConeK, shapeResponse, animate)
        headWidth.moveTo(target.w0, shapeResponse, animate)
        firstAlpha.moveTo(target.alpha1, alphaResponse, animate)
        secondAlpha.moveTo(target.alpha2, alphaResponse, animate)
        thickness.moveTo(if (dragging) 6f else 4f, 0.3f, animate)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadArtworkColor()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        artworkGeneration++
        aggregatedVisible = false
        touchScrubbing = false
        updateAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        updateAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        val dragging = handled && isEnabled && isPressed
        if (touchScrubbing != dragging) {
            touchScrubbing = dragging
            updateAnimation()
        }
        return handled
    }

    override fun onDraw(canvas: Canvas) {
        val length = (width - paddingLeft - paddingRight).toFloat()
        if (length <= 0f || height <= 0) return
        val fraction =
            if (max > min) ((progress - min).toFloat() / (max - min)).coerceIn(0f, 1f) else 0f
        val left = paddingLeft.toFloat()
        val centerY = height / 2f
        val radius = (thickness.value * density / 2f).coerceAtMost(centerY)
        val color = barColor ?: progressTintList?.defaultColor ?: Color.WHITE
        trackPaint.color =
            progressBackgroundTintList?.defaultColor ?: ColorUtils.setAlphaComponent(color, 51)
        progressPaint.color = color
        canvas.drawRoundRect(
            left, centerY - radius, left + length, centerY + radius, radius, radius, trackPaint
        )
        if (fraction > 0f) {
            canvas.drawRoundRect(
                left,
                centerY - radius,
                left + length * fraction,
                centerY + radius,
                radius,
                radius,
                progressPaint,
            )
        }
        if (
            canvas.isHardwareAccelerated &&
                (firstAlpha.value > 0.001f || secondAlpha.value > 0.001f)
        ) {
            light?.apply {
                setColor(artworkColor ?: color)
                setShape(
                    CosSeekLight.Shape(
                        glowHeight.value,
                        falloff.value,
                        cone.value,
                        secondCone.value,
                        headWidth.value,
                        firstAlpha.value,
                        secondAlpha.value,
                    )
                )
                draw(canvas, left, centerY, length, fraction, rtl = false)
            }
        }
    }

    private inner class AnimatedValue(initial: Float) {
        private val holder = FloatValueHolder(initial)
        private val animation =
            SpringAnimation(holder).apply {
                spring = SpringForce(initial).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                setMinimumVisibleChange(0.00001f)
                addUpdateListener { _, _, _ -> invalidate() }
            }
        val value: Float
            get() = holder.value

        fun moveTo(target: Float, response: Float, animate: Boolean) {
            if (!animate) {
                animation.cancel()
                holder.value = target
            } else if (holder.value != target || animation.isRunning) {
                val frequency = (2 * PI / response).toFloat()
                animation.spring.stiffness = frequency * frequency
                if (!animation.isRunning) animation.setStartValue(holder.value)
                animation.animateToFinalPosition(target)
            }
        }
    }

    private companion object {
        val Off = CosSeekLight.Shape(0.1f, 0f, 0f, 0f, 0f, 0f, 0f)
        val Playing = CosSeekLight.Shape(0.6f, 0.6f, 0.2f, 0.2f, 0.008f, 1f, 0.6f)
        val Dragging = CosSeekLight.Shape(0.2f, 2f, 0f, 0f, 0.014f, 0.3f, 0.3f)
        val artworkExecutor = Executors.newSingleThreadExecutor()
        val artworkColorCache = WeakHashMap<Drawable, Int?>()

        fun extractArtworkColor(drawable: Drawable): Int? {
            if (artworkColorCache.containsKey(drawable)) return artworkColorCache[drawable]
            val color =
                runCatching {
                    val copy =
                        drawable.constantState?.newDrawable()?.mutate() ?: return@runCatching null
                    val palette = WallpaperColors.fromDrawable(copy)
                    val colors =
                        listOfNotNull(
                            palette.primaryColor,
                            palette.secondaryColor,
                            palette.tertiaryColor,
                        )
                    val hsl = FloatArray(3)
                    val source =
                        colors.firstOrNull {
                            ColorUtils.colorToHSL(it.toArgb(), hsl)
                            hsl[1] >= 0.12f
                        } ?: colors.firstOrNull() ?: return@runCatching null
                    ColorUtils.colorToHSL(source.toArgb(), hsl)
                    hsl[2] = hsl[2].coerceIn(0.6f, 0.72f)
                    ColorUtils.HSLToColor(hsl)
                }.getOrNull()
            artworkColorCache[drawable] = color
            return color
        }
    }
}
