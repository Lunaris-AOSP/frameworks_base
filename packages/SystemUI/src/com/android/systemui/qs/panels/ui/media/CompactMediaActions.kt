/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.shared.model.MediaCardActionButtonLayout
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.media.remedia.ui.compose.LocalMediaAnimationsEnabled
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R

@Composable
internal fun CompactMediaActions(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    foreground: Color,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    buttonBackground: Color = Color.Transparent,
    playPauseBackground: Color = buttonBackground,
    playPauseSize: Dp = 48.dp,
    playPauseIconSize: Dp = 28.dp,
    animatePlayPause: Boolean = true,
) {
    val secondaryOnly =
        session?.actionButtonLayout == MediaCardActionButtonLayout.SecondaryActionsOnly
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = maxOf(48.dp, playPauseSize))) {
            val showNavigation = maxWidth >= 96.dp + maxOf(48.dp, playPauseSize)
            val actions =
                when {
                    secondaryOnly ->
                        session
                            ?.additionalActions
                            .orEmpty()
                            .take((maxWidth / 48.dp).toInt().coerceIn(0, 3))
                    showNavigation ->
                        listOf(session?.leftAction, session?.playPauseAction, session?.rightAction)
                    else -> listOf(session?.playPauseAction)
                }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEachIndexed { index, action ->
                    val primary = !secondaryOnly && (!showNavigation || index == 1)
                    val size = if (primary) playPauseSize else 48.dp
                    Box(
                        modifier = Modifier.size(maxOf(48.dp, size)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (secondaryOnly && action != null) {
                            MediaAction(
                                action = action,
                                viewModel = viewModel,
                                width = size,
                                iconSize = 24.dp,
                                tint = foreground,
                                background = buttonBackground,
                                interactive = interactive,
                            )
                        } else {
                            CoreMediaAction(
                                action = action,
                                imageVector =
                                    when {
                                        primary -> playPauseIcon(session)
                                        index == 0 -> Icons.Filled.SkipPrevious
                                        else -> Icons.Filled.SkipNext
                                    },
                                descriptionRes =
                                    when {
                                        primary -> playPauseDescription(session)
                                        index == 0 -> R.string.controls_media_button_prev
                                        else -> R.string.controls_media_button_next
                                    },
                                animatedIconRes =
                                    if (
                                        primary &&
                                            animatePlayPause &&
                                            LocalMediaAnimationsEnabled.current
                                    )
                                        R.drawable.ic_media_play_button
                                    else null,
                                animatedIconAtEnd = session?.state == MediaSessionState.Playing,
                                buffering =
                                    primary && session?.state == MediaSessionState.Buffering,
                                viewModel = viewModel,
                                width = size,
                                iconSize = if (primary) playPauseIconSize else 24.dp,
                                tint = foreground,
                                background = if (primary) playPauseBackground else buttonBackground,
                                interactive = interactive,
                            )
                        }
                    }
                }
            }
        }
    }
}
