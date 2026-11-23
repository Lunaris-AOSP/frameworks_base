/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.media

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.axion.blur.AxBlurLifecycle
import com.android.axion.blur.axBlurBackground
import com.android.axion.blur.model.AxBackdropBlurSettingsSpec
import com.android.compose.animation.Expandable
import com.android.compose.animation.rememberExpandableController
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.shared.model.LockscreenMediaStyle
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.media.remedia.ui.compose.LocalMediaAnimationsEnabled
import com.android.systemui.qs.composefragment.cosMediaRipple
import com.android.systemui.qs.panels.shared.model.LunarisQsNotificationMediaStyle
import com.android.systemui.qs.panels.ui.compose.AxNotificationMediaCornerRadius
import com.android.systemui.qs.panels.ui.compose.AxTileDefaults
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R

internal fun lockscreenMediaCornerRadius(style: LockscreenMediaStyle): Dp =
    when (style) {
        LockscreenMediaStyle.ALBUM_ART -> AxNotificationMediaCornerRadius
        LockscreenMediaStyle.ONE_UI -> AxNotificationMediaCornerRadius
        else -> 32.dp
    }

@Composable
internal fun LockscreenMediaCard(
    session: MediaSessionModel,
    viewModel: AxMediaViewModel,
    style: LockscreenMediaStyle,
    isCurrentCard: Boolean,
    isVisibleCard: Boolean,
    modifier: Modifier,
) {
    val animationsEnabled = LocalMediaAnimationsEnabled.current
    val interactive = isCurrentCard && animationsEnabled
    val blurVisible = isCurrentCard || isVisibleCard
    val motionArtEnabled by viewModel.mediaMotionArt.collectAsStateWithLifecycle()
    val isShadeInteracting by viewModel.isShadeInteracting.collectAsStateWithLifecycle()
    val keyguardState by viewModel.currentKeyguardState.collectAsStateWithLifecycle()
    val motionArtActive =
        (style == LockscreenMediaStyle.ALBUM_ART || style == LockscreenMediaStyle.ONE_UI) &&
            interactive &&
            session.state == MediaSessionState.Playing &&
            keyguardState != KeyguardState.OFF &&
            keyguardState != KeyguardState.DOZING &&
            keyguardState != KeyguardState.AOD
    val motionArt =
        rememberLunarisMotionArtFile(
            artist = session.subtitle,
            title = session.title,
            enabled = motionArtEnabled && motionArtActive,
        )
    val motionArtPlaying = motionArtEnabled && motionArtActive && !isShadeInteracting
    DisposableEffect(session.key, session.title, session.subtitle, isCurrentCard) {
        if (isCurrentCard) viewModel.synchronizeSession(session.key)
        onDispose { if (isCurrentCard) viewModel.cancelScrubbing() }
    }
    val primary by
        animateColorAsState(
            session.colorScheme?.primary ?: MaterialTheme.colorScheme.primaryFixed,
            tween(if (animationsEnabled) 250 else 0),
            label = "LockscreenMediaAccent",
        )
    val background by
        animateColorAsState(
            when (style) {
                LockscreenMediaStyle.ALBUM_ART -> AxTileDefaults.backgroundColor()
                LockscreenMediaStyle.ONE_UI ->
                    lerp(session.colorScheme?.background ?: Color(0xFF30242C), Color.Black, 0.8f)
                else -> session.colorScheme?.background ?: Color(0xFF30242C)
            },
            tween(if (animationsEnabled) 250 else 0),
            label = "LockscreenMediaBackground",
        )
    val foreground = Color.White
    val colors = AxMediaColors(primary, Color.Black, background, foreground)
    val cornerRadius = lockscreenMediaCornerRadius(style)
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val hasMultipleSessions = viewModel.sessionCount > 1
    val pagerPadding = if (hasMultipleSessions && style == LockscreenMediaStyle.COS) 16.dp else 0.dp
    val seekBarStyle by viewModel.seekBarStyle.collectAsStateWithLifecycle()
    val seekBarHeight = mediaSeekBarHeight(seekBarStyle, dense = true)
    val timestampHeight = with(LocalDensity.current) { 12.sp.toDp() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cardHeight =
            when (style) {
                LockscreenMediaStyle.ALBUM_ART ->
                    notificationMediaHeight(LunarisQsNotificationMediaStyle.ALBUM_COVER)
                LockscreenMediaStyle.ONE_UI ->
                    notificationMediaHeight(LunarisQsNotificationMediaStyle.ONE_UI)
                else ->
                    maxOf(156.dp, maxWidth * (220f / 598f)) +
                        with(LocalDensity.current) {
                            (24.sp.toDp() + 20.sp.toDp() - 44.dp).coerceAtLeast(0.dp)
                        } +
                        maxOf(seekBarHeight, timestampHeight) - 20.dp
            }
        AxBlurLifecycle(enabled = blurVisible) {
            Expandable(
                controller =
                    rememberExpandableController(color = { Color.Transparent }, shape = shape),
                modifier =
                    Modifier.fillMaxWidth()
                        .height(cardHeight)
                        .clip(shape)
                        .cosMediaRipple(
                            trigger = Triple(session.key, session.title, session.subtitle),
                            cornerRadius = cornerRadius,
                            enabled = interactive && style != LockscreenMediaStyle.DEFAULT,
                        ),
                defaultMinSize = false,
                useModifierBasedImplementation = true,
            ) { expandable ->
                val glass =
                    style == LockscreenMediaStyle.ALBUM_ART || style == LockscreenMediaStyle.COS
                val surface =
                    if (glass) {
                        Modifier.axBlurBackground(
                            enabled = blurVisible,
                            fallbackColor = background,
                            tintColor = Color.Black.copy(alpha = 0.32f),
                            settingsSpec = AxBackdropBlurSettingsSpec.system(),
                            shape = shape,
                        )
                    } else {
                        Modifier.background(background)
                    }
                Box(Modifier.fillMaxSize().then(surface)) {
                    if (style == LockscreenMediaStyle.ONE_UI) {
                        MediaArtwork(
                            artwork =
                                rememberAxMediaArtwork(
                                    session.background,
                                    "${session.key}:${session.title}:${session.subtitle}",
                                ),
                            overlayColor = colors.background,
                            motionArt = motionArt,
                            motionArtPlaying = motionArtPlaying,
                            scrim = AxMediaArtworkScrim.TopLit,
                        )
                    }
                    Column(
                        Modifier.fillMaxSize().padding(bottom = pagerPadding).clickable(
                            enabled = interactive,
                            role = Role.Button,
                        ) {
                            viewModel.openSession(session, expandable)
                        }
                    ) {
                        AnimatedContent(
                            targetState = style,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            transitionSpec = {
                                (fadeIn(tween(if (animationsEnabled) 180 else 0)) togetherWith
                                        fadeOut(tween(if (animationsEnabled) 120 else 0)))
                                    .using(sizeTransform = null)
                            },
                            label = "LockscreenMediaStyle",
                        ) { currentStyle ->
                            when (currentStyle) {
                                LockscreenMediaStyle.ALBUM_ART ->
                                    LunarisWideMediaPlayer(
                                        session = session,
                                        viewModel = viewModel,
                                        interactive = interactive,
                                        hasMultipleSessions = hasMultipleSessions,
                                        motionArt = motionArt,
                                        motionArtPlaying = motionArtPlaying,
                                        foreground = Color.White,
                                        secondaryForeground = Color.White,
                                    )
                                LockscreenMediaStyle.ONE_UI ->
                                    NotificationMediaContent(
                                        session = session,
                                        viewModel = viewModel,
                                        colors = colors,
                                        interactive = interactive,
                                        hasMultipleSessions = hasMultipleSessions,
                                    )
                                else ->
                                    CosLockscreenMediaPlayer(
                                        session = session,
                                        viewModel = viewModel,
                                        colors = colors.copy(primary = Color(0xFF80DEEA)),
                                        interactive = interactive,
                                        onOpenApp = { viewModel.openSession(session, expandable) },
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CosLockscreenMediaPlayer(
    session: MediaSessionModel,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    onOpenApp: () -> Unit,
) {
    val isBluetoothConnected by viewModel.isBluetoothConnected.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val artworkSize = (maxWidth * (68f / 598f)).coerceIn(44.dp, 68.dp)
        val pauseSize = (maxWidth * (64f / 598f)).coerceIn(48.dp, 64.dp)
        val seekBarStyle by viewModel.seekBarStyle.collectAsStateWithLifecycle()
        val metadataHeight = with(LocalDensity.current) { 24.sp.toDp() + 20.sp.toDp() }
        val timestampHeight = with(LocalDensity.current) { 12.sp.toDp() }
        val padding =
            minOf(
                (maxWidth * (24f / 598f)).coerceIn(12.dp, 24.dp),
                ((maxHeight -
                        maxOf(artworkSize, metadataHeight, 48.dp) -
                        maxOf(mediaSeekBarHeight(seekBarStyle, dense = true), timestampHeight) -
                        pauseSize) / 2)
                    .coerceAtLeast(0.dp),
            )
        val contentWidth = (maxWidth - padding * 2).coerceAtLeast(0.dp)
        val leadingSpace = if (contentWidth >= 240.dp) 48.dp else 0.dp
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AlbumCoverArtwork(
                    artwork = session.background,
                    modifier = Modifier.size(artworkSize),
                    motionArt = null,
                    motionArtPlaying = false,
                    cornerRadius = 12.dp,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    AnimatedMediaText(
                        text = session.title,
                        color = colors.foreground,
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                            ),
                    )
                    AnimatedMediaText(
                        text = session.subtitle,
                        color = colors.foreground.copy(alpha = 0.55f),
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                            ),
                    )
                }
                Box(
                    modifier =
                        Modifier.size(48.dp)
                            .clickable(
                                enabled = interactive,
                                role = Role.Button,
                                onClick = onOpenApp,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon = session.appIcon,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)),
                    )
                }
            }
            MediaSeekBar(
                session = session,
                viewModel = viewModel,
                colors = colors,
                dense = true,
                interactive = interactive,
                showTimestamps = true,
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (leadingSpace > 0.dp) Spacer(Modifier.width(leadingSpace))
                    CompactMediaActions(
                        session = session,
                        viewModel = viewModel,
                        foreground = colors.foreground,
                        interactive = interactive,
                        modifier = Modifier.weight(1f),
                        playPauseBackground = colors.foreground.copy(alpha = 0.16f),
                        playPauseSize = pauseSize,
                    )
                    AlbumCoverMediaOutput(
                        session = session,
                        viewModel = viewModel,
                        interactive = interactive,
                        modifier = Modifier.size(48.dp),
                        foreground = colors.foreground,
                        iconRes =
                            if (isBluetoothConnected) R.drawable.ic_lockscreen_media_earbuds
                            else R.drawable.ic_qs_media_output_album_cover,
                    )
                }
            }
        }
    }
}
