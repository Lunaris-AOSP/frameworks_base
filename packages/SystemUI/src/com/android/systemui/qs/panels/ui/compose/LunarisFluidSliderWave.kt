/*
 * Copyright (C) 2026 Lunaris AOSP
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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.tanh
import kotlinx.coroutines.channels.Channel

internal val LocalFluidSliderAnimation = staticCompositionLocalOf { false }

@Stable
internal class FluidSliderWaveState(initialFraction: Float) {
    var bend by mutableFloatStateOf(0f)
        private set

    var slosh by mutableFloatStateOf(0f)
        private set

    private var bendVelocity = 0f
    private var sloshVelocity = 0f
    private var lastFraction = initialFraction
    private var lastSampleNanos = 0L
    private var lastInputVelocity = 0f

    internal val wakeChannel = Channel<Unit>(Channel.CONFLATED)

    fun reset(fraction: Float) {
        bend = 0f
        slosh = 0f
        bendVelocity = 0f
        sloshVelocity = 0f
        lastFraction = fraction
        lastSampleNanos = 0L
        lastInputVelocity = 0f
    }

    fun onFractionChanged(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        val delta = clamped - lastFraction
        if (abs(delta) < 1e-4f) return

        val now = System.nanoTime()
        val dt =
            if (lastSampleNanos == 0L) 1f / 60f
            else ((now - lastSampleNanos) / 1_000_000_000f).coerceIn(1f / 120f, 0.05f)
        lastFraction = clamped
        lastSampleNanos = now

        val velocity = (delta / dt).coerceIn(-6f, 6f)
        val accel = (velocity - lastInputVelocity).coerceIn(-8f, 8f)
        lastInputVelocity = velocity

        val targetBend = tanh((velocity * 0.55f).toDouble()).toFloat()
        bend = (bend + (targetBend - bend) * 0.35f).coerceIn(-1.2f, 1.2f)
        bendVelocity = (bendVelocity + velocity * 2.2f).coerceIn(-16f, 16f)

        sloshVelocity =
            (sloshVelocity + accel * 0.65f + velocity * 0.55f).coerceIn(-10f, 10f)

        wakeChannel.trySend(Unit)
    }

    internal fun step(dt: Float): Boolean {
        val steps = if (dt > 0.017f) 2 else 1
        val h = dt / steps
        repeat(steps) {
            val bendAcc = -324f * bend - 12.96f * bendVelocity
            bendVelocity += bendAcc * h
            bend = (bend + bendVelocity * h).coerceIn(-1.25f, 1.25f)

            val sloshAcc = -169f * slosh - 8.32f * sloshVelocity
            sloshVelocity += sloshAcc * h
            slosh = (slosh + sloshVelocity * h).coerceIn(-1f, 1f)

            lastInputVelocity *= (1f - 10f * h).coerceAtLeast(0f)
        }

        val settled =
            abs(bend) < 0.002f &&
                abs(bendVelocity) < 0.005f &&
                abs(slosh) < 0.002f &&
                abs(sloshVelocity) < 0.005f
        if (settled) {
            bend = 0f
            bendVelocity = 0f
            slosh = 0f
            sloshVelocity = 0f
            lastInputVelocity = 0f
        }
        return settled
    }
}

@Composable
internal fun rememberFluidSliderWaveState(
    fraction: Float,
    enabled: Boolean,
): FluidSliderWaveState {
    val state = remember { FluidSliderWaveState(fraction.coerceIn(0f, 1f)) }
    SideEffect {
        if (enabled) {
            state.onFractionChanged(fraction)
        } else {
            state.reset(fraction.coerceIn(0f, 1f))
        }
    }
    LaunchedEffect(state, enabled) {
        if (!enabled) {
            state.reset(fraction.coerceIn(0f, 1f))
            return@LaunchedEffect
        }
        while (true) {
            state.wakeChannel.receive()
            var lastFrameNanos = 0L
            while (true) {
                val settled = withFrameNanos { frameNanos ->
                    val dt =
                        if (lastFrameNanos == 0L) 1f / 60f
                        else ((frameNanos - lastFrameNanos) / 1_000_000_000f)
                            .coerceIn(1f / 240f, 0.033f)
                    lastFrameNanos = frameNanos
                    state.step(dt)
                }
                if (settled) break
            }
        }
    }
    return state
}

internal fun DrawScope.drawFluidSliderFill(
    path: Path,
    brush: Brush,
    alpha: Float,
    startX: Float,
    edgeX: Float,
    top: Float,
    bottom: Float,
    isRtl: Boolean,
    bend: Float,
    slosh: Float,
) {
    val h = bottom - top
    if (h <= 0f) return
    val dir = if (isRtl) -1f else 1f
    val fillLength = (edgeX - startX) * dir
    if (fillLength <= 0.5f) return

    val maxConcave = minOf(h * 0.44f, fillLength * 0.85f)
    val maxConvex = h * 0.44f
    val depth = (bend * h * 0.38f).coerceIn(-maxConcave, maxConvex)
    val tilt = (slosh * h * 0.16f).coerceIn(-h * 0.18f, h * 0.18f)

    val topEdgeX = edgeX + dir * (-depth * 0.55f - tilt)
    val bottomEdgeX = edgeX + dir * (-depth * 0.55f + tilt)
    val ctrlX = edgeX + dir * (depth * 1.10f)
    val ctrlY = (top + bottom) * 0.5f + slosh * h * 0.14f

    path.rewind()
    path.moveTo(startX, top)
    path.lineTo(topEdgeX, top)
    path.quadraticTo(ctrlX, ctrlY, bottomEdgeX, bottom)
    path.lineTo(startX, bottom)
    path.close()

    drawPath(path = path, brush = brush, alpha = alpha)
}

@Composable
internal fun Modifier.lunarisFluidSliderIndicator(
    waveState: FluidSliderWaveState,
    brush: Brush,
    insetWidth: Dp = 0.dp,
    alpha: () -> Float = { 1f },
): Modifier {
    val path = remember { Path() }
    return drawBehind {
        val insetPx = insetWidth.toPx().coerceIn(0f, size.minDimension / 2f)
        val isRtl = layoutDirection == LayoutDirection.Rtl
        val startX = if (isRtl) size.width else 0f
        val edgeX =
            if (isRtl) insetPx.coerceAtMost(size.width)
            else (size.width - insetPx).coerceAtLeast(0f)
        drawFluidSliderFill(
            path = path,
            brush = brush,
            alpha = alpha(),
            startX = startX,
            edgeX = edgeX,
            top = insetPx,
            bottom = size.height - insetPx,
            isRtl = isRtl,
            bend = waveState.bend,
            slosh = waveState.slosh,
        )
    }
}
