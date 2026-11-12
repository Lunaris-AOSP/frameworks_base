/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

internal object ClockGlassShader {
    const val SOURCE = """
        uniform shader blurred;
        uniform shader blurSides;
        uniform shader glyph;
        uniform shader shadowGlyph;
        uniform float refraction;
        uniform float refractionWidth;
        uniform float chromatic;
        uniform float contrast;
        uniform float rimStrength;
        uniform float2 rimTrig;
        uniform float3 softRim;
        uniform float borderStrength;
        uniform float rimWidth;
        uniform float dateLens;
        uniform float dateFade;
        uniform float shadowStrength;
        uniform float2 shadowOffset;
        uniform float2 shadowRadius;
        layout(color) uniform half4 tint;
        uniform float tintOpacity;
        uniform float2 wallpaperSize;
        uniform float2 glyphSize;
        uniform float2 glyphOrigin;
        uniform float3 mapX;
        uniform float3 mapY;
        uniform float3 mapW;
        uniform float outline;

        float alphaAt(float2 p) { return glyph.eval(p).a; }
        float shadowAt(float2 p) { return shadowGlyph.eval(p).a; }

        half4 main(float2 p) {
            float spread = 1.5;
            float a = alphaAt(p);
            float ar = alphaAt(p + float2(spread, 0));
            float al = alphaAt(p - float2(spread, 0));
            float au = alphaAt(p + float2(0, spread));
            float ad = alphaAt(p - float2(0, spread));
            float2 grad = float2(ar - al, au - ad);
            float magnitude = length(grad);
            float2 normal = magnitude > 0.001 ? grad / magnitude : float2(0, 1);
            float rs = max(1.0, refractionWidth * mix(35.0, 70.0, dateLens));
            float softAlpha = (a + alphaAt(p + float2(rs, 0)) + alphaAt(p - float2(rs, 0))
                + alphaAt(p + float2(0, rs)) + ad) / 5.0;
            float bulge = 1.0 - clamp(softAlpha, 0.0, 1.0);
            float3 pos = float3(p, 1);
            float w = dot(mapW, pos);
            w = abs(w) < 0.0001 ? 0.0001 : w;
            float2 bg = float2(dot(mapX, pos), dot(mapY, pos)) / w;
            float dw = dot(mapW.xy, normal);
            float2 n = float2(dot(mapX.xy, normal) * w - dot(mapX, pos) * dw,
                dot(mapY.xy, normal) * w - dot(mapY, pos) * dw) / (w * w);
            n = length(n) > 0.0001 ? normalize(n) : float2(0, 1);
            float fade = mix(1.0, dateFade, dateLens);
            float2 offset = n * bulge * refraction * fade * 0.7 * wallpaperSize;
            float2 split = n * chromatic * bulge * 2.0 * wallpaperSize.y;
            half3 color = blurred.eval(bg + offset).rgb;
            if (dateLens < 0.5 && chromatic > 0.0001) {
                color.r = blurSides.eval(bg + offset + split).r;
                color.b = blurSides.eval(bg + offset - split).b;
            }
            color = mix(color, tint.rgb, tintOpacity);
            color = clamp((color - 0.5) * contrast + 0.5, 0.0, 1.0);
            float2 rotated = float2(normal.x * rimTrig.x + normal.y * rimTrig.y,
                normal.x * rimTrig.y - normal.y * rimTrig.x);
            float light = sqrt(max(0.0, -rotated.x * rotated.y));
            float dark = sqrt(max(0.0, rotated.x * rotated.y));
            float rimMask;
            if (dateLens > 0.5) {
                float t = clamp(rimWidth * 0.5 + 0.5, 0.0, 1.0);
                rimMask = smoothstep(mix(0.01, 0.1, t), mix(0.02, 0.25, t), a)
                    * (1.0 - smoothstep(mix(0.3, 0.5, t), mix(0.4, 0.95, t), a));
            } else {
                float rimAlpha = pow(clamp(a, 0.0, 1.0), 1.0 / max(rimWidth, 0.25));
                rimMask = smoothstep(0.02, 0.08, rimAlpha) * (1.0 - smoothstep(0.3, 0.4, rimAlpha));
            }
            float sharp = rimMask * smoothstep(0.0, mix(2.5, 2.0, dateLens), light)
                * mix(1.05, 1.0, dateLens);
            float glowSpread = max(spread, softRim.y * 6.0);
            float glowAlpha = (alphaAt(p + float2(glowSpread, 0)) + alphaAt(p - float2(glowSpread, 0))
                + alphaAt(p + float2(0, glowSpread)) + alphaAt(p - float2(0, glowSpread))) * 0.25;
            float glowFall = clamp(a - glowAlpha, 0.0, 1.0);
            float glow = glowFall * glowFall * glowFall * smoothstep(0.3, 1.6, light) * 0.125 * softRim.x;
            half3 surround = color / max(max(color.r, max(color.g, color.b)), 0.05);
            half3 glowColor = mix(half3(1), clamp(surround, 0.0, 1.0), softRim.z);
            float border = dateLens > 0.5 ? 0.0 : rimMask * smoothstep(0.0, 0.7, dark) * borderStrength * 0.7;
            color = mix(color, half3(0), border);
            color += (half3(sharp) + glowColor * glow * (1.0 - sharp)) * rimStrength * fade;
            if (outline > 0.5) return half4(clamp(color, 0.0, 1.0), 1);
            float2 edge = clamp(min(p - glyphOrigin, glyphSize - p + glyphOrigin) * 0.5, 0.0, 1.0);
            float coverage = smoothstep(0.05, 0.25, a) * edge.x * edge.y;
            float2 c = p + shadowOffset;
            float2 ir = shadowRadius * 0.5;
            float2 id = ir * 0.7071;
            float shadow = shadowAt(c) * 0.28;
            shadow += (shadowAt(c + float2(ir.x, 0)) + shadowAt(c - float2(ir.x, 0))
                + shadowAt(c + float2(0, ir.y)) + shadowAt(c - float2(0, ir.y))
                + shadowAt(c + id) + shadowAt(c - id)
                + shadowAt(c + float2(id.x, -id.y)) + shadowAt(c + float2(-id.x, id.y))) * 0.09;
            shadow = clamp(shadow, 0.0, 1.0) * (1.0 - a) * 0.5 * shadowStrength;
            float alpha = coverage + shadow * (1.0 - coverage);
            return half4(clamp(color, 0.0, 1.0) * coverage, alpha);
        }
    """
}
