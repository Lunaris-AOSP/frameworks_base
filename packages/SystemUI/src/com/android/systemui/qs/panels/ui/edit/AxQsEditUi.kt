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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.qs.panels.ui.edit

import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.qs.panels.shared.model.AxQsControl
import com.android.systemui.qs.panels.shared.model.AxQsControlSpans
import com.android.systemui.qs.panels.shared.model.AxQsGridItem
import com.android.systemui.qs.panels.shared.model.AxQsGridLayout
import com.android.systemui.qs.panels.shared.model.AxQsGridPosition
import com.android.systemui.qs.panels.shared.model.AxQsGridSection
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.AxQsPanelMode
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.shared.model.AxQsTileStacks
import com.android.systemui.qs.panels.shared.model.LunarisQsSliderStyle
import com.android.systemui.qs.panels.ui.compose.AX_QS_CONTROL_MAX_ROWS
import com.android.systemui.qs.panels.ui.compose.AxInteractiveTileContainer
import com.android.systemui.qs.panels.ui.compose.AxQuickSettingsLayoutDefaults
import com.android.systemui.qs.panels.ui.compose.AxQsGrid
import com.android.systemui.qs.panels.ui.compose.AxTileDefaults
import com.android.systemui.qs.panels.ui.compose.LocalTileScale
import com.android.systemui.qs.panels.ui.compose.LocalAxQsTileStyle
import com.android.systemui.qs.panels.ui.compose.LunarisQsSliderOrientationButton
import com.android.systemui.qs.panels.ui.compose.axQsControlShape
import com.android.systemui.qs.panels.ui.compose.axQsGridCellWidth
import com.android.systemui.qs.panels.ui.compose.axQsGridRowCount
import com.android.systemui.qs.panels.ui.compose.axQsResizeHandle
import com.android.systemui.qs.panels.ui.compose.axQsSharedGridRows
import com.android.systemui.qs.panels.ui.compose.axQsSliderTrackHeight
import com.android.systemui.qs.panels.ui.compose.axQsTileShape
import com.android.systemui.qs.panels.ui.compose.canFitAxQsGridItems
import com.android.systemui.qs.panels.ui.compose.canFitAxQsSharedGrid
import com.android.systemui.qs.panels.ui.compose.fitAxQsGridItems
import com.android.systemui.qs.panels.ui.compose.selection.TileState
import com.android.systemui.qs.panels.ui.compose.useAxQsCircleCells
import com.android.systemui.qs.panels.ui.viewmodel.AxQsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditModeViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.ui.composable.QuickSettingsShade
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ui.dialog.viewmodel.ModeTileViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
private fun axString(resName: String, fallback: String): String {
    val context = LocalContext.current
    val id = remember(resName) { context.resources.getIdentifier(resName, "string", context.packageName) }
    return if (id != 0) stringResource(id) else fallback
}

private sealed interface AxEditGridValue {
    val section: AxQsGridSection

    data class Tile(
        val viewModel: EditTileViewModel,
        override val section: AxQsGridSection,
    ) : AxEditGridValue

    data class Control(val control: AxQsControl, override val section: AxQsGridSection) :
        AxEditGridValue

    data class Stack(val tileSpecs: List<String>, val tiles: List<EditTileViewModel>) :
        AxEditGridValue {
        override val section = AxQsGridSection.CONTROLS
    }
}

