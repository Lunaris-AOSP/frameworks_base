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

package com.android.systemui.common.ui.drawable;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;

public final class CosTileBorderDrawable extends Drawable {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final float[] mCornerRadii = new float[8];
    private float mDensity;
    private float mStrokeWidth;
    private float mFeatherWidth;
    private int mAlpha = 255;
    private boolean mPathDirty = true;

    public CosTileBorderDrawable(float density) {
        mDensity = density;
        mPaint.setStyle(Paint.Style.STROKE);
    }

    public void setDensity(float density) {
        if (mDensity != density) {
            mDensity = density;
            mPathDirty = true;
            invalidateSelf();
        }
    }

    public void setCornerRadii(float[] radii) {
        if (!Arrays.equals(mCornerRadii, radii)) {
            System.arraycopy(radii, 0, mCornerRadii, 0, mCornerRadii.length);
            mPathDirty = true;
            invalidateSelf();
        }
    }

    public void setCornerRadius(float radius) {
        float[] radii = new float[8];
        Arrays.fill(radii, radius);
        setCornerRadii(radii);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        mPathDirty = true;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (getBounds().isEmpty() || mAlpha == 0) {
            return;
        }
        if (mPathDirty) {
            Rect bounds = getBounds();
            float minDimension = Math.min(bounds.width(), bounds.height());
            mStrokeWidth = Math.min(0.5f * mDensity, minDimension);
            mFeatherWidth = Math.min(mStrokeWidth + 1f, minDimension);
            float inset = mFeatherWidth / 2f;
            RectF strokeBounds = new RectF(bounds);
            strokeBounds.inset(inset, inset);
            float[] radii = new float[8];
            for (int i = 0; i < radii.length; i++) {
                radii[i] = Math.max(0f, mCornerRadii[i] - inset);
            }
            mPath.reset();
            mPath.addRoundRect(strokeBounds, radii, Path.Direction.CW);
            mPaint.setShader(new LinearGradient(0f, bounds.top, 0f, bounds.bottom,
                    new int[] {0x73FFFFFF, 0x73FFFFFF, 0x08FFFFFF},
                    new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            mPathDirty = false;
        }
        mPaint.setStrokeWidth(mFeatherWidth);
        mPaint.setAlpha(Math.round(mAlpha * 0.18f));
        canvas.drawPath(mPath, mPaint);
        mPaint.setStrokeWidth(mStrokeWidth);
        mPaint.setAlpha(mAlpha);
        canvas.drawPath(mPath, mPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        if (mAlpha != alpha) {
            mAlpha = alpha;
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
