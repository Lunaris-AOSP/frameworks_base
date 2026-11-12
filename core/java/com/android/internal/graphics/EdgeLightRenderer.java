/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.graphics;

import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;

/**
 * Edge lighting geometry shared by SystemUI and the Settings preview. Motion is driven by a
 * caller-owned clock, so a notification pulse needs neither audio capture nor an overlay service.
 * All dimensions are relative to the supplied viewport; stroke width is supplied in pixels.
 * @hide
 */
public final class EdgeLightRenderer {
    private static final float TAU = (float) (Math.PI * 2);
    private static final String[] STYLES = {
            "default", "rounded", "chase_around", "mirror_rain", "corner_out", "fire_dance",
            "side_ripples", "flat_ripples", "flat_bars", "side_bars", "flat_streaks",
            "side_streaks", "side_flutter", "flat_flutter", "flat_waves", "side_waves",
            "waves_around", "lights_around", "bars_around", "side_braids", "side_lights",
            "serial_lights", "serials_around", "snow_particles", "bubble_particles",
            "dot_particles", "strings_around",
    };
    private static final int[] RAINBOW = {
            0xFFFF0000, 0xFFFF7F00, 0xFFFFFF00, 0xFF00FF00,
            0xFF0000FF, 0xFF4B0082, 0xFF9400D3, 0xFFFF0000,
    };
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();
    private final Matrix mLightMatrix = new Matrix();
    private final Path mFrame = new Path();
    private final Path mClip = new Path();
    private final Path[] mStrokes = {new Path(), new Path(), new Path()};
    private final Path[] mFills = {new Path(), new Path(), new Path()};
    private final PathMeasure mPerimeter = new PathMeasure();
    private final PathMeasure mOutline = new PathMeasure();
    private final PathMeasure[] mSides = {new PathMeasure(), new PathMeasure()};
    private final PathMeasure[] mFlats = {new PathMeasure(), new PathMeasure()};
    private final PathMeasure[] mSideTracks = measures(4);
    private final PathMeasure[] mFlatTracks = measures(4);
    private final PathMeasure[] mFireTracks = measures(2);
    private final PathMeasure mBraid = new PathMeasure();
    private final Path mWave = new Path();
    private final Path mSegment = new Path();
    private final float[] mPosition = new float[2];
    private final float[] mTangent = new float[2];
    private final float[] mRadii = new float[8];
    private final float[] mInsetRadii = new float[8];
    private final float[] mLayerWidths = new float[3];
    private final float[] mLayerAlphas = new float[3];
    private SweepGradient mRainbow;
    private RadialGradient mLight;
    private LinearGradient mFade;
    private Shader mRainbowLight;
    private Shader mRainbowFade;
    private String mStyle = "default";
    private String mEffect = "none";
    private float mWidth;
    private float mHeight;
    private float mStroke = 8;
    private float mInset;
    private float mLength = 0.35f;
    private float mSize = 0.3f;
    private float mSpread;
    private float mIntensity;
    private int mDensity = 50;
    private int mDirection;
    private int mColor = 0xFFFFFFFF;
    private boolean mUseRainbow;
    private boolean mGeometryDirty = true;

    public static String normalizeStyle(String style) {
        style = style == null ? null : style.trim();
        if ("frame".equalsIgnoreCase(style)) return "rounded";
        for (String candidate : STYLES) {
            if (candidate.equalsIgnoreCase(style)) return candidate;
        }
        return "default";
    }

    public static String normalizeEffect(String effect) {
        if ("breathing".equals(effect) || "wave".equals(effect) || "chase".equals(effect)
                || "sparkle".equals(effect) || "comet".equals(effect)) return effect;
        return "none";
    }

    public static boolean isDesign(String style) {
        String value = normalizeStyle(style);
        return !"default".equals(value) && !"rounded".equals(value);
    }

    public static boolean usesStrokeWidth(String style) {
        String value = normalizeStyle(style);
        return !"side_flutter".equals(value) && !"flat_flutter".equals(value)
                && !"flat_waves".equals(value) && !"side_waves".equals(value)
                && !"waves_around".equals(value);
    }

    /** Pulse spacing shared by the finite ROM pulse and the repeating Settings preview. */
    public static float pulseAlpha(float progress, int pulseCount) {
        int count = Math.max(1, Math.min(5, pulseCount));
        int segments = Math.max(3, count);
        float position = clamp(progress, 0, 1) * segments;
        int segment = Math.min(segments - 1, (int) position);
        boolean active = false;
        for (int i = 0; i < count; i++) {
            if ((int) ((i + 0.5f) * segments / count) == segment) active = true;
        }
        if (!active) return 0;
        float fraction = position - segment;
        return clamp(Math.min(fraction, 1 - fraction) / 0.2f, 0, 1);
    }

