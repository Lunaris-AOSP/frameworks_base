/*
 * Copyright (C) 2026 MistOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.util;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.MathUtils;
import android.view.IWallpaperZoomListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.FrameLayout;

import com.android.systemui.Dependency;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.media.MediaViewController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ScrimState;
import com.android.systemui.tuner.TunerService;

import java.io.File;

public class WallpaperDepthUtils {

    private static final String WALLPAPER_DEPTH_KEY = "system:depth_wallpaper_subject_image_uri";
    private static final String WALLPAPER_DEPTH_ENABLED_KEY = "system:depth_wallpaper_enabled";
    private static final String WALLPAPER_DEPTH_OPACITY_KEY = "system:depth_wallpaper_opacity";
    private static final String WALLPAPER_DEPTH_OFFSET_X_KEY = "system:depth_wallpaper_offset_x";
    private static final String WALLPAPER_DEPTH_OFFSET_Y_KEY = "system:depth_wallpaper_offset_y";
    private static final String WALLPAPER_DEPTH_BOTTOM_FADE_KEY = "system:depth_wallpaper_bottom_fade";
    private static final String WALLPAPER_DEPTH_FADE_CURVE_KEY = "system:depth_wallpaper_fade_curve";
    private static final String WALLPAPER_DEPTH_BOTTOM_INSET_KEY = "system:depth_wallpaper_bottom_inset";
    private static final String WALLPAPER_DEPTH_SPATIAL_KEY = "system:depth_wallpaper_spatial_effect";

    private static final int DEFAULT_FADE_CURVE_PERCENT = 50;
    private static final int DEFAULT_BOTTOM_FADE_DP = 100;
    private static final int DEFAULT_BOTTOM_INSET_DP = 180;

    private static WallpaperDepthUtils instance;
    private FrameLayout mLockScreenBackground;
    private FrameLayout mLockScreenSubject;

    private final Context mContext;
    private final ScrimController mScrimController;
    private final TunerService mTunerService;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final UserTracker mUserTracker;
    private final SpatialEffectController mSpatialEffectController;
    private boolean mTunableRegistered;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private IWallpaperZoomListener mWallpaperZoomListener;
    private int mWallpaperZoomDisplayId;
    private boolean mHasWallpaperZoom;
    private float mWallpaperZoomScale = 1f;
    private float mWallpaperZoomPivotX;
    private float mWallpaperZoomPivotY;

    private boolean mDWallpaperEnabled;
    private boolean mSpatialEffectEnabled;
    private int mDWallOpacity = 255;
    private String mWallpaperSubjectPath;
    private int mSubjectWallpaperId;
    private int mSubjectUserId;
    private boolean mDozing;
    private boolean mBouncerShowing;
    private boolean mGlanceableHubShowing;
    private boolean mDynamicBarExpanded;
    private boolean mWallpaperLoaded = false;
    private LoadWallpaperTask mLoadWallpaperTask;
    private int mOffsetX;
    private int mOffsetY;
    private boolean mUnlocking;

    private int mBottomFadeDp = DEFAULT_BOTTOM_FADE_DP;
    private int mFadeCurvePercent = DEFAULT_FADE_CURVE_PERCENT;
    private int mBottomInsetDp = DEFAULT_BOTTOM_INSET_DP;

    private WallpaperDepthUtils(Context context, ScrimController scrimController) {
        mContext = context.getApplicationContext();
        mScrimController = scrimController;
        mTunerService = Dependency.get(TunerService.class);
        mBroadcastDispatcher = Dependency.get(BroadcastDispatcher.class);
        mUserTracker = Dependency.get(UserTracker.class);
        mSpatialEffectController = new SpatialEffectController(mContext);

        mLockScreenBackground = new FrameLayout(mContext) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                applyWallpaperZoom();
            }
        };
        FrameLayout.LayoutParams bgLp = new FrameLayout.LayoutParams(-1, -1);
        mLockScreenBackground.setLayoutParams(bgLp);
        mLockScreenBackground.setClickable(false);
        mLockScreenBackground.setFocusable(false);
        mLockScreenBackground.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mLockScreenBackground.setVisibility(View.GONE);

        mLockScreenSubject = new FrameLayout(mContext) {
            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                mSpatialEffectController.attachBackgroundView(mLockScreenBackground);
                mSpatialEffectController.attachSubjectView(mLockScreenSubject);
                registerTunable();
                updateWallpaperZoomListener();
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                applyWallpaperZoom();
                ViewGroup parent = (ViewGroup) getParent();
                if (parent != null && mLockScreenBackground.getParent() == null) {
                    parent.addView(mLockScreenBackground, 0);
                }
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                if (mLockScreenBackground.getParent() != null) {
                    ((ViewGroup) mLockScreenBackground.getParent())
                            .removeView(mLockScreenBackground);
                }
                WallpaperDepthUtils.this.onDetachedFromWindow();
            }
        };

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        mLockScreenSubject.setLayoutParams(lp);
        mLockScreenSubject.setClickable(false);
        mLockScreenSubject.setFocusable(false);
        mLockScreenSubject.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mLockScreenSubject.setVisibility(View.GONE);
        registerTunable();
    }

    private void registerTunable() {
        if (mTunableRegistered) return;
        mTunableRegistered = true;
        mBroadcastDispatcher.registerReceiver(mWallpaperChangedReceiver,
                new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
                new HandlerExecutor(mMainHandler), UserHandle.ALL);
        clearDepthWallpaperIfChanged();
        mTunerService.addTunable(mTunable, WALLPAPER_DEPTH_KEY,
            WALLPAPER_DEPTH_ENABLED_KEY, WALLPAPER_DEPTH_OPACITY_KEY,
            WALLPAPER_DEPTH_OFFSET_X_KEY, WALLPAPER_DEPTH_OFFSET_Y_KEY,
            WALLPAPER_DEPTH_BOTTOM_FADE_KEY, WALLPAPER_DEPTH_FADE_CURVE_KEY,
            WALLPAPER_DEPTH_BOTTOM_INSET_KEY, WALLPAPER_DEPTH_SPATIAL_KEY);
    }

    private int getLockWallpaperId(int userId) {
        WallpaperManager wm = WallpaperManager.getInstance(mContext);
        int lockId = wm.getWallpaperIdForUser(WallpaperManager.FLAG_LOCK, userId);
        return lockId > 0 ? lockId
                : wm.getWallpaperIdForUser(WallpaperManager.FLAG_SYSTEM, userId);
    }

    private final BroadcastReceiver mWallpaperChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mTunableRegistered && Intent.ACTION_WALLPAPER_CHANGED.equals(intent.getAction())) {
                clearDepthWallpaperIfChanged();
            }
        }
    };

    private void clearDepthWallpaperIfChanged() {
        if (mWallpaperSubjectPath == null) {
            return;
        }
        if (mSubjectUserId != mUserTracker.getUserId()) {
            mWallpaperSubjectPath = null;
            clearDepthWallpaper();
            updateWallpaperZoomListener();
            return;
        }
        if (mSubjectWallpaperId == getLockWallpaperId(mSubjectUserId)) {
            return;
        }
        String currentSubjectPath = mTunerService.getValue(WALLPAPER_DEPTH_KEY);
        if (currentSubjectPath != null && !currentSubjectPath.equals(mWallpaperSubjectPath)) {
            mTunable.onTuningChanged(WALLPAPER_DEPTH_KEY, currentSubjectPath);
            return;
        }
        mWallpaperSubjectPath = null;
        clearDepthWallpaper();
        updateWallpaperZoomListener();
        mTunerService.setValue(WALLPAPER_DEPTH_KEY, (String) null);
    }

    public static WallpaperDepthUtils getInstance(
            Context context, ScrimController scrimController) {
        if (instance == null) {
            instance = new WallpaperDepthUtils(context, scrimController);
        }
        return instance;
    }

    public static WallpaperDepthUtils get() {
        return instance;
    }

    public void onUnlockStarted() {
        mUnlocking = true;
        hideDepthWallpaperImmediate();
        updateSpatialEffectActive();
    }

    public void onUnlockCancelled() {
        mUnlocking = false;
        updateDepthWallpaperVisibility();
        updateSpatialEffectActive();
    }

    public void onUnlockCompleted() {
        mUnlocking = false;
        updateSpatialEffectActive();
    }

    public void onDozingChanged(boolean dozing) {
        if (mDozing == dozing) {
            return;
        }
        mDozing = dozing;
        if (mDozing) {
            hideDepthWallpaper();
        } else {
            updateDepthWallpaperVisibility();
        }
        updateSpatialEffectActive();
    }

    public void onBouncerShowingChanged(boolean showing) {
        if (mBouncerShowing == showing) {
            return;
        }
        mBouncerShowing = showing;
        if (mBouncerShowing) {
            hideDepthWallpaper();
        } else {
            updateDepthWallpaperVisibility();
        }
        updateSpatialEffectActive();
    }

    public void setDynamicBarExpanded(boolean expanded) {
        if (mDynamicBarExpanded == expanded) {
            return;
        }
        mDynamicBarExpanded = expanded;
        if (expanded) {
            hideDepthWallpaper();
        } else {
            updateDepthWallpaperVisibility();
        }
        updateSpatialEffectActive();
    }

    public void onGlanceableHubShowingChanged(boolean showing) {
        if (mGlanceableHubShowing == showing) {
            return;
        }
        mGlanceableHubShowing = showing;
        if (mGlanceableHubShowing) {
            hideDepthWallpaper();
        } else {
            updateDepthWallpaperVisibility();
        }
        updateSpatialEffectActive();
    }

    private final TunerService.Tunable mTunable = new TunerService.Tunable() {
        @Override
        public void onTuningChanged(String key, String newValue) {
            switch (key) {
                case WALLPAPER_DEPTH_ENABLED_KEY:
                    mDWallpaperEnabled = TunerService.parseIntegerSwitch(newValue, false);
                    updateWallpaperZoomListener();
                    updateDepthWallpaper(true);
                    updateSpatialEffectActive();
                    break;

                case WALLPAPER_DEPTH_KEY:
                    mWallpaperSubjectPath = newValue;
                    mSubjectUserId = mUserTracker.getUserId();
                    mSubjectWallpaperId = getLockWallpaperId(mSubjectUserId);
                    updateWallpaperZoomListener();
                    updateDepthWallpaper(true);
                    break;

                case WALLPAPER_DEPTH_OPACITY_KEY:
                    int opacity = TunerService.parseInteger(newValue, 100);
                    mDWallOpacity = Math.round(opacity * 2.55f);
                    if (mLockScreenSubject.getBackground() != null) {
                        mLockScreenSubject.getBackground().setAlpha(mDWallOpacity);
                    }
                    break;

                case WALLPAPER_DEPTH_OFFSET_X_KEY:
                    mOffsetX = TunerService.parseInteger(newValue, 0);
                    updateDepthWallpaper(true);
                    break;

                case WALLPAPER_DEPTH_OFFSET_Y_KEY:
                    mOffsetY = TunerService.parseInteger(newValue, 0);
                    updateDepthWallpaper(true);
                    break;

                case WALLPAPER_DEPTH_BOTTOM_FADE_KEY:
                    mBottomFadeDp = TunerService.parseInteger(
                            newValue, DEFAULT_BOTTOM_FADE_DP);
                    applyBottomFade();
                    break;

                case WALLPAPER_DEPTH_FADE_CURVE_KEY:
                    mFadeCurvePercent = TunerService.parseInteger(
                            newValue, DEFAULT_FADE_CURVE_PERCENT);
                    applyFadeCurve();
                    break;

                case WALLPAPER_DEPTH_BOTTOM_INSET_KEY:
                    mBottomInsetDp = TunerService.parseInteger(
                            newValue, DEFAULT_BOTTOM_INSET_DP);
                    applyBottomInset();
                    break;

                case WALLPAPER_DEPTH_SPATIAL_KEY:
                    mSpatialEffectEnabled =
                            TunerService.parseIntegerSwitch(newValue, false);
                    updateSpatialEffectActive();
                    break;

                default:
                    break;
            }
        }
    };

    public void setSubjectAlpha(float subjectAlpha) {
        if (mLockScreenSubject == null) {
            return;
        }
        mLockScreenSubject.post(() -> mLockScreenSubject.setAlpha(subjectAlpha));
    }

    private void applyBottomFade() {
        if (mLockScreenSubject == null) {
            return;
        }
        Drawable background = mLockScreenSubject.getBackground();
        if (background instanceof FadeBottomDrawable) {
            ((FadeBottomDrawable) background)
                    .setFadeHeightPx(dpToPx(mBottomFadeDp));
        }
    }

    private void applyBottomInset() {
        if (mLockScreenSubject == null) {
            return;
        }
        Drawable background = mLockScreenSubject.getBackground();
        if (background instanceof FadeBottomDrawable) {
            ((FadeBottomDrawable) background)
                    .setBottomInsetPx(dpToPx(mBottomInsetDp));
        }
    }

    private void applyFadeCurve() {
        if (mLockScreenSubject == null) {
            return;
        }
        Drawable background = mLockScreenSubject.getBackground();
        if (background instanceof FadeBottomDrawable) {
            ((FadeBottomDrawable) background)
                    .setFadeCurveExponent(computeFadeCurveExponent());
        }
    }

    private float computeFadeCurveExponent() {
        float percent = MathUtils.constrain(mFadeCurvePercent, 0, 100) / 100f;
        return 0.2f + percent * 0.8f;
    }

    private int dpToPx(int dp) {
        DisplayMetrics displayMetrics =
                mContext.getResources().getDisplayMetrics();
        return Math.round(dp * displayMetrics.density);
    }

    public void updateDepthWallpaper() {
        updateDepthWallpaper(false);
    }

    public FrameLayout getDepthWallpaperView() {
        return mLockScreenSubject;
    }

    private void updateWallpaperZoomListener() {
        if (!isDWallpaperEnabled() || !mLockScreenSubject.isAttachedToWindow()) {
            unregisterWallpaperZoomListener();
            updateDepthWallpaperVisibility();
            return;
        }
        if (mWallpaperZoomListener != null) return;
        mWallpaperZoomDisplayId = mLockScreenSubject.getDisplay().getDisplayId();
        mWallpaperZoomListener = new IWallpaperZoomListener.Stub() {
            @Override
            public void onWallpaperZoomChanged(float scale, float pivotX, float pivotY) {
                mMainHandler.post(() -> {
                    if (mWallpaperZoomListener != this) return;
                    boolean firstUpdate = !mHasWallpaperZoom;
                    mWallpaperZoomScale = scale;
                    mWallpaperZoomPivotX = pivotX;
                    mWallpaperZoomPivotY = pivotY;
                    mHasWallpaperZoom = true;
                    applyWallpaperZoom();
                    if (firstUpdate) {
                        updateDepthWallpaperVisibility();
                    }
                });
            }
        };
        try {
            WindowManagerGlobal.getWindowManagerService().registerWallpaperZoomListener(
                    mWallpaperZoomListener, mWallpaperZoomDisplayId);
        } catch (RemoteException | IllegalArgumentException e) {
            Log.w("WallpaperDepthUtils", "Unable to listen for wallpaper zoom", e);
            mWallpaperZoomListener = null;
        }
    }

    private void unregisterWallpaperZoomListener() {
        if (mWallpaperZoomListener == null) return;
        IWallpaperZoomListener listener = mWallpaperZoomListener;
        mWallpaperZoomListener = null;
        mHasWallpaperZoom = false;
        try {
            WindowManagerGlobal.getWindowManagerService().unregisterWallpaperZoomListener(
                    listener, mWallpaperZoomDisplayId);
        } catch (RemoteException e) {
            Log.w("WallpaperDepthUtils", "Unable to remove wallpaper zoom listener", e);
        }
    }

    private void applyWallpaperZoom() {
        if (!mHasWallpaperZoom) return;
        mSpatialEffectController.setWallpaperZoom(
                mWallpaperZoomScale, mWallpaperZoomPivotX, mWallpaperZoomPivotY);
    }

    public FrameLayout getBackgroundView() {
        return mLockScreenBackground;
    }

    private boolean isDWallpaperEnabled() {
        return mDWallpaperEnabled
                && mWallpaperSubjectPath != null
                && !mWallpaperSubjectPath.isEmpty();
    }

    private boolean canShowDepthWallpaper() {
        ScrimState currentState = mScrimController.getState();
        MediaViewController mediaViewController =
                MediaViewController.getOrNull();
        boolean albumArtVisible = mediaViewController != null
                && mediaViewController.albumArtVisible();
        return mLockScreenSubject != null
                && isDWallpaperEnabled()
                && mWallpaperLoaded
                && mHasWallpaperZoom
                && !mDozing
                && !mBouncerShowing
                && !mGlanceableHubShowing
                && !mDynamicBarExpanded
                && !mUnlocking
                && currentState == ScrimState.KEYGUARD
                && mContext.getResources().getConfiguration().orientation
                        != Configuration.ORIENTATION_LANDSCAPE
                && !albumArtVisible;
    }

    public void updateDepthWallpaperVisibility() {
        if (mLockScreenSubject == null) {
            return;
        }

        int subjectVisibility =
                canShowDepthWallpaper() ? View.VISIBLE : View.GONE;

        if (mLockScreenSubject.getVisibility() == subjectVisibility
                && (mLockScreenBackground == null
                || mLockScreenBackground.getVisibility() == subjectVisibility)) {
            return;
        }

        mLockScreenSubject.post(() -> {
            int targetVisibility = canShowDepthWallpaper() ? View.VISIBLE : View.GONE;
            if (mLockScreenSubject.getVisibility() == targetVisibility
                    && (mLockScreenBackground == null
                    || mLockScreenBackground.getVisibility() == targetVisibility)) return;
            mLockScreenSubject.setVisibility(targetVisibility);

            if (mLockScreenBackground != null) {
                mLockScreenBackground.setVisibility(targetVisibility);
            }

            if (targetVisibility == View.VISIBLE) {
                mLockScreenSubject.invalidate();

                if (mLockScreenBackground != null) {
                    mLockScreenBackground.invalidate();
                }
            }
        });

        updateSpatialEffectActive();
    }

    public void hideDepthWallpaper() {
        if (mLockScreenSubject == null
                || mLockScreenSubject.getVisibility() == View.GONE) {
            return;
        }

        mLockScreenSubject.post(() -> {
            mLockScreenSubject.setVisibility(View.GONE);

            if (mLockScreenBackground != null) {
                mLockScreenBackground.setVisibility(View.GONE);
            }
        });

        updateSpatialEffectActive();
    }

    public void hideDepthWallpaperImmediate() {
        if (mLockScreenSubject == null) {
            return;
        }

        mLockScreenSubject.post(() -> {
            mLockScreenSubject.animate().cancel();

            if (mLockScreenBackground != null) {
                mLockScreenBackground.animate().cancel();
            }

            mLockScreenSubject.animate()
                    .alpha(0f)
                    .setDuration(120)
                    .withEndAction(() -> {
                        mLockScreenSubject.setVisibility(View.GONE);
                        mLockScreenSubject.setAlpha(1f);

                        if (mLockScreenBackground != null) {
                            mLockScreenBackground.setVisibility(View.GONE);
                            mLockScreenBackground.setAlpha(1f);
                        }
                    })
                    .start();

            if (mLockScreenBackground != null) {
                mLockScreenBackground.animate()
                        .alpha(0f)
                        .setDuration(120)
                        .start();
            }
        });

        updateSpatialEffectActive();
    }

    private void updateSpatialEffectActive() {
        if (mSpatialEffectController == null) {
            return;
        }

        boolean active = mSpatialEffectEnabled && canShowDepthWallpaper();
        mSpatialEffectController.setActive(active);
    }

    private Rect getDepthWallpaperBounds() {
        Rect bounds = mContext
                .getSystemService(WindowManager.class)
                .getCurrentWindowMetrics()
                .getBounds();
        return new Rect(0, 0,
                Math.min(bounds.width(), bounds.height()),
                Math.max(bounds.width(), bounds.height()));
    }

    public Bitmap getResizedBitmap(
            Bitmap wallpaperBitmap, float xOffsetDp, float yOffsetDp) {
        return getResizedBitmap(wallpaperBitmap, xOffsetDp, yOffsetDp,
                getDepthWallpaperBounds(), mContext.getResources().getDisplayMetrics().density);
    }

    private Bitmap getResizedBitmap(Bitmap wallpaperBitmap, float xOffsetDp, float yOffsetDp,
            Rect displayBounds, float density) {
        float xOffsetPx = xOffsetDp * density;
        float yOffsetPx = yOffsetDp * density;

        float ratioW = displayBounds.width()
                / (float) wallpaperBitmap.getWidth();
        float ratioH = displayBounds.height()
                / (float) wallpaperBitmap.getHeight();

        int desiredHeight = Math.round(
                Math.max(ratioH, ratioW) * wallpaperBitmap.getHeight());
        int desiredWidth = Math.round(
                Math.max(ratioH, ratioW) * wallpaperBitmap.getWidth());

        desiredHeight = Math.max(desiredHeight, 0);
        desiredWidth = Math.max(desiredWidth, 0);

        Bitmap scaledWallpaperBitmap = Bitmap.createScaledBitmap(
                wallpaperBitmap, desiredWidth, desiredHeight, true);

        int xPixelShift = Math.max(
                (desiredWidth - displayBounds.width()) / 2, 0)
                - Math.round(xOffsetPx);

        int yPixelShift = Math.max(
                (desiredHeight - displayBounds.height()) / 2, 0)
                - Math.round(yOffsetPx);

        int cropWidth = Math.min(
                displayBounds.width(),
                scaledWallpaperBitmap.getWidth() - xPixelShift);

        int cropHeight = Math.min(
                displayBounds.height(),
                scaledWallpaperBitmap.getHeight() - yPixelShift);

        scaledWallpaperBitmap = Bitmap.createBitmap(
                scaledWallpaperBitmap,
                Math.max(xPixelShift, 0),
                Math.max(yPixelShift, 0),
                cropWidth,
                cropHeight);

        return scaledWallpaperBitmap;
    }

    public void updateDepthWallpaper(boolean forced) {
        if (!mTunableRegistered || !isDWallpaperEnabled()) {
            clearDepthWallpaper();
            return;
        }

        if (forced || (!mWallpaperLoaded && mLoadWallpaperTask == null)) {
            clearDepthWallpaper();
            mLoadWallpaperTask = new LoadWallpaperTask(mWallpaperSubjectPath, mOffsetX, mOffsetY,
                    mSubjectUserId);
            mLoadWallpaperTask.execute();
        }

        updateDepthWallpaperVisibility();
    }

    private void clearDepthWallpaper() {
        if (mLoadWallpaperTask != null) {
            mLoadWallpaperTask.cancel(false);
            mLoadWallpaperTask = null;
        }
        mWallpaperLoaded = false;
        mLockScreenSubject.animate().cancel();
        mLockScreenSubject.setVisibility(View.GONE);
        mLockScreenSubject.setAlpha(1f);
        mLockScreenSubject.setBackground(null);
        mLockScreenBackground.animate().cancel();
        mLockScreenBackground.setVisibility(View.GONE);
        mLockScreenBackground.setAlpha(1f);
        mLockScreenBackground.setBackground(null);
        updateSpatialEffectActive();
    }

    private Bitmap loadSystemWallpaperBitmap(int userId) {
        Context userContext = mContext.createContextAsUser(UserHandle.of(userId), 0);
        WallpaperManager wm = WallpaperManager.getInstance(userContext);

        if (wm == null) {
            return null;
        }

        try (ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)) {
            if (pfd != null) {
                Bitmap bmp = BitmapFactory.decodeFileDescriptor(
                        pfd.getFileDescriptor());

                if (bmp != null) {
                    return bmp;
                }
            }
        } catch (Exception ignored) {
        }

        try (ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)) {
            if (pfd != null) {
                Bitmap bmp = BitmapFactory.decodeFileDescriptor(
                        pfd.getFileDescriptor());

                if (bmp != null) {
                    return bmp;
                }
            }
        } catch (Exception ignored) {
        }

        try {
            Drawable drawable = wm.getDrawable();

            if (drawable instanceof BitmapDrawable
                    && ((BitmapDrawable) drawable).getBitmap() != null) {
                return ((BitmapDrawable) drawable)
                        .getBitmap()
                        .copy(Bitmap.Config.ARGB_8888, false);
            }
        } catch (Exception ignored) {
        }

        try {
            File deFile = new File(
                    userContext.createDeviceProtectedStorageContext()
                            .getFilesDir(),
                    "wallpaper.jpg");

            if (deFile.exists()) {
                return BitmapFactory.decodeFile(
                        deFile.getAbsolutePath());
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static final class WallpaperLayers {
        final Drawable background;
        final Drawable subject;

        WallpaperLayers(Drawable bg, Drawable fg) {
            this.background = bg;
            this.subject = fg;
        }
    }

    private class LoadWallpaperTask
            extends AsyncTask<Void, Void, WallpaperLayers> {

        private final String mSubjectPath;
        private final int mSubjectOffsetX;
        private final int mSubjectOffsetY;
        private final int mUserId;
        private final Rect mDisplayBounds;
        private final float mDensity;

        LoadWallpaperTask(String subjectPath, int offsetX, int offsetY, int userId) {
            mSubjectPath = subjectPath;
            mSubjectOffsetX = offsetX;
            mSubjectOffsetY = offsetY;
            mUserId = userId;
            mDisplayBounds = getDepthWallpaperBounds();
            mDensity = mContext.getResources().getDisplayMetrics().density;
        }

        @Override
        protected WallpaperLayers doInBackground(Void... voids) {
            try {
                if (isCancelled()) return null;
                Log.d(
                        "LoadWallpaperTask",
                        "Wallpaper path: " + mSubjectPath);

                Bitmap bitmap =
                        BitmapFactory.decodeFile(mSubjectPath);

                if (bitmap == null) {
                    Log.d(
                            "LoadWallpaperTask",
                            "Failed to decode bitmap from file");
                    return null;
                }

                Bitmap resizedBitmap =
                        getResizedBitmap(bitmap, mSubjectOffsetX, mSubjectOffsetY,
                                mDisplayBounds, mDensity);

                if (resizedBitmap == null) {
                    Log.d(
                            "LoadWallpaperTask",
                            "Failed to decode resized bitmap from file");
                    return null;
                }

                Drawable bitmapDrawable = new FadeBottomDrawable(resizedBitmap, 0);

                if (isCancelled()) return null;
                Drawable backgroundDrawable = null;
                Bitmap bgRaw = loadSystemWallpaperBitmap(mUserId);

                if (bgRaw != null) {
                    Bitmap resizedBg =
                            getResizedBitmap(
                                    bgRaw,
                                    mSubjectOffsetX,
                                    mSubjectOffsetY,
                                    mDisplayBounds,
                                    mDensity);

                    if (resizedBg != null) {
                        backgroundDrawable =
                                new BitmapDrawable(
                                        mContext.getResources(),
                                        resizedBg);

                        backgroundDrawable.setAlpha(255);
                    }
                }

                return new WallpaperLayers(
                        backgroundDrawable,
                        bitmapDrawable);

            } catch (OutOfMemoryError e) {
                Log.e(
                        "LoadWallpaperTask",
                        "Out of memory error",
                        e);
                return null;

            } catch (Exception e) {
                Log.e(
                        "LoadWallpaperTask",
                        "Error loading wallpaper",
                        e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(WallpaperLayers layers) {
            if (mLoadWallpaperTask != this || isCancelled()) {
                return;
            }
            clearDepthWallpaperIfChanged();
            if (mLoadWallpaperTask != this) {
                return;
            }
            mLoadWallpaperTask = null;
            if (!isDWallpaperEnabled() || layers == null || layers.subject == null) {
                Log.d(
                        "LoadWallpaperTask",
                        "decodeFile returned nothing, skipping "
                                + "application of subject as background");
                clearDepthWallpaper();
                return;
            }

            mLockScreenBackground.setBackground(layers.background);
            mLockScreenSubject.setBackground(layers.subject);
            layers.subject.setAlpha(mDWallOpacity);
            applyBottomFade();
            applyFadeCurve();
            applyBottomInset();
            mWallpaperLoaded = true;
            updateDepthWallpaperVisibility();
            updateSpatialEffectActive();
        }
    }

    public void onDetachedFromWindow() {
        unregisterWallpaperZoomListener();
        clearDepthWallpaper();
        mBroadcastDispatcher.unregisterReceiver(mWallpaperChangedReceiver);
        mTunerService.removeTunable(mTunable);
        mTunableRegistered = false;
        mSpatialEffectController.destroy();
    }
}
