/*
 * SPDX-FileCopyrightText: 2026 MistOS
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.util;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public final class ParallaxTiltSensor implements SensorEventListener {
    public interface Listener {
        void onTiltChanged(float horizontal, float vertical);
    }

    private static final float LOW_PASS_ALPHA = 0.15f;
    private static final float STABLE_SPEED_THRESHOLD = 0.08f;
    private static final float ADAPT_ALPHA_STABLE = 0.05f;
    private static final float ADAPT_ALPHA_MOVING = 0.003f;
    private static final float DEAD_ZONE_DEGREES = 0.4f;
    private static final float MAX_TILT_DEGREES = 18f;
    private static final float CHANGE_THRESHOLD = 0.0005f;
    private static ParallaxTiltSensor sInstance;

    private final SensorManager mSensorManager;
    private final DisplayManager mDisplayManager;
    private final Sensor mSensor;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<Listener> mSubscriptions = new ArrayList<>();
    private final ConcurrentHashMap<Listener, Object> mRequested = new ConcurrentHashMap<>();
    private Listener[] mListeners = new Listener[0];
    private final float[] mRawQuaternion = new float[4];
    private final float[] mFilteredQuaternion = new float[4];
    private final float[] mPreviousQuaternion = new float[4];
    private final float[] mReferenceQuaternion = new float[4];
    private final float[] mRelativeQuaternion = new float[4];
    private boolean mRegistered;
    private boolean mFirstSampleReady;
    private int mDisplayRotation = Surface.ROTATION_0;
    private long mLastSampleTime;
    private float mHorizontal;
    private float mVertical;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {}

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        refreshDisplayRotation();
                    }
                }
            };

    public static synchronized ParallaxTiltSensor getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ParallaxTiltSensor(context.getApplicationContext());
        }
        return sInstance;
    }

    private ParallaxTiltSensor(Context context) {
        mSensorManager = context.getSystemService(SensorManager.class);
        mDisplayManager = context.getSystemService(DisplayManager.class);
        Sensor sensor = null;
        if (mSensorManager != null) {
            sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (sensor == null) {
                sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            }
            if (sensor == null) {
                sensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
        }
        mSensor = sensor;
    }

    public boolean addListener(Listener listener) {
        if (mSensor == null) return false;
        Object request = new Object();
        mRequested.put(listener, request);
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(() -> addListenerOnMain(listener, request));
            return true;
        }
        return addListenerOnMain(listener, request);
    }

    private boolean addListenerOnMain(Listener listener, Object request) {
        if (mRequested.get(listener) != request) return false;
        if (mSubscriptions.contains(listener)) return mRegistered;
        if (!mRegistered) {
            mRegistered = mSensorManager.registerListener(this, mSensor,
                    SensorManager.SENSOR_DELAY_GAME, mHandler);
            if (!mRegistered) {
                mRequested.remove(listener, request);
                return false;
            }
            refreshDisplayRotation();
            if (mDisplayManager != null) {
                mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
            }
        }
        if (mRequested.get(listener) != request) {
            if (mSubscriptions.isEmpty()) stopSensing();
            return false;
        }
        mSubscriptions.add(listener);
        mListeners = mSubscriptions.toArray(new Listener[0]);
        listener.onTiltChanged(mHorizontal, mVertical);
        return mRequested.containsKey(listener) && mSubscriptions.contains(listener);
    }

    public void removeListener(Listener listener) {
        mRequested.remove(listener);
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(() -> removeListenerOnMain(listener));
            return;
        }
        removeListenerOnMain(listener);
    }

    private void removeListenerOnMain(Listener listener) {
        if (mRequested.containsKey(listener)) return;
        if (!mSubscriptions.remove(listener)) return;
        mListeners = mSubscriptions.toArray(new Listener[0]);
        if (!mSubscriptions.isEmpty()) return;
        stopSensing();
    }

    private void stopSensing() {
        if (!mRegistered) return;
        mSensorManager.unregisterListener(this);
        if (mDisplayManager != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
        mRegistered = false;
        mFirstSampleReady = false;
        mLastSampleTime = 0L;
        mHorizontal = 0f;
        mVertical = 0f;
    }

    public void onDisplayRotationChanged(int rotation) {
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(() -> onDisplayRotationChanged(rotation));
            return;
        }
        if (mDisplayRotation == rotation) return;
        mDisplayRotation = rotation;
        mFirstSampleReady = false;
        mLastSampleTime = 0L;
        publish(0f, 0f);
    }

    private void refreshDisplayRotation() {
        Display display = mDisplayManager == null ? null
                : mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display != null) onDisplayRotationChanged(display.getRotation());
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!mRegistered || mListeners.length == 0 || event.values.length < 3) return;
        if (!readQuaternion(event.values)) return;
        if (!mFirstSampleReady) {
            System.arraycopy(mRawQuaternion, 0, mFilteredQuaternion, 0, 4);
            System.arraycopy(mRawQuaternion, 0, mReferenceQuaternion, 0, 4);
            mLastSampleTime = event.timestamp;
            mFirstSampleReady = true;
            return;
        }
        if (event.timestamp <= mLastSampleTime) return;
        float seconds = Math.min((event.timestamp - mLastSampleTime) * 0.000000001f, 0.1f);
        mLastSampleTime = event.timestamp;
        float sampleScale = seconds * 60f;
        System.arraycopy(mFilteredQuaternion, 0, mPreviousQuaternion, 0, 4);
        interpolateQuaternion(mFilteredQuaternion, mRawQuaternion,
                interpolation(LOW_PASS_ALPHA, sampleScale));
        float speed = quaternionDistance(mFilteredQuaternion, mPreviousQuaternion) / sampleScale;
        interpolateQuaternion(mReferenceQuaternion, mFilteredQuaternion,
                interpolation(speed < STABLE_SPEED_THRESHOLD
                        ? ADAPT_ALPHA_STABLE : ADAPT_ALPHA_MOVING, sampleScale));
        relativeQuaternion(mReferenceQuaternion, mFilteredQuaternion, mRelativeQuaternion);
        double length = Math.sqrt((double) mRelativeQuaternion[0] * mRelativeQuaternion[0]
                + (double) mRelativeQuaternion[1] * mRelativeQuaternion[1]
                + (double) mRelativeQuaternion[2] * mRelativeQuaternion[2]);
        float multiplier = length < 0.000001 ? 0f
                : (float) (Math.toDegrees(2d * Math.atan2(length,
                        Math.abs(mRelativeQuaternion[3]))) / length);
        if (mRelativeQuaternion[3] < 0f) multiplier = -multiplier;
        float x = mRelativeQuaternion[1] * multiplier;
        float y = -mRelativeQuaternion[0] * multiplier;
        float horizontal;
        float vertical;
        switch (mDisplayRotation) {
            case Surface.ROTATION_90:
                horizontal = -y;
                vertical = x;
                break;
            case Surface.ROTATION_180:
                horizontal = -x;
                vertical = -y;
                break;
            case Surface.ROTATION_270:
                horizontal = y;
                vertical = -x;
                break;
            default:
                horizontal = x;
                vertical = y;
        }
        publish(normalize(horizontal), normalize(vertical));
    }

    private boolean readQuaternion(float[] values) {
        for (int i = 0; i < 3; i++) {
            if (!Float.isFinite(values[i])) return false;
        }
        if (mSensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            double length = Math.sqrt((double) values[0] * values[0]
                    + (double) values[1] * values[1] + (double) values[2] * values[2]);
            if (length < 0.01) return false;
            mRawQuaternion[0] = (float) (values[1] / length);
            mRawQuaternion[1] = (float) (-values[0] / length);
            mRawQuaternion[2] = 0f;
            mRawQuaternion[3] = 1f + (float) (values[2] / length);
            if (mRawQuaternion[3] < 0.000001f
                    && Math.abs(mRawQuaternion[0]) + Math.abs(mRawQuaternion[1]) < 0.000001f) {
                mRawQuaternion[0] = 1f;
                mRawQuaternion[1] = 0f;
                mRawQuaternion[3] = 0f;
            }
        } else {
            System.arraycopy(values, 0, mRawQuaternion, 0, 3);
            if (values.length > 3) {
                if (!Float.isFinite(values[3])) return false;
                mRawQuaternion[3] = values[3];
            } else {
                double squared = (double) values[0] * values[0]
                        + (double) values[1] * values[1] + (double) values[2] * values[2];
                mRawQuaternion[3] = (float) Math.sqrt(Math.max(0d, 1d - squared));
            }
        }
        return normalizeQuaternion(mRawQuaternion);
    }

    private void publish(float horizontal, float vertical) {
        if (Math.abs(horizontal - mHorizontal) < CHANGE_THRESHOLD
                && Math.abs(vertical - mVertical) < CHANGE_THRESHOLD
                && (horizontal != 0f || mHorizontal == 0f)
                && (vertical != 0f || mVertical == 0f)) return;
        mHorizontal = horizontal;
        mVertical = vertical;
        Listener[] listeners = mListeners;
        for (Listener listener : listeners) {
            if (mRequested.containsKey(listener)) listener.onTiltChanged(horizontal, vertical);
        }
    }

    private static float interpolation(float alpha, float sampleScale) {
        return 1f - (float) Math.pow(1f - alpha, sampleScale);
    }

    private static boolean normalizeQuaternion(float[] quaternion) {
        double squared = 0d;
        for (float value : quaternion) squared += (double) value * value;
        double length = Math.sqrt(squared);
        if (length < 0.000001 || !Double.isFinite(length)) return false;
        for (int i = 0; i < 4; i++) quaternion[i] = (float) (quaternion[i] / length);
        return true;
    }

    private static void interpolateQuaternion(float[] current, float[] target, float alpha) {
        float dot = 0f;
        for (int i = 0; i < 4; i++) dot += current[i] * target[i];
        float sign = dot < 0f ? -1f : 1f;
        for (int i = 0; i < 4; i++) {
            current[i] += (sign * target[i] - current[i]) * alpha;
        }
        normalizeQuaternion(current);
    }

    private static float quaternionDistance(float[] first, float[] second) {
        double difference = 0d;
        double sum = 0d;
        for (int i = 0; i < 4; i++) {
            double delta = first[i] - second[i];
            double total = first[i] + second[i];
            difference += delta * delta;
            sum += total * total;
        }
        return (float) Math.toDegrees(4d * Math.atan2(Math.sqrt(difference), Math.sqrt(sum)));
    }

    private static void relativeQuaternion(float[] reference, float[] current, float[] relative) {
        float x = reference[0];
        float y = reference[1];
        float z = reference[2];
        float w = reference[3];
        relative[0] = w * current[0] - x * current[3] - y * current[2] + z * current[1];
        relative[1] = w * current[1] + x * current[2] - y * current[3] - z * current[0];
        relative[2] = w * current[2] - x * current[1] + y * current[0] - z * current[3];
        relative[3] = w * current[3] + x * current[0] + y * current[1] + z * current[2];
    }

    private static float normalize(float degrees) {
        float effective = Math.max(0f, Math.abs(degrees) - DEAD_ZONE_DEGREES);
        return Math.copySign(Math.min(1f, effective / MAX_TILT_DEGREES), degrees);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