    public void configure(String style, String effect, float strokeWidth, int length, int size,
            int density, int direction, float spread, float intensity) {
        mStyle = normalizeStyle(style);
        boolean design = isDesign(mStyle);
        mEffect = design ? "none" : normalizeEffect(effect);
        float stroke = Math.max(0.5f, strokeWidth);
        if (mStroke != stroke) mGeometryDirty = true;
        mStroke = stroke;
        mLength = usesDesignLength(mStyle) ? clamp(length / 100f, 0.05f, 1f) : 0.35f;
        mSize = usesDesignSize(mStyle) ? clamp(size / 100f, 0.05f, 1f) : 0.3f;
        mDensity = design ? Math.max(10, Math.min(100, density)) : 50;
        mDirection = design && direction == 1 ? 1 : 0;
        mSpread = clamp(spread, 0, 0.6f);
        mIntensity = clamp(intensity, 0, 1);
    }

    private static boolean usesDesignLength(String style) {
        switch (style) {
            case "chase_around": case "mirror_rain": case "corner_out": case "fire_dance":
            case "side_ripples": case "flat_ripples": case "flat_streaks": case "side_streaks":
            case "waves_around": case "lights_around": case "side_lights":
            case "snow_particles": case "bubble_particles": case "dot_particles": return true;
            default: return false;
        }
    }

    private static boolean usesDesignSize(String style) {
        switch (style) {
            case "fire_dance": case "flat_bars": case "side_bars": case "bars_around":
            case "flat_flutter": case "side_flutter": case "flat_waves": case "side_waves":
            case "waves_around": case "strings_around": case "side_braids": case "side_lights":
            case "serial_lights": case "serials_around": case "snow_particles":
            case "bubble_particles": case "dot_particles": return true;
            default: return false;
        }
    }

    public void setColor(int color, boolean rainbow) {
        if (mColor != color || mLight == null) {
            mLight = new RadialGradient(0, 0, 1,
                    new int[]{color, color, color & 0x00ffffff},
                    new float[]{0, 0.18f, 1}, Shader.TileMode.CLAMP);
            mFade = new LinearGradient(0, 0, 1, 0, color, color & 0x00ffffff,
                    Shader.TileMode.CLAMP);
            updateLightShaders();
        }
        mColor = color;
        mUseRainbow = rainbow;
    }

    /** Radii are ordered top-left, top-right, bottom-right, bottom-left, as for Path. */
    public void setBounds(float width, float height, float[] radii) {
        boolean changed = mWidth != width || mHeight != height;
        mWidth = Math.max(0, width);
        mHeight = Math.max(0, height);
        float maxRadius = Math.min(mWidth, mHeight) / 2;
        for (int i = 0; i < 8; i++) {
            float radius = radii == null ? 0 : clamp(radii[i], 0, maxRadius);
            changed |= radius != mRadii[i];
            mRadii[i] = radius;
        }
        mGeometryDirty |= changed;
    }

    /** One cycle is two seconds at normal speed. No per-frame randomness or retained particles. */
    public void draw(Canvas canvas, float cycle) {
        if (mWidth <= 1 || mHeight <= 1) return;
        if (mGeometryDirty) rebuildGeometry();
        float phase = wrap(mDirection == 1 ? -cycle : cycle);
        float breath = "breathing".equals(mEffect) ? 0.7f + 0.6f * pulse(phase) : 1;
        float stroke = usesStrokeWidth(mStyle) ? mStroke
                : Math.max(0.5f, Math.min(mWidth, mHeight) / 45);
        for (int i = 0; i < 3; i++) {
            mStrokes[i].rewind();
            mFills[i].rewind();
            mFills[i].setFillType(Path.FillType.WINDING);
            mLayerWidths[i] = stroke * breath;
            mLayerAlphas[i] = 1 - i * 0.25f;
        }
        buildDesign(phase);
        int save = canvas.save();
        canvas.clipPath(mClip);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setColor(mColor);
        if (mUseRainbow) {
            mMatrix.setRotate(phase * 360, mWidth / 2, mHeight / 2);
            mRainbow.setLocalMatrix(mMatrix);
            mPaint.setShader(mRainbow);
        } else {
            mPaint.setShader(null);
        }
        float opacity = "breathing".equals(mEffect) ? 0.6f + 0.4f * pulse(phase) : 1;
        if (mSpread > 0 && mIntensity > 0) {
            float spreadPx = Math.min(mWidth, mHeight) * mSpread * 0.3f;
            boolean design = isDesign(mStyle);
            for (int pass = 4; pass >= 1; pass--) {
                mPaint.setStyle(Paint.Style.STROKE);
                for (int layer = 2; layer >= 0; layer--) {
                    mPaint.setStrokeWidth(mLayerWidths[layer] + spreadPx * pass / 2);
                    mPaint.setAlpha((int) (255 * mIntensity * opacity
                            * (design ? mLayerAlphas[layer] : 1)
                            * (5 - pass) * 0.025f));
                    canvas.drawPath(mStrokes[layer], mPaint);
                    canvas.drawPath(mFills[layer], mPaint);
                }
            }
        }
        for (int layer = 2; layer >= 0; layer--) {
            mPaint.setAlpha((int) (255 * opacity * mLayerAlphas[layer]));
            mPaint.setStrokeWidth(mLayerWidths[layer]);
            mPaint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(mStrokes[layer], mPaint);
            mPaint.setStyle(Paint.Style.FILL);
            canvas.drawPath(mFills[layer], mPaint);
        }
        drawLights(canvas, phase);
        canvas.restoreToCount(save);
    }

