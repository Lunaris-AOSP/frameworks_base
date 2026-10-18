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

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.panels.shared.model.AxQsControl
import com.android.systemui.qs.panels.shared.model.AxQsGridLayout
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.AxQsPanelMode
import com.android.systemui.qs.panels.shared.model.AxQsTileStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsSliderStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsSpacing
import com.android.systemui.qs.panels.ui.compose.AxTileDefaults
import com.android.systemui.qs.panels.ui.compose.rememberLunarisQsGradient
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
    val gradientSettings = viewModel.gradientSettings
    val gradientEnabled = gradientSettings.enabled
    val gradientScope =
        if (gradientSettings.sliderOnly) GRADIENT_SCOPE_SLIDERS_ONLY else GRADIENT_SCOPE_ALL_ACTIVE
    val gradientMode = gradientSettings.colorMode
    val autoGradientStyle = gradientSettings.autoStyle
    val gradientRotation = gradientSettings.rotation
    val gradient = rememberLunarisQsGradient(gradientSettings)
    val gradientPreviewLabel = stringResource(R.string.ax_qs_settings_gradient_preview)
    var showStartColorPicker by rememberSaveable { mutableStateOf(false) }
    var showEndColorPicker by rememberSaveable { mutableStateOf(false) }
    val startColor =
        if (gradientSettings.startColor == 0) MaterialTheme.colorScheme.primary
        else Color(gradientSettings.startColor)
    val endColor =
        if (gradientSettings.endColor == 0) MaterialTheme.colorScheme.secondary
        else Color(gradientSettings.endColor)

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
                    )
                }
                item("dual_target_tile_style") {
                    SettingsSwitch(
                        title = stringResource(R.string.ax_qs_settings_dual_target_tile_style),
                        summary =
                            stringResource(R.string.ax_qs_settings_dual_target_tile_style_summary),
                        checked = viewModel.fullColorDualTargetTiles,
                        onCheckedChange = viewModel::setFullColorDualTargetTiles,
                    )
                }
                item("stroke_style") {
                    SettingsSwitch(
                        title = stringResource(R.string.ax_qs_settings_stroke_style),
                        summary = stringResource(R.string.ax_qs_settings_stroke_style_summary),
                        checked = viewModel.strokeStyle,
                        onCheckedChange = viewModel::setStrokeStyle,
                        position = SettingPosition.Last,
                    )
                }
                item("gradient") {
                    SettingsHeading(stringResource(R.string.ax_qs_settings_gradient))
                }
                item("gradient_enabled") {
                    SettingsSwitch(
                        title = stringResource(R.string.ax_qs_settings_gradient_enabled),
                        summary = stringResource(R.string.ax_qs_settings_gradient_enabled_summary),
                        checked = gradientEnabled,
                        onCheckedChange = viewModel::setGradientEnabled,
                        position = SettingPosition.First,
                    )
                }
                item("gradient_scope") {
                    SettingsChoice(
                        title = stringResource(R.string.ax_qs_settings_gradient_scope),
                        selected = gradientScope,
                        options =
                            listOf(
                                GRADIENT_SCOPE_ALL_ACTIVE to
                                    stringResource(R.string.ax_qs_settings_gradient_scope_all),
                                GRADIENT_SCOPE_SLIDERS_ONLY to
                                    stringResource(R.string.ax_qs_settings_gradient_scope_sliders),
                            ),
                        onSelect = {
                            viewModel.setGradientSliderOnly(it == GRADIENT_SCOPE_SLIDERS_ONLY)
                        },
                        enabled = gradientEnabled,
                    )
                }
                item("gradient_mode") {
                    SettingsChoice(
                        title = stringResource(R.string.ax_qs_settings_gradient_mode),
                        selected = gradientMode,
                        options =
                            listOf(
                                GRADIENT_MODE_AUTO to
                                    stringResource(R.string.ax_qs_settings_gradient_mode_auto),
                                GRADIENT_MODE_MANUAL to
                                    stringResource(R.string.ax_qs_settings_gradient_mode_manual),
                            ),
                        onSelect = viewModel::setGradientColorMode,
                        enabled = gradientEnabled,
                    )
                }
                if (gradientEnabled && gradientMode == GRADIENT_MODE_AUTO) {
                    item("gradient_auto_style") {
                        SettingsChoice(
                            title = stringResource(R.string.ax_qs_settings_gradient_auto_style),
                            selected = autoGradientStyle,
                            options =
                                listOf(
                                    GRADIENT_AUTO_STYLE_LIGHT_DEEP_PRIMARY to
                                        stringResource(
                                            R.string
                                                .ax_qs_settings_gradient_auto_style_light_deep_primary
                                        ),
                                    GRADIENT_AUTO_STYLE_PRIMARY_SECONDARY to
                                        stringResource(
                                            R.string
                                                .ax_qs_settings_gradient_auto_style_primary_secondary
                                        ),
                                ),
                            onSelect = viewModel::setGradientAutoStyle,
                            enabled = gradientEnabled,
                        )
                    }
                } else if (gradientEnabled) {
                    item("gradient_start_color") {
                        SettingsColorChoice(
                            title = stringResource(R.string.ax_qs_settings_gradient_start_color),
                            color = startColor,
                            onClick = { showStartColorPicker = true },
                        )
                    }
                    item("gradient_end_color") {
                        SettingsColorChoice(
                            title = stringResource(R.string.ax_qs_settings_gradient_end_color),
                            color = endColor,
                            onClick = { showEndColorPicker = true },
                        )
                    }
                }
                if (gradientEnabled) {
                    item("gradient_preview") {
                        Box(
                            Modifier.fillMaxWidth()
                                .height(64.dp)
                                .background(gradient.brush, RoundedCornerShape(4.dp))
                                .semantics { contentDescription = gradientPreviewLabel },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(gradientPreviewLabel, color = gradient.contentColor)
                        }
                    }
                }
                item("gradient_rotation") {
                    SettingsSlider(
                        title = stringResource(R.string.ax_qs_settings_gradient_rotation),
                        summary = stringResource(R.string.ax_qs_settings_gradient_rotation_summary),
                        value = gradientRotation,
                        defaultValue = 0,
                        range = 0..360,
                        onValueChange = viewModel::setGradientRotation,
                        position = SettingPosition.Last,
                        enabled = gradientEnabled,
                        valueInDegrees = true,
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

    if (showStartColorPicker) {
        GradientColorPickerDialog(
            title = stringResource(R.string.ax_qs_settings_gradient_start_color),
            initialColor = startColor,
            onDismiss = { showStartColorPicker = false },
            onConfirm = { color ->
                viewModel.setGradientStartColor(color)
                showStartColorPicker = false
            },
        )
    }
    if (showEndColorPicker) {
        GradientColorPickerDialog(
            title = stringResource(R.string.ax_qs_settings_gradient_end_color),
            initialColor = endColor,
            onDismiss = { showEndColorPicker = false },
            onConfirm = { color ->
                viewModel.setGradientEndColor(color)
                showEndColorPicker = false
            },
        )
    }
}

private const val GRADIENT_SCOPE_ALL_ACTIVE = 0
private const val GRADIENT_SCOPE_SLIDERS_ONLY = 1
private const val GRADIENT_MODE_AUTO = 0
private const val GRADIENT_MODE_MANUAL = 1
private const val GRADIENT_AUTO_STYLE_LIGHT_DEEP_PRIMARY = 0
private const val GRADIENT_AUTO_STYLE_PRIMARY_SECONDARY = 1

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
    enabled: Boolean = true,
    position: SettingPosition = SettingPosition.Middle,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(settingShape(position))
                .background(AxTileDefaults.backgroundColor())
                .clickable(enabled = enabled, role = Role.Button) { showDialog = true }
                .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsLabel(
            title,
            options.firstOrNull { it.first == selected }?.second,
            Modifier.weight(1f).alpha(if (enabled) 1f else 0.38f),
        )
        Icon(
            Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
        )
    }
    if (showDialog && enabled) {
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
private fun SettingsColorChoice(
    title: String,
    color: Color,
    onClick: () -> Unit,
    position: SettingPosition = SettingPosition.Middle,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(settingShape(position))
                .background(AxTileDefaults.backgroundColor())
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsLabel(title, null, Modifier.weight(1f))
        Box(
            modifier =
                Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(color).semantics {
                    contentDescription = title
                }
        )
    }
}

