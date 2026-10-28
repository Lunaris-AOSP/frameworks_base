/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package com.android.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirst
import androidx.compose.ui.util.fastFirstOrNull
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Platform slider implementation that displays a slider with an [icon] and a [label] at the start.
 *
 * @param onValueChangeFinished is called when the slider settles on a [value]. This callback
 *   shouldn't be used to react to value changes. Use [onValueChange] instead
 * @param interactionSource the [MutableInteractionSource] representing the stream of Interactions
 *   for this slider. You can create and pass in your own remembered instance to observe
 *   Interactions and customize the appearance / behavior of this slider in different states.
 * @param colors determine slider color scheme.
 * @param draggingCornersRadius is the radius of the slider indicator when the user drags it
 * @param icon at the start of the slider. Icon is limited to a square space at the start of the
 *   slider
 * @param label is shown next to the icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    colors: PlatformSliderColors = PlatformSliderDefaults.defaultPlatformSliderColors(),
    draggingCornersRadius: Dp = PlatformSliderDefaults.DefaultPlatformSliderDraggingCornerRadius,
    icon: (@Composable (isDragging: Boolean) -> Unit)? = null,
    label: (@Composable (isDragging: Boolean) -> Unit)? = null,
    indicatorBackground: (@Composable (Modifier, Dp) -> Unit)? = null,
    sliderHeight: Dp = 64.dp,
    trackCornerRadius: Dp? = null,
    indicatorInset: Dp = 0.dp,
) {
    val thumbSize: Dp = sliderHeight
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    isDragging = true
                }
                is DragInteraction.Cancel,
                is DragInteraction.Stop -> {
                    isDragging = false
                }
            }
        }
    }

    Box(modifier = modifier.height(sliderHeight)) {
        Slider(
            modifier = Modifier.fillMaxSize(),
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
            interactionSource = interactionSource,
            enabled = enabled,
            track = {
                Track(
                    sliderState = it,
                    enabled = enabled,
                    colors = colors,
                    draggingCornersRadius = draggingCornersRadius,
                    trackCornerRadius = trackCornerRadius,
                    sliderHeight = sliderHeight,
                    thumbSize = thumbSize,
                    isDragging = isDragging,
                    label = label,
                    icon = icon,
                    indicatorBackground = indicatorBackground,
                    indicatorInset = indicatorInset,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            thumb = { Spacer(Modifier.size(thumbSize)) },
        )
    }
}

private enum class TrackComponent(val zIndex: Float) {
    Background(0f),
    Icon(1f),
    Label(1f),
}

@Composable
private fun Track(
    sliderState: SliderState,
    enabled: Boolean,
    colors: PlatformSliderColors,
    draggingCornersRadius: Dp,
    trackCornerRadius: Dp?,
    sliderHeight: Dp,
    thumbSize: Dp,
    isDragging: Boolean,
    icon: (@Composable (isDragging: Boolean) -> Unit)?,
    label: (@Composable (isDragging: Boolean) -> Unit)?,
    indicatorBackground: (@Composable (Modifier, Dp) -> Unit)?,
    indicatorInset: Dp,
    modifier: Modifier = Modifier,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var drawingState: DrawingState by remember { mutableStateOf(DrawingState()) }
    Layout(
        modifier = modifier,
        content = {
            TrackBackground(
                modifier = Modifier.layoutId(TrackComponent.Background),
                drawingState = { drawingState },
                enabled = enabled,
                colors = colors,
                draggingCornersRadiusActive = draggingCornersRadius,
                draggingCornersRadiusIdle = trackCornerRadius ?: sliderHeight / 2,
                trackCornerRadius = trackCornerRadius,
                isDragging = isDragging,
                indicatorBackground = indicatorBackground,
                indicatorInset = indicatorInset,
            )
            if (icon != null) {
                Box(
                    modifier = Modifier.layoutId(TrackComponent.Icon).clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides
                            if (enabled) colors.iconColor else colors.disabledIconColor
                    ) {
                        icon(isDragging)
                    }
                }
            }
            if (label != null) {
                val offsetX by
                    animateFloatAsState(
                        targetValue =
                            if (enabled) {
                                if (drawingState.isLabelOnTopOfIndicator) {
                                    drawingState.iconWidth.coerceAtLeast(
                                        LocalDensity.current.run { 16.dp.toPx() }
                                    )
                                } else {
                                    val indicatorWidth =
                                        drawingState.indicatorRight - drawingState.indicatorLeft
                                    indicatorWidth + LocalDensity.current.run { 16.dp.toPx() }
                                }
                            } else {
                                drawingState.iconWidth
                            },
                        label = "LabelIconSpacingAnimation"
                    )
                Box(
                    modifier =
                        Modifier.layoutId(TrackComponent.Label)
                            .offset { IntOffset(offsetX.toInt(), 0) }
                            .padding(end = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides
                            colors.getLabelColor(
                                isEnabled = enabled,
                                isLabelOnTopOfTheIndicator = drawingState.isLabelOnTopOfIndicator,
                            )
                    ) {
                        label(isDragging)
                    }
                }
            }
        },
        measurePolicy =
            TrackMeasurePolicy(
                sliderState = sliderState,
                enabled = enabled,
                thumbSize = LocalDensity.current.run { thumbSize.roundToPx() },
                isRtl = isRtl,
                onDrawingStateMeasured = { drawingState = it }
            )
    )
}