    private void rebuildGeometry() {
        float half = Math.min(mStroke / 2, Math.min(mWidth, mHeight) / 4);
        mInset = half;
        mClip.rewind();
        mClip.addRoundRect(0, 0, mWidth, mHeight, mRadii, Path.Direction.CW);
        mOutline.setPath(mClip, true);
        for (int i = 0; i < 8; i++) mInsetRadii[i] = Math.max(0, mRadii[i] - half);
        mFrame.rewind();
        mFrame.addRoundRect(half, half, mWidth - half, mHeight - half,
                mInsetRadii, Path.Direction.CW);
        mPerimeter.setPath(mFrame, true);
        Path path = mSegment;
        for (int edge = 0; edge < 2; edge++) {
            float x = edge == 0 ? half : mWidth - half;
            path.rewind();
            path.moveTo(x, Math.max(half, mRadii[edge * 2 + 1]));
            path.lineTo(x, mHeight - Math.max(half, mRadii[7 - edge * 2]));
            mSides[edge].setPath(path, false);
            float y = edge == 0 ? half : mHeight - half;
            path.rewind();
            path.moveTo(Math.max(half, mRadii[edge == 0 ? 0 : 6]), y);
            path.lineTo(mWidth - Math.max(half, mRadii[edge == 0 ? 2 : 4]), y);
            mFlats[edge].setPath(path, false);
        }
        rebuildTracks();
        mRainbow = new SweepGradient(mWidth / 2, mHeight / 2, RAINBOW, null);
        if (mLight == null) setColor(mColor, mUseRainbow);
        else updateLightShaders();
        mGeometryDirty = false;
    }

    private void buildDesign(float phase) {
        switch (mStyle) {
            case "default": case "rounded": buildLegacy(phase); break;
            case "chase_around": buildChase(phase); break;
            case "mirror_rain": buildRain(phase, false); break;
            case "fire_dance": buildRain(phase, true); break;
            case "corner_out": buildCorners(phase); break;
            case "side_ripples": buildRipples(phase, false); break;
            case "flat_ripples": buildRipples(phase, true); break;
            case "flat_bars": buildBars(phase, 0); break;
            case "side_bars": buildBars(phase, 1); break;
            case "bars_around": buildBars(phase, 2); break;
            case "flat_streaks": buildStreaks(phase, true); break;
            case "side_streaks": buildStreaks(phase, false); break;
            case "flat_flutter": buildFlutter(phase, true); break;
            case "side_flutter": buildFlutter(phase, false); break;
            case "flat_waves": buildWaves(phase, 0); break;
            case "side_waves": buildWaves(phase, 1); break;
            case "waves_around": buildWaves(phase, 2); break;
            case "strings_around": buildWaves(phase, 3); break;
            case "side_braids": buildBraids(phase); break;
            case "lights_around": case "side_lights":
            case "serial_lights": case "serials_around": break;
            case "snow_particles": buildParticles(phase, 0); break;
            case "bubble_particles": buildParticles(phase, 1); break;
            case "dot_particles": buildParticles(phase, 2); break;
            default: break;
        }
    }

    private static PathMeasure[] measures(int count) {
        PathMeasure[] result = new PathMeasure[count];
        for (int i = 0; i < count; i++) result[i] = new PathMeasure();
        return result;
    }

    private void rebuildTracks() {
        final float k = 0.55228475f;
        for (int orientation = 0; orientation < 2; orientation++) {
            boolean flat = orientation == 0;
            float span = flat ? mWidth : mHeight;
            for (int track = 0; track < 4; track++) {
                int edge = track % 2;
                boolean reverse = (edge == 1) != (track >= 2);
                int corner = flat ? (edge == 0 ? (reverse ? 1 : 0) : (reverse ? 2 : 3))
                        : (edge == 0 ? (reverse ? 3 : 0) : (reverse ? 2 : 1));
                float along = mInsetRadii[corner * 2 + (flat ? 0 : 1)];
                float across = mInsetRadii[corner * 2 + (flat ? 1 : 0)];
                float start = reverse ? span - mInset : mInset;
                float sign = reverse ? -1 : 1;
                float end = start + sign * along;
                mSegment.rewind();
                edgeMove(mSegment, flat, edge, start, mInset + across);
                edgeCubic(mSegment, flat, edge, start, mInset + across * (1 - k),
                        end - sign * along * k, mInset, end, mInset);
                edgeLine(mSegment, flat, edge, reverse ? -span : span * 2, mInset);
                (flat ? mFlatTracks : mSideTracks)[track].setPath(mSegment, false);
            }
        }
        for (int edge = 0; edge < 2; edge++) {
            int corner = edge == 0 ? 3 : 2;
            float rx = mInsetRadii[corner * 2];
            float ry = mInsetRadii[corner * 2 + 1];
            float x = edge == 0 ? mInset : mWidth - mInset;
            float sign = edge == 0 ? 1 : -1;
            float bottom = mHeight - mInset;
            mSegment.rewind();
            mSegment.moveTo(mWidth / 2, bottom);
            mSegment.lineTo(x + sign * rx, bottom);
            mSegment.cubicTo(x + sign * rx * (1 - k), bottom,
                    x, bottom - ry * (1 - k), x, bottom - ry);
            mSegment.lineTo(x, -mHeight);
            mFireTracks[edge].setPath(mSegment, false);
        }
    }

