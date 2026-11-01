/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.internal.util.lunaris;

import android.graphics.BlendMode;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.LruCache;

import com.android.internal.util.lunaris.EdgeAuroraConfig.Profile;

public final class EdgeAuroraRenderer {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = new float[3];
    private final LruCache<Integer, BlurMaskFilter> blurs = new LruCache<>(64);
    private Canvas canvas;
    private float length, depth, core, time;
    private int color, hot, hotter;

    public EdgeAuroraRenderer() {
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    public void draw(Canvas target, Profile p, RectF screen, float density, float seconds) {
        draw(target, p, screen, density, seconds, 255);
    }

    public void draw(Canvas target, Profile p, RectF screen, float density, float seconds,
            int opacity) {
        if (screen.isEmpty()) return;
        canvas = target;
        color = p.color;
        hot = blend(color, .42f);
        hotter = blend(color, .78f);
        core = p.thickness * density;
        float bloom = p.bloom * 1.35f * density;
        depth = bloom + Math.max(core, 3f * density);
        time = seconds + (p.id.hashCode() & 1023) / 97f;
        boolean vertical = p.edge == EdgeAuroraConfig.Edge.LEFT
                || p.edge == EdgeAuroraConfig.Edge.RIGHT;
        float span = vertical ? screen.height() : screen.width();
        length = Math.min(span, Math.max(52f * density, span * p.length) + bloom * 2f);
        float start = Math.max(0f, Math.min(span - length, span * p.position - bloom));
        int saved = canvas.save();
        switch (p.edge) {
            case TOP: canvas.translate(screen.left + start, screen.top); break;
            case BOTTOM:
                canvas.translate(screen.left + start, screen.bottom); canvas.scale(1f, -1f); break;
            case LEFT:
                canvas.translate(screen.left, screen.top + start);
                canvas.rotate(90f); canvas.scale(1f, -1f); break;
            case RIGHT:
                canvas.translate(screen.right, screen.top + start); canvas.rotate(90f); break;
        }
        int layer = canvas.saveLayerAlpha(0f, 0f, length, depth, opacity);
        switch (p.motion) {
            case BREATHE: breathe(); break;
            case AURORA: aurora(); break;
            case COMET: comet(); break;
            case PULSE: pulse(); break;
            case EMBER: ember(); break;
            case NEON: neon(); break;
            case WAVE: wave(); break;
        }
        mask.setShader(new LinearGradient(0, 0, length, 0,
                new int[]{0, Color.WHITE, Color.WHITE, 0},
                new float[]{0f, .14f, .86f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, length, depth, mask);
        canvas.restoreToCount(layer);
        canvas.restoreToCount(saved);
        paint.setShader(null);
        canvas = null;
    }

    private void bloom(float alpha, float scale, int tint) {
        float d = Math.max(1f, depth * scale);
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(0, 0, 0, d,
                new int[]{alpha(tint, alpha), alpha(tint, alpha * .62f),
                        alpha(tint, alpha * .26f), alpha(tint, alpha * .08f), alpha(tint, 0)},
                new float[]{0, .16f, .42f, .72f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, length, d, paint);
    }

    private void line(float alpha, int tint, float thickness, float from, float to) {
        line(alpha, tint, thickness, 0f, from, to, thickness * .9f);
    }

    private void line(float alpha, int tint, float thickness, float offset, float from, float to,
            float blur) {
        paint.setShader(null);
        paint.setColor(alpha(tint, alpha));
        int key = Math.round(blur * 2f);
        BlurMaskFilter filter = blurs.get(key);
        if (filter == null && key > 0) {
            filter = new BlurMaskFilter(key / 2f, BlurMaskFilter.Blur.NORMAL);
            blurs.put(key, filter);
        }
        paint.setMaskFilter(filter);
        canvas.drawRoundRect(from, offset, to, offset + thickness,
                thickness / 2f, thickness / 2f, paint);
        paint.setMaskFilter(null);
    }

    private void spot(float at, float radius, float opacity, int tint, float scale) {
        if (radius < .5f || opacity < .002f) return;
        int saved = canvas.save();
        canvas.scale(1f, scale, at, 0);
        paint.setAlpha(255);
        paint.setBlendMode(BlendMode.PLUS);
        paint.setShader(new RadialGradient(at, core * .5f, radius,
                new int[]{alpha(tint, opacity), alpha(tint, opacity * .48f),
                        alpha(tint, opacity * .1f), alpha(tint, 0)},
                new float[]{0f, .32f, .7f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(at - radius, 0, at + radius, radius + core, paint);
        paint.setBlendMode(BlendMode.SRC_OVER);
        canvas.restoreToCount(saved);
    }

    private void wash(float at, float radius, float opacity, int tint) {
        spot(at, radius, opacity, tint, Math.max(.12f, Math.min(.8f, depth * 1.05f / radius)));
    }

    private void breathe() {
        float o = osc(2.1f, 0);
        float swell = .5f + .5f * o * o * (3f - 2f * o);
        bloom(.2f + .32f * swell, 1, color);
        bloom(.36f * swell, .42f, color);
        line(.55f + .42f * swell, hot, core, 0, length);
        line(.3f + .45f * swell, hotter, core * .45f, core * .12f, 0, length, core * .3f);
    }

    private void aurora() {
        int plus = hue(34f), minus = hue(-38f);
        bloom(.26f, 1, color);
        wash((.5f + .4f * sin(time * .55f)) * length, .34f * length, .42f, plus);
        wash((.5f + .42f * sin(time * .41f + 2.1f)) * length, .3f * length, .4f, minus);
        wash((.5f + .36f * sin(time * .73f + 4.2f)) * length, .26f * length, .36f, hot);
        line(.62f, color, core, 0, 0, length, core * 1.3f);
        float start = -(time * .11f % 1f) * length * 1.6f;
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(start, 0, start + length * 1.6f, 0,
                new int[]{color, plus, hotter, minus, color}, null, Shader.TileMode.MIRROR));
        canvas.drawRoundRect(0, 0, length, core, core / 2f, core / 2f, paint);
    }

    private void comet() {
        float tail = Math.min(depth * 3.4f, length * .7f);
        float track = length + tail * 1.4f;
        float head = time * depth * 6.5f % track - tail * .2f;
        bloom(.2f, 1, color);
        line(.42f, color, core, 0, length);
        line(.35f, hot, core, 0, head - tail, head, core * 1.8f);
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(head - tail, 0, head, 0,
                new int[]{alpha(hot, 0), alpha(hot, .5f), alpha(hotter, .95f)},
                null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(head - tail, 0, head, core, core / 2f, core / 2f, paint);
        wash(head - tail * .35f, tail * .55f, .28f, color);
        spot(head, depth * .7f, .75f, hotter, .7f);
        spot(head, core * 1.6f, .95f, Color.WHITE, 1f);
    }

    private void pulse() {
        float phase = time / 1.9f % 1f;
        float beat = (float) Math.exp(-phase * 5f);
        bloom(.22f + .26f * beat, 1, color);
        line(.45f + .5f * beat, hot, core, 0, length);
        for (int i = 0; i < 2; i++) {
            float p = (phase + i * .5f) % 1f;
            float offset = p * length * .56f;
            float opacity = (float) Math.pow(1f - p, 1.7f) * .7f;
            wash(length / 2f + offset, depth * (1.4f + p * 1.2f), opacity, hot);
            wash(length / 2f - offset, depth * (1.4f + p * 1.2f), opacity, hot);
        }
        wash(length / 2f, depth * 2.4f, beat * .65f, hotter);
    }

    private void ember() {
        bloom(.22f + osc(1.3f, .4f) * .07f, 1, color);
        line(.42f, color, core, 0, length);
        int count = Math.max(4, Math.min(12, (int) (length / (depth * 1.6f))));
        for (int i = 0; i < count; i++) {
            float at = ((i + .5f) / count + .35f / count * sin(i * 1.7f + time * .7f)) * length;
            float flicker = osc(5.3f + i * .21f, i * .9f) * osc(3.1f + i * .37f, i * 2.1f);
            float strength = .12f + .88f * flicker * flicker;
            wash(at, depth * (1.1f + .6f * flicker), strength * .62f,
                    i % 2 == 0 ? hot : blend(hue(22), .2f));
            spot(at, core * 1.4f, strength * .9f, hotter, .9f);
        }
    }

    private void neon() {
        float dip = Math.max(0, Math.min(1,
                (sin(time * 19.3f) * sin(time * 7.1f) * sin(time * 2.3f) - .62f) / .38f));
        float hum = (.97f + .03f * sin(time * 48f)) * (1f - dip * .42f);
        bloom(hum * .3f, 1, color);
        bloom(hum * .48f, .36f, color);
        line(hum * .95f, hot, core, 0, 0, length, core * 1.5f);
        line(hum, hotter, core * .5f, core * .25f, 0, length, core * .12f);
    }

    private void wave() {
        float band = Math.max(length * .12f, Math.min(length * .5f, depth * 2.2f));
        float start = -(time * depth * 6.5f * .35f % (band * 2));
        bloom(.22f, 1, color);
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(start, 0, start + band, 0,
                alpha(hotter, .95f), alpha(color, .12f), Shader.TileMode.MIRROR));
        canvas.drawRoundRect(0, 0, length, core, core / 2, core / 2, paint);
        for (float at = start; at < length + band; at += band * 2) {
            if (at > -band) wash(at, band * 1.2f, .34f, hot);
        }
    }

    private float osc(float speed, float phase) { return .5f + .5f * sin(time * speed + phase); }
    private static float sin(float v) { return (float) Math.sin(v); }
    private int hue(float shift) {
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + shift + 360f) % 360f;
        return Color.HSVToColor(hsv);
    }
    private static int alpha(int c, float a) {
        return (c & 0xffffff) | (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24);
    }
    private static int blend(int c, float white) {
        return Color.rgb(Math.round(Color.red(c) * (1f - white) + 255f * white),
                Math.round(Color.green(c) * (1f - white) + 255f * white),
                Math.round(Color.blue(c) * (1f - white) + 255f * white));
    }
}
