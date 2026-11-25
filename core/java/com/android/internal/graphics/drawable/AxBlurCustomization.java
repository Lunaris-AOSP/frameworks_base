/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.graphics.drawable;

import android.content.ComponentCallbacks;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.ArraySet;

/** @hide */
public final class AxBlurCustomization {
    public static final String TINT_ENABLED = "ax_blur_tint_override_enabled";
    public static final String TINT_MODE = "ax_blur_tint_override_mode";
    public static final String TINT_COLOR = "ax_blur_tint_override_color";
    public static final String TINT_ALPHA = "ax_blur_tint_override_alpha";
    public static final String RADIUS_ENABLED = "ax_blur_radius_override_enabled";
    public static final String RADIUS_DP = "ax_blur_radius_override_dp";
    public static final String RIM_ENABLED = "ax_blur_rim_enabled";
    public static final String RIM_COLOR = "ax_blur_rim_color";
    public static final String RIM_STRENGTH = "ax_blur_rim_strength";
    public static final String RIM_TOP_ALPHA = "ax_blur_rim_top_alpha";
    public static final String RIM_BOTTOM_ALPHA = "ax_blur_rim_bottom_alpha";
    public static final String RIM_SIDE_ALPHA = "ax_blur_rim_side_alpha";
    public static final String RIM_EDGE_WIDTH = "ax_blur_rim_edge_width";
    public static final String RIM_GLOW_DEPTH = "ax_blur_rim_glow_depth";
    public static final String RIM_GLOW_ALPHA = "ax_blur_rim_glow_alpha";
    public static final String RIM_CORNER_WIDTH_SCALE = "ax_blur_rim_corner_width_scale";
    public static final String RIM_CORNER_ALPHA_SCALE = "ax_blur_rim_corner_alpha_scale";
    public static final String RIM_CORNER_TAPER_POWER = "ax_blur_rim_corner_taper_power";
    public static final int TINT_ACCENT = 0;
    public static final int TINT_SECONDARY = 1;
    public static final int TINT_CUSTOM = 2;

    public static final int DEFAULT_RIM_COLOR = 0xffffffff;
    public static final int DEFAULT_RIM_STRENGTH = 100;
    public static final int DEFAULT_RIM_TOP_ALPHA = 14;
    public static final int DEFAULT_RIM_BOTTOM_ALPHA = 50;
    public static final int DEFAULT_RIM_SIDE_ALPHA = 0;
    public static final int DEFAULT_RIM_EDGE_WIDTH_TENTHS = 8;
    public static final int DEFAULT_RIM_GLOW_DEPTH_TENTHS = 125;
    public static final int DEFAULT_RIM_GLOW_ALPHA = 30;
    public static final int DEFAULT_RIM_CORNER_WIDTH_SCALE = 100;
    public static final int DEFAULT_RIM_CORNER_ALPHA_SCALE = 80;
    public static final int DEFAULT_RIM_CORNER_TAPER_POWER_TENTHS = 68;

    private static final String[] KEYS = {
            TINT_ENABLED, TINT_MODE, TINT_COLOR, TINT_ALPHA, RADIUS_ENABLED, RADIUS_DP,
            RIM_ENABLED, RIM_COLOR, RIM_STRENGTH, RIM_TOP_ALPHA, RIM_BOTTOM_ALPHA,
            RIM_SIDE_ALPHA, RIM_EDGE_WIDTH, RIM_GLOW_DEPTH, RIM_GLOW_ALPHA,
            RIM_CORNER_WIDTH_SCALE, RIM_CORNER_ALPHA_SCALE, RIM_CORNER_TAPER_POWER,
    };
    private static AxBlurCustomization sInstance;

