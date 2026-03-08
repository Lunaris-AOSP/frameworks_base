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

package com.android.systemui.axion.volume.ui.composable.lunaris

import android.content.res.Configuration
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.axion.volume.domain.model.AxionStreamInfo
import com.android.systemui.axion.volume.domain.model.AxionVolumeDialogState
import com.android.systemui.axion.volume.domain.model.VolumeSliderItem
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel
import kotlinx.coroutines.delay

@Composable
fun LunarisVolumeDialogContent(viewModel: AxionVolumeDialogViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isVisible = uiState.isVisible
    val isLeft = uiState.isLeftSide
    val isExpanded = uiState.isExpanded

    var animateIn by remember { mutableStateOf(false) }
    LaunchedEffect(isVisible) { animateIn = isVisible }

    val visibilityProgress by animateFloatAsState(
        targetValue = if (animateIn && isVisible) 1f else 0f,
        animationSpec = tween(LunarisAnimDuration, easing = FastOutSlowInEasing),
        label = "lunaris_visibility"
    )

    val overscrollOffset by viewModel.overscrollOffset.collectAsStateWithLifecycle()
    val animatedOverscroll by animateFloatAsState(
        targetValue = overscrollOffset,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "lunaris_overscroll"
    )

    val view = LocalView.current
    LaunchedEffect(Unit) {
        viewModel.volumeKeyHapticTrigger.collect {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    val slideOffset = with(LocalDensity.current) { 56.dp.toPx() }
    val slideDirection = if (isLeft) -1 else 1

    var showCollapsed by remember { mutableStateOf(!isExpanded) }
    var showExpanded by remember { mutableStateOf(isExpanded) }

    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            showCollapsed = false
            delay(LunarisAnimDuration.toLong())
            showExpanded = true
        } else {
            showExpanded = false
            delay(LunarisAnimDuration.toLong())
            showCollapsed = true
        }
    }

    Box(
        modifier = Modifier.graphicsLayer {
            alpha = visibilityProgress
            translationX = if (isLeft) -slideOffset * (1f - visibilityProgress)
                           else slideOffset * (1f - visibilityProgress)
            translationY = animatedOverscroll
        },
        contentAlignment = if (isLeft) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        AnimatedVisibility(
            visible = showCollapsed,
            enter = slideInHorizontally(animationSpec = tween(LunarisAnimDuration), initialOffsetX = { slideDirection * it }) + fadeIn(tween(LunarisAnimDuration)),
            exit = slideOutHorizontally(animationSpec = tween(LunarisAnimDuration), targetOffsetX  = { slideDirection * it }) + fadeOut(tween(LunarisAnimDuration))
        ) {
            LunarisCollapsedPanel(viewModel)
        }

        AnimatedVisibility(
            visible = showExpanded,
            enter = slideInHorizontally(animationSpec = tween(LunarisAnimDuration), initialOffsetX = { slideDirection * it }) + fadeIn(tween(LunarisAnimDuration)),
            exit = slideOutHorizontally(animationSpec = tween(LunarisAnimDuration), targetOffsetX  = { slideDirection * it }) + fadeOut(tween(LunarisAnimDuration))
        ) {
            LunarisExpandedPanel(viewModel)
        }
    }
}

