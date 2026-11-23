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

package com.android.systemui.qs.panels.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.media.controls.shared.MediaSeekBarStyle
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R

@Composable
internal fun LunarisOneUiMediaPlayer(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    hasMultipleSessions: Boolean,
    lockscreen: Boolean = false,
) {
    val title =
        session?.title?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.lunaris_qs_media_not_playing)
    val subtitle = session?.subtitle.orEmpty()
    val metadata = if (subtitle.isBlank()) title else "$title · $subtitle"

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalPadding = (maxWidth * 0.06f).coerceIn(6.dp, 12.dp)
        val seekBarWidth = maxWidth - horizontalPadding * 2 + 4.dp
        val metadataPadding = (maxWidth * 0.07f).coerceIn(6.dp, 14.dp)
        val preferredTopPadding = (maxHeight * 0.04f).coerceIn(4.dp, 10.dp)
        val preferredBottomPadding = if (hasMultipleSessions) 16.dp else 10.dp
        val metadataHeight = with(LocalDensity.current) { 20.sp.toDp().coerceAtLeast(20.dp) }
        val configuredSeekBarStyle by viewModel.seekBarStyle.collectAsStateWithLifecycle()
        val seekBarStyle = if (lockscreen) configuredSeekBarStyle else MediaSeekBarStyle.GLOWING
        val seekBarHeight =
            mediaSeekBarHeight(seekBarStyle, dense = true) +
                if (lockscreen) with(LocalDensity.current) { 12.sp.toDp() } else 0.dp
        val useCompactSpacing =
            maxHeight <
                preferredTopPadding +
                    preferredBottomPadding +
                    metadataHeight +
                    32.dp +
                    seekBarHeight +
                    48.dp
        val topPadding = if (useCompactSpacing) 0.dp else preferredTopPadding
        val bottomPadding =
            if (useCompactSpacing) {
                if (hasMultipleSessions) 12.dp else 4.dp
            } else preferredBottomPadding
        val actionSize =
            minOf(48.dp, (maxWidth - horizontalPadding * 2 - 8.dp) / 3).coerceAtLeast(0.dp)

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        top = topPadding,
                        bottom = bottomPadding,
                    )
        ) {
            NotificationMediaOutput(
                session = session,
                viewModel = viewModel,
                colors = colors,
                interactive = interactive,
                modifier = Modifier.padding(horizontal = metadataPadding),
                showDeviceIcon = true,
                minHeight = if (useCompactSpacing) 24.dp else 32.dp,
            )
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = metadataPadding)
                        .heightIn(min = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaAppIcon(session = session, size = 14.dp, tint = colors.foreground)
                AnimatedMediaText(
                    text = metadata,
                    color = colors.foreground,
                    style =
                        MaterialTheme.typography.titleSmall.copy(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        ),
                    modifier = Modifier.weight(1f),
                )
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                MediaSeekBar(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    dense = true,
                    interactive = interactive,
                    modifier = Modifier.requiredWidth(seekBarWidth),
                    styleOverride = if (lockscreen) null else MediaSeekBarStyle.GLOWING,
                    showTimestamps = lockscreen,
                    timestampsBelow = lockscreen,
                )
            }
            CompactMediaActions(
                session = session,
                viewModel = viewModel,
                foreground = colors.foreground,
                interactive = interactive,
                buttonBackground = colors.foreground.copy(alpha = 0.12f),
                playPauseSize = actionSize,
                playPauseIconSize = 26.dp,
            )
        }
    }
}
