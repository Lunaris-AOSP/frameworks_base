/*
 * Copyright (C) 2026 The PenguinOS Project
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util.lunaris;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.AppGlobals;
import android.content.pm.ApplicationInfo;
import android.os.Looper;
import android.provider.Settings;
import android.util.AndroidRuntimeException;
import android.util.SparseArray;
import android.view.RenderNodeAnimator;
import android.view.View;
import android.view.animation.AnimationUtils;

import java.util.WeakHashMap;

/** @hide */
public final class Luminous {
    public static final int TRANSLATION_X = RenderNodeAnimator.TRANSLATION_X;
    public static final int TRANSLATION_Y = RenderNodeAnimator.TRANSLATION_Y;
    public static final int TRANSLATION_Z = RenderNodeAnimator.TRANSLATION_Z;
    public static final int SCALE_X = RenderNodeAnimator.SCALE_X;
    public static final int SCALE_Y = RenderNodeAnimator.SCALE_Y;
    public static final int ROTATION = RenderNodeAnimator.ROTATION;
    public static final int ALPHA = RenderNodeAnimator.ALPHA;

    private static final WeakHashMap<View, SparseArray<Run>> sRuns = new WeakHashMap<>();

    private Luminous() {}

    public static void spring(View view, int property, float target, float stiffness,
            float dampingRatio) {
        spring(view, property, target, stiffness, dampingRatio, Float.NaN, null);
    }

