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

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandScale
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.SystemEventKind
import com.android.systemui.statusbar.featurepods.screenrecord.shared.model.ScreenRecordPopupModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Single centered status bar capsule styled like a compact dynamic island. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatusBarDynamicIslandChip(
    viewModel: PopupChipModel.Shown,
    pageCount: Int,
    cutoutSpec: DynamicIslandCutoutSpec,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onChipBoundsChanged: (Rect) -> Unit = {},
    popupAttached: Boolean = viewModel.isPopupShown,
) {
    val isMediaChip = viewModel.popupContent is PopupContentModel.Media
    val colors = viewModel.colors
    val (widthScale, heightScale) = rememberDynamicIslandSizeScale()
    val chipContentColor =
        colors.chipContent(
            isPopupShown = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val chipOutline =
        colors.chipOutline(
            isPopupShown = viewModel.isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val view = LocalView.current
    val boundsModifier =
        Modifier.onGloballyPositioned { coordinates ->
            onChipBoundsChanged(coordinates.boundsInScreen(view))
        }
    val hapticOnTap: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        onTap()
    }
    val mediaOpenApp: (() -> Unit)? =
        (viewModel.popupContent as? PopupContentModel.Media)
            ?.takeIf { it.model.isPlaying }
            ?.model
            ?.openApp
    val hapticOnLongPress: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        mediaOpenApp?.invoke()
    }
    if (viewModel.popupContent.isUtilityStatusContent() && viewModel.icons.isNotEmpty()) {
        UtilityStatusIslandChip(
            viewModel = viewModel,
            onTap = hapticOnTap,
            cutoutSpec = cutoutSpec,
            widthScale = widthScale,
            heightScale = heightScale,
            chipContentColor = chipContentColor,
            chipOutline = chipOutline,
            popupAttached = popupAttached,
            modifier = modifier.then(boundsModifier),
        )
        return
    }

    val compactWidth = compactIslandWidthFor(viewModel.popupContent)?.times(widthScale)
    val hasInlineTimer = viewModel.popupContent is PopupContentModel.Stopwatch
    val trailingDecorationWidth =
        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.Media ->
                if (popupContent.model.isPlaying) {
                    14.dp
                } else if (pageCount > 1) {
                    11.dp
                } else {
                    0.dp
                }
            is PopupContentModel.ScreenRecord -> 11.dp
            else -> {
                if (pageCount > 1) 11.dp else 0.dp
            }
        }
    val leadingDecorationWidth =
        when {
            viewModel.icons.isEmpty() -> 0.dp
            else -> 18.dp + (8.dp * (viewModel.icons.size - 1))
        }
    val maxTextWidth =
        ((CompactIslandMaxWidth * widthScale) -
                24.dp -
                leadingDecorationWidth -
                trailingDecorationWidth)
            .coerceAtLeast(56.dp)

    val collapseState = rememberDynamicIslandCollapseState(viewModel.isPopupShown, popupAttached)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by rememberIslandPressScale(isPressed)

    Row(
        modifier =
            modifier
                .then(boundsModifier)
                .defaultMinSize(minHeight = 32.dp * heightScale)
                .widthIn(
                    min = compactWidth ?: 0.dp,
                    max = compactWidth ?: (CompactIslandMaxWidth * widthScale),
                )
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .islandCompactMorph(collapseState, chipOutline)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = hapticOnTap,
                    onLongClick = mediaOpenApp?.let { { hapticOnLongPress() } },
                )
                .padding(
                    start = 8.dp * widthScale,
                    end = 12.dp * widthScale,
                    top = 7.dp * heightScale,
                    bottom = 7.dp * heightScale,
                )
                .graphicsLayer {
                    alpha = collapseState.contentAlpha
                    scaleX = collapseState.contentScale
                    scaleY = collapseState.contentScale
                },
        horizontalArrangement =
            if (isMediaChip) Arrangement.SpaceBetween else Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        viewModel.icons.forEachIndexed { index, chipIcon ->
            val isArtworkLike =
                index == 0 &&
                    (viewModel.popupContent is PopupContentModel.Media ||
                        viewModel.popupContent is PopupContentModel.LiveScore)
            Icon(
                icon = chipIcon.icon,
                modifier =
                    Modifier.size(if (isArtworkLike) 18.dp else 16.dp)
                        .then(
                            if (isArtworkLike) {
                                Modifier.clip(CircleShape)
                            } else {
                                Modifier
                            }
                        ),
                tint = if (isArtworkLike || !chipIcon.tint) Color.Unspecified else chipContentColor,
            )
        }

        rememberChipText(viewModel)
            ?.takeIf {
                !isMediaChip &&
                    viewModel.popupContent !is PopupContentModel.ScreenRecord &&
                    !hasInlineTimer &&
                    it.isNotBlank()
            }
            ?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = chipContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = maxTextWidth),
                )
            }

        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.Media -> {
                val artworkDrawable =
                    remember(popupContent.model.artworkIcon) {
                        (popupContent.model.artworkIcon as? IconModel.Loaded)?.drawable
                    }
                if (popupContent.model.isPlaying) {
                    AudioReactiveBars(
                        isPlaying = true,
                        color = IslandAccents.Music,
                        artworkDrawable = artworkDrawable,
                    )
                } else if (pageCount > 1) {
                    SwipeHint(color = chipContentColor.copy(alpha = 0.72f))
                }
            }
            is PopupContentModel.ScreenRecord ->
                when (val model = popupContent.model) {
                    is ScreenRecordPopupModel.Starting ->
                        StatusContent(
                            text = viewModel.chipText.orEmpty(),
                            color = chipContentColor,
                            showSwipeHint = pageCount > 1,
                        )
                    is ScreenRecordPopupModel.Recording ->
                        StatusContent(
                            text = viewModel.chipText.orEmpty(),
                            color = chipContentColor,
                            showSwipeHint = pageCount > 1,
                        )
                }
            is PopupContentModel.Stopwatch ->
                StatusContent(
                    text = rememberStopwatchText(popupContent.model),
                    color = chipContentColor,
                    showSwipeHint = pageCount > 1,
                )
            else -> {
                if (pageCount > 1) {
                    SwipeHint(color = chipContentColor.copy(alpha = 0.72f))
                }
            }
        }
    }
}

