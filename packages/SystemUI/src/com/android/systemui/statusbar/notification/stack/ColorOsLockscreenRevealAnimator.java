/*
 * Copyright (C) 2026 Lunaris AOSP
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

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.ArrayMap;
import android.util.MathUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.android.internal.dynamicanimation.animation.FloatValueHolder;
import com.android.internal.dynamicanimation.animation.SpringAnimation;
import com.android.internal.dynamicanimation.animation.SpringForce;
import com.android.systemui.axdynamicbar.ui.AxDynamicBarColorOsController;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableView;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * Animates lockscreen notification cards and the lockscreen media card directly out of (and back
 * into) the bottom Dynamic Bar pill bounds with a staggered spring cascade matching ColorOS.
 */
final class ColorOsLockscreenRevealAnimator {
    private static final float DEFAULT_PILL_WIDTH_DP = 180f;
    private static final float DEFAULT_PILL_HEIGHT_DP = 58f;
    private static final float ALPHA_RAMP_WINDOW = 0.16f;
    private static final float VISIBILITY_THRESHOLD = 0.001f;
    private static final float REVEAL_DAMPING_Y = 0.62f;
    private static final float REVEAL_DAMPING_SCALE = 0.66f;
    private static final float REVEAL_MAX_OVERSHOOT_DP = 11f;
    private static final float REVEAL_PEAK_OVERSHOOT_RATIO = 0.085f;
    private static final float REVEAL_SQUISH_WIDTH_FACTOR = 0.48f;
    private static final float REVEAL_SQUISH_HEIGHT_FACTOR = 0.35f;
    private static final float COLLAPSE_DAMPING_Y = 0.90f;
    private static final float COLLAPSE_DAMPING_SCALE = 0.92f;
    private static final float REVEAL_BLUR_START = 0.18f;
    private static final float REVEAL_BLUR_END = 0.90f;
    private static final float COLLAPSE_BLUR_START = 0.45f;
    private static final float COLLAPSE_BLUR_END = 0.96f;
    private static final long REVEAL_STAGGER_MS = 24L;
    private static final long COLLAPSE_STAGGER_MS = 14L;

    private final NotificationStackScrollLayout mHost;
    private final ArrayMap<View, ItemMotion> mItems = new ArrayMap<>();
    private final ArrayList<View> mOrderedChildren = new ArrayList<>();
    private final RectF mPillAnchorScreen = new RectF();
    private final RectF mChildBounds = new RectF();
    private final RectF mRevealBounds = new RectF();
    private final Path mClipPath = new Path();
    private final int[] mHostLocation = new int[2];

    private boolean mActive;
    private boolean mRevealed;
    private boolean mEffectiveActive;
    private boolean mRunning;
    private boolean mSettledRevealed;
    private boolean mFinishedForInteraction;
    private boolean mClippingRelaxed;
    private boolean mSavedHostClipChildren;
    private boolean mSavedHostClipToPadding;
    private ViewGroup mClippedParent;
    private boolean mSavedParentClipChildren;
    private boolean mSavedParentClipToPadding;
    private float mAnchorX;
    private float mAnchorY;
    private float mPillWidth;
    private float mPillHeight;

    ColorOsLockscreenRevealAnimator(NotificationStackScrollLayout host) {
        mHost = host;
    }

    void updateState(boolean active, boolean revealed) {
        final boolean changed = mActive != active || mRevealed != revealed;
        mActive = active;
        mRevealed = revealed;
        if (!active) {
            cancel();
            return;
        }
        if (changed) {
            mFinishedForInteraction = false;
            mSettledRevealed = false;
            if (mEffectiveActive) {
                setClippingRelaxed(true);
            }
            mHost.invalidate();
        }
    }

