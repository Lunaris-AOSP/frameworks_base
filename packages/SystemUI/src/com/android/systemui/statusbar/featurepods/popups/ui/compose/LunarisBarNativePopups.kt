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

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.StopReason
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.alarm.shared.model.AlarmPopupModel
import com.android.systemui.statusbar.featurepods.flashlight.shared.model.FlashlightPopupModel
import com.android.systemui.statusbar.featurepods.livescore.shared.model.LiveScoreChipModel
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.screenrecord.shared.model.ScreenRecordPopupModel
import com.android.systemui.statusbar.featurepods.stopwatch.shared.model.StopwatchPopupModel

@Composable
internal fun LunarisBarAlarmPopup(model: AlarmPopupModel) {
    LunarisBarCard {
        LunarisBarCardHeader(model.title, model.dayText, glyph = Icons.Default.Alarm)
        LunarisBarCardValue(model.fullTimeText)
        PopupActionChips(
            buildList {
                addAll(model.actions)
                model.onOpen?.let {
                    add(
                        PopupActionModel(
                            stringResource(R.string.dynamic_island_open_clock_action),
                            it,
                        )
                    )
                }
            },
            LocalLunarisBarColors.current.accent,
        )
    }
}

@Composable
internal fun LunarisBarStopwatchPopup(model: StopwatchPopupModel) {
    LunarisBarCard {
        LunarisBarCardHeader(model.title, model.subtitle, model.icon, Icons.Default.Timer)
        LunarisBarCardValue(rememberStopwatchText(model))
        PopupActionChips(
            buildList {
                addAll(model.actions)
                model.onOpen?.let {
                    add(
                        PopupActionModel(
                            stringResource(R.string.dynamic_island_open_clock_action),
                            it,
                        )
                    )
                }
            },
            LocalLunarisBarColors.current.accent,
        )
    }
}

@Composable
internal fun LunarisBarScreenRecordPopup(model: ScreenRecordPopupModel) {
    LunarisBarCard {
        LunarisBarCardHeader(
            stringResource(R.string.screenrecord_ongoing_screen_only),
            when (model) {
                is ScreenRecordPopupModel.Starting ->
                    stringResource(
                        R.string.dynamic_island_screen_record_countdown,
                        model.secondsUntilStarted,
                    )
                is ScreenRecordPopupModel.Recording ->
                    stringResource(R.string.dynamic_island_screen_record_active)
            },
            glyph = Icons.Default.Videocam,
        )
        when (model) {
            is ScreenRecordPopupModel.Starting ->
                LunarisBarCardValue(model.secondsUntilStarted.toString())
            is ScreenRecordPopupModel.Recording -> {
                LunarisBarCardValue(rememberElapsedDurationText(model.startElapsedRealtimeMs))
                PopupActionChips(
                    listOf(
                        PopupActionModel(
                            stringResource(R.string.screenrecord_stop_label),
                            model.stopRecording,
                            emphasized = true,
                        )
                    ),
                    LocalLunarisBarColors.current.accent,
                )
            }
        }
    }
}

@Composable
internal fun LunarisBarLiveScorePopup(model: LiveScoreChipModel) {
    LunarisBarCard {
        LunarisBarCardHeader(
            model.title ?: model.appName,
            model.subtitle,
            model.icon,
            Icons.Default.SportsScore,
            model.appName,
        )
        LunarisBarCardValue(model.score)
        model.onOpen?.let {
            PopupActionChips(
                listOf(PopupActionModel(stringResource(R.string.dynamic_island_event_open), it)),
                LocalLunarisBarColors.current.accent,
            )
        }
    }
}

@Composable
internal fun LunarisBarFlashlightPopup(model: FlashlightPopupModel) {
    val settings = LocalLunarisBarState.current.settings
    val colors = LocalLunarisBarColors.current
    val view = LocalView.current
    LunarisBarCard {
        LunarisBarCardHeader(
            stringResource(R.string.quick_settings_flashlight_label),
            stringResource(R.string.dynamic_island_flashlight_enabled),
            glyph = Icons.Default.FlashlightOn,
        )
        val level = model.level
        val max = model.maxLevel
        if (level != null && max != null && max > 1) {
            var dragging by remember(max) { mutableStateOf(false) }
            var value by remember(max) { mutableIntStateOf(level.coerceIn(1, max)) }
            val latest by rememberUpdatedState(model)
            LaunchedEffect(level, dragging) { if (!dragging) value = level.coerceIn(1, max) }
            DisposableEffect(max) { onDispose { if (dragging) latest.setLevel(value) } }
            LunarisBarCardValue("${value * 100 / max}%")
            Slider(
                value = value.toFloat(),
                valueRange = 1f..max.toFloat(),
                steps = (max - 2).coerceAtLeast(0),
                onValueChange = {
                    dragging = true
                    val next = it.toInt().coerceIn(1, max)
                    if (next != value && settings.isHapticsEnabled)
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    value = next
                    model.setLevelTemporary(value)
                },
                onValueChangeFinished = {
                    model.setLevel(value)
                    dragging = false
                },
                colors =
                    SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor =
                            rememberLunarisBarPalette(
                                    settings.integer("progress_palette", 5, 0, 20),
                                    lunarisBarPartColors(
                                        "progress",
                                        colors.accent to colors.content,
                                    ),
                                )
                                .first,
                        inactiveTrackColor =
                            rememberLunarisBarPalette(
                                    settings.integer("track_palette", 2, 0, 20),
                                    lunarisBarPartColors("track", colors.surface to colors.content),
                                )
                                .first,
                    ),
            )
        }
        PopupActionChips(
            listOf(
                PopupActionModel(
                    stringResource(R.string.flashlight_dialog_turn_off),
                    model.turnOff,
                    emphasized = true,
                )
            ),
            colors.accent,
        )
    }
}

@Composable
internal fun LunarisBarPrivacyPopup(model: PopupChipModel.Shown) {
    val context = LocalContext.current
    val sharing = model.chipId == PopupChipId.ShareScreenPrivacyIndicator
    LunarisBarCard {
        LunarisBarCardHeader(
            model.contentDescription
                ?: stringResource(
                    if (sharing) R.string.lunaris_bar_screen_sharing
                    else R.string.lunaris_bar_privacy
                ),
            icon = model.icons.firstOrNull()?.icon,
            glyph = if (sharing) Icons.Default.ScreenShare else Icons.Default.PrivacyTip,
        )
        PopupActionChips(
            listOf(
                PopupActionModel(
                    stringResource(
                        if (sharing) R.string.screen_share_privacy_indicator_stop_sharing
                        else R.string.lunaris_bar_privacy_settings
                    ),
                    {
                        if (sharing) {
                            context
                                .getSystemService(MediaProjectionManager::class.java)
                                ?.stopActiveProjection(StopReason.STOP_PRIVACY_CHIP)
                        } else lunarisBarLaunch(context, Intent(Settings.ACTION_PRIVACY_SETTINGS))
                        model.hidePopup()
                    },
                    emphasized = true,
                )
            ),
            LocalLunarisBarColors.current.accent,
        )
    }
}
