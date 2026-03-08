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

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.systemui.axion.volume.domain.model.AxionAppVolumeModel
import com.android.systemui.axion.volume.domain.model.AxionVolumeStreamModel
import com.android.systemui.axion.volume.domain.model.VolumeSliderItem
import com.android.systemui.axion.volume.ui.viewmodel.AxionVolumeDialogViewModel
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.lifecycle.rememberViewModel

@Composable
fun LunarisVolumeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    isMuted: Boolean,
    modifier: Modifier = Modifier,
    viewModel: AxionVolumeDialogViewModel,
    streamType: Int? = null,
    icon: @Composable () -> Unit,
    onIconClick: () -> Unit,
    sliderHeight: Dp = LunarisSliderHeight,
    sliderWidth: Dp = LunarisSliderWidth,
    iconSize: Dp = LunarisIconSize,
    showPercentage: Boolean = false,
) {
    var sliderValue by remember { mutableFloatStateOf(value) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(value) { if (!isDragging) sliderValue = value }

    val interactionSource = remember { MutableInteractionSource() }
    val hapticsViewModel = key(viewModel) {
        rememberViewModel(traceName = "LunarisVolumeSliderHaptics") {
            viewModel.sliderHapticsViewModelFactory.create(
                interactionSource,
                0f..1f,
                Orientation.Vertical,
                SliderHapticFeedbackConfig(),
                SeekableSliderTrackerConfig()
            )
        }
    }

    val animatedValue by animateFloatAsState(
        targetValue = sliderValue,
        animationSpec = if (isDragging) snap() else tween(60, easing = FastOutSlowInEasing),
        label = "lunaris_slider_value"
    )

    val iconAlpha by animateFloatAsState(
        targetValue = if (isMuted) 0.4f else 1f,
        label = "lunaris_icon_alpha"
    )

    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = lerp(MaterialTheme.colorScheme.surfaceVariant, Color.Black, 0.2f)

    Column(
        modifier = modifier.width(sliderWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showPercentage) {
            Text(
                text = "${(sliderValue * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }

        Box(
            modifier = Modifier.height(sliderHeight).width(sliderWidth),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .height(sliderHeight)
                    .width(sliderWidth)
                    .clip(RoundedCornerShape(percent = LunarisTrackCornerPercent))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset ->
                                viewModel.isInteracting = true
                                sliderValue = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                                onValueChange(sliderValue)
                                viewModel.isInteracting = false
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                streamType?.let { viewModel.setActiveStream(it) }
                                sliderValue = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                                onValueChange(sliderValue)
                                hapticsViewModel.onValueChange(sliderValue)
                                viewModel.isInteracting = true
                            },
                            onDragEnd = {
                                isDragging = false
                                hapticsViewModel.onValueChangeEnded()
                                viewModel.isInteracting = false
                                viewModel.setOverscrollOffset(0f)
                            },
                            onDragCancel = {
                                isDragging = false
                                hapticsViewModel.onValueChangeEnded()
                                viewModel.isInteracting = false
                                viewModel.setOverscrollOffset(0f)
                            },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                val raw = 1f - (change.position.y / size.height)
                                when {
                                    raw < 0f -> {
                                        sliderValue = 0f
                                        viewModel.setOverscrollOffset((-raw * 30f).coerceAtMost(10f))
                                    }
                                    raw > 1f -> {
                                        sliderValue = 1f
                                        viewModel.setOverscrollOffset((-(raw - 1f) * 30f).coerceAtLeast(-10f))
                                    }
                                    else -> {
                                        sliderValue = raw
                                        viewModel.setOverscrollOffset(0f)
                                    }
                                }
                                hapticsViewModel.addVelocityDataPoint(sliderValue)
                                hapticsViewModel.onValueChange(sliderValue)
                                onValueChange(sliderValue)
                            }
                        )
                    }
                    .drawBehind {
                        val h = size.height
                        val w = size.width
                        val r = w / 2f
                        drawRoundRect(color = surfaceVariant, size = Size(w, h), cornerRadius = CornerRadius(r))
                        val minFill = r * 2f
                        val fillH = minFill + (h - minFill) * animatedValue
                        drawRoundRect(color = primary, topLeft = Offset(0f, h - fillH), size = Size(w, fillH), cornerRadius = CornerRadius(r))
                    }
            )
        }

        Box(
            modifier = Modifier
                .size(iconSize + 8.dp)
                .clip(CircleShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onIconClick)
                .graphicsLayer { alpha = iconAlpha },
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
    }
}

