/*
 * Copyright (C) 2026 The Android Open Source Project
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private class IslandPopupGeometry(origin: Rect) {
    val width = IslandMotionSpring(origin.height * 1.8f)
    val height = IslandMotionSpring(origin.height * 0.8f)
    val centerX = IslandMotionSpring(origin.center.x)
    val centerY = IslandMotionSpring(origin.center.y + origin.height * 0.15f)
    val pillWidth = IslandMotionSpring(origin.width)
    val pillHeight = IslandMotionSpring(origin.height)

    val bounds: Rect
        get() {
            val halfWidth = width.value.coerceAtLeast(1f) / 2f
            val halfHeight = height.value.coerceAtLeast(1f) / 2f
            return Rect(
                centerX.value - halfWidth,
                centerY.value - halfHeight,
                centerX.value + halfWidth,
                centerY.value + halfHeight,
            )
        }
}

private fun Path.islandNeck(
    firstCenter: Offset,
    firstRadius: Float,
    secondCenter: Offset,
    secondRadius: Float,
    spread: Float,
): Boolean {
    reset()
    val delta = secondCenter - firstCenter
    val distance = delta.getDistance()
    if (
        firstRadius <= 0f ||
            secondRadius <= 0f ||
            spread <= 0.01f ||
            distance <= abs(firstRadius - secondRadius) + 0.5f
    )
        return false
    val total = firstRadius + secondRadius
    val firstIntersection: Float
    val secondIntersection: Float
    if (distance < total) {
        firstIntersection =
            acos(
                ((firstRadius * firstRadius + distance * distance - secondRadius * secondRadius) /
                        (2f * firstRadius * distance))
                    .coerceIn(-1f, 1f)
            )
        secondIntersection =
            acos(
                ((secondRadius * secondRadius + distance * distance - firstRadius * firstRadius) /
                        (2f * secondRadius * distance))
                    .coerceIn(-1f, 1f)
            )
    } else {
        firstIntersection = 0f
        secondIntersection = 0f
    }
    val between = atan2(delta.y, delta.x)
    val maxSpread = acos(((firstRadius - secondRadius) / distance).coerceIn(-1f, 1f))
    val pi = PI.toFloat()
    val a1 = between + firstIntersection + (maxSpread - firstIntersection) * spread
    val a2 = between - firstIntersection - (maxSpread - firstIntersection) * spread
    val a3 = between + pi - secondIntersection - (pi - secondIntersection - maxSpread) * spread
    val a4 = between - pi + secondIntersection + (pi - secondIntersection - maxSpread) * spread
    fun polar(center: Offset, radius: Float, angle: Float) =
        center + Offset(cos(angle) * radius, sin(angle) * radius)
    val p1 = polar(firstCenter, firstRadius, a1)
    val p2 = polar(firstCenter, firstRadius, a2)
    val p3 = polar(secondCenter, secondRadius, a3)
    val p4 = polar(secondCenter, secondRadius, a4)
    val handle =
        minOf(spread * 2.4f, (p3 - p1).getDistance() / total) * minOf(1f, distance * 2f / total)
    val h1 = polar(p1, firstRadius * handle, a1 - pi / 2f)
    val h2 = polar(p2, firstRadius * handle, a2 + pi / 2f)
    val h3 = polar(p3, secondRadius * handle, a3 + pi / 2f)
    val h4 = polar(p4, secondRadius * handle, a4 - pi / 2f)
    moveTo(p1.x, p1.y)
    cubicTo(h1.x, h1.y, h3.x, h3.y, p3.x, p3.y)
    lineTo(p4.x, p4.y)
    cubicTo(h4.x, h4.y, h2.x, h2.y, p2.x, p2.y)
    close()
    return true
}

@Composable
internal fun IslandPopupMorph(
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
    val settings = LocalLunarisBarState.current.settings
    val popupShape = lunarisBarExpandedShape(settings)
    val density = LocalDensity.current
    val view = LocalView.current
    val margin = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 8.dp.toPx() }
    val returnDistance = with(density) { 4.dp.toPx() }
    val neckDistance = with(density) { 26.dp.toPx() }
    val neckRadius = with(density) { 24.dp.toPx() }
    val fallbackHeight = with(density) { 32.dp.toPx() }
    val restDiameter = islandRestDiameterPx()
    val neck = remember { Path() }
    val screenLocation = remember { IntArray(2) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var secondarySize by remember { mutableStateOf(IntSize.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var screenOffset by remember { mutableStateOf<Offset?>(null) }
    var motion by remember { mutableStateOf<IslandPopupGeometry?>(null) }
    val contentFade = remember { Animatable(0f) }
    val revealMillis = remember { Animatable(0f) }
    val currentOnHidden by rememberUpdatedState(onHidden)
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentVisible by rememberUpdatedState(isVisible)
    val origin =
        chipBoundsInScreen
            ?.takeIf { it.width > 0f && it.height > 0f }
            ?.translate(-(screenOffset ?: Offset.Zero))
            ?: Rect(
                rootSize.width / 2f - fallbackHeight,
                (topInsetPx - gap - fallbackHeight).coerceAtLeast(0f),
                rootSize.width / 2f + fallbackHeight,
                topInsetPx - gap,
            )
    val targetTop =
        (if (settings.expandedMode == 0) origin.top
            else maxOf(origin.bottom + gap, topInsetPx.toFloat()))
            .coerceAtLeast(0f)
    val targetLeft =
        lunarisBarPopupLeft(
            origin.center.x,
            contentSize.width.toFloat(),
            rootSize.width.toFloat(),
            margin.toFloat(),
        )
    val destination =
        Rect(targetLeft, targetTop, targetLeft + contentSize.width, targetTop + contentSize.height)
    val ready = screenOffset != null && contentSize.width > 0 && contentSize.height > 0
    val currentOrigin by rememberUpdatedState(origin)

    LaunchedEffect(ready) { if (ready && motion == null) motion = IslandPopupGeometry(origin) }

    LaunchedEffect(motion, isVisible, origin, destination, fastEnter) {
        val geometry = motion ?: return@LaunchedEffect
        coroutineScope {
            launch {
                geometry.width.animateTo(
                    if (isVisible) destination.width else origin.height * 1.6f,
                    when {
                        !isVisible -> DynamicIslandMotion.CloseWidth
                        fastEnter -> DynamicIslandMotion.FastOpen
                        else -> DynamicIslandMotion.OpenWidth
                    },
                )
            }
            launch {
                geometry.height.animateTo(
                    if (isVisible) destination.height else origin.height * 0.8f,
                    when {
                        !isVisible -> DynamicIslandMotion.CloseHeight
                        fastEnter -> DynamicIslandMotion.FastOpen
                        else -> DynamicIslandMotion.OpenHeight
                    },
                )
            }
            launch {
                geometry.centerX.animateTo(
                    if (isVisible) destination.center.x else origin.center.x,
                    when {
                        !isVisible -> DynamicIslandMotion.ClosePosition
                        fastEnter -> DynamicIslandMotion.FastOpen
                        else -> DynamicIslandMotion.OpenPosition
                    },
                )
            }
            launch {
                geometry.centerY.animateTo(
                    if (isVisible) destination.center.y else origin.center.y,
                    when {
                        !isVisible -> DynamicIslandMotion.ClosePosition
                        fastEnter -> DynamicIslandMotion.FastOpen
                        else -> DynamicIslandMotion.OpenPosition
                    },
                )
            }
        }
    }

    LaunchedEffect(motion, isVisible, origin, restDiameter) {
        val geometry = motion ?: return@LaunchedEffect
        val restingSize =
            (restDiameter ?: (origin.height * 0.45f)).coerceAtMost(
                minOf(origin.width, origin.height)
            )
        coroutineScope {
            launch {
                geometry.pillWidth.animateTo(
                    if (isVisible) restingSize else origin.width,
                    if (isVisible) DynamicIslandMotion.PillShrink
                    else DynamicIslandMotion.springFor(0.38f, 0.70f, 0.4f),
                )
            }
            launch {
                geometry.pillHeight.animateTo(
                    if (isVisible) restingSize else origin.height,
                    if (isVisible) DynamicIslandMotion.PillShrink
                    else DynamicIslandMotion.springFor(0.34f, 0.75f, 0.4f),
                )
            }
        }
    }

    LaunchedEffect(isVisible, motion, screenOffset != null, fastEnter) {
        val geometry = motion
        if (geometry == null) {
            if (!isVisible && screenOffset != null && !ready) currentOnHidden()
            return@LaunchedEffect
        }
        if (isVisible) {
            coroutineScope {
                launch {
                    contentFade.animateTo(
                        1f,
                        tween(
                            if (fastEnter) DynamicIslandMotion.FastContentEnterMillis
                            else DynamicIslandMotion.ContentEnterMillis,
                            delayMillis =
                                if (!fastEnter && contentFade.value == 0f)
                                    DynamicIslandMotion.ContentDelayMillis
                                else 0,
                            easing = LinearEasing,
                        ),
                    )
                }
                launch {
                    revealMillis.animateTo(
                        260f,
                        tween(
                            if (fastEnter) DynamicIslandMotion.FastContentEnterMillis else 260,
                            delayMillis =
                                if (!fastEnter && revealMillis.value == 0f)
                                    DynamicIslandMotion.ContentDelayMillis
                                else 0,
                            easing = LinearEasing,
                        ),
                    )
                }
            }
        } else {
            contentFade.animateTo(
                0f,
                tween(DynamicIslandMotion.ContentExitMillis, easing = DynamicIslandMotion.EaseInOut),
            )
            snapshotFlow {
                    val frame = geometry.bounds
                    (frame.center - currentOrigin.center).getDistance() < returnDistance &&
                        frame.height < currentOrigin.height * 0.95f
                }
                .first { it }
            if (!currentVisible) currentOnHidden()
        }
    }

    val geometry = motion
    val rawFrame = geometry?.bounds ?: Rect.Zero
    val frameWidth = rawFrame.width.coerceIn(0f, rootSize.width.toFloat())
    val frameHeight = rawFrame.height.coerceIn(0f, rootSize.height.toFloat())
    val frameLeft = lunarisBarPopupLeft(rawFrame.center.x, frameWidth, rootSize.width.toFloat(), 0f)
    val frameTop = rawFrame.top.coerceIn(0f, (rootSize.height - frameHeight).coerceAtLeast(0f))
    val frame = Rect(frameLeft, frameTop, frameLeft + frameWidth, frameTop + frameHeight)
    val expansion =
        ((frame.height - origin.height) / (destination.height - origin.height).coerceAtLeast(1f))
            .coerceIn(0f, 1f)
    // Unlock feedback should appear during expansion, without waiting for the card to settle.
    val bodyAlpha =
        contentFade.value *
            if (isVisible && !fastEnter) DynamicIslandMotion.contentReveal(expansion) else 1f
    val currentBodyAlpha by rememberUpdatedState(bodyAlpha)
    val currentBounds by rememberUpdatedState(frame)
    val secondaryLeft =
        lunarisBarPopupLeft(
            frame.center.x,
            secondarySize.width.toFloat(),
            rootSize.width.toFloat(),
            margin.toFloat(),
        )
    val secondaryBounds =
        Rect(
            secondaryLeft,
            frame.bottom + gap,
            secondaryLeft + secondarySize.width,
            frame.bottom + gap + secondarySize.height,
        )
    val currentSecondaryBounds by rememberUpdatedState(secondaryBounds)
    val surfaceReveal = ((expansion - 0.82f) / 0.18f).coerceIn(0f, 1f)
    val contentClip =
        object : Shape {
            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Outline {
                val left = (size.width - frame.width) / 2f
                val outline =
                    popupShape.createOutline(
                        Size(frame.width, frame.height),
                        layoutDirection,
                        density,
                    )
                return Outline.Generic(
                    Path().apply {
                        when (outline) {
                            is Outline.Generic -> addPath(outline.path)
                            is Outline.Rounded -> addRoundRect(outline.roundRect)
                            is Outline.Rectangle -> addRect(outline.rect)
                        }
                        translate(Offset(left, 0f))
                    }
                )
            }
        }

    Layout(
        modifier =
            Modifier.onGloballyPositioned {
                    view.getLocationOnScreen(screenLocation)
                    screenOffset = Offset(screenLocation[0].toFloat(), screenLocation[1].toFloat())
                }
                .drawBehind {
                    if (geometry != null) {
                        val pillBottom = origin.center.y + geometry.pillHeight.value / 2f
                        val spread =
                            0.6f *
                                (1f - expansion) *
                                (1f - ((frame.top - pillBottom) / neckDistance).coerceIn(0f, 1f))
                        val radius = minOf(neckRadius, frame.width / 2f, frame.height / 2f)
                        val pillRadius =
                            minOf(
                                origin.height / 2f,
                                maxOf(geometry.pillWidth.value, geometry.pillHeight.value) / 2f,
                            )
                        if (
                            spread > 0.02f &&
                                neck.islandNeck(
                                    origin.center,
                                    pillRadius,
                                    Offset(frame.center.x, frame.top + radius),
                                    radius,
                                    spread,
                                )
                        ) {
                            drawPath(neck, Color.Black)
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { change ->
                                if (
                                    change.changedToDownIgnoreConsumed() &&
                                        !currentBounds.contains(change.position) &&
                                        !currentSecondaryBounds.contains(change.position)
                                ) {
                                    change.consume()
                                    currentOnDismiss()
                                }
                                if (!currentVisible || currentBodyAlpha < 0.5f) change.consume()
                            }
                        }
                    }
                },
        content = {
            Box(
                Modifier.drawWithContent {
                    drawContent()
                    if (geometry != null) {
                        drawOutline(
                            popupShape.createOutline(size, layoutDirection, this),
                            Color(0xFF0E0E10),
                            alpha = 1f - surfaceReveal,
                        )
                    }
                },
                propagateMinConstraints = true,
            ) {
                if (geometry != null) {
                    CompositionLocalProvider(LocalIslandPopupRevealMillis provides 260f) {
                        LunarisBarSurface(
                            shape = popupShape,
                            background =
                                if (useRedesignedMediaBackground) {
                                    { RedesignedMediaPopupBackground(popupShape) }
                                } else null,
                        ) {}
                    }
                }
            }
            Box(
                Modifier.graphicsLayer {
                        alpha = if (geometry != null) bodyAlpha else 0f
                        shape = contentClip
                        clip = true
                    }
                    .then(if (bodyAlpha < 0.5f) Modifier.clearAndSetSemantics {} else Modifier)
            ) {
                CompositionLocalProvider(
                    LocalIslandPopupRevealMillis provides
                        if (isVisible) revealMillis.value else Float.POSITIVE_INFINITY,
                    LocalIslandPopupContentOnly provides true,
                ) {
                    content()
                }
            }
            Box(
                Modifier.graphicsLayer { alpha = if (geometry != null) bodyAlpha else 0f }
                    .then(if (bodyAlpha < 0.5f) Modifier.clearAndSetSemantics {} else Modifier)
            ) {
                CompositionLocalProvider(
                    LocalIslandPopupRevealMillis provides
                        if (isVisible) revealMillis.value else Float.POSITIVE_INFINITY,
                    LocalIslandPopupContentOnly provides false,
                ) {
                    secondaryContent()
                }
            }
        },
    ) { measurables, constraints ->
        val availableWidth = (constraints.maxWidth - margin * 2).coerceAtLeast(1)
        val maxWidth =
            if (settings.isFullWidth) availableWidth
            else minOf(availableWidth, with(density) { 360.dp.roundToPx() })
        val top = targetTop.roundToInt().coerceAtLeast(0)
        val maxHeight = (constraints.maxHeight - top - margin).coerceAtLeast(0)
        val card =
            measurables[1].measure(
                Constraints(
                    minWidth = if (settings.isFullWidth) maxWidth else 0,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                )
            )
        val secondary =
            measurables[2].measure(
                Constraints(
                    maxWidth = maxWidth,
                    maxHeight = (maxHeight - card.height - gap.roundToInt()).coerceAtLeast(0),
                )
            )
        secondarySize = IntSize(secondary.width, secondary.height)
        val background =
            measurables[0].measure(
                Constraints.fixed(
                    frame.width.roundToInt().coerceAtLeast(1),
                    frame.height.roundToInt().coerceAtLeast(1),
                )
            )
        contentSize = IntSize(card.width, card.height)
        val secondaryHeight = if (secondary.height > 0) secondary.height + gap.roundToInt() else 0
        val windowHeight = constraints.constrainHeight(top + card.height + secondaryHeight + margin)
        rootSize = IntSize(constraints.maxWidth, windowHeight)
        layout(rootSize.width, windowHeight) {
            background.place(frame.left.roundToInt(), frame.top.roundToInt())
            card.place((frame.center.x - card.width / 2f).roundToInt(), frame.top.roundToInt())
            secondary.place(secondaryLeft.roundToInt(), (frame.bottom + gap).roundToInt())
        }
    }
}
