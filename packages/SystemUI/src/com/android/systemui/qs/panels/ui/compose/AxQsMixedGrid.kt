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

import android.service.quicksettings.Tile.STATE_UNAVAILABLE
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height as layoutHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.settingslib.notification.modes.ZenMode
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel
import com.android.systemui.qs.panels.shared.model.AxMediaSurface
import com.android.systemui.qs.panels.shared.model.AxQsControl
import com.android.systemui.qs.panels.shared.model.AxQsGridItem
import com.android.systemui.qs.panels.shared.model.AxQsGridLayout
import com.android.systemui.qs.panels.shared.model.AxQsGridSection
import com.android.systemui.qs.panels.shared.model.AxQsGridValue
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.AxQsPanelMode
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.shared.model.AxQsTileStacks
import com.android.systemui.qs.panels.shared.model.LunarisQsSliderStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsTileFolderController
import com.android.systemui.qs.panels.shared.model.shouldPlayQsContent
import com.android.systemui.qs.panels.ui.media.AxMediaPanel
import com.android.systemui.qs.panels.ui.media.nonQsGridMediaHeight
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.qs.panels.ui.viewmodel.AxQsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel
import com.android.systemui.qs.tiles.ringer.RingerSliderTileContent
import com.android.systemui.qs.ui.composable.QuickSettingsTheme
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel

