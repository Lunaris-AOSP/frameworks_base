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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class GlowingSeekBar
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle,
) : SeekBar(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 242 }
    private val glowShader = RuntimeShader(GLOW_SHADER)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glowShader }
    private val gradientMatrix = Matrix()
    private val progressPath = Path()
    private var progressGradient: LinearGradient? = null
    private var accent = context.getColor(android.R.color.system_accent1_400)
    private var fallbackColor: Int? = null
    private var artworkColor: Int? = null
    private var resolvedColor: Int? = null
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
        updateColors()
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
        artworkColor = null
        updateColors()
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
        val color = ColorUtils.setAlphaComponent(artworkColor ?: fallbackColor ?: accent, 255)
        if (resolvedColor == color) return
        resolvedColor = color
        progressGradient =
            LinearGradient(
                0f,
                0f,
                1f,
                0f,
                intArrayOf(
                    color,
                    ColorUtils.blendARGB(color, Color.WHITE, 0.2f),
                    ColorUtils.blendARGB(color, Color.WHITE, 0.65f),
                    Color.WHITE,
                ),
                floatArrayOf(0f, 0.4f, 0.8f, 1f),
                Shader.TileMode.CLAMP,
            )
        progressPaint.shader = progressGradient
        glowShader.setColorUniform("artworkColor", color)
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
        loadArtworkColor()
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
        val head = left + trackWidth * fraction
        canvas.drawRoundRect(
            left,
            centerY - radius,
            left + trackWidth,
            centerY + radius,
            radius,
            radius,
            trackPaint,
        )

        if (head > left) {
            val tailLength = min(60f * density, head - left)
            val trailScale = sqrt(tailLength / (80f * density))
            val glowHeight = min((15f * density * trailScale).coerceAtLeast(radius), centerY)
            val tipRadius = radius * 0.8f
            val glowAlpha = 0.85f + liveness * (0.05f + 0.1f * sin(phase))
            glowShader.setFloatUniform("head", head, centerY)
            glowShader.setFloatUniform("tailLength", tailLength)
            glowShader.setFloatUniform("tipRadius", tipRadius)
            glowShader.setFloatUniform("tailRadius", glowHeight)
            glowShader.setFloatUniform("phase", phase)
            glowShader.setFloatUniform("motion", if (animate) liveness else 0f)
            glowPaint.alpha = (255f * glowAlpha).toInt()
            canvas.drawRect(
                head - tailLength,
                centerY - glowHeight,
                head,
                centerY + glowHeight,
                glowPaint,
            )

            val gradientLength = min(80f * density, head - left)
            gradientMatrix.setScale(gradientLength, 1f)
            gradientMatrix.postTranslate(head - gradientLength, 0f)
            progressGradient?.setLocalMatrix(gradientMatrix)
            drawProgress(canvas, left, head, centerY, radius, tipRadius)
        }

        if (
            isAttachedToWindow &&
                aggregatedVisible &&
                animate &&
                (playing || fadeAnimator?.isStarted == true || shownProgress != target)
        ) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawProgress(
        canvas: Canvas,
        left: Float,
        head: Float,
        centerY: Float,
        radius: Float,
        tipRadius: Float,
    ) {
        val length = head - left
        if (length <= 4f * radius) {
            canvas.drawRoundRect(
                left,
                centerY - radius,
                head,
                centerY + radius,
                radius,
                radius,
                progressPaint,
            )
            return
        }
        val taperStart = (head - min(48f * density, length * 0.75f)).coerceAtLeast(left + radius)
        val control = taperStart + (head - tipRadius - taperStart) * 0.5f
        progressPath.rewind()
        progressPath.moveTo(left + radius, centerY - radius)
        progressPath.lineTo(taperStart, centerY - radius)
        progressPath.cubicTo(
            control,
            centerY - radius,
            head - 2f * tipRadius,
            centerY - tipRadius,
            head - tipRadius,
            centerY - tipRadius,
        )
        progressPath.arcTo(
            head - 2f * tipRadius,
            centerY - tipRadius,
            head,
            centerY + tipRadius,
            -90f,
            180f,
            false,
        )
        progressPath.cubicTo(
            head - 2f * tipRadius,
            centerY + tipRadius,
            control,
            centerY + radius,
            taperStart,
            centerY + radius,
        )
        progressPath.lineTo(left + radius, centerY + radius)
        progressPath.arcTo(
            left,
            centerY - radius,
            left + 2f * radius,
            centerY + radius,
            90f,
            180f,
            false,
        )
        progressPath.close()
        canvas.drawPath(progressPath, progressPaint)
    }

    companion object {
        private val artworkExecutor = Executors.newSingleThreadExecutor()
        private val artworkColorCache = WeakHashMap<Drawable, Int?>()

        private fun extractArtworkColor(drawable: Drawable): Int? {
            if (artworkColorCache.containsKey(drawable)) return artworkColorCache[drawable]
            val color =
                runCatching {
                        val copy =
                            drawable.constantState?.newDrawable()?.mutate()
                                ?: return@runCatching null
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
                    }
                    .getOrNull()
            artworkColorCache[drawable] = color
            return color
        }

        private const val GLOW_SHADER =
            """
            uniform float2 head;
            uniform float tailLength;
            uniform float tipRadius;
            uniform float tailRadius;
            uniform float phase;
            uniform float motion;
            layout(color) uniform half4 artworkColor;

            half4 main(float2 p) {
                float rear = clamp((head.x - p.x) / tailLength, 0.0, 1.0);
                // Waves travel away from the seek position into the fading wake.
                // Integer phase multiples keep the animation seamless when phase wraps.
                float wave = sin(rear * 16.0 - phase * 3.0);
                float spread = mix(tipRadius, tailRadius, rear)
                    * (1.0 + 0.12 * motion * wave);
                float drift = tailRadius * 0.12 * motion * rear
                    * sin(rear * 10.0 - phase * 2.0);
                float vertical = (p.y - head.y - drift) / spread;
                float fade = 1.0 - rear;
                float envelope = smoothstep(0.0, 0.1, rear)
                    * (1.0 - smoothstep(0.6, 1.0, rear))
                    * (1.0 - smoothstep(0.85, 1.0, abs(p.y - head.y) / tailRadius));
                float haze = 0.85 * exp(-1.8 * vertical * vertical)
                    * (1.0 - motion * (0.2 - 0.15 * wave));

                // Staggered, elongated sparks stream backward and fade before wrapping.
                float streaks = 0.0;
                for (int i = 0; i < 3; i++) {
                    float offset = float(i) / 3.0;
                    float age = fract(phase / 3.14159265 + offset);
                    float lane = (float(i) - 1.0) * 0.55
                        + 0.1 * rear * sin(rear * 14.0 - phase * 4.0 + float(i) * 2.0);
                    float along = (rear - age) / 0.14;
                    float across = (vertical - lane) / 0.12;
                    float life = smoothstep(0.0, 0.12, age)
                        * (1.0 - smoothstep(0.65, 1.0, age));
                    streaks += exp(-along * along - across * across) * life;
                }
                float heat = motion * streaks;
                float alpha = clamp(envelope * (haze + 0.8 * heat), 0.0, 1.0);
                half3 color = mix(artworkColor.rgb, half3(1.0),
                    half(clamp(fade * fade * fade + 0.65 * heat, 0.0, 1.0)));
                return half4(color * alpha, alpha);
            }
        """
    }
}
