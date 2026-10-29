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

package com.android.systemui.axion.volume.ui.composable.moto

import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaptionDisabled
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.axion.blur.AxBlurSurface
import com.android.axion.blur.AxBlurSurfaceDefaults
import com.android.systemui.axion.volume.domain.model.AxionRingerMode
import com.android.systemui.axion.volume.domain.model.AxionVolumeDialogState
import com.android.systemui.axion.volume.domain.model.AxionVolumeStreamModel
import com.android.systemui.axion.volume.ui.composable.SliderIconSize
import com.android.systemui.axion.volume.ui.composable.VolumeButtonsSize
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel
import com.android.systemui.axion.volume.ui.viewmodel.VisibilityState
import com.android.systemui.res.R

internal val MotoPanelWidth = VolumeButtonsSize
private const val MotoAnimationDuration = 200

@Composable
fun MotoVolumeDialogContent(
    viewModel: AxionVolumeDialogViewModel,
    onExpandedLayoutChanged: (Boolean) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val voiceCallStream by viewModel.voiceCallStream.collectAsStateWithLifecycle()
    val isVisible = uiState.isVisible
    val isExpanded = uiState.isExpanded
    var displayedExpanded by remember { mutableStateOf(isExpanded) }
    val visibility = remember { Animatable(0f) }
    val onLayoutChanged by rememberUpdatedState(onExpandedLayoutChanged)
    LaunchedEffect(isVisible, isExpanded) {
        if (!isVisible) {
            visibility.animateTo(0f, tween(MotoAnimationDuration))
        } else {
            if (displayedExpanded != isExpanded) {
                visibility.animateTo(0f, tween(90))
                displayedExpanded = isExpanded
            }
            onLayoutChanged(displayedExpanded)
            withFrameNanos { }
            visibility.animateTo(1f, tween(MotoAnimationDuration, easing = FastOutSlowInEasing))
        }
    }
    val slideOffset = with(LocalDensity.current) { MotoPanelWidth.toPx() }
    val view = LocalView.current
    val interactionOwner = remember(viewModel) { Any() }
    val hapticsEnabled by viewModel.isHapticEnabled.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, hapticsEnabled) {
        viewModel.volumeKeyHapticTrigger.collect {
            if (hapticsEnabled) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Box(
        modifier = Modifier.pointerInput(viewModel, interactionOwner) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                viewModel.setInteractionActive(interactionOwner, true)
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                } finally {
                    viewModel.setInteractionActive(interactionOwner, false)
                    viewModel.rescheduleTimeout()
                }
            }
        }.graphicsLayer {
            alpha = visibility.value
            translationX = if (displayedExpanded) 0f else {
                slideOffset * (1f - visibility.value) * if (uiState.isLeftSide) -1f else 1f
            }
            scaleX = if (displayedExpanded) 0.98f + 0.02f * visibility.value else 1f
            scaleY = scaleX
        },
        contentAlignment = Alignment.Center,
    ) {
        if (displayedExpanded) {
            MotoExpandedPanel(viewModel, uiState.dialogState, voiceCallStream)
        } else {
            MotoCollapsedPanel(viewModel, uiState.dialogState)
        }
    }
}

