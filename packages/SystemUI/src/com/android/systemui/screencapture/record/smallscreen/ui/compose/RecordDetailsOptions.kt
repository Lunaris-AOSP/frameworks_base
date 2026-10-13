/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.screencapture.record.smallscreen.ui.compose

import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.android.systemui.res.R
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureTarget
import com.android.systemui.screencapture.common.ui.compose.loadIcon
import com.android.systemui.screencapture.common.ui.viewmodel.DrawableLoaderViewModel
import com.android.systemui.screencapture.record.ui.viewmodel.ScreenCaptureRecordParametersViewModel
import com.android.systemui.screenrecord.domain.ScreenRecordingOptions

@Composable
internal fun RecordDetailsOptions(
    viewModel: ScreenCaptureRecordParametersViewModel,
    drawableLoaderViewModel: DrawableLoaderViewModel,
    enabled: Boolean,
) {
    val options = viewModel.options
    val icon = loadIcon(drawableLoaderViewModel, R.drawable.ic_settings, null)
    RichSwitch(
        icon,
        stringResource(R.string.screenrecord_skip_time_label),
        options.skipTimer,
        { value -> viewModel.updateOptions { it.copy(skipTimer = value) } },
        enabled,
    )
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.screenrecord_advanced_options))
    }
    AnimatedVisibility(expanded) {
        Column {
            RichSwitch(
                icon,
                stringResource(R.string.screenrecord_lowquality_label),
                options.lowQuality,
                { value -> viewModel.setLowQuality(value) },
                enabled,
            )
            RichSwitch(
                icon,
                stringResource(R.string.screenrecord_longer_timeout_switch_label),
                options.longerDuration,
                { value -> viewModel.setLongerDuration(value) },
                enabled,
            )
            if (viewModel.hasHevcEncoder) {
                RichSwitch(
                    icon,
                    stringResource(R.string.screenrecord_hevc_switch_label),
                    options.hevc,
                    { value -> viewModel.setHevc(value) },
                    enabled,
                )
            }
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val displayId =
                when (val target = options.target) {
                    is ScreenCaptureTarget.Fullscreen -> target.displayId
                    is ScreenCaptureTarget.App -> target.displayId
                    else -> Display.DEFAULT_DISPLAY
                }
            val display =
                context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
                    ?: context.display
            val metrics =
                remember(displayId, configuration) {
                    DisplayMetrics().also { display?.getRealMetrics(it) }
                }
            val longEdge = maxOf(metrics.widthPixels, metrics.heightPixels)
            val resolutions = ScreenRecordingOptions.resolutions(display)
            val resolutionLabels =
                resolutions.map { cap ->
                    if (cap == 0) stringResource(R.string.screenrecord_resolution_auto)
                    else {
                        val scale = cap.toDouble() / longEdge
                        val width = (metrics.widthPixels * scale).toInt() / 2 * 2
                        val height = (metrics.heightPixels * scale).toInt() / 2 * 2
                        stringResource(R.string.screenrecord_resolution_dimensions, width, height)
                    }
                }
            OptionMenu(
                stringResource(R.string.screenrecord_resolution_label),
                resolutions,
                resolutionLabels,
                options.resolutionMode,
                enabled,
            ) { value ->
                viewModel.updateOptions { it.copy(resolutionMode = value) }
            }
            val rates =
                remember(displayId, configuration) {
                    ScreenRecordingOptions.frameRates(context, display)
                }
            OptionMenu(
                stringResource(R.string.screenrecord_fps_label),
                rates,
                rates.map {
                    if (it == 0) stringResource(R.string.screenrecord_fps_auto)
                    else stringResource(R.string.screenrecord_fps_value, it)
                },
                options.fpsMode,
                enabled,
            ) { value ->
                viewModel.updateOptions { it.copy(fpsMode = value) }
            }
            OptionMenu(
                stringResource(R.string.screenrecord_bitrate_label),
                ScreenRecordingOptions.bitrates,
                stringArrayResource(R.array.screenrecord_bitrate_entries).toList(),
                options.bitrateMultiplier,
                enabled,
            ) { value ->
                viewModel.updateOptions { it.copy(bitrateMultiplier = value) }
            }
            OptionMenu(
                stringResource(R.string.screenrecord_time_limit_label),
                ScreenRecordingOptions.timeLimits,
                stringArrayResource(R.array.screenrecord_time_limit_entries).mapIndexed {
                    index,
                    label ->
                    if (index == 0)
                        stringResource(
                            R.string.screenrecord_default_limit,
                            stringResource(
                                if (options.longerDuration) R.string.screenrecord_time_unlimited
                                else R.string.screenrecord_time_60min
                            ),
                        )
                    else label
                },
                options.timeLimitMs,
                enabled,
            ) { value ->
                viewModel.updateOptions { it.copy(timeLimitMs = value) }
            }
            OptionMenu(
                stringResource(R.string.screenrecord_file_size_label),
                ScreenRecordingOptions.fileSizes,
                stringArrayResource(R.array.screenrecord_file_size_entries).mapIndexed {
                    index,
                    label ->
                    if (index == 0)
                        stringResource(
                            R.string.screenrecord_default_limit,
                            stringResource(
                                if (options.longerDuration) R.string.screenrecord_size_15gb
                                else R.string.screenrecord_size_5gb
                            ),
                        )
                    else label
                },
                options.fileSizeBytes,
                enabled,
            ) { value ->
                viewModel.updateOptions { it.copy(fileSizeBytes = value) }
            }
        }
    }
}

@Composable
private fun <T> OptionMenu(
    label: String,
    values: List<T>,
    labels: List<String>,
    selected: T,
    enabled: Boolean,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(enabled = enabled, role = Role.Button) { expanded = true }
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                labels[values.indexOf(selected).coerceAtLeast(0)],
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            values.forEachIndexed { index, value ->
                DropdownMenuItem(
                    text = { Text(labels[index]) },
                    onClick = {
                        expanded = false
                        onSelected(value)
                    },
                )
            }
        }
    }
}
