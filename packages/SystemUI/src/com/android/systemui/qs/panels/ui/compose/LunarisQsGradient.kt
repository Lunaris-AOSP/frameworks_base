/*
 * Copyright (C) 2024-2026 AxionOS
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

package com.android.systemui.qs.panels.ui.compose

import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.android.systemui.qs.panels.shared.model.LunarisQsGradientSettings
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal val LocalLunarisQsGradientSettings = staticCompositionLocalOf {
    LunarisQsGradientSettings()
}

internal data class LunarisQsGradient(
    val enabled: Boolean,
    val sliderOnly: Boolean,
    val startColor: Color,
    val endColor: Color,
    val contentColor: Color,
    val brush: Brush,
) {
    fun tileBrush(): Brush? = brush.takeIf { enabled && !sliderOnly }

    fun sliderBrush(): Brush? = brush.takeIf { enabled }
}

@Composable
internal fun rememberLunarisQsGradient(
    settings: LunarisQsGradientSettings = LocalLunarisQsGradientSettings.current,
    rotationOffset: Int = 0,
): LunarisQsGradient {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    return remember(settings, primary, secondary, rotationOffset) {
        val (startColor, endColor) =
            when {
                settings.colorMode == 1 ->
                    (if (settings.startColor == 0) primary
                    else Color(settings.startColor).copy(alpha = 1f)) to
                        (if (settings.endColor == 0) secondary
                        else Color(settings.endColor).copy(alpha = 1f))
                settings.autoStyle == 1 -> primary to secondary
                else -> lightenAndDarken(primary)
            }
        val samples =
            (0..4).map { ColorUtils.blendARGB(startColor.toArgb(), endColor.toArgb(), it / 4f) }
        val contentColor =
            listOf(Color.Black, Color.White).maxBy { foreground ->
                samples.minOf { ColorUtils.calculateContrast(foreground.toArgb(), it) }
            }
        LunarisQsGradient(
            enabled = settings.enabled,
            sliderOnly = settings.sliderOnly,
            startColor = startColor,
            endColor = endColor,
            contentColor = contentColor,
            brush =
                RotatedLinearGradientBrush(
                    startColor,
                    endColor,
                    (settings.rotation + rotationOffset) % 360,
                ),
        )
    }
}

private class RotatedLinearGradientBrush(
    private val startColor: Color,
    private val endColor: Color,
    private val rotation: Int,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val angle = Math.toRadians(rotation.toDouble())
        val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        val extent =
            (abs(size.width * direction.x) + abs(size.height * direction.y)).coerceAtLeast(1f)
        val center = Offset(size.width / 2f, size.height / 2f)
        val halfExtent = extent / 2f
        val start = center - direction * halfExtent
        val end = center + direction * halfExtent
        return LinearGradient(
            start.x,
            start.y,
            end.x,
            end.y,
            startColor.toArgb(),
            endColor.toArgb(),
            Shader.TileMode.CLAMP,
        )
    }
}

private fun lightenAndDarken(color: Color): Pair<Color, Color> {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    val light = hsl.copyOf().also { it[2] = (it[2] + 0.18f).coerceIn(0f, 1f) }
    val dark = hsl.copyOf().also { it[2] = (it[2] - 0.18f).coerceIn(0f, 1f) }
    return Color(ColorUtils.HSLToColor(light)) to Color(ColorUtils.HSLToColor(dark))
}
