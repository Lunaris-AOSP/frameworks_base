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

package com.android.systemui.shade;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;

import com.android.app.animation.Interpolators;

final class LunarisQsPanelSwipeController {
    interface Callback {
        boolean canStart(MotionEvent event);
        boolean isShowingQs();
        int width();
        void onStarted(float progress);
        void onProgress(float progress);
        void onFinished(boolean showQs);
    }

    private final Callback mCallback;
    private final int mTouchSlop;
    private final int mMinFlingVelocity;
    private final int mMaxFlingVelocity;
    private VelocityTracker mVelocityTracker;
    private ValueAnimator mAnimator;
    private boolean mCandidate;
    private boolean mDragging;
    private boolean mActive;
    private boolean mConsumeUntilUp;
    private boolean mOriginalQs;
    private boolean mDownDuringAnimation;
    private long mDownTime = -1;
    private long mSlopEventTime = -1;
    private float mDownX;
    private float mDownY;
    private float mStartProgress;
    private float mProgress;
    private int mWidth;

    LunarisQsPanelSwipeController(ViewConfiguration configuration, Callback callback) {
        mCallback = callback;
        mTouchSlop = configuration.getScaledPagingTouchSlop();
        mMinFlingVelocity = Math.max(configuration.getScaledMinimumFlingVelocity(), mTouchSlop * 10);
        mMaxFlingVelocity = configuration.getScaledMaximumFlingVelocity();
    }

    boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mDownTime == event.getDownTime()) return mDragging;
                mDownTime = event.getDownTime();
                mConsumeUntilUp = false;
                mCandidate = mCallback.canStart(event) && mCallback.width() > 0;
                if (!mCandidate) {
                    resetTouch();
                    return false;
                }
                mDownDuringAnimation = mActive;
                if (!mActive) {
                    mOriginalQs = mCallback.isShowingQs();
                    mProgress = mOriginalQs ? 1f : 0f;
                }
                mWidth = mCallback.width();
                mDownX = event.getRawX();
                mDownY = event.getRawY();
                mStartProgress = mProgress;
                mSlopEventTime = -1;
                recycleVelocityTracker();
                mVelocityTracker = VelocityTracker.obtain();
                mVelocityTracker.addMovement(event);
                mDragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (mDragging || mConsumeUntilUp) return true;
                if (!mCandidate) return false;
                if (mDownDuringAnimation && !mActive) {
                    mOriginalQs = mCallback.isShowingQs();
                    mProgress = mOriginalQs ? 1f : 0f;
                }
                float dx = event.getRawX() - mDownX;
                float dy = event.getRawY() - mDownY;
                mVelocityTracker.addMovement(event);
                if (Math.abs(dy) > mTouchSlop && Math.abs(dy) >= Math.abs(dx)) {
                    resetTouch();
                    return false;
                }
                if (Math.abs(dx) > mTouchSlop && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    if (!mActive && ((mOriginalQs && dx < 0f)
                            || (!mOriginalQs && dx > 0f))) {
                        resetTouch();
                        return false;
                    }
                    if (mSlopEventTime < 0) mSlopEventTime = event.getEventTime();
                    if (event.getEventTime() > mSlopEventTime) {
                        boolean catchingAnimation = mActive;
                        stopAnimator();
                        if (mDownDuringAnimation) {
                            mStartProgress = mProgress;
                            mDownX = event.getRawX();
                            mDownDuringAnimation = false;
                        }
                        mActive = true;
                        mDragging = true;
                        if (!catchingAnimation) mCallback.onStarted(mProgress);
                        if (mActive) updateProgress(event);
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (mDragging) {
                    mConsumeUntilUp = true;
                    settle(mOriginalQs);
                } else {
                    resetTouch();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!mDragging) resetTouch();
                break;
        }
        return mDragging || mConsumeUntilUp;
    }

    boolean onTouchEvent(MotionEvent event) {
        if (mConsumeUntilUp) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                mConsumeUntilUp = false;
            }
            return true;
        }
        if (!mDragging && !onInterceptTouchEvent(event)) return false;
        if (mVelocityTracker != null) mVelocityTracker.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                updateProgress(event);
                break;
            case MotionEvent.ACTION_UP:
                updateProgress(event);
                if (!mActive) return true;
                mVelocityTracker.computeCurrentVelocity(1000, mMaxFlingVelocity);
                float velocity = mVelocityTracker.getXVelocity();
                boolean moved = Math.abs(event.getRawX() - mDownX) > mTouchSlop;
                boolean showQs = moved && Math.abs(velocity) >= mMinFlingVelocity
                        ? velocity < 0f : mProgress >= 0.5f;
                settle(showQs);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                mConsumeUntilUp = true;
                settle(mOriginalQs);
                break;
            case MotionEvent.ACTION_CANCEL:
                settle(mOriginalQs);
                break;
        }
        return true;
    }

    void cancel() {
        stopAnimator();
        resetTouch();
        mConsumeUntilUp = false;
        if (mActive) {
            mActive = false;
            mCallback.onFinished(mOriginalQs);
        }
    }

    private void updateProgress(MotionEvent event) {
        mProgress = Math.max(0f, Math.min(1f,
                mStartProgress - (event.getRawX() - mDownX) / mWidth));
        mCallback.onProgress(mProgress);
    }

    private void settle(boolean showQs) {
        resetTouch();
        stopAnimator();
        float target = showQs ? 1f : 0f;
        if (mProgress == target) {
            finish(showQs);
            return;
        }
        mAnimator = ValueAnimator.ofFloat(mProgress, target);
        mAnimator.setDuration(Math.max(120, Math.min(320,
                Math.round(320 * Math.abs(target - mProgress)))));
        mAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mAnimator.addUpdateListener(animation -> {
            mProgress = (float) animation.getAnimatedValue();
            mCallback.onProgress(mProgress);
        });
        mAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimator = null;
                finish(showQs);
            }
        });
        mAnimator.start();
    }

    private void finish(boolean showQs) {
        if (!mActive) return;
        mProgress = showQs ? 1f : 0f;
        mCallback.onProgress(mProgress);
        if (!mActive) return;
        mActive = false;
        mCallback.onFinished(showQs);
    }

    private void stopAnimator() {
        if (mAnimator != null) {
            mAnimator.removeAllListeners();
            mAnimator.removeAllUpdateListeners();
            mAnimator.cancel();
            mAnimator = null;
        }
    }

    private void resetTouch() {
        mCandidate = false;
        mDragging = false;
        mDownDuringAnimation = false;
        recycleVelocityTracker();
    }

    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }
}