    private float edgeX(boolean flat, int edge, float along, float inward) {
        return flat ? along : edge == 0 ? inward : mWidth - inward;
    }

    private float edgeY(boolean flat, int edge, float along, float inward) {
        return flat ? (edge == 0 ? inward : mHeight - inward) : along;
    }

    private void edgeMove(Path path, boolean flat, int edge, float along, float inward) {
        path.moveTo(edgeX(flat, edge, along, inward), edgeY(flat, edge, along, inward));
    }

    private void edgeLine(Path path, boolean flat, int edge, float along, float inward) {
        path.lineTo(edgeX(flat, edge, along, inward), edgeY(flat, edge, along, inward));
    }

    private void edgeCubic(Path path, boolean flat, int edge,
            float t1, float d1, float t2, float d2, float t3, float d3) {
        path.cubicTo(edgeX(flat, edge, t1, d1), edgeY(flat, edge, t1, d1),
                edgeX(flat, edge, t2, d2), edgeY(flat, edge, t2, d2),
                edgeX(flat, edge, t3, d3), edgeY(flat, edge, t3, d3));
    }

    private void buildLegacy(float phase) {
        boolean frame = "rounded".equals(mStyle);
        switch (mEffect) {
            case "wave": buildLegacyWaves(phase, frame ? 3 : 4); return;
            case "sparkle":
                addBase(frame, 2);
                for (int i = 0; i < 24; i++) {
                    float p = wrap(phase + hash(i));
                    point(frame ? mPerimeter : mSides[i % 2], hash(i + 43), 0);
                    circle(mFills[i % 3], mPosition[0], mPosition[1],
                            mStroke * (0.3f + pulse(p)));
                }
                return;
            case "chase": case "comet":
                addBase(frame, 2);
                if (frame) buildLegacyChase(phase);
                else buildLegacyStreaks(phase, false);
                return;
            default: addBase(frame, 0);
        }
    }

    private void buildLegacyChase(float phase) {
        int count = "comet".equals(mEffect) ? 1 : 2 + mDensity / 50;
        for (int i = 0; i < count; i++) {
            float head = wrap(phase + i / (float) count);
            for (int layer = 2; layer >= 0; layer--) {
                float tail = mLength * 0.35f * (layer + 1) / 3;
                segment(mPerimeter, head - tail, head, mStrokes[layer], true);
            }
        }
    }

    private void buildLegacyStreaks(float phase, boolean flat) {
        int count = "comet".equals(mEffect) ? 1 : 2 + mDensity / 20;
        for (int edge = 0; edge < 2; edge++) {
            PathMeasure measure = flat ? mFlats[edge] : mSides[edge];
            for (int i = 0; i < count; i++) {
                float head = wrap(phase + i / (float) count);
                if (edge == 1) head = 1 - head;
                for (int layer = 2; layer >= 0; layer--) {
                    float tail = mLength * 0.4f * (layer + 1) / 3;
                    segment(measure, head - tail, head, mStrokes[layer], false);
                }
            }
        }
    }

