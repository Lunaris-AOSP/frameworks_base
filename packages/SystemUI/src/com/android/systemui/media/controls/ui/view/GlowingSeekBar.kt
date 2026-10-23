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
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.SeekBar
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class GlowingSeekBar
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle,
) : SeekBar(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val glowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader =
                RadialGradient(
                    0f,
                    0f,
                    1f,
                    intArrayOf(0xCCFFFFFF.toInt(), 0x66FFFFFF, 0x1AFFFFFF, 0x00FFFFFF),
                    floatArrayOf(0f, 0.3f, 0.65f, 1f),
                    Shader.TileMode.CLAMP,
                )
        }
    private var playing = false
    private var animationsAllowed = true
    private var aggregatedVisible = false
    private var liveness = 0f
    private var phase = 0f
    private var lastFrame = 0L
    private var shownProgress = Float.NaN
    private var lastMaximum = 0
    private var fadeAnimator: ValueAnimator? = null

    var isScrubbing = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        thumb = ColorDrawable(Color.TRANSPARENT)
        progressDrawable = ColorDrawable(Color.TRANSPARENT)
        background = null
        splitTrack = false
        thumbOffset = 0
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(0, 0, 0, 0)
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
        if (
            !isAttachedToWindow ||
                !aggregatedVisible ||
                !animationsAllowed ||
                !ValueAnimator.areAnimatorsEnabled()
        ) {
            liveness = if (playing && animationsAllowed) 1f else 0f
            invalidate()
            return
        }
        val target = if (playing) 1f else 0f
        if (liveness != target) {
            fadeAnimator =
                ValueAnimator.ofFloat(liveness, target).apply {
                    duration = 600L
                    startDelay = if (playing) 0L else 700L
                    setInterpolator { fraction ->
                        if (fraction < 0.5f) 4f * fraction * fraction * fraction
                        else 1f - (2f - 2f * fraction).let { it * it * it } / 2f
                    }
                    addUpdateListener {
                        liveness = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        fadeAnimator?.cancel()
        fadeAnimator = null
        aggregatedVisible = false
        lastFrame = 0L
        shownProgress = Float.NaN
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        updateAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val trackWidth = (width - paddingLeft - paddingRight).toFloat()
        if (trackWidth <= 0f || height <= 0) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1000f, 0.05f)
        lastFrame = now
        val animate = animationsAllowed && ValueAnimator.areAnimatorsEnabled()
        val dragged = isScrubbing || isPressed
        val target = progress.toFloat()
        if (
            !shownProgress.isFinite() ||
                lastMaximum != max ||
                dragged ||
                !animate ||
                !playing ||
                abs(shownProgress - target) >= 2000f
        ) {
            shownProgress = target
        } else {
            shownProgress += (target - shownProgress) * (1f - exp(-dt / 0.18f))
            if (abs(shownProgress - target) < 1f) shownProgress = target
        }
        lastMaximum = max
        if (animate && (playing || liveness > 0f)) {
            phase =
                (phase + 2.3809524f * dt * (0.15f + 0.85f * liveness)) % (2f * Math.PI.toFloat())
        }
        val height = (if (dragged) 7f else 3.5f) * density
        val radius = height / 2f
        val centerY = this.height / 2f
        val left = paddingLeft.toFloat()
        val fraction = if (max > min) ((shownProgress - min) / (max - min)).coerceIn(0f, 1f) else 0f
        val head = left + max(2.4f * height, trackWidth * fraction).coerceAtMost(trackWidth)
        paint.alpha = 51
        canvas.drawRoundRect(
            left,
            centerY - radius,
            left + trackWidth,
            centerY + radius,
            radius,
            radius,
            paint,
        )

        val glowHead = head - min(0.6f * height, (head - left) / 2f)
        val tailLength = min(90f * density, glowHead - left)
        val glowHeight = min(16f * density, centerY)
        val glowAlpha = 0.8f + liveness * (0.05f + 0.15f * sin(phase))
        glowPaint.alpha = (255f * 0.72f * glowAlpha).toInt()
        drawGlow(canvas, glowHead, centerY, tailLength, 12f * density, glowHeight)
        glowPaint.alpha = (255f * 0.9f * glowAlpha).toInt()
        drawGlow(
            canvas,
            glowHead,
            centerY,
            tailLength * 0.8f,
            4f * density,
            min(1.2f * height, glowHeight),
        )
        paint.alpha = 242
        canvas.drawRoundRect(left, centerY - radius, head, centerY + radius, radius, radius, paint)

        if (
            isAttachedToWindow &&
                aggregatedVisible &&
                animate &&
                (playing || fadeAnimator?.isStarted == true || shownProgress != target)
        ) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawGlow(
        canvas: Canvas,
        head: Float,
        centerY: Float,
        tailLength: Float,
        headLength: Float,
        radius: Float,
    ) {
        // Stretch the rear half of the falloff into a trail, keeping the leading edge compact.
        val tailSave = canvas.save()
        canvas.translate(head, centerY)
        canvas.scale(tailLength, radius)
        canvas.clipRect(-1f, -1f, 0f, 1f)
        canvas.drawCircle(0f, 0f, 1f, glowPaint)
        canvas.restoreToCount(tailSave)

        val headSave = canvas.save()
        canvas.translate(head, centerY)
        canvas.scale(headLength, radius)
        canvas.clipRect(0f, -1f, 1f, 1f)
        canvas.drawCircle(0f, 0f, 1f, glowPaint)
        canvas.restoreToCount(headSave)
    }
}
