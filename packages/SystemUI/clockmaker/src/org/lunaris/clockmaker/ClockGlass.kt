/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import android.graphics.Shader

class ClockGlass private constructor(val clear: Bitmap, private val blurred: Bitmap) {
    fun shader(intensity: Int, lift: Int): Shader {
        val clearShader =
            BitmapShader(clear, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
            }
        val blurShader =
            BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
            }
        val amount = intensity.coerceIn(0, 100)
        var fill: Shader =
            when (amount) {
                0 -> clearShader
                100 -> blurShader
                else ->
                    ComposeShader(
                        clearShader,
                        ComposeShader(blurShader, white(amount), PorterDuff.Mode.DST_IN),
                        PorterDuff.Mode.SRC_OVER,
                    )
            }
        if (lift > 0) fill = ComposeShader(fill, white(lift), PorterDuff.Mode.SRC_OVER)
        return fill
    }

    companion object {
        const val ENABLED_SETTING = "lock_screen_custom_clock_text_glass"
        const val INTENSITY_SETTING = "lock_screen_custom_clock_text_glass_intensity"
        const val LIFT_SETTING = "lock_screen_custom_clock_text_glass_lift"
        const val DEFAULT_INTENSITY = 100
        const val DEFAULT_LIFT = 25

        fun prepare(background: Bitmap): ClockGlass {
            val radiusPx = minOf(background.width, background.height) * 0.1f
            if (radiusPx < 1f) return ClockGlass(background, background)
            val scale = minOf(1f, 540f / minOf(background.width, background.height))
            val width = maxOf(8, (background.width * scale).toInt())
            val height = maxOf(8, (background.height * scale).toInt())
            val small = Bitmap.createScaledBitmap(background, width, height, true)
            try {
                val pixels = IntArray(width * height)
                small.getPixels(pixels, 0, width, 0, 0, width, height)
                val radius = (radiusPx * scale).toInt().coerceIn(1, 254)
                val blurredSmall =
                    Bitmap.createBitmap(
                        ClockStackBlur.blur(pixels, width, height, radius),
                        width,
                        height,
                        Bitmap.Config.ARGB_8888,
                    )
                val blurred =
                    try {
                        Bitmap.createScaledBitmap(
                            blurredSmall,
                            background.width,
                            background.height,
                            true,
                        )
                    } catch (error: Throwable) {
                        blurredSmall.recycle()
                        throw error
                    }
                if (blurred !== blurredSmall) blurredSmall.recycle()
                return ClockGlass(background, blurred)
            } finally {
                if (small !== background) small.recycle()
            }
        }

        private fun white(percent: Int): Shader {
            val color = Color.argb(percent.coerceIn(0, 100) * 255 / 100, 255, 255, 255)
            return LinearGradient(0f, 0f, 1f, 0f, color, color, Shader.TileMode.CLAMP)
        }
    }
}
