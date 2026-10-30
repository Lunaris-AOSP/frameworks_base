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
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class GlowingSeekBar
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
    private val gradientMatrix = Matrix()
    private val waveMatrix = Matrix()
    private val glowMatrix = Matrix()
    private val wavePath = Path()
    private var wavePoints = FloatArray(0)
    private var progressGradient: LinearGradient? = null
    private var glowGradient: RadialGradient? = null
    private var waveColors = IntArray(4)
    private val waveGradients = arrayOfNulls<LinearGradient>(4)
    private var accent = context.getColor(android.R.color.system_accent1_400)
    private var fallbackColor: Int? = null
    private var artworkColors: ArtworkColors? = null
    private var resolvedColors: ArtworkColors? = null
    private var artwork: Drawable? = null
    @Volatile private var artworkGeneration = 0
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
        val inset = ceil(THUMB_GLOW_RADIUS_DP * resources.displayMetrics.density).toInt()
        super.setPadding(maxOf(left, inset), top, maxOf(right, inset), bottom)
    }

    fun setBarColor(color: Int) {
        if (fallbackColor == color) return
        fallbackColor = color
        updateColors()
    }

    fun setArtwork(drawable: Drawable?) {
        if (artwork === drawable) return
        artwork = drawable
        artworkGeneration++
        artworkColors = null
        updateColors()
        if (isAttachedToWindow) loadArtworkColors()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        accent = context.getColor(android.R.color.system_accent1_400)
        updateColors()
    }

    private fun updateColors() {
        val colors =
            artworkColors
                ?: run {
                    val hsl = FloatArray(3)
                    val fallback = fallbackColor ?: accent
                    ColorUtils.colorToHSL(fallback, hsl)
                    val color = vibrantColor(if (hsl[1] < 0.12f) accent else fallback)
                    ArtworkColors(List(4) { color }, color)
                }
        if (resolvedColors == colors) return
        resolvedColors = colors
        val color = colors.accent
        val multicolor = colors.waves.distinct().size > 1
        waveColors = colors.waves.toIntArray()
        for (layer in waveColors.indices) {
            val waveColor = waveColors[layer]
            waveGradients[layer] =
                LinearGradient(
                    0f,
                    0f,
                    0f,
                    1f,
                    intArrayOf(
                        ColorUtils.setAlphaComponent(waveColor, if (layer == 3) 150 else 205),
                        ColorUtils.setAlphaComponent(waveColor, if (layer == 3) 120 else 175),
                        ColorUtils.setAlphaComponent(waveColor, if (layer == 3) 45 else 80),
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
        }
        val progressColor = if (multicolor) Color.WHITE else color
        progressGradient =
            LinearGradient(
                0f,
                0f,
                1f,
                0f,
                intArrayOf(
                    ColorUtils.setAlphaComponent(progressColor, 110),
                    progressColor,
                    progressColor,
                ),
                floatArrayOf(0f, 0.12f, 1f),
                Shader.TileMode.CLAMP,
            )
        progressPaint.shader = progressGradient
        thumbPaint.color = ColorUtils.blendARGB(color, Color.WHITE, 0.08f)
        glowGradient =
            RadialGradient(
                0f,
                0f,
                1f,
                intArrayOf(
                    ColorUtils.setAlphaComponent(color, 210),
                    ColorUtils.setAlphaComponent(color, 145),
                    ColorUtils.setAlphaComponent(color, 48),
                    ColorUtils.setAlphaComponent(color, 0),
                ),
                floatArrayOf(0f, 0.28f, 0.6f, 1f),
                Shader.TileMode.CLAMP,
            )
        glowPaint.shader = glowGradient
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
            liveness = target
            invalidate()
            return
        }
        if (liveness != target) {
            fadeAnimator =
                ValueAnimator.ofFloat(liveness, target).apply {
                    duration = if (isScrubbing) 180L else 450L
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
        loadArtworkColors()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        artworkGeneration++
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
        val contentHeight = (height - paddingTop - paddingBottom).toFloat()
        if (trackWidth <= 0f || contentHeight <= 0f) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1000f, 0.05f)
        lastFrame = now
        val animate =
            isAttachedToWindow &&
                aggregatedVisible &&
                animationsAllowed &&
                ValueAnimator.areAnimatorsEnabled()
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
        if (animate && !dragged && (playing || liveness > 0f)) {
            phase = (phase + 2f * PI.toFloat() * dt / 3.5f) % (2f * PI.toFloat())
        }
        val centerY = paddingTop + contentHeight / 2f
        val verticalSpace = min(centerY, height - centerY)
        val radius = min((if (dragged) 1.8f else 1.4f) * density, verticalSpace)
        val left = paddingLeft.toFloat()
        val right = left + trackWidth
        val fraction = if (max > min) ((shownProgress - min) / (max - min)).coerceIn(0f, 1f) else 0f
        val head = left + trackWidth * fraction
        val thumbAlpha = if (isEnabled) thumb?.alpha ?: 0 else 0
        val thumbRadius =
            min(
                (if (dragged) 8f else 6.5f) * density,
                (verticalSpace - ringPaint.strokeWidth / 2f).coerceAtLeast(0f),
            )
        val trackStart =
            if (thumbAlpha > 0) head + thumbRadius + ringPaint.strokeWidth / 2f + 4f * density
            else head
        if (trackStart < right) {
            canvas.drawRoundRect(
                trackStart,
                centerY - radius,
                right,
                centerY + radius,
                radius,
                radius,
                trackPaint,
            )
        }

        if (head > left) {
            if (liveness > 0f) {
                val waveEnd = head - if (thumbAlpha > 0) thumbRadius else 0f
                for (layer in waveColors.indices) {
                    drawWave(canvas, layer, left, waveEnd, centerY - radius)
                }
            }
            gradientMatrix.setScale(head - left, 1f)
            gradientMatrix.postTranslate(left, 0f)
            progressGradient?.setLocalMatrix(gradientMatrix)
            canvas.drawRoundRect(
                left,
                centerY - radius,
                head,
                centerY + radius,
                radius,
                radius,
                progressPaint,
            )
        }

        if (thumbAlpha > 0 && thumbRadius > 0f) {
            drawThumb(canvas, head, centerY, thumbRadius, thumbAlpha)
        }
        if (
            animate &&
                ((playing && !dragged) ||
                    fadeAnimator?.isStarted == true ||
                    shownProgress != target)
        ) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawWave(canvas: Canvas, layer: Int, left: Float, right: Float, baseline: Float) {
        val length = right - left
        if (length <= 0f) return
        val waveHeight =
            min(WAVE_HEIGHT_DP * density, (baseline - density).coerceAtLeast(0f)) *
                (length / (80f * density)).coerceAtMost(1f) *
                liveness *
                WAVE_SCALES[layer]
        if (waveHeight <= 0f) return
        val offset = layer * 1.15f
        val waveLength = (100f + layer * 18f) * density
        val steps = (length / (2f * density)).roundToInt().coerceIn(16, 192)
        val pointCount = steps + 1
        if (wavePoints.size < pointCount * 2) wavePoints = FloatArray(pointCount * 2)

        for (step in 0..steps) {
            val position = step.toFloat() / steps
            val envelope = 4f * position * (1f - position)
            val angle = length * position / waveLength * 2f * PI.toFloat()
            val swell = 0.5f + 0.5f * sin(angle + phase + offset)
            val ripple = 0.5f + 0.5f * sin(angle * 1.7f + phase * 2f + offset)
            val rise = waveHeight * envelope * envelope * (0.12f + 0.72f * swell + 0.16f * ripple)
            wavePoints[step * 2] = left + length * position
            wavePoints[step * 2 + 1] = baseline - rise
        }
        wavePath.rewind()
        wavePath.moveTo(left, baseline)
        for (point in 0 until steps) {
            val p0 = (point - 1).coerceAtLeast(0) * 2
            val p1 = point * 2
            val p2 = (point + 1) * 2
            val p3 = (point + 2).coerceAtMost(steps) * 2
            val cp1x = wavePoints[p1] + (wavePoints[p2] - wavePoints[p0]) / 6f
            val cp1y = wavePoints[p1 + 1] + (wavePoints[p2 + 1] - wavePoints[p0 + 1]) / 6f
            val cp2x = wavePoints[p2] - (wavePoints[p3] - wavePoints[p1]) / 6f
            val cp2y = wavePoints[p2 + 1] - (wavePoints[p3 + 1] - wavePoints[p1 + 1]) / 6f
            wavePath.cubicTo(
                cp1x,
                cp1y.coerceIn(baseline - waveHeight, baseline),
                cp2x,
                cp2y.coerceIn(baseline - waveHeight, baseline),
                wavePoints[p2],
                wavePoints[p2 + 1],
            )
        }
        wavePath.lineTo(right, baseline)
        wavePath.close()
        waveMatrix.setScale(1f, waveHeight)
        waveMatrix.postTranslate(0f, baseline - waveHeight)
        waveGradients[layer]?.setLocalMatrix(waveMatrix)
        wavePaint.shader = waveGradients[layer]
        wavePaint.alpha = (255f * liveness).roundToInt()
        canvas.drawPath(wavePath, wavePaint)
    }

    private fun drawThumb(canvas: Canvas, x: Float, y: Float, radius: Float, alpha: Int) {
        val glowRadius = THUMB_GLOW_RADIUS_DP * density
        val glowHeight = min(glowRadius, min(y, height - y))
        glowMatrix.setScale(glowRadius, glowHeight)
        glowMatrix.postTranslate(x, y)
        glowGradient?.setLocalMatrix(glowMatrix)
        glowPaint.alpha = alpha
        canvas.drawOval(x - glowRadius, y - glowHeight, x + glowRadius, y + glowHeight, glowPaint)
        thumbPaint.alpha = alpha
        ringPaint.alpha = alpha
        canvas.drawCircle(x, y, radius, thumbPaint)
        canvas.drawCircle(x, y, radius, ringPaint)
    }

    private data class ArtworkColors(val waves: List<Int>, val accent: Int)

    companion object {
        private const val THUMB_GLOW_RADIUS_DP = 24f
        private const val WAVE_HEIGHT_DP = 14f
        private const val ARTWORK_SAMPLE_SIZE = 96
        private val WAVE_SCALES = floatArrayOf(1f, 0.82f, 0.64f, 0.25f)
        private val artworkExecutor = Executors.newSingleThreadExecutor()
        private val artworkColorCache = WeakHashMap<Drawable, ArtworkColors?>()

        private fun vibrantColor(color: Int): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            hsl[1] = hsl[1].coerceAtLeast(0.78f)
            hsl[2] = hsl[2].coerceIn(0.53f, 0.62f)
            return ColorUtils.HSLToColor(hsl)
        }

        private fun extractArtworkColors(drawable: Drawable): ArtworkColors? {
            if (artworkColorCache.containsKey(drawable)) return artworkColorCache[drawable]
            val colors =
                runCatching {
                        val copy =
                            drawable.constantState?.newDrawable()?.mutate()
                                ?: return@runCatching null
                        val bitmap =
                            Bitmap.createBitmap(
                                ARTWORK_SAMPLE_SIZE,
                                ARTWORK_SAMPLE_SIZE,
                                Bitmap.Config.ARGB_8888,
                            )
                        try {
                            copy.setBounds(0, 0, bitmap.width, bitmap.height)
                            copy.draw(Canvas(bitmap))
                            val color = artworkColor(WallpaperColors.fromBitmap(bitmap))
                            val regionSize = ARTWORK_SAMPLE_SIZE / 2
                            val waves =
                                List(4) { region ->
                                    val part =
                                        Bitmap.createBitmap(
                                            bitmap,
                                            region % 2 * regionSize,
                                            region / 2 * regionSize,
                                            regionSize,
                                            regionSize,
                                        )
                                    try {
                                        artworkColor(WallpaperColors.fromBitmap(part))
                                    } finally {
                                        part.recycle()
                                    }
                                }
                            ArtworkColors(waves, color)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    .getOrNull()
            artworkColorCache[drawable] = colors
            return colors
        }

        private fun artworkColor(palette: WallpaperColors): Int {
            val candidates =
                listOfNotNull(palette.primaryColor, palette.secondaryColor, palette.tertiaryColor)
            val hsl = FloatArray(3)
            val color =
                candidates.firstOrNull {
                    ColorUtils.colorToHSL(it.toArgb(), hsl)
                    hsl[1] >= 0.18f && hsl[2] in 0.1f..0.9f
                } ?: candidates.firstOrNull()
            return ColorUtils.setAlphaComponent(color?.toArgb() ?: Color.BLACK, 255)
        }
    }
}