@Composable
private fun UtilityStatusIslandChip(
    viewModel: PopupChipModel.Shown,
    onTap: () -> Unit,
    cutoutSpec: DynamicIslandCutoutSpec,
    widthScale: Float = 1f,
    heightScale: Float = 1f,
    chipContentColor: Color,
    chipOutline: Color,
    popupAttached: Boolean,
    modifier: Modifier = Modifier,
) {
    val collapseState = rememberDynamicIslandCollapseState(viewModel.isPopupShown, popupAttached)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by rememberIslandPressScale(isPressed)
    val isSystemEvent = viewModel.popupContent is PopupContentModel.SystemEvent
    val isFlashlight = viewModel.popupContent is PopupContentModel.Flashlight
    val hasBalancedSegments = isSystemEvent || isFlashlight
    val deviceImage =
        (viewModel.popupContent as? PopupContentModel.SystemEvent)
            ?.takeIf { it.kind == SystemEventKind.Bluetooth }
            ?.image
    val deviceImageSize = 20.dp * widthScale
    val deviceImageSpace = if (deviceImage != null) deviceImageSize + 6.dp * widthScale else 0.dp
    val omitCameraGap =
        when (val content = viewModel.popupContent) {
            is PopupContentModel.Stopwatch -> true
            is PopupContentModel.SystemEvent ->
                content.kind == SystemEventKind.Bluetooth ||
                    content.kind == SystemEventKind.Charging ||
                    content.kind == SystemEventKind.Unlock ||
                    content.kind == SystemEventKind.Timer
            else -> false
        }
    val cameraGapWidth = if (omitCameraGap) 0.dp else cutoutSpec.embeddedGapWidth
    val liveChipText = rememberChipText(viewModel).orEmpty()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textEndPadding =
        (when {
            isSystemEvent -> 12.dp
            isFlashlight -> 10.dp
            else -> 6.dp
        }) * widthScale
    val eventTextWidth =
        if (isSystemEvent) {
            val measured =
                textMeasurer.measure(
                    liveChipText,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            with(density) { measured.size.width.toDp() } +
                6.dp * widthScale +
                textEndPadding +
                deviceImageSpace
        } else 0.dp
    val rightSegmentWidth =
        (when (viewModel.popupContent) {
                is PopupContentModel.Alarm -> 72.dp
                is PopupContentModel.SystemEvent -> 60.dp
                else -> 80.dp
            } * widthScale)
            .coerceAtLeast(eventTextWidth)
    val connectedIslandWidth =
        if (isSystemEvent) {
            rightSegmentWidth * 2 + cameraGapWidth
        } else
            ((CompactUtilityConnectedIslandChromeWidth * widthScale) +
                    cameraGapWidth +
                    rightSegmentWidth)
                .coerceIn(
                    (if (omitCameraGap) 0.dp else CompactUtilityConnectedIslandMinWidth) *
                        widthScale,
                    CompactUtilityConnectedIslandMaxWidth * widthScale,
                )
    val utilityText =
        when (val popupContent = viewModel.popupContent) {
            is PopupContentModel.ScreenRecord ->
                when (val model = popupContent.model) {
                    is ScreenRecordPopupModel.Starting -> "${model.secondsUntilStarted}s"
                    is ScreenRecordPopupModel.Recording ->
                        rememberElapsedDurationText(model.startElapsedRealtimeMs)
                }
            is PopupContentModel.Stopwatch -> rememberStopwatchText(popupContent.model)
            is PopupContentModel.Alarm -> viewModel.chipText.orEmpty()
            is PopupContentModel.Flashlight -> viewModel.chipText.orEmpty()
            is PopupContentModel.SystemEvent ->
                if (popupContent.kind == SystemEventKind.Timer) rememberTimerText(popupContent)
                else liveChipText
            else -> ""
        }
    Row(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .islandCompactMorph(collapseState, chipOutline)
                .defaultMinSize(minHeight = 32.dp * heightScale)
                .then(
                    if (isFlashlight) {
                        Modifier.width(IntrinsicSize.Max)
                    } else {
                        Modifier.width(connectedIslandWidth)
                    }
                )
                .then(
                    if (isSystemEvent)
                        Modifier.semantics {
                            contentDescription = viewModel.contentDescription.orEmpty()
                        }
                    else Modifier
                )
                .clip(RoundedCornerShape(50))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onTap,
                )
                .graphicsLayer {
                    alpha = collapseState.contentAlpha
                    scaleX = collapseState.contentScale
                    scaleY = collapseState.contentScale
                },
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                (if (isFlashlight) {
                        Modifier.weight(1f)
                    } else {
                        Modifier.width(if (isSystemEvent) rightSegmentWidth else 30.dp * widthScale)
                    })
                    .padding(start = (if (isSystemEvent) 12.dp else 10.dp) * widthScale),
            contentAlignment = Alignment.CenterStart,
        ) {
            val chipIcon = viewModel.icons.first()
            if (isSystemEvent) {
                val event = viewModel.popupContent as PopupContentModel.SystemEvent
                if (event.kind == SystemEventKind.Charging) {
                    ChargingChipGlyph(
                        pulse = event.pulse,
                        modifier = Modifier.size(18.dp * widthScale),
                    )
                } else {
                    Icon(
                        icon = chipIcon.icon,
                        modifier = Modifier.size(18.dp * widthScale),
                        tint = if (chipIcon.tint) chipContentColor else Color.Unspecified,
                    )
                }
            } else {
                CollapsedGlyphBadge(
                    icon = chipIcon.icon,
                    accent = islandChipAccentFor(viewModel.popupContent) ?: chipContentColor,
                    content = viewModel.popupContent,
                    badgeSize = 20.dp * widthScale,
                    iconSize = 13.dp * widthScale,
                )
            }
        }
        Spacer(modifier = Modifier.width(cameraGapWidth))
        Box(
            modifier =
                (if (isFlashlight) Modifier.weight(1f) else Modifier.width(rightSegmentWidth))
                    .padding(
                        start = 6.dp * widthScale,
                        top = 7.dp * heightScale,
                        bottom = 7.dp * heightScale,
                        end = textEndPadding,
                    ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp * widthScale, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = utilityText,
                    style = MaterialTheme.typography.labelLarge,
                    color = chipContentColor,
                    maxLines = 1,
                    softWrap = !isFlashlight,
                    overflow = if (isFlashlight) TextOverflow.Clip else TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = if (isFlashlight) Modifier else Modifier.weight(1f),
                )
                deviceImage?.let { artwork ->
                    Icon(
                        icon = artwork,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(deviceImageSize),
                    )
                }
            }
        }
        if (!hasBalancedSegments) Spacer(modifier = Modifier.width(10.dp * widthScale))
    }
}

