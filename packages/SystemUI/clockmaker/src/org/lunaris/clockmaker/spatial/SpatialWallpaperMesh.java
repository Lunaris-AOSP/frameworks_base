/*
 * SPDX-FileCopyrightText: 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lunaris.clockmaker.spatial;
final class SpatialWallpaperMesh {
    static final int COLUMNS = 64;
    static final int ROWS = 128;
    private static final float SUBJECT_PERSPECTIVE_STRENGTH = 0.30f;
    final float[] vertices = new float[(COLUMNS + 1) * (ROWS + 1) * 2];
    final float[] textureCoordinates = new float[vertices.length];
    final short[] indices = new short[COLUMNS * ROWS * 6];
    private final float[] depth = new float[(COLUMNS + 1) * (ROWS + 1)];
    private final float[] smoothedDepth = new float[depth.length];
    private final float[] positions = new float[depth.length * 3];
    private float depthOrigin = 0.24f;
    private float depthRange = 0.12f;
    private float referenceRelief;
    private boolean background;
    private float horizontal;
    private float vertical;
    private float left;
    private float top;
    private float width;
    private float height;
    private boolean dirty = true;
    private final float[] original;
    private final int mapWidth;
    private final int mapHeight;
    private float offsetX;
    private float offsetY;
    private boolean depthDirty;
    private boolean geometryDirty = true;

    SpatialWallpaperMesh(float[] map, int mapWidth, int mapHeight) {
        if (mapWidth < 2 || mapHeight < 2 || map.length != mapWidth * mapHeight) {
            throw new IllegalArgumentException("Invalid spatial depth map");
        }
        original = map.clone();
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        rebuildDepth();
        int i = 0;
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLUMNS; x++) {
                int a = y * (COLUMNS + 1) + x;
                int b = a + 1;
                int c = a + COLUMNS + 1;
                int d = c + 1;
                indices[i++] = (short) a;
                indices[i++] = (short) b;
                indices[i++] = (short) c;
                indices[i++] = (short) b;
                indices[i++] = (short) d;
                indices[i++] = (short) c;
            }
        }
    }

    private void rebuildDepth() {
        depthDirty = false;
        for (int y = 0; y <= ROWS; y++) {
            for (int x = 0; x <= COLUMNS; x++) {
                float u = x / (float) COLUMNS;
                float v = y / (float) ROWS;
                depth[y * (COLUMNS + 1) + x] = sample(original, mapWidth, mapHeight,
                        u - offsetX, v - offsetY);
            }
        }
        for (int y = 0; y <= ROWS; y++) {
            for (int x = 0; x <= COLUMNS; x++) {
                int vertex = y * (COLUMNS + 1) + x;
                float low = 0f;
                float high = 1f;
                if (x > 0) {
                    low = Math.max(low, depth[vertex - 1] - 0.15f);
                    high = Math.min(high, depth[vertex - 1] + 0.15f);
                }
                if (y > 0) {
                    low = Math.max(low, depth[vertex - COLUMNS - 1] - 0.15f);
                    high = Math.min(high, depth[vertex - COLUMNS - 1] + 0.15f);
                }
                depth[vertex] = Math.max(low, Math.min(high, depth[vertex]));
            }
        }
        if (!background) smoothDepth();
    }

    private void smoothDepth() {
        int stride = COLUMNS + 1;
        for (int y = 0; y <= ROWS; y++) {
            int row = y * stride;
            for (int x = 0; x <= COLUMNS; x++) {
                smoothedDepth[row + x] = (depth[row + Math.max(0, x - 2)]
                        + 4f * depth[row + Math.max(0, x - 1)]
                        + 6f * depth[row + x]
                        + 4f * depth[row + Math.min(COLUMNS, x + 1)]
                        + depth[row + Math.min(COLUMNS, x + 2)]) / 16f;
            }
        }
        for (int y = 0; y <= ROWS; y++) {
            int above2 = Math.max(0, y - 2) * stride;
            int above = Math.max(0, y - 1) * stride;
            int row = y * stride;
            int below = Math.min(ROWS, y + 1) * stride;
            int below2 = Math.min(ROWS, y + 2) * stride;
            for (int x = 0; x <= COLUMNS; x++) {
                depth[row + x] = (smoothedDepth[above2 + x]
                        + 4f * smoothedDepth[above + x]
                        + 6f * smoothedDepth[row + x]
                        + 4f * smoothedDepth[below + x]
                        + smoothedDepth[below2 + x]) / 16f;
            }
        }
    }

    boolean setDepthOffset(float x, float y) {
        if (offsetX == x && offsetY == y) return false;
        offsetX = Float.isFinite(x) ? x : 0f;
        offsetY = Float.isFinite(y) ? y : 0f;
        depthDirty = geometryDirty = dirty = true;
        return true;
    }

    void setBackground() {
        if (background) return;
        background = true;
        depthOrigin = -0.24f;
        depthRange = 0.08f;
        depthDirty = geometryDirty = dirty = true;
    }

    boolean setTilt(float x, float y) {
        x = clamp(x, -1f, 1f);
        y = clamp(y, -1f, 1f);
        if (horizontal == x && vertical == y) return false;
        horizontal = x;
        vertical = y;
        dirty = true;
        return true;
    }

    void update(float l, float t, float w, float h) {
        if (left != l || top != t || width != w || height != h) {
            left = l;
            top = t;
            width = w;
            height = h;
            geometryDirty = dirty = true;
        }
        if (!dirty || w <= 0f || h <= 0f) return;
        if (depthDirty) rebuildDepth();
        dirty = false;
        double focal = w * 2.4;
        if (geometryDirty) {
            geometryDirty = false;
            int i = 0;
            double reliefSum = 0d;
            for (int y = 0; y <= ROWS; y++) {
                for (int x = 0; x <= COLUMNS; x++) {
                    float sx = x * w / COLUMNS;
                    float sy = y * h / ROWS;
                    textureCoordinates[i * 2] = l + sx;
                    textureCoordinates[i * 2 + 1] = t + sy;
                    double relief = (depthOrigin + depth[i] * depthRange) * w;
                    reliefSum += relief;
                    double sourceScale = (focal - relief) / focal;
                    positions[i * 3] = (float) ((sx - w * 0.5) * sourceScale);
                    positions[i * 3 + 1] = (float) ((sy - h * 0.5) * sourceScale);
                    positions[i * 3 + 2] = (float) (relief - focal);
                    i++;
                }
            }
            referenceRelief = (float) (reliefSum / depth.length);
        }
        double cameraX = horizontal * w * 0.12;
        double cameraY = vertical * w * 0.09;
        double distance = Math.sqrt(focal * focal + cameraX * cameraX + cameraY * cameraY);
        double planarDistance = Math.sqrt(focal * focal + cameraX * cameraX);
        double rightX = focal / planarDistance;
        double rightZ = -cameraX / planarDistance;
        double forwardX = -cameraX / distance;
        double forwardY = -cameraY / distance;
        double forwardZ = -focal / distance;
        double downX = -rightZ * forwardY;
        double downY = rightZ * forwardX - rightX * forwardZ;
        double downZ = rightX * forwardY;
        double referenceX = 0d;
        double referenceY = 0d;
        double referenceScale = 1d;
        if (!background) {
            double pz = referenceRelief - focal;
            double z = -cameraX * forwardX - cameraY * forwardY + pz * forwardZ;
            double scale = focal / z;
            referenceX = (-cameraX * rightX + pz * rightZ) * scale;
            referenceY = (-cameraX * downX - cameraY * downY + pz * downZ) * scale;
            referenceScale = -pz / z;
        }
        for (int i = 0; i < depth.length; i++) {
            double px = positions[i * 3] - cameraX;
            double py = positions[i * 3 + 1] - cameraY;
            double pz = positions[i * 3 + 2];
            double z = px * forwardX + py * forwardY + pz * forwardZ;
            double scale = focal / z;
            double projectedX = (px * rightX + pz * rightZ) * scale;
            double projectedY = (px * downX + py * downY + pz * downZ) * scale;
            if (!background) {
                double rigidX = referenceX
                        + (textureCoordinates[i * 2] - l - w * 0.5) * referenceScale;
                double rigidY = referenceY
                        + (textureCoordinates[i * 2 + 1] - t - h * 0.5) * referenceScale;
                projectedX = rigidX + (projectedX - rigidX) * SUBJECT_PERSPECTIVE_STRENGTH;
                projectedY = rigidY + (projectedY - rigidY) * SUBJECT_PERSPECTIVE_STRENGTH;
            }
            vertices[i * 2] = (float) (l + w * 0.5 + projectedX);
            vertices[i * 2 + 1] = (float) (t + h * 0.5 + projectedY);
        }
    }

    private static float sample(float[] map, int w, int h, float u, float v) {
        u = clamp(u, 0f, 1f);
        v = clamp(v, 0f, 1f);
        float x = u * (w - 1);
        float y = v * (h - 1);
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = Math.min(w - 1, x0 + 1);
        int y1 = Math.min(h - 1, y0 + 1);
        float a = map[y0 * w + x0] * (1f - x + x0) + map[y0 * w + x1] * (x - x0);
        float b = map[y1 * w + x0] * (1f - x + x0) + map[y1 * w + x1] * (x - x0);
        float result = a * (1f - y + y0) + b * (y - y0);
        return Float.isFinite(result) ? clamp(result, 0f, 1f) : 0f;
    }

    private static float clamp(float value, float min, float max) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : 0f;
    }
}
