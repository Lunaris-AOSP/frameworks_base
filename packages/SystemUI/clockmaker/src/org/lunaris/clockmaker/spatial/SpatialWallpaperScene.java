/*
 * SPDX-FileCopyrightText: 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lunaris.clockmaker.spatial;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.DataInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
public final class SpatialWallpaperScene {
    private static final int MAGIC = 0x53505731;
    public final Bitmap background;
    public final float[] backgroundDepth;
    public final float[] subjectDepth;
    public final int depthWidth;
    public final int depthHeight;

    private SpatialWallpaperScene(Bitmap background, float[] bg, float[] fg, int w, int h) {
        this.background = background;
        backgroundDepth = bg;
        subjectDepth = fg;
        depthWidth = w;
        depthHeight = h;
    }

    public static Uri request(String subject, int wallpaperId, int width, int height, float density,
            int offsetX, int offsetY) {
        return new Uri.Builder().scheme("content")
                .authority("org.lunaris.lockstyles.spatial").appendPath("scene")
                .appendQueryParameter("subject", subject)
                .appendQueryParameter("wallpaper", Integer.toString(wallpaperId))
                .appendQueryParameter("width", Integer.toString(width))
                .appendQueryParameter("height", Integer.toString(height))
                .appendQueryParameter("density", Float.toString(density))
                .appendQueryParameter("x", Integer.toString(offsetX))
                .appendQueryParameter("y", Integer.toString(offsetY)).build();
    }

    public static SpatialWallpaperScene read(ParcelFileDescriptor descriptor) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new ParcelFileDescriptor.AutoCloseInputStream(descriptor),
                        65536))) {
            if (in.readInt() != MAGIC || in.readInt() != 1) {
                throw new IOException("Unsupported spatial scene");
            }
            int width = in.readInt();
            int height = in.readInt();
            if (width < 1 || height < 1 || width > 4096 || height > 4096
                    || (long) width * height > 4_000_000) {
                throw new IOException("Invalid spatial image size");
            }
            int w = in.readInt();
            int h = in.readInt();
            if (w < 2 || h < 2 || w > 512 || h > 512) {
                throw new IOException("Invalid spatial depth size");
            }
            float[] bg = new float[w * h];
            float[] fg = new float[w * h];
            for (int i = 0; i < bg.length; i++) bg[i] = in.readUnsignedShort() / 65535f;
            for (int i = 0; i < fg.length; i++) fg[i] = in.readUnsignedShort() / 65535f;
            int size = in.readInt();
            if (size <= 0 || size > 32 * 1024 * 1024) {
                throw new IOException("Invalid spatial image packet");
            }
            byte[] encoded = new byte[size];
            in.readFully(encoded);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(encoded, 0, size, options);
            if (options.outWidth != width || options.outHeight != height) {
                throw new IOException("Spatial image dimensions do not match");
            }
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap background = BitmapFactory.decodeByteArray(encoded, 0, size, options);
            if (background == null) throw new IOException("Cannot decode spatial background");
            return new SpatialWallpaperScene(background, bg, fg, w, h);
        }
    }
}
