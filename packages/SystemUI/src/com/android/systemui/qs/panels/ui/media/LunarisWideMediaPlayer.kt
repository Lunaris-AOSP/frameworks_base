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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.shared.model.MediaCardActionButtonLayout
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R
import java.io.File

@Composable
internal fun LunarisWideMediaPlayer(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    interactive: Boolean,
    hasMultipleSessions: Boolean,
    motionArt: File? = null,
    motionArtPlaying: Boolean = false,
) {
    val foreground = MaterialTheme.colorScheme.onSurface
    val colors =
        AxMediaColors(
            primary = foreground,
            onPrimary = MaterialTheme.colorScheme.surface,
            background = MaterialTheme.colorScheme.surface,
            foreground = foreground,
        )
    val title =
        session?.title?.takeIf { it.isNotBlank() }
            ?: stringResource(
                if (session == null) R.string.lunaris_qs_media_no_history
                else R.string.lunaris_qs_media_not_playing
            )
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = if (hasMultipleSessions) 16.dp else 12.dp,
            )
    ) {
        val gap = 16.dp
        val artworkSize =
            minOf(maxHeight, maxWidth * 0.42f, (maxWidth - gap - 144.dp).coerceAtLeast(0.dp))
        val metadataHeight = with(LocalDensity.current) { 36.sp.toDp() }
        val showSubtitle = maxHeight >= metadataHeight + 96.dp
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumCoverArtwork(
                artwork = session?.background,
                motionArt = motionArt,
                motionArtPlaying = motionArtPlaying,
                cornerRadius = 20.dp,
                modifier = Modifier.size(artworkSize),
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = foreground,
                            style =
                                MaterialTheme.typography.titleSmall.copy(
                                    fontSize = 14.sp,
                                    lineHeight = 18.sp,
                                ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        session?.subtitle?.takeIf { showSubtitle && it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                    ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    AlbumCoverMediaOutput(
                        session = session,
                        viewModel = viewModel,
                        interactive = interactive,
                        background = foreground.copy(alpha = 0.08f),
                        modifier = Modifier.size(40.dp),
                    )
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val actionSize = minOf(48.dp, maxHeight, maxWidth / 3)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (
                                session?.actionButtonLayout ==
                                    MediaCardActionButtonLayout.SecondaryActionsOnly
                            ) {
                                session.additionalActions.take(3).forEach { action ->
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        MediaAction(
                                            action = action,
                                            viewModel = viewModel,
                                            width = actionSize,
                                            iconSize = minOf(24.dp, actionSize),
                                            tint = foreground,
                                            interactive = interactive,
                                        )
                                    }
                                }
                            } else {
                                val actions =
                                    listOf(
                                        session?.leftAction,
                                        session?.playPauseAction,
                                        session?.rightAction,
                                    )
                                val icons =
                                    listOf(
                                        Icons.Filled.SkipPrevious,
                                        playPauseIcon(session),
                                        Icons.Filled.SkipNext,
                                    )
                                val descriptions =
                                    listOf(
                                        R.string.controls_media_button_prev,
                                        playPauseDescription(session),
                                        R.string.controls_media_button_next,
                                    )
                                actions.forEachIndexed { index, action ->
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        CoreMediaAction(
                                            action = action,
                                            imageVector = icons[index],
                                            descriptionRes = descriptions[index],
                                            viewModel = viewModel,
                                            width = actionSize,
                                            iconSize =
                                                minOf(if (index == 1) 32.dp else 24.dp, actionSize),
                                            tint = foreground,
                                            interactive = interactive,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                MediaSeekBar(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    dense = true,
                    interactive = interactive,
                    showTimestamps = true,
                    timestampsBelow = true,
                )
            }
        }
    }
}