@Composable
private fun ChargingChipGlyph(pulse: Boolean, modifier: Modifier = Modifier) {
    val alpha =
        if (pulse) {
            val transition = rememberInfiniteTransition(label = "charging_chip")
            val animatedAlpha by
                transition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec =
                        infiniteRepeatable(
                            tween(800, easing = FastOutSlowInEasing),
                            RepeatMode.Reverse,
                        ),
                    label = "charging_bolt_alpha",
                )
            animatedAlpha
        } else 1f
    Icon(
        icon = IconModel.Resource(R.drawable.dynamic_island_charging, null),
        tint = IslandAccents.Battery,
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}

@Composable
private fun StatusContent(text: String, color: Color, showSwipeHint: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
        if (showSwipeHint) {
            SwipeHint(color = color.copy(alpha = 0.72f))
        }
    }
}

@Composable
private fun SwipeHint(color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) {
            Box(
                modifier =
                    Modifier.size(width = 3.dp, height = 3.dp)
                        .background(color = color, shape = CircleShape)
            )
        }
    }
}

private val CompactIslandMaxWidth = 192.dp
private val CompactMediaIslandWidth = 108.dp
private val CompactTimerIslandWidth = 116.dp
private val CompactRecordingIslandWidth = 88.dp
private val CompactAlarmIslandWidth = 92.dp
private val CompactUtilityIslandWidth = 74.dp
private val CompactUtilityConnectedIslandChromeWidth = 42.dp
private val CompactUtilityConnectedIslandMinWidth = 132.dp
private val CompactUtilityConnectedIslandMaxWidth = 188.dp

