/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.composefragment

import com.android.systemui.surfaceeffects.shaderutil.GlowShaderLibrary

internal object LightShaders {
    const val ROUNDED_BOX =
        """
        float roundedBox(float2 p, float2 extent, float r) {
            float2 q = abs(p) - extent + r;
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
        }
        """

    const val CAPSULE =
        """
        float softAbs(float x, float k) {
            return sqrt(x * x + k * k);
        }

        float softMax(float a, float b, float k) {
            float d = a - b;
            return 0.5 * (a + b + sqrt(d * d + k * k));
        }

        float capsule(float2 p, float2 extent) {
            bool tall = extent.x < extent.y;
            float2 e = tall ? extent.yx : extent;
            float2 q = tall ? p.yx : p;
            q = float2(softAbs(q.x, 1.0), softAbs(q.y, 1.0));
            float r = e.y;
            float straight = max(e.x - r, 0.0);
            float scale = r / 80.0;
            float s = q.x - straight;
            float hard = max(s, 0.0);
            float soft = softMax(s, 0.0, 26.6 * scale);
            float alongEnd = 1.0 - smoothstep(0.0, max(70.0 * scale, 1e-3), abs(s));
            float acrossEnd = smoothstep(r - max(60.0 * scale, 1e-3), r, q.y);
            float x = mix(hard, soft, clamp(alongEnd * acrossEnd, 0.0, 1.0));
            return length(float2(x, q.y)) - r;
        }
        """

    const val PERIMETER_LIGHT =
        """
        ${GlowShaderLibrary.SHADER_LIB}

        float2 perimeterLight(float2 pos, float2 extent, float d, float turn, float4 bands,
                float4 spread, float curve, float curveMix) {
            float perimeter = (extent.x + extent.y) * 2.0;
            float lightAt = mod(turn + extent.x / perimeter * 0.5 + 0.5, 1.0);
            float2 dir = normalize(pos);
            float2 dirAbs = abs(dir);
            float2 onBox =
                min(dirAbs.xy * extent.yx / max(float2(0.001), dirAbs.yx), extent) * sign(dir);
            float side = (lightAt <= 0.5 ? 1.0 : -1.0) * dot(float2(-extent.y, extent.x), onBox);
            float lightDistance = perimeter * min(lightAt, 1.0 - lightAt) * 2.0;
            float2 fromCorner = abs(onBox - extent);
            float here = fromCorner.x + fromCorner.y;
            float toNear = side >= 0.0 ? abs(lightDistance - here) : lightDistance + here;
            toNear = min(2.0 * perimeter - toNear, toNear);

            float near = smoothstep(spread.y * perimeter, spread.x * perimeter, toNear);
            float far = smoothstep(spread.w * perimeter, spread.z * perimeter, perimeter - toNear);
            float nearBand = smoothstep(-bands.y, -bands.x, d);
            float farBand = smoothstep(-bands.w, -bands.z, d);
            if (curveMix > 0.001) {
                nearBand = curvedGlowBand(nearBand, curve, curveMix);
                farBand = curvedGlowBand(farBand, curve, curveMix);
            }
            return float2(near * nearBand, far * farBand);
        }
        """

}
