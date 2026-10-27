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

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.animation.Expandable as ExpandableContainer
import com.android.compose.animation.rememberExpandableController
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.shared.model.asImageBitmap
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.motionart.FadingLunarisMotionArtVideo
import com.android.systemui.media.remedia.shared.model.MediaCardActionButtonLayout
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R
import java.io.File

@Composable
internal fun LunarisAlbumCoverMediaPlayer(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    interactive: Boolean,
    hasMultipleSessions: Boolean,
    motionArt: File? = null,
    motionArtPlaying: Boolean = false,
) {
    val foreground = MaterialTheme.colorScheme.onSurface
    val title =
        session?.title?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.lunaris_qs_media_not_playing)
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = if (hasMultipleSessions) 14.dp else 6.dp,
                )
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val artworkSize = minOf(48.dp, maxHeight, maxWidth * 0.42f)
            AlbumCoverArtwork(
                artwork = session?.background,
                motionArt = motionArt,
                motionArtPlaying = motionArtPlaying,
                modifier = Modifier.align(Alignment.TopStart).size(artworkSize),
            )
            AlbumCoverMediaOutput(
                session = session,
                viewModel = viewModel,
                interactive = interactive,
                modifier = Modifier.align(Alignment.TopEnd).size(minOf(48.dp, maxHeight)),
            )
        }
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = title,
                color = foreground,
                style =
                    MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            session
                ?.subtitle
                ?.takeIf { it.isNotBlank() }
                ?.let { subtitle ->
                    Text(
                        text = subtitle,
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
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (
                    session?.actionButtonLayout == MediaCardActionButtonLayout.SecondaryActionsOnly
                ) {
                    session.additionalActions.take(3).forEach { action ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            MediaAction(
                                action = action,
                                viewModel = viewModel,
                                width = 48.dp,
                                iconSize = 24.dp,
                                tint = foreground,
                                interactive = interactive,
                            )
                        }
                    }
                } else {
                    val actions =
                        listOf(session?.leftAction, session?.playPauseAction, session?.rightAction)
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
                                width = 48.dp,
                                iconSize = if (index == 1) 28.dp else 24.dp,
                                tint = foreground,
                                interactive = interactive,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AlbumCoverArtwork(
    artwork: IconModel?,
    modifier: Modifier,
    motionArt: File?,
    motionArtPlaying: Boolean,
    cornerRadius: Dp = 8.dp,
) {
    Box(modifier.clip(RoundedCornerShape(cornerRadius))) {
        Crossfade(
            targetState = rememberAxMediaArtwork(artwork).artwork,
            animationSpec = tween(250),
            label = "LunarisAlbumCoverArtwork",
            modifier = Modifier.fillMaxSize(),
        ) { currentArtwork ->
            when (currentArtwork) {
                is IconModel.Loaded ->
                    Image(
                        bitmap = currentArtwork.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                is IconModel.Resource ->
                    Image(
                        painter = painterResource(currentArtwork.resId),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                null ->
                    Box(
                        modifier =
                            Modifier.fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        MaterialIcon(
                            painter = painterResource(R.drawable.ic_music_note),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxSize(0.5f),
                        )
                    }
            }
        }
        FadingLunarisMotionArtVideo(
            file = motionArt,
            crop = true,
            isPlaying = motionArtPlaying,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun AlbumCoverMediaOutput(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    interactive: Boolean,
    modifier: Modifier,
    background: Color = Color.Transparent,
) {
    val description =
        session?.outputDevice?.name?.toString()?.takeUnless { it.isBlank() || it == "null" }
            ?: stringResource(R.string.ax_dynamic_bar_media_output)
    ExpandableContainer(
        controller =
            rememberExpandableController(color = { background }, shape = CircleShape),
        modifier = modifier,
        defaultMinSize = false,
        useModifierBasedImplementation = true,
    ) { expandable ->
        Box(
            modifier =
                Modifier.fillMaxSize().clip(CircleShape).clickable(
                    enabled = interactive && session != null,
                    role = Role.Button,
                ) {
                    session?.let { viewModel.openOutput(it.outputDevice, expandable) }
                },
            contentAlignment = Alignment.Center,
        ) {
            MaterialIcon(
                painter = painterResource(R.drawable.ic_qs_media_output_album_cover),
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}