@Composable
private fun TrackBackground(
    drawingState: () -> DrawingState,
    enabled: Boolean,
    colors: PlatformSliderColors,
    draggingCornersRadiusActive: Dp,
    draggingCornersRadiusIdle: Dp,
    trackCornerRadius: Dp?,
    isDragging: Boolean,
    indicatorBackground: (@Composable (Modifier, Dp) -> Unit)?,
    indicatorInset: Dp,
    modifier: Modifier = Modifier,
) {
    val indicatorRadiusDp: Dp by
        animateDpAsState(
            targetValue =
                if (isDragging) draggingCornersRadiusActive else draggingCornersRadiusIdle,
            label = "PlatformSliderCornersAnimation",
        )

    val trackColor by
        animateColorAsState(
            colors.getTrackColor(enabled),
            label = "PlatformSliderTrackColorAnimation",
        )

    val indicatorColor by
        animateColorAsState(
            colors.getIndicatorColor(enabled),
            label = "PlatformSliderIndicatorColorAnimation",
        )
    Box(
        contentAlignment = AbsoluteAlignment.TopLeft,
        modifier =
            modifier.drawWithCache {
                val measuredState = drawingState()
                val radius =
                    (trackCornerRadius?.toPx() ?: (size.height / 2)).coerceIn(0f, size.height / 2)
                val trackCorners = CornerRadius(radius, radius)
                val trackPath =
                    Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = 0f,
                                top = 0f,
                                right = size.width,
                                bottom = size.height,
                                cornerRadius = trackCorners,
                            )
                        )
                    }
                val inset = indicatorInset.toPx().coerceIn(0f, size.minDimension / 2)
                val indicatorClipPath =
                    if (inset > 0f) {
                        Path().apply {
                            addRoundRect(
                                RoundRect(
                                    left = inset,
                                    top = inset,
                                    right = size.width - inset,
                                    bottom = size.height - inset,
                                    cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)),
                                )
                            )
                        }
                    } else {
                        trackPath
                    }
                val indicatorPath =
                    if (
                        indicatorBackground == null &&
                            measuredState.indicatorRight > measuredState.indicatorLeft
                    ) {
                        val endRadius = indicatorRadiusDp.toPx().coerceIn(0f, size.height / 2)
                        val indicatorCornerRadius = CornerRadius(endRadius)
                        val bounds = measuredState.indicatorBounds(2 * maxOf(radius, endRadius))
                        val leftCorners =
                            if (measuredState.isRtl) indicatorCornerRadius else trackCorners
                        val rightCorners =
                            if (measuredState.isRtl) trackCorners else indicatorCornerRadius
                        Path().apply {
                            addRoundRect(
                                RoundRect(
                                    left = bounds.left,
                                    top = bounds.top,
                                    right = bounds.right,
                                    bottom = bounds.bottom,
                                    topLeftCornerRadius = leftCorners,
                                    topRightCornerRadius = rightCorners,
                                    bottomRightCornerRadius = rightCorners,
                                    bottomLeftCornerRadius = leftCorners,
                                )
                            )
                        }
                    } else {
                        null
                    }
                onDrawWithContent {
                    drawPath(path = trackPath, color = trackColor)
                    clipPath(indicatorClipPath) {
                        if (indicatorPath != null) {
                            drawPath(path = indicatorPath, color = indicatorColor)
                        }
                        this@onDrawWithContent.drawContent()
                    }
                }
            },
    ) {
        if (indicatorBackground != null) {
            val measuredState = drawingState()
            if (measuredState.indicatorRight > measuredState.indicatorLeft) {
                val density = LocalDensity.current
                val bounds =
                    with(density) {
                        val halfHeight =
                            (measuredState.indicatorBottom - measuredState.indicatorTop) / 2
                        val radius =
                            (trackCornerRadius?.toPx() ?: halfHeight).coerceIn(0f, halfHeight)
                        val endRadius = indicatorRadiusDp.toPx().coerceIn(0f, halfHeight)
                        measuredState.indicatorBounds(ceil(2 * maxOf(radius, endRadius)))
                    }
                indicatorBackground(
                    Modifier.absoluteOffset {
                            IntOffset(
                                bounds.left.roundToInt(),
                                bounds.top.roundToInt(),
                            )
                        }
                        .size(
                            with(density) { bounds.width.toDp() },
                            with(density) { bounds.height.toDp() },
                        ),
                    indicatorRadiusDp,
                )
            }
        }
    }
}

