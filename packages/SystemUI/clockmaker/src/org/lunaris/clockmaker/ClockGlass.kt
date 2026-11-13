/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class ClockGlass private constructor(val clear: Bitmap, private val referenceWidth: Int,
    private val referenceHeight: Int) {
    private data class Blur(val bitmap: Bitmap, val sides: Bitmap)
    private val blurs = LruCache<Int, Blur>(16)
    private val pending = mutableMapOf<Int, MutableSet<Runnable>>()
    private val failedBlurs = mutableSetOf<Int>()

    internal fun isBlurReady(style: ClockGlassStyle, ready: Runnable): Boolean =
        requestBlur(style.blurRadius.coerceIn(0, 120), ready) != null

    internal fun requestedBlurRadii(): IntArray = synchronized(this) {
        (blurs.snapshot().keys + pending.keys).toIntArray()
    }

    internal fun shader(mask: Bitmap, style: ClockGlassStyle, tint: Int, date: Boolean,
        originX: Float, originY: Float, ready: Runnable): RuntimeShader? {
        val s = style.normalized()
        val blur = requestBlur(s.blurRadius, ready) ?: return null
        fun bitmapShader(bitmap: Bitmap) =
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                setLocalMatrix(Matrix().apply {
                    setScale(clear.width.toFloat() / bitmap.width, clear.height.toFloat() / bitmap.height)
                })
            }
        fun maskShader(bitmap: Bitmap) = BitmapShader(bitmap, Shader.TileMode.DECAL, Shader.TileMode.DECAL).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            setLocalMatrix(Matrix().apply {
                setScale(mask.width.toFloat() / bitmap.width, mask.height.toFloat() / bitmap.height)
                postTranslate(originX, originY)
            })
        }
        val shadowMask = Bitmap.createScaledBitmap(mask, (mask.width / 4).coerceAtLeast(1),
            (mask.height / 4).coerceAtLeast(1), true)
        val angle = Math.toRadians(s.rimRotation.toDouble())
        return RuntimeShader(ClockGlassShader.SOURCE).apply {
            setInputShader("blurred", bitmapShader(blur.bitmap))
            setInputShader("blurSides", bitmapShader(blur.sides))
            setInputShader("glyph", maskShader(mask))
            setInputShader("shadowGlyph", maskShader(shadowMask))
            setFloatUniform("refraction", (if (s.separateRefraction) s.clockRefraction else s.refraction) / 1000f)
            setFloatUniform("refractionWidth", (if (s.separateRefraction) s.clockRefractionWidth else s.refractionWidth) / 1000f)
            setFloatUniform("chromatic", s.chromaticAberration / 1000f)
            setFloatUniform("contrast", s.contrast / 100f)
            setFloatUniform("rimStrength", (if (s.separateRefraction) s.clockRimStrength else s.rimStrength) / 100f)
            setFloatUniform("rimTrig", cos(angle).toFloat(), sin(angle).toFloat())
            setFloatUniform("softRim", s.softRimStrength / 100f, s.softRimWidth / 100f, s.softRimTint / 100f)
            setFloatUniform("borderStrength", if (s.blackBorder) s.blackBorderStrength / 100f else 0f)
            setFloatUniform("rimWidth", s.rimWidth / 100f)
            setFloatUniform("dateLens", if (date) 1f else 0f)
            setFloatUniform("shadowStrength", s.shadowStrength / 100f)
            setFloatUniform("outline", 0f)
            setColorUniform("tint", tint or 0xFF000000.toInt())
            setFloatUniform("tintOpacity", s.tintOpacity / 100f)
            setFloatUniform("wallpaperSize", clear.width.toFloat(), clear.height.toFloat())
            setFloatUniform("glyphSize", mask.width.toFloat(), mask.height.toFloat())
            setFloatUniform("glyphOrigin", originX, originY)
        }
    }

    private fun requestBlur(radius: Int, ready: Runnable): Blur? {
        return synchronized(this) {
            if (radius in failedBlurs) return@synchronized null
            blurs.get(radius) ?: run {
                val waiting = pending[radius]
                if (waiting != null) waiting.add(ready) else {
                    pending[radius] = mutableSetOf(ready)
                    worker.execute {
                        val result = runCatching { prepareBlur(radius) }
                            .onFailure { Log.w("ClockGlass", "Cannot prepare blur radius $radius", it) }
                            .getOrNull()
                        val callbacks = synchronized(this) {
                            if (result != null) blurs.put(radius, result)
                            else failedBlurs.add(radius)
                            pending.remove(radius)?.toList().orEmpty()
                        }
                        main.post { callbacks.forEach { it.run() } }
                    }
                }
                null
            }
        }
    }

    fun surfaceShader(style: ClockGlassStyle, tint: Int, ready: Runnable): RuntimeShader? {
        val s = style.normalized()
        val blur = requestBlur(s.blurRadius, ready) ?: return null
        fun bitmapShader(bitmap: Bitmap) =
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                setLocalMatrix(Matrix().apply {
                    setScale(clear.width.toFloat() / bitmap.width, clear.height.toFloat() / bitmap.height)
                })
            }
        val angle = Math.toRadians(s.rimRotation.toDouble())
        return RuntimeShader(GlassSurfaceShader.SOURCE).apply {
            setInputShader("blurred", bitmapShader(blur.bitmap))
            setInputShader("blurSides", bitmapShader(blur.sides))
            setFloatUniform("refraction", s.refraction / 1000f)
            setFloatUniform("refractionWidth", s.refractionWidth / 1000f)
            setFloatUniform("chromatic", s.chromaticAberration / 1000f)
            setFloatUniform("contrast", s.contrast / 100f)
            setFloatUniform("rimStrength", s.rimStrength / 100f)
            setFloatUniform("rimTrig", cos(angle).toFloat(), sin(angle).toFloat())
            setFloatUniform("softRim", s.softRimStrength / 100f, s.softRimWidth / 100f, s.softRimTint / 100f)
            setFloatUniform("rimWidth", s.rimWidth / 100f)
            setFloatUniform("borderStrength", if (s.blackBorder) s.blackBorderStrength / 100f else 0f)
            setFloatUniform("shadowStrength", s.shadowStrength / 100f)
            setColorUniform("tint", tint or 0xFF000000.toInt())
            setFloatUniform("tintOpacity", s.tintOpacity / 100f)
            setFloatUniform("wallpaperSize", clear.width.toFloat(), clear.height.toFloat())
        }
    }

    private fun prepareBlur(radius: Int): Blur {
        val width = clear.width
        val height = clear.height
        val pixels = IntArray(width * height)
        clear.getPixels(pixels, 0, width, 0, 0, width, height)
        val scale = height.toFloat() / maxOf(referenceWidth, referenceHeight)
        val base = maxOf(radius * scale / 6f, 0.01f)
        val passes = ceil((radius / 6f) * (radius / 6f)).toInt().coerceIn(1, 8)
        val spread = base / sqrt(passes.toFloat())
        var input = pixels
        var output = IntArray(pixels.size)
        if (radius > 0) repeat(passes) {
            blurPass(input, output, width, height, spread, true)
            val swap = input
            input = output
            output = swap
            blurPass(input, output, width, height, spread, false)
            val second = input
            input = output
            output = second
        }
        val bitmap = if (radius == 0) clear else Bitmap.createBitmap(input, width, height, Bitmap.Config.ARGB_8888)
        val sides = Bitmap.createScaledBitmap(bitmap, (width / 2).coerceAtLeast(1),
            (height / 2).coerceAtLeast(1), true)
        return Blur(bitmap, sides)
    }

    private fun blurPass(input: IntArray, output: IntArray, width: Int, height: Int,
        spread: Float, horizontal: Boolean) {
        val offsets = floatArrayOf(0f, 1.384615f * spread, -1.384615f * spread,
            3.230769f * spread, -3.230769f * spread)
        val weights = floatArrayOf(0.227027f, 0.316216f, 0.316216f, 0.070270f, 0.070270f)
        val count = if (horizontal) width else height
        val indices = Array(5) { IntArray(count) }
        val next = Array(5) { IntArray(count) }
        val fractions = Array(5) { FloatArray(count) }
        for (tap in 0..4) for (p in 0 until count) {
            val sample = (p + offsets[tap]).coerceIn(0f, (count - 1).toFloat())
            indices[tap][p] = floor(sample).toInt()
            next[tap][p] = (indices[tap][p] + 1).coerceAtMost(count - 1)
            fractions[tap][p] = sample - indices[tap][p]
        }
        for (y in 0 until height) for (x in 0 until width) {
            var red = 0f
            var green = 0f
            var blue = 0f
            var alpha = 0f
            val p = if (horizontal) x else y
            for (tap in 0..4) {
                val a = input[if (horizontal) y * width + indices[tap][p] else indices[tap][p] * width + x]
                val b = input[if (horizontal) y * width + next[tap][p] else next[tap][p] * width + x]
                val t = fractions[tap][p]
                val weight = weights[tap]
                red += (((a ushr 16) and 255) * (1f - t) + ((b ushr 16) and 255) * t) * weight
                green += (((a ushr 8) and 255) * (1f - t) + ((b ushr 8) and 255) * t) * weight
                blue += ((a and 255) * (1f - t) + (b and 255) * t) * weight
                alpha += ((a ushr 24) * (1f - t) + (b ushr 24) * t) * weight
            }
            output[y * width + x] = (alpha.roundToInt().coerceIn(0, 255) shl 24) or
                (red.roundToInt().coerceIn(0, 255) shl 16) or (green.roundToInt().coerceIn(0, 255) shl 8) or
                blue.roundToInt().coerceIn(0, 255)
        }
    }

    companion object {
        const val ENABLED_SETTING = "lock_screen_custom_clock_text_glass"
        const val INTENSITY_SETTING = "lock_screen_custom_clock_text_glass_intensity"
        const val LIFT_SETTING = "lock_screen_custom_clock_text_glass_lift"
        const val DEFAULT_INTENSITY = 100
        const val DEFAULT_LIFT = 25
        private val worker = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())

        fun prepare(background: Bitmap, blurRadii: IntArray = intArrayOf(16),
            referenceWidth: Int = background.width, referenceHeight: Int = background.height): ClockGlass {
            val scale = minOf(1f, 540f / background.width)
            val width = maxOf(1, (background.width * scale).toInt())
            val height = maxOf(1, (background.height * scale).toInt())
            val small = Bitmap.createScaledBitmap(background, width, height, true)
            return ClockGlass(small, referenceWidth.coerceAtLeast(1), referenceHeight.coerceAtLeast(1)).apply {
                blurRadii.map { it.coerceIn(0, 120) }.distinct().forEach { blurs.put(it, prepareBlur(it)) }
            }
        }
    }
}
