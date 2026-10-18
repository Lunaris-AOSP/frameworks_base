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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.android.systemui.qs.panels.shared.model.AxQsTileStacks
import com.android.systemui.qs.panels.ui.compose.AxTileStack
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ui.dialog.viewmodel.ModeTileViewModel

@Composable
internal fun AxEditTileStack(
    tiles: List<EditTileViewModel>,
    modes: List<ModeTileViewModel>,
    modifier: Modifier = Modifier,
) {
    AxTileStack(
        pageKeys = tiles.map { it.tileSpec.spec },
        swipeEnabled = false,
        modifier = modifier,
    ) { page ->
        AxQsEditTile(
            tile = tiles[page],
            span = AxQsTileStacks.Span,
            modes = modes,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun AxTileStackEditor(
    initialSpecs: List<String>,
    availableTiles: List<EditTileViewModel>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var selectedSpecs by remember(initialSpecs) { mutableStateOf(initialSpecs.distinct()) }
    val bySpec = remember(availableTiles) { availableTiles.associateBy { it.tileSpec.spec } }
    val entries =
        selectedSpecs + availableTiles.map { it.tileSpec.spec }.filterNot(selectedSpecs::contains)
    val move: (Int, Int) -> Unit = { from, to ->
        selectedSpecs = selectedSpecs.toMutableList().apply { add(to, removeAt(from)) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lunaris_qs_stack_edit)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.lunaris_qs_stack_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
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
                                        role = Role.Checkbox,
                                        onValueChange = { checked ->
                                            selectedSpecs =
                                                if (checked) selectedSpecs + spec
                                                else selectedSpecs - spec
                                        },
                                    ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = selected, onCheckedChange = null)
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
            TextButton(onClick = { onSave(selectedSpecs) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
