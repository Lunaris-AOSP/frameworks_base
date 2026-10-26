/*
 * Copyright (C) 2025-2026 AxionOS
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

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.Expandable as ExpandableContainer
import com.android.compose.animation.rememberExpandableController
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.shared.model.MediaCardActionButtonLayout
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.composefragment.cosStatusGlow
import com.android.systemui.qs.panels.shared.model.AxMediaSurface
import com.android.systemui.qs.panels.shared.model.AxQsControl
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.shared.model.LunarisQsMediaStyle
import com.android.systemui.qs.panels.ui.compose.AxQsControlCornerRadius
import com.android.systemui.qs.panels.ui.compose.AxTileDefaults
import com.android.systemui.qs.panels.ui.compose.MaterialMediaExpandedPopup
import com.android.systemui.qs.panels.ui.compose.SharedMediaState
import com.android.systemui.qs.panels.ui.compose.axQsControlShape
import com.android.systemui.qs.panels.ui.compose.rememberMediaState
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R

private enum class AxMediaLayout {
    OneRow,
    Box,
    AlbumCover,
    Compact,
    Expanded,
}

@Composable
fun AxMediaPanel(
    viewModel: AxMediaViewModel,
    span: AxQsSpan,
    modifier: Modifier = Modifier,
    mediaViewModelFactory: MediaViewModel.Factory? = null,
    showPlaceholder: Boolean = false,
    interactive: Boolean = true,
    allowGuts: Boolean = true,
    surface: AxMediaSurface = AxMediaSurface.CONTROL,
    motionArtVisible: Boolean = true,
    motionArtPlaying: Boolean = true,
) {
    val showOnLockscreen by viewModel.showOnLockscreen.collectAsStateWithLifecycle()
    if (surface == AxMediaSurface.LOCKSCREEN && !showOnLockscreen) return

    val isShadeExpanded by viewModel.isShadeExpanded.collectAsStateWithLifecycle()
    val keyguardState by viewModel.currentKeyguardState.collectAsStateWithLifecycle()
    val isOnLockscreen = keyguardState != KeyguardState.GONE && showOnLockscreen

    val isPanelVisible =
        when (surface) {
            AxMediaSurface.LOCKSCREEN -> isOnLockscreen
            else -> isShadeExpanded
        }
    if (!isPanelVisible) {
        Box(modifier = modifier)
        return
    }

    val sessions = viewModel.visibleSessions(surface)
    val currentSession = viewModel.currentSession(surface)
    val lastMediaPackage by viewModel.lastMediaPackage.collectAsStateWithLifecycle()
    val expandedMediaState = rememberMediaState()
    val hasVisibleMedia = sessions.isNotEmpty() || showPlaceholder

    LaunchedEffect(viewModel, currentSession?.key) {
        viewModel.synchronizeSession(currentSession?.key)
    }

    if (!hasVisibleMedia) return

    val factory = mediaViewModelFactory
    if (factory == null || (sessions.isEmpty() && showPlaceholder)) {
        AxMediaCard(
            viewModel = viewModel,
            session = currentSession,
            expandedMediaState = expandedMediaState,
            lastMediaPackage = lastMediaPackage,
            span = span,
            interactive = interactive,
            allowGuts = false,
            surface = surface,
            motionArtVisible = motionArtVisible,
            motionArtAllowedToPlay = motionArtPlaying,
            modifier = modifier,
        )
        return
    }

    val shape = axQsControlShape(AxQsControl.MEDIA, span, mediaSurface = surface)
    val gesturesEnabled = interactive && !viewModel.hasVisibleGuts()
    val carouselScrollingEnabled =
        gesturesEnabled && (surface != AxMediaSurface.LOCKSCREEN || sessions.size > 1)
    var isFalseTouchDetected by remember(surface) { mutableStateOf(false) }
    val behavior =
        remember(viewModel, surface, carouselScrollingEnabled) {
            MediaUiBehavior(
                isCarouselDismissible = false,
                isCarouselScrollingEnabled = carouselScrollingEnabled,
                carouselVisibility = MediaCarouselVisibility.WhenAnyCardIsActive,
                isCarouselScrollFalseTouch =
                    if (surface == AxMediaSurface.LOCKSCREEN) {
                        null
                    } else {
                        { viewModel.isSwipeFalseTouch().also { isFalseTouchDetected = it } }
                    },
            )
        }
    val onDismissed = remember(viewModel, surface) { { viewModel.dismissBySwipe(surface) } }
    val mediaModifier =
        if (surface == AxMediaSurface.SEPARATE_QQS) {
            modifier
                .fillMaxSize()
                .clip(shape)
                .mediaOverscrollToDismiss(
                    enabled = gesturesEnabled,
                    dismissAllowed = !isFalseTouchDetected,
                    onDismissed = onDismissed,
                )
        } else {
            modifier.fillMaxSize()
        }
    val context = LocalContext.current
    val mediaViewModel: MediaViewModel =
        rememberViewModel(traceName = "AxMediaViewModel") {
            factory.create(context, behavior.carouselVisibility)
        }
    AxMediaCarousel(
        viewModel = mediaViewModel,
        behavior = behavior,
        onDismissed = onDismissed,
        modifier = mediaModifier,
        cardFilter = { viewModel.isSessionVisible(it.key, surface) },
        carouselShape = shape,
        cardContent = { card, cardModifier, isCurrentCard ->
            viewModel.sessionForKey(card.key)?.let { session ->
                AxMediaCard(
                    viewModel = viewModel,
                    session = session,
                    expandedMediaState = expandedMediaState,
                    lastMediaPackage = lastMediaPackage,
                    span = span,
                    interactive = interactive,
                    allowGuts = allowGuts,
                    surface = surface,
                    hasMultipleSessions = sessions.size > 1,
                    motionArtVisible = motionArtVisible && isCurrentCard,
                    motionArtAllowedToPlay = motionArtPlaying,
                    modifier = cardModifier.fillMaxSize(),
                )
            }
        },
        pagerIndicator = { pagerState ->
            PagerDots(
                pagerState = pagerState,
                activeColor = Color(0xffdee0ff),
                nonActiveColor = Color(0xffa7a9ca),
                dotSize = 5.dp,
                spaceSize = 5.dp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp),
            )
        },
    )
}

@Composable
private fun AxMediaCard(
    viewModel: AxMediaViewModel,
    session: MediaSessionModel?,
    expandedMediaState: SharedMediaState,
    lastMediaPackage: String?,
    span: AxQsSpan,
    interactive: Boolean,
    allowGuts: Boolean,
    surface: AxMediaSurface,
    hasMultipleSessions: Boolean = false,
    motionArtVisible: Boolean = true,
    motionArtAllowedToPlay: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val shape = axQsControlShape(AxQsControl.MEDIA, span, mediaSurface = surface)
    val mediaStyle by viewModel.mediaStyle.collectAsStateWithLifecycle()
    val motionArtEnabled by viewModel.mediaMotionArt.collectAsStateWithLifecycle()
    val isShadeInteracting by viewModel.isShadeInteracting.collectAsStateWithLifecycle()
    val keyguardState by viewModel.currentKeyguardState.collectAsStateWithLifecycle()
    val layout =
        when {
            surface == AxMediaSurface.CONTROL && span == AxQsSpan.MediaDefault ->
                if (mediaStyle == LunarisQsMediaStyle.ALBUM_COVER) AxMediaLayout.AlbumCover
                else AxMediaLayout.Box
            span.rows == 1 -> AxMediaLayout.OneRow
            span.columns >= 3 -> AxMediaLayout.Expanded
            else -> AxMediaLayout.Compact
        }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var showExpandedPopup by remember(session?.key, isLandscape) { mutableStateOf(false) }
    val gutsVisible = allowGuts && session?.let(viewModel::isGutsVisible) == true
    val statusGlowEnabled =
        when (surface) {
            AxMediaSurface.CONTROL ->
                viewModel.mediaTileStatusGlow.collectAsStateWithLifecycle().value
            AxMediaSurface.SEPARATE_QQS ->
                viewModel.notificationMediaStatusGlow.collectAsStateWithLifecycle().value
            AxMediaSurface.LOCKSCREEN -> false
        }
    val statusGlowVisible =
        statusGlowEnabled &&
            interactive &&
            motionArtVisible &&
            !gutsVisible &&
            !showExpandedPopup &&
            keyguardState != KeyguardState.OFF &&
            keyguardState != KeyguardState.DOZING &&
            keyguardState != KeyguardState.AOD
    val glowCornerRadius =
        if (surface == AxMediaSurface.CONTROL && span == AxQsSpan.MediaDefault) 28.dp
        else AxQsControlCornerRadius
    val motionArtActive =
        interactive &&
            motionArtVisible &&
            !gutsVisible &&
            session?.state == MediaSessionState.Playing &&
            keyguardState != KeyguardState.OFF &&
            keyguardState != KeyguardState.DOZING &&
            keyguardState != KeyguardState.AOD
    val motionArtPlaying =
        motionArtActive && motionArtAllowedToPlay && !isShadeInteracting && !showExpandedPopup
    val motionArt =
        rememberLunarisMotionArtFile(
            artist = session?.subtitle,
            title = session?.title,
            enabled = motionArtEnabled && motionArtActive && span.columns > 1,
        )
    val artwork = session?.background?.takeIf { span.columns > 1 }
    val tileBackground = AxTileDefaults.backgroundColor()
    val tileForeground = MaterialTheme.colorScheme.onSurface
    val colorScheme = session?.colorScheme
    val mediaBackground = colorScheme?.background ?: MaterialTheme.colorScheme.onSurface
    val background by
        animateColorAsState(
            targetValue =
                when {
                    (layout == AxMediaLayout.Box || layout == AxMediaLayout.AlbumCover) &&
                        !gutsVisible -> tileBackground
                    artwork != null && !gutsVisible -> Color.Transparent
                    session != null -> mediaBackground
                    else -> tileBackground
                },
            label = "AxMediaBackground",
        )
    val primary by
        animateColorAsState(
            targetValue =
                if (session != null) {
                    colorScheme?.primary ?: MaterialTheme.colorScheme.primaryFixed
                } else {
                    MaterialTheme.colorScheme.primary
                },
            label = "AxMediaPrimary",
        )
    val onPrimary by
        animateColorAsState(
            targetValue =
                if (session != null) {
                    colorScheme?.onPrimary ?: MaterialTheme.colorScheme.onPrimaryFixed
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            label = "AxMediaOnPrimary",
        )
    val foreground by
        animateColorAsState(
            targetValue = if (session != null) Color.White else tileForeground,
            label = "AxMediaForeground",
        )
    val animatedMediaBackground by
        animateColorAsState(targetValue = mediaBackground, label = "AxMediaArtworkOverlay")
    val colors =
        AxMediaColors(
            primary = primary,
            onPrimary = onPrimary,
            background = animatedMediaBackground,
            foreground = foreground,
        )
    val clickLabel =
        if (session != null) {
            stringResource(
                R.string.controls_media_playing_item_description,
                session.title,
                session.subtitle,
                session.appName,
            )
        } else if (lastMediaPackage != null) {
            "Open last media app"
        } else {
            null
        }
    ExpandableContainer(
        controller = rememberExpandableController(color = { background }, shape = shape),
        modifier =
            modifier
                .fillMaxSize()
                .clip(shape)
                .cosStatusGlow(
                    trigger = session?.let { Triple(it.key, it.title, it.subtitle) },
                    cornerRadius = glowCornerRadius,
                    enabled = statusGlowVisible,
                ),
        onClick = null,
        onClickLabel = null,
        defaultMinSize = false,
        useModifierBasedImplementation = true,
    ) { expandable ->
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .combinedClickable(
                        enabled = interactive && (session != null || lastMediaPackage != null),
                        onClick = {
                            if (gutsVisible) {
                                viewModel.closeGuts()
                            } else if (session != null && isLandscape) {
                                viewModel.openSession(session, expandable)
                            } else if (session != null && expandedMediaState.controller != null) {
                                showExpandedPopup = true
                            } else if (session == null) {
                                viewModel.openLastMediaApp(expandable)
                            }
                        },
                        onClickLabel = clickLabel,
                        onLongClick = {
                            if (session != null) {
                                viewModel.openSession(session, expandable)
                            } else {
                                viewModel.openLastMediaApp(expandable)
                            }
                        },
                    )
            )
            AnimatedContent(
                targetState = gutsVisible,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "AxMediaGuts",
                modifier = Modifier.fillMaxSize(),
            ) { showGuts ->
                if (showGuts && session != null) {
                    MediaGuts(
                        session = session,
                        viewModel = viewModel,
                        colors = colors,
                        compact = span.columns == 1,
                        surface = surface,
                    )
                } else {
                    Box(Modifier.fillMaxSize()) {
                        if (
                            session != null &&
                                layout != AxMediaLayout.Box &&
                                layout != AxMediaLayout.AlbumCover
                        ) {
                            val songKey = "${session.appName}:${session.title}:${session.subtitle}"
                            MediaArtwork(
                                artwork = artwork,
                                songKey = songKey,
                                overlayColor = colors.background,
                                motionArt = motionArt,
                                motionArtPlaying = motionArtPlaying,
                            )
                        }
                        AnimatedContent(
                            targetState = layout,
                            transitionSpec = {
                                ((fadeIn(tween(180)) +
                                        scaleIn(tween(220), initialScale = 0.96f)) togetherWith
                                        (fadeOut(tween(120)) +
                                            scaleOut(tween(160), targetScale = 1.04f)))
                                    .using(sizeTransform = null)
                            },
                            contentAlignment = Alignment.Center,
                            contentKey = { it },
                            label = "AxMediaLayout",
                            modifier = Modifier.fillMaxSize(),
                        ) { mediaLayout ->
                            when (mediaLayout) {
                                AxMediaLayout.Box ->
                                    LunarisBoxMediaPlayer(
                                        session = session,
                                        viewModel = viewModel,
                                        interactive = interactive,
                                        hasMultipleSessions = hasMultipleSessions,
                                        motionArt = motionArt,
                                        motionArtPlaying = motionArtPlaying,
                                    )
                                AxMediaLayout.AlbumCover ->
                                    LunarisAlbumCoverMediaPlayer(
                                        session = session,
                                        viewModel = viewModel,
                                        interactive = interactive,
                                        hasMultipleSessions = hasMultipleSessions,
                                        motionArt = motionArt,
                                        motionArtPlaying = motionArtPlaying,
                                    )
                                AxMediaLayout.OneRow ->
                                    OneRowMediaContent(
                                        session = session,
                                        viewModel = viewModel,
                                        colors = colors,
                                        interactive = interactive,
                                        maxActions = mediaActionLimit(span.columns),
                                        hasMultipleSessions = hasMultipleSessions,
                                    )
                                AxMediaLayout.Compact ->
                                    CompactMediaContent(
                                        session = session,
                                        viewModel = viewModel,
                                        colors = colors,
                                        interactive = interactive,
                                        hasMultipleSessions = hasMultipleSessions,
                                    )
                                AxMediaLayout.Expanded ->
                                    ExpandedMediaContent(
                                        session = session,
                                        viewModel = viewModel,
                                        span = span,
                                        colors = colors,
                                        interactive = interactive,
                                        inlineTimestamps = surface == AxMediaSurface.SEPARATE_QQS,
                                        hasMultipleSessions = hasMultipleSessions,
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
    if (
        !isLandscape &&
            showExpandedPopup &&
            session != null &&
            expandedMediaState.controller != null
    ) {
        MaterialMediaExpandedPopup(
            mediaState = expandedMediaState,
            onDismiss = { showExpandedPopup = false },
            motionArtEnabled = motionArtEnabled && motionArtVisible,
            motionArtPlaying = motionArtAllowedToPlay && !isShadeInteracting,
        )
    }
}

@Composable
private fun OneRowMediaContent(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    maxActions: Int,
    hasMultipleSessions: Boolean = false,
) {
    val title = session?.title?.takeIf { it.isNotBlank() } ?: "No media playing"
    val subtitle = session?.subtitle.orEmpty()
    val metadata =
        if (subtitle.isEmpty()) {
            title
        } else {
            "$title · $subtitle"
        }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sizing =
            remember(maxWidth, maxHeight) {
                AxMediaSizing.from(maxWidth, maxHeight)
            }
        val actionLimit = if (maxWidth < 132.dp) 1 else maxActions
        val useRowLayout = maxWidth >= 200.dp || maxHeight < 56.dp
        if (useRowLayout) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxSize().padding(horizontal = sizing.horizontalPadding, vertical = 6.dp),
            ) {
                MediaAppIcon(
                    session = session,
                    size = sizing.headerIconSize,
                    tint = colors.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                AnimatedMediaText(
                    text = metadata,
                    color = colors.foreground,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = if (session == null) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                MediaControls(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    interactive = interactive,
                    maxActions = actionLimit,
                    actionSize = sizing.actionSize,
                )
            }
        } else {
            val dense = maxHeight < 68.dp
            val bottomPadding = if (hasMultipleSessions) 16.dp else (if (dense) 6.dp else 8.dp)
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier =
                    Modifier.fillMaxSize()
                        .padding(
                            start = sizing.horizontalPadding,
                            end = sizing.horizontalPadding,
                            top = if (dense) 6.dp else 8.dp,
                            bottom = bottomPadding,
                        ),
            ) {
                AnimatedMediaText(
                    text = metadata,
                    color = colors.foreground,
                    style =
                        if (dense) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                    textAlign = if (session == null) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    MediaControls(
                        session = session,
                        viewModel = viewModel,
                        colors = colors,
                        interactive = interactive,
                        maxActions = actionLimit,
                        actionSize = sizing.actionSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactMediaContent(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    hasMultipleSessions: Boolean = false,
) {
    val title = session?.title?.takeIf { it.isNotBlank() } ?: "No media playing"
    val subtitle = session?.subtitle.orEmpty()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sizing =
            remember(maxWidth, maxHeight) {
                AxMediaSizing.from(maxWidth, maxHeight, AxQsSpan.MediaDefault)
            }
        val availableHeight = maxHeight
        val contentHeight = maxHeight.coerceAtMost(CompactMediaMaxHeight)
        val outputMaxWidth = maxWidth * 0.32f
        val bottomPadding = if (hasMultipleSessions) 18.dp else 10.dp
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier =
                Modifier.align(Alignment.Center)
                    .fillMaxWidth()
                    .height(contentHeight)
                    .padding(top = 10.dp, bottom = bottomPadding),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = sizing.horizontalPadding),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                ) {
                    MediaAppIcon(
                        session = session,
                        size = sizing.headerIconSize,
                        tint = colors.primary,
                    )
                }
                MediaOutputChip(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    interactive = interactive,
                    showLabel = false,
                    compact = sizing.outputChipCompact,
                    modifier = Modifier.widthIn(max = outputMaxWidth),
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = sizing.horizontalPadding)) {
                Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                    AnimatedMediaText(
                        text = title,
                        color = colors.foreground,
                        style =
                            if (sizing.outputChipCompact) {
                                MaterialTheme.typography.labelMedium
                            } else {
                                MaterialTheme.typography.labelLarge
                            },
                        textAlign = if (session == null) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (subtitle.isNotEmpty() && availableHeight >= 112.dp) {
                        AnimatedMediaText(
                            text = subtitle,
                            color = colors.foreground.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                MediaControls(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    interactive = interactive,
                    maxActions = mediaActionLimit(AxQsSpan.MediaDefault.columns),
                    actionSize = sizing.actionSize,
                    spreadCoreActions = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ExpandedMediaContent(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    span: AxQsSpan,
    colors: AxMediaColors,
    interactive: Boolean,
    inlineTimestamps: Boolean = false,
    hasMultipleSessions: Boolean = false,
) {
    val title = session?.title?.takeIf { it.isNotBlank() } ?: "No media playing"
    val subtitle = session?.subtitle.orEmpty()
    val showCoreActions =
        session?.actionButtonLayout != MediaCardActionButtonLayout.SecondaryActionsOnly

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sizing =
            remember(maxWidth, maxHeight, span) {
                AxMediaSizing.from(maxWidth, maxHeight, span)
            }
        val verticalPadding = if (inlineTimestamps) 4.dp else sizing.verticalPadding
        val bottomPadding =
            if (hasMultipleSessions) {
                if (inlineTimestamps) 12.dp else 16.dp
            } else {
                verticalPadding
            }

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(
                        start = sizing.horizontalPadding,
                        end = sizing.horizontalPadding,
                        top = verticalPadding,
                        bottom = bottomPadding,
                    ),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaAppIcon(
                    session = session,
                    size = sizing.headerIconSize,
                    tint = colors.primary,
                )
                val outputDeviceName =
                    session?.outputDevice?.name?.toString()?.takeUnless { it.isBlank() || it == "null" }
                        ?: stringResource(R.string.ax_dynamic_bar_media_output)
                MediaOutputChip(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    interactive = interactive,
                    showLabel = true,
                    label = outputDeviceName,
                    compact = sizing.outputChipCompact,
                )
            }

            Spacer(Modifier.height(if (inlineTimestamps) 2.dp else 6.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                AnimatedMediaText(
                    text = title,
                    color = colors.foreground,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    AnimatedMediaText(
                        text = subtitle,
                        color = colors.foreground.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (!inlineTimestamps) Spacer(Modifier.height(8.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                MediaSeekBar(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    dense = true,
                    interactive = interactive,
                    showTimestamps = inlineTimestamps && sizing.showTimestamps,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sizing.showTimestamps && !inlineTimestamps) {
                    MediaTimestamps(
                        session = session,
                        viewModel = viewModel,
                        colors = colors,
                    )
                }
            }

            if (!inlineTimestamps) Spacer(Modifier.height(6.dp))

            val maxActions = mediaActionLimit(span.columns)
            val additionalActions =
                session?.additionalActions.orEmpty().take((maxActions - 3).coerceAtLeast(0))
            val leftSecondary = additionalActions.firstOrNull()
            val rightSecondary = additionalActions.drop(1).firstOrNull()
            val totalActionCount =
                if (showCoreActions) 3 + additionalActions.size else additionalActions.size
            val actionSpacing = if (totalActionCount > 3) 14.dp else sizing.actionSpacing

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                with(Icons.Filled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (leftSecondary != null && showCoreActions) {
                            MediaAction(
                                action = leftSecondary,
                                viewModel = viewModel,
                                width = sizing.actionSize,
                                iconSize = sizing.actionIconSize,
                                tint = colors.foreground,
                                interactive = interactive,
                            )
                            Spacer(Modifier.width(actionSpacing))
                        }

                        if (showCoreActions) {
                            ExpandedNavigationAction(
                                action = session?.leftAction,
                                placeholderDescription = R.string.controls_media_button_prev,
                                viewModel = viewModel,
                                colors = colors,
                                interactive = interactive,
                                size = sizing.actionSize,
                                iconSize = sizing.actionIconSize,
                                imageVector = SkipPrevious,
                            )

                            Spacer(Modifier.width(actionSpacing))

                            CoreMediaAction(
                                action = session?.playPauseAction,
                                imageVector = playPauseIcon(session),
                                descriptionRes = playPauseDescription(session),
                                animatedIconRes = R.drawable.ic_media_play_button,
                                animatedIconAtEnd = session?.state == MediaSessionState.Playing,
                                viewModel = viewModel,
                                width = sizing.heroActionSize,
                                height = sizing.heroActionSize,
                                iconSize = sizing.heroIconSize,
                                tint = colors.foreground,
                                background = Color.Transparent,
                                interactive = interactive,
                            )

                            Spacer(Modifier.width(actionSpacing))

                            ExpandedNavigationAction(
                                action = session?.rightAction,
                                placeholderDescription = R.string.controls_media_button_next,
                                viewModel = viewModel,
                                colors = colors,
                                interactive = interactive,
                                size = sizing.actionSize,
                                iconSize = sizing.actionIconSize,
                                imageVector = SkipNext,
                            )
                        } else if (additionalActions.isNotEmpty()) {
                            additionalActions.forEachIndexed { index, action ->
                                if (index > 0) Spacer(Modifier.width(actionSpacing))
                                MediaAction(
                                    action = action,
                                    viewModel = viewModel,
                                    width = sizing.actionSize,
                                    iconSize = sizing.actionIconSize,
                                    tint = colors.foreground,
                                    interactive = interactive,
                                )
                            }
                        }

                        if (rightSecondary != null && showCoreActions) {
                            Spacer(Modifier.width(actionSpacing))
                            MediaAction(
                                action = rightSecondary,
                                viewModel = viewModel,
                                width = sizing.actionSize,
                                iconSize = sizing.actionIconSize,
                                tint = colors.foreground,
                                interactive = interactive,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val CompactMediaMaxHeight = 220.dp
