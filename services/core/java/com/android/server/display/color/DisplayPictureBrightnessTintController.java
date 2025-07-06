/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.display.color;

import android.content.Context;
import android.hardware.display.ColorDisplayManager;
import android.opengl.Matrix;
import android.util.MathUtils;

import java.util.Arrays;

/** Control the color transform for user-set global picture brightness gain. */
final class DisplayPictureBrightnessTintController extends TintController {

    static final int DEFAULT_BRIGHTNESS = 100;
    static final int MIN_BRIGHTNESS = 50;
    static final int MAX_BRIGHTNESS = 200;

    private final float[] mMatrix = new float[16];

    @Override
    public void setUp(Context context, boolean needsLinear) {
    }

    @Override
    public float[] getMatrix() {
        return Arrays.copyOf(mMatrix, mMatrix.length);
    }

    @Override
    public void setMatrix(int value) {
        final int brightness = MathUtils.constrain(value, MIN_BRIGHTNESS, MAX_BRIGHTNESS);
        if (brightness == DEFAULT_BRIGHTNESS) {
            setActivated(false);
            Matrix.setIdentityM(mMatrix, 0);
            return;
        }

        setActivated(true);

        final float gain = brightness / 100f;
        Matrix.setIdentityM(mMatrix, 0);
        mMatrix[0] = gain;
        mMatrix[5] = gain;
        mMatrix[10] = gain;
    }

    @Override
    public int getLevel() {
        return DisplayTransformManager.LEVEL_COLOR_MATRIX_PICTURE_BRIGHTNESS;
    }

    @Override
    public boolean isAvailable(Context context) {
        return ColorDisplayManager.isColorTransformAccelerated(context);
    }
}
