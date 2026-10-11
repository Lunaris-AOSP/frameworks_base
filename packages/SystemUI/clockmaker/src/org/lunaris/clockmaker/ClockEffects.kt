/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import kotlin.math.roundToInt

object ClockEffects {
    fun shadow(color: Int, opacity: Int, softness: Int, density: Float): RenderEffect? {
        val alpha = (Color.alpha(color) * opacity.coerceIn(0, 100) / 100f).roundToInt()
        if (alpha == 0) return null
        val tint = (color and 0xFFFFFF) or (alpha shl 24)
        var effect = RenderEffect.createColorFilterEffect(BlendModeColorFilter(tint, BlendMode.SRC_IN))
        val radius = softness.coerceIn(0, 30) * density
        if (radius > 0f) effect = RenderEffect.createBlurEffect(radius, radius, effect, Shader.TileMode.DECAL)
        return RenderEffect.createBlendModeEffect(RenderEffect.createOffsetEffect(0f, 2f * density, effect),
            RenderEffect.createOffsetEffect(0f, 0f), BlendMode.SRC_OVER)
    }
}
