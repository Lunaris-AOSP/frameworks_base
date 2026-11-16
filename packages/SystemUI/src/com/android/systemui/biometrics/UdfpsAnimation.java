/**
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static android.content.Intent.ACTION_USER_SWITCHED;

import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;
import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInProgressOffset;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.DisplayUtils;
import android.util.Log;
import android.util.MathUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.graphics.Rect;

import com.android.systemui.Dependency;
import com.android.systemui.res.R;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.concurrent.Executor;

public class UdfpsAnimation extends ImageView {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "UdfpsAnimations";

    private boolean mShowing = false;
    private Context mContext;
    private int mAnimationSize;
    private int mAnimationOffset;
    private AnimationDrawable recognizingAnim;

    private final WindowManager.LayoutParams mAnimParams = new WindowManager.LayoutParams();
    private WindowManager mWindowManager;

    private boolean mIsKeyguard;

    private final int mMaxBurnInOffsetX;
    private final int mMaxBurnInOffsetY;

    private static final String UDFPS_ANIMATIONS_PACKAGE = "com.lunaris.udfps.animations";

    private final KeyguardStateController mKeyguardStateController;
    private final AuthController mAuthController;
    private FingerprintSensorPropertiesInternal mProps;
    private final Executor mBackgroundExecutor;
    private final Executor mMainExecutor;
    private final ContentObserver mSettingsObserver;
    private final Configuration mAnimationConfiguration;

    private boolean mAnimationAdded = false;
    private boolean mAnimationRequested;
    private boolean mAnimationLoading;
    private volatile boolean mDestroyed;
    private int mAnimationStyle = -1;
    private volatile int mAnimationLoadGeneration;

    // Default values from dimens
    private int mDefaultAnimationSize;
    private int mDefaultAnimationOffset;

    private final KeyguardStateController.Callback keyguardStateCallback = new KeyguardStateController.Callback() {
        @Override
        public void onKeyguardFadingAwayChanged() {
            removeAnimation();
        }
        @Override
        public void onKeyguardGoingAwayChanged() {
            removeAnimation();
        }
    };

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                hide();
                updateAnimationStyle();
                updateAnimationDimensions();
            }
        }
    };

    public UdfpsAnimation(Context context, WindowManager windowManager,
           FingerprintSensorPropertiesInternal props, AuthController authController,
           Executor backgroundExecutor, Executor mainExecutor) {
        super(context);
        mContext = context;
        mAuthController = authController;
        mProps = props;
        mBackgroundExecutor = backgroundExecutor;
        mMainExecutor = mainExecutor;
        mAnimationConfiguration = new Configuration(context.getResources().getConfiguration());

        mWindowManager = windowManager;

        mKeyguardStateController = Dependency.get(KeyguardStateController.class);

        float scaleFactor = getDisplayFactor();

        mMaxBurnInOffsetX = (int) (context.getResources()
            .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_x) * scaleFactor);
        mMaxBurnInOffsetY = (int) (context.getResources()
            .getDimensionPixelSize(R.dimen.udfps_burn_in_offset_y) * scaleFactor);

        mDefaultAnimationSize = mContext.getResources().getDimensionPixelSize(R.dimen.udfps_animation_size);
        mDefaultAnimationOffset = mContext.getResources().getDimensionPixelSize(R.dimen.udfps_animation_offset);

        updateAnimationDimensions();

        mAnimParams.format = PixelFormat.TRANSLUCENT;
        mAnimParams.type = WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY; // it must be behind Udfps icon
        mAnimParams.flags =  WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        mAnimParams.gravity = Gravity.TOP | Gravity.CENTER;

        setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        mSettingsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                onSettingsChanged();
            }
        };
        for (String setting : new String[] {Settings.System.UDFPS_ANIM_STYLE,
                Settings.System.UDFPS_ANIM_SIZE, Settings.System.UDFPS_ANIM_OFFSET}) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(setting), false, mSettingsObserver,
                    UserHandle.USER_ALL);
        }
        mContext.registerReceiverAsUser(mIntentReceiver, UserHandle.ALL,
                new IntentFilter(ACTION_USER_SWITCHED), null, null);
        updateAnimationStyle();
    }

    private void updateAnimationDimensions() {
        int sizeScale = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.UDFPS_ANIM_SIZE, 50, UserHandle.USER_CURRENT);
        
        int offsetScale = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.UDFPS_ANIM_OFFSET, 0, UserHandle.USER_CURRENT);

        mAnimationSize = (int) (mDefaultAnimationSize * (0.5f + (sizeScale / 100.0f)));
        
        float density = mContext.getResources().getDisplayMetrics().density;
        mAnimationOffset = mDefaultAnimationOffset + (int) (offsetScale * density);

        mAnimParams.height = mAnimationSize;
        mAnimParams.width = mAnimationSize;
        updatePosition();

        if (DEBUG) {
            Log.d(LOG_TAG, "updateAnimationDimensions: sizeScale=" + sizeScale + 
                           ", offsetScale=" + offsetScale +
                           ", mAnimationSize=" + mAnimationSize + 
                           ", mAnimationOffset=" + mAnimationOffset);
        }

        if (mShowing) {
            try {
                mWindowManager.updateViewLayout(this, mAnimParams);
            } catch (RuntimeException e) {
                Log.e(LOG_TAG, "Error updating view layout", e);
            }
        }
    }

    private void addAnimation() {
        if (!mAnimationAdded) {
            mKeyguardStateController.addCallback(keyguardStateCallback);
            mAnimationAdded = true;
        }
    }

    private void updateAnimationStyle() {
        if (mDestroyed) return;

        final int value = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.UDFPS_ANIM_STYLE, 0, UserHandle.USER_CURRENT);
        if (value == mAnimationStyle) return;

        mAnimationStyle = value;
        final int generation = ++mAnimationLoadGeneration;
        final boolean animationRequested = mAnimationRequested;
        hide();
        mAnimationRequested = animationRequested;
        setBackground(null);
        recognizingAnim = null;
        mAnimationLoading = value > 0;
        if (!mAnimationLoading) return;

        mBackgroundExecutor.execute(() -> {
            if (mDestroyed || generation != mAnimationLoadGeneration) return;

            final AnimationDrawable animation = getBgDrawable(value, generation);
            if (mDestroyed || generation != mAnimationLoadGeneration) return;

            mMainExecutor.execute(() -> {
                if (mDestroyed || generation != mAnimationLoadGeneration) return;

                mAnimationLoading = false;
                setBackground(animation);
                recognizingAnim = animation;
                if (mAnimationRequested) show();
            });
        });
    }

    private AnimationDrawable getBgDrawable(int styleIdx, int generation) {
        if (styleIdx <= 0) return null;

        try {
            final Resources resources = mContext.getPackageManager()
                    .getResourcesForApplication(UDFPS_ANIMATIONS_PACKAGE);
            final int stylesId = resources.getIdentifier("udfps_animation_styles",
                    "array", UDFPS_ANIMATIONS_PACKAGE);
            if (stylesId == 0) return null;
            final String[] styles = resources.getStringArray(stylesId);
            if (styleIdx >= styles.length) return null;

            final int resId = resources.getIdentifier(styles[styleIdx], "drawable",
                    UDFPS_ANIMATIONS_PACKAGE);
            if (resId == 0) return null;
            final Drawable drawable = resources.getDrawable(resId);
            if (!(drawable instanceof AnimationDrawable animation)) return null;

            animation.mutate();
            for (int i = 0; i < animation.getNumberOfFrames(); i++) {
                if (mDestroyed || generation != mAnimationLoadGeneration) return null;
                animation.getFrame(i);
            }
            animation.getPadding(new Rect());
            animation.isStateful();
            animation.getMinimumWidth();
            animation.getMinimumHeight();
            animation.getOpacity();
            return animation;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            Log.w(LOG_TAG, "Failed to load animation style " + styleIdx, e);
            return null;
        }
    }

    public boolean isAnimationEnabled() {
        return mAnimationLoading || recognizingAnim != null;
    }

    private float getDisplayFactor() {
        return DisplayUtils.getScaleFactor(mContext);
    }

    public void updatePosition() {
        Point displaySize = new Point();
        mWindowManager.getDefaultDisplay().getRealSize(displaySize);
        boolean isFullResolution = displaySize.y > 3000; 
        Point udfpsLocation = mAuthController.getUdfpsLocation();
        float scaleFactor = getDisplayFactor();
        float udfpsRadius = isFullResolution ? mAuthController.getUdfpsRadius() : mProps.getLocation().sensorRadius;
        float udfpsLocationY = isFullResolution && udfpsLocation != null ? udfpsLocation.y : mProps.getLocation().sensorLocationY;
        
        mAnimParams.y = (int) (udfpsLocationY * scaleFactor) - (int) (udfpsRadius * scaleFactor)
                        - (mAnimationSize / 2) + mAnimationOffset;
        if (DEBUG) {
            Log.d(LOG_TAG, "updatePosition: displaySize=" + displaySize + 
                           ", isFullResolution=" + isFullResolution + 
                           ", udfpsLocation=" + udfpsLocation + 
                           ", udfpsRadius=" + udfpsRadius + 
                           ", scaleFactor=" + scaleFactor + 
                           ", udfpsLocationY=" + udfpsLocationY + 
                           ", mAnimationOffset=" + mAnimationOffset + 
                           ", mAnimParams.y=" + mAnimParams.y);
        }
    }

    public void show() {
        if (mDestroyed || !mIsKeyguard) return;

        mAnimationRequested = true;
        if (recognizingAnim == null) return;

        if (mShowing && !isAttachedToWindow()) {
            mShowing = false;
        }

        if (mShowing && isAttachedToWindow()) {
            return;
        }

        try {
            if (!isAttachedToWindow()) {
                mWindowManager.addView(this, mAnimParams);
            } else {
                mWindowManager.updateViewLayout(this, mAnimParams);
            }

            mShowing = true;
            recognizingAnim.stop();
            recognizingAnim.selectDrawable(0);
            recognizingAnim.start();
        } catch (RuntimeException e) {
            mShowing = false;
            Log.e(LOG_TAG, "Error adding animation window", e);
        }
    }

    public void hide() {
        mAnimationRequested = false;
        try {
            if (recognizingAnim != null) {
                recognizingAnim.stop();
                recognizingAnim.selectDrawable(0);
                clearAnimation();
            }

            if (isAttachedToWindow()) {
                mWindowManager.removeViewImmediate(this);
            }
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, "Error removing animation window", e);
        }

        mShowing = false;
    }

    public void removeAnimation() {
        hide();
        if (mAnimationAdded) {
            mKeyguardStateController.removeCallback(keyguardStateCallback);
            mAnimationAdded = false;
        }
    }

    public void setIsKeyguard(boolean isKeyguard) {
        if (mDestroyed) return;
        mIsKeyguard = isKeyguard;
        if (mIsKeyguard) {
            addAnimation();
        } else {
            removeAnimation();
        }
    }

    public void onSettingsChanged() {
        if (mDestroyed) return;
        updateAnimationStyle();
        updateAnimationDimensions();
    }

    public void onConfigurationChanged(FingerprintSensorPropertiesInternal props) {
        if (mDestroyed) return;
        mProps = props;
        final Resources resources = mContext.getResources();
        final int changes = mAnimationConfiguration.updateFrom(resources.getConfiguration());
        mDefaultAnimationSize = resources.getDimensionPixelSize(R.dimen.udfps_animation_size);
        mDefaultAnimationOffset = resources.getDimensionPixelSize(R.dimen.udfps_animation_offset);
        updateAnimationDimensions();
        if ((mAnimationLoading && changes != 0)
                || (changes & (ActivityInfo.CONFIG_DENSITY | ActivityInfo.CONFIG_UI_MODE
                | ActivityInfo.CONFIG_ASSETS_PATHS)) != 0
                || (recognizingAnim != null
                && (changes & recognizingAnim.getChangingConfigurations()) != 0)) {
            mAnimationStyle = -1;
            updateAnimationStyle();
        }
    }

    public void destroy() {
        if (mDestroyed) return;
        mDestroyed = true;
        ++mAnimationLoadGeneration;
        mAnimationLoading = false;
        removeAnimation();
        mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
        mContext.unregisterReceiver(mIntentReceiver);
        setBackground(null);
        recognizingAnim = null;
    }
}
