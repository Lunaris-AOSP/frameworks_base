/*
 * Copyright (C) 2026 The PenguinOS Project
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.cosMediaRipple(
    trigger: Any?,
    cornerRadius: Dp,
    enabled: Boolean,
    origin: DpOffset = DpOffset(40.dp, 40.dp),
    animateOnEnable: Boolean = true,
): Modifier {
    val progress = remember { Animatable(1f) }
    var lastTrigger by remember { mutableStateOf(trigger) }

    LaunchedEffect(trigger, enabled) {
        val triggerChanged = trigger != lastTrigger
        lastTrigger = trigger
        if (!enabled || trigger == null || (!triggerChanged && !animateOnEnable)) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(MediaRipple.DURATION_MS.toInt(), easing = LinearEasing))
    }

    if (!enabled || trigger == null) return this
    val ripple =
        remember {
            runCatching { MediaRipple() }
                .onFailure { Log.w("CosMediaRipple", "Could not create the media ripple", it) }
                .getOrNull()
        } ?: return this
    return drawWithContent {
        drawContent()
        val p = progress.value
        if (p < 1f && size.width > 0f && size.height > 0f) {
            drawIntoCanvas {
                val canvas = it.nativeCanvas
                if (canvas.isHardwareAccelerated) {
                    val start = minOf(origin.x.toPx(), size.width / 2f)
                    ripple.draw(
                        canvas,
                        size.width,
                        size.height,
                        minOf(cornerRadius.toPx(), size.minDimension / 2f),
                        if (layoutDirection == LayoutDirection.Rtl) size.width - start else start,
                        minOf(origin.y.toPx(), size.height / 2f),
                        p,
                    )
                }
            }
        }
    }
}

private class MediaRipple {
    private val shader = RuntimeShader(SOURCE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = this@MediaRipple.shader }

    init {
        shader.setFloatUniform("uRippleWave", RIPPLE_SPEED, RIPPLE_WAVELENGTH)
        shader.setFloatUniform("uRippleAlpha", RIPPLE_ALPHA)
        shader.setFloatUniform("uRippleWidth", RIPPLE_WIDTH_NEAR, RIPPLE_WIDTH_FAR)
    }

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        cornerRadius: Float,
        centerX: Float,
        centerY: Float,
        progress: Float,
    ) {
        shader.setFloatUniform("uSize", width, height)
        shader.setFloatUniform("uRadius", cornerRadius)
        shader.setFloatUniform("uRippleCenter", centerX, centerY)
        shader.setFloatUniform("uTime", RIPPLE_TIME_OFFSET + progress * DURATION_MS / 1000f)
        shader.setFloatUniform(
            "uRippleMask",
            RIPPLE_MASK_RADIUS,
            0f,
            RIPPLE_MASK_IN_FROM + (RIPPLE_MASK_IN_TO - RIPPLE_MASK_IN_FROM) * progress,
            RIPPLE_MASK_OUT,
        )
        canvas.drawRect(0f, 0f, width, height, paint)
    }

    companion object {
        private const val RIPPLE_SPEED = 0.6f
        private const val RIPPLE_WAVELENGTH = 1000f
        private const val RIPPLE_ALPHA = 0.8f
        private const val RIPPLE_WIDTH_NEAR = 0.1f
        private const val RIPPLE_WIDTH_FAR = -0.3f
        private const val RIPPLE_MASK_RADIUS = 1200f
        private const val RIPPLE_MASK_IN_FROM = 2000f
        private const val RIPPLE_MASK_IN_TO = 200f
        private const val RIPPLE_MASK_OUT = 50f
        private const val RIPPLE_TIME_OFFSET = RIPPLE_WAVELENGTH * 3f / (RIPPLE_SPEED * 4000f)

        const val DURATION_MS = (RIPPLE_WAVELENGTH / RIPPLE_SPEED).toLong()

        private val SOURCE =
            """
            uniform float2 uSize;
            uniform float uRadius;
            uniform float2 uRippleCenter;
            uniform float2 uRippleWave;
            uniform float uRippleAlpha;
            uniform float2 uRippleWidth;
            uniform float4 uRippleMask;
            uniform float uTime;

            ${LightShaders.ROUNDED_BOX}

            half4 main(float2 xy) {
                float2 extent = uSize * 0.5;
                float d = roundedBox(xy - extent, extent, uRadius);
                float shape = smoothstep(1.0, -1.0, d);
                if (shape < 0.001) {
                    return half4(0.0);
                }
                float dist = length(xy - uRippleCenter);
                float4 mask = uRippleMask * (uSize.x * 0.000926);
                float ring = smoothstep(mask.x - mask.z, mask.x, dist)
                    - smoothstep(mask.x + mask.y, mask.x + mask.y + mask.w, dist);
                if (ring < 0.001) {
                    return half4(0.0);
                }
                float width = mix(uRippleWidth.x, uRippleWidth.y, clamp(dist / uRippleMask.x, 0.0, 1.0));
                float travelled = dist - uTime * uRippleWave.x * 1000.0;
                float wave = clamp(sin(travelled * 6.2831285 / uRippleWave.y) + width, 0.0, 1.0);
                wave = wave * wave * uRippleAlpha * ring;
                if (wave < 0.001) {
                    return half4(0.0);
                }
                return half4(wave, wave, wave, wave) * shape;
            }
            """
                .trimIndent()
    }
}
