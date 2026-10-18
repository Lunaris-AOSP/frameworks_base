/*
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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val LocalLunarisQsStrokeStyle = staticCompositionLocalOf { false }
internal val LunarisQsTileStrokeWidth = 7.dp
internal val LunarisQsSliderStrokeWidth = 4.dp

internal fun Modifier.lunarisQsInsetBackground(
    brush: Brush,
    shape: RoundedCornerShape,
    insetWidth: Dp,
    alpha: () -> Float = { 1f },
): Modifier = drawWithCache {
    val insetPx = insetWidth.toPx().coerceIn(0f, size.minDimension / 2f)
    val width = size.width - insetPx * 2
    val height = size.height - insetPx * 2
    val outline = shape.createOutline(size, layoutDirection, this)
    val outer =
        (outline as? Outline.Rounded)?.roundRect
            ?: RoundRect(0f, 0f, size.width, size.height)
    fun CornerRadius.insetRadius() =
        CornerRadius((x - insetPx).coerceAtLeast(0f), (y - insetPx).coerceAtLeast(0f))
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                left = 0f,
                top = 0f,
                right = width,
                bottom = height,
                topLeftCornerRadius = outer.topLeftCornerRadius.insetRadius(),
                topRightCornerRadius = outer.topRightCornerRadius.insetRadius(),
                bottomRightCornerRadius = outer.bottomRightCornerRadius.insetRadius(),
                bottomLeftCornerRadius = outer.bottomLeftCornerRadius.insetRadius(),
            )
        )
    }
    onDrawBehind {
        if (width > 0f && height > 0f) {
            inset(insetPx) { drawPath(path, brush, alpha = alpha()) }
        }
    }
}