    private void buildLegacyWaves(float phase, int orientation) {
        boolean around = orientation == 2 || orientation == 3;
        boolean filled = orientation < 3;
        int edges = around ? 1 : 2;
        int samples = around ? 192 : 80;
        for (int edge = 0; edge < edges; edge++) {
            PathMeasure measure = around ? mPerimeter
                    : orientation == 0 ? mFlats[edge] : mSides[edge];
            for (int layer = 2; layer >= 0; layer--) {
                Path path = filled ? mFills[layer] : mStrokes[layer];
                float start = around && filled ? wrap(phase + layer * 0.1f) : 0;
                float span = around && filled ? 0.5f + mLength * 0.4f : 1;
                if (filled) outerPoint(measure, start, orientation, edge);
                else point(measure, start, 0);
                if (filled) path.moveTo(mPosition[0], mPosition[1]);
                for (int i = 0; i <= samples; i++) {
                    float t = i / (float) samples;
                    float cycles = 2 + mDensity / 20f;
                    float wave;
                    if (around && !filled) {
                        int lower = (int) cycles;
                        float mix = cycles - lower;
                        wave = (1 - mix) * pulse(t * lower - phase + layer / 3f)
                                + mix * pulse(t * (lower + 1) - phase + layer / 3f);
                    } else {
                        wave = pulse(t * cycles - phase + layer / 3f);
                    }
                    float depth = amplitude() * wave * (0.4f + layer * 0.25f);
                    if (!around) depth *= (float) Math.sin(Math.PI * t);
                    float fraction = around ? wrap(start + t * span) : t;
                    if (filled) outerPoint(measure, fraction, orientation, edge);
                    else point(measure, fraction, 0);
                    if (around) {
                        mPosition[0] -= mTangent[1] * depth;
                        mPosition[1] += mTangent[0] * depth;
                    } else if (orientation == 0) {
                        mPosition[1] += (edge == 0 ? 1 : -1) * depth;
                    } else {
                        mPosition[0] += (edge == 0 ? 1 : -1) * depth;
                    }
                    if (i == 0 && !filled) path.moveTo(mPosition[0], mPosition[1]);
                    else path.lineTo(mPosition[0], mPosition[1]);
                }
                if (filled) {
                    for (int i = samples; i >= 0; i--) {
                        float t = start + i * span / samples;
                        outerPoint(measure, around ? wrap(t) : Math.min(1, t),
                                orientation, edge);
                        path.lineTo(mPosition[0], mPosition[1]);
                    }
                    path.close();
                } else if (around) path.close();
            }
        }
    }

    private void addBase(boolean frame, int layer) {
        if (frame) mStrokes[layer].addPath(mFrame);
        else for (PathMeasure side : mSides) segment(side, 0, 1, mStrokes[layer], false);
    }

    private void buildChase(float phase) {
        int count = 1 + mDensity / 34;
        for (int layer = 0; layer < count; layer++) {
            float age = wrap(phase + layer / (2f * count));
            float extent = mLength * 0.45f * beat(age);
            mLayerAlphas[layer] = fade(age);
            mLayerWidths[layer] = mStroke * (0.6f + 0.4f * fade(age));
            for (int opposite = 0; opposite < 2; opposite++) {
                float head = age + opposite * 0.5f;
                segment(mPerimeter, head - extent, head, mStrokes[layer], true);
            }
        }
    }

    private void buildCorners(float phase) {
        buildTravel(phase, mSideTracks, mHeight, 2);
        buildTravel(phase, mFlatTracks, mWidth, 2);
    }

    private void buildRain(float phase, boolean fire) {
        buildTravel(phase, fire ? mFireTracks : mSideTracks,
                fire ? mHeight + mWidth / 2 : mHeight, 2);
    }

    private void buildRipples(float phase, boolean flat) {
        buildTravel(phase, flat ? mFlatTracks : mSideTracks, flat ? mWidth : mHeight, 4);
    }

    private void buildTravel(float phase, PathMeasure[] tracks, float span, int trackCount) {
        int count = 1 + mDensity / 34;
        float maxLength = span * (0.06f + mLength * 0.36f);
        for (int layer = 0; layer < count; layer++) {
            float age = wrap(phase + layer / (float) count);
            float length = maxLength * beat(age);
            float start = age * (span + maxLength) - maxLength * 0.5f;
            mLayerAlphas[layer] = fade(age);
            mLayerWidths[layer] = mStroke * (0.55f + 0.45f * beat(age));
            if ("fire_dance".equals(mStyle)) mLayerWidths[layer] *= 0.4f + 2 * mSize;
            for (int track = 0; track < trackCount; track++) {
                tracks[track].getSegment(Math.max(0, start), Math.max(0, start + length),
                        mStrokes[layer], true);
            }
        }
    }

    private void buildBars(float phase, int orientation) {
        int edges = orientation == 2 ? 1 : 2;
        for (int edge = 0; edge < edges; edge++) {
            PathMeasure measure = orientation == 2 ? mPerimeter
                    : orientation == 0 ? mFlats[edge] : mSides[edge];
            if (measure.getLength() <= 0) continue;
            int desired = orientation == 2 ? 32 + mDensity : 8 + mDensity / 2;
            int count = Math.max(2, Math.min(desired,
                    (int) (measure.getLength() / (mStroke * 1.8f))));
            for (int i = 0; i < count; i++) {
                float t = (i + 0.5f) / count;
                float sample = edge == 1 ? 1 - t : t;
                float depth = amplitude() * (0.08f + 0.92f * spectrum(sample, phase, 0));
                point(measure, t, 0);
                float nx = -mTangent[1];
                float ny = mTangent[0];
                if (orientation == 0) ny = edge == 0 ? 1 : -1;
                if (orientation == 1) nx = edge == 0 ? 1 : -1;
                Path path = mStrokes[0];
                path.moveTo(mPosition[0], mPosition[1]);
                path.lineTo(mPosition[0] + nx * depth, mPosition[1] + ny * depth);
            }
        }
    }

