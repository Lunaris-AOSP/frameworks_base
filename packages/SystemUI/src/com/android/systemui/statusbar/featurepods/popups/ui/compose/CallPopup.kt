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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupActionRole
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun CallPopup(model: PopupContentModel.SystemEvent, modifier: Modifier = Modifier) {
    val status = model.prominentText ?: stringResource(R.string.dynamic_island_event_call)
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val scope = rememberCoroutineScope()

    fun checkSpeaker(): Boolean =
        audioManager?.let {
            it.isSpeakerphoneOn ||
                it.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: false

    fun checkMute(): Boolean = audioManager?.isMicrophoneMute ?: false

    var isSpeakerOn by remember(audioManager) { mutableStateOf(checkSpeaker()) }
    var isMicrophoneMuted by remember(audioManager) { mutableStateOf(checkMute()) }

    fun refreshAudioState() {
        isSpeakerOn = checkSpeaker()
        isMicrophoneMuted = checkMute()
    }

    DisposableEffect(context, audioManager) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    refreshAudioState()
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
            AudioManager.OnCommunicationDeviceChangedListener { isSpeakerOn = checkSpeaker() }
        audioManager?.addOnCommunicationDeviceChangedListener(context.mainExecutor, deviceListener)
        // Re-read after registration so an update during composition is not missed.
        refreshAudioState()

        onDispose {
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
            if (!isSpeaker && !isMute) {
                action
            } else {
                action.copy(
                    emphasized = if (isSpeaker) isSpeakerOn else isMicrophoneMuted,
                    onClick = {
                        if (isSpeaker) {
                            isSpeakerOn = !isSpeakerOn
                        } else {
                            isMicrophoneMuted = !isMicrophoneMuted
                        }
                        action.onClick()
                        scope.launch {
                            // A rejected toggle may not send an audio-state change notification.
                            delay(1_000L)
                            refreshAudioState()
                        }
                    },
                )
            }
        }

    LunarisBarCard(modifier) {
        LunarisBarCardHeader(
            title = model.title,
            subtitle = model.text.takeIf { it.isNotBlank() && it != model.title } ?: status,
            icon = model.icon,
            glyph = Icons.Default.Person,
            appName = model.appName,
        )
        LunarisBarCardValue(model.callStartTimeMs?.let { rememberCallDurationText(it) } ?: status)
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
