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

package com.android.systemui.qs.panels.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.Icon as TileIconView
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.shared.model.AxQsTileStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsTileFolder
import com.android.systemui.qs.panels.shared.model.LunarisQsTileFolderController
import com.android.systemui.qs.panels.ui.compose.LocalAxQsTileStyle
import com.android.systemui.qs.panels.ui.compose.LunarisFolderPreview
import com.android.systemui.qs.panels.ui.compose.LunarisFolderShapePicker
import com.android.systemui.qs.panels.ui.compose.LunarisFolderUnavailableTile
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.res.R

@Composable
internal fun LunarisEditTileFolder(
    folder: LunarisQsTileFolder,
    tiles: List<EditTileViewModel>,
    span: AxQsSpan,
    modifier: Modifier = Modifier,
) {
    val bySpec = remember(tiles) { tiles.associateBy { it.tileSpec.spec } }
    val style = folder.tileStyle ?: LocalAxQsTileStyle.current
    CompositionLocalProvider(LocalAxQsTileStyle provides style) {
        LunarisFolderPreview(
            specs = folder.tileSpecs,
            modifier = modifier,
            span = span,
            tileContent = { spec, _ ->
                val tile = bySpec[spec]
                if (tile == null) {
                    LunarisFolderUnavailableTile()
                } else {
                    Box(
                        Modifier.fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(if (style == AxQsTileStyle.PILL) 50 else 25),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        TileIconView(
                            tile.icon,
                            modifier = Modifier.fillMaxSize(0.5f),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
        )
    }
}

@Composable
internal fun LunarisTileFolderEditor(
    initialFolder: LunarisQsTileFolder,
    availableTiles: List<EditTileViewModel>,
    onDismiss: () -> Unit,
    onSave: (LunarisQsTileFolder) -> Unit,
) {
    var selectedSpecs by
        remember(initialFolder) {
            mutableStateOf(LunarisQsTileFolderController.normalizeSpecs(initialFolder.tileSpecs))
        }
    var selectedStyle by remember(initialFolder) { mutableStateOf(initialFolder.tileStyle) }
    val bySpec = remember(availableTiles) { availableTiles.associateBy { it.tileSpec.spec } }
    val entries =
        selectedSpecs + availableTiles.map { it.tileSpec.spec }.filterNot(selectedSpecs::contains)
    val move: (Int, Int) -> Unit = { from, to ->
        selectedSpecs = selectedSpecs.toMutableList().apply { add(to, removeAt(from)) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lunaris_qs_folder_edit)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.lunaris_qs_folder_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                LunarisFolderShapePicker(selectedStyle) { selectedStyle = it }
                Text(
                    stringResource(
                        R.string.lunaris_qs_folder_count,
                        selectedSpecs.size,
                        LunarisQsTileFolderController.MAX_TILES,
                    ),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 320.dp)) {
                    items(entries, key = { it }) { spec ->
                        val index = selectedSpecs.indexOf(spec)
                        val selected = index >= 0
                        val label =
                            bySpec[spec]?.label?.text
                                ?: stringResource(R.string.lunaris_qs_stack_unavailable_tile)
                        Row(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .toggleable(
                                        value = selected,
                                        enabled =
                                            selected ||
                                                selectedSpecs.size <
                                                    LunarisQsTileFolderController.MAX_TILES,
                                        role = Role.Checkbox,
                                        onValueChange = { checked ->
                                            selectedSpecs =
                                                if (checked)
                                                    LunarisQsTileFolderController.normalizeSpecs(
                                                        selectedSpecs + spec
                                                    )
                                                else selectedSpecs - spec
                                        },
                                    ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                enabled =
                                    selected ||
                                        selectedSpecs.size <
                                            LunarisQsTileFolderController.MAX_TILES,
                                onClick = null,
                            )
                            Text(
                                text = label,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            if (selected) {
                                IconButton(
                                    enabled = index > 0,
                                    onClick = { move(index, index - 1) },
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowUp,
                                        stringResource(R.string.lunaris_qs_stack_move_up, label),
                                    )
                                }
                                IconButton(
                                    enabled = index < selectedSpecs.lastIndex,
                                    onClick = { move(index, index + 1) },
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowDown,
                                        stringResource(R.string.lunaris_qs_stack_move_down, label),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(LunarisQsTileFolder(selectedSpecs, selectedStyle)) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
internal fun LunarisFolderEditButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Box(
            Modifier.size(24.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(R.string.lunaris_qs_folder_edit),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
