/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import android.content.Context
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.flashlight.ui.compose.FlashlightPopup
import com.android.systemui.statusbar.featurepods.media.ui.compose.LyricsCard
import com.android.systemui.statusbar.featurepods.media.ui.compose.MediaControlPopup
import com.android.systemui.statusbar.featurepods.media.ui.compose.RedesignedMediaPopup
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.SystemEventKind
import kotlinx.coroutines.launch

/** Displays an island card that grows from, and returns to, the tapped capsule. */
@Composable
fun StatusBarPopup(
    viewModel: PopupChipModel.Shown,
    isVisible: Boolean,
    chipBoundsInScreen: Rect? = null,
    canPage: Boolean = false,
    pageDirection: Int = 1,
    onPage: (Int) -> Unit = {},
    onHidden: () -> Unit = {},
) {
    LunarisBarContentTheme(viewModel.popupContent, viewModel.chipId) {
        LunarisBarPopupWindow(
            viewModel,
            isVisible,
            chipBoundsInScreen,
            canPage,
            pageDirection,
            onPage,
            onHidden,
        )
    }
}

@Composable
private fun LunarisBarPopupWindow(
    viewModel: PopupChipModel.Shown,
    isVisible: Boolean,
    chipBoundsInScreen: Rect?,
    canPage: Boolean,
    pageDirection: Int,
    onPage: (Int) -> Unit,
    onHidden: () -> Unit,
) {
    val density = LocalDensity.current
    val settings = LocalLunarisBarState.current.settings
    val topInsetPx =
        with(density) {
            dimensionResource(R.dimen.status_bar_height).roundToPx() + 8.dp.roundToPx()
        }
    val currentHidePopup by rememberUpdatedState(viewModel.hidePopup)
    Popup(
        popupPositionProvider =
            remember {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ) = IntOffset.Zero
                }
            },
        properties =
            PopupProperties(
                flags =
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
        onDismissRequest = { currentHidePopup() },
    ) {
        val popupView = LocalView.current
        DisposableEffect(popupView) {
            val windowView = popupView.rootView
            val params = windowView.layoutParams as? WindowManager.LayoutParams
            if (params != null && windowView.isAttachedToWindow) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                params.setFitInsetsTypes(0)
                params.windowAnimations = 0
                val manager =
                    windowView.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                manager.updateViewLayout(windowView, params)
            }
            val listener =
                ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                    if (!hasFocus) currentHidePopup()
                }
            popupView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            onDispose { popupView.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
        }
        LunarisBarPopupMotion(
            isVisible = isVisible,
            chipBoundsInScreen = chipBoundsInScreen,
            topInsetPx = topInsetPx,
            onDismissRequest = { currentHidePopup() },
            onHidden = onHidden,
            fastEnter =
                (viewModel.popupContent as? PopupContentModel.SystemEvent)?.kind ==
                    SystemEventKind.Unlock,
            useRedesignedMediaBackground =
                LocalLunarisBarState.current.alternativeMedia &&
                    (viewModel.popupContent as? PopupContentModel.Media)?.useRedesignedMedia ==
                        true,
            secondaryContent = {
                val media = viewModel.popupContent as? PopupContentModel.Media
                if (
                    media != null &&
                        (!media.model.lyrics.isNullOrBlank() ||
                            !media.model.syncedLyrics.isNullOrBlank())
                ) {
                    LyricsCard(model = media.model, useRedesignedMedia = false)
                }
            },
        ) {
            val pagerScope = rememberCoroutineScope()
            val pageFollow = remember(viewModel.chipId) { Animatable(0f) }
            var pageAccumulator by remember(viewModel.chipId) { mutableFloatStateOf(0f) }
            var followJob by
                remember(viewModel.chipId) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            val notification =
                (viewModel.popupContent as? PopupContentModel.SystemEvent)?.kind ==
                    SystemEventKind.Notification
            val currentDismissNotification by rememberUpdatedState(viewModel.dismissNotification)
            val dismissLabel = stringResource(R.string.lunaris_bar_swipe_dismiss)
            val pageThresholdPx = with(density) { 56.dp.toPx() }
            val maxFollowPx = with(density) { 40.dp.toPx() }
            val currentOnPage by rememberUpdatedState(onPage)

            Box(modifier = Modifier.wrapContentSize()) {
                Box(
                    modifier =
                        Modifier.then(
                                if (notification || canPage) {
                                    Modifier.pointerInput(viewModel.chipId, notification, canPage) {
                                        detectHorizontalDragGestures(
                                            onDragEnd = {
                                                val direction =
                                                    when {
                                                        pageAccumulator <= -pageThresholdPx -> 1
                                                        pageAccumulator >= pageThresholdPx -> -1
                                                        else -> 0
                                                    }
                                                pageAccumulator = 0f
                                                followJob?.cancel()
                                                followJob =
                                                    pagerScope.launch {
                                                        pageFollow.animateTo(
                                                            0f,
                                                            spring(
                                                                dampingRatio =
                                                                    Spring.DampingRatioNoBouncy,
                                                                stiffness = Spring.StiffnessMedium,
                                                            ),
                                                        )
                                                    }
                                                if (direction != 0) {
                                                    if (notification) {
                                                        currentHidePopup()
                                                        currentDismissNotification?.invoke()
                                                    } else currentOnPage(direction)
                                                }
                                            },
                                            onDragCancel = {
                                                pageAccumulator = 0f
                                                followJob?.cancel()
                                                followJob =
                                                    pagerScope.launch {
                                                        pageFollow.animateTo(
                                                            0f,
                                                            spring(
                                                                dampingRatio =
                                                                    Spring.DampingRatioMediumBouncy,
                                                                stiffness = Spring.StiffnessMedium,
                                                            ),
                                                        )
                                                    }
                                            },
                                            onHorizontalDrag = { change, dragAmount ->
                                                pageAccumulator += dragAmount
                                                val target =
                                                    (pageAccumulator * 0.5f).coerceIn(
                                                        -maxFollowPx,
                                                        maxFollowPx,
                                                    )
                                                followJob?.cancel()
                                                followJob =
                                                    pagerScope.launch { pageFollow.snapTo(target) }
                                                change.consume()
                                            },
                                        )
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .then(
                                if (notification)
                                    Modifier.semantics {
                                        stateDescription = dismissLabel
                                        dismiss {
                                            currentHidePopup()
                                            currentDismissNotification?.invoke()
                                            true
                                        }
                                    }
                                else Modifier
                            )
                            .graphicsLayer { translationX = pageFollow.value }
                ) {
                    AnimatedContent(
                        targetState = viewModel,
                        contentAlignment = Alignment.TopCenter,
                        contentKey = { it.chipId },
                        transitionSpec = {
                            val dir = if (pageDirection >= 0) 1 else -1
                            (slideInHorizontally(
                                animationSpec =
                                    tween(
                                        if (settings.isReducedMotion) 0
                                        else (280 / settings.animationSpeed).toInt()
                                    )
                            ) { w ->
                                dir * w
                            } +
                                fadeIn(
                                    animationSpec =
                                        tween(
                                            if (settings.isReducedMotion) 0
                                            else (200 / settings.animationSpeed).toInt()
                                        )
                                )) togetherWith
                                (slideOutHorizontally(
                                    animationSpec =
                                        tween(
                                            if (settings.isReducedMotion) 0
                                            else (240 / settings.animationSpeed).toInt()
                                        )
                                ) { w ->
                                    -dir * w
                                } +
                                    fadeOut(
                                        animationSpec =
                                            tween(
                                                if (settings.isReducedMotion) 0
                                                else (140 / settings.animationSpeed).toInt()
                                            )
                                    )) using
                                null
                        },
                        label = "island_popup_pager",
                    ) { chip ->
                        IslandPopupContent(chip)
                    }
                }
            }
        }
    }
}

/** Renders the per-feature card for [viewModel]; the swipe pager slides between these. */
@Composable
private fun IslandPopupContent(viewModel: PopupChipModel.Shown) {
    when (val popupContent = viewModel.popupContent) {
        is PopupContentModel.Utility ->
            LunarisBarUtilityCard(popupContent.card, viewModel.hidePopup)
        is PopupContentModel.Media -> {
            val model = popupContent.model
            val seekBarStyle = popupContent.seekBarStyle
            if (!LocalLunarisBarState.current.alternativeMedia) {
                LunarisBarMediaPopup(model = model, seekBarStyle = seekBarStyle)
            } else if (popupContent.useRedesignedMedia) {
                RedesignedMediaPopup(model = model, seekBarStyle = seekBarStyle)
            } else {
                MediaControlPopup(model = model, seekBarStyle = seekBarStyle)
            }
        }
        is PopupContentModel.ScreenRecord -> LunarisBarScreenRecordPopup(model = popupContent.model)
        is PopupContentModel.LiveScore -> LunarisBarLiveScorePopup(model = popupContent.model)
        is PopupContentModel.Flashlight -> {
            if (LocalLunarisBarState.current.alternativeFlashlight) {
                FlashlightPopup(
                    model = popupContent.model,
                    hapticsViewModelFactory = popupContent.hapticsViewModelFactory,
                )
            } else {
                LunarisBarFlashlightPopup(model = popupContent.model)
            }
        }
        is PopupContentModel.Stopwatch -> LunarisBarStopwatchPopup(model = popupContent.model)
        is PopupContentModel.Alarm -> LunarisBarAlarmPopup(model = popupContent.model)
        is PopupContentModel.SystemEvent -> SystemEventPopup(model = popupContent)
        PopupContentModel.None -> LunarisBarPrivacyPopup(viewModel)
    }
}
