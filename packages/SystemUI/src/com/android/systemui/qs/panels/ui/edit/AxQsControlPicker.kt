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

package com.android.systemui.qs.panels.ui.edit

import android.service.quicksettings.Tile.STATE_INACTIVE
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.android.axion.compose.preferences.LocalPreferencePosition
import com.android.axion.compose.preferences.PreferenceGroup
import com.android.axion.compose.preferences.preferenceShape
import com.android.compose.gesture.gesturesDisabled
import com.android.settingslib.notification.modes.ZenMode
import com.android.systemui.common.ui.compose.load
import com.android.systemui.qs.panels.shared.model.AxQsControl
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.shared.model.AxQsTileStacks
import com.android.systemui.qs.panels.shared.model.LunarisQsSliderStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsTileFolderController
import com.android.systemui.qs.panels.ui.compose.AxLargeTileContent
import com.android.systemui.qs.panels.ui.compose.AxQsPagerIndicator
import com.android.systemui.qs.panels.ui.compose.AxTileDefaults
import com.android.systemui.qs.panels.ui.compose.AxTileStackPlaceholder
import com.android.systemui.qs.panels.ui.compose.LocalAxQsTileStyle
import com.android.systemui.qs.panels.ui.compose.LocalTileScale
import com.android.systemui.qs.panels.ui.compose.LunarisFolderPreview
import com.android.systemui.qs.panels.ui.compose.LunarisModesTile
import com.android.systemui.qs.panels.ui.compose.LunarisQsSliderOrientationButton
import com.android.systemui.qs.panels.ui.compose.axQsControlShape
import com.android.systemui.qs.panels.ui.compose.axQsGridCellWidth
import com.android.systemui.qs.panels.ui.compose.axQsTileShape
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import com.android.systemui.qs.panels.ui.compose.infinitegrid.TileColors
import com.android.systemui.qs.panels.ui.compose.useAxQsCircleCells
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.LocalLunarisFlashlightSliderViewModel
import com.android.systemui.qs.shared.model.CategoryAndName
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.shared.model.groupAndSort
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ui.dialog.viewmodel.ModeTileViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
private fun axString(resName: String, fallback: String): String {
    val context = LocalContext.current
    val id = remember(resName) { context.resources.getIdentifier(resName, "string", context.packageName) }
    return if (id != 0) stringResource(id) else fallback
}

internal sealed interface AxAddItem : CategoryAndName {
    val id: String
    val label: String
    val isAdded: Boolean

    data class Tile(val viewModel: EditTileViewModel, override val isAdded: Boolean) : AxAddItem {
        override val id = viewModel.tileSpec.spec
        override val label = viewModel.label.text
        override val category = viewModel.category
    }

    data class Control(
        val control: AxQsControl,
        override val label: String,
        override val isAdded: Boolean,
        val span: AxQsSpan = control.defaultSpan,
    ) : AxAddItem {
        override val id = control.id
        override val category = TileCategory.UTILITIES
    }

    data class Stack(override val label: String) : AxAddItem {
        override val id = "stack:new"
        override val isAdded = false
        override val category = TileCategory.UTILITIES
    }

    data class Folder(override val label: String, val limitReached: Boolean) : AxAddItem {
        override val id = "folder:new"
        override val isAdded = false
        override val category = TileCategory.UTILITIES
    }

    override val name: String
        get() = label
}

