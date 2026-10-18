/*
 * Copyright (C) 2025-2026 AxionOS
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

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.panels.shared.model.AxQsTileStacks
import com.android.systemui.res.R
import kotlinx.coroutines.launch

@Composable
internal fun AxTileStack(
    pageKeys: List<String>,
    modifier: Modifier = Modifier,
    swipeEnabled: Boolean = true,
    indicatorColor: @Composable (Int) -> Color = { MaterialTheme.colorScheme.onSurface },
    pageContent: @Composable (Int) -> Unit,
) {
    if (pageKeys.isEmpty()) {
        AxTileStackPlaceholder(modifier)
        return
    }
    val pagerState = rememberPagerState(pageCount = { pageKeys.size })
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val canScroll = swipeEnabled && pageKeys.size > 1
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = Offset(0f, available.y)

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                Velocity(0f, available.y)
        }
    }
    val previousLabel = stringResource(R.string.lunaris_qs_stack_previous)
    val nextLabel = stringResource(R.string.lunaris_qs_stack_next)
    val pageDescription =
        stringResource(R.string.lunaris_qs_stack_page, pagerState.currentPage + 1, pageKeys.size)
    val shape = axQsTileShape(AxQsTileStacks.Span, LocalAxQsTileStyle.current)
    Box(
        modifier
            .fillMaxSize()
            .clip(shape)
            .then(
                if (canScroll) {
                    Modifier.nestedScroll(scrollConnection).pointerInput(view) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                            try {
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                } while (event.changes.any { it.pressed })
                            } finally {
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
            .semantics {
                stateDescription = pageDescription
                if (canScroll) {
                    customActions = buildList {
                        if (pagerState.currentPage > 0) {
                            add(
                                CustomAccessibilityAction(previousLabel) {
                                    scope.launch {
                                        pagerState.animateScrollToPage(
                                            (pagerState.currentPage - 1).coerceAtLeast(0)
                                        )
                                    }
                                    true
                                }
                            )
                        }
                        if (pagerState.currentPage < pageKeys.lastIndex) {
                            add(
                                CustomAccessibilityAction(nextLabel) {
                                    scope.launch {
                                        pagerState.animateScrollToPage(
                                            (pagerState.currentPage + 1).coerceAtMost(
                                                pageKeys.lastIndex
                                            )
                                        )
                                    }
                                    true
                                }
                            )
                        }
                    }
                }
            }
    ) {
        VerticalPager(
            state = pagerState,
            userScrollEnabled = canScroll,
            key = { pageKeys[it] },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            pageContent(page)
        }
        if (pageKeys.size > 1) {
            val firstDot =
                (pagerState.currentPage - 3).coerceIn(0, (pageKeys.size - 7).coerceAtLeast(0))
            val color = indicatorColor(pagerState.currentPage.coerceIn(pageKeys.indices))
            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                repeat(minOf(7, pageKeys.size)) { offset ->
                    val selected = firstDot + offset == pagerState.currentPage
                    Box(
                        Modifier.size(if (selected) 5.dp else 4.dp)
                            .background(
                                color.copy(alpha = if (selected) 1f else 0.38f),
                                CircleShape,
                            )
                    )
                }
            }
        }
    }
}

@Composable
internal fun AxTileStackPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .background(
                AxTileDefaults.backgroundColor(),
                axQsTileShape(AxQsTileStacks.Span, LocalAxQsTileStyle.current),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(
            imageVector = Icons.Filled.Layers,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(AxTileDefaults.LargeIconSize),
        )
        Text(
            text = stringResource(R.string.lunaris_qs_tile_stack),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
