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
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos

internal class IslandMotionSpring(initialValue: Float) {
    private val animation = Animatable(initialValue)
    private var retainedVelocity = 0f
    val value: Float
        get() = animation.value

    suspend fun animateTo(targetValue: Float, animationSpec: SpringSpec<Float>) {
        animation.animateTo(targetValue, animationSpec, initialVelocity = retainedVelocity) {
            retainedVelocity = velocity
        }
        retainedVelocity = 0f
    }
}

/** Motion parameters recovered from IslandView.stepCards and IslandView.onTouchEvent. */
internal object DynamicIslandMotion {
    fun springFor(response: Float, damping: Float, threshold: Float = 0.001f): SpringSpec<Float> =
        spring(
            dampingRatio = damping,
            stiffness = ((2.0 * PI / response) * (2.0 * PI / response)).toFloat(),
            visibilityThreshold = threshold,
        )

    val OpenWidth = springFor(0.40f, 0.76f, 0.4f)
    val OpenHeight = springFor(0.48f, 0.78f, 0.4f)
    val OpenPosition = springFor(0.46f, 0.74f, 0.4f)
    val FastOpen = springFor(0.24f, 0.85f, 0.4f)
    val CloseWidth = springFor(0.30f, 0.92f, 0.4f)
    val CloseHeight = springFor(0.34f, 0.90f, 0.4f)
    val ClosePosition = springFor(0.40f, 0.90f, 0.4f)
    val PillShrink = springFor(0.34f, 0.86f, 0.4f)
    val PillFade = springFor(0.22f, 1f, 0.002f)
    val Press = springFor(0.20f, 0.90f, 0.0005f)
    val Release = springFor(0.35f, 0.60f, 0.0005f)

    const val ContentDelayMillis = 230
    const val ContentEnterMillis = 150
    const val FastContentEnterMillis = 120
    const val ContentExitMillis = 80

    val ButtonDown = Easing { value ->
        val remaining = 1f - value
        1f - remaining * remaining * remaining
    }
    val ContentIn = Easing { value -> 1f - (1f - value) * (1f - value) }
    val ButtonUp = Easing { value ->
        val remaining = 1f - value
        1f - cos(value * PI.toFloat() * 1.6f) * remaining * remaining
    }

    val EaseInOut = Easing { value ->
        if (value < 0.5f) {
            4f * value * value * value
        } else {
            val remaining = -2f * value + 2f
            1f - remaining * remaining * remaining / 2f
        }
    }

    fun contentReveal(expansion: Float): Float = ((expansion - 0.85f) / 0.15f).coerceIn(0f, 1f)
}

@Composable
internal fun rememberIslandPressScale(isPressed: Boolean): State<Float> =
    animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = if (isPressed) DynamicIslandMotion.Press else DynamicIslandMotion.Release,
        label = "island_press",
    )

internal val LocalIslandPopupRevealMillis = compositionLocalOf<Float?> { null }
internal val LocalIslandPopupContentOnly = compositionLocalOf { false }

@Composable
internal fun islandRestDiameterPx(): Float? {
    val view = LocalView.current
    val cutout = view.rootWindowInsets?.displayCutout ?: view.display?.cutout
    val top = cutout?.getBoundingRectTop()?.takeUnless { it.isEmpty } ?: return null
    return minOf(top.width(), top.height()) * 0.92f
}

@Composable
internal fun Modifier.islandPopupRow(index: Int): Modifier {
    val elapsed = LocalIslandPopupRevealMillis.current ?: return this
    val progress = ((elapsed - index * 30f) / 200f).coerceIn(0f, 1f)
    val shift = with(LocalDensity.current) { 8.dp.toPx() }
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * shift
    }
}
