/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.media.controls.ui.view

import android.animation.ValueAnimator
import android.app.WallpaperColors
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.SeekBar
import com.android.internal.graphics.ColorUtils
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class GradientWaveSeekBar
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle,
) : SeekBar(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * density
        }
    private val wavePath = Path()
    private val waveShaders = arrayOfNulls<Shader>(3)
    private var accent = context.getColor(android.R.color.system_accent1_400)
    private var artworkColors: List<Int> = emptyList()
    private var gradientColors = intArrayOf(accent, accent, accent, accent)
    private var shaderLeft = Float.NaN
    private var shaderRight = Float.NaN
    private var glowColor: Int? = null
    private var playing = false
    private var animationsAllowed = true
    private var aggregatedVisible = false
    private var amplitude = 0f
    private var phase = 0f
    private var lastFrame = 0L
    private var fadeAnimator: ValueAnimator? = null
    private var artwork: Drawable? = null
    @Volatile private var artworkGeneration = 0

    var isScrubbing = false
        set(value) {
            if (field == value) return
            field = value
            updateAnimation()
        }

    init {
        thumb = ColorDrawable(Color.WHITE)
        progressDrawable = ColorDrawable(Color.TRANSPARENT)
        background = null
        splitTrack = false
        thumbOffset = 0
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(0, 0, 0, 0)
        updateColors()
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        val inset = (THUMB_GLOW_RADIUS_DP * resources.displayMetrics.density).roundToInt()
        super.setPadding(maxOf(left, inset), top, maxOf(right, inset), bottom)
    }

    fun setTrackColor(color: Int) {
        val trackColor = (color and 0x00ffffff) or 0x66000000
        if (trackPaint.color == trackColor) return
        trackPaint.color = trackColor
        invalidate()
    }

    fun setPlaybackState(isPlaying: Boolean, animate: Boolean = true) {
        if (playing == isPlaying && animationsAllowed == animate) return
        playing = isPlaying
        animationsAllowed = animate
        updateAnimation()
    }

    private fun updateAnimation() {
        fadeAnimator?.cancel()
        fadeAnimator = null
        lastFrame = 0L
        val target = if (playing && !isScrubbing) 1f else 0f
        if (
            !isAttachedToWindow ||
                !aggregatedVisible ||
                !animationsAllowed ||
                !ValueAnimator.areAnimatorsEnabled()
        ) {
            amplitude = target
        } else if (amplitude != target) {
            fadeAnimator =
                ValueAnimator.ofFloat(amplitude, target).apply {
                    duration = 350L
                    addUpdateListener {
                        amplitude = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadArtworkColors()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        artworkGeneration++
        fadeAnimator?.cancel()
        fadeAnimator = null
        aggregatedVisible = false
        lastFrame = 0L
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        updateAnimation()
    }

    fun setArtwork(drawable: Drawable?) {
        if (artwork === drawable) return
        artwork = drawable
        artworkGeneration++
        if (drawable == null) {
            artworkColors = emptyList()
            updateColors()
        } else if (isAttachedToWindow) {
            loadArtworkColors()
        }
    }

    private fun loadArtworkColors() {
        val drawable = artwork ?: return
        val generation = ++artworkGeneration
        artworkExecutor.execute {
            if (generation != artworkGeneration) return@execute
            val colors = extractArtworkColors(drawable)
            post {
                if (isAttachedToWindow && generation == artworkGeneration) {
                    artworkColors = colors
                    updateColors()
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        accent = context.getColor(android.R.color.system_accent1_400)
        updateColors()
    }

    private fun updateColors() {
        val primary = artworkColors.getOrNull(0) ?: accent
        val secondary = artworkColors.getOrNull(1) ?: ColorUtils.blendARGB(primary, accent, 0.5f)
        val tertiary =
            artworkColors.getOrNull(2) ?: ColorUtils.blendARGB(primary, Color.WHITE, 0.25f)
        gradientColors =
            intArrayOf(accent, ColorUtils.blendARGB(primary, accent, 0.15f), secondary, tertiary)
                .map { vibrantColor(it) }
                .toIntArray()
        shaderLeft = Float.NaN
        invalidate()
    }

    private fun updateShaders(left: Float, right: Float) {
        if (shaderLeft == left && shaderRight == right) return
        shaderLeft = left
        shaderRight = right
        progressPaint.shader =
            LinearGradient(left, 0f, right, 0f, gradientColors, null, Shader.TileMode.CLAMP)
        for (layer in waveShaders.indices) {
            val colors = IntArray(4) { gradientColors[(it + layer) % gradientColors.size] }
            waveShaders[layer] =
                LinearGradient(left, 0f, right, 0f, colors, null, Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val trackWidth = right - left
        if (trackWidth <= 0f || height <= 0) return
        updateShaders(left, right)
        val fraction =
            if (max > min) {
                ((progress - min).toFloat() / (max - min)).coerceIn(0f, 1f)
            } else 0f
        val head = left + trackWidth * fraction
        val radius = 2f * density
        val contentHeight = (height - paddingTop - paddingBottom).toFloat()
        if (contentHeight <= 0f) return
        val trackOffset =
            min(3f * density, contentHeight / 2f - THUMB_GLOW_RADIUS_DP * density)
                .coerceAtLeast(0f)
        val centerY = paddingTop + contentHeight / 2f + trackOffset
        val trackTop = centerY - radius
        val now = SystemClock.uptimeMillis()
        val animate =
            isAttachedToWindow &&
                aggregatedVisible &&
                animationsAllowed &&
                ValueAnimator.areAnimatorsEnabled()
        if (animate && playing && !isScrubbing) {
            val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1000f, 0.05f)
            phase = (phase + dt * 1.35f) % (20f * PI.toFloat())
        }
        lastFrame = now

        canvas.drawRoundRect(left, trackTop, right, centerY + radius, radius, radius, trackPaint)
        if (head > left) {
            if (amplitude > 0f) {
                for (layer in waveShaders.indices) {
                    drawWave(canvas, layer, left, head, trackTop)
                }
            }
            canvas.drawRoundRect(
                left,
                trackTop,
                head,
                centerY + radius,
                radius,
                radius,
                progressPaint,
            )
        }

        if (thumb.alpha > 0) drawThumb(canvas, head, centerY, fraction)
        if (animate && playing && !isScrubbing) postInvalidateOnAnimation()
    }

    private fun drawWave(canvas: Canvas, layer: Int, left: Float, head: Float, baseline: Float) {
        val length = head - left
        val waveLength = (128f + layer * 26f) * density
        val availableHeight = (baseline - paddingTop - density).coerceAtLeast(0f)
        val waveHeight = min(20f * density, availableHeight) * (1f - layer * 0.22f)
        val taper = min(40f * density, length / 2f)
        if (taper <= 0f) return
        wavePath.rewind()
        wavePath.moveTo(left, baseline)
        var x = left
        while (x < head) {
            val edge = (min(x - left, head - x) / taper).coerceIn(0f, 1f)
            val envelope = edge * edge * (3f - 2f * edge)
            val position = (x - left) / waveLength * 2f * PI.toFloat()
            val crest = ((cos(position - phase + layer * 1.9f) + 1f) / 2f)
            val variation = 0.88f + 0.12f * sin(position * 0.47f + layer + phase * 0.3f)
            val y =
                baseline -
                    (0.55f + 0.45f * crest) * variation * envelope * waveHeight * amplitude
            wavePath.lineTo(x, y)
            x += density
        }
        wavePath.lineTo(head, baseline)
        wavePath.close()
        wavePaint.shader = waveShaders[layer]
        wavePaint.alpha = 205 + layer * 20
        canvas.drawPath(wavePath, wavePaint)
    }

    private fun drawThumb(canvas: Canvas, x: Float, y: Float, fraction: Float) {
        val position = fraction * (gradientColors.size - 1)
        val index = position.toInt().coerceAtMost(gradientColors.size - 2)
        val color =
            ColorUtils.blendARGB(gradientColors[index], gradientColors[index + 1], position - index)
        if (glowColor != color) {
            glowColor = color
            glowPaint.shader =
                RadialGradient(
                    0f,
                    0f,
                    THUMB_GLOW_RADIUS_DP * density,
                    intArrayOf(
                        ColorUtils.setAlphaComponent(color, 180),
                        ColorUtils.setAlphaComponent(color, 80),
                        ColorUtils.setAlphaComponent(color, 0),
                    ),
                    floatArrayOf(0f, 0.4f, 1f),
                    Shader.TileMode.CLAMP,
                )
        }
        val save = canvas.save()
        canvas.translate(x, y)
        canvas.drawCircle(0f, 0f, THUMB_GLOW_RADIUS_DP * density, glowPaint)
        val thumbRadius = (if (isScrubbing || isPressed) 8.5f else 7f) * density
        thumbPaint.color = color
        canvas.drawCircle(0f, 0f, thumbRadius, thumbPaint)
        canvas.drawCircle(0f, 0f, thumbRadius, ringPaint)
        canvas.restoreToCount(save)
    }

    companion object {
        private const val THUMB_GLOW_RADIUS_DP = 14f
        private val artworkExecutor = Executors.newSingleThreadExecutor()
        private val artworkColorCache = WeakHashMap<Drawable, List<Int>>()

        private fun vibrantColor(color: Int): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[1] = hsl[1].coerceAtLeast(0.65f)
            hsl[2] = hsl[2].coerceIn(0.5f, 0.65f)
            return ColorUtils.HSLToColor(hsl)
        }

        private fun extractArtworkColors(drawable: Drawable): List<Int> {
            artworkColorCache[drawable]?.let {
                return it
            }
            val colors =
                runCatching {
                        val copy =
                            drawable.constantState?.newDrawable()?.mutate()
                                ?: return@runCatching emptyList<Int>()
                        val palette = WallpaperColors.fromDrawable(copy)
                        listOfNotNull(
                                palette.primaryColor,
                                palette.secondaryColor,
                                palette.tertiaryColor,
                            )
                            .map {
                                val hsl = FloatArray(3)
                                ColorUtils.colorToHSL(it.toArgb(), hsl)
                                hsl[2] = hsl[2].coerceIn(0.5f, 0.72f)
                                ColorUtils.HSLToColor(hsl)
                            }
                    }
                    .getOrDefault(emptyList())
            artworkColorCache[drawable] = colors
            return colors
        }
    }
}