@Composable
internal fun AxAvailableControls(
    allTiles: List<EditTileViewModel>,
    currentIds: Set<String>,
    controlColumns: Int,
    tileColumns: Int,
    sliderStyle: (AxQsControl) -> LunarisQsSliderStyle,
    onSliderStyleChanged: (AxQsControl, LunarisQsSliderStyle) -> Unit,
    sliderSpan: (AxQsControl) -> AxQsSpan,
    onSliderSpanChanged: (AxQsControl, AxQsSpan) -> Unit,
    controlPreview: @Composable (AxQsControl, AxQsSpan, LunarisQsSliderStyle) -> Unit,
    canAdd: (AxAddItem) -> Boolean,
    onAdd: (AxAddItem) -> Unit,
    spacing: Dp,
    settings: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hasFlashlight = LocalLunarisFlashlightSliderViewModel.current.hasFlashlight
    val controls: List<AxAddItem> =
        listOf(
                AxQsControl.AUTO_BRIGHTNESS to axString("ax_qs_auto_brightness", "Auto brightness"),
                AxQsControl.VOLUME_MUTE to axString("ax_qs_volume_mute", "Mute volume"),
                AxQsControl.RINGER to stringResource(R.string.volume_ringer_mode),
                AxQsControl.BRIGHTNESS to stringResource(R.string.lunaris_qs_brightness_slider),
                AxQsControl.VOLUME to stringResource(R.string.lunaris_qs_volume_slider),
                AxQsControl.FLASHLIGHT to stringResource(R.string.lunaris_qs_flashlight_slider),
                AxQsControl.MEDIA to axString("ax_qs_media", "Media player"),
                AxQsControl.PHOTO to stringResource(R.string.lunaris_qs_photo_title),
                AxQsControl.APP_WIDGET to stringResource(R.string.lunaris_qs_widget_title),
            )
            .filter { (control, _) ->
                control != AxQsControl.FLASHLIGHT || hasFlashlight
            }
            .map { (control, label) ->
                AxAddItem.Control(
                    control,
                    label,
                    control.id in currentIds,
                    if (control.supportsOrientation) sliderSpan(control) else control.defaultSpan,
                )
            }
    val folderItem =
        AxAddItem.Folder(
            stringResource(R.string.lunaris_qs_tile_folder),
            currentIds.count(LunarisQsTileFolderController::isFolderId) >=
                LunarisQsTileFolderController.MAX_FOLDERS,
        )
    val stackItem = AxAddItem.Stack(stringResource(R.string.lunaris_qs_tile_stack))
    val onRotateSlider: (AxAddItem.Control) -> Unit = { item ->
        onSliderSpanChanged(item.control, item.control.rotateSpan(item.span, controlColumns))
    }
    val tiles: List<AxAddItem> = allTiles.map { AxAddItem.Tile(it, it.tileSpec.spec in currentIds) }
    val tileGroups = remember(tiles) { groupAndSort(tiles).entries.toList() }
    PreferenceGroup(modifier = modifier.fillMaxWidth(), spacing = 2.dp) {
        settings?.let { settingsContent ->
            item { AxPickerSettingsCard(settingsContent) }
        }
        item {
            AxAvailableItemGroup(
                title = axString("ax_qs_controls", "Controls"),
                iconId = TileCategory.UTILITIES.iconId,
                items = controls + stackItem + folderItem,
                columns = controlColumns,
                sliderStyle = sliderStyle,
                onSliderStyleChanged = onSliderStyleChanged,
                onRotateSlider = onRotateSlider,
                onControlSpanChanged = onSliderSpanChanged,
                spacing = spacing,
                controlPreview = controlPreview,
                canAdd = canAdd,
                onAdd = onAdd,
            )
        }
        tileGroups.forEach { (category, items) ->
            item {
                AxAvailableItemGroup(
                    title = category.label.load().orEmpty(),
                    iconId = category.iconId,
                    items = items,
                    columns = tileColumns,
                    sliderStyle = sliderStyle,
                    onSliderStyleChanged = onSliderStyleChanged,
                    onRotateSlider = onRotateSlider,
                    onControlSpanChanged = onSliderSpanChanged,
                    spacing = spacing,
                    controlPreview = controlPreview,
                    canAdd = canAdd,
                    onAdd = onAdd,
                )
            }
        }
    }
}

@Composable
private fun AxPickerSettingsCard(settings: @Composable () -> Unit) {
    AxEditContainerCard(
        contentPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AvailableItemHeader(
            title = axString("qs_edit_settings", "Settings"),
            iconId = R.drawable.ic_settings,
        )
        settings()
    }
}

