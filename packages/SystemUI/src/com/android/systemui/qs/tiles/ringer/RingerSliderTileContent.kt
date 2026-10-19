/*
 * Copyright (C) 2025-2026 AxionOS
 * Copyright (C) 2026 Lunaris AOSP
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

package com.android.systemui.qs.tiles.ringer

import android.media.AudioManager
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoNotDisturb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.ui.compose.AxTileDefaults
import com.android.systemui.qs.panels.ui.compose.LocalLunarisQsStrokeStyle
import com.android.systemui.qs.panels.ui.compose.LocalTileScale
import com.android.systemui.qs.panels.ui.compose.LunarisQsSliderStrokeWidth
import com.android.systemui.qs.panels.ui.compose.LunarisQsTileStrokeWidth
import com.android.systemui.qs.panels.ui.compose.animateAxQsTileShape
import com.android.systemui.qs.panels.ui.compose.axQsTileIconSize
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberQSTileAnimationStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberTileHaptic
import com.android.systemui.qs.panels.ui.compose.infinitegrid.tileToggleAnimation
import com.android.systemui.qs.panels.ui.compose.lunarisQsInsetBackground
import com.android.systemui.qs.panels.ui.compose.lunarisTilePressAnimation
import com.android.systemui.qs.panels.ui.compose.rememberLunarisQsGradient
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RingerSliderTileContent(
    modifier: Modifier = Modifier.fillMaxSize(),
    interactable: Boolean = true,
    compact: Boolean = false,
    vertical: Boolean = false,
    shape: Shape = CircleShape,
    viewModel: RingerSliderViewModel = LocalRingerSliderViewModel.current,
) {
    val initialMode = remember(viewModel) { viewModel.currentMode }
    val currentRingerMode by
        viewModel.ringerModeChanges.collectAsStateWithLifecycle(initialValue = initialMode)
    val isDndEnabled by viewModel.isDndEnabled.collectAsStateWithLifecycle()
    val isDndAvailable by viewModel.isDndAvailable.collectAsStateWithLifecycle()
    val availableModes = viewModel.availableModes
    val currentIndex = viewModel.targetPosition(currentRingerMode).toInt()
    val maxModeIndex = availableModes.lastIndex
    val displayedMode = currentRingerMode.takeUnless { isDndEnabled }
    var previousMode by remember(viewModel) { mutableStateOf(displayedMode) }
    var toggleAnimState by remember(viewModel) { mutableIntStateOf(STATE_INACTIVE) }
    val animationStyle = rememberQSTileAnimationStyle()
    val hapticEnabled = rememberTileHaptic()
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val interactionSource = remember(viewModel) { MutableInteractionSource() }

    LaunchedEffect(displayedMode) {
        if (previousMode != displayedMode) {
            previousMode = displayedMode
            toggleAnimState = if (toggleAnimState == STATE_ACTIVE) STATE_INACTIVE else STATE_ACTIVE
        }
    }

    fun selectMode(mode: Int): Boolean {
        if (!interactable || viewModel.isDndEnabled.value || !viewModel.setRingerMode(mode)) {
            return false
        }
        if (hapticEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (mode == AudioManager.RINGER_MODE_VIBRATE) {
                coroutineScope.launch {
                    delay(80L)
                    if (viewModel.currentMode == mode) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
            }
        }
        return true
    }

    fun toggleDnd(): Boolean {
        if (!interactable || !viewModel.toggleDnd()) return false
        if (hapticEnabled) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return true
    }

    fun cycleMode(): Boolean {
        val index = viewModel.targetPosition(viewModel.currentMode).toInt()
        return selectMode(availableModes[(index + 1) % availableModes.size].mode)
    }

    val onSelectMode by rememberUpdatedState(::selectMode)
    val onToggleDnd by rememberUpdatedState(::toggleDnd)
    val controlLabel = stringResource(R.string.quick_settings_sound_label)
    val changeModeLabel = stringResource(R.string.lunaris_qs_slider_ringer_mode)
    val modeLabels =
        availableModes.map { option ->
            stringResource(
                when (option.mode) {
                    AudioManager.RINGER_MODE_NORMAL -> R.string.quick_settings_sound_ring
                    AudioManager.RINGER_MODE_VIBRATE -> R.string.quick_settings_sound_vibrate
                    else -> R.string.quick_settings_sound_silent
                }
            )
        }
    val currentDescription =
        if (isDndEnabled) stringResource(R.string.dnd_is_on) else modeLabels[currentIndex]
    val dndActionLabel =
        stringResource(
            if (isDndEnabled) R.string.lunaris_qs_ringer_dnd_off
            else R.string.lunaris_qs_ringer_dnd_on
        )
    val interactionModifier =
        if (interactable) {
            val gestureModifier =
                if (compact) {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        enabled = !isDndEnabled || isDndAvailable,
                        onClick = { cycleMode() },
                        onLongClick = if (isDndAvailable) ({ toggleDnd() }) else null,
                        hapticFeedbackEnabled = false,
                    )
                } else {
                    Modifier.pointerInput(viewModel, vertical, isRtl, isDndAvailable) {
                        detectTapGestures(
                            onTap = { offset ->
                                val length = if (vertical) size.height else size.width
                                if (length > 0) {
                                    val position =
                                        when {
                                            vertical -> offset.y
                                            isRtl -> size.width - offset.x
                                            else -> offset.x
                                        }
                                    val index =
                                        (position / length * availableModes.size)
                                            .toInt()
                                            .coerceIn(0, maxModeIndex)
                                    onSelectMode(availableModes[index].mode)
                                }
                            },
                            onLongPress = if (isDndAvailable) ({ onToggleDnd() }) else null,
                        )
                    }
                }
            Modifier.clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = controlLabel
                    stateDescription = currentDescription
                    if (!isDndEnabled) {
                        onClick(changeModeLabel) { cycleMode() }
                        customActions =
                            availableModes.mapIndexedNotNull { index, option ->
                                if (index == currentIndex) null
                                else
                                    CustomAccessibilityAction(modeLabels[index]) {
                                        selectMode(option.mode)
                                    }
                            }
                    }
                    if (isDndAvailable) {
                        onLongClick(dndActionLabel) { toggleDnd() }
                    } else if (isDndEnabled) {
                        disabled()
                    }
                }
                .then(gestureModifier)
        } else {
            Modifier
        }

    val gradient = rememberLunarisQsGradient()
    if (compact) {
        val active =
            interactable && (isDndEnabled || currentRingerMode == AudioManager.RINGER_MODE_NORMAL)
        val tileState = if (active) STATE_ACTIVE else STATE_INACTIVE
        val tileShape = animateAxQsTileShape(AxQsSpan.TileDefault, tileState)
        val activeBrush = gradient.tileBrush()
        val strokeStyle = LocalLunarisQsStrokeStyle.current
        val scale = LocalTileScale.current
        val background by
            animateColorAsState(
                targetValue =
                    if (active && !strokeStyle) MaterialTheme.colorScheme.primary
                    else AxTileDefaults.backgroundColor(),
                label = "RingerTileBackground",
            )
        val foreground by
            animateColorAsState(
                targetValue =
                    if (!active) MaterialTheme.colorScheme.onSurface
                    else if (activeBrush != null) gradient.contentColor
                    else MaterialTheme.colorScheme.onPrimary,
                label = "RingerTileForeground",
            )
        val fillModifier =
            if (strokeStyle) {
                val fillAlpha =
                    animateFloatAsState(
                        targetValue = if (active) 1f else 0f,
                        label = "RingerTileStrokeFill",
                    )
                Modifier.lunarisQsInsetBackground(
                    brush = activeBrush ?: SolidColor(MaterialTheme.colorScheme.primary),
                    shape = tileShape,
                    insetWidth = LunarisQsTileStrokeWidth * scale,
                    alpha = { fillAlpha.value },
                )
            } else if (active && activeBrush != null) {
                Modifier.background(activeBrush)
            } else {
                Modifier
            }
        BoxWithConstraints(
            modifier =
                modifier
                    .lunarisTilePressAnimation(
                        interactionSource,
                        interactable && (!isDndEnabled || isDndAvailable),
                    )
                    .clip(tileShape)
                    .background(background)
                    .then(fillModifier)
                    .borderOnFocus(MaterialTheme.colorScheme.secondary, tileShape.topEnd)
                    .then(interactionModifier),
            contentAlignment = Alignment.Center,
        ) {
            val iconSize =
                if (interactable) axQsTileIconSize(minOf(maxWidth, maxHeight))
                else AxTileDefaults.IconSize
            Icon(
                imageVector =
                    if (isDndEnabled) Icons.Filled.DoNotDisturb
                    else availableModes[currentIndex].icon,
                contentDescription = null,
                tint = foreground,
                modifier =
                    Modifier.tileToggleAnimation(animationStyle, toggleAnimState)
                        .size(iconSize * scale),
            )
        }
        return
    }

    val animatedPosition =
        animateFloatAsState(
            targetValue = if (isDndEnabled) maxModeIndex / 2f else currentIndex.toFloat(),
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 400f),
            label = "RingerPosition",
        )
    val activeBrush = gradient.sliderBrush()
    val strokeWidth = LunarisQsSliderStrokeWidth * LocalTileScale.current
    val activeBg = MaterialTheme.colorScheme.primary
    val activeIcon =
        if (activeBrush != null) gradient.contentColor else MaterialTheme.colorScheme.onPrimary
    val neutralBg = AxTileDefaults.backgroundColor()
    val neutralDot = MaterialTheme.colorScheme.onSurface
    val background by
        animateColorAsState(
            targetValue = if (isDndEnabled) activeBg else neutralBg,
            animationSpec = tween(durationMillis = 200),
            label = "RingerBackground",
        )
    val trackAlignment = if (vertical) Alignment.TopCenter else Alignment.CenterStart

    BoxWithConstraints(
        modifier = modifier.background(background, shape).clip(shape).then(interactionModifier),
        contentAlignment = trackAlignment,
    ) {
        val outerPadding = RINGER_OUTER_PADDING
        val availableHeight = (maxHeight - outerPadding * 2).coerceAtLeast(0.dp)
        val availableWidth = (maxWidth - outerPadding * 2).coerceAtLeast(0.dp)
        val slotSize = minOf(availableHeight, availableWidth)
        val iconSize = (slotSize * 0.42f).coerceIn(minOf(12.dp, slotSize), 28.dp)
        val trackLength = if (vertical) maxHeight else maxWidth
        val travel = (trackLength - outerPadding * 2 - slotSize).coerceAtLeast(0.dp)
        val step = if (maxModeIndex > 0) travel / maxModeIndex else 0.dp
        val minCenter = outerPadding + slotSize / 2

        availableModes.forEachIndexed { index, option ->
            key(option.mode) {
                val dotAlpha =
                    animateFloatAsState(
                        targetValue = if (isDndEnabled || currentIndex == index) 0f else 0.4f,
                        animationSpec = tween(durationMillis = 200),
                        label = "RingerModeDotAlpha",
                    )
                val dotOffset = (minCenter + step * index - RINGER_DOT_SIZE / 2).coerceAtLeast(0.dp)
                Box(
                    modifier =
                        Modifier.align(trackAlignment)
                            .offset(
                                x = if (vertical) 0.dp else dotOffset,
                                y = if (vertical) dotOffset else 0.dp,
                            )
                            .size(RINGER_DOT_SIZE)
                            .graphicsLayer { alpha = dotAlpha.value }
                            .background(neutralDot, CircleShape)
                )
            }
        }

        Box(
            modifier =
                Modifier.align(trackAlignment)
                    .offset {
                        val offset =
                            (outerPadding +
                                    step *
                                        animatedPosition.value.coerceIn(0f, maxModeIndex.toFloat()))
                                .roundToPx()
                        if (vertical) IntOffset(0, offset) else IntOffset(offset, 0)
                    }
                    .tileToggleAnimation(animationStyle, toggleAnimState)
                    .size(slotSize)
                    .lunarisQsInsetBackground(
                        brush = activeBrush ?: SolidColor(activeBg),
                        shape = CircleShape,
                        insetWidth = strokeWidth,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector =
                    if (isDndEnabled) Icons.Filled.DoNotDisturb
                    else availableModes[currentIndex].icon,
                contentDescription = null,
                tint = activeIcon,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

private val RINGER_OUTER_PADDING = 3.dp
private val RINGER_DOT_SIZE = 6.dp
