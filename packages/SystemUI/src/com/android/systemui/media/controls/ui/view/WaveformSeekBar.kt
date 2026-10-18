/*
 * Copyright (C) 2025-2026 AxionOS
 * Copyright (C) 2026 Lunaris AOSP
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
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import com.android.systemui.media.MediaSessionManager
import kotlin.math.*

class WaveformSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle,
) : SeekBar(context, attrs, defStyleAttr), MediaSessionManager.MediaDataListener {

    private val density = resources.displayMetrics.density
    
    private val progressPath = Path()
    private val backgroundRect = RectF()
    private val progressRect = RectF()
    
    private val trackHeight = 6f * density
    private val cornerRadius = 8f * density
    private val thumbRadius = 8f * density
    
    private val waveHeight = 12f * density
    private val waveLength = 80f * density
    private val minWaveTrackLength = waveLength * 1.0f
    
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 77
    }
    
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    
    private val thumbShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
        alpha = 60
    }
    
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        setShadowLayer(4f * density, 0f, 2f * density, Color.argb(80, 0, 0, 0))
    }
    
    private var wavePhase = 0f
    private var waveAmplitudeMultiplier = 0f
    private var waveAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null
    private var aggregatedVisible = false
    private var wavePoints = FloatArray(0)
    var isPlaying = false
        private set
    var followsMediaColors = true
    
    init {
        thumb = TransparentDrawable()
        splitTrack = false
        progressDrawable = TransparentDrawable()
    }
    
    fun startWaveAnimation() {
        isPlaying = true
        syncWaveAnimation()
    }

    private fun syncWaveAnimation() {
        if (!isPlaying || !isAttachedToWindow || !aggregatedVisible) {
            stopWaveAnimation(animate = false)
            return
        }
        if (waveAnimator?.isRunning == true) return

        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(waveAmplitudeMultiplier, 1f).apply {
            duration = 300L
            addUpdateListener { 
                waveAmplitudeMultiplier = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        
        waveAnimator?.cancel()
        waveAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 3500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { 
                wavePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    
    fun stopWaveAnimation() {
        val wasPlaying = isPlaying
        isPlaying = false
        if (!wasPlaying && isAttachedToWindow && aggregatedVisible) return
        stopWaveAnimation(animate = isAttachedToWindow && aggregatedVisible)
    }

    private fun stopWaveAnimation(animate: Boolean) {
        waveAnimator?.cancel()
        waveAnimator = null
        fadeAnimator?.cancel()
        fadeAnimator = null
        if (!animate || waveAmplitudeMultiplier == 0f) {
            waveAmplitudeMultiplier = 0f
            postInvalidateOnAnimation()
            return
        }
        fadeAnimator = ValueAnimator.ofFloat(waveAmplitudeMultiplier, 0f).apply {
            duration = 300L
            addUpdateListener { 
                waveAmplitudeMultiplier = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    
    fun regenerateWaveform(seed: Long = System.currentTimeMillis()) {
        invalidate()
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (followsMediaColors) MediaSessionManager.get().addListener(this)
        syncWaveAnimation()
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        MediaSessionManager.get().removeListener(this)
        aggregatedVisible = false
        stopWaveAnimation(animate = false)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        syncWaveAnimation()
    }
    
    override fun onMediaColorsChanged(color: Int) {
        if (followsMediaColors) {
            post { if (followsMediaColors && isAttachedToWindow) setWaveformColor(color) }
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        val pLeft = paddingLeft.toFloat()
        val pRight = paddingRight.toFloat()
        
        val drawWidth = width - pLeft - pRight
        if (drawWidth <= 0) return
        
        val centerY = height / 2f
        val trackTop = centerY - trackHeight / 2f
        val trackBottom = centerY + trackHeight / 2f
        
        val ratio = if (max > 0) progress.toFloat() / max else 0f
        val progressX = pLeft + drawWidth * ratio
        
        backgroundRect.set(pLeft, trackTop, pLeft + drawWidth, trackBottom)
        canvas.drawRoundRect(backgroundRect, cornerRadius, cornerRadius, backgroundPaint)
        
        if (progressX > pLeft) {
            if (waveAmplitudeMultiplier > 0.01f) {
                drawWaveProgress(canvas, pLeft, progressX, centerY, trackTop, trackBottom)
            } else {
                progressRect.set(pLeft, trackTop, progressX, trackBottom)
                canvas.drawRoundRect(progressRect, cornerRadius, cornerRadius, progressPaint)
            }
        }
        canvas.drawCircle(progressX, centerY + 2 * density, thumbRadius, thumbShadowPaint)
        canvas.drawCircle(progressX, centerY, thumbRadius, thumbPaint)
    }
    
    private fun drawWaveProgress(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        centerY: Float,
        trackTop: Float,
        trackBottom: Float
    ) {
        progressPath.reset()
        
        val progressLength = endX - startX
        
        val minLength = waveLength * 0.8f
        val lengthRatio = (progressLength / minLength).coerceIn(0f, 1f)
        val progressLengthFactor = (sqrt(lengthRatio) * 0.7f + 0.3f).coerceIn(0.3f, 1f)
        
        val edgeRadius = (trackHeight / 2f).coerceAtMost(cornerRadius)
        
        progressPath.moveTo(startX + edgeRadius, trackBottom)
        
        progressPath.arcTo(
            startX, trackTop,
            startX + edgeRadius * 2, trackBottom,
            90f, 90f, false
        )
        
        val waveStartX = startX + edgeRadius
        val firstWaveY = calculateWaveY(waveStartX, waveStartX, endX, trackTop, progressLengthFactor)
        progressPath.quadTo(startX, trackTop, waveStartX, firstWaveY)
        
        drawSmoothWave(waveStartX, endX, trackTop, progressLengthFactor)
        
        progressPath.lineTo(endX, trackTop)
        progressPath.lineTo(endX, trackBottom)
        progressPath.close()
        
        canvas.drawPath(progressPath, progressPaint)
    }
    
    private fun calculateWaveY(
        x: Float,
        waveStartX: Float,
        waveEndX: Float,
        trackTop: Float,
        progressLengthFactor: Float
    ): Float {
        val totalDist = waveEndX - waveStartX
        if (totalDist <= 0) return trackTop
        
        val waveProgress = (x - waveStartX) / waveLength
        val sinValue = sin(waveProgress * 2 * PI.toFloat() + wavePhase)
        
        val normalizedWave = (sinValue + 1f) / 2f
        
        val distFromStart = x - waveStartX
        val distFromEnd = waveEndX - x
        val taperZone = waveLength * 0.4f
        
        val envelope = when {
            totalDist < taperZone * 2 -> {
                val t = distFromStart / totalDist
                4f * t * (1f - t)
            }
            distFromStart < taperZone -> {
                val t = distFromStart / taperZone
                t * t * (3f - 2f * t)
            }
            distFromEnd < taperZone -> {
                val t = distFromEnd / taperZone
                t * t * (3f - 2f * t)
            }
            else -> 1f
        }.coerceIn(0f, 1f)
        
        return trackTop - (normalizedWave * waveHeight * envelope * waveAmplitudeMultiplier * progressLengthFactor)
    }
    
    private fun drawSmoothWave(
        startX: Float,
        endX: Float,
        trackTop: Float,
        progressLengthFactor: Float
    ) {
        if (endX <= startX) return
        
        val step = 2f * density
        val pointCount = max(2, ceil((endX - startX) / step).toInt() + 1)
        if (wavePoints.size < pointCount * 2) {
            wavePoints = FloatArray(pointCount * 2)
        }
        for (i in 0 until pointCount) {
            val x = min(startX + i * step, endX)
            wavePoints[i * 2] = x
            wavePoints[i * 2 + 1] = calculateWaveY(x, startX, endX, trackTop, progressLengthFactor)
        }

        for (i in 0 until pointCount - 1) {
            val p0 = (i - 1).coerceAtLeast(0) * 2
            val p1 = i * 2
            val p2 = (i + 1) * 2
            val p3 = (i + 2).coerceAtMost(pointCount - 1) * 2

            val tension = 0.5f
            val cp1x = wavePoints[p1] + (wavePoints[p2] - wavePoints[p0]) * tension / 3f
            val cp1y = wavePoints[p1 + 1] + (wavePoints[p2 + 1] - wavePoints[p0 + 1]) * tension / 3f
            val cp2x = wavePoints[p2] - (wavePoints[p3] - wavePoints[p1]) * tension / 3f
            val cp2y = wavePoints[p2 + 1] - (wavePoints[p3 + 1] - wavePoints[p1 + 1]) * tension / 3f

            progressPath.cubicTo(cp1x, cp1y, cp2x, cp2y, wavePoints[p2], wavePoints[p2 + 1])
        }
    }

    
    fun setWaveformColor(color: Int) {
        progressPaint.color = color
        backgroundPaint.color = color
        backgroundPaint.alpha = 77
        invalidate()
    }
    
    fun setThumbColor(color: Int) {
        thumbPaint.color = color
        thumbPaint.setShadowLayer(4f * density, 0f, 2f * density, Color.argb(80, 0, 0, 0))
        invalidate()
    }
    
    private class TransparentDrawable : Drawable() {
        override fun draw(canvas: Canvas) {}
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.TRANSPARENT
    }
}
