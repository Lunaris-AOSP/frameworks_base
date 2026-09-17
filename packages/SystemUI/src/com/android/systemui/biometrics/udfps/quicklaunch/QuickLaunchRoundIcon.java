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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;

import com.android.systemui.res.R;

public class QuickLaunchRoundIcon extends ImageView {

    private static final PathInterpolator SCALE_INTERPOLATOR =
            new PathInterpolator(0.0f, 0.0f, 0.1f, 1.0f);
    private static final PathInterpolator ALPHA_IN_INTERPOLATOR =
            new PathInterpolator(0.33f, 0.0f, 0.67f, 1.0f);
    private static final PathInterpolator ALPHA_OUT_INTERPOLATOR =
            new PathInterpolator(0.4f, 0.0f, 0.0f, 1.0f);
    private static final PathInterpolator REVOKE_INTERPOLATOR =
            new PathInterpolator(0.3f, 0.0f, 1.0f, 1.0f);

    private AnimatorSet mPressAnimatorSet;
    private AnimatorSet mRevokeAnimatorSet;

    public interface OnSpreadThresholdListener {
        void onSpreadThresholdReached();
    }

    public QuickLaunchRoundIcon(Context context) {
        this(context, null);
    }

    public QuickLaunchRoundIcon(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QuickLaunchRoundIcon(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setImageDrawable(ContextCompat.getDrawable(context, R.drawable.kgd_fp_ql_round_icon));
        setScaleX(1.1f);
        setScaleY(1.1f);
        setAlpha(0.0f);
    }

    public AnimatorSet startLongPressAnim(final Runnable onAnimationEnd,
                                          final OnSpreadThresholdListener thresholdListener) {
        cancelAnim();
        setScaleX(1.1f);
        setScaleY(1.1f);
        setAlpha(0.0f);

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(this, "scaleX", 1.1f, 7.5f);
        scaleX.setDuration(750);
        scaleX.setInterpolator(SCALE_INTERPOLATOR);

        ObjectAnimator scaleY = ObjectAnimator.ofFloat(this, "scaleY", 1.1f, 7.5f);
        scaleY.setDuration(750);
        scaleY.setInterpolator(SCALE_INTERPOLATOR);

        ObjectAnimator alphaIn = ObjectAnimator.ofFloat(this, "alpha", 0.0f, 0.25f);
        alphaIn.setDuration(350);
        alphaIn.setInterpolator(ALPHA_IN_INTERPOLATOR);

        ObjectAnimator alphaOut = ObjectAnimator.ofFloat(this, "alpha", 0.25f, 0.0f);
        alphaOut.setDuration(400);
        alphaOut.setStartDelay(350);
        alphaOut.setInterpolator(ALPHA_OUT_INTERPOLATOR);

        if (thresholdListener != null) {
            scaleX.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                private boolean mFired = false;
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    if (!mFired && animation.getAnimatedFraction() >= 0.3f) {
                        mFired = true;
                        thresholdListener.onSpreadThresholdReached();
                    }
                }
            });
        }

        mPressAnimatorSet = new AnimatorSet();
        mPressAnimatorSet.playTogether(scaleX, scaleY, alphaIn, alphaOut);
        mPressAnimatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onAnimationEnd != null) {
                    onAnimationEnd.run();
                }
            }
        });
        mPressAnimatorSet.start();
        return mPressAnimatorSet;
    }

    public void startLongPressRevokeAnim() {
        cancelAnim();
        float currentScale = getScaleX();
        float currentAlpha = getAlpha();

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(this, "scaleX", currentScale, 1.1f);
        scaleX.setDuration(200);
        scaleX.setInterpolator(REVOKE_INTERPOLATOR);

        ObjectAnimator scaleY = ObjectAnimator.ofFloat(this, "scaleY", currentScale, 1.1f);
        scaleY.setDuration(200);
        scaleY.setInterpolator(REVOKE_INTERPOLATOR);

        ObjectAnimator alpha = ObjectAnimator.ofFloat(this, "alpha", currentAlpha, 0.0f);
        alpha.setDuration(200);
        alpha.setInterpolator(REVOKE_INTERPOLATOR);

        mRevokeAnimatorSet = new AnimatorSet();
        mRevokeAnimatorSet.playTogether(scaleX, scaleY, alpha);
        mRevokeAnimatorSet.start();
    }

    public void cancelAnim() {
        if (mPressAnimatorSet != null && mPressAnimatorSet.isRunning()) {
            mPressAnimatorSet.cancel();
            mPressAnimatorSet = null;
        }
        if (mRevokeAnimatorSet != null && mRevokeAnimatorSet.isRunning()) {
            mRevokeAnimatorSet.cancel();
            mRevokeAnimatorSet = null;
        }
    }
}
