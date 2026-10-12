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

package com.android.systemui.shade

import android.animation.ValueAnimator
import androidx.compose.animation.SplineBasedFloatDecayAnimationSpec
import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.generateDecayAnimationSpec
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.unit.Density
import com.android.app.animation.Interpolators
import com.android.compose.animation.scene.willDecayFasterThanAnimating
import com.android.compose.gesture.effect.OffsetOverscrollEffect
import com.android.compose.gesture.effect.ProgressConverter
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal object ShadeExpansionSpring {
    private val spatialSpec = MotionScheme.expressive().slowSpatialSpec<Float>() as SpringSpec<Float>
    private const val BOUNCE_STRENGTH = 0.8f
    private const val SPLIT_SHADE_BOUNCE_STRENGTH = 0.8f
    private const val MAX_ANIMATION_DURATION_NANOS = 750_000_000L
    private val dampingRatio = maxOf(spatialSpec.dampingRatio, 0.9f)
    private val adjustedSpatialSpec =
        SpringSpec<Float>(
            dampingRatio = dampingRatio,
            stiffness = spatialSpec.stiffness,
            visibilityThreshold = 1f,
        )
    private val spring =
        FloatSpringSpec(
            dampingRatio = dampingRatio,
            stiffness = spatialSpec.stiffness,
            visibilityThreshold = 1f,
        )

    fun interface FrameListener {
        fun onFrame(height: Float, overscroll: Float)
    }

    @JvmStatic
    fun offset(overscroll: Float, density: Float): Float {
        return offset(overscroll, density, false)
    }

    @JvmStatic
    fun offset(overscroll: Float, density: Float, splitShade: Boolean): Float {
        val maxDistance = OffsetOverscrollEffect.DefaultMaxDistance.value * density
        val splitShadeStrength = if (splitShade) SPLIT_SHADE_BOUNCE_STRENGTH else 1f
        return (ProgressConverter.Default.convert(overscroll / maxDistance) * maxDistance
                * BOUNCE_STRENGTH * splitShadeStrength)
    }

    @JvmStatic
    fun createAnimator(
        initialHeight: Float,
        targetHeight: Float,
        initialOverscroll: Float,
        velocity: Float,
        density: Float,
        listener: FrameListener,
    ): ValueAnimator {
        val initialValue = initialHeight + initialOverscroll
        val initialSpringVelocity =
            if (initialValue < targetHeight && velocity < 0f) 0f else velocity
        val decay = SplineBasedFloatDecayAnimationSpec(Density(density))
        val useDecay =
            initialValue < targetHeight && initialSpringVelocity > 0f &&
                decay.getTargetValue(initialValue, initialSpringVelocity) >= targetHeight &&
                willDecayFasterThanAnimating(
                    adjustedSpatialSpec,
                    decay.generateDecayAnimationSpec(),
                    initialValue,
                    targetHeight,
                    initialSpringVelocity,
                )
        val boundaryNanos =
            if (useDecay) {
                var low = 0L
                var high = decay.getDurationNanos(initialValue, initialSpringVelocity)
                while (low < high) {
                    val mid = low + (high - low) / 2
                    val value =
                        decay.getValueFromNanos(mid, initialValue, initialSpringVelocity)
                    if (value < targetHeight) {
                        low = mid + 1
                    } else {
                        high = mid
                    }
                }
                high
            } else 0L
        val springStart = if (useDecay) targetHeight else initialValue
        val springVelocity =
            if (useDecay) {
                decay.getVelocityFromNanos(boundaryNanos, initialValue, initialSpringVelocity)
            } else {
                initialSpringVelocity
            }
        val physicsDurationNanos =
            boundaryNanos + spring.getDurationNanos(springStart, targetHeight, springVelocity)
        val animationDurationNanos =
            minOf(physicsDurationNanos, MAX_ANIMATION_DURATION_NANOS)
        var reachedTarget = initialHeight >= targetHeight
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ceil(animationDurationNanos / 1_000_000.0).toLong()
            interpolator = Interpolators.LINEAR
            addUpdateListener {
                val fraction = it.animatedFraction
                val playTimeNanos = (fraction.toDouble() * physicsDurationNanos).toLong()
                val value =
                    if (fraction >= 1f) targetHeight
                    else if (useDecay && playTimeNanos < boundaryNanos) {
                        decay.getValueFromNanos(
                            playTimeNanos,
                            initialValue,
                            initialSpringVelocity,
                        )
                    } else {
                        spring.getValueFromNanos(
                            playTimeNanos - boundaryNanos,
                            springStart,
                            targetHeight,
                            springVelocity,
                        )
                    }
                reachedTarget =
                    reachedTarget || value >= targetHeight ||
                        (useDecay && playTimeNanos >= boundaryNanos)
                if (reachedTarget) {
                    listener.onFrame(targetHeight, value - targetHeight)
                } else {
                    listener.onFrame(value.coerceAtLeast(0f), 0f)
                }
            }
        }
    }
}
