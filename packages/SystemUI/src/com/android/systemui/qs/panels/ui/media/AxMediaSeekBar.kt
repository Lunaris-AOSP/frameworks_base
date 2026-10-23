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

package com.android.systemui.qs.panels.ui.media

import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.media.controls.ui.compose.AndroidMediaSeekBar
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R

@Composable
internal fun MediaSeekBar(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    dense: Boolean,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    val height = if (dense) 20.dp else 32.dp
    val isShadeInteracting by viewModel.isShadeInteracting.collectAsStateWithLifecycle()
    val useWaveform by
        viewModel.useWaveformSeekBar.collectAsStateWithLifecycle(initialValue = false)
    val progress = session?.let(viewModel::progress) ?: 0f
    val seekDescription =
        session
            ?.let {
                stringResource(
                    R.string.controls_media_seekbar_description,
                    DateUtils.formatElapsedTime((progress * it.durationMs).toLong() / 1000L),
                    DateUtils.formatElapsedTime(it.durationMs / 1000L),
                )
            }
            .orEmpty()

    key(session?.key) {
        AndroidMediaSeekBar(
            progress = progress,
            onScrubChange = { value -> session?.let { viewModel.onScrubChange(it, value) } },
            onScrubFinished = { delta -> session?.let { viewModel.onScrubFinished(it, delta) } },
            useWaveform = useWaveform,
            isPlaying = session?.state == MediaSessionState.Playing && !isShadeInteracting,
            enabled = interactive && session?.canBeScrubbed == true,
            contentDescription = seekDescription,
            color = colors.primary,
            backgroundColor = colors.foreground.copy(alpha = 0.3f),
            modifier = modifier.fillMaxWidth().height(height),
        )
    }
}
