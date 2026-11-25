/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.statusbar.notification.stack;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.util.ArrayMap;
import android.view.View;

import com.android.internal.dynamicanimation.animation.FloatValueHolder;
import com.android.internal.dynamicanimation.animation.SpringAnimation;
import com.android.internal.dynamicanimation.animation.SpringForce;
import com.android.systemui.qs.MiuiQsMotion;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;

final class MiuiNotificationShadeAnimator {
    private final NotificationStackScrollLayout mHost;
    private final ArrayMap<View, ItemMotion> mItems = new ArrayMap<>();
    private final MiuiQsMotion.Appearance mAppearance = new MiuiQsMotion.Appearance();
    private final RectF mViewport = new RectF();
    private final RectF mChildBounds = new RectF();
    private float mExpansion;
    private float mCornerX;
    private float mHeaderY;
    private boolean mEnabled;
    private boolean mShowing;
    private boolean mSwiping;
    private boolean mLaunching;
    private boolean mFinishedForInteraction;
    private boolean mFromLeft;
    private boolean mRunning;

    MiuiNotificationShadeAnimator(NotificationStackScrollLayout host) {
        mHost = host;
    }

    void setExpansion(float expansion, boolean enabled, boolean fromLeft) {
        mExpansion = MiuiQsMotion.clamp(expansion);
        if (!enabled && !mEnabled) return;
        mEnabled = enabled;
        updateAppearance(fromLeft);
    }

    void setSwiping(boolean swiping) {
        mSwiping = swiping;
        updateAppearance(mFromLeft);
    }

    void setLaunching(boolean launching) {
        mLaunching = launching;
        if (launching) cancel();
    }

    void refreshVisibility() {
        updateAppearance(mFromLeft);
    }

    boolean finishForInteraction() {
        if (!mShowing || !mRunning) return false;
        mFinishedForInteraction = true;
        for (int i = 0; i < mItems.size(); i++) {
            ItemMotion item = mItems.valueAt(i);
            item.snap(1f);
            item.target = 1f;
        }
        mRunning = false;
        mHost.invalidate();
        return true;
    }

    private void updateAppearance(boolean fromLeft) {
        boolean visible = mHost.getVisibility() == View.VISIBLE;
        boolean showing = mAppearance.update(mExpansion,
                mEnabled && visible, mSwiping);
        if (!showing || mSwiping) mFinishedForInteraction = false;
        if (mExpansion <= 0f || (showing && !mShowing)) mFromLeft = fromLeft;
        mShowing = showing;
        for (int i = 0; i < mItems.size(); i++) {
            ItemMotion item = mItems.valueAt(i);
            if (!mEnabled || mExpansion <= 0f || mSwiping || !showing || !visible) {
                item.update(visible && item.active, item.distance);
            }
        }
        if (!mEnabled || mExpansion <= 0f || mSwiping || mFinishedForInteraction || !visible) {
            mRunning = false;
        }
        mHost.invalidate();
    }

