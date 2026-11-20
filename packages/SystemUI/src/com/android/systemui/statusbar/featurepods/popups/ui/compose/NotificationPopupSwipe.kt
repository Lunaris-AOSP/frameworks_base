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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitDragOrCancellation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class NotificationPopupSwipe(
    private val scope: CoroutineScope,
    private val reducedMotion: Boolean,
    private val speed: Float,
    private val sideTravel: Float,
    private val upTravel: Float,
    val dismiss: () -> Unit,
) {
    var offset by mutableStateOf(Offset.Zero)
    private var returnJob: Job? = null

    private val side: Float get() = abs(offset.x) / sideTravel
    private val up: Float get() = -offset.y / upTravel

    val translation: Offset
        get() = if (reducedMotion) Offset.Zero else Offset(offset.x / (1f + side), offset.y / (1f + up))

    val collapseFraction: Float
        get() = if (reducedMotion) 0f else 0.24f * side / (1f + side) + 0.70f * up / (1f + up)

    fun startDrag(): Offset {
        returnJob?.cancel()
        return offset
    }

    fun reset() {
        returnJob?.cancel()
        if (reducedMotion) {
            offset = Offset.Zero
            return
        }
        returnJob = scope.launch {
            Animatable(offset, Offset.VectorConverter).animateTo(
                Offset.Zero,
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium * speed * speed,
                ),
            ) { offset = value }
        }
    }
}

@Composable
internal fun rememberNotificationPopupSwipe(
    chipId: PopupChipId?,
    visible: Boolean,
    onDismiss: () -> Unit,
): NotificationPopupSwipe? {
    if (chipId == null) return null
    val settings = LocalLunarisBarState.current.settings
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDismiss)
    val swipe = remember(chipId, density, settings.isReducedMotion, settings.animationSpeed) {
        NotificationPopupSwipe(
            scope,
            settings.isReducedMotion,
            settings.animationSpeed,
            with(density) { 100.dp.toPx() },
            with(density) { 80.dp.toPx() },
            dismiss = { currentDismiss() },
        )
    }
    LaunchedEffect(swipe, visible) {
        if (visible && swipe.offset != Offset.Zero) swipe.reset()
    }
    return swipe
}

/** Track the swipe in popup coordinates so moving or clipping the card does not move the pointer. */
@Composable
internal fun Modifier.notificationPopupSwipe(
    swipe: NotificationPopupSwipe?,
    visible: Boolean,
    frame: Rect,
): Modifier {
    if (swipe == null || !visible) return this
    val currentFrame by rememberUpdatedState(frame)
    return pointerInput(swipe) {
        val sideThreshold = 48.dp.toPx()
        val upThreshold = 32.dp.toPx()
        val flickDistance = 12.dp.toPx()
        val flickSpeed = 600.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!currentFrame.contains(down.position)) return@awaitEachGesture
            var horizontal = false
            var displacement = Offset.Zero
            var startOffset = Offset.Zero
            fun updateSwipe() {
                val offset = startOffset + displacement
                swipe.offset =
                    if (horizontal) Offset(offset.x, 0f)
                    else Offset(0f, offset.y.coerceAtMost(0f))
            }
            val velocityTracker = VelocityTracker().apply {
                addPointerInputChange(down)
            }
            val start =
                awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                    horizontal = abs(overSlop.x) >= abs(overSlop.y)
                    if (horizontal || overSlop.y < 0f) {
                        startOffset = swipe.startDrag()
                        displacement = change.position - down.position
                        velocityTracker.addPointerInputChange(change)
                        updateSwipe()
                        change.consume()
                    }
                } ?: return@awaitEachGesture
            var pointer = start.id
            var lastMove = start.uptimeMillis
            var change = awaitDragOrCancellation(pointer)
            while (change != null && change.pressed) {
                if (change.id != pointer) velocityTracker.resetTracking()
                pointer = change.id
                displacement += change.positionChange()
                velocityTracker.addPointerInputChange(change)
                lastMove = change.uptimeMillis
                updateSwipe()
                change.consume()
                change = awaitDragOrCancellation(pointer)
            }
            val distance =
                if (horizontal) abs(displacement.x) else -displacement.y
            val threshold =
                if (horizontal) sideThreshold
                else minOf(upThreshold, maxOf(flickDistance, down.position.y * 0.6f))
            val flick =
                if (change != null && distance >= flickDistance &&
                    change.uptimeMillis - lastMove < 80L
                ) {
                    val velocity = velocityTracker.calculateVelocity()
                    if (horizontal) velocity.x * sign(displacement.x) >= flickSpeed
                    else -velocity.y >= flickSpeed
                } else false
            if (change != null && (distance >= threshold || flick)) {
                swipe.dismiss()
            } else {
                swipe.reset()
            }
        }
    }
}
