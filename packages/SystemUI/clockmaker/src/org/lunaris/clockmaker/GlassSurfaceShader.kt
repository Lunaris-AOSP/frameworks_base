/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

internal object GlassSurfaceShader {
    const val SOURCE = """
        uniform shader clear;
        uniform shader blurred;
        uniform shader blurSides;
        uniform float blurAmount;
        uniform float4 bounds;
        uniform float radius;
        uniform float refraction;
        uniform float refractionWidth;
        uniform float chromatic;
        uniform float contrast;
        uniform float rimStrength;
        uniform float2 rimTrig;
        uniform float3 softRim;
        uniform float rimWidth;
        uniform float borderStrength;
        uniform float shadowStrength;
        layout(color) uniform half4 tint;
        uniform float tintOpacity;
        uniform float2 wallpaperSize;
        uniform float3 mapX;
        uniform float3 mapY;
        uniform float3 mapW;

        float distanceAt(float2 p) {
            float2 halfSize = (bounds.zw - bounds.xy) * 0.5;
            float2 q = abs(p - (bounds.xy + bounds.zw) * 0.5) - halfSize + radius;
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
        }

        half3 backgroundAt(float2 p) {
            if (blurAmount <= 0.0) return clear.eval(p).rgb;
            if (blurAmount >= 1.0) return blurred.eval(p).rgb;
            return mix(clear.eval(p).rgb, blurred.eval(p).rgb, blurAmount);
        }

        half3 sidesAt(float2 p) {
            if (blurAmount <= 0.0) return clear.eval(p).rgb;
            if (blurAmount >= 1.0) return blurSides.eval(p).rgb;
            return mix(clear.eval(p).rgb, blurSides.eval(p).rgb, blurAmount);
        }

        half4 main(float2 p) {
            float d = distanceAt(p);
            float coverage = 1.0 - smoothstep(-0.5, 0.5, d);
            if (coverage <= 0.0 && (shadowStrength <= 0.0 || blurAmount <= 0.0)) return half4(0);
            float3 pos = float3(p, 1);
            float w = dot(mapW, pos);
            w = abs(w) < 0.0001 ? 0.0001 : w;
            float2 bg = float2(dot(mapX, pos), dot(mapY, pos)) / w;
            if (blurAmount <= 0.0) {
                half3 color = mix(clear.eval(bg).rgb, tint.rgb, tintOpacity);
                color = clamp((color - 0.5) * contrast + 0.5, 0.0, 1.0);
                return half4(color * coverage, coverage);
            }
            float2 grad = float2(distanceAt(p + float2(0.5, 0)) - distanceAt(p - float2(0.5, 0)),
                distanceAt(p + float2(0, 0.5)) - distanceAt(p - float2(0, 0.5)));
            float2 normal = length(grad) > 0.0001 ? normalize(grad) : float2(0, 1);
            float dw = dot(mapW.xy, normal);
            float2 mapped = float2(dot(mapX.xy, normal) * w - dot(mapX, pos) * dw,
                dot(mapY.xy, normal) * w - dot(mapY, pos) * dw) / (w * w);
            float pixelScale = max(length(mapped), 0.0001);
            float2 n = mapped / pixelScale;
            float shadow = 0.0;
            if (shadowStrength > 0.0 && coverage < 1.0) {
                float shadowDist = distanceAt(p - float2(0, 3.0 / pixelScale));
                shadow = (1.0 / (1.0 + exp(clamp(shadowDist * pixelScale * 0.4, -40.0, 40.0))))
                    * 0.5 * shadowStrength * blurAmount * (1.0 - coverage);
            }
            float alpha = coverage + shadow * (1.0 - coverage);
            if (alpha <= 0.0) return half4(0);
            float dist = d * pixelScale * 2.0 / wallpaperSize.y;
            float2 halfSize = (bounds.zw - bounds.xy) * pixelScale / wallpaperSize.y;
            float sizeFactor = mix(0.1, 1.0, smoothstep(0.0, 0.20, min(halfSize.x, halfSize.y) * 2.0));
            float edgeWidth = max(refractionWidth * sizeFactor, 0.0001);
            float edge = exp(-abs(dist) / edgeWidth * 3.0);
            float bulgeT = clamp(1.0 - dist / max(length(halfSize), 0.0001), 0.0, 1.0);
            float bulge = sin(bulgeT * bulgeT * 2.57);
            float2 offset = -n * bulge * refraction * blurAmount * sizeFactor * edge * wallpaperSize.y;
            half3 color = backgroundAt(bg + offset);
            if (chromatic * blurAmount > 0.0001) {
                float2 split = n * chromatic * blurAmount * edge * 0.5 * wallpaperSize.y;
                color.r = sidesAt(bg + offset + split).r;
                color.b = sidesAt(bg + offset - split).b;
            }
            color = mix(color, tint.rgb, tintOpacity);
            color = clamp((color - 0.5) * contrast + 0.5, 0.0, 1.0);
            float2 rotated = float2(n.x * rimTrig.x + n.y * rimTrig.y,
                n.x * rimTrig.y - n.y * rimTrig.x);
            float light = sqrt(max(0.0, -rotated.x * rotated.y));
            float dark = sqrt(max(0.0, rotated.x * rotated.y));
            float rim = 0.002 * max(rimWidth, 0.25);
            float inner = smoothstep(-0.5, -0.003, dist);
            float mask = inner * (1.0 - smoothstep(0.0, rim, abs(dist)));
            float sharp = mask * smoothstep(0.0, 2.5, light);
            float glowFall = 1.0 - smoothstep(0.0, rim * 45.0 * softRim.y, abs(dist));
            float glow = inner * glowFall * glowFall * glowFall * smoothstep(0.3, 1.6, light) * 0.125 * softRim.x;
            half3 surround = color / max(max(color.r, max(color.g, color.b)), 0.05);
            half3 glowColor = mix(half3(1), clamp(surround, 0.0, 1.0), softRim.z);
            color = mix(color, half3(0), mask * smoothstep(0.15, 0.7, dark) * borderStrength * blurAmount);
            color += (half3(sharp) + glowColor * glow * (1.0 - sharp)) * rimStrength * blurAmount;
            return half4(clamp(color, 0.0, 1.0) * coverage, alpha);
        }
    """
}
