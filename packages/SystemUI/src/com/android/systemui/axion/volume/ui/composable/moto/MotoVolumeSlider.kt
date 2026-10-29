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

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.android.systemui.axion.volume.ui.composable.moto

import android.media.AudioManager
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.BluetoothAudio
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RingVolume
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.axion.volume.domain.model.AxionAppVolumeModel
import com.android.systemui.axion.volume.domain.model.AxionVolumeStreamModel
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.res.R
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigs
import com.android.systemui.volume.ui.compose.slider.AccessibilityParams
import com.android.systemui.volume.ui.compose.slider.Haptics
import com.android.systemui.volume.ui.compose.slider.Slider

@Composable
internal fun MotoStreamSlider(
    stream: AxionVolumeStreamModel,
    viewModel: AxionVolumeDialogViewModel,
    sliderHeight: Dp,
    sliderWidth: Dp,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp,
) {
    val label = stringResource(
        when (stream.streamType) {
            AudioManager.STREAM_ALARM -> com.android.internal.R.string.volume_alarm
            AudioManager.STREAM_NOTIFICATION -> com.android.internal.R.string.volume_icon_description_notification
            AudioManager.STREAM_RING -> com.android.internal.R.string.volume_icon_description_ringer
            AudioManager.STREAM_VOICE_CALL -> com.android.internal.R.string.volume_icon_description_incall
            AudioManager.STREAM_BLUETOOTH_SCO -> com.android.internal.R.string.volume_icon_description_bluetooth
            AudioManager.STREAM_MUSIC -> com.android.internal.R.string.volume_icon_description_media
            else -> R.string.media_output_dialog_accessibility_seekbar
        }
    )
    val iconDescription = stringResource(
        if (stream.isMuted) R.string.volume_panel_hint_unmute else R.string.volume_panel_hint_mute,
        label,
    )
    MotoVolumeSlider(
        value = if (stream.isMuted) 0f else stream.level,
        isMuted = stream.isMuted,
        label = label,
        sliderHeight = sliderHeight,
        sliderWidth = sliderWidth,
        modifier = modifier,
        viewModel = viewModel,
        enabled = stream.maxLevel > stream.minLevel,
        stepDistance = 1f / (stream.maxLevel - stream.minLevel).coerceAtLeast(1),
        onValueChanged = {
            viewModel.setActiveStream(stream.streamType)
            viewModel.setVolume(stream.streamType, it)
        },
        onIconClick = { viewModel.toggleMute(stream.streamType) },
        icon = {
            Icon(
                imageVector = motoStreamIcon(stream),
                contentDescription = iconDescription,
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (stream.isMuted) 0.5f else 1f),
            )
        },
    )
}

private fun motoStreamIcon(stream: AxionVolumeStreamModel): ImageVector = when {
    stream.isRoutedToBluetooth -> Icons.Outlined.BluetoothAudio
    stream.isMuted -> Icons.AutoMirrored.Outlined.VolumeOff
    else -> when (stream.streamType) {
        AudioManager.STREAM_ALARM -> Icons.Outlined.Alarm
        AudioManager.STREAM_NOTIFICATION -> Icons.Outlined.Notifications
        AudioManager.STREAM_RING -> Icons.Outlined.RingVolume
        AudioManager.STREAM_VOICE_CALL -> Icons.Outlined.Call
        AudioManager.STREAM_BLUETOOTH_SCO -> Icons.Outlined.BluetoothAudio
        else -> Icons.Outlined.MusicNote
    }
}

@Composable
internal fun MotoAppVolumeSlider(
    app: AxionAppVolumeModel,
    viewModel: AxionVolumeDialogViewModel,
    sliderHeight: Dp,
    sliderWidth: Dp,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp,
) {
    val context = LocalContext.current
    val icon = remember(app.packageName, context) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap().asImageBitmap()
        }.getOrNull()
    }
    val description = stringResource(
        if (app.isMuted) R.string.volume_panel_hint_unmute else R.string.volume_panel_hint_mute,
        app.label,
    )
    MotoVolumeSlider(
        value = if (app.isMuted) 0f else app.volume,
        isMuted = app.isMuted,
        label = app.label,
        sliderHeight = sliderHeight,
        sliderWidth = sliderWidth,
        modifier = modifier,
        viewModel = viewModel,
        onValueChanged = { viewModel.setAppVolume(app.packageName, it) },
        onIconClick = { viewModel.setAppMute(app.packageName, !app.isMuted) },
        icon = {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = description,
                    modifier = Modifier.size(iconSize).clip(CircleShape),
                    alpha = if (app.isMuted) 0.5f else 1f,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Android,
                    contentDescription = description,
                    modifier = Modifier.size(iconSize),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    )
}

@Composable
private fun MotoVolumeSlider(
    value: Float,
    isMuted: Boolean,
    label: String,
    sliderHeight: Dp,
    sliderWidth: Dp,
    viewModel: AxionVolumeDialogViewModel,
    onValueChanged: (Float) -> Unit,
    onIconClick: () -> Unit,
    icon: @Composable () -> Unit,
    enabled: Boolean = true,
    stepDistance: Float = 0.01f,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isDragging by interactionSource.collectIsDraggedAsState()
    val trackWidth by animateDpAsState(
        targetValue = if (isDragging) 10.dp else 6.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "moto_track_width",
    )
    val hapticsEnabled by viewModel.isHapticEnabled.collectAsStateWithLifecycle()
    val hapticConfigs = remember {
        VolumeHapticsConfigs(SliderHapticFeedbackConfig(), SeekableSliderTrackerConfig())
    }
    val activeColor = MaterialTheme.colorScheme.primary
    val trackColor = Color.Black.copy(alpha = 0.8f)
    Column(
        modifier = modifier.width(sliderWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Slider(
            value = value.coerceIn(0f, 1f),
            valueRange = 0f..1f,
            onValueChanged = {
                viewModel.rescheduleTimeout()
                onValueChanged(it)
            },
            onValueChangeFinished = { viewModel.rescheduleTimeout() },
            isEnabled = enabled,
            accessibilityParams = AccessibilityParams(
                contentDescription = label,
                stateDescription = if (isMuted) stringResource(R.string.volume_panel_hint_muted) else null,
            ),
            stepDistance = stepDistance,
            modifier = Modifier.height(sliderHeight).width(48.dp),
            interactionSource = interactionSource,
            isVertical = true,
            isReverseDirection = true,
            haptics = if (hapticsEnabled) {
                Haptics.Enabled(viewModel.sliderHapticsViewModelFactory, hapticConfigs, Orientation.Vertical)
            } else {
                Haptics.Disabled
            },
            thumb = { _, _ -> Spacer(Modifier.size(0.dp)) },
            track = { state ->
                Canvas(Modifier.width(trackWidth).fillMaxHeight()) {
                    val radius = CornerRadius(size.width / 2)
                    drawRoundRect(trackColor, cornerRadius = radius)
                    val fillHeight = size.height * state.value.coerceIn(0f, 1f)
                    if (fillHeight > 0f) {
                        drawRoundRect(
                            color = activeColor,
                            topLeft = Offset(0f, size.height - fillHeight),
                            size = Size(size.width, fillHeight),
                            cornerRadius = radius,
                        )
                    }
                }
            },
        )
        IconButton(
            onClick = {
                viewModel.rescheduleTimeout()
                onIconClick()
            },
            enabled = enabled,
            modifier = Modifier.size(48.dp),
            content = icon,
        )
    }
}