    void prepareFrame(float left, float top, float right, float bottom) {
        if (!mEnabled || mExpansion <= 0f || mSwiping || mLaunching
                || mFinishedForInteraction || mHost.getVisibility() != View.VISIBLE) {
            mRunning = false;
            return;
        }
        mViewport.set(left, top, right, bottom);
        View root = mHost.getRootView();
        mCornerX = mFromLeft ? 0f : mHost.getWidth();
        mHeaderY = Math.max(0f, top);
        float width = Math.max(root.getWidth(), mHost.getResources().getDisplayMetrics().widthPixels);
        float height = Math.max(root.getHeight(), mHost.getResources().getDisplayMetrics().heightPixels);
        for (int i = 0; i < mHost.getChildCount(); i++) {
            View child = mHost.getChildAt(i);
            if (!(child instanceof ExpandableView expandable) || child instanceof NotificationShelf
                    || (child instanceof ExpandableNotificationRow row && row.isPinned())) {
                remove(child);
                continue;
            }
            float childLeft = child.getX() + child.getPivotX() * (1f - child.getScaleX());
            float childTop = child.getY() + child.getPivotY() * (1f - child.getScaleY());
            mChildBounds.set(childLeft, childTop, childLeft + child.getWidth() * child.getScaleX(),
                    childTop + expandable.getActualHeight() * child.getScaleY());
            ItemMotion item = mItems.get(child);
            boolean active = child.getVisibility() == View.VISIBLE
                    && !expandable.getViewState().hidden
                    && expandable.getViewState().getAlpha() > 0f
                    && mChildBounds.width() > 0f && mChildBounds.height() > 0f
                    && (RectF.intersects(mChildBounds, mViewport)
                            || (!mShowing && item != null && item.active));
            if (item == null) {
                if (!active) continue;
                item = new ItemMotion(mExpansion >= 1f ? 1f : 0f);
                mItems.put(child, item);
            }
            item.bounds.set(mChildBounds);
            float distance = MiuiQsMotion.distance(item.bounds.centerX(), item.bounds.centerY(),
                    mCornerX, mHeaderY, width, height);
            item.update(active, distance);
        }
        mRunning = false;
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.valueAt(i).isRunning()) {
                mRunning = true;
                break;
            }
        }
    }

    boolean isRunning() {
        return mRunning;
    }

    boolean shouldDraw(View child) {
        if (!mEnabled || mExpansion <= 0f || mSwiping || mLaunching
                || mFinishedForInteraction) return true;
        ItemMotion item = mItems.get(child);
        return item == null || ((!mRunning || item.active)
                && (!item.active || MiuiQsMotion.clamp(item.appearance.value.getValue()) > 0f));
    }

    int prepareCanvas(Canvas canvas, View child) {
        if (!mEnabled || mExpansion <= 0f || mSwiping || mLaunching
                || mFinishedForInteraction) return -1;
        ItemMotion item = mItems.get(child);
        if (item == null || !item.active || item.isAt(1f)) return -1;
        float centerX = item.bounds.centerX();
        float centerY = item.bounds.centerY();
        float scale = MiuiQsMotion.scale(item.appearance.value.getValue());
        float alpha = MiuiQsMotion.clamp(item.appearance.value.getValue());
        int saveCount = canvas.save();
        canvas.translate(
                MiuiQsMotion.translation(centerX, mCornerX, item.horizontal.value.getValue()),
                MiuiQsMotion.translation(centerY, mHeaderY, item.vertical.value.getValue()));
        canvas.scale(scale, scale, centerX, centerY);
        if (alpha < 1f) {
            float padding = Math.max(child.getElevation(), child.getTranslationZ())
                    + 24f * mHost.getResources().getDisplayMetrics().density;
            canvas.saveLayerAlpha(item.bounds.left - padding, item.bounds.top - padding,
                    item.bounds.right + padding, item.bounds.bottom + padding,
                    Math.round(255f * alpha));
        }
        return saveCount;
    }

    void getTouchPosition(View child, float x, float y, float[] out) {
        out[0] = x;
        out[1] = y;
        if (!mEnabled || mExpansion <= 0f || mSwiping || mLaunching
                || mFinishedForInteraction) return;
        ItemMotion item = mItems.get(child);
        if (item == null || !item.active || item.isAt(1f)) return;
        float appearance = item.appearance.value.getValue();
        out[0] = MiuiQsMotion.inversePosition(x, item.bounds.centerX(), mCornerX,
                item.horizontal.value.getValue(), appearance);
        out[1] = MiuiQsMotion.inversePosition(y, item.bounds.centerY(), mHeaderY,
                item.vertical.value.getValue(), appearance);
    }

    void remove(View child) {
        ItemMotion item = mItems.remove(child);
        if (item != null) item.snap(1f);
    }

    void cancel() {
        for (int i = 0; i < mItems.size(); i++) mItems.valueAt(i).snap(1f);
        mItems.clear();
        mRunning = false;
        mHost.invalidate();
    }

    private final class ItemMotion {
        final RectF bounds = new RectF();
        final Progress appearance;
        final Progress horizontal;
        final Progress vertical;
        final Runnable reveal = this::animate;
        boolean active;
        boolean delaying;
        float distance;
        float target = Float.NaN;

        ItemMotion(float initial) {
            appearance = new Progress(mHost, initial);
            horizontal = new Progress(mHost, initial);
            vertical = new Progress(mHost, initial);
        }

        void update(boolean visible, float newDistance) {
            active = visible;
            float next = mFinishedForInteraction ? 1f : mEnabled
                    ? MiuiQsMotion.itemTarget(mExpansion <= 0f, mExpansion >= 1f, mShowing,
                            active, true, mSwiping) : 1f;
            if (!mEnabled || mExpansion <= 0f || mSwiping || !active || mFinishedForInteraction
                    || !ValueAnimator.areAnimatorsEnabled()) {
                snap(next);
                target = next;
                return;
            }
            if (target == next) return;
            mHost.removeCallbacks(reveal);
            delaying = false;
            distance = newDistance;
            target = next;
            if (isAt(next)) return;
            if (next == 1f && isAt(0f)) {
                delaying = true;
                mHost.postDelayed(reveal, MiuiQsMotion.showDelayMillis(distance));
            } else {
                animate();
            }
        }

        void animate() {
            delaying = false;
            appearance.animate(target, MiuiQsMotion.appearanceSpring(distance, target == 1f));
            horizontal.animate(target, MiuiQsMotion.horizontalSpring(distance));
            vertical.animate(target, MiuiQsMotion.verticalSpring(distance));
        }

        void snap(float value) {
            mHost.removeCallbacks(reveal);
            delaying = false;
            appearance.snap(value);
            horizontal.snap(value);
            vertical.snap(value);
        }

        boolean isAt(float value) {
            return appearance.value.getValue() == value && horizontal.value.getValue() == value
                    && vertical.value.getValue() == value;
        }

        boolean isRunning() {
            return active && (delaying || appearance.animation.isRunning()
                    || horizontal.animation.isRunning() || vertical.animation.isRunning()
                    || !isAt(target));
        }
    }

    private static final class Progress {
        final FloatValueHolder value;
        final SpringAnimation animation;

        Progress(View host, float initial) {
            value = new FloatValueHolder(initial);
            animation = new SpringAnimation(value);
            animation.setSpring(new SpringForce(initial));
            animation.setMinimumVisibleChange(MiuiQsMotion.VISIBILITY_THRESHOLD);
            animation.addUpdateListener((spring, current, velocity) -> host.invalidate());
        }

        void animate(float target, MiuiQsMotion.SpringParameters parameters) {
            animation.getSpring().setDampingRatio(parameters.dampingRatio)
                    .setStiffness(parameters.stiffness);
            animation.animateToFinalPosition(target);
        }

        void snap(float target) {
            if (!animation.isRunning() && value.getValue() == target) return;
            animation.cancel();
            value.setValue(target);
            animation.setStartValue(target);
            animation.setStartVelocity(0f);
            animation.getSpring().setFinalPosition(target);
        }
    }
}
