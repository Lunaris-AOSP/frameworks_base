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

import android.view.ViewTreeObserver
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.alarm.ui.compose.AlarmPopup
import com.android.systemui.statusbar.featurepods.av.ui.compose.AvControlsChipPopup
import com.android.systemui.statusbar.featurepods.flashlight.ui.compose.FlashlightPopup
import com.android.systemui.statusbar.featurepods.livescore.ui.compose.LiveScorePopup
import com.android.systemui.statusbar.featurepods.media.ui.compose.MediaControlPopup
import com.android.systemui.statusbar.featurepods.media.ui.compose.LyricsCard
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.screenrecord.ui.compose.ScreenRecordPopup
import com.android.systemui.statusbar.featurepods.sharescreen.ui.compose.ShareScreenPrivacyIndicatorPopup
import com.android.systemui.statusbar.featurepods.stopwatch.ui.compose.StopwatchPopup
import kotlinx.coroutines.coroutineScope
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.POPUP_COLOR_MODE_BLUR
import kotlinx.coroutines.launch

/**
 * Displays a popup in the status bar area. The offset is calculated to draw the popup below the
 * status bar. When [chipBoundsInScreen] is provided, the popup grows out of the chip's on-screen
 * position and aspect ratio with an independent-axis squish animation, rather than a generic
 * uniform scale-in.
 */
