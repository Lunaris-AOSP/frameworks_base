/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.graphics.drawable;

import android.annotation.Nullable;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @hide
 */
public final class AxBlurRimPainter {
    private static final float[] EDGE_DEPTHS = {0f, 0.2f, 0.5f, 0.8f, 1f};
    private static final float[] EDGE_ALPHAS = {0f, 0.7f, 1f, 0.5f, 0f};
    private static final float[] GLOW_DEPTHS = {0f, 0.08f, 0.2f, 0.4f, 0.65f, 1f};
    private static final float[] GLOW_ALPHAS = {0f, 1f, 0.72f, 0.38f, 0.12f, 0f};

    private final Path mOutline = new Path();
    private final RectF mRect = new RectF();
    private final Paint mPaint = new Paint();
    private final Paint mLayerPaint = new Paint();

    private AxBlurCustomization.Rim mCachedStyle;
    private float mCachedDensity;
    private float[] mCachedRadii;
    private float mCachedRadius;
    private Path mCachedPath;
    private int mCachedPathGeneration = -1;
    private List<Mesh> mMeshes = Collections.emptyList();

    public AxBlurRimPainter() {
        mPaint.setColor(Color.WHITE);
    }

    public void draw(
            Canvas canvas,
            AxBlurCustomization.Rim style,
            float density,
            float left,
            float top,
            float right,
            float bottom,
            float cornerRadius,
            @Nullable float[] cornerRadii,
            int alpha) {
        draw(canvas, style, density, left, top, right, bottom, cornerRadius, cornerRadii,
                null, alpha, null);
    }

