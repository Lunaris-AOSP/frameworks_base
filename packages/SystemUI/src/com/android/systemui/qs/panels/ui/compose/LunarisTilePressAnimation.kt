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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

@Composable
internal fun Modifier.lunarisTilePressAnimation(
    interactionSource: InteractionSource,
    enabled: Boolean,
    secondaryInteractionSource: InteractionSource? = null,
): Modifier {
    val squish = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val maxEdgeMovement = with(LocalDensity.current) { 2.5.dp.toPx() }

    LaunchedEffect(interactionSource, secondaryInteractionSource, enabled) {
        squish.snapTo(Offset.Zero)
        if (!enabled) return@LaunchedEffect

        val presses = mutableSetOf<PressInteraction.Press>()
        var animation: Job? = null
        var release: CompletableDeferred<Unit>? = null
        val interactions =
            secondaryInteractionSource?.let {
                merge(interactionSource.interactions, it.interactions)
            } ?: interactionSource.interactions

        interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    if (presses.add(interaction) && presses.size == 1) {
                        animation?.cancel()
                        val released = CompletableDeferred<Unit>()
                        release = released
                        animation = launch {
                            squish.animateTo(
                                PressedSquish,
                                tween(150, easing = FastOutSlowInEasing),
                            )
                            released.await()
                            squish.animateTo(
                                Offset.Zero,
                                keyframes {
                                    durationMillis = 350
                                    squish.value at 0 using FastOutSlowInEasing
                                    ReboundSquish at 150
                                    Offset.Zero at 350
                                },
                            )
                        }
                    }
                }
                is PressInteraction.Release -> {
                    if (presses.remove(interaction.press) && presses.isEmpty()) {
                        release?.complete(Unit)
                    }
                }
                is PressInteraction.Cancel -> {
                    if (presses.remove(interaction.press) && presses.isEmpty()) {
                        animation?.cancel()
                        release = null
                        animation = launch {
                            squish.animateTo(
                                Offset.Zero,
                                tween(100, easing = FastOutSlowInEasing),
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    return graphicsLayer {
        val displacement = if (enabled) squish.value else Offset.Zero
        val strengthX =
            (2f * maxEdgeMovement / (size.width.coerceAtLeast(1f) * PressedSquish.x))
                .coerceAtMost(1f)
        val strengthY =
            (2f * maxEdgeMovement / (size.height.coerceAtLeast(1f) * -PressedSquish.y))
                .coerceAtMost(1f)
        scaleX = 1f + displacement.x * strengthX
        scaleY = 1f + displacement.y * strengthY
    }
}

private val PressedSquish = Offset(0.07f, -0.06f)
private val ReboundSquish = Offset(-0.03f, 0.04f)
