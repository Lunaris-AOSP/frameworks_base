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
import com.android.systemui.axion.volume.domain.model.AxionVolumeDialogState
import com.android.systemui.axion.volume.domain.model.AxionVolumeStreamModel

internal fun motoSystemVolumeStreams(
    state: AxionVolumeDialogState,
    voiceCallStream: AxionVolumeStreamModel?,
): List<AxionVolumeStreamModel> {
    val streams = (listOfNotNull(voiceCallStream) + state.volumeStreams)
        .filter { it.maxLevel > it.minLevel }
        .associateBy { it.streamType }
    val callStream = if (state.activeStream == AudioManager.STREAM_BLUETOOTH_SCO) {
        streams[AudioManager.STREAM_BLUETOOTH_SCO] ?: streams[AudioManager.STREAM_VOICE_CALL]
    } else {
        streams[AudioManager.STREAM_VOICE_CALL]
    }
    return listOfNotNull(
        streams[AudioManager.STREAM_ALARM],
        streams[AudioManager.STREAM_NOTIFICATION],
        streams[AudioManager.STREAM_RING],
        callStream,
        streams[AudioManager.STREAM_MUSIC],
    )
}
