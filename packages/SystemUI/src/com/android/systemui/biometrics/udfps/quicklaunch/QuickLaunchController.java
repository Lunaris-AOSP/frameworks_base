/*
 * Copyright (C) 2026 Project ASCP
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

package com.android.systemui.biometrics.udfps.quicklaunch;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.CoreStartable;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.UdfpsController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.List;

import javax.inject.Inject;

import dagger.Lazy;

@SysUISingleton
public class QuickLaunchController implements CoreStartable,
        QuickLaunchContainer.OnQuickLaunchActionListener {

    private static final String TAG = "QuickLaunchController";

    private static final long HOLD_THRESHOLD_MS = 500L;
    private static final long FAST_GLIDE_TIMEOUT_MS = 250L;
    private static final long SAFETY_UNLOCK_TIMEOUT_MS = 3500L;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final AuthController mAuthController;
    private final ActivityStarter mActivityStarter;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardStateController mKeyguardStateController;
    private final LauncherApps mLauncherApps;
    private final InputManager mInputManager;
    private final Handler mMainHandler;
    private final Lazy<UdfpsController> mUdfpsControllerLazy;

    private QuickLaunchContainer mContainer;
    private WindowManager.LayoutParams mLayoutParams;
    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputEventReceiver;

    private boolean mFingerDown = false;
    private boolean mGestureActive = false;
    private boolean mOverlayShowing = false;
    private float mTouchDownX;
    private float mTouchDownY;
    private long mTouchDownTime = 0L;

    private final Runnable mLongPressRunnable = this::onLongPressTriggered;
    private final Runnable mSafetyUnlockRunnable = this::onSafetyTimeout;

    private void onSafetyTimeout() {
        if (mFingerDown) {
            mMainHandler.postDelayed(mSafetyUnlockRunnable, SAFETY_UNLOCK_TIMEOUT_MS);
        } else {
            dismissOverlay();
        }
    }

    private final KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    updateInputMonitoring();
                }
            };

    private final KeyguardUpdateMonitorCallback mUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onKeyguardVisibilityChanged(boolean showing) {
                    if (showing && QuickLaunchHelper.getInstance(mContext).isQuickLaunchEnabled()) {
                        startInputMonitoring();
                    } else if (!showing && !mGestureActive && !mOverlayShowing) {
                        dismissOverlay();
                        stopInputMonitoring();
                    }
                }

                @Override
                public void onStartedGoingToSleep(int why) {
                    dismissOverlay();
                    stopInputMonitoring();
                }

                @Override
                public void onStartedWakingUp() {
                    if (mKeyguardStateController.isShowing()
                            && QuickLaunchHelper.getInstance(mContext).isQuickLaunchEnabled()) {
                        startInputMonitoring();
                    }
                }

                @Override
                public void onUserSwitching(int userId) {
                    dismissOverlay();
                    stopInputMonitoring();
                    QuickLaunchHelper.getInstance(mContext).updateQuickLaunchEnabled();
                }
            };

    private final UdfpsController.Callback mUdfpsCallback = new UdfpsController.Callback() {
        @Override
        public void onFingerDown() {
            mFingerDown = true;
            if (mTouchDownTime <= 0) {
                mTouchDownTime = SystemClock.uptimeMillis();
            }
            Point udfpsLoc = mAuthController.getUdfpsLocation();
            if (udfpsLoc != null) {
                mTouchDownX = udfpsLoc.x;
                mTouchDownY = udfpsLoc.y;
            }
            startInputMonitoring();
        }

        @Override
        public void onFingerUp() {
                        if (!mGestureActive && !mOverlayShowing) {
                mFingerDown = false;
                mTouchDownTime = 0L;
                dismissOverlay();
            }
        }
    };

    @Inject
    public QuickLaunchController(
            Context context,
            WindowManager windowManager,
            AuthController authController,
            ActivityStarter activityStarter,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardStateController keyguardStateController,
            LauncherApps launcherApps,
            InputManager inputManager,
            Lazy<UdfpsController> udfpsControllerLazy,
            @Main Handler mainHandler
    ) {
        mContext = context;
        mWindowManager = windowManager;
        mAuthController = authController;
        mActivityStarter = activityStarter;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mKeyguardStateController = keyguardStateController;
        mLauncherApps = launcherApps;
        mInputManager = inputManager;
        mUdfpsControllerLazy = udfpsControllerLazy;
        mMainHandler = mainHandler;
    }

    @Override
    public void start() {
        Log.d(TAG, "QuickLaunchController started");
        mKeyguardUpdateMonitor.registerCallback(mUpdateMonitorCallback);
        mKeyguardStateController.addCallback(mKeyguardStateCallback);
        try {
            UdfpsController udfps = mUdfpsControllerLazy.get();
            if (udfps != null) {
                udfps.addCallback(mUdfpsCallback);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed registering UdfpsController callback", e);
        }
        initOverlayViews();
        updateInputMonitoring();
    }

    private void updateInputMonitoring() {
        boolean shouldMonitor = mKeyguardStateController.isShowing()
                && !mKeyguardStateController.isOccluded()
                && QuickLaunchHelper.getInstance(mContext).isQuickLaunchEnabled();
        if (shouldMonitor) {
            startInputMonitoring();
        } else if (!mGestureActive && !mOverlayShowing) {
            stopInputMonitoring();
        }
    }

    private void initOverlayViews() {
        mContainer = new QuickLaunchContainer(mContext);
        mContainer.setActionListener(this);

        mLayoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        mLayoutParams.setTitle("QuickLaunchOverlay");
        mLayoutParams.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        mLayoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mLayoutParams.privateFlags =
                WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                        | WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION;
    }

    
    public void onFingerprintAuthenticated() {
        Log.d(TAG, "onFingerprintAuthenticated called: mFingerDown=" + mFingerDown);
        if (!QuickLaunchHelper.getInstance(mContext).isQuickLaunchEnabled()) {
            Log.d(TAG, "Quick Launch disabled in settings, skipping");
            return;
        }

        boolean isUdfpsDown = false;
        try {
            isUdfpsDown = mAuthController.isUdfpsFingerDown();
            if (!isUdfpsDown) {
                UdfpsController udfps = mUdfpsControllerLazy.get();
                if (udfps != null) {
                    isUdfpsDown = udfps.isFingerDown();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed checking udfps finger down state", e);
        }

        boolean isFingerActuallyDown = mFingerDown || isUdfpsDown;
        if (!isFingerActuallyDown) {
            Log.d(TAG, "Finger already lifted when auth completed, skipping Quick Launch");
            mGestureActive = false;
            mTouchDownTime = 0L;
            return;
        }

        mFingerDown = true;
        mGestureActive = true;

        Point udfpsLoc = mAuthController.getUdfpsLocation();
        if (udfpsLoc != null) {
            mTouchDownX = udfpsLoc.x;
            mTouchDownY = udfpsLoc.y;
        }

        startInputMonitoring();

        long now = SystemClock.uptimeMillis();
        long elapsed = (mTouchDownTime > 0) ? (now - mTouchDownTime) : 200L;
        long delay = Math.max(150L, HOLD_THRESHOLD_MS - elapsed);
        Log.d(TAG, "Fingerprint authenticated with finger held (" + elapsed
                + "ms elapsed). Scheduling Quick Launch overlay in " + delay + "ms");

        mMainHandler.removeCallbacks(mSafetyUnlockRunnable);
        mMainHandler.postDelayed(mSafetyUnlockRunnable, SAFETY_UNLOCK_TIMEOUT_MS);

        mMainHandler.removeCallbacks(mLongPressRunnable);
        mMainHandler.postDelayed(mLongPressRunnable, delay);
    }

    private void onLongPressTriggered() {
        Log.d(TAG, "onLongPressTriggered: mFingerDown=" + mFingerDown
                + " mGestureActive=" + mGestureActive);
        if (!mFingerDown || !mGestureActive) {
            Log.d(TAG, "onLongPressTriggered: finger not down or gesture inactive, dismissing");
            dismissOverlay();
            return;
        }

        showOverlay();
    }

    private void showOverlay() {
        if (mOverlayShowing) return;
        Log.d(TAG, "showOverlay: presenting Quick Launch radial menu on homescreen");

        Point sensorCenter = mAuthController.getUdfpsLocation();
        mContainer.setSensorCenter(sensorCenter);

        List<QuickLaunchItem> items = QuickLaunchHelper.getInstance(mContext).getQuickLaunchItems();
        mContainer.setItems(items);

        try {
            mWindowManager.addView(mContainer, mLayoutParams);
            mOverlayShowing = true;
            if (mInputMonitor != null) {
                mInputMonitor.pilferPointers();
            }
            mContainer.startLongPressAnim();
        } catch (Exception e) {
            Log.e(TAG, "Failed adding QuickLaunchContainer to WindowManager", e);
            dismissOverlay();
        }
    }

    private void dismissOverlay() {
        mMainHandler.removeCallbacks(mLongPressRunnable);
        mMainHandler.removeCallbacks(mSafetyUnlockRunnable);
        mGestureActive = false;
        mFingerDown = false;
        mTouchDownTime = 0L;

        if (mOverlayShowing && mContainer != null) {
            try {
                if (mContainer.isAttachedToWindow() || mContainer.getParent() != null) {
                    mWindowManager.removeViewImmediate(mContainer);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed removing QuickLaunchContainer", e);
            }
        }
        mOverlayShowing = false;
        if (mContainer != null) {
            mContainer.reset();
        }
        if (!mKeyguardStateController.isShowing()) {
            stopInputMonitoring();
        }
    }

    private synchronized void startInputMonitoring() {
        if (mInputMonitor != null) {
            return;
        }
        try {
            int displayId = mContext.getDisplayId();
            mInputMonitor = mInputManager.monitorGestureInput("fp-quick-launch", displayId);
            mInputEventReceiver = new InputEventReceiver(mInputMonitor.getInputChannel(),
                    Looper.getMainLooper()) {
                @Override
                public void onInputEvent(InputEvent event) {
                    try {
                        if (event instanceof MotionEvent) {
                            handleMotionEvent((MotionEvent) event);
                        }
                    } finally {
                        finishInputEvent(event, true);
                    }
                }
            };
            Log.d(TAG, "Gesture input monitor registered on display " + displayId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to monitor gesture input", e);
        }
    }

    private synchronized void stopInputMonitoring() {
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
            Log.d(TAG, "Gesture input monitor disposed");
        }
    }

    private void handleMotionEvent(MotionEvent event) {
        int action = event.getActionMasked();
        float x = event.getRawX();
        float y = event.getRawY();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mFingerDown = true;
                mTouchDownTime = SystemClock.uptimeMillis();
                mTouchDownX = x;
                mTouchDownY = y;
                break;

            case MotionEvent.ACTION_MOVE:
                if (mOverlayShowing) {
                    mContainer.onPointerMove(x, y);
                } else if (mGestureActive && mFingerDown) {
                                        double dist = Math.hypot(x - mTouchDownX, y - mTouchDownY);
                    float density = mContext.getResources().getDisplayMetrics().density;
                    if (dist > 25 * density) {
                        mMainHandler.removeCallbacks(mLongPressRunnable);
                        onLongPressTriggered();
                        if (mOverlayShowing) {
                            mContainer.onPointerMove(x, y);
                        }
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
                mFingerDown = false;
                mTouchDownTime = 0L;
                if (mOverlayShowing) {
                    mContainer.onPointerUp(x, y);
                } else {
                    dismissOverlay();
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                mFingerDown = false;
                mTouchDownTime = 0L;
                dismissOverlay();
                break;
        }
    }

    @Override
    public void onItemLaunched(QuickLaunchItem item, int slotIndex) {
        dismissOverlay();

        if (item == null) return;

        if (item.getViewType() == QuickLaunchItem.VIEW_TYPE_SHORTCUT) {
            mActivityStarter.executeRunnableDismissingKeyguard(() -> {
                try {
                    mLauncherApps.startShortcut(item.getPackageName(), item.getShortcutId(),
                            null, null, UserHandle.of(item.getUserId()));
                } catch (Exception e) {
                    Log.e(TAG, "Failed launching shortcut " + item.getShortcutId(), e);
                }
            }, null, true, true, false);
        } else {
            try {
                Intent intent = mContext.getPackageManager()
                        .getLaunchIntentForPackage(item.getPackageName());
                if (intent == null) {
                    intent = new Intent();
                    intent.setComponent(new ComponentName(item.getPackageName(), item.getClassName()));
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mActivityStarter.startActivity(intent, true);
            } catch (Exception e) {
                Log.e(TAG, "Failed launching app " + item.getPackageName(), e);
            }
        }
    }

    @Override
    public void onEditRequested() {
        dismissOverlay();

        try {
            Intent intent = new Intent("com.android.settings.action.EDIT_QUICK_LAUNCH");
            intent.setComponent(new ComponentName("com.android.settings",
                    "com.android.settings.gestures.quicklaunch.EditQuickLaunchActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mActivityStarter.startActivity(intent, true);
        } catch (Exception e) {
            Log.e(TAG, "Failed opening EditQuickLaunchActivity", e);
        }
    }

    @Override
    public void onDismissed() {
        dismissOverlay();
    }
}
