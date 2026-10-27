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

package com.android.systemui.qs.panels.ui.compose

import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_UNAVAILABLE
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.Expandable
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.animation.Expandable as SystemUiExpandable
import com.android.systemui.common.shared.model.Icon as TileIcon
import com.android.systemui.common.ui.compose.Icon as TileIconView
import com.android.systemui.qs.composefragment.mist.LocalBlurEnabled
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.shared.model.AxQsTileStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsTileFolder
import com.android.systemui.qs.panels.shared.model.LunarisQsTileFolderController
import com.android.systemui.qs.panels.ui.viewmodel.AxQsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toIconProvider
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.ui.composable.QuickSettingsTheme
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.create
import kotlin.math.roundToInt

@Composable
internal fun ContentScope.LunarisQsTileFolder(
    id: String,
    folder: LunarisQsTileFolder,
    span: AxQsSpan,
    tiles: List<TileViewModel>,
    layout: AxQsLayout,
    viewModel: AxQsViewModel,
    listening: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentFolder by rememberUpdatedState(folder)
    val currentTiles by rememberUpdatedState(tiles)
    val panelStyle by rememberUpdatedState(LocalAxQsTileStyle.current)
    val blurEnabled by rememberUpdatedState(LocalBlurEnabled.current)
    val gradientSettings by rememberUpdatedState(LocalLunarisQsGradientSettings.current)
    val strokeStyle by rememberUpdatedState(LocalLunarisQsStrokeStyle.current)
    val cosTileBackground by rememberUpdatedState(LocalLunarisQsCosTileBackground.current)
    val tileScale by rememberUpdatedState(LocalTileScale.current)
    val bySpec = remember(tiles) { tiles.associateBy { it.spec.spec } }
    val directCount = LunarisQsTileFolderController.directTileCount(span, folder.tileSpecs.size)
    var dialog by remember(id, layout) { mutableStateOf<SystemUIDialog?>(null) }
    val openLabel = stringResource(R.string.lunaris_qs_folder_open)
    val countLabel =
        stringResource(
            R.string.lunaris_qs_folder_count,
            folder.tileSpecs.size,
            LunarisQsTileFolderController.MAX_TILES,
        )
    val openFolder: (SystemUiExpandable) -> Unit = { expandable ->
        viewModel.onFolderClick {
            if (dialog == null) {
                val newDialog =
                    viewModel.folderDialogFactory.create(
                        context = context,
                        theme = R.style.Theme_SystemUI_QSFolder,
                        dialogDelegate = LunarisFolderWindowDelegate(),
                    ) { window ->
                        QuickSettingsTheme {
                            CompositionLocalProvider(
                                LocalAxQsTileStyle provides (currentFolder.tileStyle ?: panelStyle),
                                LocalBlurEnabled provides blurEnabled,
                                LocalLunarisQsGradientSettings provides gradientSettings,
                                LocalLunarisQsStrokeStyle provides strokeStyle,
                                LocalLunarisQsCosTileBackground provides cosTileBackground,
                                LocalTileScale provides tileScale,
                            ) {
                                this@LunarisQsTileFolder.LunarisFolderScreen(
                                    folder = currentFolder,
                                    tiles = currentTiles,
                                    onShapeChange = {
                                        viewModel.setFolderTileStyle(id, layout, it)
                                    },
                                    onTileResize = { spec, size ->
                                        viewModel.setFolderTileSpan(id, layout, spec, size)
                                    },
                                    onDismiss = { window.dismiss() },
                                )
                            }
                        }
                    }
                newDialog.setOnDismissListener { dialog = null }
                dialog = newDialog
                viewModel.showFolderDialog(newDialog, expandable)
            }
        }
    }
    DisposableEffect(id, layout) { onDispose { dialog?.dismiss() } }
    LaunchedEffect(listening()) { if (!listening()) dialog?.dismiss() }
    CompositionLocalProvider(LocalAxQsTileStyle provides (folder.tileStyle ?: panelStyle)) {
        LunarisFolderPreview(
            specs = folder.tileSpecs,
            span = span,
            modifier =
                modifier.semantics {
                    role = Role.Button
                    contentDescription = openLabel
                    stateDescription = countLabel
                },
            onClick = openFolder,
            onClickLabel = openLabel,
            tileContent = { spec, index ->
                val tile = bySpec[spec]
                if (tile == null) {
                    LunarisFolderUnavailableTile(AxTileDefaults.folderPreviewTileBackgroundColor())
                } else if (index < directCount) {
                    this@LunarisQsTileFolder.LunarisFolderLiveTile(tile, preview = true)
                } else {
                    LunarisFolderSmallTile(tile)
                }
            },
        )
    }
}

