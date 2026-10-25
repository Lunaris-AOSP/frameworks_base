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

import android.os.BatteryManager
import android.widget.TextClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel

@Composable
internal fun LunarisBarChip(
    viewModel: PopupChipModel.Shown,
    pageCount: Int,
    cutoutSpec: DynamicIslandCutoutSpec,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onChipBoundsChanged: (Rect) -> Unit = {},
    popupAttached: Boolean = viewModel.isPopupShown,
    onPage: (Int) -> Unit = {},
    companion: Boolean = false,
    onCard: (String) -> Unit = {},
) {
    LunarisBarContentTheme(viewModel.popupContent, viewModel.chipId) {
        LunarisBarChipContent(
            viewModel,
            pageCount,
            cutoutSpec,
            onTap,
            modifier,
            onChipBoundsChanged,
            popupAttached,
            onPage,
            companion,
            onCard,
        )
    }
}

@Composable
private fun LunarisBarChipContent(
    viewModel: PopupChipModel.Shown,
    pageCount: Int,
    cutoutSpec: DynamicIslandCutoutSpec,
    onTap: () -> Unit,
    modifier: Modifier,
    onChipBoundsChanged: (Rect) -> Unit,
    popupAttached: Boolean,
    onPage: (Int) -> Unit,
    companion: Boolean,
    onCard: (String) -> Unit,
) {
    val state = LocalLunarisBarState.current
    val settings = state.settings
    val alignment = LunarisBarAlignment.fromSetting(settings.alignment)
    val layoutDirection = LocalLayoutDirection.current
    val colors = LocalLunarisBarColors.current
    val context = LocalContext.current
    val view = LocalView.current
    val media = (viewModel.popupContent as? PopupContentModel.Media)?.model
    val utility = (viewModel.popupContent as? PopupContentModel.Utility)?.card
    val resting =
        utility?.id == "lunarisbar_home" ||
            (utility != null && !utility.enabled("mini_enabled", true) && !companion) ||
            (media != null && !settings.enabled("media_mini", true) && !companion)
    val battery = if (utility?.type == "battery") rememberLunarisBarBattery() else null
    val batteryScale =
        (battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
    val batteryLevel =
        (battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0) * 100 / batteryScale
    val temperature = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    @Composable
    fun batteryPart(key: String, fallback: Int) {
        when (utility!!.integer(key, fallback, 0, 2)) {
            0 ->
                CircularProgressIndicator(
                    progress = { batteryLevel / 100f },
                    modifier = Modifier.size(20.dp),
                    color = colors.miniContent,
                    strokeWidth = 3.dp,
                )
            1 ->
                Text(
                    "$batteryLevel%",
                    color = colors.miniContent,
                    style = MaterialTheme.typography.labelMedium,
                )
            else ->
                Text(
                    if (utility.enabled("fahrenheit", false))
                        "${(temperature * 1.8f + 32f).toInt()}°F"
                    else "${temperature.toInt()}°C",
                    color = colors.miniContent,
                    style = MaterialTheme.typography.labelMedium,
                )
        }
    }
    val (widthScale, heightScale) = rememberDynamicIslandSizeScale()
    val scale = settings.miniScale
    val sideCamera =
        !companion && alignment != LunarisBarAlignment.CENTER && cutoutSpec.cutoutBounds != null
    val gapAtStart =
        (alignment == LunarisBarAlignment.LEFT) == (layoutDirection == LayoutDirection.Ltr)
    val cameraGap = if (companion) 0.dp else cutoutSpec.embeddedGapWidth
    val appear =
        remember(companion) { Animatable(if (companion && !settings.isReducedMotion) 0.3f else 1f) }
    LaunchedEffect(companion, settings.isReducedMotion) {
        if (settings.isReducedMotion) appear.snapTo(1f)
        else appear.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = 480f))
    }
    val collapse by
        animateFloatAsState(
            if (viewModel.isPopupShown) 0f else 1f,
            tween(if (settings.isReducedMotion) 0 else (240 / settings.animationSpeed).toInt()),
            label = "lunarisbar_compact",
        )
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(
        media?.isPlaying,
        settings.isArtworkRotating,
        settings.isReducedMotion,
        popupAttached,
    ) {
        if (
            media?.isPlaying != true ||
                !settings.isArtworkRotating ||
                settings.isReducedMotion ||
                popupAttached
        )
            return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) rotation = (rotation + (now - last) / 1e9f * 18f) % 360f
                last = now
            }
        }
    }
    fun perform(action: String) {
        if (settings.isHapticsEnabled)
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        lunarisBarAction(action, context, viewModel, onTap, onPage, onCard)
    }
    val ownMedia = media != null && settings.enabled("media_mini_custom", false)
    val event = viewModel.popupContent as? PopupContentModel.SystemEvent
    val styledFeature = viewModel.popupContent.lunarisBarFeature(viewModel.chipId) != null
    val ownLeft = ownMedia || (styledFeature && settings.integer("left_palette", -1, -1, 20) >= 0)
    val ownRight = ownMedia || (styledFeature && settings.integer("right_palette", -1, -1, 20) >= 0)
    val shape =
        lunarisBarIconShape(
            settings.integer(if (ownMedia) "media_mini_left_shape" else "mini_shape", 2, 0, 31)
        )
    val rightShape =
        lunarisBarIconShape(
            settings.integer(if (ownMedia) "media_mini_right_shape" else "right_shape", 2, 0, 31)
        )
    val leftPalette =
        rememberLunarisBarPalette(
            settings.integer(
                if (media != null) "media_mini_left_palette" else "left_palette",
                0,
                0,
                20,
            ),
            lunarisBarPartColors(
                if (media != null) "media_mini_left" else "left",
                colors.miniSurface to colors.miniContent,
            ),
        )
    val rightPalette =
        rememberLunarisBarPalette(
            settings.integer(
                if (media != null) "media_mini_right_palette" else "right_palette",
                0,
                0,
                20,
            ),
            lunarisBarPartColors(
                if (media != null) "media_mini_right" else "right",
                colors.miniSurface to colors.miniContent,
            ),
        )
    val legacy =
        if (state.alternativeAnimation && !settings.isReducedMotion)
            rememberDynamicIslandCollapseState(viewModel.isPopupShown, popupAttached)
        else null
    val artwork = media?.artworkIcon ?: viewModel.icons.firstOrNull()?.icon
    val dimensions =
        (if (resting) settings.integer("camera_height", 24, 8, 64).dp else 32.dp) *
            heightScale *
            scale
    LunarisBarSurface(
        mini = true,
        resting = resting,
        shape = if (companion) CircleShape else LunarisBarSquircle(settings.cornerRadius.dp),
        modifier =
            modifier
                .then(
                    if (companion)
                        Modifier.size(DynamicIslandCompanionDiameter * heightScale * scale)
                    else Modifier
                )
                .onGloballyPositioned { coordinates ->
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    val rootLocation = IntArray(2)
                    view.getLocationInWindow(rootLocation)
                    onChipBoundsChanged(
                        coordinates
                            .boundsInWindow()
                            .translate(
                                androidx.compose.ui.geometry.Offset(
                                    (location[0] - rootLocation[0]).toFloat(),
                                    (location[1] - rootLocation[1]).toFloat(),
                                )
                            )
                    )
                }
                .graphicsLayer {
                    alpha =
                        if (legacy != null) 1f
                        else if (settings.enabled("mini_fade", true)) collapse
                        else if (viewModel.isPopupShown) 0f else 1f
                    scaleX = appear.value * (if (legacy != null) 1f else 0.78f + 0.22f * collapse)
                    scaleY = scaleX
                    transformOrigin =
                        TransformOrigin(
                            0.5f,
                            when (settings.scaleAlignment) {
                                1 -> 0f
                                2 -> 1f
                                else -> 0.5f
                            },
                        )
                }
                .then(
                    if (legacy != null)
                        Modifier.islandCompactMorph(legacy, colors.outline, drawBackground = false)
                    else Modifier
                )
                .semantics {
                    contentDescription =
                        viewModel.contentDescription ?: viewModel.chipText.orEmpty()
                },
    ) {
        Row(
            modifier =
                Modifier.graphicsLayer {
                        if (legacy != null) {
                            alpha = legacy.contentAlpha
                            scaleX = legacy.contentScale
                            scaleY = legacy.contentScale
                        }
                    }
                    .then(
                        if (companion) Modifier.fillMaxSize()
                        else Modifier.defaultMinSize(minHeight = dimensions)
                    )
                    .widthIn(max = 240.dp * widthScale + if (sideCamera) cameraGap else 0.dp)
                    .combinedClickable(
                        onClick = {
                            perform(
                                if (companion) "expand" else settings.text("gesture_tap", "expand")
                            )
                        },
                        onDoubleClick =
                            settings
                                .text("gesture_double_tap", "none")
                                .takeUnless { it == "none" || companion }
                                ?.let { action -> { perform(action) } },
                        onLongClick =
                            if (companion) null
                            else ({ perform(settings.text("gesture_long_press", "open_app")) }),
                    )
                    .padding(
                        start =
                            if (companion) 0.dp
                            else
                                5.dp * scale +
                                    if (sideCamera && !resting && gapAtStart) cameraGap else 0.dp,
                        end =
                            if (companion) 0.dp
                            else
                                5.dp * scale +
                                    if (sideCamera && !resting && !gapAtStart) cameraGap else 0.dp,
                        top = if (companion) 0.dp else 3.dp * scale,
                        bottom = if (companion) 0.dp else 3.dp * scale,
                    ),
            horizontalArrangement =
                if (companion) Arrangement.Center else Arrangement.spacedBy(5.dp * scale),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (resting) {
                Spacer(
                    Modifier.width(
                        if (cutoutSpec.cutoutBounds != null) cameraGap
                        else settings.integer("camera_width", 25, 0, 120).dp
                    )
                )
            } else {
                if (utility != null && (companion || utility.type != "battery")) {
                    androidx.compose.material3.Icon(
                        lunarisBarUtilityGlyph(utility.type),
                        null,
                        modifier =
                            Modifier.size(
                                    if (companion) 22.dp * heightScale * scale
                                    else dimensions - 6.dp * scale
                                )
                                .clip(shape),
                        tint = colors.miniContent,
                    )
                } else if (companion && artwork != null) {
                    Icon(
                        artwork,
                        modifier = Modifier.size(22.dp * heightScale * scale).clip(shape),
                        tint =
                            if (media != null || viewModel.icons.firstOrNull()?.tint == false)
                                Color.Unspecified
                            else colors.miniContent,
                    )
                } else if (utility?.type == "battery") batteryPart("battery_left", 0)
                else if (
                    (!styledFeature || settings.integer("left_content", 0, 0, 2) != 2) &&
                        artwork != null &&
                        (media == null || settings.enabled("media_mini", true))
                ) {
                    Icon(
                        artwork,
                        modifier =
                            Modifier.size(dimensions - 6.dp * scale)
                                .graphicsLayer { rotationZ = if (media != null) rotation else 0f }
                                .clip(shape)
                                .then(
                                    if (ownLeft) Modifier.background(leftPalette.first, shape)
                                    else Modifier
                                )
                                .then(
                                    if (
                                        media != null && settings.enabled("media_thin_border", true)
                                    )
                                        Modifier.border(
                                            0.5.dp,
                                            colors.miniContent.copy(alpha = 0.35f),
                                            shape,
                                        )
                                    else Modifier
                                ),
                        tint =
                            if (media != null || viewModel.icons.firstOrNull()?.tint == false)
                                androidx.compose.ui.graphics.Color.Unspecified
                            else if (ownLeft) leftPalette.second else colors.miniContent,
                    )
                }
                if (!companion && alignment == LunarisBarAlignment.CENTER) {
                    Spacer(Modifier.width(cameraGap))
                }
                if (!companion && media != null && settings.enabled("media_mini", true)) {
                    val appIcon = media.appIcon
                    if (
                        settings.integer("media_mini_right", 1, 0, 1) == 1 &&
                            settings.isEqualizerEnabled &&
                            media.isPlaying
                    ) {
                        AudioReactiveBars(
                            isPlaying = !settings.isReducedMotion,
                            color = if (ownMedia) rightPalette.second else colors.miniContent,
                            artworkDrawable = (media.artworkIcon as? IconModel.Loaded)?.drawable,
                        )
                    } else if (appIcon != null) {
                        Icon(
                            appIcon,
                            modifier =
                                Modifier.size(20.dp * scale)
                                    .clip(rightShape)
                                    .then(
                                        if (ownMedia) Modifier.background(rightPalette.first)
                                        else Modifier
                                    ),
                            tint = androidx.compose.ui.graphics.Color.Unspecified,
                        )
                    }
                } else if (!companion && utility?.type == "battery") {
                    batteryPart("battery_right", 1)
                } else if (!companion && utility?.type == "clock") {
                    AndroidView(
                        factory = { TextClock(it) },
                        update = {
                            it.format12Hour =
                                if (utility.enabled("seconds", false)) "h:mm:ss" else "h:mm"
                            it.format24Hour =
                                if (utility.enabled("seconds", false)) "HH:mm:ss" else "HH:mm"
                            it.timeZone =
                                utility.text("timezone", "").takeIf { zone -> zone.isNotBlank() }
                            it.setTextColor(colors.miniContent.toArgb())
                            it.textSize = 12f
                        },
                    )
                } else if (
                    !companion && styledFeature && settings.integer("right_content", 0, 0, 3) == 1
                ) {
                    (event?.icon ?: viewModel.icons.firstOrNull()?.icon)?.let {
                        Icon(
                            it,
                            modifier =
                                Modifier.size(24.dp)
                                    .clip(rightShape)
                                    .then(
                                        if (ownRight) Modifier.background(rightPalette.first)
                                        else Modifier
                                    ),
                            tint =
                                if (it is IconModel.Loaded) Color.Unspecified
                                else if (ownRight) rightPalette.second else colors.miniContent,
                        )
                    }
                } else if (
                    !companion &&
                        event != null &&
                        settings.integer("right_content", 0, 0, 3) == 2 &&
                        event.progress != null
                ) {
                    CircularProgressIndicator(
                        progress = { event.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.size(24.dp),
                        color = if (ownRight) rightPalette.second else colors.miniContent,
                        strokeWidth = 3.dp,
                    )
                } else if (
                    !companion &&
                        (!styledFeature || settings.integer("right_content", 0, 0, 3) != 3)
                ) {
                    val value =
                        when {
                            viewModel.popupContent is PopupContentModel.Flashlight ->
                                viewModel.chipText
                            event?.kind ==
                                com.android.systemui.statusbar.featurepods.popups.ui.model
                                    .SystemEventKind
                                    .Timer -> rememberTimerText(event)
                            event?.kind ==
                                com.android.systemui.statusbar.featurepods.popups.ui.model
                                    .SystemEventKind
                                    .Bluetooth ->
                                if (settings.enabled("feature_bluetooth_show_battery", true))
                                    event.bluetoothBatteries
                                        .minOfOrNull { it.level }
                                        ?.let { "$it%" }
                                        ?: event.progress?.let { "${(it * 100).toInt()}%" }
                                else null
                            event?.kind in
                                listOf(
                                    com.android.systemui.statusbar.featurepods.popups.ui.model
                                        .SystemEventKind
                                        .Charging,
                                    com.android.systemui.statusbar.featurepods.popups.ui.model
                                        .SystemEventKind
                                        .Notification,
                                    com.android.systemui.statusbar.featurepods.popups.ui.model
                                        .SystemEventKind
                                        .Ongoing,
                                    com.android.systemui.statusbar.featurepods.popups.ui.model
                                        .SystemEventKind
                                        .Hotspot,
                                ) -> viewModel.chipText
                            viewModel.callStartTimeMs != null ||
                                viewModel.popupContent is PopupContentModel.Stopwatch ->
                                rememberChipText(viewModel)
                            else -> viewModel.chipText
                        }
                    if (!value.isNullOrBlank())
                        Text(
                            value,
                            color = if (ownRight) rightPalette.second else colors.miniContent,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier.widthIn(max = 116.dp)
                                    .clip(rightShape)
                                    .then(
                                        if (ownRight)
                                            Modifier.background(rightPalette.first)
                                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                        else Modifier
                                    )
                                    .padding(end = 5.dp),
                        )
                }
                if (!companion && pageCount > 1) {
                    Box(
                        Modifier.size(4.dp)
                            .background(colors.miniContent.copy(alpha = 0.65f), CircleShape)
                    )
                }
            }
        }
    }
}