@Composable
private fun AxAvailableItemGroup(
    title: String,
    iconId: Int,
    items: List<AxAddItem>,
    columns: Int,
    sliderStyle: (AxQsControl) -> LunarisQsSliderStyle,
    onSliderStyleChanged: (AxQsControl, LunarisQsSliderStyle) -> Unit,
    onRotateSlider: (AxAddItem.Control) -> Unit,
    onControlSpanChanged: (AxQsControl, AxQsSpan) -> Unit,
    spacing: Dp,
    controlPreview: @Composable (AxQsControl, AxQsSpan, LunarisQsSliderStyle) -> Unit,
    canAdd: (AxAddItem) -> Boolean,
    onAdd: (AxAddItem) -> Unit,
) {
    AxEditContainerCard(
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AvailableItemHeader(
            title = title,
            iconId = iconId,
            modifier =
                Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                ),
        )
        val rows = packAvailableItems(items, columns)
        val centerRows = items.firstOrNull() is AxAddItem.Control
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            val cellWidth = axQsGridCellWidth(maxWidth, columns, spacing)
            val aospTileHeight = AxTileDefaults.TileHeight * LocalTileScale.current
            val circleCells =
                useAxQsCircleCells(
                    gridWidth = maxWidth,
                    tileColumns = columns,
                    spacing = spacing,
                    allowCircles = items.firstOrNull() is AxAddItem.Tile,
                )
            val itemRowHeight = if (circleCells) cellWidth else aospTileHeight
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                spacing,
                                if (centerRows) {
                                    Alignment.CenterHorizontally
                                } else {
                                    Alignment.Start
                                },
                            ),
                    ) {
                        row.forEach { item ->
                            val span = item.pickerSpan(columns)
                            AxAddItemCell(
                                item = item,
                                span = span,
                                rowHeight = itemRowHeight,
                                spacing = spacing,
                                circleCells = circleCells,
                                sliderStyle = sliderStyle,
                                onSliderStyleChanged = onSliderStyleChanged,
                                onRotateSlider = onRotateSlider,
                                onResizeContent = { content ->
                                    onControlSpanChanged(
                                        content.control,
                                        content.control.nextSpan(content.span, columns),
                                    )
                                },
                                controlPreview = controlPreview,
                                canAdd = canAdd(item),
                                onAdd = onAdd,
                                modifier =
                                    Modifier.width(
                                        cellWidth * span.columns + spacing * (span.columns - 1)
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AxEditContainerCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 0.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = .32f),
                    preferenceShape(LocalPreferencePosition.current),
                )
                .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

@Composable
private fun AvailableItemHeader(title: String, iconId: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconId),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LunarisSliderStylePager(
    control: AxQsControl,
    span: AxQsSpan,
    selectedStyle: LunarisQsSliderStyle,
    onStyleSelected: (LunarisQsSliderStyle) -> Unit,
    controlPreview: @Composable (AxQsControl, AxQsSpan, LunarisQsSliderStyle) -> Unit,
    canAdd: Boolean,
    canChangeStyle: Boolean,
    clickLabel: String,
    onAdd: () -> Unit,
    previewHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val styles = LunarisQsSliderStyle.entries
    val pagerState = rememberPagerState(initialPage = selectedStyle.ordinal) { styles.size }
    val currentStyle = rememberUpdatedState(selectedStyle)
    val currentCanChangeStyle = rememberUpdatedState(canChangeStyle)
    val currentOnStyleSelected = rememberUpdatedState(onStyleSelected)
    LaunchedEffect(selectedStyle) {
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedStyle.ordinal) {
            pagerState.scrollToPage(selectedStyle.ordinal)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .map(styles::get)
            .distinctUntilChanged()
            .collect { style ->
                if (currentCanChangeStyle.value && style != currentStyle.value) {
                    currentOnStyleSelected.value(style)
                }
            }
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CommonTileDefaults.TileStartPadding),
    ) {
        Box(Modifier.fillMaxWidth().height(previewHeight)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = styles::get,
                overscrollEffect = null,
                userScrollEnabled = canChangeStyle,
            ) { page ->
                val style = styles[page]
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                        controlPreview(control, span, style)
                    }
                    Box(
                        Modifier.fillMaxSize()
                            .clip(axQsControlShape(control, span, style))
                            .clickable(
                                enabled = canAdd,
                                onClickLabel = clickLabel,
                                role = Role.Button,
                                onClick = onAdd,
                            )
                    )
                }
            }
            AxAddItemBadge(Modifier.align(Alignment.TopEnd))
        }
        AxQsPagerIndicator(pagerState)
    }
}

