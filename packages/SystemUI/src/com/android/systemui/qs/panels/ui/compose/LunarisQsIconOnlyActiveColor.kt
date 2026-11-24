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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.android.systemui.qs.panels.shared.model.AxQsSpan

internal val LocalLunarisQsIconOnlyActiveColor = staticCompositionLocalOf { false }

internal val LocalAxQsTileStack = staticCompositionLocalOf { false }

@Composable
@ReadOnlyComposable
internal fun usesIconOnlyActiveColor(span: AxQsSpan): Boolean =
    LocalLunarisQsIconOnlyActiveColor.current &&
        (LocalAxQsTileStack.current || (span.columns == 2 && span.rows == 2))

@Composable
internal fun Modifier.lunarisQsAnimatedIconBackground(color: Color, brush: Brush?): Modifier {
    val animatedColor by animateColorAsState(color, label = "LunarisQsIconBackground")
    var lastBrush by remember { mutableStateOf(brush) }
    LaunchedEffect(brush) { if (brush != null) lastBrush = brush }
    val gradientAlpha =
        animateFloatAsState(if (brush != null) 1f else 0f, label = "LunarisQsIconGradientAlpha")
    return background(animatedColor, CircleShape).drawWithCache {
        val gradient = lastBrush
        onDrawBehind {
            if (gradient != null) {
                drawCircle(brush = gradient, alpha = gradientAlpha.value)
            }
        }
    }
}
