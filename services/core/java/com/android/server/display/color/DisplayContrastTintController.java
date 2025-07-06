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

/** Control the color transform for user-set global display contrast. */
final class DisplayContrastTintController extends TintController {

    static final int DEFAULT_CONTRAST = 100;
    static final int MIN_CONTRAST = 50;
    static final int MAX_CONTRAST = 200;

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
        final int contrast = MathUtils.constrain(value, MIN_CONTRAST, MAX_CONTRAST);
        if (contrast == DEFAULT_CONTRAST) {
            setActivated(false);
            Matrix.setIdentityM(mMatrix, 0);
            return;
        }

        setActivated(true);

        final float contrastFactor = mapContrastForPerceptualResponse(contrast);
        final float translate = (1f - contrastFactor) / 2f;

        Matrix.setIdentityM(mMatrix, 0);
        mMatrix[0] = contrastFactor;
        mMatrix[5] = contrastFactor;
        mMatrix[10] = contrastFactor;
        mMatrix[12] = translate;
        mMatrix[13] = translate;
        mMatrix[14] = translate;
    }

    @Override
    public int getLevel() {
        return DisplayTransformManager.LEVEL_COLOR_MATRIX_PICTURE_CONTRAST;
    }

    @Override
    public boolean isAvailable(Context context) {
        return ColorDisplayManager.isColorTransformAccelerated(context);
    }

    /**
     * Contrast in linear color space is visually strong near neutral.
     * Use an eased curve so tiny slider changes around 100 are gentle.
     */
    private float mapContrastForPerceptualResponse(int contrast) {
        final float normalized = (contrast - DEFAULT_CONTRAST) / 100f; // [-0.5, 1.0]
        final float magnitude = (float) Math.pow(Math.abs(normalized), 1.6f);
        final float eased = Math.signum(normalized) * magnitude;
        // Final operating range ~= [0.72, 1.65], with high precision around 1.0.
        return 1f + (eased * 0.65f);
    }
}