    private void buildStreaks(float phase, boolean flat) {
        float span = flat ? mWidth : mHeight;
        float halfLength = span * (0.08f + mLength * 0.28f);
        int count = 1 + mDensity / 25;
        for (int i = count - 1; i >= 0; i--) {
            float strength = beat(wrap(phase + i / (float) count));
            float offset = (float) Math.sin(TAU * phase + i * TAU / count)
                    * span * (0.12f + mLength * 0.16f);
            float depth = ridgeDepth() * (0.15f + 0.85f * strength);
            for (int edge = 0; edge < 2; edge++) {
                float center = span / 2 + (edge == 0 ? offset : -offset);
                Path path = mFills[i % 3];
                edgeMove(path, flat, edge, center - halfLength, 0);
                edgeLine(path, flat, edge, center - halfLength, depth / 3);
                edgeLine(path, flat, edge, center, depth);
                edgeLine(path, flat, edge, center + halfLength, depth / 3);
                edgeLine(path, flat, edge, center + halfLength, 0);
                path.close();
            }
        }
    }

    private void buildFlutter(float phase, boolean flat) {
        float span = flat ? mWidth : mHeight;
        int count = 1 + mDensity / 25;
        for (int i = count - 1; i >= 0; i--) {
            float center = span * (0.5f + (i - (count - 1) / 2f) * 0.5f / count);
            float shoulder = span * 0.5f / count;
            float depth = amplitude() * (0.15f + 0.85f
                    * beat(wrap(phase + i / (float) count)));
            for (int edge = 0; edge < 2; edge++) {
                Path path = mFills[i % 3];
                edgeMove(path, flat, edge, 0, 0);
                edgeCubic(path, flat, edge, center - shoulder, 0,
                        center - shoulder, depth, center, depth);
                edgeCubic(path, flat, edge, center + shoulder, depth,
                        center + shoulder, 0, span, 0);
                path.close();
            }
        }
    }

    private void buildWaves(float phase, int orientation) {
        boolean around = orientation >= 2;
        boolean filled = orientation != 3;
        int samples = around ? 256 : 96;
        for (int layer = 2; layer >= 0; layer--) {
            Path path = filled ? mFills[layer] : mStrokes[layer];
            if (around) {
                if (filled) {
                    path.setFillType(Path.FillType.EVEN_ODD);
                    path.addPath(mClip);
                }
                for (int i = 0; i <= samples; i++) {
                    float t = i / (float) samples;
                    float wave = spectrum(t, phase, layer);
                    if (filled) wave = (float) Math.pow(wave, 1.35f - mLength);
                    float depth = amplitude() * (0.08f + 0.92f * wave);
                    insetContourPoint(filled ? mOutline : mPerimeter, t, depth);
                    if (i == 0) path.moveTo(mPosition[0], mPosition[1]);
                    else path.lineTo(mPosition[0], mPosition[1]);
                }
                path.close();
            } else {
                boolean flat = orientation == 0;
                float span = flat ? mWidth : mHeight;
                for (int edge = 0; edge < 2; edge++) {
                    edgeMove(path, flat, edge, 0, 0);
                    for (int i = 1; i < samples; i++) {
                        float t = i / (float) samples;
                        float sample = edge == 1 ? 1 - t : t;
                        float envelope = (float) Math.sin(Math.PI * t);
                        float depth = amplitude() * envelope
                                * (0.08f + 0.92f * spectrum(sample, phase, layer));
                        edgeLine(path, flat, edge, t * span, depth);
                    }
                    edgeLine(path, flat, edge, span, 0);
                    path.close();
                }
            }
        }
    }

    private void buildBraids(float phase) {
        int count = 1 + mDensity / 34;
        for (int layer = 0; layer < count; layer++) {
            float age = wrap(phase + layer / (float) count);
            float extent = 0.1f + mLength * 0.55f;
            mLayerAlphas[layer] = fade(age);
            for (int edge = 0; edge < 2; edge++) {
                mWave.rewind();
                for (int i = 0; i <= 96; i++) {
                    float t = i / 96f;
                    float depth = amplitude() * (0.06f + 0.94f
                            * spectrum(t, phase, layer));
                    float along = (edge == 0 ? t : 1 - t) * mHeight;
                    if (i == 0) edgeMove(mWave, false, edge, along, mInset + depth);
                    else edgeLine(mWave, false, edge, along, mInset + depth);
                }
                mBraid.setPath(mWave, false);
                float start = age * (1 + extent) - extent;
                segment(mBraid, start, start + extent, mStrokes[layer], false);
            }
        }
    }

    private void buildParticles(float phase, int kind) {
        if (kind == 1) return;
        int count = 12 + mDensity;
        float depth = Math.min(mWidth, mHeight) * (0.06f + mLength * 0.28f);
        float radius = particleRadius();
        for (int i = 0; i < count; i++) {
            float age = wrap(phase + hash(i));
            int layer = i % 3;
            if (kind == 0) {
                float x = hash(i + 19) * mWidth + (float) Math.sin(TAU * age)
                        * radius * 2;
                float y = age * depth;
                float size = radius * (0.3f + 0.7f * hash(i + 91)) * beat(age);
                circle(mFills[layer], x, y, size);
            } else {
                float t = wrap(i / (float) count + 0.025f * (float) Math.sin(TAU * phase));
                float displacement = depth * hash(i + 19) * spectrum(t, phase, layer);
                point(mPerimeter, t, radius + displacement);
                circle(mFills[layer], mPosition[0], mPosition[1], radius * 0.55f);
            }
        }
    }

