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

@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.qs.panels.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.panels.shared.model.AxQsControl
import com.android.systemui.qs.panels.shared.model.AxQsGridLayout
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.AxQsPanelMode
import com.android.systemui.qs.panels.shared.model.AxQsTileStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsSliderStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsSpacing
import com.android.systemui.qs.panels.ui.compose.AxTileDefaults
import com.android.systemui.qs.panels.ui.viewmodel.AxQsViewModel
import com.android.systemui.res.R
import kotlin.math.roundToInt

@Composable
fun LunariQsPanelSettings(
    viewModel: AxQsViewModel,
    initialLayout: AxQsLayout,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var layout by rememberSaveable(initialLayout) { mutableStateOf(initialLayout) }
    val title = stringResource(R.string.ax_qs_panel_settings)
    val defaults = remember { LunarisQsSpacing() }
    val separate = viewModel.panelMode == AxQsPanelMode.SEPARATE
    val controls =
        when (layout) {
            AxQsLayout.QQS -> AxQsGridLayout.PORTRAIT_QQS_CONTROLS
            AxQsLayout.QS -> AxQsGridLayout.PORTRAIT_QS_CONTROLS
            AxQsLayout.SPLIT_SHADE -> AxQsGridLayout.SPLIT_SHADE_CONTROLS
        }
    val tiles =
        when (layout) {
            AxQsLayout.QQS -> AxQsGridLayout.PORTRAIT_QQS_TILES
            AxQsLayout.QS -> AxQsGridLayout.PORTRAIT_QS_TILES
            AxQsLayout.SPLIT_SHADE -> AxQsGridLayout.SPLIT_SHADE_TILES
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .semantics { paneTitle = title },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize().padding(horizontal = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = onBack,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.ax_qs_back_to_editor),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Button(onClick = onDone, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.quick_settings_done))
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item("appearance") {
                    SettingsHeading(stringResource(R.string.ax_qs_settings_appearance))
                }
                item("weather") {
                    SettingsSwitch(
                        title = stringResource(R.string.ax_qs_settings_weather),
                        summary = stringResource(R.string.ax_qs_settings_weather_summary),
                        checked = viewModel.showWeather,
                        onCheckedChange = viewModel::setShowWeather,
                        position = SettingPosition.First,
                    )
                }
                item("percentages") {
                    SettingsSwitch(
                        title = stringResource(R.string.ax_qs_settings_percentages),
                        summary = stringResource(R.string.ax_qs_settings_percentages_summary),
                        checked = viewModel.showSliderPercentage,
                        onCheckedChange = viewModel::setShowSliderPercentage,
                    )
                }
                item("shape") {
                    SettingsChoice(
                        title = stringResource(R.string.ax_qs_settings_tile_shape),
                        selected = viewModel.tileStyle.settingValue,
                        options =
                            listOf(
                                AxQsTileStyle.PILL.settingValue to
                                    stringResource(R.string.ax_qs_settings_shape_pill),
                                AxQsTileStyle.ROUNDED_SQUARE.settingValue to
                                    stringResource(R.string.ax_qs_settings_shape_rounded),
                                AxQsTileStyle.DYNAMIC.settingValue to
                                    stringResource(R.string.ax_qs_settings_shape_dynamic),
                            ),
                        onSelect = { viewModel.setTileStyle(AxQsTileStyle.fromSetting(it)) },
                        position = SettingPosition.Last,
                    )
                }
                item("behavior") {
                    SettingsHeading(stringResource(R.string.ax_qs_settings_behavior))
                }
                item("split") {
                    SettingsSwitch(
                        title = stringResource(R.string.ax_qs_settings_split),
                        summary = stringResource(R.string.ax_qs_settings_split_summary),
                        checked = separate,
                        onCheckedChange = {
                            viewModel.setPanelMode(
                                if (it) AxQsPanelMode.SEPARATE else AxQsPanelMode.TOGETHER
                            )
                        },
                        position = SettingPosition.First,
                    )
                }
                item("left") {
                    SettingsSwitch(
                        title = stringResource(R.string.ax_qs_settings_left),
                        summary = stringResource(R.string.ax_qs_settings_left_summary),
                        checked = viewModel.quickPanelOnLeft,
                        onCheckedChange = viewModel::setQuickPanelOnLeft,
                        enabled = separate,
                        position = SettingPosition.Last,
                    )
                }
                item("spacing") { SettingsHeading(stringResource(R.string.ax_qs_settings_spacing)) }
                item("gap") {
                    SettingsSlider(
                        title = stringResource(R.string.ax_qs_settings_tile_gap),
                        summary = stringResource(R.string.ax_qs_settings_tile_gap_summary),
                        value = viewModel.spacing.tileGap,
                        defaultValue = defaults.tileGap,
                        range = LunarisQsSpacing.TILE_GAP_RANGE,
                        onValueChange = viewModel::setTileGap,
                        position = SettingPosition.First,
                    )
                }
                item("side_padding") {
                    SettingsSlider(
                        title = stringResource(R.string.ax_qs_settings_side_padding),
                        summary = stringResource(R.string.ax_qs_settings_side_padding_summary),
                        value = viewModel.spacing.sidePadding,
                        defaultValue = defaults.sidePadding,
                        range = LunarisQsSpacing.SIDE_PADDING_RANGE,
                        onValueChange = viewModel::setSidePadding,
                    )
                }
                item("top_padding") {
                    SettingsSlider(
                        title = stringResource(R.string.ax_qs_settings_top_padding),
                        summary = stringResource(R.string.ax_qs_settings_top_padding_summary),
                        value = viewModel.spacing.topPadding,
                        defaultValue = defaults.topPadding,
                        range = LunarisQsSpacing.TOP_PADDING_RANGE,
                        onValueChange = viewModel::setTopPadding,
                        position = SettingPosition.Last,
                    )
                }
                item("layout") {
                    SettingsHeading(stringResource(R.string.ax_qs_settings_layout))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AxQsLayout.entries.forEach { option ->
                            FilterChip(
                                selected = layout == option,
                                onClick = { layout = option },
                                label = {
                                    Text(
                                        stringResource(
                                            when (option) {
                                                AxQsLayout.QQS -> R.string.ax_qs_settings_layout_top
                                                AxQsLayout.QS -> R.string.ax_qs_settings_layout_full
                                                AxQsLayout.SPLIT_SHADE ->
                                                    R.string.ax_qs_settings_layout_large
                                            }
                                        )
                                    )
                                },
                                leadingIcon =
                                    if (layout == option) {
                                        { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                                    } else null,
                            )
                        }
                    }
                }
                item("control_columns_$layout") {
                    SettingsChoice(
                        title = stringResource(R.string.ax_qs_settings_control_columns),
                        selected = viewModel.configuredColumns(controls) ?: 0,
                        options =
                            gridOptions(
                                viewModel
                                    .defaultColumns(controls)
                                    .coerceIn(viewModel.columnRange(controls)),
                                viewModel.columnRange(controls),
                            ),
                        onSelect = {
                            if (it == 0) viewModel.resetColumns(controls)
                            else viewModel.setColumns(controls, it)
                        },
                        position = SettingPosition.First,
                    )
                }
                item("tile_columns_$layout") {
                    SettingsChoice(
                        title = stringResource(R.string.ax_qs_settings_tile_columns),
                        selected = viewModel.configuredColumns(tiles) ?: 0,
                        options =
                            gridOptions(
                                viewModel
                                    .defaultColumns(tiles)
                                    .coerceIn(viewModel.columnRange(tiles)),
                                viewModel.columnRange(tiles),
                            ),
                        onSelect = {
                            if (it == 0) viewModel.resetColumns(tiles)
                            else viewModel.setColumns(tiles, it)
                        },
                    )
                }
                item("tile_rows_$layout") {
                    SettingsChoice(
                        title = stringResource(R.string.ax_qs_settings_tile_rows),
                        selected = viewModel.configuredRows(tiles) ?: 0,
                        options =
                            gridOptions(viewModel.defaultRows(tiles), viewModel.rowRange(tiles)!!),
                        onSelect = {
                            if (it == 0) viewModel.resetRows(tiles)
                            else viewModel.setRows(tiles, it)
                        },
                    )
                }
                item("brightness_style_$layout") {
                    SliderStyleSetting(viewModel, layout, AxQsControl.BRIGHTNESS)
                }
                item("volume_style_$layout") {
                    SliderStyleSetting(viewModel, layout, AxQsControl.VOLUME)
                }
                item("flashlight_style_$layout") {
                    SliderStyleSetting(
                        viewModel,
                        layout,
                        AxQsControl.FLASHLIGHT,
                        position =
                            if (tiles.supportsTileLabels) SettingPosition.Middle
                            else SettingPosition.Last,
                    )
                }
                if (tiles.supportsTileLabels) {
                    item("tile_labels_$layout") {
                        SettingsSwitch(
                            title = stringResource(R.string.ax_qs_settings_tile_labels),
                            checked = viewModel.showTileLabels(tiles),
                            onCheckedChange = { viewModel.setTileLabels(tiles, it) },
                            position = SettingPosition.Last,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun gridOptions(default: Int, range: IntRange): List<Pair<Int, String>> =
    listOf(0 to stringResource(R.string.ax_qs_settings_automatic, default)) +
        range.map { it to it.toString() }

@Composable
private fun SliderStyleSetting(
    viewModel: AxQsViewModel,
    layout: AxQsLayout,
    control: AxQsControl,
    position: SettingPosition = SettingPosition.Middle,
) {
    SettingsChoice(
        title =
            stringResource(
                when (control) {
                    AxQsControl.BRIGHTNESS -> R.string.ax_qs_settings_brightness_style
                    AxQsControl.VOLUME -> R.string.ax_qs_settings_volume_style
                    else -> R.string.ax_qs_settings_flashlight_style
                }
            ),
        selected = viewModel.sliderStyle(layout, control).settingValue,
        options =
            LunarisQsSliderStyle.entries.map { style ->
                style.settingValue to
                    stringResource(
                        when (style) {
                            LunarisQsSliderStyle.M3_EXPRESSIVE ->
                                R.string.ax_qs_settings_style_expressive
                            LunarisQsSliderStyle.PLATFORM -> R.string.ax_qs_settings_style_classic
                            LunarisQsSliderStyle.ROUNDED_SQUARE ->
                                R.string.ax_qs_settings_shape_rounded
                        }
                    )
            },
        onSelect = {
            viewModel.setSliderStyle(layout, control, LunarisQsSliderStyle.fromSetting(it))
        },
        position = position,
    )
}

private enum class SettingPosition {
    First,
    Middle,
    Last,
}

private fun settingShape(position: SettingPosition) =
    RoundedCornerShape(
        topStart = if (position == SettingPosition.First) 28.dp else 4.dp,
        topEnd = if (position == SettingPosition.First) 28.dp else 4.dp,
        bottomStart = if (position == SettingPosition.Last) 28.dp else 4.dp,
        bottomEnd = if (position == SettingPosition.Last) 28.dp else 4.dp,
    )

@Composable
private fun SettingsHeading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier.padding(start = 20.dp, top = 24.dp, bottom = 10.dp).semantics { heading() },
    )
}

@Composable
private fun SettingsLabel(title: String, summary: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (summary != null) {
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    position: SettingPosition = SettingPosition.Middle,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(settingShape(position))
                .background(AxTileDefaults.backgroundColor())
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsLabel(title, summary, Modifier.weight(1f).alpha(if (enabled) 1f else 0.38f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            thumbContent = {
                Icon(
                    if (checked) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
    }
}

@Composable
private fun SettingsChoice(
    title: String,
    selected: Int,
    options: List<Pair<Int, String>>,
    onSelect: (Int) -> Unit,
    position: SettingPosition = SettingPosition.Middle,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(settingShape(position))
                .background(AxTileDefaults.backgroundColor())
                .clickable(role = Role.Button) { showDialog = true }
                .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsLabel(
            title,
            options.firstOrNull { it.first == selected }?.second,
            Modifier.weight(1f),
        )
        Icon(
            Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp).selectableGroup()) {
                    options.forEach { (value, label) ->
                        item(value) {
                            Row(
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .selectable(
                                            selected = selected == value,
                                            role = Role.RadioButton,
                                        ) {
                                            onSelect(value)
                                            showDialog = false
                                        }
                                        .padding(horizontal = 8.dp, vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected == value, onClick = null)
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SettingsSlider(
    title: String,
    summary: String,
    value: Int,
    defaultValue: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    position: SettingPosition = SettingPosition.Middle,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val currentValue = sliderValue.roundToInt().coerceIn(range)
    val valueLabel = stringResource(R.string.ax_qs_settings_dp, currentValue)
    val spacingIconButtonColors =
        IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        )
    val commitValue: (Int) -> Unit = {
        val next = it.coerceIn(range)
        sliderValue = next.toFloat()
        if (next != value) onValueChange(next)
    }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(settingShape(position))
                .background(AxTileDefaults.backgroundColor())
                .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        SettingsLabel(title, summary)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                valueLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(
                onClick = { commitValue(defaultValue) },
                enabled = currentValue != defaultValue,
                colors = spacingIconButtonColors,
            ) {
                Icon(
                    Icons.Default.RestartAlt,
                    contentDescription = stringResource(R.string.ax_qs_settings_reset_value, title),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { commitValue(currentValue - 1) },
                enabled = currentValue > range.first,
                colors = spacingIconButtonColors,
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription =
                        stringResource(R.string.ax_qs_settings_decrease_value, title),
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { commitValue(sliderValue.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = range.last - range.first - 1,
                modifier =
                    Modifier.weight(1f).semantics {
                        contentDescription = title
                        stateDescription = valueLabel
                    },
            )
            IconButton(
                onClick = { commitValue(currentValue + 1) },
                enabled = currentValue < range.last,
                colors = spacingIconButtonColors,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription =
                        stringResource(R.string.ax_qs_settings_increase_value, title),
                )
            }
        }
    }
}
