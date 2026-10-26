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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp

@Composable
fun Modifier.cosStatusGlow(trigger: Any?, cornerRadius: Dp, enabled: Boolean): Modifier {
    if (!enabled || trigger == null) return this
    val glow =
        remember {
            runCatching { EdgeSweep() }
                .onFailure { Log.w("CosStatusGlow", "Could not create the edge sweep", it) }
                .getOrNull()
        } ?: return this
    val progress = remember { Animatable(1f) }

    LaunchedEffect(trigger) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(EdgeSweep.DURATION_MS.toInt(), easing = LinearEasing))
    }

    return drawWithContent {
        drawContent()
        val p = progress.value
        if (p < 1f) {
            drawIntoCanvas {
                val canvas = it.nativeCanvas
                if (canvas.isHardwareAccelerated) {
                    glow.setProgress(p)
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
