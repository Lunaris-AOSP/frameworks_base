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

package com.android.systemui.statusbar.notification.stack;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.MathUtils;
import android.view.View;

import com.android.app.animation.Interpolators;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import java.util.ArrayList;
import java.util.List;

class MotoCompactNotificationAnimator {
    private static final long EXPAND_DURATION = 360;
    private static final long COLLAPSE_DURATION = 280;

    private final View mHost;
    private final ArrayList<ExpandableNotificationRow> mRows = new ArrayList<>();
    private final int[] mLocation = new int[2];
    private final RectF mIconBounds = new RectF();
    private final RectF mRowBounds = new RectF();
    private final RectF mRevealBounds = new RectF();
    private final Path mClipPath = new Path();
    private StatusBarIconView mIcon;
    private ValueAnimator mAnimator;
    private float mAmount = 1f;
    private boolean mCollapsing;

    MotoCompactNotificationAnimator(View host) {
        mHost = host;
    }

    boolean isCollapsing() {
        return mCollapsing;
    }

    void start(StatusBarIconView icon, List<ExpandableNotificationRow> rows, boolean expand,
            Runnable onCollapsed) {
        stopAnimator();
        if (mIcon != icon) mAmount = expand ? 0f : 1f;
        mIcon = icon;
        mRows.clear();
        mRows.addAll(rows);
        mCollapsing = !expand;
        final float target = expand ? 1f : 0f;
        mAnimator = ValueAnimator.ofFloat(mAmount, target);
        mAnimator.setDuration(Math.round((expand ? EXPAND_DURATION : COLLAPSE_DURATION)
                * Math.abs(target - mAmount)));
        mAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mAnimator.addUpdateListener(animation -> {
            mAmount = (float) animation.getAnimatedValue();
            mHost.invalidate();
        });
        mAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimator = null;
                if (!expand) onCollapsed.run();
            }
        });
        mAnimator.start();
    }

    void cancel() {
        stopAnimator();
        mIcon = null;
        mRows.clear();
        mAmount = 1f;
        mCollapsing = false;
        mHost.invalidate();
    }

    private void stopAnimator() {
        if (mAnimator == null) return;
        mAnimator.removeAllListeners();
        mAnimator.removeAllUpdateListeners();
        mAnimator.cancel();
        mAnimator = null;
    }

    int prepareCanvas(Canvas canvas, View child) {
        if (mIcon == null || mAmount >= 1f || !mRows.contains(child)
                || !(child instanceof ExpandableNotificationRow row)
                || row.getWidth() <= 0 || row.getActualHeight() <= 0) return -1;

        mHost.getLocationOnScreen(mLocation);
        final int hostX = mLocation[0];
        final int hostY = mLocation[1];
        mIcon.getLocationOnScreen(mLocation);
        final float centerX = mLocation[0] - hostX + mIcon.getWidth() / 2f;
        final float centerY = mLocation[1] - hostY + mIcon.getHeight() / 2f;
        final float radius = mHost.getResources().getDimension(
                R.dimen.moto_compact_notification_background_size) / 2f
                * Math.min(mIcon.getScaleX(), mIcon.getScaleY());
        mIconBounds.set(centerX - radius, centerY - radius,
                centerX + radius, centerY + radius);
        final float left = row.getX() + row.getPivotX() * (1f - row.getScaleX());
        final float top = row.getY() + row.getPivotY() * (1f - row.getScaleY());
        mRowBounds.set(left, top, left + row.getWidth() * row.getScaleX(),
                top + row.getActualHeight() * row.getScaleY());
        if (mRowBounds.width() <= 0f || mRowBounds.height() <= 0f) return -1;
        interpolateBounds(mIconBounds, mRowBounds, mAmount, mRevealBounds);

        final int saveCount = canvas.save();
        if (mAmount < 0.2f) {
            canvas.saveLayerAlpha(mRevealBounds, Math.round(255f * mAmount / 0.2f));
        }
        final float cornerRadius = MathUtils.lerp(radius,
                mHost.getResources().getDimension(R.dimen.notification_corner_radius), mAmount);
        mClipPath.rewind();
        mClipPath.addRoundRect(mRevealBounds, cornerRadius, cornerRadius, Path.Direction.CW);
        canvas.clipPath(mClipPath);
        canvas.translate(mRevealBounds.left, mRevealBounds.top);
        canvas.scale(mRevealBounds.width() / mRowBounds.width(),
                mRevealBounds.height() / mRowBounds.height());
        canvas.translate(-mRowBounds.left, -mRowBounds.top);
        return saveCount;
    }

    private static void interpolateBounds(RectF icon, RectF row, float amount, RectF out) {
        amount = MathUtils.saturate(amount);
        out.set(MathUtils.lerp(icon.left, row.left, amount),
                MathUtils.lerp(icon.top, row.top, amount),
                MathUtils.lerp(icon.right, row.right, amount),
                MathUtils.lerp(icon.bottom, row.bottom, amount));
    }
}
