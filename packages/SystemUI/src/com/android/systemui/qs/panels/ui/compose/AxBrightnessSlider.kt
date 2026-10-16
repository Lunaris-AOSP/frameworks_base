/*
 * Copyright (C) 2024 The Android Open Source Project
 * Copyright (C) 2025-2026 AxionOS
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

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.brightness.shared.model.GammaBrightness
import com.android.systemui.brightness.ui.compose.ContainerColors
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R

private object AxBrightnessDimensions {
    val SliderBackgroundFrameSize = DpSize(10.dp, 6.dp)
    val SliderBackgroundRoundedCorner = 24.dp
    val SliderTrackRoundedCorner = 12.dp
    val IconSize = DpSize(28.dp, 28.dp)
    val IconPadding = 6.dp
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AxBrightnessSlider(
    gammaValue: Int,
    valueRange: IntRange,
    icon: IconModel,
    label: String,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    autoMode: Boolean = false,
    onIconClick: (suspend () -> Unit)? = null,
    sliderColors: SliderColors = SliderDefaults.colors(),
    isRestricted: Boolean = false,
    onRestrictedClick: () -> Unit = {},
    overriddenByAppState: Boolean = false,
    showToast: () -> Unit = {},
    showAutoBrightnessButton: Boolean = true,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory? = null,
) {
    var value by remember(gammaValue) { mutableIntStateOf(gammaValue) }
    val animatedValue by animateFloatAsState(targetValue = value.toFloat(), label = "AxBrightnessSliderValue")
    val coroutineScope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }

    val currentHapticsViewModel: SliderHapticsViewModel? =
        hapticsViewModelFactory?.let { factory ->
            rememberViewModel(traceName = "AxBrightnessSliderHaptics") {
                factory.create(
                    interactionSource = interactionSource,
                    sliderRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                    orientation = Orientation.Horizontal,
                    sliderHapticFeedbackConfig = SliderHapticFeedbackConfig(),
                    sliderTrackerConfig = SeekableSliderTrackerConfig(),
                )
            }
        }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Slider(
                value = animatedValue,
                onValueChange = { newValue ->
                    value = newValue.toInt()
                    onValueChange(value)
                },
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                colors = sliderColors,
                interactionSource = interactionSource,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (showAutoBrightnessButton && onIconClick != null) {
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                onClick = { coroutineScope.launch { onIconClick() } },
            ) {
                Icon(
                    painter =
                        painterResource(
                            if (autoMode) R.drawable.ic_qs_brightness_auto_on
                            else R.drawable.ic_qs_brightness_auto_off
                        ),
                    contentDescription = stringResource(R.string.accessibility_adaptive_brightness),
                )
            }
        }
    }
}

@Composable
fun AxBrightnessSliderContainer(
    viewModel: BrightnessSliderViewModel,
    modifier: Modifier = Modifier,
    containerColors: ContainerColors,
    showAutoBrightnessButton: Boolean = true,
) {
    val gamma = viewModel.currentBrightness.value
    if (gamma == BrightnessSliderViewModel.initialValue.value) return

    val tileScale = LocalTileScale.current

    Box(
        modifier =
            modifier
                .padding(vertical = AxBrightnessDimensions.SliderBackgroundFrameSize.height * tileScale)
                .fillMaxWidth()
                .sysuiResTag("ax_brightness_slider")
    ) {
        val coroutineScope = rememberCoroutineScope()
        val context = LocalContext.current
        val percentage =
            if (viewModel.maxBrightness.value > viewModel.minBrightness.value) {
                ((gamma - viewModel.minBrightness.value).toFloat() /
                    (viewModel.maxBrightness.value - viewModel.minBrightness.value).toFloat()) * 100f
            } else {
                0f
            }
        val iconRes = BrightnessSliderViewModel.getIconForPercentage(percentage)
        val icon = IconModel.Resource(iconRes, null)

        AxBrightnessSlider(
            gammaValue = gamma,
            valueRange = viewModel.minBrightness.value..viewModel.maxBrightness.value,
            label = stringResource(R.string.accessibility_brightness),
            icon = icon,
            onValueChange = { value ->
                coroutineScope.launch { viewModel.onDrag(Drag.Dragging(GammaBrightness(value))) }
            },
            onValueChangeFinished = {
                coroutineScope.launch { viewModel.onDrag(Drag.Stopped(GammaBrightness(gamma))) }
            },
            autoMode = viewModel.autoMode,
            onIconClick = { viewModel.onIconClick() },
            showToast = {
                viewModel.showToast(context, R.string.quick_settings_brightness_unable_adjust_msg)
            },
            showAutoBrightnessButton = showAutoBrightnessButton,
            hapticsViewModelFactory = viewModel.hapticsViewModelFactory,
        )
    }
}