@Composable
internal fun LunarisFolderPreview(
    specs: List<String>,
    modifier: Modifier = Modifier,
    span: AxQsSpan = LunarisQsTileFolderController.Span,
    onClick: ((SystemUiExpandable) -> Unit)? = null,
    onClickLabel: String? = null,
    tileContent: @Composable (String, Int) -> Unit,
) {
    val shape = axQsTileShape(span, LocalAxQsTileStyle.current)
    val previewContent: @Composable (Modifier) -> Unit = { contentModifier ->
        val surfaceModifier =
            if (onClick == null) {
                Modifier.background(AxTileDefaults.backgroundColor())
            } else {
                // Expandable draws the folder surface itself. Avoid painting the translucent
                // surface a second time when blur is enabled, which makes the folder darker than
                // regular tiles.
                Modifier
            }
        Box(
            contentModifier
                .fillMaxSize()
                .clip(shape)
                .then(surfaceModifier)
                .then(
                    if (LocalLunarisQsCosTileBackground.current) {
                        Modifier.lunarisQsCosTileBorder(shape)
                    } else {
                        Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            shape,
                        )
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (specs.isEmpty()) {
                Icon(
                    Icons.Filled.Folder,
                    stringResource(R.string.lunaris_qs_tile_folder),
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                val members = LunarisQsTileFolderController.previewSpecs(specs, span)
                Layout(
                    modifier = Modifier.fillMaxSize(),
                    content = {
                        members.forEachIndexed { index, spec ->
                            key(spec) { Box(Modifier.fillMaxSize()) { tileContent(spec, index) } }
                        }
                    },
                ) { measurables, constraints ->
                    val width = constraints.maxWidth
                    val height = constraints.maxHeight
                    val side = minOf(width, height)
                    val singleCell = span == AxQsSpan.TileDefault
                    val vertical = span.columns == 1 && span.rows > 1
                    val compact = span.rows == 1 || vertical
                    val compactLength = if (vertical) height else width
                    val compactThickness = if (vertical) width else height
                    val directCount =
                        LunarisQsTileFolderController.directTileCount(span, specs.size)
                    val smallCount = (members.size - directCount).coerceAtLeast(0)
                    val smallRows = ((smallCount + 1) / 2).coerceAtLeast(1)
                    val smallCenters =
                        if (singleCell) {
                            listOf(
                                0.32f to 0.32f,
                                0.68f to 0.32f,
                                0.32f to 0.68f,
                                0.68f to 0.68f,
                            )
                        } else if (compact) {
                            (0 until smallCount).map { index ->
                                val x =
                                    if (smallCount == 1) 0.80f
                                    else if (index % 2 == 0) 0.73f
                                    else 0.87f
                                val y =
                                    when (smallRows) {
                                        1 -> 0.5f
                                        2 -> 0.33f + (index / 2) * 0.34f
                                        else -> 0.23f + (index / 2) * 0.27f
                                    }
                                x to y
                            }
                        } else {
                            when (smallCount) {
                                0 -> emptyList()
                                1 -> listOf(0.75f to 0.75f)
                                2 -> listOf(0.64f to 0.75f, 0.85f to 0.75f)
                                3 ->
                                    listOf(
                                        0.64f to 0.64f,
                                        0.85f to 0.64f,
                                        0.75f to 0.85f,
                                    )
                                4 ->
                                    listOf(
                                        0.64f to 0.64f,
                                        0.85f to 0.64f,
                                        0.64f to 0.85f,
                                        0.85f to 0.85f,
                                    )
                                else ->
                                    listOf(
                                        0.65f to 0.64f,
                                        0.84f to 0.64f,
                                        0.59f to 0.84f,
                                        0.75f to 0.84f,
                                        0.91f to 0.84f,
                                    )
                            }
                        }
                    val largeCenters =
                        if (compact) {
                            listOf(0.18f to 0.5f, 0.50f to 0.5f, 0.82f to 0.5f)
                        } else {
                            listOf(
                                0.26f to 0.26f,
                                0.74f to 0.26f,
                                0.26f to 0.74f,
                                0.74f to 0.74f,
                            )
                        }
                    val centers = largeCenters.take(directCount) + smallCenters
                    val placeables =
                        measurables.mapIndexed { index, measurable ->
                            val size =
                                if (singleCell) {
                                    side * 0.30f
                                } else if (compact) {
                                    if (index < directCount)
                                        minOf(compactThickness * 0.62f, compactLength * 0.24f)
                                    else
                                        minOf(
                                            compactLength * 0.105f,
                                            compactThickness * if (smallRows == 3) 0.21f else 0.26f,
                                        )
                                } else {
                                    side *
                                        if (index < directCount) 0.39f else 0.18f
                                }
                            measurable.measure(Constraints.fixed(size.roundToInt(), size.roundToInt()))
                        }
                    layout(width, height) {
                        placeables.forEachIndexed { index, placeable ->
                            val (x, y) = centers[index]
                            placeable.placeRelative(
                                (width * (if (vertical) y else x) - placeable.width / 2f)
                                    .roundToInt(),
                                (height * (if (vertical) x else y) - placeable.height / 2f)
                                    .roundToInt(),
                            )
                        }
                    }
                }
            }
        }
    }
    if (onClick != null) {
        Expandable(
            color = AxTileDefaults.backgroundColor(),
            shape = shape,
            modifier = modifier.fillMaxSize(),
            onClick = onClick,
            onClickLabel = onClickLabel,
            useModifierBasedImplementation = true,
            defaultMinSize = false,
        ) {
            previewContent(Modifier)
        }
    } else {
        previewContent(modifier)
    }
}

@Composable
private fun ContentScope.LunarisFolderLiveTile(tile: TileViewModel, preview: Boolean = false) {
    val scope = rememberCoroutineScope()
    val style = LocalAxQsTileStyle.current
    val shape =
        if (preview) {
            val state by tile.state.collectAsStateWithLifecycle(tile.currentState)
            val corner by
                animateIntAsState(
                    if (style.usesPillShape(isActive = state.state == STATE_ACTIVE)) 50 else 25,
                    label = "LunarisFolderTileShape",
                )
            RoundedCornerShape(percent = corner)
        } else {
            null
        }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        this@LunarisFolderLiveTile.AxTile(
            tile = tile,
            iconOnly = true,
            span = AxQsSpan.TileDefault,
            fillHeight = true,
            tileShapeOverride = shape,
            strokeStyle = false,
            inactiveBackgroundColor =
                if (preview) AxTileDefaults.folderPreviewTileBackgroundColor() else null,
            compactIconSize = minOf(maxWidth, maxHeight) * 0.46f,
            squishiness = { 1f },
            coroutineScope = scope,
            bounceableInfo = null,
            interactionSource = null,
            detailsViewModel = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun LunarisFolderSmallTile(tile: TileViewModel) {
    val state by tile.state.collectAsStateWithLifecycle(tile.currentState)
    val colors =
        AxTileColorsDefaults.getColorForState(
            state.toUiState(LocalResources.current),
            iconOnly = true,
            inactiveBackgroundColor = AxTileDefaults.folderPreviewTileBackgroundColor(),
        )
    val context = LocalContext.current
    val provider = state.toIconProvider()
    val icon =
        remember(provider, context) {
            provider.icon?.getDrawable(context)?.let { TileIcon.Loaded(it, null) }
                ?: TileIcon.Resource(R.drawable.ic_error_outline, null)
        }
    val style = LocalAxQsTileStyle.current
    val corner = if (style.usesPillShape(isActive = state.state == STATE_ACTIVE)) 50 else 25
    val shape = RoundedCornerShape(percent = corner)
    Box(
        Modifier.fillMaxSize()
            .clearAndSetSemantics {}
            .background(
                colors.backgroundGradient ?: SolidColor(colors.background),
                shape,
            )
            .lunarisQsCosTileBorder(
                shape = shape,
                enabled = LocalLunarisQsCosTileBackground.current && state.state != STATE_UNAVAILABLE,
            ),
        contentAlignment = Alignment.Center,
    ) {
        TileIconView(icon, modifier = Modifier.fillMaxSize(0.55f), tint = colors.icon)
    }
}

@Composable
internal fun LunarisFolderUnavailableTile(
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    Box(
        Modifier.fillMaxSize()
            .background(backgroundColor, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        TileIconView(
            TileIcon.Resource(R.drawable.ic_error_outline, null),
            modifier = Modifier.fillMaxSize(0.5f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun LunarisFolderShapePicker(
    selected: AxQsTileStyle?,
    onSelect: (AxQsTileStyle?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options =
        listOf(
            null to stringResource(R.string.lunaris_qs_folder_shape_panel),
            AxQsTileStyle.PILL to stringResource(R.string.ax_qs_settings_shape_pill),
            AxQsTileStyle.ROUNDED_SQUARE to stringResource(R.string.ax_qs_settings_shape_rounded),
            AxQsTileStyle.DYNAMIC to stringResource(R.string.ax_qs_settings_shape_dynamic),
            AxQsTileStyle.DYNAMIC_INVERTED to
                stringResource(R.string.ax_qs_settings_shape_dynamic_inverted),
        )
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                stringResource(R.string.ax_qs_settings_tile_shape) +
                    ": " +
                    options.first { it.first == selected }.second
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (style, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(style)
                        expanded = false
                    },
                )
            }
        }
    }
}