@Composable
fun StatusBarPopup(
    viewModel: PopupChipModel.Shown,
    isVisible: Boolean,
    chipBoundsInScreen: Rect? = null,
    canPage: Boolean = false,
    pageDirection: Int = 1,
    onPage: (Int) -> Unit = {},
) {
    val density = Density(LocalContext.current)
    Popup(
        alignment = Alignment.TopCenter,
        properties =
            PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
        offset =
            IntOffset(
                x = 0,
                y = with(density) { dimensionResource(R.dimen.status_bar_height).roundToPx() },
            ),
        onDismissRequest = { viewModel.hidePopup() },
    ) {
        val popupView = LocalView.current
        var popupBoundsInScreen by remember { mutableStateOf<Rect?>(null) }

        val transformOrigin by remember {
            derivedStateOf {
                val chip = chipBoundsInScreen
                val popup = popupBoundsInScreen
                if (chip == null || popup == null || popup.width <= 0f) {
                    TransformOrigin(0.5f, 0f)
                } else {
                    val pivotX =
                        ((chip.center.x - popup.left) / popup.width).coerceIn(0.05f, 0.95f)
                    val pivotY =
                        if (popup.height > 0f) {
                            ((chip.center.y - popup.top) / popup.height).coerceIn(0f, 0.3f)
                        } else {
                            0f
                        }
                    TransformOrigin(pivotX, pivotY)
                }
            }
        }

        val initialScaleFromChip by remember {
            derivedStateOf {
                val chip = chipBoundsInScreen
                val popup = popupBoundsInScreen
                if (chip == null || popup == null || popup.width <= 0f || popup.height <= 0f) {
                    Offset(0.4f, 0.4f)
                } else {
                    Offset(
                        x = (chip.width / popup.width).coerceIn(0.2f, 1f),
                        y = (chip.height / popup.height).coerceIn(0.15f, 1f),
                    )
                }
            }
        }

        val scaleX = remember { Animatable(initialScaleFromChip.x) }
        val scaleY = remember { Animatable(initialScaleFromChip.y) }
        val alpha = remember { Animatable(0f) }
        val translationY = remember { Animatable(-24f) }
        val colorMode = rememberPopupColorMode()
        val skipMotionOnDismiss = colorMode == POPUP_COLOR_MODE_BLUR

        LaunchedEffect(isVisible, popupBoundsInScreen != null) {
            if (isVisible && popupBoundsInScreen != null) {
                scaleX.snapTo(initialScaleFromChip.x)
                scaleY.snapTo(initialScaleFromChip.y)
                alpha.snapTo(0f)
                translationY.snapTo(-24f)
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.72f,
                                    stiffness = 520f,
                                ),
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.76f,
                                    stiffness = 520f,
                                ),
                        )
                    }
                    launch {
                        translationY.animateTo(
                            targetValue = 0f,
                            animationSpec =
                                spring(
                                    dampingRatio = 0.72f,
                                    stiffness = 520f,
                                ),
                        )
                    }
                    launch {
                        alpha.animateTo(
                            1f,
                            animationSpec = tween(190, easing = FastOutSlowInEasing),
                        )
                    }
                }
            } else if (!isVisible) {
                if (skipMotionOnDismiss) {
                    coroutineScope {
                        launch {
                            scaleX.animateTo(
                                targetValue = 0.01f,
                                animationSpec = tween(130),
                            )
                        }
                        launch {
                            scaleY.animateTo(
                                targetValue = 0.01f,
                                animationSpec = tween(130),
                            )
                        }
                        launch { alpha.animateTo(0f, animationSpec = tween(120)) }
                    }
                } else {
                    coroutineScope {
                        launch {
                            scaleX.animateTo(
                                targetValue = initialScaleFromChip.x,
                                animationSpec =
                                    spring(
                                        dampingRatio = 0.8f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                            )
                        }
                        launch {
                            scaleY.animateTo(
                                targetValue = initialScaleFromChip.y,
                                animationSpec =
                                    spring(
                                        dampingRatio = 0.8f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                            )
                        }
                        launch {
                            translationY.animateTo(-16f, animationSpec = spring(stiffness = Spring.StiffnessMedium))
                        }
                        launch { alpha.animateTo(0f, animationSpec = tween(160)) }
                    }
                }
            }
        }

        DisposableEffect(popupView) {
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (!hasFocus) {
                    viewModel.hidePopup()
                }
            }
            popupView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            onDispose {
                popupView.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
            }
        }

        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(60)),
            exit = fadeOut(animationSpec = tween(160)),
        ) {
            val pagerScope = rememberCoroutineScope()
            val pageFollow = remember { Animatable(0f) }
            var pageAccumulator by remember { mutableFloatStateOf(0f) }
            val pageThresholdPx = with(density) { 56.dp.toPx() }
            val maxFollowPx = with(density) { 40.dp.toPx() }
            val currentOnPage by rememberUpdatedState(onPage)

            Box(
                modifier =
                    Modifier.padding(8.dp)
                        .wrapContentSize()
                        .onGloballyPositioned { coordinates ->
                            popupBoundsInScreen = coordinates.boundsInScreen(popupView)
                        }
                        .graphicsLayer {
                            this.scaleX = scaleX.value
                            this.scaleY = scaleY.value
                            this.alpha = alpha.value
                            this.translationY = translationY.value
                            this.transformOrigin = transformOrigin
                        }
            ) {
                Box(
                    modifier =
                        Modifier.then(
                                if (canPage) {
                                    Modifier.pointerInput(canPage) {
                                        detectHorizontalDragGestures(
                                            onDragEnd = {
                                                val direction =
                                                    when {
                                                        pageAccumulator <= -pageThresholdPx -> 1
                                                        pageAccumulator >= pageThresholdPx -> -1
                                                        else -> 0
                                                    }
                                                pageAccumulator = 0f
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
                                                    currentOnPage(direction)
                                                }
                                            },
                                            onDragCancel = {
                                                pageAccumulator = 0f
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
                                                    (pageAccumulator * 0.5f)
                                                        .coerceIn(-maxFollowPx, maxFollowPx)
                                                pagerScope.launch { pageFollow.snapTo(target) }
                                                change.consume()
                                            },
                                        )
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .graphicsLayer { translationX = pageFollow.value }
                ) {
                    AnimatedContent(
                        targetState = viewModel,
                        contentAlignment = Alignment.TopCenter,
                        contentKey = { it.chipId },
                        transitionSpec = {
                            val dir = if (pageDirection >= 0) 1 else -1
                            (slideInHorizontally(animationSpec = tween(280)) { w -> dir * w } +
                                fadeIn(animationSpec = tween(200))) togetherWith
                                (slideOutHorizontally(animationSpec = tween(240)) { w -> -dir * w } +
                                    fadeOut(animationSpec = tween(140))) using
                                SizeTransform(clip = false) { _, _ ->
                                    spring(dampingRatio = 0.9f, stiffness = 380f)
                                }
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

private fun LayoutCoordinates.boundsInScreen(view: android.view.View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}

/** Renders the per-feature card for [viewModel]; the swipe pager slides between these. */
@Composable
private fun IslandPopupContent(viewModel: PopupChipModel.Shown) {
    when (val popupContent = viewModel.popupContent) {
        is PopupContentModel.Media -> {
            val model = popupContent.model
            val useWaveform = popupContent.useWaveform
            val hasLyrics = !model.lyrics.isNullOrBlank() || !model.syncedLyrics.isNullOrBlank()
            if (hasLyrics) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    MediaControlPopup(model = model, useWaveform = useWaveform)
                    LyricsCard(model = model)
                }
            } else {
                MediaControlPopup(model = model, useWaveform = useWaveform)
            }
        }
        is PopupContentModel.ScreenRecord -> ScreenRecordPopup(model = popupContent.model)
        is PopupContentModel.LiveScore -> LiveScorePopup(model = popupContent.model)
        is PopupContentModel.Flashlight -> FlashlightPopup(model = popupContent.model)
        is PopupContentModel.Stopwatch -> StopwatchPopup(model = popupContent.model)
        is PopupContentModel.Alarm -> AlarmPopup(model = popupContent.model)
        is PopupContentModel.SystemEvent -> SystemEventPopup(model = popupContent)
        PopupContentModel.None ->
            when (viewModel.chipId) {
                is PopupChipId.AvControlsIndicator -> AvControlsChipPopup()
                is PopupChipId.ShareScreenPrivacyIndicator -> ShareScreenPrivacyIndicatorPopup()
                else -> Unit
            }
    }
}
