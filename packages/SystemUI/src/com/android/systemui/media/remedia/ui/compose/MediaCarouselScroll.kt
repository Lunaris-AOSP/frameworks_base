/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.media.remedia.ui.compose

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity

@Composable
internal fun Modifier.mediaCarouselScroll(
    pagerState: PagerState,
    isScrollFalseTouch: (() -> Boolean)?,
): Modifier {
    val currentIsScrollFalseTouch by rememberUpdatedState(isScrollFalseTouch)
    val gesture = remember(pagerState) { MediaCarouselGesture() }
    val flingConnection =
        remember(pagerState) {
            object : NestedScrollConnection {
                override suspend fun onPreFling(available: Velocity): Velocity {
                    val startPage = gesture.startPage
                    if (
                        !gesture.isReleased ||
                            startPage == null ||
                            pagerState.pageCount == 0 ||
                            currentIsScrollFalseTouch?.invoke() != true
                    ) {
                        return Velocity.Zero
                    }
                    pagerState.scrollToPage(startPage.coerceIn(0, pagerState.pageCount - 1))
                    return available
                }
            }
        }

    return pointerInput(pagerState) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                gesture.startPage = pagerState.currentPage
                gesture.isReleased = false
                var released = false
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    released = true
                } finally {
                    gesture.isReleased = released
                }
            }
        }
        .nestedScroll(flingConnection)
}

private class MediaCarouselGesture {
    var startPage: Int? = null
    var isReleased = false
}