@Composable
internal fun ContentScope.AxQsMixedGrid(
    viewModel: QSFragmentComposeViewModel,
    toolbarViewModel: ToolbarViewModel,
    axQsViewModel: AxQsViewModel,
    mediaViewModel: AxMediaViewModel,
    detailsViewModel: DetailsViewModel? = null,
    qqs: Boolean,
    listening: () -> Boolean,
    brightnessSliderViewModel: BrightnessSliderViewModel,
    volumeSliderViewModel: AudioStreamSliderViewModel? = null,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val tiles = viewModel.containerViewModel.tileGridViewModel.tileViewModels
    val values = LinkedHashMap<String, AxQsGridValue>()
    tiles.forEach { tile -> values[tile.spec.spec] = AxQsGridValue.Tile(tile) }
    AxQsControl.entries.forEach { control -> values[control.id] = AxQsGridValue.Control(control) }

    val splitShade = viewModel.isInSplitShade
    LaunchedEffect(splitShade) {
        if (splitShade) {
            scrollState.scrollTo(0)
        }
    }
    val layout =
        when {
            splitShade -> AxQsLayout.SPLIT_SHADE
            qqs -> AxQsLayout.QQS
            else -> AxQsLayout.QS
        }
    val controlGridLayout = AxQsGridLayout.from(layout, AxQsGridSection.CONTROLS)
    val tileGridLayout = AxQsGridLayout.from(layout, AxQsGridSection.TILES)
    val controlColumns = axQsViewModel.columns(controlGridLayout)
    val tileColumns = axQsViewModel.columns(tileGridLayout)
    val tileRowLimit = axQsViewModel.rows(tileGridLayout)
    val showTileLabels = axQsViewModel.showTileLabels(tileGridLayout)
    val showWeather = axQsViewModel.showWeather
    val allowCircleCells =
        controlColumns >= axQsViewModel.defaultColumns(controlGridLayout) &&
            (splitShade || tileColumns >= axQsViewModel.defaultColumns(tileGridLayout))
    val controlPositions = axQsViewModel.controlPositions(layout)
    val stacks = axQsViewModel.stacks(layout)
    val folders = axQsViewModel.folders(layout)
    val stackedSpecs = stacks.values.flatten().toSet() + folders.values.flatMap { it.tileSpecs }
    val tilesBySpec = tiles.associateBy { it.spec.spec }
    stacks.forEach { (id, specs) ->
        values[id] = AxQsGridValue.Stack(specs.mapNotNull(tilesBySpec::get))
    }
    folders.forEach { (id, folder) ->
        values[id] = AxQsGridValue.Folder(folder, folder.tileSpecs.mapNotNull(tilesBySpec::get))
    }
    stackedSpecs.forEach(values::remove)
    val controlIds =
        axQsViewModel.orderedIds(
            layout = layout,
            section = AxQsGridSection.CONTROLS,
            availableIds = values.keys.toList(),
            defaultIds = tiles.map { it.spec.spec },
        )
    val tileIds =
        axQsViewModel.orderedIds(
            layout = layout,
            section = AxQsGridSection.TILES,
            availableIds =
                tiles.map { it.spec.spec }.filterNot(stackedSpecs::contains) +
                    AxQsControl.entries.filter { it.canUseTileGrid }.map(AxQsControl::id),
            defaultIds = tiles.map { it.spec.spec },
        )
    val controlItems: List<AxQsGridItem<AxQsGridValue>> =
        controlIds.map { id ->
            when (val value = values.getValue(id)) {
                is AxQsGridValue.Folder ->
                    AxQsGridItem<AxQsGridValue>(
                        id = id,
                        span =
                            LunarisQsTileFolderController.coerceSpan(
                                axQsViewModel.span(id, layout, LunarisQsTileFolderController.Span),
                                controlColumns,
                            ),
                        minSpan = LunarisQsTileFolderController.MinSpan,
                        maxSpan = LunarisQsTileFolderController.maxSpan(controlColumns),
                        value = value,
                        position = controlPositions[id],
                    )
                is AxQsGridValue.Stack ->
                    AxQsGridItem<AxQsGridValue>(
                        id = id,
                        span = AxQsTileStacks.Span,
                        minSpan = AxQsTileStacks.Span,
                        maxSpan = AxQsTileStacks.Span,
                        value = value,
                        position = controlPositions[id],
                    )
                is AxQsGridValue.Tile ->
                    AxQsGridItem<AxQsGridValue>(
                        id = id,
                        span =
                            axQsViewModel
                                .span(id, layout, AxQsSpan.TileDefault)
                                .coerceForControlTile(controlColumns),
                        minSpan = AxQsSpan.ControlTileMin,
                        maxSpan = AxQsSpan.controlTileMax(controlColumns),
                        value = value,
                        position = controlPositions[id],
                    )
                is AxQsGridValue.Control -> {
                    val spans = value.control.spans(controlColumns)
                    AxQsGridItem<AxQsGridValue>(
                        id = id,
                        span =
                            axQsViewModel.span(id, layout, spans.default).let {
                                value.control.coerceSpan(it, controlColumns)
                            },
                        minSpan = spans.min,
                        maxSpan = spans.max,
                        value = value,
                        position = controlPositions[id],
                    )
                }
            }
        }
    val tileItems: List<AxQsGridItem<AxQsGridValue>> =
        tileIds.map { id ->
            AxQsGridItem<AxQsGridValue>(
                id = id,
                span = AxQsSpan.TileDefault,
                minSpan = AxQsSpan.TileDefault,
                maxSpan = AxQsSpan.TileDefault,
                value = values.getValue(id),
            )
        }
    val rows =
        if (layout == AxQsLayout.QS) {
            AxQsGridRows(
                controls = AX_QS_CONTROL_MAX_ROWS,
                tiles = axQsVisibleTileRows(tileItems.size, tileColumns, tileRowLimit),
            )
        } else {
            axQsSharedGridRows(
                controlItems,
                controlColumns,
                tileItems.size,
                tileColumns,
                maxTileRows = tileRowLimit,
            )
        }
    val fittedControlItems: List<AxQsGridItem<AxQsGridValue>> =
        fitAxQsGridItems<AxQsGridValue>(controlItems, controlColumns, rows.controls)
    val visibleTiles =
        (fittedControlItems + tileItems)
            .flatMap {
                when (val value = it.value) {
                    is AxQsGridValue.Tile -> listOf(value.viewModel)
                    is AxQsGridValue.Stack -> value.tiles
                    is AxQsGridValue.Folder -> value.tiles
                    is AxQsGridValue.Control -> emptyList()
                }
            }
            .distinctBy { it.spec.spec }
    val rowHeight = AxTileDefaults.TileHeight * LocalTileScale.current
    val spacing = axQsViewModel.spacing.tileGap.dp * LocalTileScale.current
    val qsEntranceProgress = {
        ((viewModel.expansionState.progress - QS_ENTRANCE_START) / (1f - QS_ENTRANCE_START))
            .coerceIn(0f, 1f)
    }
    val separateQqs =
        qqs && !splitShade && axQsViewModel.panelMode == AxQsPanelMode.SEPARATE
    val qqsHideBypass = separateQqs && axQsViewModel.isQsBypassingShade
    val contentVisible by remember(viewModel, qqs, splitShade, qqsHideBypass) {
        derivedStateOf {
            viewModel.isQsVisibleAndAnyShadeExpanded && !viewModel.isEditing &&
                if (qqs) !qqsHideBypass && !viewModel.isQsFullyExpanded
                else splitShade || !viewModel.isQsFullyCollapsed
        }
    }
    val contentPlaying by remember(viewModel, axQsViewModel, scrollState, layout, contentVisible) {
        derivedStateOf {
            shouldPlayQsContent(
                layout = layout,
                panelExpansion = viewModel.panelExpansionFraction,
                qsExpansion = viewModel.expansionState.progress,
                qsExpanded = viewModel.isQsExpanded,
                visible = contentVisible,
                transitioning = axQsViewModel.isShadeBeingDragged ||
                    viewModel.isTransitioningToFullShade ||
                    viewModel.isStackScrollerOverscrolling || scrollState.isScrollInProgress,
            )
        }
    }
    val controlContent: @Composable (AxQsGridItem<AxQsGridValue>) -> Unit = { item ->
        when (val value = item.value) {
            is AxQsGridValue.Folder ->
                this@AxQsMixedGrid.LunarisQsTileFolder(
                    id = item.id,
                    folder = value.folder,
                    span = item.span,
                    tiles = value.tiles,
                    layout = layout,
                    viewModel = axQsViewModel,
                    listening = listening,
                )
            is AxQsGridValue.Stack ->
                AxTileStack(
                    pageKeys = value.tiles.map { it.spec.spec },
                    indicatorColor = { page ->
                        val tile = value.tiles[page]
                        val state by tile.state.collectAsStateWithLifecycle(tile.currentState)
                        AxTileColorsDefaults.getColorForState(
                                uiState = state.toUiState(LocalResources.current),
                                iconOnly = false,
                            )
                            .label
                    },
                ) { page ->
                    this@AxQsMixedGrid.AxLiveTile(
                        tile = value.tiles[page],
                        item = item,
                        iconOnly = false,
                        qqs = qqs,
                        separateQqs = separateQqs,
                        listening = listening,
                        detailsViewModel = detailsViewModel,
                        axQsViewModel = axQsViewModel,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            is AxQsGridValue.Tile ->
                this@AxQsMixedGrid.AxLiveTile(
                    tile = value.viewModel,
                    item = item,
                    iconOnly = false,
                    qqs = qqs,
                    separateQqs = separateQqs,
                    listening = listening,
                    detailsViewModel = detailsViewModel,
                    axQsViewModel = axQsViewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            is AxQsGridValue.Control ->
                AxLiveControl(
                    control = value.control,
                    span = item.span,
                    axQsViewModel = axQsViewModel,
                    layout = layout,
                    contentVisible = contentVisible,
                    contentPlaying = contentPlaying,
                    mediaViewModel = mediaViewModel,
                    mediaViewModelFactory = viewModel.mediaViewModelFactory,
                    brightnessSliderViewModel = brightnessSliderViewModel,
                    volumeSliderViewModel = volumeSliderViewModel,
                    sliderStyle =
                        axQsViewModel.sliderStyle(layout, value.control),
                    entranceProgress = {
                        viewModel.quickQuickSettingsViewModel.squishinessViewModel.squishiness.value
                    },
                    modifier = Modifier.fillMaxSize(),
                )
        }
    }
    val tileContent: @Composable (AxQsGridItem<AxQsGridValue>) -> Unit = { item ->
        when (val value = item.value) {
            is AxQsGridValue.Stack,
            is AxQsGridValue.Folder -> Unit
            is AxQsGridValue.Tile ->
                this@AxQsMixedGrid.AxLiveTile(
                    tile = value.viewModel,
                    item = item,
                    iconOnly = true,
                    qqs = qqs,
                    separateQqs = separateQqs,
                    listening = listening,
                    detailsViewModel = detailsViewModel,
                    axQsViewModel = axQsViewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            is AxQsGridValue.Control ->
                AxLiveControl(
                    control = value.control,
                    span = item.span,
                    axQsViewModel = axQsViewModel,
                    layout = layout,
                    contentVisible = contentVisible,
                    contentPlaying = contentPlaying,
                    mediaViewModel = mediaViewModel,
                    mediaViewModelFactory = viewModel.mediaViewModelFactory,
                    brightnessSliderViewModel = brightnessSliderViewModel,
                    volumeSliderViewModel = volumeSliderViewModel,
                    sliderStyle =
                        axQsViewModel.sliderStyle(layout, value.control),
                    entranceProgress = {
                        viewModel.quickQuickSettingsViewModel.squishinessViewModel.squishiness.value
                    },
                    modifier = Modifier.fillMaxSize(),
                )
        }
    }
    val tileLabel: @Composable (AxQsGridItem<AxQsGridValue>) -> Unit = { item ->
        (item.value as? AxQsGridValue.Tile)?.let { AxTileGridLabel(it.viewModel) }
    }

    QuickSettingsTheme {
        BoxWithConstraints(modifier) {
            val contentPadding = axQsViewModel.spacing.sidePadding.dp
            val gridWidth = (maxWidth - contentPadding * 2).coerceAtLeast(0.dp)
            val circleCells =
                useAxQsCircleCells(
                    gridWidth = gridWidth,
                    tileColumns = tileColumns,
                    spacing = spacing,
                    allowCircles = allowCircleCells,
                )
            when {
                qqs && splitShade -> Unit
                qqs ->
                    Column(
                        Modifier.fillMaxWidth().graphicsLayer {
                            alpha = if (qqsHideBypass) 0f else 1f
                        }
                    ) {
                        AxQQS(
                            toolbarViewModel = toolbarViewModel,
                            shadeHeaderViewModel =
                                viewModel.containerViewModel.shadeHeaderViewModel,
                            controlItems = fittedControlItems,
                            tileItems = tileItems,
                            controlColumns = controlColumns,
                            controlRows = rows.controls,
                            tileColumns = tileColumns,
                            tileRows = rows.tiles,
                            showTileLabels = showTileLabels,
                            showWeather = showWeather,
                            rowHeight = rowHeight,
                            spacing = spacing,
                            circleCells = circleCells,
                            isFullyVisible = {
                                viewModel.isQsVisibleAndAnyShadeExpanded && !viewModel.isEditing
                            },
                            editButtonProgress = qsEntranceProgress,
                            separateMode = separateQqs,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = contentPadding),
                            controlContent = controlContent,
                            tileContent = tileContent,
                        )
                        if (
                            separateQqs &&
                                mediaViewModel.hasVisibleSessions(AxMediaSurface.SEPARATE_QQS)
                        ) {
                            Spacer(Modifier.layoutHeight(spacing))
                            Box(
                                Modifier.fillMaxWidth()
                                    .padding(
                                        horizontal = dimensionResource(R.dimen.qs_horizontal_margin)
                                    )
                                    .layoutHeight(nonQsGridMediaHeight),
                                contentAlignment = Alignment.Center,
                            ) {
                                AxMediaPanel(
                                    viewModel = mediaViewModel,
                                    span = AxQsSpan(controlColumns, 2),
                                    mediaViewModelFactory = viewModel.mediaViewModelFactory,
                                    modifier = Modifier.fillMaxSize(),
                                    surface = AxMediaSurface.SEPARATE_QQS,
                                )
                            }
                        }
                    }
                splitShade ->
                    AxQS(
                        toolbarViewModel = toolbarViewModel,
                        shadeHeaderViewModel = viewModel.containerViewModel.shadeHeaderViewModel,
                        isFullyVisible = { viewModel.isQsFullyExpanded && !viewModel.isEditing },
                        controlItems = fittedControlItems,
                        tileItems = tileItems,
                        controlColumns = controlColumns,
                        controlRows = rows.controls,
                        tileColumns = tileColumns,
                        tileRows = rows.tiles,
                        showTileLabels = showTileLabels,
                        showWeather = showWeather,
                        rowHeight = rowHeight,
                        spacing = spacing,
                        editButtonProgress = qsEntranceProgress,
                        scrollState = scrollState,
                        circleCells = circleCells,
                        modifier =
                            Modifier.fillMaxSize()
                                .padding(horizontal = contentPadding),
                        controlContent = controlContent,
                        tileContent = tileContent,
                        tileLabel = tileLabel,
                    )
                else ->
                    AxQS(
                        toolbarViewModel = toolbarViewModel,
                        shadeHeaderViewModel = viewModel.containerViewModel.shadeHeaderViewModel,
                        isFullyVisible = { viewModel.isQsFullyExpanded && !viewModel.isEditing },
                        controlItems = fittedControlItems,
                        tileItems = tileItems,
                        controlColumns = controlColumns,
                        controlRows = rows.controls,
                        tileColumns = tileColumns,
                        tileRows = rows.tiles,
                        showTileLabels = showTileLabels,
                        showWeather = showWeather,
                        rowHeight = rowHeight,
                        spacing = spacing,
                        editButtonProgress = qsEntranceProgress,
                        scrollState = scrollState,
                        circleCells = circleCells,
                        modifier = Modifier.fillMaxSize().padding(horizontal = contentPadding),
                        controlContent = controlContent,
                        tileContent = tileContent,
                        tileLabel = tileLabel,
                    )
            }
        }
    }
    val tilesListening = !separateQqs && !(qqs && splitShade) && listening()
    DisposableEffect(visibleTiles, tilesListening) {
        val token = Any()
        if (tilesListening) {
            visibleTiles.forEach { it.startListening(token) }
        }
        onDispose {
            if (tilesListening) {
                visibleTiles.forEach { it.stopListening(token) }
            }
        }
    }
}

@Composable
private fun ContentScope.AxLiveTile(
    tile: TileViewModel,
    item: AxQsGridItem<AxQsGridValue>,
    iconOnly: Boolean,
    qqs: Boolean,
    separateQqs: Boolean,
    listening: () -> Boolean,
    detailsViewModel: DetailsViewModel?,
    axQsViewModel: AxQsViewModel,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val large = item.span.columns > 1 && item.span.rows > 1
    if (large && tile.spec.spec == "dnd") {
        val modes by axQsViewModel.modes.collectAsStateWithLifecycle(emptyList())
        val state by tile.state.collectAsStateWithLifecycle(tile.currentState)
        if (modes.any { it.id == ZenMode.MANUAL_DND_MODE_ID }) {
            LunarisModesTile(
                modes = modes,
                span = item.span,
                enabled = state.state != STATE_UNAVAILABLE && !state.disabledByPolicy,
                onModeClick = axQsViewModel::onModeClick,
                onModeLongClick = axQsViewModel::onModeLongClick,
                onClick = { tile.mainClick(null) },
                onLongClick = { tile.settingsClick(null) },
                modifier = modifier,
            )
            return
        }
    }
    val deviceArtwork =
        if (large && tile.spec.spec == "bt" && listening()) {
            val artwork by axQsViewModel.bluetoothArtwork.collectAsStateWithLifecycle()
            artwork
        } else {
            null
        }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        this@AxLiveTile.AxTile(
            tile = tile,
            iconOnly = iconOnly,
            span = item.span,
            fillHeight = true,
            compactIconSize = axQsTileIconSize(minOf(maxWidth, maxHeight)),
            deviceArtwork = deviceArtwork,
            squishiness = { 1f },
            coroutineScope = coroutineScope,
            bounceableInfo = null,
            interactionSource = null,
            modifier = Modifier.fillMaxSize(),
            isVisible = if (separateQqs) ({ false }) else listening,
            detailsViewModel = if (qqs) null else detailsViewModel,
        )
    }
}

@Composable
private fun AxLiveControl(
    control: AxQsControl,
    span: AxQsSpan,
    axQsViewModel: AxQsViewModel,
    layout: AxQsLayout,
    contentVisible: Boolean,
    contentPlaying: Boolean,
    mediaViewModel: AxMediaViewModel,
    mediaViewModelFactory: MediaViewModel.Factory,
    brightnessSliderViewModel: BrightnessSliderViewModel,
    volumeSliderViewModel: AudioStreamSliderViewModel?,
    sliderStyle: LunarisQsSliderStyle,
    entranceProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (control) {
            AxQsControl.APP_WIDGET,
            AxQsControl.APP_WIDGET_2,
            AxQsControl.APP_WIDGET_3 ->
                LunarisQsWidgetCard(
                    widget = axQsViewModel.appWidget(layout, control),
                    layout = layout,
                    control = control,
                    playing = contentPlaying,
                    visible = contentVisible,
                    interactionHandler = axQsViewModel.widgetInteractionHandler,
                    onChoose = { width, height ->
                        axQsViewModel.editAppWidget(layout, control, width, height)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            AxQsControl.PHOTO ->
                LunarisQsPhotoCard(
                    photo = axQsViewModel.photoFrame(layout),
                    playing = contentPlaying,
                    visible = contentVisible,
                    modifier = Modifier.fillMaxSize(),
                )
            AxQsControl.FLASHLIGHT ->
                LunarisFlashlightSlider(
                    vertical = span.columns == 1,
                    sliderStyle = sliderStyle,
                    modifier = Modifier.fillMaxSize(),
                )
            AxQsControl.BRIGHTNESS ->
                AxQsBrightnessControl(
                    vertical = span.columns == 1,
                    sliderStyle = sliderStyle,
                    viewModel = brightnessSliderViewModel,
                    entranceProgress = entranceProgress,
                    modifier = Modifier.fillMaxSize(),
                )
            AxQsControl.VOLUME ->
                if (volumeSliderViewModel != null) {
                    AxQsVolumeControl(
                        vertical = span.columns == 1,
                        sliderStyle = sliderStyle,
                        viewModel = volumeSliderViewModel,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            AxQsControl.AUTO_BRIGHTNESS ->
                AxQsBrightnessButton(
                    viewModel = brightnessSliderViewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            AxQsControl.VOLUME_MUTE ->
                if (volumeSliderViewModel != null) {
                    AxQsVolumeMuteButton(
                        viewModel = volumeSliderViewModel,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            AxQsControl.RINGER ->
                RingerSliderTileContent(
                    compact = span == AxQsSpan.TileDefault,
                    vertical = span.columns == 1,
                    shape = axQsControlShape(AxQsControl.RINGER, span),
                    modifier = Modifier.fillMaxSize(),
                )
            AxQsControl.MEDIA ->
                AxMediaPanel(
                    viewModel = mediaViewModel,
                    span = span,
                    mediaViewModelFactory = mediaViewModelFactory,
                    modifier = Modifier.fillMaxSize(),
                    showPlaceholder = true,
                )
        }
    }
}

@Composable
private fun AxTileGridLabel(tile: TileViewModel) {
    val state by tile.state.collectAsStateWithLifecycle(initialValue = tile.currentState)
    Text(
        text = state.label?.toString().orEmpty(),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val QS_ENTRANCE_START = 0.5f