@Composable
private fun MotoCollapsedPanel(
    viewModel: AxionVolumeDialogViewModel,
    state: AxionVolumeDialogState,
) {
    val activeApp = state.appVolumes.find { it.packageName == state.activeAppPackageName }
    val activeStream = state.volumeStreams.find { it.streamType == state.activeStream }
        ?: state.volumeStreams.find { it.streamType == AudioManager.STREAM_MUSIC }
    val availableHeight = (LocalConfiguration.current.screenHeightDp.dp - 24.dp).coerceAtLeast(64.dp)
    val captionsHeight = if (state.captionsAvailable) MotoPanelWidth + 8.dp else 0.dp
    val sliderHeight = minOf(
        136.dp,
        (availableHeight - 177.dp - captionsHeight).coerceAtLeast(64.dp),
    )

    Column(
        modifier = Modifier.heightIn(max = availableHeight).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AxBlurSurface(
            modifier = Modifier.width(MotoPanelWidth),
            shape = RoundedCornerShape(MotoPanelWidth / 2),
            cornerRadius = MotoPanelWidth / 2,
            surfaceColor = AxBlurSurfaceDefaults.surfaceColor(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MotoRingerButton(viewModel, state.ringerMode)
                Spacer(Modifier.height(8.dp))
                if (activeApp != null) {
                    key(activeApp.packageName) {
                        MotoAppVolumeSlider(
                            activeApp, viewModel, sliderHeight, MotoPanelWidth,
                            iconSize = SliderIconSize,
                        )
                    }
                } else if (activeStream != null) {
                    key(activeStream.streamType) {
                        MotoStreamSlider(
                            activeStream, viewModel, sliderHeight, MotoPanelWidth,
                            iconSize = SliderIconSize,
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.width(24.dp).padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
                )
                IconButton(
                    onClick = {
                        viewModel.rescheduleTimeout()
                        viewModel.toggleExpanded()
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreHoriz,
                        contentDescription = stringResource(R.string.volume_panel_expand_sliders),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (state.captionsAvailable) {
            AxBlurSurface(
                modifier = Modifier.size(MotoPanelWidth),
                shape = CircleShape,
                cornerRadius = MotoPanelWidth / 2,
                surfaceColor = AxBlurSurfaceDefaults.surfaceColor(),
            ) {
                IconButton(
                    onClick = {
                        viewModel.rescheduleTimeout()
                        viewModel.toggleCaptions()
                    },
                    modifier = Modifier.fillMaxSize().semantics {
                        toggleableState = ToggleableState(state.captionsEnabled)
                    },
                ) {
                    Icon(
                        imageVector = if (state.captionsEnabled) Icons.Outlined.ClosedCaption
                            else Icons.Outlined.ClosedCaptionDisabled,
                        contentDescription = stringResource(R.string.volume_panel_captioning_title),
                        modifier = Modifier.size(24.dp),
                        tint = if (state.captionsEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun MotoRingerButton(
    viewModel: AxionVolumeDialogViewModel,
    ringerMode: AxionRingerMode,
) {
    val description = stringResource(R.string.volume_ringer_change)
    val modeDescription = stringResource(
        when (ringerMode) {
            AxionRingerMode.NORMAL -> R.string.volume_ringer_status_normal
            AxionRingerMode.VIBRATE -> R.string.volume_ringer_status_vibrate
            AxionRingerMode.SILENT -> R.string.volume_ringer_status_silent
        }
    )
    IconButton(
        onClick = {
            viewModel.rescheduleTimeout()
            viewModel.cycleRingerMode()
        },
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = description
            stateDescription = modeDescription
        },
    ) {
        Icon(
            imageVector = when (ringerMode) {
                AxionRingerMode.NORMAL -> Icons.AutoMirrored.Outlined.VolumeUp
                AxionRingerMode.VIBRATE -> Icons.Outlined.Vibration
                AxionRingerMode.SILENT -> Icons.AutoMirrored.Outlined.VolumeOff
            },
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MotoExpandedPanel(
    viewModel: AxionVolumeDialogViewModel,
    state: AxionVolumeDialogState,
    voiceCallStream: AxionVolumeStreamModel?,
) {
    val streams = motoSystemVolumeStreams(state, voiceCallStream)
    val appVolumes = state.appVolumes.filter { it.isActive }
    val configuration = LocalConfiguration.current
    val basePanelWidth = minOf(420.dp, configuration.screenWidthDp.dp - 48.dp)
    val availableWidth = configuration.screenWidthDp.dp - 24.dp
    val panelWidth by animateDpAsState(
        targetValue = minOf(basePanelWidth + 48.dp * appVolumes.size, availableWidth),
        animationSpec = tween(MotoAnimationDuration, easing = FastOutSlowInEasing),
        label = "moto_panel_width",
    )
    val availableHeight = (configuration.screenHeightDp.dp - 24.dp).coerceAtLeast(64.dp)
    val sliderHeight = minOf(168.dp, (configuration.screenHeightDp.dp - 172.dp).coerceAtLeast(64.dp))
    val cornerRadius = 28.dp

    AxBlurSurface(
        modifier = Modifier.width(panelWidth).heightIn(max = availableHeight),
        shape = RoundedCornerShape(cornerRadius),
        cornerRadius = cornerRadius,
        surfaceColor = AxBlurSurfaceDefaults.surfaceColor(),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.sound_settings),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val sliderCount = (streams.size + appVolumes.size).coerceAtLeast(1)
                val iconSize = minOf(26.dp, (maxWidth / sliderCount - 8.dp).coerceAtLeast(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    streams.forEach { stream ->
                        key(stream.streamType) {
                            MotoStreamSlider(
                                stream, viewModel, sliderHeight, 48.dp,
                                modifier = Modifier.weight(1f),
                                iconSize = iconSize,
                            )
                        }
                    }
                    appVolumes.forEach { app ->
                        key(app.packageName) {
                            MotoAppVolumeSlider(
                                app, viewModel, sliderHeight, 48.dp,
                                modifier = Modifier.weight(1f),
                                iconSize = iconSize,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { viewModel.visibilityState = VisibilityState.DISMISSED },
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.quick_settings_done))
                }
                IconButton(onClick = viewModel::onSoundSettingsClick) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.accessibility_volume_settings),
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