    void prepareFrame(boolean onLockscreen, boolean dozing, boolean pulsing,
            float dozeAmount, float fractionToShade) {
        final boolean effectiveActive = mActive && onLockscreen && !pulsing
                && fractionToShade == 0f
                && mHost.getVisibility() == View.VISIBLE;
        mEffectiveActive = effectiveActive;
        if (!effectiveActive) {
            if (mRunning || !mItems.isEmpty() || mClippingRelaxed) {
                snapAllAndClear();
            }
            AxDynamicBarColorOsController.updateMediaBlurFraction(1f);
            return;
        }

        updateAnchorCoordinates();

        mOrderedChildren.clear();
        for (int i = 0; i < mHost.getChildCount(); i++) {
            View child = mHost.getChildAt(i);
            if (!(child instanceof ExpandableView expandable)) {
                continue;
            }
            ExpandableViewState state = expandable.getViewState();
            if (child.getVisibility() != View.VISIBLE
                    || (state != null && (state.hidden || state.gone || state.getAlpha() <= 0f))
                    || child.getWidth() <= 0
                    || expandable.getActualHeight() <= 0) {
                continue;
            }
            mOrderedChildren.add(child);
        }
        mOrderedChildren.sort(Comparator.comparingDouble(View::getY));

        for (int i = mItems.size() - 1; i >= 0; i--) {
            View v = mItems.keyAt(i);
            if (!mOrderedChildren.contains(v)) {
                mItems.valueAt(i).snap(1f);
                applyTransientBlur(v, 1f);
                mItems.removeAt(i);
            }
        }

        final int count = mOrderedChildren.size();
        final boolean allowMotion = ValueAnimator.areAnimatorsEnabled()
                && !dozing && dozeAmount == 0f;
        boolean hasMediaChild = false;
        float mediaBlurFraction = mRevealed ? 1f : 0f;
        for (int i = 0; i < count; i++) {
            View child = mOrderedChildren.get(i);
            ExpandableView expandable = (ExpandableView) child;
            float left = child.getX() + child.getPivotX() * (1f - child.getScaleX());
            float top = child.getY() + child.getPivotY() * (1f - child.getScaleY());
            mChildBounds.set(
                    left,
                    top,
                    left + child.getWidth() * child.getScaleX(),
                    top + expandable.getActualHeight() * child.getScaleY());

            ItemMotion item = mItems.get(child);
            if (item == null) {
                final float initial = (mRevealed && (mSettledRevealed || mFinishedForInteraction
                        || !allowMotion)) ? 1f : 0f;
                item = new ItemMotion(initial);
                mItems.put(child, item);
            }
            item.bounds.set(mChildBounds);
            item.update(mRevealed, i, count, allowMotion);
            final float blurFraction = computeBlurFraction(item);
            applyTransientBlur(child, blurFraction);
            if (child instanceof MediaContainerView) {
                hasMediaChild = true;
                mediaBlurFraction = blurFraction;
            }
        }
        AxDynamicBarColorOsController.updateMediaBlurFraction(
                hasMediaChild ? mediaBlurFraction : (mRevealed ? 1f : 0f));

        mRunning = false;
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.valueAt(i).isRunning()) {
                mRunning = true;
                break;
            }
        }
        setClippingRelaxed(mRunning);
        if (mRevealed && !mRunning && count > 0) {
            mSettledRevealed = true;
        } else if (!mRevealed) {
            mSettledRevealed = false;
        }
    }

    boolean isRunning() {
        return mEffectiveActive && mRunning;
    }

    boolean isUnclipped() {
        return mClippingRelaxed || isRunning();
    }

    boolean shouldBlockTouch() {
        return mEffectiveActive && !mRevealed;
    }

    boolean isRevealedAndActive() {
        return mEffectiveActive && mRevealed;
    }

    boolean shouldDraw(View child) {
        if (!mEffectiveActive || !(child instanceof ExpandableView)) {
            return true;
        }
        ItemMotion item = mItems.get(child);
        if (item == null) {
            return mRevealed;
        }
        return clamp(item.scale.value.getValue()) > 0.01f;
    }

    int prepareCanvas(Canvas canvas, View child) {
        if (!mEffectiveActive || !(child instanceof ExpandableView)) {
            return -1;
        }
        ItemMotion item = mItems.get(child);
        if (item == null || item.isAt(1f)) {
            return -1;
        }
        final float rowW = item.bounds.width();
        final float rowH = item.bounds.height();
        if (rowW <= 0f || rowH <= 0f) {
            return -1;
        }

        final float rawTY = item.translate.value.getValue();
        final float tY = clamp(rawTY);
        final float rawScale = item.scale.value.getValue();
        final float tS = clamp(rawScale);
        final float curCenterX = MathUtils.lerp(mAnchorX, item.bounds.centerX(), tY);
        float curCenterY = MathUtils.lerp(mAnchorY, item.bounds.centerY(), tY);
        if (rawTY > 1f) {
            final float density = mHost.getResources().getDisplayMetrics().density;
            final float maxOvershootPx = REVEAL_MAX_OVERSHOOT_DP * density;
            final float travelY = item.bounds.centerY() - mAnchorY;
            final float peakOvershoot = Math.abs(travelY) * REVEAL_PEAK_OVERSHOOT_RATIO;
            final float overshootScale = peakOvershoot > maxOvershootPx
                    ? (maxOvershootPx / peakOvershoot) : 1f;
            curCenterY += (rawTY - 1f) * travelY * overshootScale;
        }
        final float scaleOvershoot = Math.max(0f, rawScale - 1f);
        final float startW = Math.min(mPillWidth, rowW);
        final float startH = Math.min(mPillHeight, rowH);
        final float curW = MathUtils.lerp(startW, rowW, tS)
                * (1f + scaleOvershoot * REVEAL_SQUISH_WIDTH_FACTOR);
        final float curH = MathUtils.lerp(startH, rowH, tS)
                * (1f - scaleOvershoot * REVEAL_SQUISH_HEIGHT_FACTOR);
        mRevealBounds.set(
                curCenterX - curW * 0.5f,
                curCenterY - curH * 0.5f,
                curCenterX + curW * 0.5f,
                curCenterY + curH * 0.5f);

        final int saveCount = canvas.save();
        final float alpha = clamp(tS / ALPHA_RAMP_WINDOW);
        if (alpha < 1f) {
            canvas.saveLayerAlpha(mRevealBounds, Math.round(255f * alpha));
        }
        final float cardRadius = mHost.getResources()
                .getDimension(R.dimen.notification_corner_radius);
        final float cornerRadius = MathUtils.lerp(startH * 0.5f, cardRadius, tS);
        mClipPath.rewind();
        mClipPath.addRoundRect(mRevealBounds, cornerRadius, cornerRadius, Path.Direction.CW);
        canvas.clipPath(mClipPath);
        canvas.translate(mRevealBounds.left, mRevealBounds.top);
        canvas.scale(mRevealBounds.width() / rowW, mRevealBounds.height() / rowH);
        canvas.translate(-item.bounds.left, -item.bounds.top);
        return saveCount;
    }

    boolean finishForInteraction() {
        if (!mEffectiveActive || !mRevealed || !mRunning) {
            return false;
        }
        mFinishedForInteraction = true;
        for (int i = 0; i < mItems.size(); i++) {
            ItemMotion item = mItems.valueAt(i);
            item.snap(1f);
            item.target = 1f;
            applyTransientBlur(mItems.keyAt(i), 1f);
        }
        AxDynamicBarColorOsController.updateMediaBlurFraction(1f);
        mRunning = false;
        setClippingRelaxed(false);
        mHost.invalidate();
        return true;
    }

    void remove(View child) {
        ItemMotion item = mItems.remove(child);
        if (item != null) {
            item.snap(1f);
        }
        applyTransientBlur(child, 1f);
    }

    void cancel() {
        mEffectiveActive = false;
        mFinishedForInteraction = false;
        mSettledRevealed = false;
        snapAllAndClear();
        mHost.invalidate();
    }

    private void snapAllAndClear() {
        for (int i = 0; i < mItems.size(); i++) {
            mItems.valueAt(i).snap(1f);
            applyTransientBlur(mItems.keyAt(i), 1f);
        }
        mItems.clear();
        mOrderedChildren.clear();
        mRunning = false;
        setClippingRelaxed(false);
        AxDynamicBarColorOsController.updateMediaBlurFraction(1f);
    }

    private void setClippingRelaxed(boolean relaxed) {
        if (mClippingRelaxed == relaxed) {
            return;
        }
        mClippingRelaxed = relaxed;
        if (relaxed) {
            mSavedHostClipChildren = mHost.getClipChildren();
            mSavedHostClipToPadding = mHost.getClipToPadding();
            mHost.setClipChildren(false);
            mHost.setClipToPadding(false);
            ViewParent parent = mHost.getParent();
            if (parent instanceof ViewGroup vg) {
                mClippedParent = vg;
                mSavedParentClipChildren = vg.getClipChildren();
                mSavedParentClipToPadding = vg.getClipToPadding();
                vg.setClipChildren(false);
                vg.setClipToPadding(false);
                vg.invalidate();
            }
        } else {
            mHost.setClipChildren(mSavedHostClipChildren);
            mHost.setClipToPadding(mSavedHostClipToPadding);
            if (mClippedParent != null) {
                mClippedParent.setClipChildren(mSavedParentClipChildren);
                mClippedParent.setClipToPadding(mSavedParentClipToPadding);
                mClippedParent.invalidate();
                mClippedParent = null;
            }
        }
        mHost.updateClipping();
    }

    private void updateAnchorCoordinates() {
        mHost.getLocationOnScreen(mHostLocation);
        final float density = mHost.getResources().getDisplayMetrics().density;
        AxDynamicBarColorOsController controller = AxDynamicBarColorOsController.getOrNull();
        if (controller != null && controller.getPillAnchorRect(mPillAnchorScreen)) {
            mAnchorX = mPillAnchorScreen.centerX() - mHostLocation[0];
            mAnchorY = mPillAnchorScreen.centerY() - mHostLocation[1];
            mPillWidth = Math.max(48f * density, mPillAnchorScreen.width());
            mPillHeight = Math.max(36f * density, mPillAnchorScreen.height());
        } else {
            mAnchorX = mHost.getWidth() * 0.5f;
            mAnchorY = mHost.getHeight() * 0.88f;
            mPillWidth = DEFAULT_PILL_WIDTH_DP * density;
            mPillHeight = DEFAULT_PILL_HEIGHT_DP * density;
        }
    }

    private float computeBlurFraction(ItemMotion item) {
        if (!mEffectiveActive || item == null || item.isAt(1f)) {
            return 1f;
        }
        if (!mRevealed && item.isAt(0f)) {
            return 0f;
        }
        final float tS = clamp(item.scale.value.getValue());
        return mRevealed
                ? smoothstep(REVEAL_BLUR_START, REVEAL_BLUR_END, tS)
                : smoothstep(COLLAPSE_BLUR_START, COLLAPSE_BLUR_END, tS);
    }

    private static void applyTransientBlur(View child, float fraction) {
        if (child instanceof ActivatableNotificationView anv) {
            anv.setTransientBlurFraction(fraction);
        }
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        final float t = clamp((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private final class ItemMotion {
        final RectF bounds = new RectF();
        final Progress translate;
        final Progress scale;
        final Runnable launchAnim = this::startSprings;
        boolean delaying;
        int index;
        float target = Float.NaN;

        ItemMotion(float initial) {
            translate = new Progress(mHost, initial);
            scale = new Progress(mHost, initial);
        }

        void update(boolean revealed, int visualIndex, int totalCount, boolean animatorsEnabled) {
            final float next = revealed ? 1f : 0f;
            index = visualIndex;
            if (!animatorsEnabled || (revealed && mFinishedForInteraction)) {
                snap(next);
                target = next;
                return;
            }
            if (target == next) {
                return;
            }
            mHost.removeCallbacks(launchAnim);
            delaying = false;
            target = next;
            if (isAt(next)) {
                return;
            }
            final long delayMs;
            if (revealed) {
                delayMs = Math.min(visualIndex, 5) * REVEAL_STAGGER_MS;
            } else {
                int reverseIndex = Math.max(0, totalCount - 1 - visualIndex);
                delayMs = Math.min(reverseIndex, 4) * COLLAPSE_STAGGER_MS;
            }
            if (delayMs > 0L) {
                delaying = true;
                mHost.postDelayed(launchAnim, delayMs);
            } else {
                startSprings();
            }
        }

        private void startSprings() {
            delaying = false;
            final boolean revealing = target >= 0.5f;
            if (revealing) {
                final float stiffnessY = Math.max(250f, 400f - index * 24f);
                final float stiffnessScale = Math.max(280f, 440f - index * 22f);
                translate.animate(1f, REVEAL_DAMPING_Y, stiffnessY);
                scale.animate(1f, REVEAL_DAMPING_SCALE, stiffnessScale);
            } else {
                translate.animate(0f, COLLAPSE_DAMPING_Y, 520f);
                scale.animate(0f, COLLAPSE_DAMPING_SCALE, 560f);
            }
            mHost.invalidate();
        }

        void snap(float value) {
            mHost.removeCallbacks(launchAnim);
            delaying = false;
            translate.snap(value);
            scale.snap(value);
        }

        boolean isAt(float value) {
            return Math.abs(translate.value.getValue() - value) <= VISIBILITY_THRESHOLD
                    && Math.abs(scale.value.getValue() - value) <= VISIBILITY_THRESHOLD
                    && !translate.animation.isRunning()
                    && !scale.animation.isRunning()
                    && !delaying;
        }

        boolean isRunning() {
            return delaying || translate.animation.isRunning() || scale.animation.isRunning()
                    || (!Float.isNaN(target) && !isAt(target));
        }
    }

    private static final class Progress {
        final FloatValueHolder value;
        final SpringAnimation animation;

        Progress(View host, float initial) {
            value = new FloatValueHolder(initial);
            animation = new SpringAnimation(value);
            animation.setSpring(new SpringForce(initial));
            animation.setMinimumVisibleChange(VISIBILITY_THRESHOLD);
            animation.addUpdateListener((spring, current, velocity) -> host.invalidate());
        }

        void animate(float target, float dampingRatio, float stiffness) {
            animation.getSpring()
                    .setDampingRatio(dampingRatio)
                    .setStiffness(stiffness);
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
