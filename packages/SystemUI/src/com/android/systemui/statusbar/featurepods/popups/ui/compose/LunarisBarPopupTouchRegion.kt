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

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import android.graphics.Region
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import kotlin.math.ceil
import kotlin.math.floor

@Composable
internal fun LunarisBarPopupTouchRegion(enabled: Boolean, bounds: Rect, secondaryBounds: Rect) {
    if (!enabled) return

    val view = LocalView.current
    val currentBounds by rememberUpdatedState(bounds)
    val currentSecondaryBounds by rememberUpdatedState(secondaryBounds)
    val location = remember(view) { IntArray(2) }
    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val listener =
            ViewTreeObserver.OnComputeInternalInsetsListener { info ->
                view.getLocationInWindow(location)
                info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
                info.touchableRegion.set(
                    lunarisBarPopupTouchRegion(
                        currentBounds,
                        currentSecondaryBounds,
                        IntOffset(location[0], location[1]),
                    )
                )
            }
        observer.addOnComputeInternalInsetsListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnComputeInternalInsetsListener(listener)
            view.requestLayout()
        }
    }
    SideEffect { view.requestLayout() }
}

internal fun lunarisBarPopupTouchRegion(
    bounds: Rect,
    secondaryBounds: Rect,
    windowOffset: IntOffset,
): Region =
    Region().apply {
        for (rect in listOf(bounds, secondaryBounds)) {
            if (rect.isEmpty) continue
            union(
                android.graphics.Rect(
                    floor(rect.left).toInt() + windowOffset.x,
                    floor(rect.top).toInt() + windowOffset.y,
                    ceil(rect.right).toInt() + windowOffset.x,
                    ceil(rect.bottom).toInt() + windowOffset.y,
                )
            )
        }
    }