    private float particleRadius() {
        return Math.max(0.5f, Math.min(Math.min(mWidth, mHeight) * 0.015f,
                mStroke * (0.15f + mSize * 0.65f)));
    }

    private float spectrum(float t, float phase, int layer) {
        int frequency = 2 + mDensity / 20;
        return 0.55f * pulse(t * frequency - phase + layer / 3f)
                + 0.3f * pulse(t * (frequency + 3) + phase * 2 + layer / 3f)
                + 0.15f * pulse(t * (frequency + 7) - phase * 3);
    }

    private static float beat(float age) {
        float t = age < 0.3f ? age / 0.3f : (1 - age) / 0.7f;
        return t * t * (3 - 2 * t);
    }

    private static float fade(float age) {
        return clamp(Math.min(age / 0.08f, (1 - age) / 0.2f), 0, 1);
    }

    private void updateLightShaders() {
        if (mRainbow == null || mLight == null) return;
        mRainbowLight = new ComposeShader(mRainbow, mLight, PorterDuff.Mode.DST_IN);
        mRainbowFade = new ComposeShader(mRainbow, mFade, PorterDuff.Mode.DST_IN);
    }

    private void drawLights(Canvas canvas, float phase) {
        switch (mStyle) {
            case "lights_around": drawFrameLights(canvas, phase); break;
            case "side_lights":
                for (int layer = 2; layer >= 0; layer--) {
                    float strength = 0.2f + 0.8f * beat(wrap(phase + layer / 3f));
                    float depth = ridgeDepth() * strength;
                    float length = mHeight * (0.08f + mLength * 0.5f) + depth;
                    drawSpot(canvas, -depth * 0.15f, mHeight / 2,
                            depth, length, 0.8f - layer * 0.15f);
                    drawSpot(canvas, mWidth + depth * 0.15f, mHeight / 2,
                            depth, length, 0.8f - layer * 0.15f);
                }
                break;
            case "serial_lights": case "serials_around":
                drawSerials(canvas, phase, "serials_around".equals(mStyle));
                break;
            case "bubble_particles": case "snow_particles":
                boolean snow = "snow_particles".equals(mStyle);
                int count = 12 + mDensity;
                float depth = Math.min(mWidth, mHeight) * (0.06f + mLength * 0.28f);
                for (int i = 0; i < count; i++) {
                    if (snow && i % 3 != 2) continue;
                    float age = wrap(phase + hash(i));
                    float radius = particleRadius() * (0.3f + 0.7f * hash(i + 91))
                            * beat(age);
                    float x = hash(i + 19) * mWidth + (float) Math.sin(TAU * age)
                            * particleRadius() * 2;
                    float y = snow ? age * depth : mHeight - age * depth;
                    drawSpot(canvas, x, y, radius * 2.5f, radius * 2.5f,
                            fade(age) * (snow ? 0.45f : 0.9f));
                }
                break;
            default: break;
        }
    }

    private void drawFrameLights(Canvas canvas, float phase) {
        mLightPaint.setStyle(Paint.Style.STROKE);
        int count = 1 + mDensity / 25;
        for (int band = 0; band < count; band++) {
            float strength = 0.2f + 0.8f * beat(wrap(phase + band / (float) count));
            float depth = mWidth * (0.12f + mLength * 0.65f) * strength;
            mLightPaint.setStrokeWidth(mStroke * (0.35f + strength));
            mLightPaint.setAlpha((int) (255 * (0.9f - (band % 3) * 0.2f)));
            if (band % 3 == 0) {
                drawFrameFade(canvas, 0, mHeight / 2, depth, mHeight / 2);
                drawFrameFade(canvas, mWidth, mHeight / 2, mWidth - depth, mHeight / 2);
            } else {
                float slant = (band % 3 == 1 ? 1 : -1) * mWidth * 0.2f
                        * (float) Math.sin(TAU * phase);
                drawFrameFade(canvas, mWidth / 2, 0, mWidth / 2 + slant, depth);
                drawFrameFade(canvas, mWidth / 2, mHeight,
                        mWidth / 2 - slant, mHeight - depth);
            }
        }
    }

