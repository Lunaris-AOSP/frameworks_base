/*
 * SPDX-FileCopyrightText: 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.util;

import android.content.Context;
import android.view.View;
import com.android.systemui.Dependency;
import com.android.systemui.tuner.TunerService;
import org.lunaris.clockmaker.spatial.SpatialWallpaperMotion;

public final class SpatialEffectController implements ParallaxTiltSensor.Listener {
    private static final String SENSITIVITY = "system:depth_wallpaper_spatial_sensitivity";
    private final SpatialWallpaperMotion motion;
    private final TunerService tuner;
    private final int[] parentLocation = new int[2];
    private View background;
    private View subject;
    private boolean registered;
    private boolean requested;
    private boolean hasZoom;
    private float zoom = 1f;
    private float pivotX;
    private float pivotY;
    private float spatialScale = 1f;
    private final TunerService.Tunable tunable = (key, value) -> {
        if (SENSITIVITY.equals(key)) setSensitivity(TunerService.parseInteger(value, 100));
    };

    public SpatialEffectController(Context context) {
        tuner = Dependency.get(TunerService.class);
        motion = new SpatialWallpaperMotion(context, (x, y, scale) -> {
            applyPose(background, x, y);
            applyPose(subject, x, y);
            if (spatialScale != scale) {
                spatialScale = scale;
                applyScale();
            }
        });
    }

    public void setSensitivity(int percent) {
        motion.setSensitivity(percent);
        setActive(requested, true);
    }

    public void attachBackgroundView(View view) { background = view; applyScale(); }
    public void attachSubjectView(View view) {
        subject = view;
        if (!registered && tuner != null) {
            registered = true;
            tuner.addTunable(tunable, SENSITIVITY);
        }
        applyScale();
    }

    public void setWallpaperZoom(float scale, float x, float y) {
        hasZoom = Float.isFinite(x) && Float.isFinite(y);
        zoom = Float.isFinite(scale) && scale > 0f ? scale : 1f;
        pivotX = x;
        pivotY = y;
        applyScale();
    }

    public void setActive(boolean active) { setActive(active, true); }
    public void setActive(boolean active, boolean animate) {
        requested = active;
        motion.setActive(active && hasDepth(background) && hasDepth(subject), animate);
    }

    public void onLayersChanged() { motion.dispatchPose(); applyScale(); }
    public void onDisplayRotationChanged(int rotation) { motion.onDisplayRotationChanged(rotation); }
    @Override public void onTiltChanged(float x, float y) { motion.onTiltChanged(x, y); }

    public void destroy() {
        requested = false;
        hasZoom = false;
        zoom = 1f;
        motion.destroy();
        if (registered) tuner.removeTunable(tunable);
        registered = false;
        background = subject = null;
    }

    private static boolean hasDepth(View view) {
        return view != null && view.getBackground() instanceof SpatialWallpaperDrawable
                && ((SpatialWallpaperDrawable) view.getBackground()).hasSpatialDepth();
    }

    private static void applyPose(View view, float x, float y) {
        if (view != null && view.getBackground() instanceof SpatialWallpaperDrawable) {
            ((SpatialWallpaperDrawable) view.getBackground()).setTilt(x, y);
        }
    }

    private void applyScale() { applyScale(background); applyScale(subject); }
    private void applyScale(View view) {
        if (view == null) return;
        if (hasZoom && view.getParent() instanceof View) {
            ((View) view.getParent()).getLocationOnScreen(parentLocation);
            view.setPivotX(pivotX - parentLocation[0] - view.getLeft());
            view.setPivotY(pivotY - parentLocation[1] - view.getTop());
        } else {
            view.setPivotX(view.getWidth() / 2f);
            view.setPivotY(view.getHeight() / 2f);
        }
        view.setScaleX(zoom * spatialScale);
        view.setScaleY(zoom * spatialScale);
        view.setTranslationX(zoom * (1f - spatialScale)
                * (view.getWidth() / 2f - view.getPivotX()));
        view.setTranslationY(zoom * (1f - spatialScale)
                * (view.getHeight() / 2f - view.getPivotY()));
    }
}
