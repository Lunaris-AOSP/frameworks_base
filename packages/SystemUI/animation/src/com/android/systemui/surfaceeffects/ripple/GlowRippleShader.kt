/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.surfaceeffects.ripple

import android.graphics.RuntimeShader
import com.android.systemui.surfaceeffects.shaderutil.GlowShaderLibrary
import kotlin.math.hypot

class GlowRippleShader : RuntimeShader(SOURCE) {
    init {
        setFloatUniform("uOrigin", 0f, 0f)
        setFloatUniform("uMaxRadius", 0f)
        setFloatUniform("uDensity", 1f)
        setFloatUniform("uProgress", 0f)
        setFloatUniform("uRainbow", 0f)
    }

    fun setGeometry(width: Int, height: Int, x: Float, y: Float, density: Float) {
        val scale = density.coerceAtLeast(0.001f)
        val farX = maxOf(x, width - x)
        val farY = maxOf(y, height - y)
        setFloatUniform("uOrigin", x, y)
        setFloatUniform("uMaxRadius", hypot(farX, farY) / scale + 144f)
        setFloatUniform("uDensity", scale)
    }

    fun setProgress(progress: Float) {
        setFloatUniform("uProgress", progress.coerceIn(0f, 1f))
    }

    fun setRainbow(rainbow: Boolean) {
        setFloatUniform("uRainbow", if (rainbow) 1f else 0f)
    }

    companion object {
        const val EFFECT_GLOW = 3
        const val EFFECT_RAINBOW_GLOW = 4
        const val DURATION_MS = 1200L

        private const val SOURCE =
            """
            uniform float2 uOrigin;
            uniform float uMaxRadius;
            uniform float uDensity;
            uniform float uProgress;
            uniform float uRainbow;

            ${GlowShaderLibrary.SHADER_LIB}

            float glowHash(float2 p) {
                p = fract(p * float2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }

            half4 main(float2 xy) {
                float p = uProgress;
                float level = smoothstep(0.0, 0.10, p) * (1.0 - smoothstep(0.55, 1.0, p));
                if (level <= 0.001) return half4(0.0);

                float2 pos = (xy - uOrigin) / uDensity;
                float angle = atan(pos.y, pos.x);
                float travel = 1.0 - pow(1.0 - p, 3.0);
                float radius = uMaxRadius * travel;
                float wobble = sin(angle * 7.0 + p * 9.0) * 1.2
                    + sin(angle * 11.0 - p * 6.0) * 0.6;
                float d = length(pos) - radius + wobble;
                if (d < -144.0 || d > 84.0) return half4(0.0);

                float crestDistance = d / 14.0;
                float crest = exp(-0.5 * crestDistance * crestDistance);
                float bloom = curvedGlowBand(1.0 - smoothstep(0.0, 80.0, abs(d)), 2.2, 0.5);
                float nearEcho = (d + 38.0) / 17.0;
                float farEcho = (d + 76.0) / 22.0;
                float echo = exp(-0.5 * nearEcho * nearEcho) * 0.18;
                echo += exp(-0.5 * farEcho * farEcho) * 0.09;
                float envelope = smoothstep(-144.0, -120.0, d)
                    * (1.0 - smoothstep(64.0, 84.0, d));
                float light = (crest * 0.58 + bloom * 0.42 + echo) * envelope;
                float3 color = float3(1.0);
                float sparkle = 0.0;

                if (uRainbow > 0.5) {
                    float hue = angle / 6.2831853 + p * 0.22 + d * 0.003;
                    color = 0.5 + 0.5 * cos(6.2831853 *
                        (hue + float3(0.0, 0.6666667, 0.3333333)));
                    color = mix(color, float3(1.0), crest * 0.38);

                    float2 grid = pos / 5.5;
                    float2 cell = floor(grid);
                    float seed = glowHash(cell);
                    float2 starPos = float2(glowHash(cell + 17.0), glowHash(cell + 43.0));
                    float2 star = (fract(grid) - mix(float2(0.2), float2(0.8), starPos)) * 5.5;
                    float dotLight = 1.0 - smoothstep(0.18, 1.05, length(star));
                    float crossLight = (1.0 - smoothstep(0.05, 0.20, min(abs(star.x), abs(star.y))))
                        * (1.0 - smoothstep(0.4, 1.35, max(abs(star.x), abs(star.y))));
                    float wake = smoothstep(-126.0, -88.0, d) * (1.0 - smoothstep(0.0, 36.0, d));
                    float twinkle = pow(0.5 + 0.5 * sin(p * 45.0 + seed * 80.0), 3.0);
                    sparkle = max(dotLight, crossLight * 0.75) * step(0.82, seed)
                        * wake * (0.65 + 0.35 * twinkle) * 1.3;
                }

                float alpha = clamp(light + sparkle, 0.0, 1.0) * level;
                float3 rgb = mix(color, float3(1.0), clamp(sparkle * 2.0, 0.0, 1.0));
                return half4(rgb * alpha, alpha);
            }
            """
    }
}
