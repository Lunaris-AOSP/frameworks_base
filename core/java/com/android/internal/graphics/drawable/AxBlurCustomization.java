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
    public static final int TINT_ACCENT = 0;
    public static final int TINT_SECONDARY = 1;
    public static final int TINT_CUSTOM = 2;

    private static final String[] KEYS = {
            TINT_ENABLED, TINT_MODE, TINT_COLOR, TINT_ALPHA, RADIUS_ENABLED, RADIUS_DP,
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
            if (mState.tintEnabled || mState.radiusEnabled) refresh();
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
                && (!next.radiusEnabled || mDensity == density)) return;
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
                Float.isFinite(radius) ? Math.max(0f, Math.min(60f, radius)) : 34f);
    }

    public static final class State {
        public final boolean tintEnabled;
        public final boolean radiusEnabled;
        private final int mTintMode;
        private final int mTintColor;
        private final int mTintAlpha;
        private final float mRadiusDp;

        private State(boolean tintEnabled, int mode, int color, int alpha,
                boolean radiusEnabled, float radiusDp) {
            this.tintEnabled = tintEnabled;
            this.radiusEnabled = radiusEnabled;
            mTintMode = mode;
            mTintColor = color;
            mTintAlpha = Math.round(alpha * 255f / 100f);
            mRadiusDp = radiusDp;
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
                    && mTintAlpha == other.mTintAlpha && mRadiusDp == other.mRadiusDp;
        }
    }
}