@Composable
private fun BoxScope.AxAddItemBadge(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun AxAddItemCell(
    item: AxAddItem,
    span: AxQsSpan,
    rowHeight: Dp,
    spacing: Dp,
    sliderStyle: (AxQsControl) -> LunarisQsSliderStyle,
    onSliderStyleChanged: (AxQsControl, LunarisQsSliderStyle) -> Unit,
    onRotateSlider: (AxAddItem.Control) -> Unit,
    onResizeContent: (AxAddItem.Control) -> Unit,
    controlPreview: @Composable (AxQsControl, AxQsSpan, LunarisQsSliderStyle) -> Unit,
    canAdd: Boolean,
    onAdd: (AxAddItem) -> Unit,
    circleCells: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val clickLabel =
        stringResource(R.string.accessibility_qs_edit_named_tile_add_action, item.label)
    val addedDescription =
        if (item.isAdded) {
            stringResource(R.string.accessibility_qs_edit_tile_already_added)
        } else {
            null
        }
    val fullDescription =
        if (!item.isAdded && !canAdd) {
            if (item is AxAddItem.Folder && item.limitReached) {
                stringResource(R.string.lunaris_qs_folder_limit)
            } else if (
                item is AxAddItem.Control || item is AxAddItem.Stack || item is AxAddItem.Folder
            ) {
                axString("ax_qs_control_grid_full", "Controls grid full")
            } else {
                axString("ax_qs_tile_grid_full", "Tiles grid full")
            }
        } else {
            null
        }
    val previewHeight = rowHeight * span.rows + spacing * (span.rows - 1)
    val slider = item is AxAddItem.Control && item.control.isSlider
    val controlStyle =
        (item as? AxAddItem.Control)?.control?.let(sliderStyle)
            ?: LunarisQsSliderStyle.M3_EXPRESSIVE
    val previewShape =
        when (item) {
            is AxAddItem.Folder -> axQsTileShape(span, LocalAxQsTileStyle.current)
            is AxAddItem.Stack -> axQsTileShape(span, LocalAxQsTileStyle.current)
            is AxAddItem.Tile -> axQsTileShape(span, LocalAxQsTileStyle.current)
            is AxAddItem.Control ->
                axQsControlShape(
                    item.control,
                    span,
                    controlStyle,
                    tileStyle = LocalAxQsTileStyle.current,
                )
        }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement =
            Arrangement.spacedBy(CommonTileDefaults.TileStartPadding, Alignment.Top),
        modifier =
            modifier
                .graphicsLayer { alpha = if (item.isAdded || !canAdd) .38f else 1f }
                .semantics(mergeDescendants = true) {
                    if (addedDescription != null) stateDescription = addedDescription
                    if (fullDescription != null) stateDescription = fullDescription
                },
    ) {
        if (item is AxAddItem.Control && slider) {
            LunarisSliderStylePager(
                control = item.control,
                span = span,
                selectedStyle = controlStyle,
                onStyleSelected = { style ->
                    onSliderStyleChanged(item.control, style)
                },
                controlPreview = controlPreview,
                canAdd = !item.isAdded && canAdd,
                canChangeStyle = !item.isAdded,
                clickLabel = clickLabel,
                onAdd = { onAdd(item) },
                previewHeight = previewHeight,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(previewHeight)) {
                Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                    when (item) {
                        is AxAddItem.Folder ->
                            LunarisFolderPreview(emptyList(), tileContent = { _, _ -> })
                        is AxAddItem.Stack -> AxTileStackPlaceholder()
                        is AxAddItem.Tile ->
                            AxQsEditTile(
                                tile = item.viewModel,
                                span = AxQsSpan.TileDefault,
                                circleCells = circleCells,
                                modifier = Modifier.fillMaxSize(),
                            )
                        is AxAddItem.Control ->
                            controlPreview(item.control, span, controlStyle)
                    }
                }
                Box(
                    modifier =
                        Modifier.fillMaxSize()
                            .zIndex(1f)
                            .clip(previewShape)
                            .clickable(
                                enabled = !item.isAdded && canAdd,
                                onClickLabel = clickLabel,
                                role = Role.Button,
                            ) {
                                onAdd(item)
                            }
                )
                AxAddItemBadge(Modifier.align(Alignment.TopEnd))
            }
        }
        if (
            item is AxAddItem.Control &&
                item.control.supportsOrientation &&
                span != AxQsSpan.TileDefault &&
                (!item.control.isResizableContent || span.columns != span.rows)
        ) {
            LunarisQsSliderOrientationButton(
                vertical = span.columns == 1,
                enabled = !item.isAdded,
                onClick = { onRotateSlider(item) },
            )
        }
        Text(
            text = item.label,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (item is AxAddItem.Control && item.control.isResizableContent) {
            TextButton(onClick = { onResizeContent(item) }, enabled = !item.isAdded) {
                Text(stringResource(R.string.lunaris_qs_content_size, span.columns, span.rows))
            }
        }
        if (item is AxAddItem.Folder && item.limitReached) {
            Text(
                stringResource(R.string.lunaris_qs_folder_limit),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        if (item is AxAddItem.Control && item.control == AxQsControl.FLASHLIGHT) {
            Text(
                text = stringResource(R.string.lunaris_qs_flashlight_slider_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (item is AxAddItem.Control && item.control == AxQsControl.RINGER) {
            Text(
                text = stringResource(R.string.lunaris_qs_ringer_slider_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun AxAddItem.pickerSpan(columns: Int): AxQsSpan {
    return when (this) {
        is AxAddItem.Folder -> LunarisQsTileFolderController.Span
        is AxAddItem.Stack -> AxQsTileStacks.Span
        is AxAddItem.Tile -> AxQsSpan.TileDefault
        is AxAddItem.Control -> {
            val span =
                if (control == AxQsControl.RINGER && this.span.columns > 1) {
                    AxQsSpan.TileWideDefault
                } else {
                    control.coerceSpan(this.span, columns)
                }
            span.copy(columns = span.columns.coerceAtMost(columns))
        }
    }
}

private fun packAvailableItems(items: List<AxAddItem>, columns: Int): List<List<AxAddItem>> =
    buildList {
        var row = mutableListOf<AxAddItem>()
        var usedColumns = 0
        var rowSpan = 0
        items.forEach { item ->
            val span = item.pickerSpan(columns)
            if (
                row.isNotEmpty() &&
                    (usedColumns + span.columns > columns || rowSpan != span.rows)
            ) {
                add(row)
                row = mutableListOf()
                usedColumns = 0
            }
            row.add(item)
            usedColumns += span.columns
            rowSpan = span.rows
        }
        if (row.isNotEmpty()) add(row)
    }

@Composable
fun AxQsEditTile(
    tile: EditTileViewModel,
    span: AxQsSpan,
    circleCells: Boolean = false,
    modifier: Modifier = Modifier,
    modes: List<ModeTileViewModel> = emptyList(),
) {
    if (
        tile.tileSpec.spec == "dnd" && span.columns > 1 && span.rows > 1 &&
            modes.any { it.id == ZenMode.MANUAL_DND_MODE_ID }
    ) {
        Box(modifier.gesturesDisabled().clearAndSetSemantics {}) {
            LunarisModesTile(
                modes = modes,
                span = span,
                enabled = false,
                onModeClick = {},
                onModeLongClick = {},
                onClick = {},
                onLongClick = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }
    val tileBg = AxTileDefaults.backgroundColor()
    val colors =
        TileColors(
            background = tileBg,
            iconBackground = Color.Transparent,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = MaterialTheme.colorScheme.onSurface,
            outline = MaterialTheme.colorScheme.onSurface,
        )
    val shape = axQsTileShape(span, LocalAxQsTileStyle.current)
    BoxWithConstraints(
        modifier = modifier.clip(shape).background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        val compactIconSize = AxTileDefaults.IconSize * LocalTileScale.current
        AnimatedContent(
            targetState = span.columns == 1,
            label = "AxQsEditTileLayout",
            contentAlignment = Alignment.Center,
        ) { compact ->
            if (compact) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SmallTileContent(
                        iconProvider = { tile.icon },
                        color = colors.icon,
                        size = { compactIconSize },
                    )
                    if (span.rows > 1) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = tile.label,
                            color = colors.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                AxLargeTileContent(
                    label = tile.label.text,
                    secondaryLabel = tile.appName?.text,
                    iconProvider = { tile.icon },
                    sideDrawable = null,
                    colors = colors,
                    squishiness = { 1f },
                    tileState = STATE_INACTIVE,
                    span = span,
                    isDualTarget = tile.isDualTarget,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
