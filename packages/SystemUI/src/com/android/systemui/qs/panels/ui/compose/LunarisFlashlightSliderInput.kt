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

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection

@Composable
internal fun Modifier.lunarisFlashlightSliderInput(
    enabled: Boolean,
    canAdjust: Boolean,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onTap: () -> Unit,
    onDragStart: () -> Boolean,
    onValueChange: (Float) -> Unit,
    onDragEnd: () -> Unit,
): Modifier {
    val currentValue by rememberUpdatedState(value)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val view = LocalView.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    if (!enabled) return this
    return pointerInput(view, rtl, canAdjust, valueRange) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            var position = down.position
            val finished =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (
                            change == null ||
                                change.isConsumed ||
                                event.changes.size != 1 ||
                                change.isOutOfBounds(size, extendedTouchPadding) ||
                                (change.position - down.position).getDistance() >
                                    viewConfiguration.touchSlop
                        ) {
                            return@withTimeoutOrNull true
                        }
                        if (!change.pressed) {
                            change.consume()
                            currentOnTap()
                            return@withTimeoutOrNull true
                        }
                        position = change.position
                        if (
                            awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }
                        ) {
                            return@withTimeoutOrNull true
                        }
                    }
                }
            if (finished != null || !canAdjust) return@awaitEachGesture
            val startValue = currentValue
            val startPosition = position
            if (!currentOnDragStart()) return@awaitEachGesture
            try {
                view.parent?.requestDisallowInterceptTouchEvent(true)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || change.isConsumed || event.changes.size != 1) break
                    change.consume()
                    if (!change.pressed) break
                    val delta = (change.position.x - startPosition.x) * if (rtl) -1f else 1f
                    val newValue =
                        startValue +
                            delta / size.width.coerceAtLeast(1) *
                                (valueRange.endInclusive - valueRange.start)
                    currentOnValueChange(newValue.coerceIn(valueRange))
                }
            } finally {
                view.parent?.requestDisallowInterceptTouchEvent(false)
                currentOnDragEnd()
            }
        }
    }
}
