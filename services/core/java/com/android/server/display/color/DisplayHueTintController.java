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

/** Control the color transform for user-set global display hue. */
final class DisplayHueTintController extends TintController {

    static final int DEFAULT_HUE = 0;
    static final int MIN_HUE = -180;
    static final int MAX_HUE = 180;

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
        final int hue = MathUtils.constrain(value, MIN_HUE, MAX_HUE);
        if (hue == DEFAULT_HUE) {
            setActivated(false);
            Matrix.setIdentityM(mMatrix, 0);
            return;
        }

        setActivated(true);

        final float angle = hue * (float) Math.PI / 180f;
        final float cosA = (float) Math.cos(angle);
        final float sinA = (float) Math.sin(angle);

        mMatrix[0] = 0.213f + cosA * 0.787f - sinA * 0.213f;
        mMatrix[1] = 0.715f - cosA * 0.715f - sinA * 0.715f;
        mMatrix[2] = 0.072f - cosA * 0.072f + sinA * 0.928f;
        mMatrix[3] = 0f;

        mMatrix[4] = 0.213f - cosA * 0.213f + sinA * 0.143f;
        mMatrix[5] = 0.715f + cosA * 0.285f + sinA * 0.140f;
        mMatrix[6] = 0.072f - cosA * 0.072f - sinA * 0.283f;
        mMatrix[7] = 0f;

        mMatrix[8] = 0.213f - cosA * 0.213f - sinA * 0.787f;
        mMatrix[9] = 0.715f - cosA * 0.715f + sinA * 0.715f;
        mMatrix[10] = 0.072f + cosA * 0.928f + sinA * 0.072f;
        mMatrix[11] = 0f;

        mMatrix[12] = 0f;
        mMatrix[13] = 0f;
        mMatrix[14] = 0f;
        mMatrix[15] = 1f;
    }

    @Override
    public int getLevel() {
        return DisplayTransformManager.LEVEL_COLOR_MATRIX_PICTURE_HUE;
    }

    @Override
    public boolean isAvailable(Context context) {
        return ColorDisplayManager.isColorTransformAccelerated(context);
    }
}
