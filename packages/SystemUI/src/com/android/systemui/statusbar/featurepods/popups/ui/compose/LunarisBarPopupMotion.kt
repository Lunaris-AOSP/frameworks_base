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

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun LunarisBarPopupMotion(
    isVisible: Boolean,
    chipBoundsInScreen: Rect?,
    topInsetPx: Int,
    onDismissRequest: () -> Unit,
    onHidden: () -> Unit,
    fastEnter: Boolean = false,
    useRedesignedMediaBackground: Boolean = false,
    secondaryContent: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val state = LocalLunarisBarState.current
    if (state.alternativeAnimation && !state.settings.isReducedMotion) {
        IslandPopupMorph(
            isVisible,
            chipBoundsInScreen,
            topInsetPx,
            onDismissRequest,
            onHidden,
            fastEnter,
            useRedesignedMediaBackground,
            secondaryContent,
            content,
        )
        return
    }
    val settings = state.settings
    val density = LocalDensity.current
    val view = LocalView.current
    val margin = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 8.dp.roundToPx() }
    val fallback = with(density) { 32.dp.toPx() }
    var screenOffset by remember { mutableStateOf<Offset?>(null) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val widthProgress = remember { Animatable(0f) }
    val heightProgress = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val currentHidden by rememberUpdatedState(onHidden)
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val visible by rememberUpdatedState(isVisible)
    val origin =
        chipBoundsInScreen?.translate(-(screenOffset ?: Offset.Zero))
            ?: Rect(
                rootSize.width / 2f - fallback,
                (topInsetPx - fallback).coerceAtLeast(0f),
                rootSize.width / 2f + fallback,
                topInsetPx.toFloat(),
            )
    val targetTop =
        (if (settings.expandedMode == 0) origin.top
            else maxOf(origin.bottom + gap, topInsetPx.toFloat()))
            .coerceIn(0f, (rootSize.height - margin).coerceAtLeast(0).toFloat())
    val targetLeft =
        lunarisBarPopupLeft(
            origin.center.x,
            contentSize.width.toFloat(),
            rootSize.width.toFloat(),
            margin.toFloat(),
        )
    val ready = screenOffset != null && contentSize.width > 0 && contentSize.height > 0
    LaunchedEffect(
        ready,
        isVisible,
        settings.animationSpeed,
        settings.animationStyle,
        settings.bounce,
        settings.isReducedMotion,
        fastEnter,
    ) {
        if (!ready) {
            if (!isVisible) currentHidden()
            return@LaunchedEffect
        }
        val target = if (isVisible) 1f else 0f
        if (settings.isReducedMotion) {
            widthProgress.snapTo(target)
            heightProgress.snapTo(target)
            alpha.snapTo(target)
        } else {
            fun duration(value: Int) = (value / settings.animationSpeed).roundToInt()
            val noBounce = settings.bounce == 0 || fastEnter
            val easing =
                if (isVisible) CubicBezierEasing(0.2f, 0.15f, 0f, 1f)
                else CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
            val mainDuration = duration(if (fastEnter) 180 else if (isVisible) 360 else 260)
            coroutineScope {
                launch {
                    if (settings.animationStyle == 0) {
                        widthProgress.animateTo(
                            target,
                            spring(
                                dampingRatio =
                                    if (!isVisible || noBounce) 1f
                                    else if (settings.bounce == 2) 0.76f else 0.86f,
                                stiffness =
                                    (if (fastEnter) 900f else 400f) *
                                        settings.animationSpeed *
                                        settings.animationSpeed,
                                visibilityThreshold = 0.001f,
                            ),
                        )
                    } else widthProgress.animateTo(target, tween(mainDuration, easing = easing))
                }
                launch {
                    if (settings.animationStyle == 0) {
                        heightProgress.animateTo(
                            target,
                            spring(
                                dampingRatio =
                                    if (!isVisible || noBounce) 1f
                                    else if (settings.bounce == 2) 0.8f else 0.9f,
                                stiffness =
                                    (if (fastEnter) 900f else 340f) *
                                        settings.animationSpeed *
                                        settings.animationSpeed,
                                visibilityThreshold = 0.001f,
                            ),
                        )
                    } else
                        heightProgress.animateTo(
                            target,
                            tween(
                                mainDuration,
                                delayMillis = if (isVisible) duration(60) else 0,
                                easing = easing,
                            ),
                        )
                }
                launch {
                    alpha.animateTo(
                        target,
                        tween(
                            duration(if (isVisible) 170 else 120),
                            delayMillis = if (isVisible) duration(100) else 0,
                        ),
                    )
                }
            }
        }
        if (!visible) currentHidden()
    }
    val w =
        (origin.width + (contentSize.width - origin.width) * widthProgress.value).coerceIn(
            1f,
            rootSize.width.coerceAtLeast(1).toFloat(),
        )
    val h =
        (origin.height + (contentSize.height - origin.height) * heightProgress.value).coerceIn(
            1f,
            (rootSize.height - targetTop).coerceAtLeast(1f),
        )
    val centerX =
        origin.center.x +
            (targetLeft + contentSize.width / 2f - origin.center.x) *
                widthProgress.value.coerceIn(0f, 1f)
    val x = lunarisBarPopupLeft(centerX, w, rootSize.width.toFloat(), 0f)
    val y =
        (origin.top + (targetTop - origin.top) * heightProgress.value.coerceIn(0f, 1f)).coerceIn(
            0f,
            (rootSize.height - h).coerceAtLeast(0f),
        )
    val frame = Rect(x, y, x + w, y + h)
    val currentFrame by rememberUpdatedState(frame)
    var secondaryBounds by remember { mutableStateOf(Rect.Zero) }
    val currentSecondary by rememberUpdatedState(secondaryBounds)
    val originRadius = with(density) { minOf(settings.cornerRadius.dp.toPx(), origin.height / 2f) }
    val targetRadius =
        if (settings.expandedShape == 0)
            minOf(with(density) { 48.dp.toPx() }, minOf(contentSize.width, contentSize.height) / 2f)
        else with(density) { settings.cornerRadius.dp.toPx() }
    val radius =
        with(density) {
            (originRadius + (targetRadius - originRadius) * heightProgress.value.coerceIn(0f, 1f))
                .toDp()
        }
    val shape =
        if (settings.expandedShape == 2) LunarisBarSquircle(radius)
        else androidx.compose.foundation.shape.RoundedCornerShape(radius)
    Layout(
        modifier =
            Modifier.fillMaxSize()
                .onGloballyPositioned {
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    screenOffset = Offset(location[0].toFloat(), location[1].toFloat())
                    rootSize = it.size
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            for (change in event.changes) {
                                if (
                                    change.changedToDownIgnoreConsumed() &&
                                        !currentFrame.contains(change.position) &&
                                        !currentSecondary.contains(change.position)
                                ) {
                                    change.consume()
                                    currentDismiss()
                                }
                                if (!visible) change.consume()
                            }
                        }
                    }
                },
        content = {
            LunarisBarSurface(
                shape = shape,
                background =
                    if (useRedesignedMediaBackground) {
                        { RedesignedMediaPopupBackground(shape) }
                    } else null,
            ) {}
            Box(if (alpha.value < 0.5f) Modifier.clearAndSetSemantics {} else Modifier) {
                CompositionLocalProvider(
                    LocalIslandPopupContentOnly provides true,
                    LocalIslandPopupRevealMillis provides Float.POSITIVE_INFINITY,
                ) {
                    content()
                }
            }
            Box {
                CompositionLocalProvider(
                    LocalIslandPopupContentOnly provides false,
                    LocalIslandPopupRevealMillis provides Float.POSITIVE_INFINITY,
                ) {
                    secondaryContent()
                }
            }
        },
    ) { measurables, constraints ->
        val availableWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(1)
        val maxWidth =
            if (settings.isFullWidth) availableWidth
            else minOf(availableWidth, with(density) { 360.dp.roundToPx() })
        val maxHeight = (constraints.maxHeight - targetTop.roundToInt() - margin).coerceAtLeast(1)
        val card =
            measurables[1].measure(
                Constraints(
                    minWidth = if (settings.isFullWidth) maxWidth else 0,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                )
            )
        contentSize = IntSize(card.width, card.height)
        val secondary =
            measurables[2].measure(
                Constraints(
                    maxWidth = maxWidth,
                    maxHeight = (maxHeight - card.height - gap).coerceAtLeast(0),
                )
            )
        val background =
            measurables[0].measure(
                Constraints.fixed(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
            )
        val secondaryLeft =
            lunarisBarPopupLeft(
                targetLeft + card.width / 2f,
                secondary.width.toFloat(),
                constraints.maxWidth.toFloat(),
                margin.toFloat(),
            )
        secondaryBounds =
            Rect(
                secondaryLeft,
                targetTop + card.height + gap,
                secondaryLeft + secondary.width,
                targetTop + card.height + gap + secondary.height,
            )
        layout(constraints.maxWidth, constraints.maxHeight) {
            background.placeWithLayer(x.roundToInt(), y.roundToInt()) {
                this.alpha = if (ready) 1f else 0f
            }
            card.placeWithLayer(x.roundToInt(), y.roundToInt()) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = w / card.width.coerceAtLeast(1)
                scaleY = h / card.height.coerceAtLeast(1)
                this.alpha = alpha.value
                this.shape = lunarisBarScaledShape(shape, w, h)
                clip = true
            }
            secondary.placeWithLayer(
                secondaryLeft.roundToInt(),
                (targetTop + card.height + gap).roundToInt(),
            ) {
                this.alpha = alpha.value
            }
        }
    }
}