    private final Context mContext;
    private final ContentResolver mResolver;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ArraySet<Runnable> mListeners = new ArraySet<>();
    private final Runnable mRefresh = this::refresh;
    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            mHandler.removeCallbacks(mRefresh);
            mHandler.post(mRefresh);
        }
    };
    private final ComponentCallbacks mConfigurationListener = new ComponentCallbacks() {
        @Override
        public void onConfigurationChanged(Configuration configuration) {
            if (mState.tintEnabled || mState.radiusEnabled || mState.rim.enabled) refresh();
        }

        @Override
        public void onLowMemory() {}
    };
    private volatile State mState;
    private volatile boolean mObserving;
    private int mResolvedTint;
    private float mDensity;

    private AxBlurCustomization(Context context) {
        mContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        mResolver = mContext.getContentResolver();
        mState = readState();
        mResolvedTint = mState.resolveTint(mContext, 0);
        mDensity = mContext.getResources().getDisplayMetrics().density;
    }

    public static synchronized AxBlurCustomization getInstance(Context context) {
        if (sInstance == null) sInstance = new AxBlurCustomization(context);
        return sInstance;
    }

    public State getState() {
        if (!mObserving) mState = readState();
        return mState;
    }

    public void addListener(Runnable listener) {
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(() -> addListener(listener));
            return;
        }
        if (mListeners.contains(listener)) return;
        if (mListeners.isEmpty()) {
            for (String key : KEYS) {
                mResolver.registerContentObserver(Settings.Global.getUriFor(key), false, mObserver);
            }
            mContext.registerComponentCallbacks(mConfigurationListener);
            mState = readState();
            mResolvedTint = mState.resolveTint(mContext, 0);
            mDensity = mContext.getResources().getDisplayMetrics().density;
            mObserving = true;
        }
        mListeners.add(listener);
        listener.run();
    }

    public void removeListener(Runnable listener) {
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(() -> removeListener(listener));
            return;
        }
        if (!mListeners.remove(listener) || !mListeners.isEmpty()) return;
        mObserving = false;
        mResolver.unregisterContentObserver(mObserver);
        mContext.unregisterComponentCallbacks(mConfigurationListener);
        mHandler.removeCallbacks(mRefresh);
    }

    private void refresh() {
        final State next = readState();
        final int tint = next.resolveTint(mContext, 0);
        final float density = mContext.getResources().getDisplayMetrics().density;
        if (mState.matches(next) && mResolvedTint == tint
                && (!(next.radiusEnabled || next.rim.enabled) || mDensity == density)) return;
        mState = next;
        mResolvedTint = tint;
        mDensity = density;
        final Runnable[] listeners = mListeners.toArray(new Runnable[0]);
        for (Runnable listener : listeners) {
            if (mListeners.contains(listener)) listener.run();
        }
    }

    private State readState() {
        final boolean tintEnabled = Settings.Global.getInt(mResolver, TINT_ENABLED, 0) != 0;
        final boolean radiusEnabled = Settings.Global.getInt(mResolver, RADIUS_ENABLED, 0) != 0;
        final int mode = tintEnabled
                ? Math.max(TINT_ACCENT, Math.min(TINT_CUSTOM,
                        Settings.Global.getInt(mResolver, TINT_MODE, TINT_ACCENT))) : TINT_ACCENT;
        final int color = tintEnabled && mode == TINT_CUSTOM
                ? Settings.Global.getInt(mResolver, TINT_COLOR, 0xff448aff) & 0x00ffffff : 0;
        final int alpha = tintEnabled
                ? Math.max(0, Math.min(100, Settings.Global.getInt(mResolver, TINT_ALPHA, 50))) : 0;
        final float radius = radiusEnabled
                ? Settings.Global.getFloat(mResolver, RADIUS_DP, 34f) : 0f;
        return new State(tintEnabled, mode, color, alpha, radiusEnabled,
                Float.isFinite(radius) ? Math.max(0f, Math.min(60f, radius)) : 34f,
                readRim());
    }

    private Rim readRim() {
        if (Settings.Global.getInt(mResolver, RIM_ENABLED, 0) == 0) return Rim.NONE;
        return new Rim(true, Settings.Global.getInt(mResolver, RIM_COLOR, DEFAULT_RIM_COLOR),
                percent(RIM_STRENGTH, DEFAULT_RIM_STRENGTH),
                percent(RIM_TOP_ALPHA, DEFAULT_RIM_TOP_ALPHA),
                percent(RIM_BOTTOM_ALPHA, DEFAULT_RIM_BOTTOM_ALPHA),
                percent(RIM_SIDE_ALPHA, DEFAULT_RIM_SIDE_ALPHA),
                tenths(RIM_EDGE_WIDTH, DEFAULT_RIM_EDGE_WIDTH_TENTHS, 0, 200),
                tenths(RIM_GLOW_DEPTH, DEFAULT_RIM_GLOW_DEPTH_TENTHS, 0, 300),
                percent(RIM_GLOW_ALPHA, DEFAULT_RIM_GLOW_ALPHA),
                percent(RIM_CORNER_WIDTH_SCALE, DEFAULT_RIM_CORNER_WIDTH_SCALE),
                percent(RIM_CORNER_ALPHA_SCALE, DEFAULT_RIM_CORNER_ALPHA_SCALE),
                tenths(RIM_CORNER_TAPER_POWER, DEFAULT_RIM_CORNER_TAPER_POWER_TENTHS, 1, 80));
    }

    private float percent(String key, int fallback) {
        return Math.max(0, Math.min(100, Settings.Global.getInt(mResolver, key, fallback))) / 100f;
    }

    private float tenths(String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, Settings.Global.getInt(mResolver, key, fallback))) / 10f;
    }

    public static final class Rim {
        public static final Rim NONE = new Rim(false, DEFAULT_RIM_COLOR,
                DEFAULT_RIM_STRENGTH / 100f, DEFAULT_RIM_TOP_ALPHA / 100f,
                DEFAULT_RIM_BOTTOM_ALPHA / 100f, DEFAULT_RIM_SIDE_ALPHA / 100f,
                DEFAULT_RIM_EDGE_WIDTH_TENTHS / 10f, DEFAULT_RIM_GLOW_DEPTH_TENTHS / 10f,
                DEFAULT_RIM_GLOW_ALPHA / 100f, DEFAULT_RIM_CORNER_WIDTH_SCALE / 100f,
                DEFAULT_RIM_CORNER_ALPHA_SCALE / 100f,
                DEFAULT_RIM_CORNER_TAPER_POWER_TENTHS / 10f);

        public final boolean enabled;
        public final int color;
        public final float strength;
        public final float topAlpha;
        public final float bottomAlpha;
        public final float sideAlpha;
        public final float edgeWidthDp;
        public final float glowDepthDp;
        public final float glowAlpha;
        public final float cornerWidthScale;
        public final float cornerAlphaScale;
        public final float cornerTaperPower;

        public Rim(boolean enabled, int color, float strength, float topAlpha,
                float bottomAlpha, float sideAlpha, float edgeWidthDp, float glowDepthDp,
                float glowAlpha, float cornerWidthScale, float cornerAlphaScale,
                float cornerTaperPower) {
            this.enabled = enabled;
            this.color = color;
            this.strength = strength;
            this.topAlpha = topAlpha;
            this.bottomAlpha = bottomAlpha;
            this.sideAlpha = sideAlpha;
            this.edgeWidthDp = edgeWidthDp;
            this.glowDepthDp = glowDepthDp;
            this.glowAlpha = glowAlpha;
            this.cornerWidthScale = cornerWidthScale;
            this.cornerAlphaScale = cornerAlphaScale;
            this.cornerTaperPower = cornerTaperPower;
        }

        public boolean matches(Rim other) {
            if (other == null) return false;
            return enabled == other.enabled && color == other.color && strength == other.strength
                    && topAlpha == other.topAlpha && bottomAlpha == other.bottomAlpha
                    && sideAlpha == other.sideAlpha && edgeWidthDp == other.edgeWidthDp
                    && glowDepthDp == other.glowDepthDp && glowAlpha == other.glowAlpha
                    && cornerWidthScale == other.cornerWidthScale
                    && cornerAlphaScale == other.cornerAlphaScale
                    && cornerTaperPower == other.cornerTaperPower;
        }
    }

    public static final class State {
        public final boolean tintEnabled;
        public final boolean radiusEnabled;
        public final Rim rim;
        private final int mTintMode;
        private final int mTintColor;
        private final int mTintAlphaPercent;
        private final int mTintAlpha;
        private final float mRadiusDp;

        private State(boolean tintEnabled, int mode, int color, int alpha,
                boolean radiusEnabled, float radiusDp, Rim rim) {
            this.tintEnabled = tintEnabled;
            this.radiusEnabled = radiusEnabled;
            mTintMode = mode;
            mTintColor = color;
            mTintAlphaPercent = alpha;
            mTintAlpha = Math.round(alpha * 255f / 100f);
            mRadiusDp = radiusDp;
            this.rim = rim;
        }

        public int getTintMode() {
            return mTintMode;
        }

        public int getTintColor() {
            return mTintColor;
        }

        public int getTintAlphaPercent() {
            return mTintAlphaPercent;
        }

        public float getRadiusDp() {
            return mRadiusDp;
        }

        public int resolveTint(Context context, int originalColor) {
            if (!tintEnabled) return originalColor;
            final int color;
            if (mTintMode == TINT_CUSTOM) {
                color = mTintColor;
            } else {
                color = context.getColor(mTintMode == TINT_SECONDARY
                        ? android.R.color.system_accent2_500 : android.R.color.system_accent1_500);
            }
            return (color & 0x00ffffff) | (mTintAlpha << 24);
        }

        public float resolveRadius(Context context, float originalRadiusPx) {
            return radiusEnabled
                    ? mRadiusDp * context.getResources().getDisplayMetrics().density
                    : originalRadiusPx;
        }

        private boolean matches(State other) {
            return tintEnabled == other.tintEnabled && radiusEnabled == other.radiusEnabled
                    && mTintMode == other.mTintMode && mTintColor == other.mTintColor
                    && mTintAlpha == other.mTintAlpha && mRadiusDp == other.mRadiusDp
                    && rim.matches(other.rim);
        }
    }
}
