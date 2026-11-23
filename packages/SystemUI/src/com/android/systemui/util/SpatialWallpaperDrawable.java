/*
 * SPDX-FileCopyrightText: 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.util;

import android.graphics.Bitmap;

class SpatialWallpaperDrawable extends org.lunaris.clockmaker.spatial.SpatialWallpaperDrawable {
    SpatialWallpaperDrawable(Bitmap bitmap, float[] depth, int width, int height) {
        super(bitmap, depth, width, height);
    }
}
