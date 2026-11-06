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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
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
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun ColorOsPopupMotion(
    isVisible: Boolean,
    chipBoundsInScreen: Rect?,
    topInsetPx: Int,
    onDismissRequest: () -> Unit,
    onHidden: () -> Unit,
    fastEnter: Boolean,
    useRedesignedMediaBackground: Boolean,
    allowOutsideInteraction: Boolean,
    secondaryContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val settings = LocalLunarisBarState.current.settings
    val density = LocalDensity.current
    val view = LocalView.current
    val margin = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 8.dp.roundToPx() }
    val fallback = with(density) { 32.dp.toPx() }
    val screenLocation = remember(view) { IntArray(2) }
    var screenOffset by remember { mutableStateOf<Offset?>(null) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var secondaryBounds by remember { mutableStateOf(Rect.Zero) }
    val position = remember { Animatable(0f) }
    val scale = remember { Animatable(0.4f) }
    val reveal = remember { Animatable(0f) }
    val veil = remember { Animatable(1f) }
    val alpha = remember { Animatable(0f) }
    val currentHidden by rememberUpdatedState(onHidden)
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val visible by rememberUpdatedState(isVisible)
    val origin =
        chipBoundsInScreen?.translate(-(screenOffset ?: Offset.Zero))
            ?: Rect(
                rootSize.width / 2f - fallback,
                (topInsetPx - gap - fallback).coerceAtLeast(0f),
                rootSize.width / 2f + fallback,
                (topInsetPx - gap).toFloat(),
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
    val destination =
        Rect(targetLeft, targetTop, targetLeft + contentSize.width, targetTop + contentSize.height)
    val ready = screenOffset != null && contentSize.width > 0 && contentSize.height > 0

    LaunchedEffect(ready, isVisible, settings.animationSpeed, settings.bounce, fastEnter) {
        if (!ready) {
            if (!isVisible) currentHidden()
            return@LaunchedEffect
        }
        fun duration(value: Int) = (value / settings.animationSpeed).roundToInt()
        if (isVisible) {
            alpha.snapTo(1f)
            val growMillis = duration(if (fastEnter) 180 else 300)
            val overshoot =
                if (fastEnter || settings.bounce == 0) 1f
                else if (settings.bounce == 2) 1.06f else 1.03f
            coroutineScope {
                launch { position.animateTo(1f, tween(growMillis, easing = ColorOsCardGrow)) }
                launch { reveal.animateTo(1f, tween(growMillis, easing = ColorOsCardGrow)) }
                launch {
                    veil.animateTo(
                        0f,
                        tween(
                            duration(if (fastEnter) 120 else 200),
                            delayMillis = duration(if (fastEnter) 0 else 100),
                            easing = ColorOsCardEase,
                        ),
                    )
                }
                launch {
                    scale.animateTo(overshoot, tween(growMillis, easing = ColorOsCardGrow))
                    if (overshoot > 1f) {
                        scale.animateTo(1f, tween(duration(300), easing = ColorOsCardEase))
                    }
                }
            }
        } else {
            val closeMillis = duration(400)
            val fadeMillis = duration(50)
            coroutineScope {
                launch { position.animateTo(0f, tween(closeMillis, easing = ColorOsCardMove)) }
                launch { scale.animateTo(0.4f, tween(closeMillis, easing = ColorOsCardEase)) }
                launch { reveal.animateTo(0f, tween(closeMillis, easing = ColorOsCardEase)) }
                launch { veil.animateTo(1f, tween(duration(200), easing = ColorOsCardEase)) }
                launch {
                    alpha.animateTo(0f, tween(fadeMillis, delayMillis = closeMillis - fadeMillis))
                }
            }
            if (!visible) currentHidden()
        }
    }

    val popupShape =
        remember(settings.expandedShape, settings.cornerRadius) {
            lunarisBarExpandedShape(settings)
        }
    val revealShape = ColorOsRevealShape(reveal.value, popupShape)
    val revealBounds =
        revealShape.bounds(Size(contentSize.width.toFloat(), contentSize.height.toFloat()))
    val cardScale =
        if (ready)
            minOf(
                scale.value,
                rootSize.width.toFloat() / contentSize.width,
                rootSize.height.toFloat() / contentSize.height,
            )
        else scale.value
    val center = origin.center + (destination.center - origin.center) * position.value
    val frameWidth = revealBounds.width * cardScale
    val frameHeight = revealBounds.height * cardScale
    val frameLeft = lunarisBarPopupLeft(center.x, frameWidth, rootSize.width.toFloat(), 0f)
    val frameTop =
        (center.y - frameHeight / 2f).coerceIn(
            0f,
            (rootSize.height - frameHeight).coerceAtLeast(0f),
        )
    val frame = Rect(frameLeft, frameTop, frameLeft + frameWidth, frameTop + frameHeight)
    val currentFrame by rememberUpdatedState(frame)
    val currentSecondary by rememberUpdatedState(secondaryBounds)
    val bodyAlpha = alpha.value * (1f - veil.value)
    val currentBodyAlpha by rememberUpdatedState(bodyAlpha)
    val canInteract = isVisible && bodyAlpha >= 0.5f
    LunarisBarPopupTouchRegion(
        enabled = allowOutsideInteraction,
        bounds = if (isVisible && ready) frame else Rect.Zero,
        secondaryBounds = if (canInteract) secondaryBounds else Rect.Zero,
    )

    Layout(
        modifier =
            Modifier.fillMaxSize()
                .onGloballyPositioned {
                    view.getLocationOnScreen(screenLocation)
                    screenOffset = Offset(screenLocation[0].toFloat(), screenLocation[1].toFloat())
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
                                if (!visible || currentBodyAlpha < 0.5f) change.consume()
                            }
                        }
                    }
                },
        content = {
            Box(
                Modifier.drawWithContent {
                        drawContent()
                        if (veil.value > 0f) {
                            drawOutline(
                                popupShape.createOutline(size, layoutDirection, this),
                                Color.Black,
                                alpha = veil.value,
                            )
                        }
                    }
                    .then(if (canInteract) Modifier else Modifier.clearAndSetSemantics {}),
                propagateMinConstraints = true,
            ) {
                LunarisBarSurface(
                    shape = popupShape,
                    background =
                        if (useRedesignedMediaBackground) {
                            {
                                Box(Modifier.matchParentSize()) {
                                    RedesignedMediaPopupBackground(popupShape)
                                }
                            }
                        } else null,
                ) {
                    CompositionLocalProvider(
                        LocalIslandPopupContentOnly provides true,
                        LocalIslandPopupRevealMillis provides Float.POSITIVE_INFINITY,
                    ) {
                        content()
                    }
                }
            }
            Box(if (canInteract) Modifier else Modifier.clearAndSetSemantics {}) {
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
            measurables[0].measure(
                Constraints(
                    minWidth = if (settings.isFullWidth) maxWidth else 0,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                )
            )
        contentSize = IntSize(card.width, card.height)
        val secondary =
            measurables[1].measure(
                Constraints(
                    maxWidth = maxWidth,
                    maxHeight = (maxHeight - card.height - gap).coerceAtLeast(0),
                )
            )
        val secondaryLeft =
            lunarisBarPopupLeft(
                destination.center.x,
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
            card.placeWithLayer(targetLeft.roundToInt(), targetTop.roundToInt()) {
                transformOrigin = TransformOrigin.Center
                scaleX = cardScale
                scaleY = cardScale
                translationX = frame.center.x - destination.center.x
                translationY = frame.center.y - destination.center.y
                this.alpha = if (ready) alpha.value else 0f
                shape = revealShape
                clip = reveal.value < 1f
            }
            secondary.placeWithLayer(
                secondaryLeft.roundToInt(),
                (targetTop + card.height + gap).roundToInt(),
            ) {
                this.alpha = if (ready) bodyAlpha else 0f
            }
        }
    }
}

private val ColorOsCardEase = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
private val ColorOsCardMove = CubicBezierEasing(0.6f, 0f, 0.1f, 1f)
private val ColorOsCardGrow = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)

private class ColorOsRevealShape(private val fraction: Float, private val shape: Shape) : Shape {
    fun bounds(size: Size): Rect {
        val start = minOf(size.width * 0.12f, size.height)
        val width = start + (size.width - start) * fraction
        val height = start + (size.height - start) * fraction
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f
        return Rect(left, top, left + width, top + height)
    }

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val bounds = bounds(size)
        val outline = shape.createOutline(bounds.size, layoutDirection, density)
        return Outline.Generic(
            Path().apply {
                when (outline) {
                    is Outline.Generic -> addPath(outline.path)
                    is Outline.Rounded -> addRoundRect(outline.roundRect)
                    is Outline.Rectangle -> addRect(outline.rect)
                }
                translate(bounds.topLeft)
            }
        )
    }
}
