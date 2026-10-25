/*
 * Copyright (C) 2024 The Android Open Source Project
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.qs.panels.ui.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaCardViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import kotlinx.coroutines.flow.filterNotNull

@Composable
fun AxMediaCarousel(
    viewModel: MediaViewModel,
    behavior: MediaUiBehavior,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    cardFilter: ((MediaCardViewModel) -> Boolean)? = null,
    carouselShape: Shape = RoundedCornerShape(32.dp),
    cardContent: @Composable (MediaCardViewModel, Modifier, Boolean) -> Unit,
    pagerIndicator: @Composable BoxScope.(PagerState) -> Unit = {},
) {
    val rawCards = cardFilter?.let { viewModel.cards.filter(it) } ?: viewModel.cards
    val rawKeys = rawCards.map { it.key }.toSet()
    var orderedKeys by remember(viewModel) { mutableStateOf(emptyList<Any>()) }
    val cardKeys =
        remember(orderedKeys, rawKeys) {
            orderedKeys.filter { it in rawKeys } + rawKeys.filter { it !in orderedKeys }
        }
    SideEffect { if (orderedKeys != cardKeys) orderedKeys = cardKeys }
    val cardMap = rawCards.associateBy { it.key }
    val cards = cardKeys.mapNotNull(cardMap::get)
    val currentRawCards by rememberUpdatedState(viewModel.cards)
    AnimatedVisibility(
        visible = viewModel.isCarouselVisible && cards.isNotEmpty(),
        modifier = modifier,
    ) {
        if (cards.isEmpty()) return@AnimatedVisibility
        val currentCardKey = viewModel.cards.getOrNull(viewModel.currentIndex)?.key
        val initialIndex = cardKeys.indexOf(currentCardKey).coerceAtLeast(0)
        val pagerState = rememberPagerState(initialPage = initialIndex) { cards.size }
        LaunchedEffect(cards.size) {
            if (pagerState.currentPage >= cards.size) {
                pagerState.scrollToPage(cards.lastIndex)
            }
        }
        LaunchedEffect(pagerState, viewModel) {
            snapshotFlow {
                    if (pagerState.isScrollInProgress) null
                    else {
                        val selectedKey =
                            pagerState.layoutInfo.visiblePagesInfo
                                .firstOrNull { it.index == pagerState.settledPage }
                                ?.key
                        currentRawCards.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 }
                    }
                }
                .filterNotNull()
                .collect { viewModel.onCardSelected(it) }
        }
        var isFalseTouchDetected: Boolean by
            remember(behavior.isCarouselScrollFalseTouch) { mutableStateOf(false) }
        val isSwipingEnabled = behavior.isCarouselScrollingEnabled && !isFalseTouchDetected

        Box(
            modifier =
                Modifier.clip(carouselShape).pointerInput(behavior) {
                    if (behavior.isCarouselScrollFalseTouch != null) {
                        awaitEachGesture {
                            awaitFirstDown(false, PointerEventPass.Initial)
                            isFalseTouchDetected = behavior.isCarouselScrollFalseTouch.invoke()
                        }
                    }
                }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().clip(carouselShape),
                userScrollEnabled = isSwipingEnabled,
                pageSpacing = 8.dp,
                beyondViewportPageCount = 1,
                key = { index: Int -> cards[index].key },
            ) { pageIndex: Int ->
                val cardModifier =
                    Modifier.clip(carouselShape).sysuiResTag("media_control")
                cardContent(cards[pageIndex], cardModifier, pageIndex == pagerState.currentPage)
            }

            if (pagerState.pageCount > 1) {
                pagerIndicator(pagerState)
            }
        }
    }
}