internal val DynamicIslandCompanionDiameter = 34.dp
internal val DynamicIslandCompanionGap = 8.dp

private fun PopupContentModel.isUtilityStatusContent(): Boolean {
    return this is PopupContentModel.ScreenRecord ||
        this is PopupContentModel.Stopwatch ||
        this is PopupContentModel.Alarm ||
        this is PopupContentModel.Flashlight ||
        (this is PopupContentModel.SystemEvent &&
            kind in
                setOf(
                    SystemEventKind.Notification,
                    SystemEventKind.Ongoing,
                    SystemEventKind.Unlock,
                    SystemEventKind.Call,
                    SystemEventKind.Bluetooth,
                    SystemEventKind.Ringer,
                    SystemEventKind.Hotspot,
                    SystemEventKind.Charging,
                    SystemEventKind.Recording,
                    SystemEventKind.Timer,
                ))
}

private fun compactIslandWidthFor(content: PopupContentModel): Dp? {
    return when (content) {
        is PopupContentModel.Media -> CompactMediaIslandWidth
        is PopupContentModel.ScreenRecord ->
            when (content.model) {
                is ScreenRecordPopupModel.Starting -> CompactTimerIslandWidth
                is ScreenRecordPopupModel.Recording -> CompactRecordingIslandWidth
            }
        is PopupContentModel.Stopwatch -> CompactTimerIslandWidth
        is PopupContentModel.Alarm -> CompactAlarmIslandWidth
        is PopupContentModel.Flashlight -> CompactUtilityIslandWidth
        else -> null
    }
}

/** Per-feature glyph accent for the collapsed pill — SmartIsland's colored-glyph motif. */
private fun islandChipAccentFor(content: PopupContentModel): Color? =
    when (content) {
        is PopupContentModel.Flashlight -> IslandAccents.Flashlight
        is PopupContentModel.Alarm -> IslandAccents.Alarm
        is PopupContentModel.Stopwatch -> IslandAccents.Stopwatch
        is PopupContentModel.ScreenRecord -> IslandAccents.Recording
        else -> null
    }

/**
 * Collapsed-pill leading glyph wrapped in SmartIsland's circular accent badge, carrying the
 * per-feature motion SmartIsland shows in its collapsed island: a pulsing recording indicator, a
 * slowly spinning stopwatch, and a gently breathing alarm. Features without a motif (flashlight)
 * render a still glyph. Sizes are passed pre-scaled by the island's width scale so the badge stays
 * within the cutout-split chrome at every scale.
 */
