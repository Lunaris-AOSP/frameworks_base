/*
 * SPDX-FileCopyrightText: 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lunaris.clockmaker.spatial;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
public class SpatialWallpaperDrawable extends FadeBottomDrawable {
    private final SpatialWallpaperMesh mesh;
    private SpatialWallpaperDrawable subjectUnderlay;

    public SpatialWallpaperDrawable(Bitmap bitmap, float[] depth, int width, int height) {
        super(bitmap, 0);
        mesh = depth == null ? null : new SpatialWallpaperMesh(depth, width, height);
    }

    public boolean hasSpatialDepth() {
        return mesh != null;
    }

    public SpatialWallpaperDrawable getSubjectUnderlay() { return subjectUnderlay; }

    public void setDepthOffset(float horizontal, float vertical) {
        if (mesh != null && mesh.setDepthOffset(horizontal, vertical)) invalidateSelf();
    }

    public void setSubjectUnderlay(Bitmap subject, float[] depth, int width, int height) {
        if (mesh != null) mesh.setBackground();
        subjectUnderlay = new SpatialWallpaperDrawable(subject, depth, width, height);
        invalidateSelf();
    }

    public void setTilt(float horizontal, float vertical) {
        boolean changed = mesh != null && mesh.setTilt(horizontal, vertical);
        if (subjectUnderlay != null) subjectUnderlay.setTilt(horizontal, vertical);
        if (changed) invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        if (subjectUnderlay != null) {
            subjectUnderlay.setBounds(getBounds());
            subjectUnderlay.draw(canvas);
        }
    }

    @Override
    protected void drawContent(Canvas canvas, Paint paint) {
        if (mesh == null) {
            super.drawContent(canvas, paint);
            return;
        }
        Rect bounds = getBounds();
        mesh.update(bounds.left, bounds.top, bounds.width(), bounds.height());
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, mesh.vertices.length, mesh.vertices,
                0, mesh.textureCoordinates, 0, null, 0, mesh.indices, 0,
                mesh.indices.length, paint);
    }
}
