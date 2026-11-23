/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.media.remedia.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.media.remedia.shared.model.LockscreenMediaStyle
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.panels.ui.media.LocalMediaButtonSquish
import com.android.systemui.qs.panels.ui.media.LockscreenMediaCard
import com.android.systemui.qs.panels.ui.media.lockscreenMediaCornerRadius
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel

@Composable
fun LockscreenMedia(
    viewModelFactory: MediaViewModel.Factory,
    lockscreenViewModel: AxMediaViewModel,
    behavior: MediaUiBehavior,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onVisibilityChanged: (Boolean) -> Unit = {},
) {
    val allowed by lockscreenViewModel.showOnLockscreen.collectAsStateWithLifecycle()
    val transition by lockscreenViewModel.lockscreenTransition.collectAsStateWithLifecycle()
    val active = transition.to == KeyguardState.LOCKSCREEN
    val style by lockscreenViewModel.lockscreenStyle.collectAsStateWithLifecycle()
    val selectedStyle = style
    val visibilityState = remember { MutableTransitionState(false) }
    visibilityState.targetState = visible && allowed && active
    val currentOnVisibilityChanged by rememberUpdatedState(onVisibilityChanged)
    LaunchedEffect(
        visibilityState.isIdle,
        visibilityState.currentState,
        visibilityState.targetState,
    ) {
        currentOnVisibilityChanged(
            visibilityState.currentState || visibilityState.targetState || !visibilityState.isIdle
        )
    }
    AnimatedVisibility(
        visibleState = visibilityState,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(120)),
        modifier = modifier,
    ) {
        CompositionLocalProvider(
            LocalMediaButtonSquish provides true,
            LocalMediaAnimationsEnabled provides (visible && allowed && active),
        ) {
            Media(
                viewModelFactory = viewModelFactory,
                presentationStyle = MediaPresentationStyle.Lockscreen,
                behavior = behavior,
                onDismissed = onDismissed,
                cardCornerRadius = lockscreenMediaCornerRadius(selectedStyle),
                pagerIndicator =
                    if (
                        selectedStyle == LockscreenMediaStyle.ALBUM_ART ||
                            selectedStyle == LockscreenMediaStyle.ONE_UI
                    ) {
                        { pagerState ->
                            val useSurfaceColors = selectedStyle == LockscreenMediaStyle.ALBUM_ART
                            PagerDots(
                                pagerState = pagerState,
                                activeColor =
                                    if (useSurfaceColors) MaterialTheme.colorScheme.onSurface
                                    else Color(0xffdee0ff),
                                nonActiveColor =
                                    if (useSurfaceColors)
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    else Color(0xffa7a9ca),
                                dotSize = 5.dp,
                                spaceSize = 5.dp,
                                modifier =
                                    Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp),
                            )
                        }
                    } else null,
                cardContent =
                    if (selectedStyle == LockscreenMediaStyle.DEFAULT) null
                    else {
                        { card, cardModifier, isCurrentCard, isVisibleCard ->
                            rememberLastNonNull(lockscreenViewModel.sessionForKey(card.key))?.let {
                                session ->
                                LockscreenMediaCard(
                                    session = session,
                                    viewModel = lockscreenViewModel,
                                    style = selectedStyle,
                                    isCurrentCard = isCurrentCard,
                                    isVisibleCard = isVisibleCard,
                                    modifier = cardModifier,
                                )
                            }
                        }
                    },
            )
        }
    }
}
