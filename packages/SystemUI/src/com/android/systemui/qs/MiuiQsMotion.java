/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs;

public final class MiuiQsMotion {
    public static final float START_SCALE = 0.65f;
    public static final float VISIBILITY_THRESHOLD = 0.001f;
    private static final float SPEED_MULTIPLIER = 1.2f;
    private static final float PROGRESS_EPSILON = 0.0001f;

    private MiuiQsMotion() {}

    public static float distance(float centerX, float centerY, float cornerX, float headerY,
            float panelWidth, float panelHeight) {
        float size = Math.min(panelWidth, panelHeight);
        if (size <= 0f) return 0f;
        float x = (cornerX - centerX) / size;
        float y = (centerY - headerY) / size;
        return (float) Math.hypot(x, y);
    }

    public static float translation(float center, float origin, float progress) {
        return (origin - center) * (1f - START_SCALE) * (1f - progress);
    }

    public static float scale(float progress) {
        return START_SCALE + (1f - START_SCALE) * clamp(progress);
    }

    public static float inversePosition(float position, float center, float origin,
            float axisProgress, float appearanceProgress) {
        return center + (position - translation(center, origin, axisProgress) - center)
                / scale(appearanceProgress);
    }

    public static long showDelayMillis(float distance) {
        return (long) (Math.max(0f, distance) * 100f / SPEED_MULTIPLIER);
    }

    public static SpringParameters horizontalSpring(float distance) {
        return new SpringParameters(lerp(distance, 0.8f, 0.95f),
                lerp(distance, 0.3f, 0.55f));
    }

    public static SpringParameters verticalSpring(float distance) {
        return new SpringParameters(lerp(distance, 0.6f, 0.95f),
                lerp(distance, 0.35f, 0.45f));
    }

    public static SpringParameters appearanceSpring(float distance, boolean showing) {
        return showing
                ? new SpringParameters(lerp(distance, 0.8f, 0.95f),
                        lerp(distance, 0.4f, 0.6f))
                : new SpringParameters(lerp(distance, 0.8f, 0.9f),
                        lerp(distance, 0.25f, 0.2f));
    }

    public static float itemTarget(boolean closed, boolean fullyExpanded, boolean showing,
            boolean active, boolean selectedPage, boolean swiping) {
        if (closed) return 0f;
        if (swiping || !selectedPage) return 1f;
        if (!active) return fullyExpanded ? 1f : 0f;
        return showing ? 1f : 0f;
    }

    public static float clamp(float progress) {
        return Math.max(0f, Math.min(1f, progress));
    }

    private static float lerp(float progress, float start, float end) {
        return start + (end - start) * progress;
    }

    public static final class SpringParameters {
        public final float dampingRatio;
        public final float stiffness;

        private SpringParameters(float dampingRatio, float response) {
            this.dampingRatio = dampingRatio;
            double omega = 2d * Math.PI / Math.max(0.01f, response / SPEED_MULTIPLIER);
            stiffness = (float) (omega * omega);
        }
    }

    public static final class Appearance {
        private float mLastProgress;
        private boolean mShowing;
        private boolean mWasVisible;

        public boolean update(float progress, boolean visible, boolean swiping) {
            float expansion = clamp(progress);
            if (!visible || expansion <= 0f) {
                mShowing = false;
            } else if (swiping || !mWasVisible || expansion >= 1f
                    || expansion > mLastProgress + PROGRESS_EPSILON) {
                mShowing = true;
            } else if (expansion < mLastProgress - PROGRESS_EPSILON) {
                mShowing = false;
            }
            mLastProgress = expansion;
            mWasVisible = visible;
            return mShowing;
        }
    }
}
