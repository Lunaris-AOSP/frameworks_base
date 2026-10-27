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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp

internal val LocalLunarisQsCosTileBackground = staticCompositionLocalOf { false }

@Composable
internal fun Modifier.lunarisQsCosTileBorder(
    shape: RoundedCornerShape,
    enabled: Boolean = LocalLunarisQsCosTileBackground.current,
    verticalSlider: Boolean = false,
    horizontalGap: (DrawScope.() -> ClosedFloatingPointRange<Float>)? = null,
): Modifier {
    if (!enabled) return this

    return drawWithCache {
        val strokeWidth = 0.5.dp.toPx().coerceAtMost(size.minDimension)
        val featherWidth = (strokeWidth + 1f).coerceAtMost(size.minDimension)
        val inset = featherWidth / 2f
        val outline = shape.createOutline(size, layoutDirection, this)
        val outer =
            (outline as? Outline.Rounded)?.roundRect
                ?: RoundRect(0f, 0f, size.width, size.height)
        fun CornerRadius.insetRadius() =
            CornerRadius((x - inset).coerceAtLeast(0f), (y - inset).coerceAtLeast(0f))
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = inset,
                    top = inset,
                    right = size.width - inset,
                    bottom = size.height - inset,
                    topLeftCornerRadius = outer.topLeftCornerRadius.insetRadius(),
                    topRightCornerRadius = outer.topRightCornerRadius.insetRadius(),
                    bottomRightCornerRadius = outer.bottomRightCornerRadius.insetRadius(),
                    bottomLeftCornerRadius = outer.bottomLeftCornerRadius.insetRadius(),
                )
            )
        }
        val brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.45f),
            0.45f to Color.White.copy(alpha = 0.45f),
            1f to Color.White.copy(alpha = 0.03f),
            start = if (verticalSlider) Offset(size.width, 0f) else Offset.Zero,
            end = if (verticalSlider) Offset.Zero else Offset(0f, size.height),
        )
        val stroke = Stroke(width = strokeWidth)
        val feather = Stroke(width = featherWidth)
        val drawBorder: DrawScope.() -> Unit = {
            drawPath(path, brush, alpha = 0.18f, style = feather)
            drawPath(path, brush, style = stroke)
        }
        onDrawWithContent {
            drawContent()
            if (strokeWidth > 0f) {
                val gap = horizontalGap?.invoke(this)
                if (gap == null) {
                    drawBorder()
                } else {
                    clipRect(right = gap.start.coerceIn(0f, size.width)) { drawBorder() }
                    clipRect(left = gap.endInclusive.coerceIn(0f, size.width)) { drawBorder() }
                }
            }
        }
    }
}
