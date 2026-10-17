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
import com.android.systemui.qs.panels.shared.model.LunarisQsSliderStyle

@Composable
internal fun Modifier.lunarisSliderLongPress(
    vertical: Boolean,
    sliderStyle: LunarisQsSliderStyle,
    onLongPress: (() -> Unit)?,
): Modifier {
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    return pointerInput(vertical, sliderStyle) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            if (currentOnLongPress == null) return@awaitEachGesture
            val cancelled =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (
                            change == null ||
                                !change.pressed ||
                                change.isConsumed ||
                                event.changes.size != 1 ||
                                change.isOutOfBounds(size, extendedTouchPadding) ||
                                (change.position - down.position).getDistance() >
                                    viewConfiguration.touchSlop
                        ) {
                            return@withTimeoutOrNull true
                        }
                        val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                        if (finalEvent.changes.any { it.isConsumed }) {
                            return@withTimeoutOrNull true
                        }
                    }
                }
            if (cancelled != null) return@awaitEachGesture
            val action = currentOnLongPress ?: return@awaitEachGesture
            action()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}
