/*
 * Copyright (C) 2026 AxionOS
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

package com.android.systemui.statusbar.notification.row;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import com.android.axion.blur.AxBlurBackgroundRenderer;
import com.android.axion.blur.AxBlurColors;
import com.android.axion.blur.model.AxBackdropBlurSettingsSpec;
import com.android.systemui.util.ScrimUtils;

public class BundleHeaderBlurView extends View {
    private final AxBlurBackgroundRenderer mBlurRenderer;
    private final Object mBlurKey = new Object();
    private boolean mBlurEnabled;
    private int mOverlayColor;
    private final GradientDrawable mMediaArtBackground = new GradientDrawable();
    private boolean mMediaArtVisible;
    private final ScrimUtils.ScrimEventListener mScrimListener =
            new ScrimUtils.ScrimEventListener() {
                @Override
                public void onMediaArtVisibilityChanged(boolean visible) {
                    setMediaArtVisible(visible);
                }
            };
    private boolean mLastBlurActive;
    private Runnable mOnBlurStateChanged;

    public BundleHeaderBlurView(Context context) {
        super(context);
        mBlurRenderer = new AxBlurBackgroundRenderer(this, AxBackdropBlurSettingsSpec.system(),
                false);
        updateColors();
    }

    public void setOnBlurStateChangedListener(Runnable listener) {
        mOnBlurStateChanged = listener;
    }

    @Override
    public void draw(Canvas canvas) {
        boolean active = isBackgroundActive();
        if (active != mLastBlurActive) {
            mLastBlurActive = active;
            if (mOnBlurStateChanged != null) {
                post(mOnBlurStateChanged);
            }
        }
        drawBlurBackground(canvas);
        super.draw(canvas);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateColors();
        ScrimUtils scrimUtils = ScrimUtils.get(getContext());
        scrimUtils.addListener(mScrimListener);
        setMediaArtVisible(scrimUtils.isMediaArtVisible());
        mBlurRenderer.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        ScrimUtils.get().removeListener(mScrimListener);
        mBlurRenderer.onDetachedFromWindow();
        mOnBlurStateChanged = null;
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        mBlurRenderer.onVisibilityAggregated(isVisible);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateColors();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return mBlurRenderer.verifyDrawable(who) || super.verifyDrawable(who);
    }

    /** Whether this view supplies the header background, including translucency over media art. */
    public boolean isBackgroundActive() {
        return mBlurEnabled && (mMediaArtVisible || mBlurRenderer.isCrossWindowBlurActive());
    }

    public void setAxBlurEnabled(boolean enabled) {
        if (mBlurEnabled == enabled) {
            return;
        }
        mBlurEnabled = enabled;
        mBlurRenderer.setEnabled(enabled && !mMediaArtVisible);
        if (!enabled) {
            mBlurRenderer.clear();
        }
        invalidate();
    }

    private void setMediaArtVisible(boolean visible) {
        if (mMediaArtVisible == visible) {
            return;
        }
        mMediaArtVisible = visible;
        mBlurRenderer.setEnabled(mBlurEnabled && !visible);
        invalidate();
    }

    private void updateColors() {
        mOverlayColor = AxBlurColors.surfaceBrightTint(getContext());
        invalidate();
    }

    private void drawBlurBackground(Canvas canvas) {
        if (!isBackgroundActive() || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        float cornerRadius = Math.min(getWidth(), getHeight()) * 0.5f;
        if (mMediaArtVisible) {
            mMediaArtBackground.setBounds(0, 0, getWidth(), getHeight());
            mMediaArtBackground.setCornerRadius(cornerRadius);
            mMediaArtBackground.setColor(mOverlayColor);
            mMediaArtBackground.draw(canvas);
            return;
        }
        mBlurRenderer.draw(
                canvas,
                mBlurKey,
                0,
                0,
                getWidth(),
                getHeight(),
                cornerRadius,
                mOverlayColor,
                255);
    }
}
