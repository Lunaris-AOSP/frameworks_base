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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.media.controls.shared.MediaSeekBarStyle
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
    showTimestamps: Boolean = false,
    timestampsBelow: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isShadeInteracting by viewModel.isShadeInteracting.collectAsStateWithLifecycle()
    val seekBarStyle by
        viewModel.seekBarStyle.collectAsStateWithLifecycle(initialValue = MediaSeekBarStyle.DEFAULT)
    val height =
        if (
            seekBarStyle == MediaSeekBarStyle.GLOWING ||
                seekBarStyle == MediaSeekBarStyle.GRADIENT_WAVE ||
                seekBarStyle == MediaSeekBarStyle.COS
        ) 34.dp else if (dense) 20.dp else 32.dp
    val progress = session?.let(viewModel::progress) ?: 0f
    val durationMs = session?.durationMs ?: 0L
    val elapsedSeconds = (progress * durationMs).toLong() / 1000L
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
    val elapsedTime = remember(elapsedSeconds) { DateUtils.formatElapsedTime(elapsedSeconds) }
    val totalTime = remember(totalSeconds) { DateUtils.formatElapsedTime(totalSeconds) }
    val displayElapsedTime = if (timestampsBelow && durationMs <= 0L) "--:--" else elapsedTime
    val displayTotalTime = if (timestampsBelow && durationMs <= 0L) "--:--" else totalTime
    val seekDescription =
        if (session != null) {
            stringResource(R.string.controls_media_seekbar_description, elapsedTime, totalTime)
        } else {
            ""
        }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showTimestamps && !timestampsBelow) MediaTimestamp(elapsedTime, colors)
                key(session?.key) {
                    AndroidMediaSeekBar(
                        progress = progress,
                        durationMs = durationMs,
                        onScrubChange = { value -> session?.let { viewModel.onScrubChange(it, value) } },
                        onScrubFinished = { delta -> session?.let { viewModel.onScrubFinished(it, delta) } },
                        style = seekBarStyle,
                        artwork = (session?.background as? Icon.Loaded)?.drawable,
                        isPlaying = session?.state == MediaSessionState.Playing,
                        animationsAllowed = !isShadeInteracting,
                        enabled = interactive && session?.canBeScrubbed == true,
                        contentDescription = seekDescription,
                        color = colors.primary,
                        backgroundColor = colors.foreground.copy(alpha = 0.3f),
                        modifier = Modifier.weight(1f).height(height),
                    )
                }
                if (showTimestamps && !timestampsBelow) MediaTimestamp(totalTime, colors)
            }
            if (showTimestamps && timestampsBelow) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MediaTimestamp(displayElapsedTime, colors)
                    MediaTimestamp(displayTotalTime, colors)
                }
            }
        }
    }
}

@Composable
private fun MediaTimestamp(text: String, colors: AxMediaColors) {
    Text(
        text = text,
        color = colors.foreground.copy(alpha = 0.65f),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
        maxLines = 1,
        softWrap = false,
    )
}