@Composable
fun LunarisSliderColumn(
    stream: AxionVolumeStreamModel,
    viewModel: AxionVolumeDialogViewModel,
    sliderHeight: Dp = LunarisSliderHeight,
    sliderWidth: Dp = LunarisSliderWidth,
    iconSize: Dp = LunarisIconSize,
    showPercentage: Boolean = false,
) {
    val muted = stream.isMuted
    LunarisVolumeSlider(
        value = if (muted) 0f else stream.level,
        onValueChange = { viewModel.setVolume(stream.streamType, it) },
        isMuted = muted,
        viewModel = viewModel,
        streamType = stream.streamType,
        sliderHeight = sliderHeight,
        sliderWidth = sliderWidth,
        iconSize = iconSize,
        showPercentage = showPercentage,
        icon = {
            Icon(
                imageVector = if (muted) stream.mutedIcon else stream.icon,
                contentDescription = stream.streamInfo.label,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(iconSize)
            )
        },
        onIconClick = { viewModel.toggleMute(stream.streamType) }
    )
}

@Composable
fun LunarisAppVolumeSlider(
    appVolume: AxionAppVolumeModel,
    viewModel: AxionVolumeDialogViewModel,
    sliderHeight: Dp = LunarisSliderHeight,
    sliderWidth: Dp = LunarisSliderWidth,
    iconSize: Dp = LunarisIconSize,
    showPercentage: Boolean = false,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val appInfo = remember(appVolume.packageName) {
        try { pm.getApplicationInfo(appVolume.packageName, 0) } catch (e: Exception) { null }
    }
    val icon = remember(appInfo) { appInfo?.loadIcon(pm) }
    val label = remember(appInfo) { appInfo?.loadLabel(pm)?.toString() ?: appVolume.packageName }
    val isMutedOrZero = appVolume.isMuted || appVolume.volume == 0f

    LunarisVolumeSlider(
        value = if (appVolume.isMuted) 0f else appVolume.volume,
        onValueChange = { viewModel.setAppVolume(appVolume.packageName, it) },
        isMuted = appVolume.isMuted,
        viewModel = viewModel,
        sliderHeight = sliderHeight,
        sliderWidth = sliderWidth,
        iconSize = iconSize,
        showPercentage = showPercentage,
        icon = {
            if (icon != null) {
                Image(
                    bitmap = icon.toBitmap().asImageBitmap(),
                    contentDescription = label,
                    colorFilter = if (isMutedOrZero) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                    modifier = Modifier.size(iconSize).clip(CircleShape)
                )
            } else {
                Icon(imageVector = Icons.Filled.Android, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(iconSize))
            }
        },
        onIconClick = { viewModel.setAppMute(appVolume.packageName, !appVolume.isMuted) }
    )
}

@Composable
fun LunarisVolumeSlidersRow(
    viewModel: AxionVolumeDialogViewModel,
    sliderItems: List<VolumeSliderItem>,
    sliderHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LunarisSliderSpacing),
        verticalAlignment = Alignment.Bottom
    ) {
        sliderItems.forEach { item ->
            when (item) {
                is VolumeSliderItem.Stream -> key(item.model.streamType) {
                    LunarisSliderColumn(stream = item.model, viewModel = viewModel, sliderHeight = sliderHeight, sliderWidth = LunarisSliderWidth, iconSize = LunarisIconSize, showPercentage = true)
                }
                is VolumeSliderItem.AppVolume -> key(item.model.packageName) {
                    LunarisAppVolumeSlider(appVolume = item.model, viewModel = viewModel, sliderHeight = sliderHeight, sliderWidth = LunarisSliderWidth, iconSize = LunarisIconSize, showPercentage = true)
                }
            }
        }
    }
}