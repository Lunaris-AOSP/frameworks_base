/*
 * Copyright 2025-2026 AxionOS
 * Copyright 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.ContentScope
import com.android.compose.gesture.gesturesDisabled
import com.android.systemui.qs.panels.shared.model.AxQsGridItem
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel

@Composable
internal fun <T> ContentScope.AxQsSharedPanel(
    toolbarViewModel: ToolbarViewModel,
    shadeHeaderViewModel: ShadeHeaderViewModel,
    controlItems: List<AxQsGridItem<T>>,
    tileItems: List<AxQsGridItem<T>>,
    controlColumns: Int,
    controlRows: Int,
    tileColumns: Int,
    tileRows: Int,
    collapsedControlRows: Int,
    collapsedTileRows: Int,
    rowHeight: Dp,
    spacing: Dp,
    circleCells: Boolean,
    showTileLabels: Boolean,
    showWeather: Boolean,
    scrollState: ScrollState,
    expansionProgress: () -> Float,
    isFullyVisible: () -> Boolean,
    onCollapsedHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    controlContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileLabel: @Composable (AxQsGridItem<T>) -> Unit,
) {
    val pagerState = rememberPagerState {
        axQsTileGridPageCount(tileItems.size, tileColumns, tileRows)
    }
    val currentExpansionProgress by rememberUpdatedState(expansionProgress)
    var expandedInteraction by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { currentExpansionProgress() }
            .collect { expansion ->
                if (expansion >= SharedQsExpandThreshold) expandedInteraction = true
                else if (expansion <= SharedQsCollapseThreshold) expandedInteraction = false
            }
    }
    val fullyExpanded by remember { derivedStateOf { currentExpansionProgress() >= 1f } }
    val hasScroll by remember(scrollState) { derivedStateOf { scrollState.value > 0 } }
    LaunchedEffect(fullyExpanded) { if (!fullyExpanded) pagerState.scrollToPage(0) }
    val tileCollapseTranslation = {
        if (collapsedTileRows > 0) {
            scrollState.value * (1f - expansionProgress().coerceIn(0f, 1f))
        } else {
            0f
        }
    }
    var headerHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val tileHeight =
            if (circleCells) axQsGridCellWidth(maxWidth, tileColumns, spacing) else rowHeight
        val tileRowHeight = tileHeight + if (showTileLabels) AX_TILE_LABEL_HEIGHT else 0.dp
        val collapsedHeight =
            with(density) {
                val spacingPx = spacing.roundToPx()
                val controlRowHeightPx =
                    if (circleCells) {
                        (maxWidth.roundToPx() - spacingPx * (controlColumns - 1)).coerceAtLeast(0) /
                            controlColumns
                    } else {
                        rowHeight.roundToPx()
                    }
                val controlsHeight =
                    sharedQsRowsHeight(collapsedControlRows, controlRowHeightPx, spacingPx)
                val tilesHeight =
                    sharedQsRowsHeight(collapsedTileRows, tileRowHeight.roundToPx(), spacingPx)
                val sectionGap =
                    if (collapsedControlRows > 0 && collapsedTileRows > 0) spacingPx else 0
                val headerGap =
                    if (collapsedControlRows > 0 || collapsedTileRows > 0) spacingPx else 0
                (headerHeight + controlsHeight + tilesHeight + sectionGap + headerGap).coerceAtMost(
                    maxHeight.roundToPx()
                )
            }
        LaunchedEffect(collapsedHeight, onCollapsedHeightChanged) {
            onCollapsedHeightChanged(collapsedHeight)
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            AxQsDateHeader(
                toolbarViewModel = toolbarViewModel,
                shadeHeaderViewModel = shadeHeaderViewModel,
                showEdit = true,
                showWeather = showWeather,
                isFullyVisible = isFullyVisible,
                editButtonProgress = { sharedQsRevealAlpha(expansionProgress()) },
                modifier = Modifier.onSizeChanged { headerHeight = it.height },
            )
            Column(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .clipScrollableContainer(Orientation.Vertical)
                    .verticalScroll(
                        state = scrollState,
                        enabled = fullyExpanded || hasScroll,
                        overscrollEffect = null,
                    ),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                if (controlItems.isNotEmpty()) {
                    AxQsGrid(
                        items = controlItems,
                        columns = controlColumns,
                        rowHeight = rowHeight,
                        spacing = spacing,
                        maxRows = controlRows,
                        squareCells = circleCells,
                        modifier = Modifier.fillMaxWidth(),
                    ) { item ->
                        val row = item.position?.row ?: 0
                        AxQsSharedItem(
                            isCollapsedItem = row + item.span.rows <= collapsedControlRows,
                            row = row,
                            expandedInteraction = expandedInteraction,
                            scrollState = scrollState,
                            progress = expansionProgress,
                        ) {
                            controlContent(item)
                        }
                    }
                }
                if (tileItems.isNotEmpty()) {
                    Column {
                        AxQsTileGrid(
                            items = tileItems,
                            columns = tileColumns,
                            rows = tileRows,
                            spacing = spacing,
                            showLabels = showTileLabels,
                            circleCells = circleCells,
                            pagerState = pagerState,
                            userScrollEnabled = fullyExpanded,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .graphicsLayer { translationY = tileCollapseTranslation() }
                                    .then(
                                        if (collapsedTileRows == 0 && !expandedInteraction) {
                                            Modifier.focusProperties { canFocus = false }
                                                .clearAndSetSemantics { hideFromAccessibility() }
                                        } else {
                                            Modifier
                                        }
                                    ),
                            itemWrapper = { page, index, content ->
                                val row = index / tileColumns
                                AxQsSharedItem(
                                    isCollapsedItem = page == 0 && row < collapsedTileRows,
                                    row = controlRows + row,
                                    expandedInteraction = expandedInteraction,
                                    scrollState = scrollState,
                                    progress = expansionProgress,
                                    parentCollapseTranslationY = tileCollapseTranslation,
                                    content = content,
                                )
                            },
                            content = tileContent,
                            label = tileLabel,
                        )
                        AxQsSharedItem(
                            isCollapsedItem = false,
                            row = controlRows + tileRows,
                            expandedInteraction = expandedInteraction,
                            scrollState = scrollState,
                            progress = expansionProgress,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
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

@Composable
private fun AxQsSharedItem(
    isCollapsedItem: Boolean,
    row: Int,
    expandedInteraction: Boolean,
    scrollState: ScrollState,
    progress: () -> Float,
    modifier: Modifier = Modifier.fillMaxSize(),
    parentCollapseTranslationY: () -> Float = { 0f },
    content: @Composable () -> Unit,
) {
    val currentProgress by rememberUpdatedState(progress)
    val visible by
        remember(isCollapsedItem) { derivedStateOf { isCollapsedItem || currentProgress() > 0f } }
    val interactionModifier =
        if (isCollapsedItem || expandedInteraction) Modifier
        else {
            Modifier.gesturesDisabled()
                .focusProperties { canFocus = false }
                .clearAndSetSemantics { hideFromAccessibility() }
        }
    Box(
        modifier.then(interactionModifier).graphicsLayer {
            val expansion = progress().coerceIn(0f, 1f)
            if (isCollapsedItem) {
                translationY = scrollState.value * (1f - expansion)
            } else {
                val collapseFade =
                    if (scrollState.value > 0) ((expansion - 0.7f) / 0.3f).coerceIn(0f, 1f) else 1f
                alpha = sharedQsRevealAlpha(expansion) * collapseFade
                val scale = 0.85f + 0.15f * expansion
                scaleX = scale
                scaleY = scale
                translationY = -(1f - expansion) * 24f * ((row - 1) * 0.5f).coerceAtLeast(1f)
            }
            translationY -= parentCollapseTranslationY()
        }
    ) {
        if (visible) content()
    }
}

internal fun Modifier.axSharedQsEntrance(progress: () -> Float): Modifier = graphicsLayer {
    val entrance = progress().coerceIn(0f, 1f)
    alpha = (entrance / 0.35f).coerceIn(0f, 1f)
    val scale = 0.92f + 0.08f * entrance
    scaleX = scale
    scaleY = scale
    transformOrigin = TransformOrigin(0.5f, 0f)
    translationY = -(1f - entrance) * 180f
}

private fun sharedQsRowsHeight(rows: Int, rowHeight: Int, spacing: Int): Int =
    if (rows == 0) 0 else rowHeight * rows + spacing * (rows - 1)

internal fun sharedQsRevealAlpha(progress: Float): Float =
    ((progress - SharedQsCollapseThreshold) / (1f - SharedQsCollapseThreshold)).coerceIn(0f, 1f)

private const val SharedQsExpandThreshold = 0.6f
private const val SharedQsCollapseThreshold = 0.4f
