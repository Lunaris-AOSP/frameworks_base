/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.PowerManager;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.server.display.utils.SensorUtils;

import java.util.function.Consumer;

final class AmbientLightModeController {
    static final long LIGHT_DEBOUNCE_MILLIS = 1000;
    private static final float DARK_LUX = 10f;
    private static final float LIGHT_LUX = 50f;
    private static final float PROXIMITY_NEAR_CM = 5f;

    private final Context mContext;
    private final Handler mHandler;
    private final Object mLock;
    private final Consumer<Float> mCallback;
    private final SensorManager mSensorManager;
    private final PowerManager mPowerManager;
    private final DisplayManager mDisplayManager;
    private final DisplayManagerInternal mDisplayManagerInternal;
    private final Sensor mProximitySensor;
    @GuardedBy("mLock") private boolean mEnabled;
    @GuardedBy("mLock") private LightListener mLightListener;

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    startListeningLocked();
                } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    stopListeningLocked();
                }
            }
        }
    };

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    if (displayId != Display.DEFAULT_DISPLAY) return;
                    synchronized (mLock) {
                        startListeningLocked();
                    }
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    if (displayId != Display.DEFAULT_DISPLAY) return;
                    synchronized (mLock) {
                        stopListeningLocked();
                    }
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId != Display.DEFAULT_DISPLAY) return;
                    synchronized (mLock) {
                        if (mLightListener == null
                                || mLightListener.mLightSensor != getLightSensor()) {
                            startListeningLocked();
                        }
                    }
                }
            };

    AmbientLightModeController(Context context, Handler handler, Object lock,
            Consumer<Float> callback) {
        mContext = context;
        mHandler = handler;
        mLock = lock;
        mCallback = callback;
        mSensorManager = context.getSystemService(SensorManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        mProximitySensor = mSensorManager == null ? null
                : mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
    }

    boolean isAvailable() {
        return getLightSensor() != null;
    }

    private Sensor getLightSensor() {
        DisplayManagerInternal.AmbientLightSensorData sensorData = mDisplayManagerInternal == null
                ? null : mDisplayManagerInternal.getAmbientLightSensorData(Display.DEFAULT_DISPLAY);
        return SensorUtils.findSensor(mSensorManager,
                sensorData == null ? null : sensorData.sensorType,
                sensorData == null ? null : sensorData.sensorName, Sensor.TYPE_LIGHT);
    }

    static boolean isNight(float lux, boolean currentlyNight) {
        return currentlyNight ? lux < LIGHT_LUX : lux <= DARK_LUX;
    }

    private static int getLuxRange(float lux) {
        if (lux <= DARK_LUX) return -1;
        return lux >= LIGHT_LUX ? 1 : 0;
    }

    void setEnabled(boolean enabled) {
        synchronized (mLock) {
            enabled &= isAvailable();
            if (mEnabled == enabled) {
                if (enabled && mLightListener == null) {
                    startListeningLocked();
                }
                return;
            }
            mEnabled = enabled;
            if (enabled) {
                IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                mContext.registerReceiver(mScreenReceiver, filter, null, mHandler);
                if (mDisplayManager != null) {
                    mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
                }
                startListeningLocked();
            } else {
                mContext.unregisterReceiver(mScreenReceiver);
                if (mDisplayManager != null) {
                    mDisplayManager.unregisterDisplayListener(mDisplayListener);
                }
                stopListeningLocked();
            }
        }
    }

    void cancelPendingChange() {
        synchronized (mLock) {
            if (mLightListener != null) {
                mLightListener.clearPendingLuxLocked();
                mLightListener.mLastLux = Float.NaN;
            }
        }
    }

    @GuardedBy("mLock")
    private void startListeningLocked() {
        stopListeningLocked();
        if (!mEnabled || !mPowerManager.isInteractive() || mPowerManager.isPowerSaveMode()) {
            return;
        }
        Sensor lightSensor = getLightSensor();
        if (lightSensor == null) return;
        LightListener listener = new LightListener(lightSensor);
        mLightListener = listener;
        if (!mSensorManager.registerListener(listener, lightSensor,
                SensorManager.SENSOR_DELAY_NORMAL, mHandler)) {
            stopListeningLocked();
            return;
        }
        if (mProximitySensor != null) {
            listener.mWaitForProximity = mSensorManager.registerListener(listener, mProximitySensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        }
    }

    @GuardedBy("mLock")
    private void stopListeningLocked() {
        if (mLightListener == null) return;
        mLightListener.clearPendingLuxLocked();
        mSensorManager.unregisterListener(mLightListener);
        mLightListener = null;
    }

    private final class LightListener implements SensorEventListener, Runnable {
        private final Sensor mLightSensor;
        private float mPendingLux = Float.NaN;
        private float mLastLux = Float.NaN;
        private boolean mWaitForProximity;
        private boolean mProximityReceived;
        private boolean mCovered;

        LightListener(Sensor lightSensor) {
            mLightSensor = lightSensor;
        }

        @GuardedBy("mLock")
        private void addLuxLocked(float lux) {
            if (!Float.isFinite(mPendingLux) || getLuxRange(lux) != getLuxRange(mPendingLux)) {
                mHandler.removeCallbacks(this);
                mHandler.postDelayed(this, LIGHT_DEBOUNCE_MILLIS);
            }
            mPendingLux = lux;
        }

        @GuardedBy("mLock")
        private void clearPendingLuxLocked() {
            mHandler.removeCallbacks(this);
            mPendingLux = Float.NaN;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            synchronized (mLock) {
                if (mLightListener != this || event.values.length == 0) return;
                float value = event.values[0];
                if (!Float.isFinite(value) || value < 0) return;
                if (event.sensor == mLightSensor) {
                    mLastLux = value;
                    if (!mCovered) {
                        addLuxLocked(value);
                    }
                } else if (event.sensor == mProximitySensor) {
                    boolean wasCovered = mCovered;
                    boolean proximityReceived = mProximityReceived;
                    mProximityReceived = true;
                    mCovered = value < Math.min(PROXIMITY_NEAR_CM,
                            mProximitySensor.getMaximumRange());
                    if (mCovered) {
                        clearPendingLuxLocked();
                        mLastLux = Float.NaN;
                    } else if ((!proximityReceived || wasCovered)
                            && !Float.isFinite(mPendingLux) && Float.isFinite(mLastLux)) {
                        addLuxLocked(mLastLux);
                    }
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        @Override
        public void run() {
            synchronized (mLock) {
                if (mLightListener != this) return;
                float lux = mPendingLux;
                clearPendingLuxLocked();
                if (Float.isFinite(lux) && !mCovered
                        && (!mWaitForProximity || mProximityReceived)
                        && mPowerManager.isInteractive() && !mPowerManager.isPowerSaveMode()) {
                    mCallback.accept(lux);
                }
            }
        }
    }
}
