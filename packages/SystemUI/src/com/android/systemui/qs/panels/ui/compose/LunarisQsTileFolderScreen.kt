/*
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

import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.view.Gravity
import android.view.WindowManager.LayoutParams
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.qs.panels.shared.model.AxQsGridItem
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.shared.model.AxQsTileStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsTileFolder
import com.android.systemui.qs.panels.ui.compose.selection.TileState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.DialogDelegate
import com.android.systemui.statusbar.phone.SystemUIDialog
import kotlin.math.roundToInt

private const val FOLDER_DIALOG_WIDTH_FRACTION = 0.92f
private const val FOLDER_DIALOG_HEIGHT_FRACTION = 0.84f
private const val FOLDER_DIALOG_MAX_WIDTH_DP = 600f
private const val FOLDER_DIALOG_MAX_HEIGHT_DP = 800f
private const val FOLDER_DIALOG_CORNER_RADIUS_DP = 28f

internal class LunarisFolderWindowDelegate : DialogDelegate<SystemUIDialog> {
    private val background = GradientDrawable()

    override fun getWidth(dialog: SystemUIDialog): Int {
        val metrics = dialog.context.resources.displayMetrics
        return minOf(
            (metrics.widthPixels * FOLDER_DIALOG_WIDTH_FRACTION).roundToInt(),
            (FOLDER_DIALOG_MAX_WIDTH_DP * metrics.density).roundToInt(),
        )
    }

    override fun getHeight(dialog: SystemUIDialog): Int {
        val metrics = dialog.context.resources.displayMetrics
        return minOf(
            (metrics.heightPixels * FOLDER_DIALOG_HEIGHT_FRACTION).roundToInt(),
            (FOLDER_DIALOG_MAX_HEIGHT_DP * metrics.density).roundToInt(),
        )
    }

    override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setDecorFitsSystemWindows(false)
            setGravity(Gravity.CENTER)
            setBackgroundDrawable(background)
            addFlags(LayoutParams.FLAG_DIM_BEHIND)
            decorView.setPadding(0, 0, 0, 0)
        }
        updateBackground(dialog)
    }

    override fun onConfigurationChanged(dialog: SystemUIDialog, configuration: Configuration) {
        updateBackground(dialog)
    }

    private fun updateBackground(dialog: SystemUIDialog) {
        val context = dialog.context
        val color = context.getColor(R.color.dialog_bg_color)
        if (background.color?.defaultColor != color) background.setColor(color)
        background.cornerRadius =
            FOLDER_DIALOG_CORNER_RADIUS_DP * context.resources.displayMetrics.density
    }
}

@Composable
internal fun ContentScope.LunarisFolderScreen(
    folder: LunarisQsTileFolder,
    tiles: List<TileViewModel>,
    onShapeChange: (AxQsTileStyle?) -> Unit,
    onTileResize: (String, AxQsSpan) -> Unit,
    onDismiss: () -> Unit,
) {
    val bySpec = remember(tiles) { tiles.associateBy { it.spec.spec } }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var editing by remember { mutableStateOf(false) }
    var selectedSpec by remember { mutableStateOf<String?>(null) }
    var resizingSpec by remember { mutableStateOf<String?>(null) }
    var tileSpans by remember { mutableStateOf(folder.tileSpans) }
    val stopEditing = {
        editing = false
        selectedSpec = null
    }
    BackHandler(enabled = editing, onBack = stopEditing)
    TileListener(tiles) { true }
    val title = stringResource(R.string.lunaris_qs_tile_folder)
    val resizeLabel = stringResource(R.string.accessibility_qs_edit_toggle_tile_size_action)

    Column(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(FOLDER_DIALOG_CORNER_RADIUS_DP.dp))
            .safeDrawingPadding()
            .semantics { paneTitle = title }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (editing) stopEditing() else onDismiss() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.accessibility_back),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TextButton(onClick = { if (editing) stopEditing() else editing = true }) {
                Text(stringResource(if (editing) R.string.quick_settings_done else R.string.qs_edit))
            }
        }
        if (editing) {
            Column(
                Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LunarisFolderShapePicker(folder.tileStyle, onShapeChange)
                Text(
                    stringResource(R.string.lunaris_qs_folder_resize_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
            val columns = if (maxWidth >= 600.dp) 6 else 4
            val spacing = 16.dp * LocalTileScale.current
            val cellWidth = axQsGridCellWidth(maxWidth, columns, spacing)
            val maxSpan = AxQsSpan.controlTileMax(columns)
            val items =
                folder.tileSpecs.map { spec ->
                    AxQsGridItem(
                        id = spec,
                        span = (tileSpans[spec] ?: AxQsSpan(2, 2)).coerceForControlTile(columns),
                        minSpan = AxQsSpan.TileDefault,
                        maxSpan = maxSpan,
                        value = bySpec[spec],
                    )
                }
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(scrollState, enabled = resizingSpec == null)
                    .padding(vertical = 24.dp)
            ) {
                if (items.isEmpty()) {
                    Text(stringResource(R.string.lunaris_qs_folder_empty))
                }
                AxQsGrid(
                    items = items,
                    columns = columns,
                    rowHeight = cellWidth,
                    spacing = spacing,
                    animateItemBounds = true,
                    staticItemId = resizingSpec,
                    modifier = Modifier.fillMaxWidth(),
                ) { item ->
                    val tile = item.value
                    val state =
                        if (tile != null) {
                            tile.state.collectAsStateWithLifecycle(tile.currentState).value
                        } else {
                            null
                        }
                    val label =
                        state?.label?.toString()
                            ?: stringResource(R.string.lunaris_qs_stack_unavailable_tile)
                    val shape =
                        axQsTileShape(
                            item.span,
                            LocalAxQsTileStyle.current,
                            state?.state ?: STATE_INACTIVE,
                        )
                    val tileContent: @Composable () -> Unit = {
                        if (tile == null) {
                            LunarisFolderUnavailableTile()
                        } else {
                            this@LunarisFolderScreen.AxTile(
                                tile = tile,
                                iconOnly = item.span.columns == 1,
                                span = item.span,
                                fillHeight = true,
                                squishiness = { 1f },
                                coroutineScope = scope,
                                bounceableInfo = null,
                                interactionSource = null,
                                detailsViewModel = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    if (editing) {
                        val resize: (AxQsSpan) -> Unit = { tileSpans = tileSpans + (item.id to it) }
                        val save: (AxQsSpan) -> Unit = { onTileResize(item.id, it) }
                        AxInteractiveTileContainer(
                            tileState =
                                if (selectedSpec == item.id) TileState.Selected else TileState.None,
                            resizeHandleModifier =
                                Modifier.axQsResizeHandle(
                                    id = item.id,
                                    span = { tileSpans[item.id] ?: item.span },
                                    cellWidth = cellWidth,
                                    rowHeight = cellWidth,
                                    spacing = spacing,
                                    resolveSpan = { start, dx, dy ->
                                        AxQsSpan(
                                            (start.columns + dx).coerceIn(1, maxSpan.columns),
                                            (start.rows + dy).coerceIn(1, maxSpan.rows),
                                        )
                                    },
                                    canResize = { it == it.coerceForControlTile(columns) },
                                    onResizeStarted = { resizingSpec = item.id },
                                    onResizeStopped = { resizingSpec = null },
                                    onResize = resize,
                                    onResizeFinished = save,
                                ),
                            selectionColor = MaterialTheme.colorScheme.primary,
                            selectionShape = shape,
                            selectionHorizontalPadding = 0.dp,
                            selectionVerticalPadding = 0.dp,
                            resizable = true,
                            removable = false,
                            onRemoveClick = {},
                            onResizeClick = {
                                val current = tileSpans[item.id] ?: item.span
                                val next =
                                    current.copy(
                                        columns =
                                            if (current.columns < maxSpan.columns) current.columns + 1
                                            else 1
                                    )
                                resize(next)
                                save(next)
                            },
                            resizeContentDescription = resizeLabel,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Box(Modifier.fillMaxSize().clearAndSetSemantics {}) { tileContent() }
                            Box(
                                Modifier.fillMaxSize()
                                    .clip(shape)
                                    .clickable(role = Role.Button) { selectedSpec = item.id }
                                    .semantics {
                                        contentDescription = label
                                        selected = selectedSpec == item.id
                                    }
                            )
                        }
                    } else {
                        tileContent()
                    }
                }
            }
        }
    }
}
