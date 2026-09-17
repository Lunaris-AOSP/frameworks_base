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

import android.content.Context;
import android.util.AttributeSet;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.res.R;

public class QuickLaunchEditView extends FrameLayout {

    private static final PathInterpolator FADE_IN_INTERPOLATOR =
            new PathInterpolator(0.3f, 0.0f, 0.1f, 1.0f);
    private static final PathInterpolator FADE_OUT_INTERPOLATOR =
            new PathInterpolator(0.3f, 0.0f, 1.0f, 1.0f);
    private static final PathInterpolator CHOSEN_INTERPOLATOR =
            new PathInterpolator(0.3f, 0.0f, 0.83f, 1.0f);

    private ImageView mCircleView;
    private ImageView mIconView;

    public QuickLaunchEditView(Context context) {
        this(context, null);
    }

    public QuickLaunchEditView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QuickLaunchEditView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mCircleView = findViewById(R.id.circle);
        mIconView = findViewById(R.id.icon);
        reset();
    }

    public void animateFadeIn() {
        if (mCircleView == null || mIconView == null) return;
        mCircleView.animate().cancel();
        mIconView.animate().cancel();
        mCircleView.animate()
                .alpha(0.85f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(250)
                .setInterpolator(FADE_IN_INTERPOLATOR)
                .start();
        mIconView.animate()
                .alpha(1.0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(250)
                .setInterpolator(FADE_IN_INTERPOLATOR)
                .start();
    }

    public void animateChosen() {
        if (mCircleView == null || mIconView == null) return;
        mCircleView.animate().cancel();
        mIconView.animate().cancel();
        mCircleView.animate()
                .alpha(1.0f)
                .scaleX(1.2f)
                .scaleY(1.2f)
                .setDuration(250)
                .setInterpolator(CHOSEN_INTERPOLATOR)
                .start();
        mIconView.animate()
                .alpha(1.0f)
                .scaleX(1.2f)
                .scaleY(1.2f)
                .setDuration(250)
                .setInterpolator(CHOSEN_INTERPOLATOR)
                .start();
    }

    public void animateFadeOut() {
        if (mCircleView == null || mIconView == null) return;
        mCircleView.animate().cancel();
        mIconView.animate().cancel();
        mCircleView.animate()
                .alpha(0.0f)
                .scaleX(0.0f)
                .scaleY(0.0f)
                .setDuration(167)
                .setInterpolator(FADE_OUT_INTERPOLATOR)
                .start();
        mIconView.animate()
                .alpha(0.0f)
                .scaleX(0.0f)
                .scaleY(0.0f)
                .setDuration(167)
                .setInterpolator(FADE_OUT_INTERPOLATOR)
                .start();
    }

    public void reset() {
        if (mCircleView != null) {
            mCircleView.animate().cancel();
            mCircleView.setAlpha(0.0f);
            mCircleView.setScaleX(0.0f);
            mCircleView.setScaleY(0.0f);
        }
        if (mIconView != null) {
            mIconView.animate().cancel();
            mIconView.setAlpha(0.0f);
            mIconView.setScaleX(0.0f);
            mIconView.setScaleY(0.0f);
        }
    }
}
