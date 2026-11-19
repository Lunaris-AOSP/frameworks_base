/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.edgego;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.function.Consumer;

final class EdgeGoLevelView extends View {
    private final boolean vertical;
    private final int foreground, background;
    private final String style;
    private final Consumer<Float> changed;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final SpringAnimation press;
    private ValueAnimator levelAnimation;
    private Consumer<Boolean> tracking;
    private float level, displayedLevel, pressAmount;
    private boolean touching;

    EdgeGoLevelView(Context context, boolean vertical, String style, int foreground, int background,
            float level, Consumer<Float> changed) {
        super(context);
        this.vertical = vertical;
        this.style = style;
        this.foreground = foreground;
        this.background = background;
        this.level = displayedLevel = Math.max(0, Math.min(1, level));
        this.changed = changed;
        setFocusable(true);
        setClickable(true);
        setStateDescription(Math.round(this.level * 100) + "%");
        press = new SpringAnimation(this, new FloatPropertyCompat<EdgeGoLevelView>("edgeGoSliderPress") {
            @Override public float getValue(EdgeGoLevelView view) { return view.pressAmount; }
            @Override public void setValue(EdgeGoLevelView view, float value) { view.pressAmount = value; view.invalidate(); }
        });
        press.setSpring(new SpringForce(0).setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        int action = event.getActionMasked();
        if (event.getPointerCount() != 1 || action == MotionEvent.ACTION_CANCEL) {
            setTouching(false);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); return true;
        }
        if (action != MotionEvent.ACTION_DOWN && !touching) return true;
        if (action == MotionEvent.ACTION_DOWN) {
            if (levelAnimation != null) levelAnimation.cancel();
            displayedLevel = level;
            setTouching(true);
            performHapticFeedback(HapticFeedbackConstants.GESTURE_START);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        float margin = 12 * getResources().getDisplayMetrics().density;
        float value = vertical ? 1 - (event.getY() - margin) / Math.max(1, getHeight() - 2 * margin)
                : (event.getX() - margin) / Math.max(1, getWidth() - 2 * margin);
        update(value);
        if (action == MotionEvent.ACTION_UP) {
            setTouching(false);
            performClick();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        invalidate();
        return true;
    }

    private void update(float value) {
        if (!Float.isFinite(value)) return;
        float next = Math.max(0, Math.min(1, value));
        if (next == level) return;
        if (levelAnimation != null) levelAnimation.cancel();
        if (next == 0 || next == 1) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        level = displayedLevel = next;
        changed.accept(level);
        setStateDescription(Math.round(level * 100) + "%"); invalidate();
    }

    void setLevel(float value) {
        if (!touching && Float.isFinite(value) && Math.abs(level - value) > .001f) {
            if (levelAnimation != null) levelAnimation.cancel();
            level = Math.max(0, Math.min(1, value));
            setStateDescription(Math.round(level * 100) + "%");
            if (ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow()) {
                levelAnimation = ValueAnimator.ofFloat(displayedLevel, level);
                levelAnimation.setDuration(140);
                levelAnimation.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1));
                levelAnimation.addUpdateListener(animation -> { displayedLevel = (float) animation.getAnimatedValue(); invalidate(); });
                levelAnimation.start();
            } else { displayedLevel = level; invalidate(); }
        }
    }

    void setTrackingListener(Consumer<Boolean> listener) { tracking = listener; }
    float level() { return level; }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled && press != null) setTouching(false);
        invalidate();
    }

    private void setTouching(boolean value) {
        if (touching == value) return;
        touching = value;
        setPressed(value);
        if (ValueAnimator.areAnimatorsEnabled()) press.animateToFinalPosition(value ? 1 : 0);
        else { press.cancel(); pressAmount = value ? 1 : 0; invalidate(); }
        if (tracking != null) tracking.accept(value);
    }

    @Override protected void onDetachedFromWindow() {
        press.cancel();
        if (levelAnimation != null) levelAnimation.cancel();
        touching = false;
        super.onDetachedFromWindow();
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.SeekBar.class.getName());
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, 1, level));
        if (isEnabled()) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (!isEnabled()) return super.performAccessibilityAction(action, arguments);
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && arguments != null) {
            update(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)); return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            update(level + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? .05f : -.05f)); return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (!isEnabled()) return super.onKeyDown(key, event);
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_RIGHT) { update(level + .05f); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_LEFT) { update(level - .05f); return true; }
        return super.onKeyDown(key, event);
    }

    @Override protected void onDraw(Canvas canvas) {
        float dp = getResources().getDisplayMetrics().density;
        float thickness = (style.equals("minimal") ? 3 : style.equals("edge_rail") ? 8 : 32 + 4 * pressAmount) * dp;
        if (vertical) bounds.set((getWidth() - thickness) / 2, 12 * dp, (getWidth() + thickness) / 2, getHeight() - 12 * dp);
        else bounds.set(12 * dp, (getHeight() - thickness) / 2, getWidth() - 12 * dp, (getHeight() + thickness) / 2);
        int save = isEnabled() ? canvas.save() : canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), 96);
        drawLevel(canvas, paint, bounds, displayedLevel, vertical, foreground, background);
        float position = vertical ? bounds.bottom - bounds.height() * displayedLevel
                : bounds.left + bounds.width() * displayedLevel;
        float halfLength = (style.equals("system_pill") ? 22 + 2 * pressAmount : 10 + 2 * pressAmount) * dp;
        float halfWidth = (2 + pressAmount) * dp;
        paint.setColor(style.equals("system_pill") ? EdgeGoUi.contrastColor(foreground) : foreground);
        if (vertical) canvas.drawRoundRect(bounds.centerX() - halfLength, position - halfWidth,
                bounds.centerX() + halfLength, position + halfWidth, halfWidth, halfWidth, paint);
        else canvas.drawRoundRect(position - halfWidth, bounds.centerY() - halfLength,
                position + halfWidth, bounds.centerY() + halfLength, halfWidth, halfWidth, paint);
        canvas.restoreToCount(save);
    }

    static void drawLevel(Canvas canvas, Paint paint, RectF bounds, float level, boolean vertical,
            int foreground, int background) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(background);
        float radius = Math.min(bounds.width(), bounds.height()) / 2;
        canvas.drawRoundRect(bounds, radius, radius, paint);
        int save = canvas.save();
        if (vertical) canvas.clipRect(bounds.left, bounds.bottom - bounds.height() * level, bounds.right, bounds.bottom);
        else canvas.clipRect(bounds.left, bounds.top, bounds.left + bounds.width() * level, bounds.bottom);
        paint.setColor(foreground);
        canvas.drawRoundRect(bounds, radius, radius, paint);
        canvas.restoreToCount(save);
    }
}
