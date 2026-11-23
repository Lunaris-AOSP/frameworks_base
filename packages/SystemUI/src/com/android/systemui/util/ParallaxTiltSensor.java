/*
 * SPDX-FileCopyrightText: 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.util;

import android.content.Context;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.Sensor;

public final class ParallaxTiltSensor implements SensorEventListener {
    public interface Listener extends org.lunaris.clockmaker.spatial.ParallaxTiltSensor.Listener { }
    private static ParallaxTiltSensor instance;
    private final org.lunaris.clockmaker.spatial.ParallaxTiltSensor delegate;

    private ParallaxTiltSensor(Context context) {
        delegate = org.lunaris.clockmaker.spatial.ParallaxTiltSensor.getInstance(context);
    }

    public static synchronized ParallaxTiltSensor getInstance(Context context) {
        if (instance == null) instance = new ParallaxTiltSensor(context.getApplicationContext());
        return instance;
    }

    public boolean addListener(Listener listener) { return delegate.addListener(listener); }
    public void removeListener(Listener listener) { delegate.removeListener(listener); }
    public void onDisplayRotationChanged(int rotation) { delegate.onDisplayRotationChanged(rotation); }
    @Override public void onSensorChanged(SensorEvent event) { delegate.onSensorChanged(event); }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