@Composable
private fun CollapsedGlyphBadge(
    icon: IconModel,
    accent: Color,
    content: PopupContentModel,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 20.dp,
    iconSize: Dp = 13.dp,
) {
    val transition = rememberInfiniteTransition(label = "collapsed_glyph")
    val breathe by
        transition.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec =
                infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathe",
        )
    val recScale by
        transition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.12f,
            animationSpec =
                infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "rec_scale",
        )
    val recAlpha by
        transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "rec_alpha",
        )
    val spin by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec =
                infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
            label = "spin",
        )

    Box(
        modifier =
            modifier.size(badgeSize).clip(CircleShape).background(IslandAccents.badgeFill(accent)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon = icon,
            modifier =
                Modifier.size(iconSize).graphicsLayer {
                    when (content) {
                        is PopupContentModel.ScreenRecord -> {
                            scaleX = recScale
                            scaleY = recScale
                            alpha = recAlpha
                        }
                        is PopupContentModel.Stopwatch -> rotationZ = spin
                        is PopupContentModel.Alarm -> {
                            scaleX = breathe
                            scaleY = breathe
                        }
                        else -> Unit
                    }
                },
            tint = accent,
        )
    }
}

/**
 * Small icon-only "peek" chip shown beside the main island when several tasks are active, ported
 * from SmartIsland's secondary bubble. It shows album art for media or the feature's accent-tinted
 * glyph otherwise, pops in with a bouncy scale, and squishes on press. It is an independent popup
 * trigger: tapping it opens that task's popup anchored to the peek's own position and folds the
 * peek away in place, without moving it to the centre or disturbing the main chip.
 */
@Composable
internal fun CompanionChip(
    viewModel: PopupChipModel.Shown,
    diameter: Dp,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onBoundsChanged: (Rect) -> Unit = {},
    popupAttached: Boolean = viewModel.isPopupShown,
) {
    val colors = viewModel.colors
    val chipContentColor =
        colors.chipContent(isPopupShown = false, colorScheme = MaterialTheme.colorScheme)
    val chipOutline =
        colors.chipOutline(isPopupShown = false, colorScheme = MaterialTheme.colorScheme)
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by rememberIslandPressScale(isPressed)
    val appear = remember { Animatable(0.3f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = 480f)) }
    val collapseState = rememberDynamicIslandCollapseState(viewModel.isPopupShown, popupAttached)

    Box(
        modifier =
            modifier
                .size(diameter)
                .onGloballyPositioned { onBoundsChanged(it.boundsInScreen(view)) }
                .graphicsLayer {
                    scaleX = pressScale * appear.value
                    scaleY = pressScale * appear.value
                    alpha = appear.value
                }
                .islandCompactMorph(collapseState, chipOutline)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        onTap()
                    },
                )
                .graphicsLayer {
                    alpha = collapseState.contentAlpha
                    scaleX = collapseState.contentScale
                    scaleY = collapseState.contentScale
                },
        contentAlignment = Alignment.Center,
    ) {
        val chipIcon = viewModel.icons.firstOrNull()
        val icon = chipIcon?.icon
        val content = viewModel.popupContent
        if (icon != null) {
            if (content is PopupContentModel.Media) {
                Icon(
                    icon = icon,
                    modifier = Modifier.size(diameter * 0.72f).clip(CircleShape),
                    tint = Color.Unspecified,
                )
            } else {
                Icon(
                    icon = icon,
                    modifier = Modifier.size(diameter * 0.46f),
                    tint =
                        if (chipIcon?.tint == false) Color.Unspecified
                        else islandChipAccentFor(content) ?: chipContentColor,
                )
            }
        }
    }
}

@Composable
internal fun rememberDynamicIslandSizeScale(): Pair<Float, Float> {
    val context = LocalContext.current
    val widthScale by
        remember { observeDynamicIslandScale(context, DynamicIslandFeatureSettings.WIDTH_SCALE) }
            .collectAsState(initial = 1f)
    val heightScale by
        remember { observeDynamicIslandScale(context, DynamicIslandFeatureSettings.HEIGHT_SCALE) }
            .collectAsState(initial = 1f)
    return widthScale to heightScale
}

internal data class DynamicIslandCollapseState(
    val widthFraction: Float,
    val heightFraction: Float,
    val contentAlpha: Float,
    val contentScale: Float,
    val surfaceAlpha: Float,
    val restDiameterPx: Float?,
    val pulseScale: Float,
)