/** Measures track components sizes and calls [onDrawingStateMeasured] when it's done. */
private class TrackMeasurePolicy(
    private val sliderState: SliderState,
    private val enabled: Boolean,
    private val thumbSize: Int,
    private val isRtl: Boolean,
    private val onDrawingStateMeasured: (DrawingState) -> Unit,
) : MeasurePolicy {

    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints
    ): MeasureResult {
        // Slider adds a paddings to the Track to make spase for thumb
        val desiredWidth = constraints.maxWidth + thumbSize
        val desiredHeight = constraints.maxHeight
        val backgroundPlaceable: Placeable =
            measurables
                .fastFirst { it.layoutId == TrackComponent.Background }
                .measure(Constraints(desiredWidth, desiredWidth, desiredHeight, desiredHeight))

        val iconPlaceable: Placeable? =
            measurables
                .fastFirstOrNull { it.layoutId == TrackComponent.Icon }
                ?.measure(
                    Constraints(
                        minWidth = desiredHeight,
                        maxWidth = desiredHeight,
                        minHeight = desiredHeight,
                        maxHeight = desiredHeight,
                    )
                )

        val iconSize = iconPlaceable?.width ?: 0
        val labelMaxWidth = if (enabled) (desiredWidth - iconSize) / 2 else desiredWidth - iconSize
        val labelPlaceable: Placeable? =
            measurables
                .fastFirstOrNull { it.layoutId == TrackComponent.Label }
                ?.measure(
                    Constraints(
                        minWidth = 0,
                        maxWidth = labelMaxWidth,
                        minHeight = desiredHeight,
                        maxHeight = desiredHeight,
                    )
                )

        val drawingState =
            if (isRtl) {
                DrawingState(
                    isRtl = true,
                    indicatorLeft =
                        desiredWidth * (1 - sliderState.coercedNormalizedValue),
                    indicatorTop = 0f,
                    indicatorRight = desiredWidth.toFloat(),
                    indicatorBottom = desiredHeight.toFloat(),
                    iconWidth = iconSize.toFloat(),
                    labelWidth = labelPlaceable?.width?.toFloat() ?: 0f,
                )
            } else {
                DrawingState(
                    isRtl = false,
                    indicatorLeft = 0f,
                    indicatorTop = 0f,
                    indicatorRight =
                        desiredWidth * sliderState.coercedNormalizedValue,
                    indicatorBottom = desiredHeight.toFloat(),
                    iconWidth = iconSize.toFloat(),
                    labelWidth = labelPlaceable?.width?.toFloat() ?: 0f,
                )
            }

        onDrawingStateMeasured(drawingState)

        return layout(desiredWidth, desiredHeight) {
            backgroundPlaceable.placeRelative(0, 0, TrackComponent.Background.zIndex)

            iconPlaceable?.placeRelative(0, 0, TrackComponent.Icon.zIndex)
            labelPlaceable?.placeRelative(0, 0, TrackComponent.Label.zIndex)
        }
    }
}

