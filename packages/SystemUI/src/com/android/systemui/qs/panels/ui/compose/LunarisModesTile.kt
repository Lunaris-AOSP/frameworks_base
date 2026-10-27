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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.settingslib.notification.modes.ZenMode
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberTileHaptic
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ui.dialog.viewmodel.ModeTileViewModel

@Composable
internal fun LunarisModesTile(
    modes: List<ModeTileViewModel>,
    span: AxQsSpan,
    enabled: Boolean,
    onModeClick: (ModeTileViewModel) -> Unit,
    onModeLongClick: (ModeTileViewModel) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dnd = modes.first { it.id == ZenMode.MANUAL_DND_MODE_ID }
    val shortcuts = modes.filter { it.id != dnd.id }.take(2)
    val gap = 8.dp * LocalTileScale.current
    val shape = axQsTileShape(span)
    val interactionSource = remember { MutableInteractionSource() }
    val view = LocalView.current
    val hapticEnabled = rememberTileHaptic()
    Column(
        modifier =
            modifier.lunarisTilePressAnimation(interactionSource, enabled)
                .clip(shape)
                .background(AxTileDefaults.backgroundColor())
                .lunarisQsCosTileBorder(shape)
                .borderOnFocus(MaterialTheme.colorScheme.secondary, shape.topEnd)
                .combinedClickable(
                    interactionSource = interactionSource,
                    enabled = enabled,
                    onClick = {
                        if (hapticEnabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        onClick()
                    },
                    onLongClick = {
                        if (hapticEnabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        onLongClick()
                    },
                    hapticFeedbackEnabled = false,
                )
                .padding(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        LunarisModeButton(
            mode = dnd,
            showLabel = true,
            enabled = enabled,
            onClick = { onModeClick(dnd) },
            onLongClick = { onModeLongClick(dnd) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        if (shortcuts.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                shortcuts.forEach { mode ->
                    LunarisModeButton(
                        mode = mode,
                        showLabel = false,
                        enabled = enabled,
                        onClick = { onModeClick(mode) },
                        onLongClick = { onModeLongClick(mode) },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LunarisModeButton(
    mode: ModeTileViewModel,
    showLabel: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gradient = rememberLunarisQsGradient()
    val brush = gradient.tileBrush().takeIf { mode.enabled }
    val strokeStyle = LocalLunarisQsStrokeStyle.current
    val background by
        animateColorAsState(
            if (mode.enabled && !strokeStyle) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.primaryContainer,
            label = "LunarisModeBackground",
        )
    val foreground by
        animateColorAsState(
            if (brush != null) gradient.contentColor
            else if (mode.enabled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onPrimaryContainer,
            label = "LunarisModeForeground",
        )
    val shape = RoundedCornerShape(20.dp)
    val backgroundModifier =
        if (strokeStyle) {
            val fillAlpha = animateFloatAsState(
                if (mode.enabled) 1f else 0f,
                label = "LunarisModeStrokeFill",
            )
            Modifier.background(background).lunarisQsInsetBackground(
                brush = gradient.tileBrush() ?: SolidColor(MaterialTheme.colorScheme.primary),
                shape = shape,
                insetWidth = LunarisQsTileStrokeWidth * LocalTileScale.current,
                alpha = { fillAlpha.value },
            )
        } else {
            Modifier.background(brush ?: SolidColor(background))
        }
    val interactionSource = remember(mode.id) { MutableInteractionSource() }
    val view = LocalView.current
    val hapticEnabled = rememberTileHaptic()
    Box(
        modifier =
            modifier.lunarisTilePressAnimation(interactionSource, enabled)
                .clip(shape)
                .then(backgroundModifier)
                .borderOnFocus(MaterialTheme.colorScheme.secondary, shape.topEnd)
                .combinedClickable(
                    interactionSource = interactionSource,
                    enabled = enabled,
                    role = Role.Switch,
                    onClick = {
                        if (hapticEnabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        onClick()
                    },
                    onLongClick = {
                        if (hapticEnabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        onLongClick()
                    },
                    onLongClickLabel = mode.onLongClickLabel,
                    hapticFeedbackEnabled = false,
                )
                .semantics {
                    contentDescription = mode.text
                    stateDescription = mode.stateDescription
                    toggleableState = ToggleableState(mode.enabled)
                }
                .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier.clearAndSetSemantics {},
        ) {
            Icon(icon = mode.icon, tint = foreground, modifier = Modifier.size(24.dp))
            if (showLabel) {
                Text(
                    text = stringResource(R.string.ax_qs_dnd_short_label),
                    color = foreground,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}
