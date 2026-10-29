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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.compose.animation.Expandable
import com.android.compose.animation.rememberExpandableController
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.shared.model.MediaCardActionButtonLayout
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R

@Composable
internal fun notificationMediaHeight(): Dp =
    with(LocalDensity.current) {
        nonQsGridMediaHeight +
            (20.sp.toDp() - 20.dp).coerceAtLeast(0.dp) +
            (16.sp.toDp() - 32.dp).coerceAtLeast(0.dp)
    }

@Composable
internal fun NotificationMediaContent(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    hasMultipleSessions: Boolean,
) {
    val title = session?.title?.takeIf { it.isNotBlank() } ?: "No media playing"
    val subtitle = session?.subtitle.orEmpty()
    val metadata = if (subtitle.isBlank()) title else "$title · $subtitle"

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth < 360.dp) 16.dp else 24.dp
        val showSecondarySlots = maxWidth - horizontalPadding * 2 >= 240.dp
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        top = 8.dp,
                        bottom = if (hasMultipleSessions) 12.dp else 8.dp,
                    ),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            NotificationMediaOutput(session, viewModel, colors, interactive)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
            MediaSeekBar(
                session = session,
                viewModel = viewModel,
                colors = colors,
                dense = false,
                interactive = interactive,
                showTimestamps = true,
            )
            NotificationMediaActions(
                session = session,
                viewModel = viewModel,
                colors = colors,
                interactive = interactive,
                showSecondarySlots = showSecondarySlots,
            )
        }
    }
}

@Composable
private fun NotificationMediaOutput(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
) {
    val outputName =
        session?.outputDevice?.name?.toString()?.takeUnless { it.isBlank() || it == "null" }
            ?: stringResource(com.android.settingslib.R.string.media_transfer_this_device_name)
    val outputDescription = stringResource(R.string.media_output_dialog_accessibility_title)
    Expandable(
        controller =
            rememberExpandableController(color = { Color.Transparent }, shape = CircleShape),
        modifier = Modifier.fillMaxWidth(),
        defaultMinSize = false,
        useModifierBasedImplementation = true,
    ) { expandable ->
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .clip(CircleShape)
                    .clickable(
                        enabled = interactive && session != null,
                        role = Role.Button,
                        onClickLabel = outputDescription,
                    ) {
                        session?.let { viewModel.openOutput(it.outputDevice, expandable) }
                    },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = outputName,
                color = colors.foreground,
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_qs_notification_media_output),
                contentDescription = null,
                tint = colors.foreground,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun NotificationMediaActions(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    showSecondarySlots: Boolean,
) {
    val showCoreActions =
        session?.actionButtonLayout != MediaCardActionButtonLayout.SecondaryActionsOnly
    val additionalActions = session?.additionalActions.orEmpty()
    val buttonBackground = colors.foreground.copy(alpha = 0.12f)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCoreActions) {
                if (showSecondarySlots) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        additionalActions.firstOrNull()?.let { action ->
                            MediaAction(
                                action = action,
                                viewModel = viewModel,
                                width = 48.dp,
                                iconSize = 22.dp,
                                tint = colors.foreground,
                                interactive = interactive,
                            )
                        }
                    }
                }
                ExpandedNavigationAction(
                    action = session?.leftAction,
                    placeholderDescription = R.string.controls_media_button_prev,
                    viewModel = viewModel,
                    colors = colors,
                    interactive = interactive,
                    size = 48.dp,
                    iconSize = 24.dp,
                    background = buttonBackground,
                    imageVector = Icons.Filled.SkipPrevious,
                )
                CoreMediaAction(
                    action = session?.playPauseAction,
                    imageVector = playPauseIcon(session),
                    descriptionRes = playPauseDescription(session),
                    animatedIconRes = R.drawable.ic_media_play_button,
                    animatedIconAtEnd = session?.state == MediaSessionState.Playing,
                    viewModel = viewModel,
                    width = 48.dp,
                    iconSize = 26.dp,
                    tint = colors.foreground,
                    background = buttonBackground,
                    interactive = interactive,
                )
                ExpandedNavigationAction(
                    action = session?.rightAction,
                    placeholderDescription = R.string.controls_media_button_next,
                    viewModel = viewModel,
                    colors = colors,
                    interactive = interactive,
                    size = 48.dp,
                    iconSize = 24.dp,
                    background = buttonBackground,
                    imageVector = Icons.Filled.SkipNext,
                )
                if (showSecondarySlots) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        additionalActions.getOrNull(1)?.let { action ->
                            MediaAction(
                                action = action,
                                viewModel = viewModel,
                                width = 48.dp,
                                iconSize = 22.dp,
                                tint = colors.foreground,
                                interactive = interactive,
                            )
                        }
                    }
                }
            } else {
                additionalActions.take(if (showSecondarySlots) 5 else 3).forEach { action ->
                    MediaAction(
                        action = action,
                        viewModel = viewModel,
                        width = 48.dp,
                        iconSize = 24.dp,
                        tint = colors.foreground,
                        interactive = interactive,
                    )
                }
                if (additionalActions.isEmpty()) Spacer(Modifier.size(48.dp))
            }
        }
    }
}
