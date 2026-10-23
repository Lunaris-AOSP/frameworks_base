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

package com.android.systemui.statusbar.featurepods.media.ui.view

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal class MediaArtworkEffects(private val resources: Resources) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val camera = Camera()
    private val matrix = Matrix()
    private val phase = FloatArray(3)
    private val rates = floatArrayOf(.55f, .8f, .65f)
    private val lanes = floatArrayOf(.16f, .52f, .86f)
    private val radiiX = floatArrayOf(.5f, .42f, .5f)
    private val radiiY = floatArrayOf(.46f, .34f, .44f)
    private val peaks = floatArrayOf(.95f, .55f, .9f)
    private val blobs = arrayOfNulls<Shader>(3)
    private var base: Shader? = null
    private var shaderAccent = 0
    private var shaderHeight = 0f
    private var artwork: Drawable? = null
    private var previousArtwork: Drawable? = null
    private var blurred: Drawable? = null
    private var previousBlurred: Drawable? = null
    private var artworkKey: Any? = null
    private var trackKey: String? = null
    private var primed = false
    private var flipStart = -340L
    private var waveStart = -1150L
    private var accentStart = -500L
    private var accentFrom = Color.WHITE
    private var accentTo = Color.WHITE
    private var playing = false
    private var pauseStart = 0L
    private var liveFrom = 1f
    private var liveStart = 0L
    private var lastFrame = 0L

    fun bind(image: Drawable?, key: String, isPlaying: Boolean, now: Long) {
        val imageKey = (image as? BitmapDrawable)?.bitmap ?: image
        if (!primed || imageKey !== artworkKey || key != trackKey) {
            val changed = primed && artwork != null && image != null
            previousArtwork = if (changed) artwork else null
            previousBlurred = if (changed) blurred else null
            flipStart = if (changed) now else now - 340L
            waveStart = if (changed) now else now - 1150L
            artwork = image
            artworkKey = imageKey
            trackKey = key
            val pixels = sample(image)
            blurred = pixels?.let { blur(it) }
            accentFrom = if (primed) accent(now) else vibrant(pixels)
            accentTo = vibrant(pixels)
            accentStart = if (primed) now else now - 500L
        }
        if (!primed || playing != isPlaying) {
            liveFrom = if (primed) liveness(now) else if (isPlaying) 1f else 0f
            liveStart = now
            pauseStart = if (isPlaying) 0L else now
            playing = isPlaying
        }
        primed = true
    }

    fun resetFrameClock() {
        lastFrame = 0L
    }

    private fun liveness(now: Long): Float {
        val start = if (playing) liveStart else pauseStart + 700L
        val t = ease(((now - start) / 600f).coerceIn(0f, 1f))
        return liveFrom + ((if (playing) 1f else 0f) - liveFrom) * t
    }

    private fun accent(now: Long) =
        blend(accentFrom, accentTo, ease(((now - accentStart) / 500f).coerceIn(0f, 1f)))

    fun drawBackground(canvas: Canvas, width: Float, height: Float, now: Long) {
        val animate = ValueAnimator.areAnimatorsEnabled()
        val dt =
            if (lastFrame == 0L || !animate) 0f else ((now - lastFrame) / 1000f).coerceAtMost(.05f)
        lastFrame = now
        val live = liveness(now)
        val glow = .72f + .28f * live
        val tone = accent(now)
        if (shaderAccent != tone || shaderHeight != height) buildShaders(tone, height)
        paint.shader = base
        paint.alpha = (255 * glow).roundToInt()
        canvas.drawRect(0f, height * .4f, width, height, paint)
        for (i in phase.indices) {
            phase[i] = (phase[i] + rates[i] * dt * (.15f + .85f * live)) % (2f * Math.PI.toFloat())
            val p = phase[i]
            val breathe = sin(3f * p + i * 2f) * .18f + .82f
            val save = canvas.save()
            canvas.translate(
                (lanes[i] + sin(p) * .12f) * width,
                height * (.95f - sin(p * 2 + i) * .07f),
            )
            canvas.scale(radiiX[i] * width * breathe, radiiY[i] * height * breathe)
            paint.shader = blobs[i]
            paint.alpha = (glow * 255 * peaks[i] * (.2f * breathe + .8f)).roundToInt()
            canvas.drawCircle(0f, 0f, 1f, paint)
            canvas.restoreToCount(save)
        }
        paint.shader = null
        paint.alpha = 255
        if (animate) drawWave(canvas, width, height, now, tone)
    }

    private fun buildShaders(accent: Int, height: Float) {
        shaderAccent = accent
        shaderHeight = height
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        val tones =
            intArrayOf(
                blend(accent, Color.WHITE, .12f),
                Color.HSVToColor(
                    floatArrayOf((hsv[0] + 28) % 360, (hsv[1] + .05f).coerceAtMost(1f), 1f)
                ),
                Color.HSVToColor(
                    floatArrayOf((hsv[0] + 332) % 360, hsv[1], hsv[2].coerceAtLeast(.7f))
                ),
            )
        base =
            LinearGradient(
                0f,
                height * .4f,
                0f,
                height,
                alpha(blend(accent, Color.BLACK, .45f), 0f),
                alpha(blend(accent, Color.BLACK, .25f), .5f),
                Shader.TileMode.CLAMP,
            )
        for (i in tones.indices) {
            blobs[i] =
                RadialGradient(
                    0f,
                    0f,
                    1f,
                    intArrayOf(alpha(tones[i], .85f), alpha(tones[i], .35f), alpha(tones[i], 0f)),
                    floatArrayOf(0f, .55f, 1f),
                    Shader.TileMode.CLAMP,
                )
        }
    }

    private fun drawWave(canvas: Canvas, width: Float, height: Float, now: Long, tone: Int) {
        val t = (now - waveStart) / 1150f
        if (t !in 0f..<1f) return
        val center = 46f * density
        val e = 1f - (1f - t).pow(3)
        val ring =
            28f * density + hypot(maxOf(center, width - center), maxOf(center, height - center)) * e
        val band = (28 + 56 * e) * density
        val outer = ring + band
        val inner = ((ring - band) / outer).coerceIn(0f, 1f)
        val color = blend(tone, Color.WHITE, .35f)
        val bloom = (1 - 3 * t).coerceIn(0f, 1f)
        paint.shader =
            RadialGradient(
                center,
                center,
                outer,
                intArrayOf(
                    alpha(color, .45f * bloom * bloom),
                    alpha(color, 0f),
                    alpha(color, 0f),
                    alpha(color, .55f * (1 - t).pow(2)),
                    alpha(color, 0f),
                ),
                floatArrayOf(
                    0f,
                    minOf(inner, maxOf(.001f, 56f * density / outer)),
                    inner,
                    ring / outer,
                    1f,
                ),
                Shader.TileMode.CLAMP,
            )
        paint.blendMode = BlendMode.PLUS
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null
        paint.blendMode = null
    }

    fun drawArtwork(canvas: Canvas, rowAlpha: Float, now: Long) {
        val t = ((now - flipStart) / 340f).coerceIn(0f, 1f)
        val flip = previousArtwork != null && t < 1f && ValueAnimator.areAnimatorsEnabled()
        val angle = ease(t) * 180f
        val first = flip && angle < 90f
        val image = if (first) previousArtwork else artwork
        val soft = if (first) previousBlurred else blurred
        val save = canvas.save()
        canvas.translate(0f, (1 - rowAlpha) * 8f * density)
        if (flip) {
            camera.save()
            camera.rotateY(-(if (first) angle else angle - 180f))
            camera.getMatrix(matrix)
            camera.restore()
            matrix.preTranslate(-46f * density, -46f * density)
            matrix.postTranslate(46f * density, 46f * density)
            canvas.concat(matrix)
        } else {
            previousArtwork = null
            previousBlurred = null
        }
        rect.set(18f * density, 18f * density, 74f * density, 74f * density)
        path.reset()
        path.addRoundRect(rect, 12f * density, 12f * density, Path.Direction.CW)
        canvas.clipPath(path)
        image?.let { drawImage(canvas, it, rowAlpha) }
        if (flip)
            soft?.let {
                drawImage(
                    canvas,
                    it,
                    rowAlpha * (sin(t * Math.PI).toFloat() * 1.3f).coerceAtMost(1f),
                )
            }
        canvas.restoreToCount(save)
    }

    private fun drawImage(canvas: Canvas, image: Drawable, opacity: Float) {
        val oldAlpha = image.alpha
        val size = 56f * density
        val scale =
            maxOf(
                size / image.intrinsicWidth.coerceAtLeast(1),
                size / image.intrinsicHeight.coerceAtLeast(1),
            )
        val w = if (image.intrinsicWidth > 0) image.intrinsicWidth * scale else size
        val h = if (image.intrinsicHeight > 0) image.intrinsicHeight * scale else size
        image.setBounds(
            (rect.centerX() - w / 2).roundToInt(),
            (rect.centerY() - h / 2).roundToInt(),
            (rect.centerX() + w / 2).roundToInt(),
            (rect.centerY() + h / 2).roundToInt(),
        )
        image.alpha = (opacity * 255).roundToInt()
        image.draw(canvas)
        image.alpha = oldAlpha
    }

    private fun sample(image: Drawable?): IntArray? =
        runCatching {
                val original = (image as? BitmapDrawable)?.bitmap ?: return null
                val scaled = Bitmap.createScaledBitmap(original, 24, 24, true)
                val readable =
                    if (scaled.config == Bitmap.Config.HARDWARE)
                        scaled.copy(Bitmap.Config.ARGB_8888, false)
                    else scaled
                val pixels = IntArray(24 * 24)
                readable.getPixels(pixels, 0, 24, 0, 0, 24, 24)
                if (readable !== original && readable !== scaled) readable.recycle()
                if (scaled !== original) scaled.recycle()
                pixels
            }
            .getOrNull()

    private fun vibrant(pixels: IntArray?): Int {
        if (pixels == null) return Color.WHITE
        val scores = FloatArray(12)
        val channels = Array(12) { FloatArray(3) }
        val hsv = FloatArray(3)
        for (pixel in pixels) {
            Color.colorToHSV(pixel, hsv)
            if (Color.alpha(pixel) < 128 || hsv[1] < .22f || hsv[2] < .3f) continue
            val score = hsv[1] * hsv[2]
            val bucket = (hsv[0] / 30).toInt().coerceIn(0, 11)
            scores[bucket] += score
            channels[bucket][0] += Color.red(pixel) * score
            channels[bucket][1] += Color.green(pixel) * score
            channels[bucket][2] += Color.blue(pixel) * score
        }
        val best = scores.indices.maxByOrNull { scores[it] } ?: return Color.WHITE
        if (scores[best] <= .5f) return Color.WHITE
        Color.colorToHSV(
            Color.rgb(
                (channels[best][0] / scores[best]).roundToInt(),
                (channels[best][1] / scores[best]).roundToInt(),
                (channels[best][2] / scores[best]).roundToInt(),
            ),
            hsv,
        )
        hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1f)
        hsv[2] = hsv[2].coerceAtLeast(.85f)
        var color = Color.HSVToColor(hsv)
        repeat(12) { if (Color.luminance(color) < .22f) color = blend(color, Color.WHITE, .12f) }
        return color
    }

    private fun blur(pixels: IntArray): Drawable {
        var source = pixels.copyOf()
        repeat(4) { pass ->
            val output = IntArray(source.size)
            for (y in 0 until 24) for (x in 0 until 24) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (offset in -3..3) {
                    val sx = if (pass % 2 == 0) (x + offset).coerceIn(0, 23) else x
                    val sy = if (pass % 2 == 1) (y + offset).coerceIn(0, 23) else y
                    val pixel = source[sy * 24 + sx]
                    a += Color.alpha(pixel)
                    r += Color.red(pixel)
                    g += Color.green(pixel)
                    b += Color.blue(pixel)
                }
                output[y * 24 + x] = Color.argb(a / 7, r / 7, g / 7, b / 7)
            }
            source = output
        }
        return BitmapDrawable(
                resources,
                Bitmap.createBitmap(source, 24, 24, Bitmap.Config.ARGB_8888),
            )
            .apply { isFilterBitmap = true }
    }

    companion object {
        fun ease(t: Float): Float = if (t < .5f) 4 * t * t * t else 1 - (2 - 2 * t).pow(3) / 2

        fun alpha(color: Int, fraction: Float): Int =
            (color and 0x00ffffff) or
                ((Color.alpha(color) * fraction.coerceIn(0f, 1f)).roundToInt() shl 24)

        private fun blend(a: Int, b: Int, t: Float) =
            Color.argb(
                (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).roundToInt(),
                (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt(),
            )
    }
}