    public void draw(
            Canvas canvas,
            AxBlurCustomization.Rim style,
            float density,
            float left,
            float top,
            float right,
            float bottom,
            float cornerRadius,
            @Nullable float[] cornerRadii,
            @Nullable Path clipPath,
            int alpha,
            @Nullable ColorFilter colorFilter) {
        if (style == null || !style.enabled || style.strength == 0f
                || Color.alpha(style.color) == 0 || alpha <= 0) {
            return;
        }
        if (!Float.isFinite(density) || density <= 0f
                || !Float.isFinite(left) || !Float.isFinite(top)
                || !Float.isFinite(right) || !Float.isFinite(bottom)
                || right <= left || bottom <= top) {
            return;
        }
        final float[] radii = (cornerRadii != null && cornerRadii.length >= 8) ? cornerRadii : null;
        final float radius = Float.isFinite(cornerRadius) ? Math.max(0f, cornerRadius) : 0f;
        final int pathGeneration = clipPath != null ? clipPath.getGenerationId() : -1;
        if (mCachedStyle == null
                || !mCachedStyle.matches(style)
                || mCachedDensity != density
                || mRect.left != left
                || mRect.top != top
                || mRect.right != right
                || mRect.bottom != bottom
                || mCachedRadius != radius
                || !sameRadii(radii)
                || mCachedPath != clipPath
                || mCachedPathGeneration != pathGeneration) {
            mRect.set(left, top, right, bottom);
            mOutline.rewind();
            if (clipPath != null) {
                mOutline.set(clipPath);
            } else if (radii != null) {
                mOutline.addRoundRect(mRect, radii, Path.Direction.CW);
            } else if (radius > 0f) {
                mOutline.addRoundRect(mRect, radius, radius, Path.Direction.CW);
            } else {
                mOutline.addRect(mRect, Path.Direction.CW);
            }
            mMeshes = buildMeshes(style, density);
            mCachedStyle = style;
            mCachedDensity = density;
            mCachedRadius = radius;
            mCachedRadii = radii != null ? Arrays.copyOf(radii, 8) : null;
            mCachedPath = clipPath;
            mCachedPathGeneration = pathGeneration;
        }
        if (mMeshes.isEmpty()) return;
        final int clampedAlpha = Math.max(0, Math.min(255, alpha));
        final int save = canvas.save();
        try {
            canvas.clipRect(mRect);
            canvas.clipPath(mOutline);
            for (int i = 0; i < mMeshes.size(); i++) {
                final Mesh mesh = mMeshes.get(i);
                if (mesh.layerAlpha == 0f) continue;
                final int layer;
                if (mesh.layerAlpha >= 0f) {
                    mLayerPaint.setAlpha(Math.max(0, Math.min(255,
                            Math.round(mesh.layerAlpha * clampedAlpha / 255f))));
                    mLayerPaint.setColorFilter(colorFilter);
                    mPaint.setAlpha(255);
                    mPaint.setColorFilter(null);
                    layer = canvas.saveLayer(mRect, mLayerPaint);
                } else {
                    mPaint.setAlpha(clampedAlpha);
                    mPaint.setColorFilter(colorFilter);
                    layer = 0;
                }
                canvas.drawVertices(
                        Canvas.VertexMode.TRIANGLES,
                        mesh.vertices.length,
                        mesh.vertices,
                        0,
                        null,
                        0,
                        mesh.colors,
                        0,
                        mesh.indices,
                        0,
                        mesh.indices.length,
                        mPaint);
                if (layer != 0) canvas.restoreToCount(layer);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private boolean sameRadii(@Nullable float[] radii) {
        if (mCachedRadii == null) return radii == null;
        if (radii == null) return false;
        for (int i = 0; i < 8; i++) {
            if (mCachedRadii[i] != radii[i]) return false;
        }
        return true;
    }

    private List<Mesh> buildMeshes(AxBlurCustomization.Rim style, float density) {
        final ArrayList<Mesh> result = new ArrayList<>();
        final PathMeasure measure = new PathMeasure(mOutline, true);
        final float[] position = new float[2];
        final float[] tangent = new float[2];
        final float maxDepth = Math.min(mRect.width(), mRect.height()) * 0.45f;
        final Region filledRegion = new Region();
        filledRegion.setPath(
                mOutline,
                new Region(
                        (int) Math.floor(mRect.left),
                        (int) Math.floor(mRect.top),
                        (int) Math.ceil(mRect.right),
                        (int) Math.ceil(mRect.bottom)));
        do {
            final float length = measure.getLength();
            if (!Float.isFinite(length) || length <= 0f) continue;
            final int count = Math.max(16, Math.min(2048, (int) Math.ceil(length / 2f)));
            final float[] points = new float[count * 2];
            final float[] normals = new float[count * 2];
            for (int i = 0; i < count; i++) {
                measure.getPosTan(length * i / count, position, tangent);
                points[i * 2] = position[0];
                points[i * 2 + 1] = position[1];
                normals[i * 2] = -tangent[1];
                normals[i * 2 + 1] = tangent[0];
            }
            int inwardVotes = 0;
            for (int i = 0; i < count; i++) {
                final float x = points[i * 2];
                final float y = points[i * 2 + 1];
                final float nx = normals[i * 2];
                final float ny = normals[i * 2 + 1];
                final boolean forward = filledRegion.contains(
                        (int) Math.floor(x + nx * 2f), (int) Math.floor(y + ny * 2f));
                final boolean backward = filledRegion.contains(
                        (int) Math.floor(x - nx * 2f), (int) Math.floor(y - ny * 2f));
                if (forward != backward) inwardVotes += forward ? 1 : -1;
            }
            float signedArea = 0f;
            for (int i = 0; i < count; i++) {
                final int next = (i + 1) % count;
                signedArea += points[i * 2] * points[next * 2 + 1]
                        - points[next * 2] * points[i * 2 + 1];
            }
            if (inwardVotes < 0 || (inwardVotes == 0 && signedArea < 0f)) {
                for (int i = 0; i < normals.length; i++) normals[i] = -normals[i];
            }
            if (style.glowDepthDp > 0f && style.glowAlpha > 0f) {
                result.add(buildMesh(
                        points,
                        normals,
                        style,
                        Math.min(style.glowDepthDp * density, maxDepth),
                        GLOW_DEPTHS,
                        GLOW_ALPHAS,
                        style.glowAlpha));
            }
            if (style.edgeWidthDp > 0f) {
                result.add(buildMesh(
                        points,
                        normals,
                        style,
                        Math.min(style.edgeWidthDp * density, maxDepth),
                        EDGE_DEPTHS,
                        EDGE_ALPHAS,
                        1f));
            }
        } while (measure.nextContour());
        return result;
    }

    private Mesh buildMesh(
            float[] points,
            float[] normals,
            AxBlurCustomization.Rim style,
            float depth,
            float[] depths,
            float[] alphas,
            float opacity) {
        final int count = points.length / 2;
        final float[] vertices = new float[count * depths.length * 2];
        final int[] colors = new int[count * depths.length];
        final short[] indices = new short[count * (depths.length - 1) * 6];
        final int rgb = style.color & 0x00ffffff;
        final float[] depthLimits = new float[count];
        Arrays.fill(depthLimits, depth);
        boolean needsLayer = false;
        for (int i = 0; i < count; i++) {
            final int previous = (i + count - 1) % count;
            final int next = (i + 1) % count;
            final float nx = normals[next * 2] - normals[previous * 2];
            final float ny = normals[next * 2 + 1] - normals[previous * 2 + 1];
            final float change = (float) Math.sqrt(nx * nx + ny * ny);
            if (change > 0.0001f) {
                final float dx = points[next * 2] - points[previous * 2];
                final float dy = points[next * 2 + 1] - points[previous * 2 + 1];
                final float limit = (float) Math.sqrt(dx * dx + dy * dy) / change * 0.95f;
                if (depth > limit) {
                    depthLimits[i] = limit;
                    needsLayer = true;
                }
            }
        }
        final float maxAlpha = Color.alpha(style.color)
                * style.strength
                * Math.max(style.topAlpha, Math.max(style.bottomAlpha, style.sideAlpha))
                * opacity;
        final float normalization = (needsLayer && maxAlpha > 0f) ? (255f / maxAlpha) : 1f;
        for (int i = 0; i < count; i++) {
            final float nx = normals[i * 2];
            final float ny = normals[i * 2 + 1];
            final int previous = (i + count - 1) % count;
            final int next = (i + 1) % count;
            final float normalDx = normals[next * 2] - normals[previous * 2];
            final float normalDy = normals[next * 2 + 1] - normals[previous * 2 + 1];
            final float normalChange = (float) Math.sqrt(normalDx * normalDx + normalDy * normalDy);
            final float curvature = Math.max(0f, Math.min(1f, normalChange / 0.002f));
            final float diagonal = Math.max(0f, Math.min(1f, 2f * Math.abs(nx * ny)));
            final float corner = (float) Math.pow(diagonal, style.cornerTaperPower) * curvature;
            final float widthScale = 1f - corner * (1f - style.cornerWidthScale);
            final float alphaScale = 1f - corner * (1f - style.cornerAlphaScale);
            final float edgeAlpha = style.sideAlpha
                    + Math.abs(ny) * ((ny >= 0f ? style.topAlpha : style.bottomAlpha)
                            - style.sideAlpha);
            final float peakAlpha = Color.alpha(style.color)
                    * style.strength
                    * edgeAlpha
                    * alphaScale
                    * opacity
                    * normalization;
            for (int level = 0; level < depths.length; level++) {
                final int vertex = level * count + i;
                final float inset = depthLimits[i] * widthScale * depths[level];
                vertices[vertex * 2] = points[i * 2] + nx * inset;
                vertices[vertex * 2 + 1] = points[i * 2 + 1] + ny * inset;
                final int a = Math.max(0, Math.min(255, Math.round(peakAlpha * alphas[level])));
                colors[vertex] = rgb | (a << 24);
            }
        }
        int index = 0;
        for (int level = 0; level < depths.length - 1; level++) {
            for (int i = 0; i < count; i++) {
                final int next = (i + 1) % count;
                final int outer = level * count + i;
                final int outerNext = level * count + next;
                final int inner = outer + count;
                final int innerNext = outerNext + count;
                indices[index++] = (short) outer;
                indices[index++] = (short) outerNext;
                indices[index++] = (short) inner;
                indices[index++] = (short) inner;
                indices[index++] = (short) outerNext;
                indices[index++] = (short) innerNext;
            }
        }
        return new Mesh(vertices, colors, indices, needsLayer ? maxAlpha : -1f);
    }

    private static final class Mesh {
        final float[] vertices;
        final int[] colors;
        final short[] indices;
        final float layerAlpha;

        Mesh(float[] vertices, int[] colors, short[] indices, float layerAlpha) {
            this.vertices = vertices;
            this.colors = colors;
            this.indices = indices;
            this.layerAlpha = layerAlpha;
        }
    }
}
