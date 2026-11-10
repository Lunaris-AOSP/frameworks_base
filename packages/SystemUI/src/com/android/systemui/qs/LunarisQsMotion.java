/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs;

public final class LunarisQsMotion {
    private LunarisQsMotion() {}

    public static float entranceAlpha(float expansion, float depth) {
        float delay = 0.04f + 0.14f * clamp(depth);
        return smoothStep((expansion - delay) / 0.62f);
    }

    public static float entranceTranslation(float expansion, float depth) {
        float remaining = 1f - clamp(expansion);
        return -(56f + 32f * clamp(depth)) * remaining * remaining;
    }

    public static float entranceScale(float expansion) {
        float remaining = 1f - clamp(expansion);
        return 1f - 0.06f * remaining * remaining;
    }

    public static float panelSwipeAlpha(float progress) {
        return smoothStep(progress);
    }

    public static float panelRowOffset(float progress, boolean fromQs, float depth, float width) {
        float p = clamp(progress);
        float direction = fromQs ? 1f : -1f;
        return direction * Math.max(0f, width) * 0.22f * clamp(depth) * 4f * p * (1f - p);
    }

    public static long settleDuration(float distance, float velocity) {
        float duration = Math.abs(velocity) > 0.01f
                ? 2500f * Math.abs(distance / velocity)
                : 360f * (float) Math.sqrt(Math.abs(distance));
        return Math.round(Math.max(180f, Math.min(420f, duration)));
    }

    public static float settleInterpolation(float fraction, float initialSlope) {
        float t = clamp(fraction);
        float slope = Math.max(0f, Math.min(3f, initialSlope));
        return t * t * (3f - 2f * t) + slope * t * (1f - t) * (1f - t);
    }

    private static float smoothStep(float fraction) {
        float t = clamp(fraction);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
