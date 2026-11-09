/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.Icon as StatusBarIcon
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupActionRole
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CallPopup(
    model: PopupContentModel.SystemEvent,
    modifier: Modifier = Modifier,
    onDismissNotification: () -> Unit = {},
) {
    val status = model.prominentText ?: stringResource(R.string.dynamic_island_event_call)
    val callStatus = model.callStartTimeMs?.let { rememberCallDurationText(it) } ?: status
    val subtitle =
        listOfNotNull(
                callStatus,
                model.text.takeIf {
                    it.isNotBlank() && it != model.title && it != status && it != callStatus
                },
            )
            .joinToString(" · ")
    val settings = LocalLunarisBarState.current.settings
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val scope = rememberCoroutineScope()

    fun checkSpeaker(): Boolean =
        audioManager?.let {
            it.isSpeakerphoneOn ||
                it.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: false

    fun checkMute(): Boolean = audioManager?.isMicrophoneMute ?: false

    val speakerState = remember(audioManager) { CallAudioToggleState() }
    val muteState = remember(audioManager) { CallAudioToggleState() }

    DisposableEffect(context, audioManager) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED,
                        AudioManager.ACTION_HEADSET_PLUG ->
                            speakerState.refresh(scope) { checkSpeaker() }
                        AudioManager.ACTION_MICROPHONE_MUTE_CHANGED ->
                            muteState.refresh(scope) { checkMute() }
                    }
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED)
                addAction(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED)
                addAction(AudioManager.ACTION_HEADSET_PLUG)
            }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)

        val deviceListener =
            AudioManager.OnCommunicationDeviceChangedListener { device ->
                speakerState.update(device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            }
        audioManager?.addOnCommunicationDeviceChangedListener(context.mainExecutor, deviceListener)
        // Re-read after registration so an update during composition is not missed.
        speakerState.refresh(scope) { checkSpeaker() }
        muteState.refresh(scope) { checkMute() }

        onDispose {
            speakerState.cancelRefresh()
            muteState.cancelRefresh()
            context.unregisterReceiver(receiver)
            audioManager?.removeOnCommunicationDeviceChangedListener(deviceListener)
        }
    }

    val actions =
        model.actions.map { action ->
            // Notification actions have no standard semantic action for speaker or microphone mute.
            val isSpeaker =
                action.role == PopupActionRole.Default &&
                    action.label.contains("speaker", ignoreCase = true)
            val isMute =
                action.role == PopupActionRole.Default &&
                    action.label.contains("mute", ignoreCase = true)
            if (
                action.role == PopupActionRole.AnswerCall ||
                    action.role == PopupActionRole.EndCall ||
                    action.role == PopupActionRole.OpenApp
            ) {
                action.copy(
                    onClick = {
                        action.onClick()
                        onDismissNotification()
                    }
                )
            } else if (!isSpeaker && !isMute) {
                action
            } else {
                val state = if (isSpeaker) speakerState else muteState
                action.copy(
                    emphasized = state.isActive,
                    onClick = {
                        state.update(!state.isActive)
                        action.onClick()
                        // A rejected toggle may not send an audio-state change notification.
                        state.refresh(scope, delayMillis = 1_000L) {
                            if (isSpeaker) checkSpeaker() else checkMute()
                        }
                    },
                )
            }
        }

    LunarisBarCard(modifier) {
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.padding(end = if (model.appIcon != null) 36.dp else 0.dp)) {
                LunarisBarCardHeader(
                    title = model.title,
                    subtitle = subtitle,
                    icon = model.icon,
                    glyph = Icons.Default.Person,
                    appName = model.appName,
                    compact = true,
                )
            }
            model.appIcon?.let {
                StatusBarIcon(
                    it,
                    modifier =
                        Modifier.align(Alignment.TopEnd)
                            .size(28.dp)
                            .clip(
                                lunarisBarIconShape(settings.integer("card_icon_shape", 2, 0, 31))
                            ),
                    tint = Color.Unspecified,
                )
            }
        }
        PopupActionChips(
            actions.sortedBy {
                when (it.role) {
                    PopupActionRole.EndCall -> 0
                    PopupActionRole.AnswerCall -> 1
                    else -> 2
                }
            },
            LocalLunarisBarColors.current.accent,
        )
    }
}

/** Keeps each control's pending refresh independent and discards superseded reads. */
private class CallAudioToggleState {
    var isActive by mutableStateOf(false)
        private set

    private var refreshJob: Job? = null

    fun update(active: Boolean) {
        cancelRefresh()
        isActive = active
    }

    fun refresh(scope: CoroutineScope, delayMillis: Long = 0L, readState: () -> Boolean) {
        cancelRefresh()
        refreshJob =
            scope.launch {
                delay(delayMillis)
                // getCommunicationDevice can wait for route changes in the audio service.
                isActive = withContext(Dispatchers.IO) { readState() }
            }
    }

    fun cancelRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }
}
