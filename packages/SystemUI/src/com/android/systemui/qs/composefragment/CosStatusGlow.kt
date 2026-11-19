/*
 * Copyright (C) 2026 The PenguinOS Project
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp

@Composable
fun Modifier.cosStatusGlow(
    trigger: Any?,
    cornerRadius: Dp,
    enabled: Boolean,
    visible: Boolean = true,
    alpha: () -> Float = { 1f },
): Modifier {
    if (!enabled || trigger == null) return this
    val glow =
        remember {
            runCatching { EdgeSweep() }
                .onFailure { Log.w("CosStatusGlow", "Could not create the edge sweep", it) }
                .getOrNull()
        } ?: return this
    val progress = remember { Animatable(1f) }
    val restartAlpha = remember { Animatable(1f) }
    var previousTrigger by remember { mutableStateOf(trigger) }

    LaunchedEffect(trigger, visible) {
        if (!visible) return@LaunchedEffect
        val newTrigger = previousTrigger != trigger
        if (newTrigger && progress.value < 1f && alpha() > 0f) {
            restartAlpha.animateTo(0f, tween(100))
        }
        if (newTrigger || progress.value >= 1f || alpha() <= 0f) {
            progress.snapTo(0f)
            restartAlpha.snapTo(1f)
        }
        previousTrigger = trigger
        progress.animateTo(
            1f,
            tween(((1f - progress.value) * EdgeSweep.DURATION_MS).toInt(), easing = LinearEasing),
        )
    }

    return drawWithContent {
        drawContent()
        val p = progress.value
        val opacity = (alpha() * restartAlpha.value).coerceIn(0f, 1f)
        if (p < 1f && opacity > 0f) {
            drawIntoCanvas {
                val canvas = it.nativeCanvas
                if (canvas.isHardwareAccelerated) {
                    glow.setProgress(p, opacity)
                    glow.draw(
                        canvas,
                        size.width.toInt(),
                        size.height.toInt(),
                        minOf(cornerRadius.toPx(), size.minDimension / 2),
                        capsule = false,
                    )
                }
            }
        }
    }
}