    public static void spring(View view, int property, float target, float stiffness,
            float dampingRatio, float velocity, @Nullable Runnable endAction) {
        checkThread();
        if (!Float.isFinite(target) || !Float.isFinite(stiffness) || stiffness <= 0
                || !Float.isFinite(dampingRatio) || dampingRatio < 0
                || (!Float.isNaN(velocity) && !Float.isFinite(velocity))) {
            throw new IllegalArgumentException("Invalid spring parameters");
        }
        long now = AnimationUtils.currentAnimationTimeMillis();
        float from = get(view, property);
        float speed = Float.isNaN(velocity) ? 0f : velocity;
        SparseArray<Run> runs = sRuns.get(view);
        Run old = runs == null ? null : runs.get(property);
        if (old != null) {
            from = old.value(now);
            if (Float.isNaN(velocity)) speed = old.velocity(now);
            cancel(view, property);
        }
        float distance = target - from;
        if (!Float.isFinite(from) || !Float.isFinite(distance)) {
            throw new IllegalArgumentException("Invalid spring start value or distance");
        }
        float durationScale = ValueAnimator.getDurationScale();
        if (Math.abs(distance) < 1e-4f || durationScale <= 0 || !view.isAttachedToWindow()) {
            set(view, property, target);
            if (endAction != null) endAction.run();
            return;
        }

        SpringInterpolator spring =
                new SpringInterpolator(stiffness, dampingRatio,
                        (float) ((double) speed * durationScale / distance));
        Animator animator;
        if (view.isHardwareAccelerated() && offloadsViewAnimations(view)) {
            RenderNodeAnimator nativeAnimator = new RenderNodeAnimator(property, target);
            nativeAnimator.setTarget(view);
            nativeAnimator.setStartValue(from);
            animator = nativeAnimator;
        } else {
            ValueAnimator uiAnimator = ValueAnimator.ofFloat(from, target);
            uiAnimator.addUpdateListener(value -> set(view, property, (float) value.getAnimatedValue()));
            animator = uiAnimator;
        }
        animator.setDuration(spring.getDurationMillis());
        animator.setInterpolator(spring);
        Run run = new Run(animator, spring, from, target, durationScale, now, property);
        runs = sRuns.get(view);
        if (runs == null) {
            runs = new SparseArray<>();
            sRuns.put(view, runs);
        }
        runs.put(property, run);
        view.addOnAttachStateChangeListener(run);
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationStart(Animator animation) {
                run.startMillis = AnimationUtils.currentAnimationTimeMillis();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                removeRun(view, property, run);
                if (!mCancelled && !run.replaced && endAction != null) endAction.run();
            }
        });
        animator.start();
    }

    public static boolean isRunning(View view, int property) {
        checkThread();
        SparseArray<Run> runs = sRuns.get(view);
        return runs != null && runs.get(property) != null;
    }

    public static void cancel(View view, int property) {
        checkThread();
        SparseArray<Run> runs = sRuns.get(view);
        Run run = runs == null ? null : runs.get(property);
        if (run == null) return;
        float value = run.value(AnimationUtils.currentAnimationTimeMillis());
        run.replaced = true;
        removeRun(view, property, run);
        run.animator.cancel();
        set(view, property, value);
    }

    public static void cancelAll(View view) {
        checkThread();
        SparseArray<Run> runs = sRuns.get(view);
        if (runs == null) return;
        for (int i = runs.size() - 1; i >= 0; i--) {
            cancel(view, runs.keyAt(i));
        }
    }

    public static boolean offloadsViewAnimations(View view) {
        if (AppGlobals.getIntCoreSetting(Settings.Global.LUNARIS_LUMINOUS_ANIMATIONS, 1) == 0) {
            return false;
        }
        ApplicationInfo info = view.getContext().getApplicationInfo();
        return info != null && (info.isSystemApp() || info.isUpdatedSystemApp());
    }

    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new AndroidRuntimeException("Luminous animations must run on the main thread");
        }
    }

    private static void removeRun(View view, int property, Run run) {
        view.removeOnAttachStateChangeListener(run);
        SparseArray<Run> runs = sRuns.get(view);
        if (runs != null && runs.get(property) == run) {
            runs.remove(property);
            if (runs.size() == 0) sRuns.remove(view);
        }
    }

    private static float get(View view, int property) {
        switch (property) {
            case TRANSLATION_X: return view.getTranslationX();
            case TRANSLATION_Y: return view.getTranslationY();
            case TRANSLATION_Z: return view.getTranslationZ();
            case SCALE_X: return view.getScaleX();
            case SCALE_Y: return view.getScaleY();
            case ROTATION: return view.getRotation();
            case ALPHA: return view.getAlpha();
            default: throw new IllegalArgumentException("Unsupported property " + property);
        }
    }

    private static void set(View view, int property, float value) {
        switch (property) {
            case TRANSLATION_X: view.setTranslationX(value); break;
            case TRANSLATION_Y: view.setTranslationY(value); break;
            case TRANSLATION_Z: view.setTranslationZ(value); break;
            case SCALE_X: view.setScaleX(value); break;
            case SCALE_Y: view.setScaleY(value); break;
            case ROTATION: view.setRotation(value); break;
            case ALPHA: view.setAlpha(value); break;
            default: throw new IllegalArgumentException("Unsupported property " + property);
        }
    }

    private static final class Run implements View.OnAttachStateChangeListener {
        final Animator animator;
        final SpringInterpolator spring;
        final float start;
        final float target;
        final long scaledDurationMillis;
        final int property;
        long startMillis;
        boolean replaced;

        Run(Animator animator, SpringInterpolator spring, float start, float target,
                float durationScale, long startMillis, int property) {
            this.animator = animator;
            this.spring = spring;
            this.start = start;
            this.target = target;
            scaledDurationMillis = Math.max(1, (long) (spring.getDurationMillis() * durationScale));
            this.startMillis = startMillis;
            this.property = property;
        }

        float fraction(long now) {
            if (animator instanceof ValueAnimator) {
                return Math.max(0f, Math.min(1f,
                        (float) ((ValueAnimator) animator).getCurrentPlayTime()
                                / spring.getDurationMillis()));
            }
            return Math.max(0f, Math.min(1f, (float) (now - startMillis) / scaledDurationMillis));
        }

        float value(long now) {
            if (animator instanceof ValueAnimator) {
                Object value = ((ValueAnimator) animator).getAnimatedValue();
                return value instanceof Float ? (Float) value : start;
            }
            return start + (target - start) * spring.getInterpolation(fraction(now));
        }

        float velocity(long now) {
            long duration = animator instanceof ValueAnimator
                    ? (long) (spring.getDurationMillis() * ValueAnimator.getDurationScale())
                    : scaledDurationMillis;
            if (duration <= 0) return 0f;
            double timeScale = spring.getDurationSeconds() * 1000 / duration;
            return (float) ((target - start)
                    * spring.velocityAt(fraction(now) * spring.getDurationSeconds()) * timeScale);
        }

        @Override
        public void onViewAttachedToWindow(View view) {}

        @Override
        public void onViewDetachedFromWindow(View view) {
            if (!replaced) cancel(view, property);
        }
    }
}
