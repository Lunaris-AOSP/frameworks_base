/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.edgego;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RegionIterator;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

final class EdgeGoTapWindow {
    private final Context context;
    private final WindowManager windows;
    private final List<Surface> surfaces = new ArrayList<>();

    EdgeGoTapWindow(Context context, WindowManager windows) {
        this.context = context;
        this.windows = windows;
    }

    void show(List<EdgeGoGestureController.Zone> zones, Region exclusions, boolean enabled) {
        Region region = new Region();
        if (enabled) {
            for (EdgeGoGestureController.Zone zone : zones) {
                if (zone.captureTaps) region.union(zone.bounds);
            }
            region.op(exclusions, Region.Op.DIFFERENCE);
        }
        List<Rect> bounds = new ArrayList<>();
        RegionIterator iterator = new RegionIterator(region);
        Rect next = new Rect();
        while (iterator.next(next)) bounds.add(new Rect(next));
        for (int i = surfaces.size() - 1; i >= 0; i--) {
            Surface surface = surfaces.get(i);
            if (bounds.remove(surface.bounds)) continue;
            remove(surface);
            surfaces.remove(i);
        }
        for (Rect rect : bounds) {
            Surface surface = new Surface(rect);
            try {
                windows.addView(surface.view, surface.layout);
                surfaces.add(surface);
            } catch (RuntimeException e) {
                Log.w("EdgeGoGesture", "Cannot show tap zone", e);
            }
        }
    }

    void hide() {
        for (Surface surface : surfaces) remove(surface);
        surfaces.clear();
    }

    private void remove(Surface surface) {
        try { windows.removeViewImmediate(surface.view); }
        catch (IllegalArgumentException ignored) { }
    }

    private final class Surface {
        final Rect bounds;
        final View view;
        final WindowManager.LayoutParams layout;

        Surface(Rect bounds) {
            this.bounds = bounds;
            view = new View(context) {
                @Override public boolean onTouchEvent(MotionEvent event) { return true; }
            };
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            layout = new WindowManager.LayoutParams(bounds.width(), bounds.height(),
                    WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT);
            layout.setTitle("EdgeGo Tap Zone");
            layout.gravity = Gravity.TOP | Gravity.LEFT;
            layout.x = bounds.left;
            layout.y = bounds.top;
            layout.setFitInsetsTypes(0);
            layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            layout.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                    | WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION;
        }
    }
}
