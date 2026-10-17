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

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.gesture.gesturesDisabled
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.qs.panels.shared.model.LunarisQsSliderStyle
import com.android.systemui.qs.panels.ui.viewmodel.LocalLunarisFlashlightSliderViewModel
import com.android.systemui.res.R
import java.text.NumberFormat
import kotlin.math.roundToInt

@Composable
internal fun LunarisFlashlightSlider(
    vertical: Boolean,
    sliderStyle: LunarisQsSliderStyle,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val viewModel = LocalLunarisFlashlightSliderViewModel.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    AxQsSliderControl(vertical = vertical, interceptParentScroll = false, modifier = modifier) {
        sliderHeight,
        rotated ->
        val interactionSource = remember { MutableInteractionSource() }
        var dragInteraction by remember { mutableStateOf<DragInteraction.Start?>(null) }
        var dragLevel by remember { mutableStateOf<Int?>(null) }
        val level = dragLevel ?: state.level
        val valueRange = 1f..state.maxLevel.coerceAtLeast(2).toFloat()
        val targetValue =
            if (state.supportsStrength) level.toFloat() else if (state.enabled) 2f else 1f
        val animatedValue by animateFloatAsState(targetValue, label = "LunarisFlashlightStrength")
        val enabled = interactive && state.available
        val label = stringResource(R.string.quick_settings_flashlight_label)
        val toggleLabel =
            stringResource(
                if (state.enabled) R.string.flashlight_strength_turn_off
                else R.string.flashlight_strength_turn_on
            )
        val onOffDescription =
            stringResource(if (state.enabled) R.string.switch_bar_on else R.string.switch_bar_off)
        val description =
            when {
                !state.available -> stringResource(R.string.quick_settings_flashlight_camera_in_use)
                state.supportsStrength ->
                    listOf(
                            onOffDescription,
                            NumberFormat.getPercentInstance()
                                .format(level.toDouble() / state.maxLevel),
                        )
                        .joinToString(", ")
                else -> onOffDescription
            }
        val view = LocalView.current
        val toggle: () -> Unit = {
            if (viewModel.toggle()) {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
        }
        Box(
            Modifier.fillMaxSize().sysuiResTag("lunaris_flashlight_slider").clearAndSetSemantics {
                contentDescription = label
                stateDescription = description
                role = Role.Switch
                toggleableState = if (state.enabled) ToggleableState.On else ToggleableState.Off
                if (state.available && state.supportsStrength) {
                    progressBarRangeInfo =
                        ProgressBarRangeInfo(level.toFloat(), valueRange, state.maxLevel - 2)
                }
                if (enabled) {
                    onClick(label = toggleLabel) {
                        toggle()
                        true
                    }
                    if (state.supportsStrength) {
                        setProgress { target -> viewModel.setLevel(target) }
                    }
                } else {
                    disabled()
                }
            }
        ) {
            AxQsSlider(
                value = if (dragLevel != null) targetValue else animatedValue,
                percentage =
                    if (state.supportsStrength) level * 100f / state.maxLevel
                    else if (state.enabled) 100f else 0f,
                onValueChange = {},
                onValueChangeFinished = null,
                valueRange = valueRange,
                enabled = state.available || !interactive,
                active = state.enabled,
                interactionSource = interactionSource,
                sliderHeight = sliderHeight,
                vertical = rotated,
                sliderStyle = sliderStyle,
                icon = { iconModifier ->
                    Icon(
                        painter =
                            painterResource(
                                if (state.enabled) R.drawable.vd_flashlight_on
                                else R.drawable.vd_flashlight_off
                            ),
                        contentDescription = null,
                        modifier = iconModifier,
                    )
                },
                modifier =
                    Modifier.fillMaxSize()
                        .focusProperties { canFocus = false }
                        .gesturesDisabled()
                        .clearAndSetSemantics {},
            )
            Box(
                Modifier.matchParentSize()
                    .lunarisFlashlightSliderInput(
                        enabled = enabled,
                        canAdjust = state.supportsStrength,
                        value = state.level.toFloat(),
                        valueRange = valueRange,
                        onTap = toggle,
                        onDragStart = {
                            if (viewModel.startAdjusting()) {
                                dragLevel = state.level
                                dragInteraction =
                                    DragInteraction.Start().also { interactionSource.tryEmit(it) }
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                true
                            } else {
                                false
                            }
                        },
                        onValueChange = { value ->
                            val newLevel = value.roundToInt()
                            if (newLevel != dragLevel) {
                                dragLevel = newLevel
                                if (viewModel.setLevel(newLevel.toFloat())) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }
                        },
                        onDragEnd = {
                            dragInteraction?.let {
                                interactionSource.tryEmit(DragInteraction.Stop(it))
                            }
                            dragInteraction = null
                            dragLevel = null
                        },
                    )
            )
        }
    }
}