@Composable
private fun LunarisCollapsedPanel(viewModel: AxionVolumeDialogViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dialogState = uiState.dialogState
    val activeStream = dialogState.activeStream

    val streamModel = dialogState.volumeStreams.find { it.streamType == activeStream }
        ?: dialogState.volumeStreams.find { it.streamType == AudioManager.STREAM_MUSIC }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(LunarisPanelWidth)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceBright)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            LunarisCollapsedRingerButton(
                ringerMode = dialogState.ringerMode,
                onClick = {
                    viewModel.rescheduleTimeout()
                    viewModel.toggleExpanded()
                },
                size = LunarisPanelWidth
            )
        }

        if (streamModel != null) {
            Box(
                modifier = Modifier
                    .width(LunarisPanelWidth)
                    .wrapContentHeight()
                    .clip(RoundedCornerShape(LunarisPanelCornerRadius))
                    .background(MaterialTheme.colorScheme.surfaceBright),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(vertical = LunarisPanelPaddingV),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LunarisSliderColumn(
                        stream = streamModel,
                        viewModel = viewModel,
                        sliderHeight = LunarisSliderHeight,
                        sliderWidth = LunarisSliderWidth,
                        iconSize = LunarisIconSize,
                        showPercentage = true,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(LunarisPanelWidth)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceBright)
                .padding(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    viewModel.rescheduleTimeout()
                    viewModel.toggleExpanded()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Expand",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun LunarisExpandedPanel(viewModel: AxionVolumeDialogViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dialogState = uiState.dialogState
    val isLeftSide = uiState.isLeftSide

    val totalSliderCount = dialogState.volumeStreams.size + dialogState.appVolumes.size
    val targetPanelWidth = computeLunarisPanelWidth(totalSliderCount)
    val panelWidth by animateDpAsState(
        targetValue = targetPanelWidth,
        animationSpec = tween(LunarisAnimDuration, easing = FastOutSlowInEasing),
        label = "lunaris_panel_width"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(panelWidth)
                .clip(RoundedCornerShape(LunarisExpandedCornerRadius))
                .background(MaterialTheme.colorScheme.surfaceBright)
                .padding(horizontal = LunarisPanelPaddingH, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            LunarisRingerControl(
                viewModel = viewModel,
                ringerMode = dialogState.ringerMode,
                supportedModes = dialogState.supportedRingerModes,
            )
        }

        Box(
            modifier = Modifier
                .width(panelWidth)
                .clip(RoundedCornerShape(LunarisExpandedCornerRadius))
                .background(MaterialTheme.colorScheme.surfaceBright)
        ) {
            LunarisExpandedSlidersSection(
                viewModel = viewModel,
                dialogState = dialogState,
                isLeftSide = isLeftSide,
                modifier = Modifier.padding(horizontal = LunarisPanelPaddingH, vertical = LunarisPanelPaddingV)
            )
        }

        Box(
            modifier = Modifier
                .width(panelWidth)
                .clip(RoundedCornerShape(LunarisExpandedCornerRadius))
                .background(MaterialTheme.colorScheme.surfaceBright)
        ) {
            LunarisExpandedHeader(
                viewModel = viewModel,
                dialogState = dialogState,
                modifier = Modifier.padding(horizontal = LunarisPanelPaddingH, vertical = 10.dp)
            )
        }

        Box(
            modifier = Modifier
                .width(panelWidth)
                .clip(RoundedCornerShape(LunarisExpandedCornerRadius))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    viewModel.rescheduleTimeout()
                    viewModel.toggleExpanded()
                }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Collapse",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Close",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LunarisExpandedHeader(
    viewModel: AxionVolumeDialogViewModel,
    dialogState: AxionVolumeDialogState,
    modifier: Modifier = Modifier
) {
    val captionsEnabled = dialogState.captionsEnabled
    val captionsAvailable = dialogState.captionsAvailable

    val activeAppLabel = dialogState.activeAppPackageName?.let { pkg ->
        dialogState.appVolumes.find { it.packageName == pkg }?.label
    }
    val title = activeAppLabel ?: run {
        val info = AxionStreamInfo.fromStreamType(dialogState.activeStream)
        if (info != AxionStreamInfo.UNKNOWN) info.label else "Volume"
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        FilledTonalIconButton(
            onClick = { viewModel.rescheduleTimeout(); viewModel.onSeeMoreClick() },
            modifier = Modifier.size(LunarisBottomButtonSize),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = lerp(MaterialTheme.colorScheme.surfaceVariant, Color.Black, 0.2f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(imageVector = Icons.Filled.Settings, contentDescription = "Sound settings", modifier = Modifier.size(LunarisSmallIconSize))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).basicMarquee()
        )

        if (captionsAvailable) {
            FilledTonalIconButton(
                onClick = { viewModel.rescheduleTimeout(); viewModel.toggleCaptions() },
                modifier = Modifier.size(LunarisBottomButtonSize),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (captionsEnabled) MaterialTheme.colorScheme.primaryContainer
                                     else lerp(MaterialTheme.colorScheme.surfaceVariant, Color.Black, 0.2f),
                    contentColor = if (captionsEnabled) MaterialTheme.colorScheme.onPrimaryContainer
                                     else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(
                    imageVector = if (captionsEnabled) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionDisabled,
                    contentDescription = if (captionsEnabled) "Captions on" else "Captions off",
                    modifier = Modifier.size(LunarisSmallIconSize)
                )
            }
        } else {
            Spacer(Modifier.size(LunarisBottomButtonSize))
        }
    }
}

@Composable
private fun LunarisExpandedSlidersSection(
    viewModel: AxionVolumeDialogViewModel,
    dialogState: AxionVolumeDialogState,
    isLeftSide: Boolean,
    modifier: Modifier = Modifier
) {
    val streamOrder = listOf(
        AudioManager.STREAM_VOICE_CALL,
        AudioManager.STREAM_BLUETOOTH_SCO,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_ALARM
    )

    val musicStream = dialogState.volumeStreams.find { it.streamType == AudioManager.STREAM_MUSIC }
    val otherStreams = dialogState.volumeStreams
        .filter { it.streamType != AudioManager.STREAM_MUSIC }
        .sortedBy { streamOrder.indexOf(it.streamType) }
    val appItems = dialogState.appVolumes.map { VolumeSliderItem.AppVolume(it) }

    val sliderItems = buildList {
        if (isLeftSide) {
            musicStream?.let { add(VolumeSliderItem.Stream(it)) }
            otherStreams.forEach { add(VolumeSliderItem.Stream(it)) }
            addAll(appItems)
        } else {
            addAll(appItems)
            otherStreams.reversed().forEach { add(VolumeSliderItem.Stream(it)) }
            musicStream?.let { add(VolumeSliderItem.Stream(it)) }
        }
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (isLeftSide) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        LunarisVolumeSlidersRow(
            viewModel = viewModel,
            sliderItems = sliderItems,
            sliderHeight = LunarisSliderHeight,
        )
    }
}

private fun computeLunarisPanelWidth(totalSliders: Int): Dp {
    val count   = totalSliders.coerceAtLeast(1)
    val padding = LunarisPanelPaddingH * 2
    val sliders = LunarisSliderWidth * count
    val spacing = LunarisSliderSpacing * (count - 1)
    return sliders + spacing + padding + 8.dp
}
