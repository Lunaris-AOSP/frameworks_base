/*
 * Copyright 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose

import android.view.MotionEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.qs.composefragment.ui.AxQsGridContent
import com.android.systemui.qs.panels.shared.model.AxQsGridItem
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel

internal val LocalQsScrollGestureStart = staticCompositionLocalOf<((ScrollState) -> Unit)?> { null }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun <T> ContentScope.AxQsLandscapePanel(
    toolbarViewModel: ToolbarViewModel,
    shadeHeaderViewModel: ShadeHeaderViewModel,
    controlItems: List<AxQsGridItem<T>>,
    tileItems: List<AxQsGridItem<T>>,
    controlColumns: Int,
    controlRows: Int,
    tileColumns: Int,
    maxTileRows: Int,
    showTileLabels: Boolean,
    showWeather: Boolean,
    spacing: Dp,
    scrollState: ScrollState,
    isFullyVisible: () -> Boolean,
    editButtonProgress: () -> Float,
    modifier: Modifier = Modifier,
    controlContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileLabel: @Composable (AxQsGridItem<T>) -> Unit,
) {
    val paneSpacing = spacing * 2
    val tileScrollState = rememberScrollState()
    val onScrollGestureStart = LocalQsScrollGestureStart.current
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = spacing),
            horizontalArrangement = Arrangement.spacedBy(paneSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AxQsDateHeader(
                toolbarViewModel = toolbarViewModel,
                shadeHeaderViewModel = shadeHeaderViewModel,
                showEdit = false,
                showWeather = showWeather,
                isFullyVisible = isFullyVisible,
                editButtonProgress = editButtonProgress,
                modifier = Modifier.weight(1f),
                showActions = false,
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                AxQsLandscapeActions(
                    viewModel = toolbarViewModel,
                    isFullyVisible = isFullyVisible,
                    editButtonProgress = editButtonProgress,
                    modifier =
                        Modifier.horizontalScroll(rememberScrollState(), overscrollEffect = null),
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val paneWidth =
                if (controlItems.isNotEmpty() && tileItems.isNotEmpty()) {
                    ((maxWidth - paneSpacing) / 2).coerceAtLeast(0.dp)
                } else maxWidth
            val controlCellHeight =
                axQsGridCellWidth(paneWidth, controlColumns, spacing).coerceAtLeast(0.dp)
            val visibleTileColumns =
                tileColumns.coerceIn(
                    1,
                    ((paneWidth + spacing) / (LandscapeMinCellSize + spacing))
                        .toInt()
                        .coerceAtLeast(1),
                )
            val visibleTileRows =
                axQsVisibleTileRows(tileItems.size, visibleTileColumns, maxTileRows)
            val pagerState = rememberPagerState {
                axQsTileGridPageCount(tileItems.size, visibleTileColumns, visibleTileRows)
            }
            val labelHeight = if (showTileLabels) AX_TILE_LABEL_HEIGHT else 0.dp
            val indicatorHeight = if (pagerState.pageCount > 1) 6.dp + spacing else 0.dp
            val tileRowCount = visibleTileRows.coerceAtLeast(1)
            val tileHeightBudget =
                ((maxHeight - indicatorHeight - spacing * (tileRowCount - 1)) / tileRowCount -
                    labelHeight).coerceAtLeast(LandscapeMinCellSize)
            val tileCellSize =
                minOf(
                    axQsGridCellWidth(paneWidth, visibleTileColumns, spacing).coerceAtLeast(0.dp),
                    AX_TILE_MAX_SIZE,
                    tileHeightBudget,
                )
            val tileGridWidth =
                tileCellSize * visibleTileColumns + spacing * (visibleTileColumns - 1)
            Row(
                modifier = Modifier.fillMaxSize().element(AxQsGridContent),
                horizontalArrangement = Arrangement.spacedBy(paneSpacing),
            ) {
                if (controlItems.isNotEmpty()) {
                    Box(
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .clipScrollableContainer(Orientation.Vertical)
                            .verticalScroll(scrollState, overscrollEffect = null)
                            .motionEventSpy { event ->
                                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                    onScrollGestureStart?.invoke(scrollState)
                                }
                            }
                    ) {
                        AxQsGrid(
                            items = controlItems,
                            columns = controlColumns,
                            rowHeight = controlCellHeight,
                            spacing = spacing,
                            maxRows = controlRows,
                            squareCells = true,
                            modifier = Modifier.fillMaxWidth(),
                            content = controlContent,
                        )
                    }
                }
                if (tileItems.isNotEmpty()) {
                    val tilePaneScrollState =
                        if (controlItems.isEmpty()) scrollState else tileScrollState
                    Box(
                        modifier =
                            Modifier.weight(1f)
                                .fillMaxHeight()
                                .clipScrollableContainer(Orientation.Vertical)
                                .verticalScroll(
                                    tilePaneScrollState,
                                    overscrollEffect = null,
                                )
                                .motionEventSpy { event ->
                                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                        onScrollGestureStart?.invoke(tilePaneScrollState)
                                    }
                                },
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Column(Modifier.width(tileGridWidth)) {
                            AxQsTileGrid(
                                items = tileItems,
                                columns = visibleTileColumns,
                                rows = visibleTileRows,
                                spacing = spacing,
                                showLabels = showTileLabels,
                                circleCells = true,
                                pagerState = pagerState,
                                modifier = Modifier.fillMaxWidth(),
                                content = tileContent,
                                label = tileLabel,
                            )
                            AxQsPagerIndicator(
                                pagerState = pagerState,
                                modifier = Modifier.fillMaxWidth().padding(top = spacing),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val LandscapeMinCellSize = 48.dp
