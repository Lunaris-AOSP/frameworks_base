/*
 * SPDX-FileCopyrightText: 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lunaris.clockmaker.spatial;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.PowerManager;
import android.view.Choreographer;

public final class SpatialWallpaperMotion implements ParallaxTiltSensor.Listener {
    public interface Listener {
        void onPose(float horizontal, float vertical, float scale);
    }

    public static final float OVERSCAN = 1.08f;
    private static final float THRESHOLD = 0.0005f;
    private static final float VELOCITY_THRESHOLD = 0.005f;
    private static final float SPRING_FREQUENCY = 18f;
    private final ParallaxTiltSensor sensor;
    private final PowerManager power;
    private final Listener listener;
    private boolean requested;
    private boolean active;
    private boolean sensing;
    private boolean scheduled;
    private float sensitivity = 1f;
    private float x;
    private float y;
    private float velocityX;
    private float velocityY;
    private float targetX;
    private float targetY;
    private float latestX;
    private float latestY;
    private float scale = 1f;
    private long lastFrame;
    private ValueAnimator animator;

    private final Choreographer.FrameCallback frame = time -> {
        scheduled = false;
        if (!active) return;
        if (!interactive()) {
            setActive(false, false);
            return;
        }
        float seconds = lastFrame == 0 || time <= lastFrame ? 1f / 60f
                : Math.min(0.1f, (time - lastFrame) / 1_000_000_000f);
        lastFrame = time;
        float decay = (float) Math.exp(-SPRING_FREQUENCY * seconds);
        float errorX = x - targetX;
        float errorY = y - targetY;
        float stepX = (velocityX + SPRING_FREQUENCY * errorX) * seconds;
        float stepY = (velocityY + SPRING_FREQUENCY * errorY) * seconds;
        x = targetX + (errorX + stepX) * decay;
        y = targetY + (errorY + stepY) * decay;
        velocityX = (velocityX - SPRING_FREQUENCY * stepX) * decay;
        velocityY = (velocityY - SPRING_FREQUENCY * stepY) * decay;
        if (x < -1f || x > 1f) {
            x = Math.max(-1f, Math.min(1f, x));
            velocityX = 0f;
        }
        if (y < -1f || y > 1f) {
            y = Math.max(-1f, Math.min(1f, y));
            velocityY = 0f;
        }
        boolean settled = isSettled();
        if (settled) {
            x = targetX;
            y = targetY;
            velocityX = velocityY = 0f;
            lastFrame = 0;
        }
        dispatchPose();
        if (!settled) schedule();
    };

    public SpatialWallpaperMotion(Context context, Listener listener) {
        sensor = ParallaxTiltSensor.getInstance(context.getApplicationContext());
        power = context.getSystemService(PowerManager.class);
        this.listener = listener;
    }

    public void setSensitivity(int percent) {
        sensitivity = Math.max(0, Math.min(100, percent)) / 100f;
        setActive(requested, true);
        if (active) {
            targetX = latestX * sensitivity;
            targetY = latestY * sensitivity;
            if (!isSettled()) schedule();
        }
    }

    public void setActive(boolean enabled, boolean animate) {
        requested = enabled;
        enabled &= sensitivity > 0f && interactive();
        if (active == enabled) {
            if (!enabled && !animate) reset();
            return;
        }
        cancelAnimation();
        cancelFrame();
        velocityX = velocityY = 0f;
        active = enabled;
        if (enabled) {
            latestX = latestY = targetX = targetY = x = y = 0f;
            sensing = sensor.addListener(this);
            if (!sensing) {
                active = false;
                reset();
                return;
            }
            if (animate) animateTo(OVERSCAN, 320, false);
            else scale = OVERSCAN;
            dispatchPose();
        } else {
            if (sensing) sensor.removeListener(this);
            sensing = false;
            targetX = targetY = 0f;
            if (animate && interactive()) animateTo(1f, 120, true);
            else reset();
        }
    }

    private void animateTo(float endScale, long duration, boolean resetPose) {
        float startScale = scale;
        float startX = x;
        float startY = y;
        ValueAnimator next = ValueAnimator.ofFloat(0f, 1f);
        animator = next;
        next.setDuration(duration);
        next.addUpdateListener(value -> {
            if (!interactive()) {
                setActive(false, false);
                return;
            }
            float progress = (float) value.getAnimatedValue();
            scale = startScale + (endScale - startScale) * progress;
            if (resetPose) {
                x = startX * (1f - progress);
                y = startY * (1f - progress);
            }
            dispatchPose();
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animator == animation) animator = null;
            }
        });
        next.start();
    }

    @Override public void onTiltChanged(float horizontal, float vertical) {
        if (!active || !Float.isFinite(horizontal) || !Float.isFinite(vertical)) return;
        latestX = Math.max(-1f, Math.min(1f, horizontal));
        latestY = Math.max(-1f, Math.min(1f, vertical));
        targetX = latestX * sensitivity;
        targetY = latestY * sensitivity;
        if (!isSettled()) schedule();
    }

    public void dispatchPose() {
        float amount = Math.max(0f, Math.min(1f, (scale - 1f) / (OVERSCAN - 1f)));
        listener.onPose(x * amount, y * amount, scale);
    }
    public boolean isActive() { return active; }
    public void onDisplayRotationChanged(int rotation) { sensor.onDisplayRotationChanged(rotation); }

    public void destroy() {
        requested = active = false;
        if (sensing) sensor.removeListener(this);
        sensing = false;
        reset();
    }

    private boolean interactive() { return power == null || power.isInteractive(); }

    private void reset() {
        cancelAnimation();
        cancelFrame();
        x = y = targetX = targetY = latestX = latestY = 0f;
        velocityX = velocityY = 0f;
        scale = 1f;
        dispatchPose();
    }

    private void cancelAnimation() {
        if (animator == null) return;
        animator.removeAllUpdateListeners();
        animator.removeAllListeners();
        animator.cancel();
        animator = null;
    }

    private void cancelFrame() {
        Choreographer.getInstance().removeFrameCallback(frame);
        scheduled = false;
        lastFrame = 0;
    }

    private void schedule() {
        if (!active || scheduled) return;
        scheduled = true;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private boolean isSettled() {
        return Math.abs(targetX - x) <= THRESHOLD && Math.abs(targetY - y) <= THRESHOLD
                && Math.abs(velocityX) <= VELOCITY_THRESHOLD
                && Math.abs(velocityY) <= VELOCITY_THRESHOLD;
    }
}
