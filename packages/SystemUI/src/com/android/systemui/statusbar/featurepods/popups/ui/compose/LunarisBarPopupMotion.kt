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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.internal.util.lunaris.LunarisBarSettings
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private class OriginalPopupGeometry(origin: Rect, radius: Float, roundness: Float) {
    val width = LunarisBarOriginalSpring(origin.width)
    val height = LunarisBarOriginalSpring(origin.height)
    val centerX = LunarisBarOriginalSpring(origin.center.x)
    val top = LunarisBarOriginalSpring(origin.top)
    val radius = LunarisBarOriginalSpring(radius)
    val roundness = LunarisBarOriginalSpring(roundness)
}

@Composable
internal fun LunarisBarPopupMotion(
    isVisible: Boolean,
    chipBoundsInScreen: Rect?,
    topInsetPx: Int,
    onDismissRequest: () -> Unit,
    onHidden: () -> Unit,
    fastEnter: Boolean = false,
    useRedesignedMediaBackground: Boolean = false,
    allowOutsideInteraction: Boolean = false,
    notificationChipId: PopupChipId? = null,
    chipId: PopupChipId? = notificationChipId,
    compactOrigin: Boolean = false,
    onNotificationSwipeDismiss: () -> Unit = {},
    onNotificationTouchChanged: (Boolean) -> Unit = {},
    secondaryContent: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val state = LocalLunarisBarState.current
    val notification = notificationChipId != null
    val swipe =
        rememberNotificationPopupSwipe(
            notificationChipId,
            isVisible,
            onNotificationSwipeDismiss,
            onNotificationTouchChanged,
        )
    if (state.alternativeAnimation && !state.settings.isReducedMotion) {
        IslandPopupMorph(
            isVisible,
            chipBoundsInScreen,
            topInsetPx,
            onDismissRequest,
            onHidden,
            fastEnter,
            useRedesignedMediaBackground,
            allowOutsideInteraction,
            secondaryContent = secondaryContent,
            swipe = swipe,
            content = content,
        )
        return
    }
    val settings = state.settings
    val original = state.useOriginalMotion
    val originalPopup = LocalLunarisBarOriginalPopupState.current
    if (
        settings.animationStyle == LunarisBarSettings.ANIMATION_STYLE_COLOR_OS &&
            !settings.isReducedMotion
    ) {
        ColorOsPopupMotion(
            isVisible = isVisible,
            chipBoundsInScreen = chipBoundsInScreen,
            topInsetPx = topInsetPx,
            onDismissRequest = onDismissRequest,
            onHidden = onHidden,
            fastEnter = fastEnter,
            useRedesignedMediaBackground = useRedesignedMediaBackground,
            allowOutsideInteraction = allowOutsideInteraction,
            secondaryContent = secondaryContent,
            swipe = swipe,
            content = content,
        )
        return
    }
    val density = LocalDensity.current
    val view = LocalView.current
    val margin = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 8.dp.roundToPx() }
    val fallback = with(density) { 32.dp.toPx() }
    var screenOffset by remember { mutableStateOf<Offset?>(null) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val widthProgress = remember(notification) { Animatable(0f) }
    val heightProgress = remember(notification) { Animatable(0f) }
    val alpha = remember(notification) { Animatable(0f) }
    var originalGeometry by remember { mutableStateOf<OriginalPopupGeometry?>(null) }
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
    val ready =
        screenOffset != null &&
            contentSize.width > 0 &&
            contentSize.height > 0 &&
            (!notification || (chipBoundsInScreen != null && !chipBoundsInScreen.isEmpty))
    val originRadius =
        if (original && compactOrigin) origin.height / 2f
        else with(density) { minOf(settings.cornerRadius.dp.toPx(), origin.height / 2f) }
    val targetRadius =
        if (settings.expandedShape == 0)
            minOf(with(density) { 48.dp.toPx() }, minOf(contentSize.width, contentSize.height) / 2f)
        else with(density) { settings.cornerRadius.dp.toPx() }
    val originRoundness = if (compactOrigin) 1f else 0f
    val destination =
        Rect(targetLeft, targetTop, targetLeft + contentSize.width, targetTop + contentSize.height)
    LaunchedEffect(
        ready,
        isVisible,
        settings.animationSpeed,
        settings.animationStyle,
        settings.bounce,
        settings.isReducedMotion,
        fastEnter,
        notification,
        original,
        if (original) origin else null,
        if (original) destination else null,
        if (original) settings.expandedShape else null,
        if (original) settings.cornerRadius else null,
        if (original) compactOrigin else null,
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
        } else if (original) {
            val geometry =
                originalGeometry
                    ?: run {
                        val sx =
                            if (originalPopup.launchChipId == chipId) originalPopup.launchScaleX
                            else 1f
                        val sy =
                            if (originalPopup.launchChipId == chipId) originalPopup.launchScaleY
                            else 1f
                        val launchWidth = origin.width * sx
                        val launchHeight = origin.height * sy
                        val launchTop =
                            when (settings.scaleAlignment) {
                                1 -> origin.top
                                2 -> origin.bottom - launchHeight
                                else -> origin.center.y - launchHeight / 2f
                            }
                        val launchOrigin =
                            Rect(
                                origin.center.x - launchWidth / 2f,
                                launchTop,
                                origin.center.x + launchWidth / 2f,
                                launchTop + launchHeight,
                            )
                        originalPopup.launchChipId = null
                        OriginalPopupGeometry(launchOrigin, originRadius, originRoundness).also {
                            originalGeometry = it
                        }
                    }
            val widthSpec =
                lunarisBarOriginalSpring(
                    settings,
                    if (fastEnter) 900f else if (isVisible) 400f else 530f,
                    if (fastEnter) 1f else if (isVisible) 0.78f else 0.86f,
                    0.3f,
                )
            val heightSpec =
                lunarisBarOriginalSpring(
                    settings,
                    if (fastEnter) 900f else if (isVisible) 340f else 480f,
                    if (fastEnter) 1f else if (isVisible) 0.82f else 0.88f,
                    0.3f,
                )
            coroutineScope {
                launch {
                    geometry.width.animateTo(
                        if (isVisible) destination.width else origin.width,
                        widthSpec,
                    )
                }
                launch {
                    geometry.height.animateTo(
                        if (isVisible) destination.height else origin.height,
                        heightSpec,
                    )
                }
                launch {
                    geometry.centerX.animateTo(
                        if (isVisible) destination.center.x else origin.center.x,
                        lunarisBarOriginalSpring(settings, 460f, 1f, 0.3f),
                    )
                }
                launch {
                    geometry.top.animateTo(
                        if (isVisible) destination.top else origin.top,
                        lunarisBarOriginalSpring(settings, 420f, 1f, 0.3f),
                    )
                }
                launch {
                    geometry.radius.animateTo(
                        if (isVisible) targetRadius else originRadius,
                        lunarisBarOriginalSpring(settings, 430f, 1f, 0.1f),
                    )
                }
                launch {
                    geometry.roundness.animateTo(
                        if (isVisible) {
                            if (settings.expandedShape == 2) 0f else 1f
                        } else originRoundness,
                        lunarisBarOriginalSpring(settings, 430f, 1f),
                    )
                }
                launch {
                    alpha.animateTo(
                        target,
                        tween(
                            ((if (isVisible) 170 else 100) / settings.animationSpeed).roundToInt(),
                            delayMillis =
                                if (isVisible) (70 / settings.animationSpeed).roundToInt() else 0,
                        ),
                    )
                }
            }
        } else {
            if (notification && isVisible && widthProgress.value == 0f) withFrameNanos {}
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
    val followOffset = swipe?.translation ?: Offset.Zero
    val dragCollapse = swipe?.collapseFraction ?: 0f
    val currentGeometry by rememberUpdatedState(originalGeometry)
    val currentOrigin by rememberUpdatedState(origin)
    val currentDestination by rememberUpdatedState(destination)
    val originalWidth = remember {
        derivedStateOf {
            val difference = currentDestination.width - currentOrigin.width
            if (abs(difference) < 1f) {
                val heightDifference = currentDestination.height - currentOrigin.height
                if (abs(heightDifference) < 1f) {
                    if (visible) 1f else 0f
                } else
                    ((currentGeometry?.height?.value ?: currentOrigin.height) -
                        currentOrigin.height) / heightDifference
            } else
                ((currentGeometry?.width?.value ?: currentOrigin.width) - currentOrigin.width) /
                    difference
        }
    }
    val originalHeight = remember {
        derivedStateOf {
            val difference = currentDestination.height - currentOrigin.height
            if (abs(difference) < 1f) originalWidth.value
            else
                ((currentGeometry?.height?.value ?: currentOrigin.height) - currentOrigin.height) /
                    difference
        }
    }
    val handoffFraction =
        (with(density) { 3.dp.toPx() } /
                maxOf(
                    abs(destination.width - origin.width),
                    abs(destination.height - origin.height),
                    1f,
                ))
            .coerceIn(0.005f, 0.12f)
    SideEffect {
        if (original) {
            val previousReturn =
                if (originalPopup.chipId == chipId && !originalPopup.visible)
                    originalPopup.returnProgress
                else 0f
            originalPopup.chipId = chipId
            originalPopup.visible = isVisible
            originalPopup.width = originalWidth
            originalPopup.height = originalHeight
            originalPopup.handoffFraction = handoffFraction
            originalPopup.returnProgress =
                if (isVisible) 0f
                else
                    maxOf(
                        previousReturn,
                        lunarisBarOriginalReturnFraction(
                            originalWidth.value,
                            originalHeight.value,
                            handoffFraction,
                        ),
                    )
        }
    }
    DisposableEffect(originalPopup, originalWidth) {
        onDispose {
            if (originalPopup.width === originalWidth) {
                originalPopup.chipId = null
                originalPopup.width = null
                originalPopup.height = null
                originalPopup.visible = false
                originalPopup.returnProgress = 0f
            }
        }
    }
    val widthExpansion = if (original) originalWidth.value else widthProgress.value
    val heightExpansion = if (original) originalHeight.value else heightProgress.value
    val widthFraction = widthExpansion * (1f - dragCollapse)
    val heightFraction = heightExpansion * (1f - dragCollapse)
    val contentAlpha =
        if (notification || original) {
            alpha.value * ((minOf(widthFraction, heightFraction) - 0.48f) / 0.4f).coerceIn(0f, 1f)
        } else alpha.value
    val surfaceAlpha =
        when {
            !ready -> 0f
            original && !isVisible ->
                1f -
                    maxOf(
                        originalPopup.returnProgress,
                        lunarisBarOriginalReturnFraction(
                            widthExpansion,
                            heightExpansion,
                            handoffFraction,
                        ),
                    )
            original -> if (notification) (widthExpansion / 0.16f).coerceIn(0f, 1f) else 1f
            !notification -> 1f
            isVisible -> (widthProgress.value / 0.16f).coerceIn(0f, 1f)
            else -> ((widthProgress.value - 0.16f) / 0.32f).coerceIn(0f, 1f)
        }
    val w =
        (if (original && originalGeometry != null)
                origin.width + (originalGeometry!!.width.value - origin.width) * (1f - dragCollapse)
            else origin.width + (contentSize.width - origin.width) * widthFraction)
            .coerceIn(1f, rootSize.width.coerceAtLeast(1).toFloat())
    val h =
        (if (original && originalGeometry != null)
                origin.height +
                    (originalGeometry!!.height.value - origin.height) * (1f - dragCollapse)
            else origin.height + (contentSize.height - origin.height) * heightFraction)
            .coerceIn(1f, (rootSize.height - targetTop).coerceAtLeast(1f))
    val centerX =
        if (original && originalGeometry != null) originalGeometry!!.centerX.value
        else
            origin.center.x +
                (targetLeft + contentSize.width / 2f - origin.center.x) *
                    widthFraction.coerceIn(0f, 1f)
    val x =
        lunarisBarPopupLeft(centerX, w, rootSize.width.toFloat(), 0f) +
            followOffset.x * widthExpansion.coerceIn(0f, 1f)
    val baseY =
        (if (original && originalGeometry != null) originalGeometry!!.top.value
            else origin.top + (targetTop - origin.top) * heightFraction.coerceIn(0f, 1f))
            .coerceIn(0f, (rootSize.height - h).coerceAtLeast(0f))
    val y =
        if (notification && followOffset.y < 0f) {
            (baseY + followOffset.y * widthExpansion.coerceIn(0f, 1f)).coerceAtLeast(
                origin.center.y - h / 2f
            )
        } else baseY
    val frame = Rect(x, y, x + w, y + h)
    val currentFrame by rememberUpdatedState(frame)
    var secondaryBounds by remember { mutableStateOf(Rect.Zero) }
    val currentSecondary by rememberUpdatedState(secondaryBounds)
    LunarisBarPopupTouchRegion(
        enabled = allowOutsideInteraction,
        bounds = if (isVisible && ready) frame else Rect.Zero,
        secondaryBounds = if (isVisible) secondaryBounds else Rect.Zero,
    )
    val radius =
        with(density) {
            (if (original && originalGeometry != null) originalGeometry!!.radius.value
                else originRadius + (targetRadius - originRadius) * heightFraction.coerceIn(0f, 1f))
                .toDp()
        }
    val shape =
        if (original)
            LunarisBarOriginalShape(radius, originalGeometry?.roundness?.value ?: originRoundness)
        else if (settings.expandedShape == 2) LunarisBarSquircle(radius)
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
                .pointerInput(allowOutsideInteraction) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            for (change in event.changes) {
                                if (
                                    !allowOutsideInteraction &&
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
                }
                .notificationPopupSwipe(swipe, isVisible, frame),
        content = {
            LunarisBarSurface(
                shape = shape,
                blurAlpha = if (notification) surfaceAlpha else 1f,
                background =
                    if (useRedesignedMediaBackground) {
                        { RedesignedMediaPopupBackground(shape) }
                    } else null,
            ) {}
            Box(if (contentAlpha < 0.5f) Modifier.clearAndSetSemantics {} else Modifier) {
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
                if (original) x + w / 2f else targetLeft + card.width / 2f,
                secondary.width.toFloat(),
                constraints.maxWidth.toFloat(),
                margin.toFloat(),
            )
        val secondaryTop = if (original) y + h + gap else targetTop + card.height + gap
        secondaryBounds =
            Rect(
                secondaryLeft,
                secondaryTop,
                secondaryLeft + secondary.width,
                secondaryTop + secondary.height,
            )
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (surfaceAlpha > 0f) {
                background.placeWithLayer(x.roundToInt(), y.roundToInt()) {
                    this.alpha = surfaceAlpha
                    if (original) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = x - x.roundToInt()
                        translationY = y - y.roundToInt()
                        scaleX = w / background.width.coerceAtLeast(1)
                        scaleY = h / background.height.coerceAtLeast(1)
                    }
                }
            }
            card.placeWithLayer(x.roundToInt(), y.roundToInt()) {
                transformOrigin = TransformOrigin(0f, 0f)
                if (original) {
                    translationX = x - x.roundToInt()
                    translationY = y - y.roundToInt()
                }
                this.alpha = contentAlpha
                if (notification && !original) {
                    this.shape =
                        object : Shape {
                            override fun createOutline(
                                size: Size,
                                layoutDirection: LayoutDirection,
                                density: Density,
                            ): Outline = shape.createOutline(Size(w, h), layoutDirection, density)
                        }
                } else {
                    scaleX = w / card.width.coerceAtLeast(1)
                    scaleY = h / card.height.coerceAtLeast(1)
                    this.shape = lunarisBarScaledShape(shape, w, h)
                }
                clip = true
            }
            secondary.placeWithLayer(secondaryLeft.roundToInt(), secondaryTop.roundToInt()) {
                this.alpha = contentAlpha
                if (original) {
                    translationX = secondaryLeft - secondaryLeft.roundToInt()
                    translationY = secondaryTop - secondaryTop.roundToInt()
                }
            }
        }
    }
}