@Composable
fun AxQsEditUi(
    editModeViewModel: EditModeViewModel,
    axQsViewModel: AxQsViewModel,
    controlPreview:
        @Composable (AxQsControl, AxQsSpan, Int, LunarisQsSliderStyle) -> Unit,
    onOpenPanelSettings: () -> Unit,
    animateItemBounds: Boolean,
    splitShade: Boolean,
    onResetTiles: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val allTiles by editModeViewModel.tiles.collectAsStateWithLifecycle(initialValue = null)
    var editQqs by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    var viewportBounds by remember { mutableStateOf(Rect.Zero) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val showQqsEditor =
        !landscape && !splitShade && axQsViewModel.panelMode == AxQsPanelMode.TOGETHER
    var resetEpoch by remember { mutableStateOf(0) }
    var showResetDialog by remember { mutableStateOf(false) }

    BackHandler { editModeViewModel.stopEditing() }
    LaunchedEffect(showQqsEditor) { if (!showQqsEditor) editQqs = false }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(com.android.internal.R.string.reset)) },
            text = { Text("Reset all Quick Settings tiles to default layout?") },
            confirmButton = {
                TextButton(onClick = {
                    onResetTiles()
                    axQsViewModel.resetLayout()
                    resetEpoch++
                    showResetDialog = false
                }) { Text(stringResource(com.android.internal.R.string.reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(horizontal = axQsViewModel.spacing.sidePadding.dp)
                    .onGloballyPositioned { viewportBounds = it.boundsInRoot() }
                    .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (landscape) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        FilledTonalButton(
                            onClick = onOpenPanelSettings,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(axString("ax_qs_panel_settings", "Panel settings"))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { showResetDialog = true },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(com.android.internal.R.string.reset))
                        }
                        Button(
                            onClick = editModeViewModel::stopEditing,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(R.string.quick_settings_done))
                        }
                    }
                }

                Text(
                    text = axString("ax_qs_reorder_education", "Hold and drag to reorder controls and tiles"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            }

            val layout =
                when {
                    splitShade -> AxQsLayout.SPLIT_SHADE
                    editQqs -> AxQsLayout.QQS
                    else -> AxQsLayout.QS
                }
            var renderedLayout by remember { mutableStateOf(layout) }
            var isSwitchingLayout by remember { mutableStateOf(false) }

            LaunchedEffect(layout) {
                if (renderedLayout != layout) {
                    isSwitchingLayout = true
                    delay(32)
                    renderedLayout = layout
                    delay(180)
                    isSwitchingLayout = false
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                AnimatedContent(
                    targetState = renderedLayout,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                            scaleIn(
                                initialScale = 0.95f,
                                animationSpec = tween(240, easing = FastOutSlowInEasing),
                            ))
                            .togetherWith(
                                fadeOut(animationSpec = tween(160, easing = FastOutLinearInEasing)) +
                                    scaleOut(
                                        targetScale = 0.95f,
                                        animationSpec = tween(160, easing = FastOutLinearInEasing),
                                    )
                            )
                    },
                    label = "AxQsEditLayoutSwitch",
                ) { currentLayout ->
                    allTiles?.let { tiles ->
                        key(currentLayout, resetEpoch) {
                            AxEditableGrid(
                                allTiles = tiles,
                                editModeViewModel = editModeViewModel,
                                axQsViewModel = axQsViewModel,
                                controlPreview = controlPreview,
                                layout = currentLayout,
                                scrollState = scrollState,
                                viewportBounds = viewportBounds,
                                animateItemBounds = animateItemBounds && !isSwitchingLayout,
                            )
                        }
                    }
                }

                val loadingAlpha by
                    animateFloatAsState(
                        targetValue = if (isSwitchingLayout) 1f else 0f,
                        animationSpec = tween(if (isSwitchingLayout) 140 else 180),
                        label = "AxQsLoadingIndicatorAlpha",
                    )
                if (loadingAlpha > 0f) {
                    Box(
                        modifier =
                            Modifier.align(Alignment.Center)
                                .padding(top = 64.dp)
                                .zIndex(20f)
                                .graphicsLayer { alpha = loadingAlpha }
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            val bottomSpacing = if (showQqsEditor) 100.dp + navBarBottom else 32.dp
            Spacer(Modifier.height(bottomSpacing))
        }

        if (showQqsEditor) {
            AxQsEditFloatingBottomBar(
                editQqs = editQqs,
                onSelectQqs = { editQqs = true },
                onSelectQs = { editQqs = false },
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .zIndex(100f)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun AxQsEditFloatingBottomBar(
    editQqs: Boolean,
    onSelectQqs: () -> Unit,
    onSelectQs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
        content = {
            AxQsEditFloatingTab(
                title = axString("ax_qs_top_quick_settings", "Top Quick Settings"),
                selected = editQqs,
                onClick = onSelectQqs,
            )
            AxQsEditFloatingTab(
                title = axString("ax_qs_full_quick_settings", "Full Quick Settings"),
                selected = !editQqs,
                onClick = onSelectQs,
            )
        },
    )
}

@Composable
private fun AxQsEditFloatingTab(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor by
        animateColorAsState(
            targetValue =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
            label = "AxQsEditFloatingTabBg",
        )
    val contentColor by
        animateColorAsState(
            targetValue =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            label = "AxQsEditFloatingTabFg",
        )
    Box(
        modifier =
            Modifier.clip(CircleShape)
                .background(containerColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun AxEditableGrid(
    allTiles: List<EditTileViewModel>,
    editModeViewModel: EditModeViewModel,
    axQsViewModel: AxQsViewModel,
    controlPreview:
        @Composable (AxQsControl, AxQsSpan, Int, LunarisQsSliderStyle) -> Unit,
    layout: AxQsLayout,
    scrollState: ScrollState,
    viewportBounds: Rect,
    animateItemBounds: Boolean,
    modifier: Modifier = Modifier,
) {
    val currentTiles = allTiles.filter(EditTileViewModel::isCurrent)
    val qqs = layout == AxQsLayout.QQS
    val splitShade = layout == AxQsLayout.SPLIT_SHADE
    val boundedRows = qqs || splitShade
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val controlGridLayout = AxQsGridLayout.from(layout, AxQsGridSection.CONTROLS)
    val tileGridLayout = AxQsGridLayout.from(layout, AxQsGridSection.TILES)
    val controlColumns = axQsViewModel.columns(controlGridLayout)
    val tileColumns = axQsViewModel.columns(tileGridLayout)
    val pickerControlColumns = controlColumns
    val pickerTileColumns = tileColumns
    val sliderStyle: (AxQsControl) -> LunarisQsSliderStyle = { control ->
        axQsViewModel.sliderStyle(layout, control)
    }
    val savedControlOrder = axQsViewModel.order(layout, AxQsGridSection.CONTROLS)
    val savedTileOrder = axQsViewModel.order(layout, AxQsGridSection.TILES)
    val savedSpans = axQsViewModel.spans(layout)
    val savedStacks = axQsViewModel.stacks(layout)
    val modes by axQsViewModel.modes.collectAsStateWithLifecycle(emptyList())
    val savedControlPositions = axQsViewModel.controlPositions(layout)
    val sourceItems =
        remember(
            currentTiles,
            allTiles,
            layout,
            controlColumns,
            tileColumns,
            savedControlOrder,
            savedTileOrder,
            savedSpans,
            savedControlPositions,
            savedStacks,
        ) {
            buildEditItems(
                currentTiles = currentTiles,
                allTiles = allTiles,
                layout = layout,
                controlColumns = controlColumns,
                tileColumns = tileColumns,
                controlPositions = savedControlPositions,
                viewModel = axQsViewModel,
            )
        }
    var selectedId by remember(layout) { mutableStateOf<String?>(null) }
    var editingStackId by remember(layout) { mutableStateOf<String?>(null) }
    var pendingStacks by remember(layout) { mutableStateOf<Map<String, List<String>>?>(null) }
    var pendingControlOrder by remember(layout) { mutableStateOf<List<String>?>(null) }
    var pendingTileOrder by remember(layout) { mutableStateOf<List<String>?>(null) }
    var pendingSpans by remember(layout) { mutableStateOf<Map<String, AxQsSpan>>(emptyMap()) }
    var pickerSliderSpans by
        remember(layout) { mutableStateOf<Map<AxQsControl, AxQsSpan>>(emptyMap()) }
    val listState = remember(layout) { AxQsEditListState(sourceItems) }
    val hapticFeedback = LocalHapticFeedback.current
    val saveSpan: (String, AxQsSpan) -> Unit = { id, span ->
        pendingSpans = pendingSpans + (id to span)
    }
    val saveOrders = {
        val positions = listState.positions()
        val controlOrder =
            listState.items.filter { it.section == AxQsGridSection.CONTROLS }.map { it.id }
        val tileOrder = listState.items.filter { it.section == AxQsGridSection.TILES }.map { it.id }
        val controlIds = controlOrder.toSet()
        val controlSpans =
            listState.items
                .filter { it.section == AxQsGridSection.CONTROLS }
                .associate { it.id to it.span }
        pendingSpans = controlSpans
        pendingControlOrder = controlOrder
        pendingTileOrder = tileOrder
        val stacks =
            listState.items
                .mapNotNull { item ->
                    (item.value as? AxEditGridValue.Stack)?.let { item.id to it.tileSpecs }
                }
                .toMap()
        pendingStacks = stacks
        axQsViewModel.saveLayout(
            layout,
            controlOrder,
            tileOrder,
            controlSpans,
            positions.filterKeys(controlIds::contains),
            stacks,
        )
    }

    LaunchedEffect(
        sourceItems,
        listState.draggedId,
        pendingControlOrder,
        pendingTileOrder,
        savedControlOrder,
        savedTileOrder,
        pendingSpans,
        savedSpans,
        pendingStacks,
        savedStacks,
    ) {
        if (pendingStacks != null && savedStacks != pendingStacks) return@LaunchedEffect
        if (pendingSpans.any { (id, span) -> savedSpans[id] != span }) {
            return@LaunchedEffect
        }
        if (pendingControlOrder != null && savedControlOrder != pendingControlOrder) {
            return@LaunchedEffect
        }
        if (pendingTileOrder != null && savedTileOrder != pendingTileOrder) {
            return@LaunchedEffect
        }
        pendingSpans = emptyMap()
        pendingControlOrder = null
        pendingTileOrder = null
        pendingStacks = null
        if (!listState.dragInProgress && listState.items != sourceItems) {
            listState.updateItems(sourceItems)
            selectedId = selectedId?.takeIf { id -> sourceItems.any { it.id == id } }
        }
    }

    val scale = LocalTileScale.current
    val rowHeight = AxTileDefaults.TileHeight * scale
    val spacing = axQsViewModel.spacing.tileGap.dp * scale
    val fitsLayout: (List<AxQsGridItem<AxEditGridValue>>) -> Boolean = { items ->
        val controls = items.filter { it.section == AxQsGridSection.CONTROLS }
        val tileCount = items.count { it.section == AxQsGridSection.TILES }
        if (boundedRows) {
            canFitAxQsSharedGrid(controls, controlColumns, tileCount, tileColumns)
        } else {
            canFitAxQsGridItems(controls, controlColumns, AX_QS_CONTROL_MAX_ROWS)
        }
    }
    val fitsAfterControlRepack: (List<AxQsGridItem<AxEditGridValue>>) -> Boolean = { items ->
        fitsLayout(items) ||
            fitsLayout(
                items.map {
                    if (it.section == AxQsGridSection.CONTROLS) {
                        it.copy(position = null)
                    } else {
                        it
                    }
                }
            )
    }
    val transformSection:
        (AxQsGridItem<AxEditGridValue>, AxQsGridSection) -> AxQsGridItem<AxEditGridValue>? =
        { item, section ->
            val value = item.value
            val allowed =
                when (value) {
                    is AxEditGridValue.Tile -> true
                    is AxEditGridValue.Stack -> section == AxQsGridSection.CONTROLS
                    is AxEditGridValue.Control ->
                        section == AxQsGridSection.CONTROLS || value.control.canUseTileGrid
                }
            if (!allowed) {
                null
            } else {
                val spans =
                    when {
                        section == AxQsGridSection.TILES ->
                            TileGridSpans
                        value is AxEditGridValue.Control -> value.control.spans(controlColumns)
                        value is AxEditGridValue.Stack ->
                            AxQsControlSpans(
                                AxQsTileStacks.Span,
                                AxQsTileStacks.Span,
                                AxQsTileStacks.Span,
                            )
                        else ->
                            AxQsControlSpans(
                                item.span.coerceForControlTile(controlColumns),
                                AxQsSpan.ControlTileMin,
                                AxQsSpan.controlTileMax(controlColumns),
                            )
                    }
                val moved =
                    item.copy(
                        span =
                            if (section == AxQsGridSection.TILES) {
                                AxQsSpan.TileDefault
                            } else if (value is AxEditGridValue.Control) {
                                value.control.coerceSpan(item.span, controlColumns)
                            } else {
                                item.span.coerceForControlTile(controlColumns)
                            },
                        minSpan = spans.min,
                        maxSpan = spans.max,
                        value = value.inSection(section),
                        position = null,
                    )
                val candidates = listState.items.filter { it.id != item.id } + moved
                moved.takeIf { fitsAfterControlRepack(candidates) }
            }
        }

    val editingStack = listState.items.firstOrNull { it.id == editingStackId }
    val editingValue = editingStack?.value as? AxEditGridValue.Stack
    if (editingStack != null && editingValue != null) {
        val otherMembers =
            listState.items
                .filter { it.id != editingStack.id }
                .flatMap { (it.value as? AxEditGridValue.Stack)?.tileSpecs.orEmpty() }
                .toSet()
        AxTileStackEditor(
            initialSpecs = editingValue.tileSpecs,
            availableTiles = allTiles.filterNot { it.tileSpec.spec in otherMembers },
            onDismiss = { editingStackId = null },
            onSave = { specs ->
                val members = specs.distinct().filterNot(otherMembers::contains)
                val tilesBySpec = allTiles.associateBy { it.tileSpec.spec }
                val newTiles = members.mapNotNull(tilesBySpec::get).filterNot { it.isCurrent }
                if (newTiles.isNotEmpty()) {
                    editModeViewModel.addTiles(newTiles.map { it.tileSpec })
                }
                listState.items
                    .filter { it.value is AxEditGridValue.Tile && it.id in members }
                    .map { it.id }
                    .forEach(listState::remove)
                listState.update(editingStack.id) {
                    it.copy(
                        value = AxEditGridValue.Stack(members, members.mapNotNull(tilesBySpec::get))
                    )
                }
                editingStackId = null
                saveOrders()
            },
        )
    }

    AxQsDragAutoScroll(listState, scrollState, viewportBounds)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val controlCapacityRows =
            if (
                boundedRows && listState.items.any { it.section == AxQsGridSection.TILES }
            ) {
                AX_QS_CONTROL_MAX_ROWS - 1
            } else {
                AX_QS_CONTROL_MAX_ROWS
            }
        val controlSection: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = axString("ax_qs_controls", "Controls"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                AxEditableGridSection(
                    section = AxQsGridSection.CONTROLS,
                    listState = listState,
                    columns = controlColumns,
                    maxRows = controlCapacityRows,
                    rowHeight = rowHeight,
                    spacing = spacing,
                    animateItemBounds = animateItemBounds && listState.dragInProgress,
                    selectedId = selectedId,
                    onSelected = { selectedId = it },
                    onSave = saveOrders,
                    onSaveSpan = saveSpan,
                    onTransformSection = transformSection,
                    onEditStack = { editingStackId = it },
                    controlPreview = controlPreview,
                    sliderStyle = sliderStyle,
                    hapticFeedback = hapticFeedback,
                    modes = modes,
                )
            }
        }
        val tileSection: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = axString("qs_edit_tiles", "Tiles"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                AxEditableGridSection(
                    section = AxQsGridSection.TILES,
                    listState = listState,
                    columns = tileColumns,
                    maxRows = null,
                    rowHeight = rowHeight,
                    spacing = spacing,
                    animateItemBounds = animateItemBounds && listState.dragInProgress,
                    selectedId = selectedId,
                    onSelected = { selectedId = it },
                    onSave = saveOrders,
                    onSaveSpan = saveSpan,
                    onTransformSection = transformSection,
                    onEditStack = { editingStackId = it },
                    controlPreview = controlPreview,
                    sliderStyle = sliderStyle,
                    hapticFeedback = hapticFeedback,
                    modes = modes,
                )
            }
        }
        if (landscape && !splitShade) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(AxQuickSettingsLayoutDefaults.LandscapeGridSpacing),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.weight(1f)) { controlSection() }
                Box(Modifier.weight(1f)) { tileSection() }
            }
        } else {
            controlSection()
            tileSection()
        }
        val addGridItem: (AxAddItem) -> AxQsGridItem<AxEditGridValue>? = { addItem ->
            if (
                listState.items.any {
                    it.id == addItem.id ||
                        (it.value as? AxEditGridValue.Stack)?.tileSpecs?.contains(addItem.id) ==
                            true
                }
            ) {
                null
            } else {
                val section =
                    if (
                        addItem is AxAddItem.Stack ||
                            (addItem is AxAddItem.Control && !addItem.control.canUseTileGrid)
                    ) {
                        AxQsGridSection.CONTROLS
                    } else {
                        AxQsGridSection.TILES
                    }
                val columns =
                    if (section == AxQsGridSection.CONTROLS) controlColumns else tileColumns
                addItem
                    .toEditGridItem(columns, section)
                    .takeIf { fitsAfterControlRepack(listState.items + it) }
            }
        }
        val canAddItem: (AxAddItem) -> Boolean = { addGridItem(it) != null }
        AxAvailableControls(
            allTiles = allTiles,
            currentIds =
                listState.items
                    .flatMap {
                        listOf(it.id) + (it.value as? AxEditGridValue.Stack)?.tileSpecs.orEmpty()
                    }
                    .toSet(),
            controlColumns = pickerControlColumns,
            tileColumns = pickerTileColumns,
            spacing = spacing,
            sliderStyle = sliderStyle,
            onSliderStyleChanged = { control, style ->
                axQsViewModel.setSliderStyle(layout, control, style)
            },
            sliderSpan = { control ->
                pickerSliderSpans[control]
                    ?: axQsViewModel.span(control.id, layout, control.defaultSpan)
            },
            onSliderSpanChanged = { control, span ->
                pickerSliderSpans = pickerSliderSpans + (control to span)
            },
            controlPreview = { control, span, style ->
                controlPreview(control, span, pickerControlColumns, style)
            },
            canAdd = canAddItem,
            onAdd = { addItem ->
                val item = addGridItem(addItem) ?: return@AxAvailableControls
                selectedId = null
                if (addItem is AxAddItem.Tile && !addItem.viewModel.isCurrent) {
                    editModeViewModel.addTile(addItem.viewModel.tileSpec)
                }
                if (item.section == AxQsGridSection.CONTROLS) {
                    val controls =
                        listState.items.filter {
                            it.section == AxQsGridSection.CONTROLS
                        }
                    if (
                        !canFitAxQsGridItems(
                            controls + item,
                            controlColumns,
                            if (
                                boundedRows &&
                                    listState.items.any {
                                        it.section == AxQsGridSection.TILES
                                    }
                            ) {
                                AX_QS_CONTROL_MAX_ROWS - 1
                            } else {
                                AX_QS_CONTROL_MAX_ROWS
                            },
                        )
                    ) {
                        listState.repack(controls.mapTo(mutableSetOf()) { it.id })
                    }
                }
                listState.add(item)
                if (addItem is AxAddItem.Stack) editingStackId = item.id
                if (addItem is AxAddItem.Control) {
                    pickerSliderSpans = pickerSliderSpans - addItem.control
                }
                if (item.section == AxQsGridSection.CONTROLS) {
                    saveSpan(item.id, item.span)
                }
                saveOrders()
            },
        )
    }
}