@Composable
private fun GradientColorPickerDialog(
    title: String,
    initialColor: Color,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initialHsv =
        remember(initialColor) {
            FloatArray(3).also { hsv -> AndroidColor.colorToHSV(initialColor.toArgb(), hsv) }
        }
    var hue by rememberSaveable(initialColor) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable(initialColor) { mutableFloatStateOf(initialHsv[1]) }
    var value by rememberSaveable(initialColor) { mutableFloatStateOf(initialHsv[2]) }
    var hexInput by
        rememberSaveable(initialColor) { mutableStateOf(colorHex(initialColor.toArgb())) }
    val selectedColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))
    val hueColor = Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    val validHex = hexInput.length == 6 && hexInput.toIntOrNull(16) != null
    val colorAreaLabel = stringResource(R.string.ax_qs_settings_gradient_color_area)

    fun updateColor(
        nextHue: Float = hue,
        nextSaturation: Float = saturation,
        nextValue: Float = value,
    ) {
        hue = nextHue
        saturation = nextSaturation
        value = nextValue
        hexInput = colorHex(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Canvas(
                    Modifier.fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .semantics { contentDescription = colorAreaLabel }
                        .pointerInput(initialColor) {
                            fun select(position: Offset) {
                                updateColor(
                                    nextSaturation =
                                        (position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                    nextValue =
                                        1f -
                                            (position.y / size.height.coerceAtLeast(1)).coerceIn(
                                                0f,
                                                1f,
                                            ),
                                )
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                select(down.position)
                                drag(down.id) { change ->
                                    change.consume()
                                    select(change.position)
                                }
                            }
                        }
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val center = Offset(saturation * size.width, (1f - value) * size.height)
                    drawCircle(Color.Black, 8.dp.toPx(), center, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White, 8.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(48.dp).background(selectedColor, RoundedCornerShape(12.dp)))
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { input ->
                            hexInput =
                                input
                                    .removePrefix("#")
                                    .filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                                    .take(6)
                                    .uppercase()
                            if (hexInput.length == 6) {
                                val hsv = FloatArray(3)
                                AndroidColor.colorToHSV(
                                    hexInput.toInt(16) or 0xFF000000.toInt(),
                                    hsv,
                                )
                                hue = hsv[0]
                                saturation = hsv[1]
                                value = hsv[2]
                            }
                        },
                        label = { Text(stringResource(R.string.ax_qs_settings_gradient_hex)) },
                        prefix = { Text("#") },
                        isError = !validHex,
                        supportingText =
                            if (!validHex) {
                                { Text(stringResource(R.string.ax_qs_settings_gradient_hex_error)) }
                            } else null,
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Ascii,
                            ),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                ColorPickerSlider(
                    title = stringResource(R.string.ax_qs_settings_gradient_hue),
                    value = hue,
                    valueRange = 0f..360f,
                    steps = 359,
                    valueLabel = stringResource(R.string.ax_qs_settings_degrees, hue.roundToInt()),
                    brush =
                        Brush.horizontalGradient(
                            (0..6).map {
                                Color(AndroidColor.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)))
                            }
                        ),
                    onValueChange = { updateColor(nextHue = it) },
                )
                ColorPickerSlider(
                    title = stringResource(R.string.ax_qs_settings_gradient_saturation),
                    value = saturation,
                    valueRange = 0f..1f,
                    steps = 99,
                    valueLabel = "${(saturation * 100).roundToInt()}%",
                    brush = Brush.horizontalGradient(listOf(Color.White, hueColor)),
                    onValueChange = { updateColor(nextSaturation = it) },
                )
                ColorPickerSlider(
                    title = stringResource(R.string.ax_qs_settings_gradient_brightness),
                    value = value,
                    valueRange = 0f..1f,
                    steps = 99,
                    valueLabel = "${(value * 100).roundToInt()}%",
                    brush =
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black,
                                Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, 1f))),
                            )
                        ),
                    onValueChange = { updateColor(nextValue = it) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = validHex,
                onClick = { onConfirm(hexInput.toInt(16) or 0xFF000000.toInt()) },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun colorHex(color: Int): String =
    (color and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorPickerSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    brush: Brush,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(valueLabel, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            track = {
                Box(
                    Modifier.fillMaxWidth()
                        .height(12.dp)
                        .background(brush, RoundedCornerShape(6.dp))
                )
            },
            modifier =
                Modifier.fillMaxWidth().semantics {
                    contentDescription = title
                    stateDescription = valueLabel
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
    enabled: Boolean = true,
    position: SettingPosition = SettingPosition.Middle,
    valueInDegrees: Boolean = false,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val currentValue = sliderValue.roundToInt().coerceIn(range)
    val valueLabel =
        stringResource(
            if (valueInDegrees) R.string.ax_qs_settings_degrees else R.string.ax_qs_settings_dp,
            currentValue,
        )
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
        SettingsLabel(title, summary, Modifier.alpha(if (enabled) 1f else 0.38f))
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
                enabled = enabled && currentValue != defaultValue,
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
                enabled = enabled && currentValue > range.first,
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
                enabled = enabled,
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
                enabled = enabled && currentValue < range.last,
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