internal fun Modifier.islandCompactMorph(
    state: DynamicIslandCollapseState,
    outline: Color,
    drawBackground: Boolean = true,
): Modifier =
    graphicsLayer {
            scaleX = state.pulseScale
            scaleY = state.pulseScale
        }
        .drawWithContent {
            val rest =
                (state.restDiameterPx ?: (size.height * 0.45f)).coerceAtMost(
                    minOf(size.width, size.height)
                )
            val width = (rest + (size.width - rest) * state.widthFraction).coerceAtLeast(0f)
            val height = (rest + (size.height - rest) * state.heightFraction).coerceAtLeast(0f)
            val left = (size.width - width) / 2f
            val top = (size.height - height) / 2f
            val radius = minOf(width, height) / 2f
            if (drawBackground) {
                drawRoundRect(
                    Color.Black,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(radius),
                    alpha = state.surfaceAlpha,
                )
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    outline,
                    topLeft = Offset(left + stroke / 2f, top + stroke / 2f),
                    size =
                        Size(
                            (width - stroke).coerceAtLeast(0f),
                            (height - stroke).coerceAtLeast(0f),
                        ),
                    cornerRadius = CornerRadius((radius - stroke / 2f).coerceAtLeast(0f)),
                    alpha = state.surfaceAlpha,
                    style = Stroke(stroke),
                )
            }
            val path =
                Path().apply {
                    addRoundRect(
                        RoundRect(left, top, left + width, top + height, CornerRadius(radius))
                    )
                }
            clipPath(path) { this@drawWithContent.drawContent() }
        }

@Composable
internal fun rememberDynamicIslandCollapseState(
    isOpen: Boolean,
    popupAttached: Boolean,
): DynamicIslandCollapseState {
    val initialSize = if (isOpen) 0f else 1f
    val width = remember { IslandMotionSpring(initialSize) }
    val height = remember { IslandMotionSpring(initialSize) }
    val contentAlpha = remember { Animatable(initialSize) }
    val contentScale = remember { Animatable(if (isOpen) 0.9f else 1f) }
    val surfaceAlpha = remember { Animatable(initialSize) }
    val fadeDelay = remember { Animatable(0f) }
    val pulse = remember { Animatable(0f) }
    var wasAttached by remember { mutableStateOf(popupAttached) }
    val restDiameter = islandRestDiameterPx()

    LaunchedEffect(isOpen) {
        coroutineScope {
            launch {
                width.animateTo(
                    if (isOpen) 0f else 1f,
                    if (isOpen) DynamicIslandMotion.springFor(0.34f, 0.86f)
                    else DynamicIslandMotion.springFor(0.38f, 0.70f),
                )
            }
            launch {
                height.animateTo(
                    if (isOpen) 0f else 1f,
                    if (isOpen) DynamicIslandMotion.springFor(0.34f, 0.86f)
                    else DynamicIslandMotion.springFor(0.34f, 0.75f),
                )
            }
        }
    }

    LaunchedEffect(isOpen) {
        coroutineScope {
            launch {
                if (isOpen) {
                    fadeDelay.snapTo(0f)
                    fadeDelay.animateTo(1f, tween(260, easing = LinearEasing))
                    surfaceAlpha.animateTo(0f, DynamicIslandMotion.PillFade)
                } else {
                    surfaceAlpha.animateTo(1f, DynamicIslandMotion.springFor(0.10f, 1f))
                }
            }
            launch {
                contentAlpha.animateTo(
                    if (isOpen) 0f else 1f,
                    if (isOpen) tween(110, easing = LinearEasing)
                    else tween(160, easing = DynamicIslandMotion.ContentIn),
                )
            }
            launch {
                contentScale.animateTo(
                    if (isOpen) 0.9f else 1f,
                    if (isOpen) tween(110, easing = LinearEasing)
                    else tween(160, easing = DynamicIslandMotion.ButtonDown),
                )
            }
        }
    }

    LaunchedEffect(popupAttached) {
        val returning = wasAttached && !popupAttached
        wasAttached = popupAttached
        if (returning) {
            pulse.animateTo(
                0f,
                DynamicIslandMotion.springFor(0.30f, 0.55f, 0.0005f),
                initialVelocity = pulse.velocity + 1.4f,
            )
        }
    }

    return DynamicIslandCollapseState(
        width.value,
        height.value,
        contentAlpha.value,
        contentScale.value,
        surfaceAlpha.value,
        restDiameter,
        1f + pulse.value,
    )
}

private fun LayoutCoordinates.boundsInScreen(view: android.view.View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}
