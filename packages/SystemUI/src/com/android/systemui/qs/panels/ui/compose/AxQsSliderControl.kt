/*
 * Copyright 2025-2026 AxionOS
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

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.qs.panels.ui.compose

import android.media.AudioManager
import android.os.UserHandle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.PlatformSlider
import com.android.compose.PlatformSliderColors
import com.android.compose.PlatformSliderDefaults
import com.android.compose.gesture.gesturesDisabled
import com.android.compose.modifiers.sliderPercentage
import com.android.compose.ui.graphics.drawInOverlay
import com.android.systemui.brightness.shared.model.GammaBrightness
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.common.ui.compose.Icon as SystemUiIcon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.haptics.slider.SeekableSliderTrackerConfig
import com.android.systemui.haptics.slider.SliderHapticFeedbackConfig
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.panels.shared.model.AxMediaSurface
import com.android.systemui.qs.panels.shared.model.AxQsControl
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.shared.model.AxQsTileStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsSliderStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberObservedSetting
import com.android.systemui.qs.tiles.ringer.LocalRingerSliderViewModel
import com.android.systemui.res.R
import com.android.systemui.utils.PolicyRestriction
import com.android.systemui.volume.dialog.sliders.ui.compose.SliderTrack
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigsProvider
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderState
import java.text.NumberFormat
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val LocalShowQsSliderPercentage = staticCompositionLocalOf { false }
internal val LocalSmoothVolumeSlider = staticCompositionLocalOf { false }

internal class AxQsSliderDrag<T : Any> {
    var value: T? by mutableStateOf(null)
        private set
    private var owner: MutableInteractionSource? = null

    fun update(source: MutableInteractionSource, value: T) {
        owner = source
        this.value = value
    }

    fun finish(source: MutableInteractionSource): T? {
        if (owner !== source) return null
        val finalValue = value
        owner = null
        value = null
        return finalValue
    }
}

internal val LocalBrightnessDrag = staticCompositionLocalOf<AxQsSliderDrag<Int>?> { null }
internal val LocalVolumeDrag = staticCompositionLocalOf<AxQsSliderDrag<Float>?> { null }

internal fun Modifier.axQsEntrance(progress: () -> Float): Modifier {
    return graphicsLayer {
        val entrance = progress().coerceIn(0f, 1f)
        alpha = ((entrance - 0.2f) / (1f - 0.2f)).coerceIn(0f, 1f)
        translationY = if (entrance == 0f) -100f else (1f - entrance) * -100f
    }
}

@Composable
internal fun AxQsSliderControl(
    vertical: Boolean,
    modifier: Modifier = Modifier,
    interceptParentScroll: Boolean = true,
    mirrorInOverlay: Boolean = false,
    entranceProgress: () -> Float = { 1f },
    content: @Composable (sliderHeight: Dp, vertical: Boolean) -> Unit,
) {
    val mirrorModifier =
        if (mirrorInOverlay) {
            Modifier.drawInOverlay().axQsEntrance(entranceProgress)
        } else {
            Modifier
        }
    Box(
        modifier = modifier.then(mirrorModifier),
        contentAlignment = Alignment.Center,
    ) {
        val view = LocalView.current
        val layoutDirection = LocalLayoutDirection.current
        val inputModifier =
            if (vertical) {
                Modifier.interceptParentScroll(interceptParentScroll, view)
            } else {
                Modifier
            }
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().then(inputModifier),
            contentAlignment = Alignment.Center,
        ) {
            val sliderWidth = if (vertical) maxHeight else maxWidth
            val availableHeight = if (vertical) maxWidth else maxHeight
            val sliderHeight = axQsSliderTrackHeight(availableHeight)
            val sliderLayoutDirection = if (vertical) LayoutDirection.Ltr else layoutDirection
            Box(
                modifier =
                    Modifier.requiredWidth(sliderWidth)
                        .requiredHeight(availableHeight)
                        .rotate(if (vertical) -90f else 0f),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides sliderLayoutDirection) {
                    content(sliderHeight, vertical)
                }
            }
        }
    }
}

private fun Modifier.interceptParentScroll(enabled: Boolean, view: View): Modifier {
    if (!enabled) return this
    return pointerInput(view) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            view.parent?.requestDisallowInterceptTouchEvent(true)
            try {
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    pressed = event.changes.any { it.pressed }
                }
            } finally {
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }
}

internal fun axQsControlShape(
    control: AxQsControl,
    span: AxQsSpan,
    sliderStyle: LunarisQsSliderStyle = LunarisQsSliderStyle.M3_EXPRESSIVE,
    mediaSurface: AxMediaSurface = AxMediaSurface.CONTROL,
    tileStyle: AxQsTileStyle = AxQsTileStyle.PILL,
): RoundedCornerShape {
    return when {
        control == AxQsControl.MEDIA && mediaSurface == AxMediaSurface.SEPARATE_QQS ->
            RoundedCornerShape(AxNotificationMediaCornerRadius)
        control == AxQsControl.MEDIA &&
            span == AxQsSpan.MediaMin &&
            mediaSurface == AxMediaSurface.CONTROL -> axQsTileShape(span, tileStyle)
        control.isResizableContent -> RoundedCornerShape(AxQsControlCornerRadius)
        control == AxQsControl.MEDIA &&
            span == AxQsSpan.MediaDefault &&
            mediaSurface == AxMediaSurface.CONTROL -> RoundedCornerShape(28.dp)
        control == AxQsControl.RINGER -> axQsTileShape(AxQsSpan.TileDefault, tileStyle)
        control.isSlider ->
            when (sliderStyle) {
                LunarisQsSliderStyle.M3_EXPRESSIVE -> ExpressiveSliderShape
                LunarisQsSliderStyle.PLATFORM,
                LunarisQsSliderStyle.PILL_FLAT -> CircleShape
                LunarisQsSliderStyle.ROUNDED_SQUARE,
                LunarisQsSliderStyle.ROUNDED_SQUARE_FLAT ->
                    RoundedCornerShape(AxQsControlCornerRadius)
            }
        span == AxQsSpan.TileDefault && control != AxQsControl.MEDIA -> CircleShape
        else -> RoundedCornerShape(AxQsControlCornerRadius)
    }
}

internal val AxQsControlCornerRadius = 24.dp
internal val AxNotificationMediaCornerRadius = 36.dp
private val ExpressiveSliderShape = RoundedCornerShape(30)
private val AxSliderTrackHeight = 64.dp
private val AxSliderTrackInsideCornerRadius = 2.dp
private val AxSliderThumbWidth = 4.dp
private val AxSliderThumbTrackGap = 6.dp
private val AxSliderIconSize = 20.dp
private val AxSliderPercentageSize = DpSize(48.dp, 24.dp)
private val AxSliderReadoutGap = 8.dp
private const val SLIDER_CORNER_DIVISOR = 3.333f

internal fun axQsSliderTrackHeight(availableHeight: Dp): Dp =
    AxSliderTrackHeight * (availableHeight / AxTileDefaults.TileHeight)

internal fun axQsSliderPercentage(value: Float, valueRange: ClosedFloatingPointRange<Float>): Float {
    val range = valueRange.endInclusive - valueRange.start
    if (!value.isFinite() || !range.isFinite() || range <= 0f) return 0f
    return ((value - valueRange.start) / range * 100f).coerceIn(0f, 100f)
}

@Composable
internal fun animateAxQsSliderValue(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    isDragging: Boolean,
    label: String,
): Float {
    val targetFraction = axQsSliderPercentage(value, valueRange) / 100f
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec =
            if (isDragging) {
                snap()
            } else {
                spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)
            },
        label = label,
    )
    val fraction = if (isDragging) targetFraction else animatedFraction.coerceIn(0f, 1f)
    return valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
}

@Composable
internal fun AxQsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)?,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    sliderHeight: Dp,
    vertical: Boolean,
    sliderStyle: LunarisQsSliderStyle,
    trackBackgroundColor: Color = Color.Transparent,
    inactiveTrackColor: Color = AxTileDefaults.backgroundColor(),
    icon: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    active: Boolean = true,
    percentage: Float = axQsSliderPercentage(value, valueRange),
) {
    val percentageLabel =
        if (LocalShowQsSliderPercentage.current) {
            val locale = LocalConfiguration.current.locales[0]
            val formatter = remember(locale) { NumberFormat.getPercentInstance(locale) }
            formatter.format(percentage.coerceIn(0f, 100f).roundToInt() / 100.0)
        } else {
            null
        }
    val gradient = rememberLunarisQsGradient(rotationOffset = if (vertical) 90 else 0)
    val activeTrackBrush = gradient.sliderBrush()
    val activeTrackColor =
        (if (activeTrackBrush != null) gradient.startColor else MaterialTheme.colorScheme.primary)
            .copy(alpha = if (active) 1f else 0.7f)
    val activeContentColor =
        if (activeTrackBrush != null) gradient.contentColor else MaterialTheme.colorScheme.onPrimary
    val sliderModifier =
        modifier.lunarisSliderLongPress(
            vertical = vertical,
            sliderStyle = sliderStyle,
            onLongPress = onLongPress.takeIf { enabled },
        )
    val sliderScale = sliderHeight / AxSliderTrackHeight
    val fraction = axQsSliderPercentage(value, valueRange) / 100f
    val fluidAnimation = LocalFluidSliderAnimation.current
    val waveState = rememberFluidSliderWaveState(fraction = fraction, enabled = fluidAnimation)
    if (sliderStyle.usesPlatformSlider) {
        val strokeStyle = LocalLunarisQsSliderStrokeStyle.current
        val strokeWidth = LocalLunarisQsSliderStrokeWidth.current * sliderScale
        val flatFill =
            sliderStyle == LunarisQsSliderStyle.PILL_FLAT ||
                sliderStyle == LunarisQsSliderStyle.ROUNDED_SQUARE_FLAT
        val roundedSquare =
            sliderStyle == LunarisQsSliderStyle.ROUNDED_SQUARE ||
                sliderStyle == LunarisQsSliderStyle.ROUNDED_SQUARE_FLAT
        val trackShape =
            if (roundedSquare) RoundedCornerShape(AxQsControlCornerRadius) else CircleShape
        val trackHeight = AxTileDefaults.TileHeight * sliderScale
        val indicatorColor by animateColorAsState(
            if (enabled) activeTrackColor else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
            label = "AxQsSliderStrokeColor",
        )
        val platformTrackColor =
            if (trackBackgroundColor == Color.Transparent) {
                inactiveTrackColor
            } else {
                trackBackgroundColor
            }
        Box(modifier = sliderModifier.fillMaxWidth()) {
            PlatformSlider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                enabled = enabled,
                interactionSource = interactionSource,
                colors =
                    PlatformSliderColors(
                        trackColor = platformTrackColor,
                        indicatorColor = activeTrackColor,
                        iconColor = activeContentColor,
                        labelColorOnIndicator = activeContentColor,
                        labelColorOnTrack = MaterialTheme.colorScheme.onSurface,
                        disabledTrackColor = platformTrackColor.copy(alpha = 0.38f),
                        disabledIndicatorColor =
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                        disabledIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    ),
                draggingCornersRadius =
                    if (roundedSquare) AxQsControlCornerRadius
                    else
                        PlatformSliderDefaults.DefaultPlatformSliderDraggingCornerRadius *
                            sliderScale,
                sliderHeight = trackHeight,
                trackCornerRadius = if (roundedSquare) AxQsControlCornerRadius else null,
                indicatorInset = if (strokeStyle) strokeWidth else 0.dp,
                indicatorBackground =
                    if (fluidAnimation || activeTrackBrush != null || strokeStyle || flatFill) {
                        { indicatorModifier, cornerRadius ->
                            val startCorner =
                                if (roundedSquare) AxQsControlCornerRadius else trackHeight / 2
                            val indicatorShape = RoundedCornerShape(
                                topStart = startCorner,
                                bottomStart = startCorner,
                                topEnd = if (flatFill) 0.dp else cornerRadius,
                                bottomEnd = if (flatFill) 0.dp else cornerRadius,
                            )
                            val gradientAlpha = if (!enabled) 0.38f else if (active) 1f else 0.7f
                            val fillBrush = activeTrackBrush ?: SolidColor(indicatorColor)
                            val fillAlpha = if (activeTrackBrush != null) gradientAlpha else 1f
                            Box(
                                when {
                                    fluidAnimation ->
                                        indicatorModifier.lunarisFluidSliderIndicator(
                                            waveState = waveState,
                                            brush = fillBrush,
                                            insetWidth = if (strokeStyle) strokeWidth else 0.dp,
                                            alpha = { fillAlpha },
                                        )
                                    strokeStyle ->
                                        indicatorModifier.lunarisQsInsetBackground(
                                            brush = fillBrush,
                                            shape = indicatorShape,
                                            insetWidth = strokeWidth,
                                            alpha = { fillAlpha },
                                        )
                                    else ->
                                        indicatorModifier
                                            .alpha(fillAlpha)
                                            .background(
                                                fillBrush,
                                                if (flatFill) RectangleShape else indicatorShape,
                                            )
                                }
                            )
                        }
                    } else null,
                modifier =
                    Modifier.fillMaxWidth()
                        .lunarisQsCosTileBorder(shape = trackShape, verticalSlider = vertical)
                        .then(if (flatFill) Modifier.clip(trackShape) else Modifier),
            )
            AxQsSliderReadout(
                percentage = percentageLabel,
                vertical = vertical,
                platform = true,
                fraction = fraction,
                sliderScale = sliderScale,
                enabled = enabled,
                activeColor = activeContentColor,
                icon = icon,
            )
        }
        return
    }
    val colors =
        SliderDefaults.colors(
            thumbColor = activeTrackColor,
            activeTrackColor = activeTrackColor,
            activeTickColor = activeContentColor,
            inactiveTrackColor = inactiveTrackColor,
            inactiveTickColor = MaterialTheme.colorScheme.onSurface,
            disabledThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            disabledActiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
            disabledActiveTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f),
            disabledInactiveTrackColor = inactiveTrackColor.copy(alpha = 0.38f),
            disabledInactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    val thumbSize =
        DpSize(
            width = AxSliderThumbWidth * sliderScale,
            height = AxTileDefaults.TileHeight * sliderScale,
        )
    val trackCornerSize = sliderHeight / SLIDER_CORNER_DIVISOR
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier = sliderModifier) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            enabled = enabled,
            interactionSource = interactionSource,
            colors = colors,
            modifier = Modifier.fillMaxWidth(),
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    enabled = enabled,
                    colors = colors,
                    thumbSize = thumbSize,
                )
            },
            track = { sliderState ->
                val expressiveTrackColors =
                    if (fluidAnimation) {
                        colors.copy(
                            activeTrackColor = Color.Transparent,
                            disabledActiveTrackColor = Color.Transparent,
                        )
                    } else {
                        colors
                    }
                val fluidClipPath = remember { Path() }
                val fluidFillPath = remember { Path() }
                SliderTrack(
                    sliderState = sliderState,
                    isEnabled = enabled,
                    colors = expressiveTrackColors,
                    trackCornerSize = trackCornerSize,
                    trackInsideCornerSize = AxSliderTrackInsideCornerRadius,
                    thumbTrackGapSize = AxSliderThumbTrackGap,
                    trackSize = sliderHeight,
                    activeTrackBrush = if (fluidAnimation) null else activeTrackBrush,
                    activeTrackAlpha = if (active) 1f else 0.7f,
                    modifier =
                        Modifier.background(trackBackgroundColor, ExpressiveSliderShape)
                            .lunarisQsCosTileBorder(
                                shape = RoundedCornerShape(trackCornerSize),
                                verticalSlider = vertical,
                                horizontalGap = {
                                    val coercedFraction = sliderState.coercedValueAsFraction
                                    val thumbCenter =
                                        size.width *
                                            (if (isRtl) 1f - coercedFraction else coercedFraction)
                                    val gap =
                                        thumbSize.width.toPx() / 2f + AxSliderThumbTrackGap.toPx()
                                    (thumbCenter - gap)..(thumbCenter + gap)
                                },
                            )
                            .then(
                                if (fluidAnimation) {
                                    Modifier.drawWithContent {
                                        drawContent()
                                        val coercedFraction = sliderState.coercedValueAsFraction
                                        val thumbHalfPx = thumbSize.width.toPx() / 2f
                                        val gapPx = AxSliderThumbTrackGap.toPx()
                                        val thumbCenter =
                                            size.width *
                                                (if (isRtl) 1f - coercedFraction else coercedFraction)
                                        val edgeX =
                                            if (isRtl) {
                                                (thumbCenter + thumbHalfPx + gapPx)
                                                    .coerceIn(0f, size.width)
                                            } else {
                                                (thumbCenter - thumbHalfPx - gapPx)
                                                    .coerceIn(0f, size.width)
                                            }
                                        val cornerPx =
                                            trackCornerSize.toPx().coerceIn(0f, size.height / 2f)
                                        fluidClipPath.rewind()
                                        fluidClipPath.addRoundRect(
                                            RoundRect(
                                                left = 0f,
                                                top = 0f,
                                                right = size.width,
                                                bottom = size.height,
                                                cornerRadius = CornerRadius(cornerPx, cornerPx),
                                            )
                                        )
                                        val thumbGuardLeft =
                                            if (isRtl) {
                                                (thumbCenter + thumbHalfPx + 1.dp.toPx())
                                                    .coerceIn(0f, size.width)
                                            } else {
                                                0f
                                            }
                                        val thumbGuardRight =
                                            if (isRtl) {
                                                size.width
                                            } else {
                                                (thumbCenter - thumbHalfPx - 1.dp.toPx())
                                                    .coerceIn(0f, size.width)
                                            }
                                        val fillBrush =
                                            activeTrackBrush
                                                ?: SolidColor(
                                                    if (enabled) activeTrackColor
                                                    else colors.disabledActiveTrackColor
                                                )
                                        val fillAlpha =
                                            if (activeTrackBrush != null) {
                                                if (!enabled) 0.38f else if (active) 1f else 0.7f
                                            } else {
                                                1f
                                            }
                                        clipPath(fluidClipPath) {
                                            clipRect(left = thumbGuardLeft, right = thumbGuardRight) {
                                                drawFluidSliderFill(
                                                    path = fluidFillPath,
                                                    brush = fillBrush,
                                                    alpha = fillAlpha,
                                                    startX = if (isRtl) size.width else 0f,
                                                    edgeX = edgeX,
                                                    top = 0f,
                                                    bottom = size.height,
                                                    isRtl = isRtl,
                                                    bend = waveState.bend,
                                                    slosh = waveState.slosh,
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Modifier
                                }
                            ),
                )
            },
        )
        AxQsSliderReadout(
            percentage = percentageLabel,
            vertical = vertical,
            platform = false,
            fraction = fraction,
            sliderScale = sliderScale,
            enabled = enabled,
            activeColor = activeContentColor,
            icon = icon,
        )
    }
}

private fun axQsSliderReadoutTarget(
    atStart: Boolean,
    trackLength: Dp,
    slotSize: Dp,
    contentSize: Dp,
    activeEnd: Dp,
    inactiveStart: Dp,
    gap: Dp,
): Dp {
    val restingCenter = if (atStart) slotSize / 2 else trackLength - slotSize / 2
    val targetCenter =
        if (atStart) {
            if (activeEnd >= slotSize) restingCenter
            else maxOf(restingCenter, inactiveStart + contentSize / 2 + gap)
        } else {
            if (trackLength - inactiveStart >= slotSize) restingCenter
            else minOf(restingCenter, activeEnd - contentSize / 2 - gap)
        }
    return targetCenter.coerceIn(contentSize / 2, trackLength - contentSize / 2)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.AxQsSliderReadout(
    percentage: String?,
    vertical: Boolean,
    platform: Boolean,
    fraction: Float,
    sliderScale: Float,
    enabled: Boolean,
    activeColor: Color,
    icon: @Composable (Modifier) -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val inactiveColor = MaterialTheme.colorScheme.onSurface
    val alpha = if (enabled) 1f else 0.38f
    BoxWithConstraints(Modifier.matchParentSize().clearAndSetSemantics {}) {
        val slotSize = minOf(maxHeight, maxWidth / 2)
        val scale = sliderScale.coerceAtLeast(0.1f)
        val iconSize = minOf(AxSliderIconSize * sliderScale, slotSize)
        val iconHalfSize = iconSize / 2
        val percentageSize =
            DpSize(
                minOf(AxSliderPercentageSize.width * scale, slotSize),
                minOf(AxSliderPercentageSize.height * scale, slotSize),
            )
        val percentageExtent = if (vertical) percentageSize.height else percentageSize.width
        val percentageHalfSize = percentageExtent / 2
        val readoutGap = AxSliderReadoutGap * scale
        val thumbHalfWidth = if (platform) 0.dp else AxSliderThumbWidth * sliderScale / 2
        val trackGap = if (platform) 0.dp else thumbHalfWidth + AxSliderThumbTrackGap
        val thumbPosition = thumbHalfWidth + (maxWidth - thumbHalfWidth * 2) * fraction
        val activeEnd = (thumbPosition - trackGap).coerceIn(0.dp, maxWidth)
        val inactiveStart = (thumbPosition + trackGap).coerceIn(0.dp, maxWidth)
        val iconAtStart = vertical || percentage == null
        val animationSpec =
            spring<Dp>(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        val iconCenter =
            animateDpAsState(
                targetValue =
                    axQsSliderReadoutTarget(
                        atStart = iconAtStart,
                        trackLength = maxWidth,
                        slotSize = slotSize,
                        contentSize = iconSize,
                        activeEnd = activeEnd,
                        inactiveStart = inactiveStart,
                        gap = readoutGap,
                    ),
                animationSpec = animationSpec,
                label = "AxQsSliderIconPosition",
            )
        val percentageCenter =
            if (percentage != null) {
                animateDpAsState(
                    targetValue =
                        axQsSliderReadoutTarget(
                            atStart = !iconAtStart,
                            trackLength = maxWidth,
                            slotSize = slotSize,
                            contentSize = percentageExtent,
                            activeEnd = activeEnd,
                            inactiveStart = inactiveStart,
                            gap = readoutGap,
                        ),
                    animationSpec = animationSpec,
                    label = "AxQsSliderPercentagePosition",
                )
            } else null

        val readoutCenter: (Boolean) -> Dp = { isIcon ->
            if (percentageCenter == null) {
                iconCenter.value.coerceIn(iconHalfSize, maxWidth - iconHalfSize)
            } else {
                val startHalfSize = if (iconAtStart) iconHalfSize else percentageHalfSize
                val endHalfSize = if (iconAtStart) percentageHalfSize else iconHalfSize
                val gap =
                    minOf(readoutGap, (maxWidth - iconSize - percentageExtent).coerceAtLeast(0.dp))
                val separation = startHalfSize + gap + endHalfSize
                val startCenter =
                    (if (iconAtStart) iconCenter.value else percentageCenter.value)
                        .coerceIn(startHalfSize, maxWidth - endHalfSize - separation)
                val endCenter =
                    (if (iconAtStart) percentageCenter.value else iconCenter.value)
                        .coerceIn(startHalfSize + separation, maxWidth - endHalfSize)
                val overlap = (startCenter + separation - endCenter).coerceAtLeast(0.dp)
                if (isIcon == iconAtStart) startCenter - overlap / 2
                else endCenter + overlap / 2
            }
        }
        val content: @Composable BoxScope.() -> Unit = {
            if (percentage != null) {
                Box(
                    modifier =
                        Modifier.align(Alignment.CenterStart)
                            .offset {
                                IntOffset(
                                    (readoutCenter(false) - slotSize / 2).roundToPx(),
                                    0,
                                )
                            }
                            .size(slotSize)
                            .rotate(if (vertical) 90f else 0f),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier =
                            Modifier.size(percentageSize).padding(horizontal = 4.dp * scale),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = percentage,
                            style =
                                MaterialTheme.typography.labelLarge.copy(
                                    color = LocalContentColor.current,
                                    fontSize = 14.sp * scale,
                                    lineHeight = 1.4.em,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                ),
                            maxLines = 1,
                            softWrap = false,
                            autoSize =
                                TextAutoSize.StepBased(
                                    minFontSize = 8.sp * scale,
                                    maxFontSize = 14.sp * scale,
                                ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Box(
                modifier =
                    Modifier.align(Alignment.CenterStart)
                        .offset {
                            IntOffset(
                                (readoutCenter(true) - iconHalfSize).roundToPx(),
                                0,
                            )
                        }
                        .size(iconSize),
                contentAlignment = Alignment.Center,
            ) {
                icon(Modifier.fillMaxSize().rotate(if (vertical) 90f else 0f))
            }
        }
        val contentModifier: (Boolean) -> Modifier = { activeTrack ->
            Modifier.fillMaxSize().drawWithContent {
                val fill = activeEnd.toPx().coerceIn(0f, size.width)
                val boundary = if (rtl) size.width - fill else fill
                val drawLeft = activeTrack != rtl
                clipRect(
                    left = if (drawLeft) 0f else boundary,
                    right = if (drawLeft) boundary else size.width,
                ) {
                    this@drawWithContent.drawContent()
                }
            }
        }
        CompositionLocalProvider(LocalContentColor provides inactiveColor.copy(alpha = alpha)) {
            Box(contentModifier(false), content = content)
        }
        CompositionLocalProvider(LocalContentColor provides activeColor.copy(alpha = alpha)) {
            Box(contentModifier(true), content = content)
        }
    }
}

@Composable
internal fun AxQsSliderPreview(
    control: AxQsControl,
    span: AxQsSpan,
    sliderStyle: LunarisQsSliderStyle,
    brightnessViewModel: BrightnessSliderViewModel,
    volumeViewModel: AudioStreamSliderViewModel? = null,
    modifier: Modifier = Modifier,
) {
    AxQsSliderControl(
        vertical = span.columns == 1,
        modifier = modifier,
        interceptParentScroll = false,
    ) { sliderHeight, vertical ->
        Box(Modifier.fillMaxSize().gesturesDisabled().clearAndSetSemantics {}) {
            when (control) {
                AxQsControl.BRIGHTNESS ->
                    AxBrightnessSlider(
                        viewModel = brightnessViewModel,
                        sliderHeight = sliderHeight,
                        vertical = vertical,
                        sliderStyle = sliderStyle,
                        interactive = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                AxQsControl.VOLUME ->
                    if (volumeViewModel != null) {
                        AxVolumeSlider(
                            viewModel = volumeViewModel,
                            interactive = false,
                            sliderHeight = sliderHeight,
                            vertical = vertical,
                            sliderStyle = sliderStyle,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                else -> Unit
            }
        }
    }
}

@Composable
internal fun AxQsBrightnessControl(
    vertical: Boolean,
    sliderStyle: LunarisQsSliderStyle,
    viewModel: BrightnessSliderViewModel,
    entranceProgress: () -> Float,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    longPressEnabled: Boolean = true,
    inactiveTrackColor: Color = AxTileDefaults.backgroundColor(),
) {
    var dragging by remember(vertical) { mutableStateOf(false) }
    val mirrorInOverlay = dragging && viewModel.showMirror
    AxQsSliderControl(
        vertical = vertical,
        interceptParentScroll = interactive,
        mirrorInOverlay = mirrorInOverlay,
        entranceProgress = entranceProgress,
        modifier = modifier,
    ) { sliderHeight, verticalSlider ->
        AxBrightnessSlider(
            viewModel = viewModel,
            interactive = interactive,
            longPressEnabled = longPressEnabled,
            inactiveTrackColor = inactiveTrackColor,
            onDraggingChanged = { dragging = it },
            sliderHeight = sliderHeight,
            vertical = verticalSlider,
            sliderStyle = sliderStyle,
            mirrorInOverlay = mirrorInOverlay,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AxBrightnessSlider(
    viewModel: BrightnessSliderViewModel,
    sliderHeight: Dp,
    vertical: Boolean,
    sliderStyle: LunarisQsSliderStyle,
    interactive: Boolean = true,
    longPressEnabled: Boolean = true,
    inactiveTrackColor: Color = AxTileDefaults.backgroundColor(),
    mirrorInOverlay: Boolean = false,
    onDraggingChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val gamma = viewModel.currentBrightness.value
    if (gamma == BrightnessSliderViewModel.initialValue.value) return

    val drag = LocalBrightnessDrag.current ?: remember { AxQsSliderDrag<Int>() }
    val displayValue = drag.value ?: gamma
    val valueRange =
        viewModel.minBrightness.value.toFloat()..viewModel.maxBrightness.value.toFloat()
    val interactionSource = remember { MutableInteractionSource() }
    val isDragging by interactionSource.collectIsDraggedAsState()
    val animatedValue = animateAxQsSliderValue(
        value = displayValue.toFloat(),
        valueRange = valueRange,
        isDragging = isDragging || drag.value != null,
        label = "AxBrightnessSliderValue",
    )
    val percentage = axQsSliderPercentage(displayValue.toFloat(), valueRange)
    val iconRes =
        if (viewModel.autoMode) {
            R.drawable.ic_qs_brightness_auto_on
        } else {
            BrightnessSliderViewModel.getIconForPercentage(percentage)
        }
    val hapticsEnabled =
        rememberObservedSetting(Settings.System.QS_BRIGHTNESS_SLIDER_HAPTIC, true) { context ->
            Settings.System.getIntForUser(
                context.contentResolver,
                Settings.System.QS_BRIGHTNESS_SLIDER_HAPTIC,
                1,
                UserHandle.USER_CURRENT,
            ) != 0
        }
    val hapticsViewModel =
        if (interactive && hapticsEnabled) {
            rememberViewModel(traceName = "AxBrightnessSliderHaptics") {
                viewModel.hapticsViewModelFactory.create(
                    interactionSource,
                    valueRange,
                    Orientation.Horizontal,
                    SliderHapticFeedbackConfig(maxVelocityToScale = 1f),
                    SeekableSliderTrackerConfig(),
                )
            }
        } else null
    val restriction by
        viewModel.policyRestriction.collectAsStateWithLifecycle(
            initialValue = PolicyRestriction.NoRestriction
        )
    val restricted = restriction as? PolicyRestriction.Restricted
    val enabled = restricted == null
    val overriddenByApp by viewModel.brightnessOverriddenByWindow.collectAsStateWithLifecycle()
    val canToggleMode = interactive && longPressEnabled && enabled && !overriddenByApp
    val view = LocalView.current
    val toggleMode: () -> Unit = {
        viewModel.onIconClick()
        if (hapticsEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
    val longPressLabel =
        stringResource(
            if (viewModel.autoMode) R.string.lunaris_qs_slider_manual_brightness
            else R.string.lunaris_qs_slider_auto_brightness
        )
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val contentDescription = stringResource(R.string.accessibility_brightness)
    var dragging by remember(viewModel, drag) { mutableStateOf(false) }
    var temporaryWrite by remember(viewModel, drag) { mutableStateOf<Job?>(null) }
    val currentOnDraggingChanged by rememberUpdatedState(onDraggingChanged)
    val inputModifier =
        if (interactive) {
            Modifier
                .semantics(mergeDescendants = true) {
                    text = AnnotatedString(contentDescription)
                    if (canToggleMode) {
                        onLongClick(label = longPressLabel) {
                            toggleMode()
                            true
                        }
                    }
                }
                .sliderPercentage {
                    (displayValue - valueRange.start) / (valueRange.endInclusive - valueRange.start)
                }
                .pointerInteropFilter {
                    if (
                        it.actionMasked == MotionEvent.ACTION_UP ||
                            it.actionMasked == MotionEvent.ACTION_CANCEL
                    ) {
                        viewModel.emitBrightnessTouchForFalsing()
                    }
                    false
                }
                .then(
                    if (restricted != null) {
                        Modifier.clickable { viewModel.showPolicyRestrictionDialog(restricted) }
                    } else {
                        Modifier
                    }
                )
        } else {
            Modifier
        }

    fun finishDrag(commit: Boolean) {
        if (!dragging) return
        dragging = false
        temporaryWrite?.cancel()
        temporaryWrite = null
        val brightness = drag.finish(interactionSource)
        if (brightness != null) {
            if (commit) {
                coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.onDrag(Drag.Stopped(GammaBrightness(brightness)))
                }
            } else {
                viewModel.cancelDrag()
            }
        }
        hapticsViewModel?.onValueChangeEnded()
        if (drag.value == null) viewModel.setIsDragging(false)
        currentOnDraggingChanged(false)
    }

    DisposableEffect(viewModel, drag, interactionSource) {
        onDispose { finishDrag(false) }
    }

    LaunchedEffect(interactionSource, drag, viewModel, enabled, overriddenByApp) {
        if (!enabled || overriddenByApp) finishDrag(false)
        interactionSource.interactions.collect { interaction ->
            when {
                interaction is DragInteraction.Cancel -> finishDrag(false)
                interaction is DragInteraction.Start && overriddenByApp ->
                    viewModel.showToast(context, R.string.quick_settings_brightness_unable_adjust_msg)
            }
        }
    }

    AxQsSlider(
        value = animatedValue,
        percentage = percentage,
        onValueChange = { newValue ->
            if (interactive && enabled && !overriddenByApp) {
                hapticsViewModel?.onValueChange(newValue)
                val brightness = newValue.toInt()
                drag.update(interactionSource, brightness)
                if (!dragging) {
                    dragging = true
                    viewModel.setIsDragging(true)
                    onDraggingChanged(true)
                }
                temporaryWrite?.cancel()
                temporaryWrite = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    viewModel.onDrag(Drag.Dragging(GammaBrightness(brightness)))
                }
            }
        },
        onValueChangeFinished = { finishDrag(interactive && enabled && !overriddenByApp) },
        valueRange = valueRange,
        enabled = enabled,
        interactionSource = interactionSource,
        sliderHeight = sliderHeight,
        vertical = vertical,
        sliderStyle = sliderStyle,
        trackBackgroundColor = if (mirrorInOverlay) inactiveTrackColor else Color.Transparent,
        inactiveTrackColor = inactiveTrackColor,
        onLongPress = toggleMode.takeIf { canToggleMode },
        icon = { iconModifier ->
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = iconModifier,
            )
        },
        modifier =
            modifier
                .fillMaxWidth()
                .sysuiResTag("ax_brightness_slider")
                .then(inputModifier),
    )
}

@Composable
internal fun AxQsVolumeControl(
    vertical: Boolean,
    sliderStyle: LunarisQsSliderStyle,
    viewModel: AudioStreamSliderViewModel,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    longPressEnabled: Boolean = true,
    inactiveTrackColor: Color = AxTileDefaults.backgroundColor(),
) {
    val state by viewModel.slider.collectAsStateWithLifecycle()
    AxQsSliderControl(
        vertical = vertical,
        interceptParentScroll = interactive,
        modifier = modifier,
    ) { sliderHeight, rotated ->
        AxVolumeSliderContent(
            state = state,
            viewModel = viewModel,
            interactive = interactive,
            longPressEnabled = longPressEnabled,
            inactiveTrackColor = inactiveTrackColor,
            sliderHeight = sliderHeight,
            vertical = rotated,
            sliderStyle = sliderStyle,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AxVolumeSlider(
    viewModel: AudioStreamSliderViewModel,
    sliderHeight: Dp,
    vertical: Boolean,
    sliderStyle: LunarisQsSliderStyle,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.slider.collectAsStateWithLifecycle()
    AxVolumeSliderContent(
        state = state,
        viewModel = viewModel,
        sliderHeight = sliderHeight,
        vertical = vertical,
        sliderStyle = sliderStyle,
        interactive = interactive,
        modifier = modifier,
    )
}

@Composable
private fun AxVolumeSliderContent(
    state: SliderState,
    viewModel: AudioStreamSliderViewModel,
    sliderHeight: Dp,
    vertical: Boolean,
    sliderStyle: LunarisQsSliderStyle,
    interactive: Boolean = true,
    longPressEnabled: Boolean = true,
    inactiveTrackColor: Color = AxTileDefaults.backgroundColor(),
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isDragging by interactionSource.collectIsDraggedAsState()
    val smooth = LocalSmoothVolumeSlider.current
    val drag = LocalVolumeDrag.current ?: remember { AxQsSliderDrag<Float>() }
    DisposableEffect(drag, interactionSource, smooth, state.valueRange, state.isEnabled) {
        onDispose { drag.finish(interactionSource) }
    }
    val displayValue = if (smooth) drag.value ?: state.value else state.value
    val value = key(state is SliderState.Empty, state.isEnabled, state.valueRange) {
        animateAxQsSliderValue(
            value = displayValue,
            valueRange = state.valueRange,
            isDragging = isDragging || (smooth && drag.value != null),
            label = "AxVolumeSliderValue",
        )
    }
    val hapticsEnabled =
        rememberObservedSetting(Settings.System.QS_VOLUME_SLIDER_HAPTIC, true) { context ->
            Settings.System.getIntForUser(
                context.contentResolver,
                Settings.System.QS_VOLUME_SLIDER_HAPTIC,
                1,
                UserHandle.USER_CURRENT,
            ) != 0
        }
    val ringerViewModel = LocalRingerSliderViewModel.current
    val ringerMode by
        ringerViewModel.ringerModeChanges.collectAsStateWithLifecycle(
            initialValue = ringerViewModel.currentMode
        )
    val isZenMuted by ringerViewModel.isZenMuted.collectAsStateWithLifecycle()
    val canToggleMode = interactive && longPressEnabled && state.isEnabled && !isZenMuted
    val view = LocalView.current
    val toggleMode: () -> Unit = {
        val modes = ringerViewModel.availableModes
        val index = modes.indexOfFirst { it.mode == ringerViewModel.currentMode }
        ringerViewModel.setRingerMode(modes[(index + 1) % modes.size].mode)
        if (hapticsEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }
    val longPressLabel = stringResource(R.string.lunaris_qs_slider_ringer_mode)
    val ringerDescription =
        stringResource(
            when (ringerMode) {
                AudioManager.RINGER_MODE_VIBRATE -> R.string.ax_dynamic_bar_vibrate
                AudioManager.RINGER_MODE_SILENT -> R.string.ax_dynamic_bar_silent
                else -> R.string.ax_dynamic_bar_ring
            }
        )
    val hapticsFactory =
        if (interactive && hapticsEnabled) viewModel.getSliderHapticsViewModelFactory() else null
    val hapticsViewModel =
        if (smooth) {
            hapticsFactory?.let { factory ->
                rememberViewModel(traceName = "AxSmoothVolumeSliderHaptics", key = state.valueRange) {
                    factory.create(
                        interactionSource,
                        state.valueRange,
                        Orientation.Horizontal,
                        SliderHapticFeedbackConfig(maxVelocityToScale = 1f),
                        SeekableSliderTrackerConfig(),
                    )
                }
            }
        } else {
            axVolumeHapticsViewModel(state.value, state, interactionSource, hapticsFactory)
        }

    if (smooth && interactive) {
        LaunchedEffect(drag, interactionSource, hapticsViewModel) {
            interactionSource.interactions.collect { interaction ->
                if (interaction is DragInteraction.Cancel && drag.finish(interactionSource) != null) {
                    hapticsViewModel?.onValueChangeEnded()
                }
            }
        }
    }

    AxQsSlider(
        value = value,
        valueRange = state.valueRange,
        percentage = axQsSliderPercentage(displayValue, state.valueRange),
        onValueChange = { newValue ->
            if (smooth && interactive) {
                drag.update(interactionSource, newValue)
                hapticsViewModel?.onValueChange(newValue)
            } else {
                hapticsViewModel?.addVelocityDataPoint(newValue)
            }
            if (interactive) viewModel.onValueChanged(state, newValue)
        },
        onValueChangeFinished = {
            drag.finish(interactionSource)
            hapticsViewModel?.onValueChangeEnded()
            if (interactive) viewModel.onValueChangeFinished()
        },
        enabled = state.isEnabled,
        interactionSource = interactionSource,
        sliderHeight = sliderHeight,
        vertical = vertical,
        sliderStyle = sliderStyle,
        inactiveTrackColor = inactiveTrackColor,
        onLongPress = toggleMode.takeIf { canToggleMode },
        icon = { iconModifier ->
            val icon = state.icon
            val ringerIcon =
                when (ringerMode) {
                    AudioManager.RINGER_MODE_VIBRATE -> R.drawable.ic_volume_ringer_vibrate
                    AudioManager.RINGER_MODE_SILENT -> R.drawable.ic_volume_off
                    else -> null
                }
            if (ringerIcon != null) {
                Icon(
                    painter = painterResource(ringerIcon),
                    contentDescription = null,
                    modifier = iconModifier,
                )
            } else if (icon != null) {
                SystemUiIcon(icon = icon, modifier = iconModifier)
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_music_note),
                    contentDescription = null,
                    modifier = iconModifier,
                )
            }
        },
        modifier =
            modifier.sysuiResTag("ax_volume_slider").clearAndSetSemantics {
                if (state.isEnabled) {
                    contentDescription = state.a11yContentDescription
                    stateDescription =
                        listOfNotNull(state.a11yStateDescription, ringerDescription).joinToString(", ")
                    progressBarRangeInfo = ProgressBarRangeInfo(displayValue, state.valueRange)
                    if (interactive) {
                        if (canToggleMode) {
                            onLongClick(label = longPressLabel) {
                                toggleMode()
                                true
                            }
                        }
                        setProgress { targetValue ->
                            val direction =
                                when {
                                    targetValue > state.value -> 1
                                    targetValue < state.value -> -1
                                    else -> 0
                                }
                            viewModel.onValueChanged(
                                state,
                                (state.value + direction * state.step).coerceIn(
                                    state.valueRange.start,
                                    state.valueRange.endInclusive,
                                ),
                            )
                            true
                        }
                    }
                } else {
                    disabled()
                    contentDescription =
                        state.disabledMessage?.let { "${state.label}, $it" } ?: state.label
                }
            },
    )
}

@Composable
private fun axVolumeHapticsViewModel(
    value: Float,
    state: SliderState,
    interactionSource: MutableInteractionSource,
    factory: SliderHapticsViewModel.Factory?,
): SliderHapticsViewModel? {
    val isDragging by interactionSource.collectIsDraggedAsState()
    return factory?.let {
        val configs =
            VolumeHapticsConfigsProvider.discreteConfigs(
                1f / (state.valueRange.endInclusive - state.valueRange.start),
                state.hapticFilter,
            )
        rememberViewModel(traceName = "AxVolumeSliderHaptics", key = state.valueRange) {
                it.create(
                    interactionSource,
                    state.valueRange,
                    Orientation.Horizontal,
                    configs.hapticFeedbackConfig,
                    configs.sliderTrackerConfig,
                )
            }
            .also { hapticsViewModel ->
                var lastStep by remember(hapticsViewModel) { mutableFloatStateOf(round(value)) }
                LaunchedEffect(value, isDragging) {
                    val step = round(value)
                    if (step != lastStep) {
                        lastStep = step
                        if (isDragging) {
                            hapticsViewModel.onValueChange(step)
                        }
                    }
                    if (!isDragging) hapticsViewModel.onValueChangeEnded()
                }
            }
    }
}
