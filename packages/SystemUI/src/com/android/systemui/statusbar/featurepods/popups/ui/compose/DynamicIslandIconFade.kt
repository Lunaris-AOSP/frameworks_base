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

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import android.graphics.RectF
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.android.systemui.statusbar.featurepods.popups.ui.view.DynamicIslandIconFader
import com.android.systemui.statusbar.featurepods.popups.ui.view.DynamicIslandIconState

internal val LocalDynamicIslandIconBounds =
    compositionLocalOf<State<DynamicIslandIconState>?> { null }
private val HideEasing = CubicBezierEasing(.4f, 0f, 1f, 1f)
private val ShowEasing = CubicBezierEasing(0f, 0f, .2f, 1f)

@Composable
internal fun Modifier.dynamicIslandIconFade(
    islandState: DynamicIslandIconState? = LocalDynamicIslandIconBounds.current?.value
): Modifier {
    if (islandState == null) return this
    val islandBounds = islandState.bounds
    val density = LocalDensity.current.density
    var bounds by remember { mutableStateOf(RectF()) }
    var hidden by remember { mutableStateOf(false) }
    LaunchedEffect(islandBounds, bounds, density) {
        hidden = DynamicIslandIconFader.overlaps(bounds, islandBounds, density, hidden)
    }
    val opacity by
        animateFloatAsState(
            targetValue = if (hidden) 0f else 1f,
            animationSpec =
                tween(
                    durationMillis =
                        if (islandState.reducedMotion) 0
                        else
                            ((if (hidden) DynamicIslandIconFader.HIDE_DURATION
                                else DynamicIslandIconFader.SHOW_DURATION) /
                                    islandState.animationSpeed)
                                .toInt(),
                    delayMillis =
                        if (hidden || islandState.reducedMotion) 0
                        else
                            (DynamicIslandIconFader.SHOW_DELAY / islandState.animationSpeed)
                                .toInt(),
                    easing = if (hidden) HideEasing else ShowEasing,
                ),
            label = "dynamic_island_icon_fade",
        )
    return graphicsLayer {
            alpha = opacity
            val radius = DynamicIslandIconFader.MAX_BLUR_RADIUS_DP * density * (1f - opacity)
            renderEffect =
                if (islandState.reducedMotion || opacity <= 0f || radius < 0.5f) null
                else BlurEffect(radius, radius, TileMode.Clamp)
        }
        .onGloballyPositioned {
            val rect = it.boundsInWindow()
            bounds = RectF(rect.left, rect.top, rect.right, rect.bottom)
        }
}