    private void drawFrameFade(Canvas canvas, float x0, float y0, float x1, float y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        mLightMatrix.setScale((float) Math.hypot(dx, dy), 1);
        mLightMatrix.postRotate((float) Math.toDegrees(Math.atan2(dy, dx)));
        mLightMatrix.postTranslate(x0, y0);
        mFade.setLocalMatrix(mLightMatrix);
        mLightPaint.setShader(mUseRainbow ? mRainbowFade : mFade);
        if (mSpread > 0 && mIntensity > 0) {
            float width = mLightPaint.getStrokeWidth();
            int alpha = mLightPaint.getAlpha();
            for (int pass = 3; pass > 0; pass--) {
                mLightPaint.setStrokeWidth(width + lightSpread() * pass / 2);
                mLightPaint.setAlpha((int) (alpha * mIntensity * (4 - pass) * 0.04f));
                canvas.drawPath(mFrame, mLightPaint);
            }
            mLightPaint.setStrokeWidth(width);
            mLightPaint.setAlpha(alpha);
        }
        canvas.drawPath(mFrame, mLightPaint);
    }

    private void drawSerials(Canvas canvas, float phase, boolean moving) {
        float radius = particleRadius();
        int count = Math.max(4, Math.min(16 + mDensity,
                (int) (mPerimeter.getLength() / (radius * 6))));
        for (int i = 0; i < count; i++) {
            float t = i / (float) count;
            float strength = 0.25f + 0.75f * spectrum(t, phase, 0);
            point(mPerimeter, wrap(t + (moving ? phase : 0)), radius * 1.5f);
            float size = radius * (0.5f + strength) * 2.5f;
            drawSpot(canvas, mPosition[0], mPosition[1], size, size, strength);
        }
    }

    private void drawSpot(Canvas canvas, float x, float y, float rx, float ry, float opacity) {
        if (rx < 0.1f || ry < 0.1f || opacity <= 0) return;
        if (mSpread > 0 && mIntensity > 0) {
            float spread = lightSpread();
            drawSpotMask(canvas, x, y, rx + spread, ry + spread, opacity * mIntensity * 0.3f);
        }
        drawSpotMask(canvas, x, y, rx, ry, opacity);
    }

    private void drawSpotMask(Canvas canvas, float x, float y, float rx, float ry, float opacity) {
        mLightMatrix.setScale(rx, ry);
        mLightMatrix.postTranslate(x, y);
        mLight.setLocalMatrix(mLightMatrix);
        mLightPaint.setShader(mUseRainbow ? mRainbowLight : mLight);
        mLightPaint.setStyle(Paint.Style.FILL);
        mLightPaint.setAlpha((int) (255 * clamp(opacity, 0, 1)));
        canvas.drawRect(x - rx, y - ry, x + rx, y + ry, mLightPaint);
    }

    private float lightSpread() {
        return Math.min(mWidth, mHeight) * mSpread * 0.3f;
    }

    private float ridgeDepth() {
        return Math.min(Math.min(mWidth, mHeight) * 0.18f,
                amplitude() * (float) Math.sqrt(mStroke / (Math.min(mWidth, mHeight) / 45)));
    }

    private void point(PathMeasure measure, float fraction, float inward) {
        measure.getPosTan(clamp(fraction, 0, 1) * measure.getLength(), mPosition, mTangent);
        mPosition[0] -= mTangent[1] * inward;
        mPosition[1] += mTangent[0] * inward;
    }

    private void insetContourPoint(PathMeasure measure, float fraction, float inward) {
        point(measure, fraction, 0);
        float dx = mPosition[0] - mWidth / 2;
        float dy = mPosition[1] - mHeight / 2;
        float distance = dx * mTangent[1] - dy * mTangent[0];
        float scale = clamp(1 - inward / Math.max(0.5f, distance), 0, 1);
        mPosition[0] = mWidth / 2 + dx * scale;
        mPosition[1] = mHeight / 2 + dy * scale;
    }

    private void outerPoint(PathMeasure measure, float fraction, int orientation, int edge) {
        float offset = orientation == 0 ? (edge == 0 ? -mInset : mInset)
                : orientation == 1 ? (edge == 0 ? mInset : -mInset) : -mInset;
        point(measure, fraction, offset);
    }

    private void segment(PathMeasure measure, float start, float end, Path out, boolean closed) {
        float length = measure.getLength();
        if (length <= 0 || end <= start) return;
        if (!closed) {
            start = clamp(start, 0, 1);
            end = clamp(end, 0, 1);
            measure.getSegment(start * length, end * length, out, true);
            return;
        }
        float span = Math.min(1, end - start);
        start = wrap(start);
        end = start + span;
        measure.getSegment(start * length, Math.min(1, end) * length, out, true);
        if (end > 1) measure.getSegment(0, (end - 1) * length, out, true);
    }

    private float amplitude() {
        return Math.min(mWidth, mHeight) * (0.01f + mSize * 0.12f);
    }

    private static void circle(Path path, float x, float y, float radius) {
        path.addCircle(x, y, Math.max(0.2f, radius), Path.Direction.CW);
    }

    private static float pulse(float phase) {
        return (float) (0.5 - 0.5 * Math.cos(TAU * phase));
    }

    private static float hash(int seed) {
        return wrap((float) Math.sin(seed * 127.1 + 311.7) * 43758.5453f);
    }

    private static float wrap(float value) {
        return value - (float) Math.floor(value);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