@Composable
private fun AxEditableGridSection(
    section: AxQsGridSection,
    listState: AxQsEditListState<AxEditGridValue>,
    columns: Int,
    maxRows: Int?,
    rowHeight: Dp,
    spacing: Dp,
    animateItemBounds: Boolean,
    selectedId: String?,
    onSelected: (String?) -> Unit,
    onSave: () -> Unit,
    onSaveSpan: (String, AxQsSpan) -> Unit,
    onTransformSection:
        (AxQsGridItem<AxEditGridValue>, AxQsGridSection) -> AxQsGridItem<AxEditGridValue>?,
    onEditStack: (String) -> Unit,
    controlPreview: @Composable (AxQsControl, AxQsSpan, Int, LunarisQsSliderStyle) -> Unit,
    sliderStyle: (AxQsControl) -> LunarisQsSliderStyle,
    hapticFeedback: HapticFeedback,
    modes: List<ModeTileViewModel>,
) {
    val items = listState.items.filter { it.section == section }
    val editStackLabel = stringResource(R.string.lunaris_qs_stack_edit)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gridPadding = if (section == AxQsGridSection.CONTROLS) EditGridPadding else 0.dp
        val availableWidth = (maxWidth - gridPadding * 2).coerceAtLeast(0.dp)
        val cellWidth = axQsGridCellWidth(availableWidth, columns, spacing)
        val circleCells =
            useAxQsCircleCells(
                gridWidth = availableWidth,
                tileColumns = columns,
                spacing = spacing,
                allowCircles = true,
            )
        val measuredRowHeight = if (circleCells) cellWidth else rowHeight
        val contentRows = axQsGridRowCount(items, columns, maxRows)
        val visibleRows = contentRows.coerceAtLeast(1)
        val gridBorder =
            if (section == AxQsGridSection.CONTROLS) {
                Modifier.border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(EditGridCornerRadius),
                )
            } else {
                Modifier
            }
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .then(gridBorder)
                    .pointerInput(selectedId) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            if (waitForUpOrCancellation() != null) onSelected(null)
                        }
                    }
                    .axQsDropTarget(
                        state = listState,
                        section = section,
                        sectionOf = { it.section },
                        transform = onTransformSection,
                        onDrop = onSave,
                        canStack = { source, target ->
                            source.value is AxEditGridValue.Tile &&
                                target.section == AxQsGridSection.CONTROLS &&
                                (target.value is AxEditGridValue.Stack ||
                                    (target.value is AxEditGridValue.Tile &&
                                        target.span == AxQsTileStacks.Span))
                        },
                        onStack = { source, target ->
                            val sourceTile = (source.value as? AxEditGridValue.Tile)?.viewModel
                            val targetValue = target.value
                            val targetSpecs =
                                when (targetValue) {
                                    is AxEditGridValue.Stack -> targetValue.tileSpecs
                                    is AxEditGridValue.Tile -> listOf(target.id)
                                    else -> emptyList()
                                }
                            val targetTiles =
                                when (targetValue) {
                                    is AxEditGridValue.Stack -> targetValue.tiles
                                    is AxEditGridValue.Tile -> listOf(targetValue.viewModel)
                                    else -> emptyList()
                                }
                            if (sourceTile == null || target.span != AxQsTileStacks.Span) {
                                null
                            } else {
                                target.copy(
                                    id =
                                        if (targetValue is AxEditGridValue.Stack) target.id
                                        else AxQsTileStacks.newId(),
                                    minSpan = AxQsTileStacks.Span,
                                    maxSpan = AxQsTileStacks.Span,
                                    value =
                                        AxEditGridValue.Stack(
                                            (targetSpecs + source.id).distinct(),
                                            (targetTiles + sourceTile).distinctBy {
                                                it.tileSpec.spec
                                            },
                                        ),
                                )
                            }
                        },
                    )
                    .padding(gridPadding)
        ) {
            var resizingItemId by remember { mutableStateOf<String?>(null) }
            AxQsGrid(
                items = items,
                columns = columns,
                rowHeight = rowHeight,
                spacing = spacing,
                maxRows = maxRows,
                minimumRows = visibleRows,
                squareCells = circleCells,
                animateItemBounds = animateItemBounds,
                staticItemId = listState.draggedId ?: resizingItemId,
                onItemBounds = { id, bounds -> listState.updateItemBounds(id, section, bounds) },
                onCells =
                    if (section == AxQsGridSection.CONTROLS) {
                        { cells -> listState.updateGridCells(section, cells) }
                    } else {
                        null
                    },
                modifier =
                    Modifier.fillMaxWidth().onGloballyPositioned {
                        listState.updateGridOrigin(section, it.positionInRoot())
                    },
            ) { item ->
                val isSelected = selectedId == item.id
                val isStackTarget = listState.hoveredStackTargetId == item.id
                val moveEarlierLabel = axString("ax_qs_move_earlier", "Move earlier")
                val moveLaterLabel = axString("ax_qs_move_later", "Move later")
                val moveItem: (Int) -> Boolean = { delta ->
                    listState
                        .moveBy(item.id, delta) { it.section }
                        .also { moved -> if (moved) onSave() }
                }
                val removeItem = {
                    listState.remove(item.id)
                    onSave()
                    onSelected(null)
                }
                val value = item.value
                val canResize: (AxQsSpan) -> Boolean = { span ->
                    val spanAllowed =
                        when (value) {
                            is AxEditGridValue.Control -> value.control.isSpanAllowed(span, columns)
                            is AxEditGridValue.Stack -> span == AxQsTileStacks.Span
                            is AxEditGridValue.Tile ->
                                section == AxQsGridSection.CONTROLS &&
                                    span == span.coerceForControlTile(columns)
                        }
                    spanAllowed &&
                        canFitAxQsGridItems(
                            listState.items
                                .filter { it.section == AxQsGridSection.CONTROLS }
                                .map { current ->
                                    if (current.id == item.id) {
                                        current.copy(span = span)
                                    } else {
                                        current
                                    }
                                },
                            columns,
                            maxRows ?: AxQsSpan.MAX_ROWS,
                        )
                }
                val resizeItem: (AxQsSpan) -> Unit = { span ->
                    if (canResize(span)) listState.update(item.id) { it.copy(span = span) }
                }
                val finishResize: (AxQsSpan) -> Unit = { span ->
                    if (canResize(span)) {
                        onSaveSpan(item.id, span)
                        onSave()
                    }
                }
                val resizable = section == AxQsGridSection.CONTROLS && item.minSpan != item.maxSpan
                val tileState =
                    if ((isSelected && resizable) || isStackTarget) TileState.Selected
                    else TileState.Removable
                val sliderControl =
                    (value as? AxEditGridValue.Control)?.control?.takeIf { it.isSlider }
                val verticalSlider = sliderControl != null && item.span.columns == 1
                val rotatedSpan = sliderControl?.rotateSpan(item.span, columns)
                val canRotateWithoutRepack = rotatedSpan?.let(canResize) == true
                val canRotate =
                    rotatedSpan != null &&
                        (canRotateWithoutRepack ||
                            canFitAxQsGridItems(
                                listState.items
                                    .filter { it.section == AxQsGridSection.CONTROLS }
                                    .map { current ->
                                        current.copy(
                                            span = if (current.id == item.id) rotatedSpan else current.span,
                                            position = null,
                                        )
                                    },
                                columns,
                                maxRows ?: AxQsSpan.MAX_ROWS,
                            ))
                val rotateItem: () -> Unit = {
                    if (canRotate && rotatedSpan != null) {
                        if (!canRotateWithoutRepack) {
                            listState.repack(
                                listState.items
                                    .filter { it.section == AxQsGridSection.CONTROLS }
                                    .mapTo(mutableSetOf()) { it.id }
                            )
                        }
                        resizeItem(rotatedSpan)
                        finishResize(rotatedSpan)
                    }
                }
                val controlStyle =
                    (value as? AxEditGridValue.Control)?.control?.let(sliderStyle)
                        ?: LunarisQsSliderStyle.M3_EXPRESSIVE
                val selectionShape =
                    when (value) {
                        is AxEditGridValue.Tile ->
                            axQsTileShape(item.span, LocalAxQsTileStyle.current)
                        is AxEditGridValue.Stack ->
                            axQsTileShape(item.span, LocalAxQsTileStyle.current)
                        is AxEditGridValue.Control ->
                            axQsControlShape(value.control, item.span, controlStyle)
                    }
                val selectionColor =
                    if (sliderControl != null) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                val sliderSelectionPadding =
                    if (sliderControl != null) {
                        val availableThickness =
                            if (verticalSlider) cellWidth else measuredRowHeight
                        val trackHeight =
                            if (controlStyle.usesPlatformSlider) {
                                availableThickness
                            } else {
                                axQsSliderTrackHeight(availableThickness)
                            }
                        ((availableThickness - trackHeight) / 2 - SliderSelectionGap)
                            .coerceAtLeast(0.dp)
                    } else {
                        0.dp
                    }
                val selectionHorizontalPadding =
                    if (verticalSlider) sliderSelectionPadding else 0.dp
                val selectionVerticalPadding =
                    if (verticalSlider) 0.dp else sliderSelectionPadding
                val resizeHandle =
                    if (resizable) {
                        Modifier.axQsResizeHandle(
                            id = item.id,
                            span = { listState.item(item.id)?.span ?: item.span },
                            cellWidth = cellWidth,
                            rowHeight = measuredRowHeight,
                            spacing = spacing,
                            resolveSpan = { startSpan, columnDelta, rowDelta ->
                                when (value) {
                                    is AxEditGridValue.Stack -> AxQsTileStacks.Span
                                    is AxEditGridValue.Control ->
                                        value.control.resizeSpan(
                                            startSpan = startSpan,
                                            columnDelta = columnDelta,
                                            rowDelta = rowDelta,
                                            columns = columns,
                                        )
                                    is AxEditGridValue.Tile ->
                                        AxQsSpan(
                                            columns =
                                                (startSpan.columns + columnDelta).coerceIn(
                                                    item.minSpan.columns,
                                                    item.maxSpan.columns,
                                                ),
                                            rows =
                                                (startSpan.rows + rowDelta).coerceIn(
                                                    item.minSpan.rows,
                                                    item.maxSpan.rows,
                                                ),
                                        )
                                }
                            },
                            canResize = canResize,
                            onResizeStarted = {
                                resizingItemId = item.id
                                listState.beginResize()
                            },
                            onResizeStopped = {
                                resizingItemId = null
                                listState.endResize()
                            },
                            onResize = resizeItem,
                            onResizeFinished = finishResize,
                        )
                    } else {
                        Modifier
                    }
                val removeDescription =
                    stringResource(R.string.accessibility_qs_edit_remove_tile_action)
                val resizeDescription =
                    stringResource(R.string.accessibility_qs_edit_toggle_tile_size_action)

                val currentSelectedId by rememberUpdatedState(selectedId)
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .pointerInput(item.id) {
                                awaitEachGesture {
                                    awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial,
                                    )
                                    if (currentSelectedId != null && currentSelectedId != item.id) {
                                        onSelected(null)
                                    }
                                }
                            }
                ) {
                    if (listState.draggedId == item.id) {
                        Box(Modifier.fillMaxSize()) {
                            if (sliderControl != null) {
                                controlPreview(sliderControl, item.span, columns, controlStyle)
                            } else if (value is AxEditGridValue.Stack) {
                                AxEditTileStack(value.tiles, modes)
                            }
                            Box(
                                Modifier.fillMaxSize()
                                    .padding(
                                        horizontal = selectionHorizontalPadding,
                                        vertical = selectionVerticalPadding,
                                    )
                                    .border(
                                        width = 3.dp,
                                        color = selectionColor,
                                        shape = selectionShape,
                                    )
                            )
                        }
                    } else {
                        AxInteractiveTileContainer(
                            tileState = tileState,
                            resizeHandleModifier = resizeHandle,
                            selectionColor = selectionColor,
                            selectionShape = selectionShape,
                            selectionHorizontalPadding = selectionHorizontalPadding,
                            selectionVerticalPadding = selectionVerticalPadding,
                            resizable = resizable,
                            modifier = Modifier.fillMaxSize(),
                            onRemoveClick = removeItem,
                            onResizeClick = {
                                if (resizable) {
                                    listState.item(item.id)?.let { current ->
                                        val nextSpan =
                                            if (sliderControl != null) {
                                                sliderControl.nextSpan(current.span, columns)
                                            } else {
                                                val width =
                                                    if (
                                                        current.span.columns <
                                                            current.maxSpan.columns
                                                    ) {
                                                        current.span.columns + 1
                                                    } else {
                                                        current.minSpan.columns
                                                    }
                                                current.span.copy(columns = width)
                                            }
                                        if (nextSpan != current.span && canResize(nextSpan)) {
                                            resizeItem(nextSpan)
                                            finishResize(nextSpan)
                                        }
                                    }
                                }
                            },
                            removeContentDescription = removeDescription,
                            resizeContentDescription = resizeDescription,
                        ) {
                            Box(
                                modifier =
                                    Modifier.fillMaxSize()
                                        .then(
                                            if (sliderControl != null) {
                                                Modifier
                                            } else {
                                                Modifier.clip(selectionShape)
                                            }
                                        )
                                        .axQsDragSource(
                                            id = item.id,
                                            state = listState,
                                            onDragStart = {
                                                onSelected(item.id)
                                                hapticFeedback.performHapticFeedback(
                                                    HapticFeedbackType.LongPress
                                                )
                                            },
                                        )
                                        .clickable(
                                            onClickLabel =
                                                editStackLabel.takeIf {
                                                    value is AxEditGridValue.Stack
                                                }
                                        ) {
                                            onSelected(item.id)
                                            if (value is AxEditGridValue.Stack) onEditStack(item.id)
                                        }
                                        .semantics {
                                            customActions =
                                                listOf(
                                                    CustomAccessibilityAction(moveEarlierLabel) {
                                                        moveItem(-1)
                                                    },
                                                    CustomAccessibilityAction(moveLaterLabel) {
                                                        moveItem(1)
                                                    },
                                                )
                                        }
                            ) {
                                when (value) {
                                    is AxEditGridValue.Stack -> AxEditTileStack(value.tiles, modes)
                                    is AxEditGridValue.Tile ->
                                        AxQsEditTile(
                                            tile = value.viewModel,
                                            span = item.span,
                                            circleCells = circleCells,
                                            modes = modes,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    is AxEditGridValue.Control ->
                                        controlPreview(
                                            value.control,
                                            item.span,
                                            columns,
                                            controlStyle,
                                        )
                                }
                            }
                        }
                        if (isSelected && sliderControl != null) {
                            LunarisQsSliderOrientationButton(
                                vertical = verticalSlider,
                                enabled = canRotate,
                                onClick = rotateItem,
                                modifier =
                                    Modifier.align(Alignment.TopStart)
                                        .offset(x = (-12).dp, y = (-12).dp)
                                        .zIndex(3f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val AxQsGridItem<AxEditGridValue>.section: AxQsGridSection
    get() = value.section

private fun AxEditGridValue.inSection(section: AxQsGridSection): AxEditGridValue {
    return when (this) {
        is AxEditGridValue.Tile -> copy(section = section)
        is AxEditGridValue.Control -> copy(section = section)
        is AxEditGridValue.Stack -> this
    }
}

private fun AxAddItem.toEditGridItem(
    columns: Int,
    section: AxQsGridSection,
): AxQsGridItem<AxEditGridValue> {
    return when (this) {
        is AxAddItem.Stack ->
            AxQsGridItem<AxEditGridValue>(
                id = AxQsTileStacks.newId(),
                span = AxQsTileStacks.Span,
                minSpan = AxQsTileStacks.Span,
                maxSpan = AxQsTileStacks.Span,
                value = AxEditGridValue.Stack(emptyList(), emptyList()),
            )
        is AxAddItem.Tile -> {
            val controlGrid = section == AxQsGridSection.CONTROLS
            AxQsGridItem(
                id = id,
                span = AxQsSpan.TileDefault,
                minSpan = if (controlGrid) AxQsSpan.ControlTileMin else AxQsSpan.TileDefault,
                maxSpan =
                    if (controlGrid) {
                        AxQsSpan.controlTileMax(columns)
                    } else {
                        AxQsSpan.TileDefault
                    },
                value = AxEditGridValue.Tile(viewModel, section),
            )
        }
        is AxAddItem.Control -> {
            val spans =
                if (section == AxQsGridSection.TILES) {
                    TileGridSpans
                } else {
                    control.spans(columns)
                }
            AxQsGridItem(
                id = id,
                span = control.coerceSpan(span, columns),
                minSpan = spans.min,
                maxSpan = spans.max,
                value = AxEditGridValue.Control(control, section),
            )
        }
    }
}

private fun buildEditItems(
    currentTiles: List<EditTileViewModel>,
    allTiles: List<EditTileViewModel>,
    layout: AxQsLayout,
    controlColumns: Int,
    tileColumns: Int,
    controlPositions: Map<String, AxQsGridPosition>,
    viewModel: AxQsViewModel,
): List<AxQsGridItem<AxEditGridValue>> {
    val values = LinkedHashMap<String, AxEditGridValue>()
    currentTiles.forEach {
        values[it.tileSpec.spec] = AxEditGridValue.Tile(it, AxQsGridSection.TILES)
    }
    AxQsControl.entries
        .filter { it.showInEditGrid }
        .forEach { control ->
            values[control.id] = AxEditGridValue.Control(control, AxQsGridSection.CONTROLS)
        }
    val stacks = viewModel.stacks(layout)
    val stackedSpecs = stacks.values.flatten().toSet()
    val tilesBySpec = allTiles.associateBy { it.tileSpec.spec }
    stacks.forEach { (id, specs) ->
        values[id] = AxEditGridValue.Stack(specs, specs.mapNotNull(tilesBySpec::get))
    }
    stackedSpecs.forEach(values::remove)
    val controlIds =
        viewModel.orderedIds(
            layout = layout,
            section = AxQsGridSection.CONTROLS,
            availableIds = values.keys.toList(),
            defaultIds = currentTiles.map { it.tileSpec.spec },
        )
    val tileIds =
        viewModel
            .orderedIds(
                layout = layout,
                section = AxQsGridSection.TILES,
                availableIds =
                    currentTiles.map { it.tileSpec.spec }.filterNot(stackedSpecs::contains) +
                        AxQsControl.entries.filter { it.canUseTileGrid }.map(AxQsControl::id),
                defaultIds = currentTiles.map { it.tileSpec.spec },
            )
            .filterNot(controlIds.toSet()::contains)
    val controlItems: List<AxQsGridItem<AxEditGridValue>> =
        controlIds.map { id ->
            when (val value = values.getValue(id)) {
                is AxEditGridValue.Stack ->
                    AxQsGridItem<AxEditGridValue>(
                        id = id,
                        span = AxQsTileStacks.Span,
                        minSpan = AxQsTileStacks.Span,
                        maxSpan = AxQsTileStacks.Span,
                        value = value,
                        position = controlPositions[id],
                    )
                is AxEditGridValue.Tile ->
                    AxQsGridItem<AxEditGridValue>(
                        id = id,
                        span =
                            viewModel
                                .span(id, layout, AxQsSpan.TileDefault)
                                .coerceForControlTile(controlColumns),
                        minSpan = AxQsSpan.ControlTileMin,
                        maxSpan = AxQsSpan.controlTileMax(controlColumns),
                        value = value.inSection(AxQsGridSection.CONTROLS),
                        position = controlPositions[id],
                    )
                is AxEditGridValue.Control -> {
                    val spans = value.control.spans(controlColumns)
                    AxQsGridItem<AxEditGridValue>(
                        id = id,
                        span =
                            viewModel.span(id, layout, spans.default).let {
                                value.control.coerceSpan(it, controlColumns)
                            },
                        minSpan = spans.min,
                        maxSpan = spans.max,
                        value = value.inSection(AxQsGridSection.CONTROLS),
                        position = controlPositions[id],
                    )
                }
            }
        }
    val tileItems: List<AxQsGridItem<AxEditGridValue>> =
        tileIds.map { id ->
            AxQsGridItem<AxEditGridValue>(
                id = id,
                span = AxQsSpan.TileDefault,
                minSpan = AxQsSpan.TileDefault,
                maxSpan = AxQsSpan.TileDefault,
                value = values.getValue(id).inSection(AxQsGridSection.TILES),
            )
        }
    val rows =
        if (layout == AxQsLayout.QS) {
            AX_QS_CONTROL_MAX_ROWS
        } else {
            axQsSharedGridRows(controlItems, controlColumns, tileItems.size, tileColumns).controls
        }
    val fittedControls =
        fitAxQsGridItems<AxEditGridValue>(controlItems, controlColumns, rows)
    return fittedControls + tileItems
}

private val SliderSelectionGap = 4.dp
private val EditGridCornerRadius = 28.dp
private val EditGridPadding = 10.dp
private val TileGridSpans =
    AxQsControlSpans(AxQsSpan.TileDefault, AxQsSpan.TileDefault, AxQsSpan.TileDefault)