private data class DrawingState(
    val isRtl: Boolean = false,
    val indicatorLeft: Float = 0f,
    val indicatorTop: Float = 0f,
    val indicatorRight: Float = 0f,
    val indicatorBottom: Float = 0f,
    val iconWidth: Float = 0f,
    val labelWidth: Float = 0f,
)

private fun DrawingState.indicatorBounds(minimumWidth: Float): Rect {
    return Rect(
        left = if (isRtl) indicatorLeft else minOf(indicatorLeft, indicatorRight - minimumWidth),
        top = indicatorTop,
        right = if (isRtl) maxOf(indicatorRight, indicatorLeft + minimumWidth) else indicatorRight,
        bottom = indicatorBottom,
    )
}

private val DrawingState.isLabelOnTopOfIndicator: Boolean
    get() = labelWidth < indicatorRight - indicatorLeft - iconWidth

/** [SliderState.value] normalized using [SliderState.valueRange]. The result belongs to [0, 1] */
private val SliderState.coercedNormalizedValue: Float
    get() {
        val dif = valueRange.endInclusive - valueRange.start
        return if (dif == 0f) {
            0f
        } else {
            val coercedValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
            (coercedValue - valueRange.start) / dif
        }
    }

/**
 * [PlatformSlider] color scheme.
 *
 * @param trackColor fills the track of the slider. This is a "background" of the slider
 * @param indicatorColor fills the slider from the start to the value
 * @param iconColor is the default icon color
 * @param labelColorOnIndicator is the label color for when it's shown on top of the indicator
 * @param labelColorOnTrack is the label color for when it's shown on top of the track
 * @param disabledTrackColor is the [trackColor] when the PlatformSlider#enabled == false
 * @param disabledIndicatorColor is the [indicatorColor] when the PlatformSlider#enabled == false
 * @param disabledIconColor is the [iconColor] when the PlatformSlider#enabled == false
 * @param disabledLabelColor is the label color when the PlatformSlider#enabled == false
 */
data class PlatformSliderColors(
    val trackColor: Color,
    val indicatorColor: Color,
    val iconColor: Color,
    val labelColorOnIndicator: Color,
    val labelColorOnTrack: Color,
    val disabledTrackColor: Color,
    val disabledIndicatorColor: Color,
    val disabledIconColor: Color,
    val disabledLabelColor: Color,
)

object PlatformSliderDefaults {

    /** Indicator corner radius used when the user drags the [PlatformSlider]. */
    val DefaultPlatformSliderDraggingCornerRadius = 8.dp

    @Composable
    fun defaultPlatformSliderColors(): PlatformSliderColors =
        PlatformSliderColors(
            trackColor = MaterialTheme.colorScheme.secondaryContainer,
            indicatorColor = MaterialTheme.colorScheme.primary,
            iconColor = MaterialTheme.colorScheme.onPrimary,
            labelColorOnIndicator = MaterialTheme.colorScheme.onPrimary,
            labelColorOnTrack = MaterialTheme.colorScheme.onSecondaryContainer,
            disabledTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledIndicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledIconColor = MaterialTheme.colorScheme.outline,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
}

private fun PlatformSliderColors.getTrackColor(isEnabled: Boolean): Color =
    if (isEnabled) trackColor else disabledTrackColor

private fun PlatformSliderColors.getIndicatorColor(isEnabled: Boolean): Color =
    if (isEnabled) indicatorColor else disabledIndicatorColor

private fun PlatformSliderColors.getLabelColor(
    isEnabled: Boolean,
    isLabelOnTopOfTheIndicator: Boolean
): Color {
    return if (isEnabled) {
        if (isLabelOnTopOfTheIndicator) labelColorOnIndicator else labelColorOnTrack
    } else {
        disabledLabelColor
    }
}
