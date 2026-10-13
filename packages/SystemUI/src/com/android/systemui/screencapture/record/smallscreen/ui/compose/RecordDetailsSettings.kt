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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.res.R
import com.android.systemui.screencapture.common.ui.compose.LoadingIcon
import com.android.systemui.screencapture.common.ui.compose.loadIcon
import com.android.systemui.screencapture.common.ui.viewmodel.DrawableLoaderViewModel
import com.android.systemui.screencapture.record.smallscreen.ui.viewmodel.RecordDetailsTargetItemViewModel
import com.android.systemui.screencapture.record.smallscreen.ui.viewmodel.RecordDetailsTargetViewModel
import com.android.systemui.screencapture.record.ui.viewmodel.ScreenCaptureRecordParametersViewModel

@Composable
fun RecordDetailsSettings(
    parametersViewModel: ScreenCaptureRecordParametersViewModel,
    targetViewModel: RecordDetailsTargetViewModel,
    drawableLoaderViewModel: DrawableLoaderViewModel,
    onAppSelectorClicked: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier.padding(vertical = 12.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        ) {
            AnimatedVisibility(visible = targetViewModel.canChangeTarget) {
                CaptureTargetSelector(
                    items = targetViewModel.items,
                    selectedItemIndex = targetViewModel.selectedIndex,
                    onItemSelected = { targetViewModel.select(it) },
                    itemToString = {
                        (it as? RecordDetailsTargetItemViewModel.EntireScreen)?.displayName
                            ?: stringResource(it.labelRes)
                    },
                    isItemEnabled = { it.isSelectable },
                    viewModel = drawableLoaderViewModel,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            AnimatedVisibility(visible = enabled && targetViewModel.shouldShowAppSelector) {
                AppSelectorButton(
                    appLabel = targetViewModel.selectedAppName?.getOrNull()?.toString(),
                    viewModel = drawableLoaderViewModel,
                    onClick = onAppSelectorClicked,
                )
            }

            RichSwitch(
                icon =
                    loadIcon(
                        viewModel = drawableLoaderViewModel,
                        resId = R.drawable.ic_phone_expressive,
                        contentDescription = null,
                    ),
                label = stringResource(R.string.screen_record_record_device_audio_label),
                checked = parametersViewModel.shouldRecordDevice,
                onCheckedChange = { parametersViewModel.shouldRecordDevice = it },
                enabled = enabled,
                modifier = Modifier,
            )
            RichSwitch(
                icon =
                    loadIcon(
                        viewModel = drawableLoaderViewModel,
                        resId = R.drawable.ic_mic_expressive,
                        contentDescription = null,
                    ),
                label = stringResource(R.string.screen_record_record_microphone_label),
                checked = parametersViewModel.shouldRecordMicrophone,
                onCheckedChange = { parametersViewModel.shouldRecordMicrophone = it },
                enabled = enabled,
                modifier = Modifier,
            )
            AnimatedVisibility(
                targetViewModel.currentTarget is RecordDetailsTargetItemViewModel.EntireScreen
            ) {
                RichSwitch(
                    icon =
                        loadIcon(
                            viewModel = drawableLoaderViewModel,
                            resId = R.drawable.ic_touch_expressive,
                            contentDescription = null,
                        ),
                    label = stringResource(R.string.screen_record_should_show_touches_label),
                    checked = parametersViewModel.options.shouldShowTaps,
                    onCheckedChange = { parametersViewModel.setShouldShowTaps(it) },
                    enabled = enabled,
                    modifier = Modifier,
                )
            }
            RecordDetailsOptions(parametersViewModel, drawableLoaderViewModel, enabled)
            Text(
                text =
                    stringResource(
                        if (!enabled) R.string.screenrecord_settings_while_recording
                        else if (
                            targetViewModel.currentTarget
                                is RecordDetailsTargetItemViewModel.SingleApp
                        )
                            R.string.screenrecord_permission_dialog_warning_single_app
                        else R.string.screenrecord_permission_dialog_warning_entire_screen
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
internal fun RichSwitch(
    icon: State<IconModel?>,
    label: String,
    checked: Boolean,
    onCheckedChange: (isChecked: Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    SettingsRow(
        modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    ) {
        LoadingIcon(icon = icon.value, modifier = Modifier.size(40.dp).padding(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 8.dp).weight(1f).basicMarquee(),
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun AppSelectorButton(
    appLabel: String?,
    viewModel: DrawableLoaderViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsRow(modifier.clickable(onClick = onClick)) {
        LoadingIcon(
            icon =
                loadIcon(
                        viewModel = viewModel,
                        resId = R.drawable.ic_apps_expressive,
                        contentDescription = null,
                    )
                    .value,
            modifier = Modifier.size(40.dp).padding(8.dp),
        )
        Column(modifier = Modifier.padding(horizontal = 8.dp).weight(1f)) {
            Text(
                text = stringResource(R.string.screen_record_single_app_hint),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                modifier = Modifier.basicMarquee(),
            )
            AnimatedVisibility(visible = !appLabel.isNullOrEmpty()) {
                Text(
                    text = appLabel ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.basicMarquee(),
                )
            }
        }

        LoadingIcon(
            icon =
                loadIcon(
                        viewModel = viewModel,
                        resId = R.drawable.ic_chevron_forward_expressive,
                        contentDescription = null,
                    )
                    .value,
            modifier = Modifier.padding(12.dp).size(16.dp),
        )
    }
}

@Composable
private fun SettingsRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .fillMaxWidth(),
        content = content,
    )
}
