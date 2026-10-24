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

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Number of concurrently active tasks at which the island starts showing an icon-only companion
 * "peek" chip beside the main one (ported from SmartIsland's secondary bubble). Matches
 * SmartIsland, which reveals its secondary bubble as soon as a second task exists (e.g. Media +
 * Flashlight); the companion then stands in for the two swipe-hint dots.
 */
private const val COMPANION_MIN_TASKS = 2

/** Phone-only centered dynamic island that pages through active popup chips. */
@Composable
fun StatusBarDynamicIslandContainer(
    chips: List<PopupChipModel.Shown>,
    onMediaControlPopupVisibilityChanged: (Boolean) -> Unit,
    onIslandBoundsChanged: (android.graphics.Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LunarisBarTheme {
        LunarisBarContainer(
            chips,
            onMediaControlPopupVisibilityChanged,
            onIslandBoundsChanged,
            modifier,
        )
    }
}

@Composable
private fun LunarisBarContainer(
    chips: List<PopupChipModel.Shown>,
    onMediaControlPopupVisibilityChanged: (Boolean) -> Unit,
    onIslandBoundsChanged: (android.graphics.Rect) -> Unit,
    modifier: Modifier,
) {
    val state = LocalLunarisBarState.current
    val settings = state.settings
    val context = LocalContext.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    var containerWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val alignmentOffset =
        when (settings.alignment) {
            1 -> -(screenWidth - containerWidth) / 2 + 12.dp
            2 -> (screenWidth - containerWidth) / 2 - 12.dp
            else -> 0.dp
        }
    val currentOnIslandBoundsChanged by rememberUpdatedState(onIslandBoundsChanged)
    DisposableEffect(Unit) { onDispose { currentOnIslandBoundsChanged(android.graphics.Rect()) } }
    LaunchedEffect(chips.isEmpty()) {
        if (chips.isEmpty()) currentOnIslandBoundsChanged(android.graphics.Rect())
    }
    val cutoutSpec = rememberDynamicIslandCutoutSpec()
    val (_, heightScale) = rememberDynamicIslandSizeScale()
    var selectedChipId by remember { mutableStateOf<PopupChipId?>(null) }
    var popupAnchorChip by remember { mutableStateOf<PopupChipModel.Shown?>(null) }
    var popupVisible by remember { mutableStateOf(false) }
    var knownChipIds by remember { mutableStateOf<List<PopupChipId>>(emptyList()) }
    var mainChipBounds by remember { mutableStateOf<Rect?>(null) }
    var companionChipBounds by remember { mutableStateOf<Rect?>(null) }
    var popupOriginBounds by remember { mutableStateOf<Rect?>(null) }
    var lastDirection by remember { mutableIntStateOf(1) }

    LaunchedEffect(state.userId) {
        popupAnchorChip = null
        popupVisible = false
        popupOriginBounds = null
        selectedChipId = null
        knownChipIds = emptyList()
    }

    LaunchedEffect(chips) {
        val currentChipIds = chips.map { it.chipId }
        val newestChipId =
            if (knownChipIds.isEmpty()) {
                null
            } else {
                currentChipIds.lastOrNull { it !in knownChipIds }
            }
        selectedChipId =
            when {
                newestChipId != null -> newestChipId
                chips.any { it.chipId == selectedChipId } -> selectedChipId
                else -> chips.firstOrNull()?.chipId
            }
        knownChipIds = currentChipIds
    }

    val selectedIndex = chips.indexOfFirst { it.chipId == selectedChipId }.coerceAtLeast(0)
    val selectedChip = chips.getOrNull(selectedIndex)
    val shownChip = chips.firstOrNull { it.isPopupShown }

    LaunchedEffect(shownChip) {
        if (shownChip != null) {
            if (popupAnchorChip == null) {
                val fromPeek = shownChip.chipId != selectedChipId
                popupOriginBounds =
                    if (fromPeek && companionChipBounds != null) companionChipBounds
                    else mainChipBounds
            }
            popupAnchorChip = shownChip
            popupVisible = true
        } else if (popupAnchorChip != null) {
            popupVisible = false
        }
    }

    LaunchedEffect(chips) {
        onMediaControlPopupVisibilityChanged(
            chips.any { it.chipId == PopupChipId.MediaControl && it.isPopupShown }
        )
    }

    val switchScaleAnim = remember { Animatable(1f) }
    var switchInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(selectedChipId) {
        if (!switchInitialized) {
            switchInitialized = true
            return@LaunchedEffect
        }
        if (settings.isReducedMotion) {
            switchScaleAnim.snapTo(1f)
            return@LaunchedEffect
        }
        switchScaleAnim.animateTo(0.92f, tween(40, easing = FastOutSlowInEasing))
        switchScaleAnim.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 650f),
        )
    }

    fun selectRelative(direction: Int) {
        if (chips.size <= 1) return
        lastDirection = if (direction >= 0) 1 else -1
        val newIndex = (selectedIndex + direction).mod(chips.size)
        val newChip = chips[newIndex]
        selectedChipId = newChip.chipId
        if (popupVisible) {
            newChip.showPopup()
        }
    }

    fun selectCard(id: String) {
        chips
            .firstOrNull { it.chipId == PopupChipId.Utility(id) }
            ?.let {
                selectedChipId = it.chipId
                popupOriginBounds = mainChipBounds
                it.showPopup()
            }
    }

    val showCompanion = settings.isCompanionEnabled && chips.size >= COMPANION_MIN_TASKS
    val companionOnRight = lastDirection >= 0
    val companionChip =
        if (showCompanion) {
            chips.getOrNull((selectedIndex + lastDirection).mod(chips.size))
        } else {
            null
        }
    val companionDiameter = DynamicIslandCompanionDiameter * heightScale * settings.miniScale
    val companionBalance = companionDiameter + DynamicIslandCompanionGap
    val effectivePageCount = if (showCompanion) 1 else chips.size

    Box(
        modifier =
            modifier
                .padding(horizontal = 8.dp)
                .offset(
                    x =
                        (if (settings.alignment == 0) cutoutSpec.horizontalOffset
                        else alignmentOffset) + settings.offsetX.dp,
                    y = settings.offsetY.dp,
                )
                .onGloballyPositioned { coordinates ->
                    containerWidth = with(density) { coordinates.size.width.toDp() }
                    val b = coordinates.boundsInWindow()
                    onIslandBoundsChanged(
                        if (chips.isEmpty()) {
                            android.graphics.Rect()
                        } else {
                            android.graphics.Rect(
                                b.left.toInt(),
                                b.top.toInt(),
                                b.right.toInt(),
                                b.bottom.toInt(),
                            )
                        }
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        popupAnchorChip?.let { anchoredChip ->
            StatusBarPopup(
                viewModel = shownChip ?: anchoredChip,
                isVisible = popupVisible,
                chipBoundsInScreen = popupOriginBounds,
                canPage = chips.size > 1,
                pageDirection = lastDirection,
                onPage = { direction -> selectRelative(direction) },
                onHidden = {
                    if (shownChip == null) {
                        popupAnchorChip = null
                        popupOriginBounds = null
                    }
                },
            )
        }

        if (selectedChip == null) return@Box

        Row(
            modifier =
                Modifier.graphicsLayer {
                    scaleX = switchScaleAnim.value
                    scaleY = switchScaleAnim.value
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCompanion && companionChip != null) {
                if (companionOnRight) {
                    Spacer(modifier = Modifier.width(companionBalance))
                } else {
                    LunarisBarChip(
                        viewModel = companionChip,
                        popupAttached = popupAnchorChip?.chipId == companionChip.chipId,
                        pageCount = 1,
                        cutoutSpec = cutoutSpec,
                        companion = true,
                        onPage = ::selectRelative,
                        onCard = ::selectCard,
                        onTap = { companionChip.showPopup() },
                        onChipBoundsChanged = { companionChipBounds = it },
                    )
                    Spacer(modifier = Modifier.width(DynamicIslandCompanionGap))
                }
            }

            AnimatedContent(
                targetState = selectedChip.chipId,
                transitionSpec = {
                    if (targetState == initialState) {
                        fadeIn(
                            animationSpec =
                                tween(
                                    if (settings.isReducedMotion) 0
                                    else (150 / settings.animationSpeed).toInt()
                                )
                        ) togetherWith
                            fadeOut(
                                animationSpec =
                                    tween(
                                        if (settings.isReducedMotion) 0
                                        else (150 / settings.animationSpeed).toInt()
                                    )
                            )
                    } else {
                        val slideDirection =
                            if (
                                chips.indexOfFirst { it.chipId == targetState } >
                                    chips.indexOfFirst { it.chipId == initialState }
                            ) {
                                1
                            } else {
                                -1
                            }
                        (slideInHorizontally(
                            animationSpec =
                                tween(
                                    if (settings.isReducedMotion) 0
                                    else (220 / settings.animationSpeed).toInt()
                                ),
                            initialOffsetX = { fullWidth -> slideDirection * fullWidth / 2 },
                        ) +
                            fadeIn(
                                animationSpec =
                                    tween(
                                        if (settings.isReducedMotion) 0
                                        else (180 / settings.animationSpeed).toInt()
                                    )
                            )) togetherWith
                            (slideOutHorizontally(
                                animationSpec =
                                    tween(
                                        if (settings.isReducedMotion) 0
                                        else (200 / settings.animationSpeed).toInt()
                                    ),
                                targetOffsetX = { fullWidth -> -slideDirection * fullWidth / 3 },
                            ) +
                                fadeOut(
                                    animationSpec =
                                        tween(
                                            if (settings.isReducedMotion) 0
                                            else (140 / settings.animationSpeed).toInt()
                                        )
                                ))
                    }
                },
                label = "dynamic_island_chip",
            ) { chipId ->
                val chip = chips.firstOrNull { it.chipId == chipId } ?: return@AnimatedContent
                var dragAccumulator by remember(chipId, chips.size) { mutableFloatStateOf(0f) }
                var verticalDragAccumulator by
                    remember(chipId, chips.size) { mutableFloatStateOf(0f) }
                val dragOffset = remember(chipId, chips.size) { Animatable(0f) }
                val thresholdPx = with(LocalDensity.current) { 36.dp.toPx() }
                val maxFollowPx = with(LocalDensity.current) { 40.dp.toPx() }
                val dragScope = rememberCoroutineScope()

                LunarisBarChip(
                    viewModel = chip,
                    popupAttached = popupAnchorChip?.chipId == chip.chipId,
                    pageCount = effectivePageCount,
                    cutoutSpec = cutoutSpec,
                    onChipBoundsChanged = { bounds -> mainChipBounds = bounds },
                    onPage = ::selectRelative,
                    onCard = ::selectCard,
                    modifier =
                        Modifier.graphicsLayer { translationX = dragOffset.value }
                            .pointerInput(chips.size, chip.chipId, settings) {
                                detectDragGestures(
                                    onDragEnd = {
                                        val switched =
                                            when {
                                                abs(verticalDragAccumulator) >= thresholdPx &&
                                                    abs(verticalDragAccumulator) >
                                                        abs(dragAccumulator) -> {
                                                    lunarisBarAction(
                                                        settings.text(
                                                            if (verticalDragAccumulator < 0)
                                                                "gesture_swipe_up"
                                                            else "gesture_swipe_down",
                                                            if (verticalDragAccumulator < 0)
                                                                "expand"
                                                            else "notifications",
                                                        ),
                                                        context,
                                                        chip,
                                                        chip.showPopup,
                                                        ::selectRelative,
                                                        ::selectCard,
                                                    )
                                                    true
                                                }
                                                dragAccumulator <= -thresholdPx -> {
                                                    lunarisBarAction(
                                                        settings.text(
                                                            "gesture_swipe_left",
                                                            "next_card",
                                                        ),
                                                        context,
                                                        chip,
                                                        chip.showPopup,
                                                        ::selectRelative,
                                                        ::selectCard,
                                                    )
                                                    true
                                                }
                                                dragAccumulator >= thresholdPx -> {
                                                    lunarisBarAction(
                                                        settings.text(
                                                            "gesture_swipe_right",
                                                            "previous_card",
                                                        ),
                                                        context,
                                                        chip,
                                                        chip.showPopup,
                                                        ::selectRelative,
                                                        ::selectCard,
                                                    )
                                                    true
                                                }
                                                else -> false
                                            }
                                        dragAccumulator = 0f
                                        verticalDragAccumulator = 0f
                                        dragScope.launch {
                                            if (switched) {
                                                dragOffset.snapTo(0f)
                                            } else {
                                                dragOffset.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec =
                                                        spring(
                                                            dampingRatio =
                                                                Spring.DampingRatioMediumBouncy,
                                                            stiffness = Spring.StiffnessMedium,
                                                        ),
                                                )
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        dragAccumulator = 0f
                                        verticalDragAccumulator = 0f
                                        dragScope.launch {
                                            dragOffset.animateTo(
                                                targetValue = 0f,
                                                animationSpec =
                                                    spring(
                                                        dampingRatio =
                                                            Spring.DampingRatioMediumBouncy,
                                                        stiffness = Spring.StiffnessMedium,
                                                    ),
                                            )
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        dragAccumulator += dragAmount.x
                                        verticalDragAccumulator += dragAmount.y
                                        val target =
                                            (dragAccumulator * 0.5f).coerceIn(
                                                -maxFollowPx,
                                                maxFollowPx,
                                            )
                                        dragScope.launch { dragOffset.snapTo(target) }
                                        if (
                                            abs(dragAccumulator) > 8f ||
                                                abs(verticalDragAccumulator) > 8f
                                        ) {
                                            change.consume()
                                        }
                                    },
                                )
                            },
                    onTap = { if (chip.isPopupShown) chip.hidePopup() else chip.showPopup() },
                )
            }

            if (showCompanion && companionChip != null) {
                if (companionOnRight) {
                    Spacer(modifier = Modifier.width(DynamicIslandCompanionGap))
                    LunarisBarChip(
                        viewModel = companionChip,
                        popupAttached = popupAnchorChip?.chipId == companionChip.chipId,
                        pageCount = 1,
                        cutoutSpec = cutoutSpec,
                        companion = true,
                        onPage = ::selectRelative,
                        onCard = ::selectCard,
                        onTap = { companionChip.showPopup() },
                        onChipBoundsChanged = { companionChipBounds = it },
                    )
                } else {
                    Spacer(modifier = Modifier.width(companionBalance))
                }
            }
        }
    }
}
