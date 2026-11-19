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
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.util.lunaris.EdgeGoConfig;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class EdgeGoCursorView extends View {
    interface Listener { void close(); void touched(); void moved(Rect bounds); }
    private final EdgeGoConfig config;
    private final EdgeGoGestureController host;
    private final EdgeGoActions actions;
    private final EdgeGoIcons icons;
    private final Handler handler;
    private final Listener listener;
    private final boolean remote;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF header = new RectF();
    private final SpringAnimation press;
    private float pressAmount;
    private final Path cursor = new Path();
    private final List<float[]> path = new ArrayList<>();
    private final Runnable hold = this::hold;
    private final Runnable taps = this::taps;
    private float padX, padY, cursorX, cursorY, width, height, dp, downX, downY, lastX, lastY;
    private float preferredWidth, preferredHeight;
    private float replayX, replayY;
    private float topPull;
    private long lastTap;
    private int tapCount;
    private boolean moved, held, consumed, recording, dragging, movingPad, resizingPad, opening, touching, topArmed = true;
    private VelocityTracker velocity;
    private Rect safe = new Rect();
    private Rect panelArea = new Rect();

    EdgeGoCursorView(Context context, Handler handler, EdgeGoConfig config, EdgeGoGestureController host,
            EdgeGoActions actions, EdgeGoIcons icons, boolean remote, float x, float y, boolean opening,
            Listener listener) {
        super(context);
        this.config = config;
        this.host = host;
        this.actions = actions;
        this.icons = icons;
        this.handler = handler;
        this.remote = remote;
        this.listener = listener;
        press = new SpringAnimation(this, new FloatPropertyCompat<EdgeGoCursorView>("edgeGoPadPress") {
            @Override public float getValue(EdgeGoCursorView view) { return view.pressAmount; }
            @Override public void setValue(EdgeGoCursorView view, float value) { view.pressAmount = value; view.invalidate(); }
        });
        press.setSpring(new SpringForce(0).setStiffness(config.integer("animation_stiffness"))
                .setDampingRatio(config.integer("animation_damping") / 100f));
        this.opening = opening;
        dp = getResources().getDisplayMetrics().density;
        width = config.integer(remote ? "remote_cursor_pad_size_dp" : "touch_pad_size_dp") * dp;
        height = remote ? width : width * .65f;
        preferredWidth = width;
        preferredHeight = height;
        padX = x;
        padY = y;
        lastX = x;
        lastY = y;
        setContentDescription(actions.label(remote ? "remote_cursor" : "touch_pad"));
        setFocusable(true);
        setHapticFeedbackEnabled(config.bool("haptic_feedback"));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        safe = host.cursorBounds();
        JSONObject positions = EdgeGoSchedules.state(getContext(), host.userId()).optJSONObject("cursorPads");
        JSONObject saved = positions == null ? null : positions.optJSONObject(remote ? "remote_cursor" : "touch_pad");
        if (saved != null && oldWidth == 0 && oldHeight == 0) {
            if (!remote && saved.optInt("configuredWidth") == config.integer("touch_pad_size_dp")) {
                this.width = Math.min(width - 24 * dp, EdgeGoConfig.clamp(saved.optInt("width"), 96, 360) * dp);
                this.height = this.width * .65f;
            }
            if (config.bool("floating_panel_remember_position")) {
                double x = saved.optDouble("x", .5), y = saved.optDouble("y", .75);
                padX = (float) (Double.isFinite(x) ? Math.max(0, Math.min(1, x)) : .5) * width;
                padY = (float) (Double.isFinite(y) ? Math.max(0, Math.min(1, y)) : .75) * height;
            }
        }
        preferredWidth = this.width;
        preferredHeight = this.height;
        updateBounds();
        resetCursor();
        listener.moved(touchBounds());
    }

    void updateBounds() {
        if (getWidth() == 0 || getHeight() == 0) return;
        safe = host.cursorBounds();
        Rect area = panelArea = host.panelBounds();
        if (area.isEmpty()) return;
        Rect before = touchBounds();
        width = Math.min(preferredWidth, Math.max(1, area.width() - 16 * dp));
        height = Math.min(preferredHeight, Math.max(1, area.height() - 48 * dp));
        if (remote) width = height = Math.min(width, height);
        float reach = headerWidth() / 2;
        padX = Math.max(area.left + reach + 8 * dp,
                Math.min(area.right - reach - 8 * dp, padX));
        padY = Math.max(area.top + height / 2 + 56 * dp,
                Math.min(area.bottom - height / 2 - 8 * dp, padY));
        cursorX = Math.max(safe.left + 1, Math.min(safe.right - 1, cursorX));
        cursorY = Math.max(safe.top + 1, Math.min(safe.bottom - 1, cursorY));
        if (!before.equals(touchBounds())) listener.moved(touchBounds());
        invalidate();
    }

    private float headerWidth() { return Math.min(Math.max(width, 176 * dp), Math.max(width, panelArea.width() - 16 * dp)); }

    Rect touchBounds() { return new Rect((int) (padX - headerWidth() / 2), (int) (padY - height / 2 - 52 * dp),
            (int) (padX + headerWidth() / 2), (int) (padY + height / 2)); }

    Region touchRegion() {
        Region region = new Region((int) (padX - headerWidth() / 2), (int) (padY - height / 2 - 52 * dp),
                (int) (padX + headerWidth() / 2), (int) (padY - height / 2 - 4 * dp));
        region.union(new Rect((int) (padX - width / 2), (int) (padY - height / 2),
                (int) (padX + width / 2), (int) (padY + height / 2)));
        return region;
    }

    void track(float x, float y) {
        if (!opening) return;
        moveCursor(x - lastX, y - lastY);
        lastX = x;
        lastY = y;
        listener.touched();
    }

    void endOpening(boolean canceled) { opening = false; if (canceled) listener.close(); }

    private void resetCursor() {
        cursorX = Math.max(safe.left + 1, Math.min(safe.right - 1, padX));
        cursorY = Math.max(safe.top + 1, Math.min(safe.bottom - 1, padY - height / 2 - 110 * dp));
        topArmed = true; topPull = 0;
        invalidate();
    }

    private void moveCursor(float dx, float dy) {
        float sensitivity = config.integer(remote ? "remote_cursor_speed" : "touch_pad_cursor_sensitivity") / 100f;
        float gain = (remote ? 2.2f : 2.5f) * sensitivity;
        if (Math.hypot(cursorX - padX, cursorY - padY) < 120 * dp || Math.hypot(dx, dy) < 3 * dp) gain *= .65f;
        cursorX = Math.max(safe.left + 1, Math.min(safe.right - 1, cursorX + dx * gain));
        float targetY = cursorY + dy * gain;
        if (dy < 0 && targetY < safe.top) topPull += safe.top - targetY;
        else topPull = 0;
        if (remote && config.bool("remote_cursor_top_edge_shortcut") && topPull >= 18 * dp && topArmed) {
            topArmed = false;
            String action = config.string("remote_cursor_top_edge_action_id");
            actions.execute(action.isEmpty() ? "notifications" : action, null, cursorX, 0, false);
        }
        cursorY = Math.max(safe.top + 1, Math.min(safe.bottom - 1, targetY));
        if (cursorY > safe.top + 32 * dp) { topArmed = true; topPull = 0; }
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (event.getPointerCount() != 1 || action == MotionEvent.ACTION_CANCEL) { resetTouch(); return true; }
        if (action != MotionEvent.ACTION_DOWN && !touching) return true;
        listener.touched();
        float x = event.getX(), y = event.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            touching = true;
            updatePress(true);
            opening = false;
            handler.removeCallbacks(taps);
            if (event.getEventTime() - lastTap > config.integer(remote ? "multi_tap_interval_ms" : "touch_pad_double_tap_timeout_ms")) tapCount = 0;
            downX = lastX = x;
            downY = lastY = y;
            moved = held = consumed = recording = dragging = false;
            movingPad = y < padY - height / 2;
            resizingPad = !remote && x > padX + width / 2 - 44 * dp && y > padY + height / 2 - 44 * dp;
            if (movingPad && x > padX + headerWidth() / 2 - 48 * dp) { consumed = true; listener.close(); return true; }
            path.clear();
            replayX = cursorX;
            replayY = cursorY;
            velocity = VelocityTracker.obtain();
            velocity.addMovement(event);
            handler.postDelayed(hold, config.integer(remote ? "remote_cursor_long_press_delay_ms" : "touch_pad_hold_delay_ms"));
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (velocity != null) velocity.addMovement(event);
            if (Math.hypot(x - downX, y - downY) > 8 * dp) {
                moved = true;
                if (!held) handler.removeCallbacks(hold);
                if (tapCount > 0 && !movingPad && !resizingPad && !dragging
                        && (!remote || config.string("remote_cursor_double_tap_action").equals("touch_pad_slide"))) {
                    dragging = true;
                    path.add(new float[]{replayX, replayY});
                }
            }
            if (resizingPad) {
                width = Math.max(96 * dp, Math.min(Math.min(getWidth() - 24 * dp, 360 * dp), width + (x - lastX) * 2));
                height = width * .65f;
                preferredWidth = width;
                preferredHeight = height;
                updateBounds();
                listener.moved(touchBounds());
            } else if (movingPad) {
                padX += x - lastX;
                padY += y - lastY;
                updateBounds();
                listener.moved(touchBounds());
            } else if (recording || dragging) {
                if (path.size() < 256) path.add(new float[]{Math.max(1, Math.min(getWidth() - 1, replayX + (x - downX) * 2)),
                        Math.max(1, Math.min(getHeight() - 1, replayY + (y - downY) * 2))});
            } else if (!consumed && !held) moveCursor(x - lastX, y - lastY);
            lastX = x;
            lastY = y;
            invalidate();
        } else if (action == MotionEvent.ACTION_UP) {
            touching = false;
            updatePress(false);
            handler.removeCallbacks(hold);
            boolean fling = false;
            if (velocity != null) {
                velocity.addMovement(event);
                velocity.computeCurrentVelocity(1000);
                fling = Math.hypot(velocity.getXVelocity(), velocity.getYVelocity()) > 1500 * dp;
            }
            if (movingPad || resizingPad) {
                savePad(); tapCount = 0;
            } else if (remote && moved && fling && config.bool("remote_cursor_throw_dismiss_enabled") && !recording && !dragging) {
                listener.close();
            } else if (recording || dragging) {
                if (path.size() > 1) host.inputSession().playCursor(path, config.integer("touch_pad_slide_duration_ms"), null);
                tapCount = 0;
            } else if (held && !consumed && !movingPad) {
                host.inputSession().tapCursor(cursorX, cursorY, config.integer("touch_pad_hold_duration_ms"), null);
                tapCount = 0;
            } else if (!moved && !consumed && !movingPad) {
                performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                tapCount++;
                lastTap = event.getEventTime();
                if (tapCount == 3 && remote && config.bool("remote_cursor_triple_tap_reset")) {
                    tapCount = 0;
                    resetCursor();
                } else handler.postDelayed(taps, config.integer(remote ? "multi_tap_interval_ms" : "touch_pad_double_tap_timeout_ms"));
            } else tapCount = 0;
            if (velocity != null) { velocity.recycle(); velocity = null; }
            held = recording = dragging = movingPad = resizingPad = false;
            invalidate();
        }
        return true;
    }

    private void hold() {
        if (!touching || moved || consumed || movingPad || resizingPad || !host.canRun()) return;
        if (!remote && !config.bool("touch_pad_hold_enabled")) return;
        String action = remote ? config.string("remote_cursor_hold_action") : "long_click";
        if (action.equals("none")) return;
        held = true;
        if (action.equals("record_swipe")) {
            recording = true;
            replayX = cursorX; replayY = cursorY; downX = lastX; downY = lastY;
            path.add(new float[]{cursorX, cursorY});
        } else if (action.equals("custom")) {
            consumed = true;
            actions.execute(config.string("remote_cursor_hold_bound_action_id"), null, cursorX, cursorY, false);
        }
        if (config.bool("haptic_feedback")) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private void taps() {
        if (!host.canRun() || host.locked()) { tapCount = 0; return; }
        int count = tapCount;
        tapCount = 0;
        if (count == 1) host.inputSession().tapCursor(cursorX, cursorY, 60, null);
        else if (count >= 2) {
            String action = remote ? config.string("remote_cursor_double_tap_action") : "double_tap";
            if (action.equals("dismiss")) listener.close();
            else if (action.equals("custom")) actions.execute(config.string("remote_cursor_double_tap_bound_action_id"), null, cursorX, cursorY, false);
            else if (action.equals("double_tap")) host.inputSession().tapCursor(cursorX, cursorY, 60,
                    () -> host.inputSession().tapCursor(cursorX, cursorY, 60, null));
        }
    }

    private void resetTouch() {
        updatePress(false);
        handler.removeCallbacks(hold);
        handler.removeCallbacks(taps);
        tapCount = 0;
        if (velocity != null) { velocity.recycle(); velocity = null; }
        touching = moved = held = consumed = recording = dragging = movingPad = resizingPad = false;
    }

    void cancelPending() { resetTouch(); opening = false; }

    private void updatePress(boolean pressed) {
        if (ValueAnimator.areAnimatorsEnabled()) press.animateToFinalPosition(pressed ? 1 : 0);
        else { press.cancel(); pressAmount = pressed ? 1 : 0; invalidate(); }
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_CLICK && host.canRun() && !host.locked()) {
            host.inputSession().tapCursor(cursorX, cursorY, 60, null);
            return true;
        }
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.getId()) { listener.close(); return true; }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            moveCursor((key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1) * 8 * dp, 0); return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            moveCursor(0, (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * 8 * dp); return true;
        }
        if (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_DPAD_CENTER) return performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null);
        return super.onKeyDown(key, event);
    }

    private void savePad() {
        JSONObject state = EdgeGoSchedules.state(getContext(), host.userId());
        JSONObject pads = state.optJSONObject("cursorPads"); if (pads == null) pads = new JSONObject();
        JSONObject position = new JSONObject();
        EdgeGoConfig.put(position, "x", padX / getWidth()); EdgeGoConfig.put(position, "y", padY / getHeight());
        EdgeGoConfig.put(position, "width", Math.round(preferredWidth / dp)); EdgeGoConfig.put(position, "configuredWidth", config.integer("touch_pad_size_dp"));
        EdgeGoConfig.put(pads, remote ? "remote_cursor" : "touch_pad", position); EdgeGoConfig.put(state, "cursorPads", pads);
        EdgeGoSchedules.save(getContext(), host.userId(), state);
    }

    @Override protected void onDetachedFromWindow() { resetTouch(); press.cancel(); super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas canvas) {
        int color = remote && config.bool("remote_cursor_use_accent_color") ? icons.accent(config)
                : config.integer(remote ? "remote_cursor_base_color_rgb" : "touch_pad_cursor_color_rgb") | 0xff000000;
        int surface = icons.surface(config, "continuous_gesture_overlay_background_color_rgb");
        paint.setColor(ColorUtils.blendARGB(surface, color, Math.max(0, Math.min(.18f, pressAmount * .18f))));
        paint.setAlpha(Math.round(config.integer(remote ? "remote_cursor_opacity" : "touch_pad_opacity") * 2.55f));
        float corner = remote ? width * (.5f - .18f * Math.max(0, Math.min(1, pressAmount))) : (28 - 8 * pressAmount) * dp;
        canvas.drawRoundRect(padX - width / 2, padY - height / 2, padX + width / 2, padY + height / 2, corner, corner, paint);
        paint.setColor(color);
        paint.setAlpha(held ? 180 : 90);
        canvas.drawCircle(padX, padY, (remote ? width * .17f : 14 * dp) * (1 + pressAmount * .15f), paint);
        paint.setAlpha(255);
        header.set(padX - headerWidth() / 2, padY - height / 2 - 52 * dp, padX + headerWidth() / 2, padY - height / 2 - 4 * dp);
        paint.setColor(surface);
        canvas.drawRoundRect(header, 16 * dp, 16 * dp, paint);
        textPaint.setTextSize(12 * dp * getResources().getConfiguration().fontScale);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setColor(icons.text(config));
        CharSequence label = TextUtils.ellipsize(actions.label(remote ? "remote_cursor" : "touch_pad"), textPaint,
                Math.max(1, header.width() - 68 * dp), TextUtils.TruncateAt.END);
        canvas.drawText(label, 0, label.length(), header.left + 12 * dp, header.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint);
        paint.setColor(icons.container(config));
        canvas.drawCircle(header.right - 24 * dp, header.centerY(), 18 * dp, paint);
        paint.setColor(icons.text(config)); paint.setStrokeWidth(2 * dp); paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(header.right - 30 * dp, header.centerY() - 6 * dp, header.right - 18 * dp, header.centerY() + 6 * dp, paint);
        canvas.drawLine(header.right - 30 * dp, header.centerY() + 6 * dp, header.right - 18 * dp, header.centerY() - 6 * dp, paint);
        paint.setColor(color);
        if (!remote) {
            paint.setStrokeWidth(2 * dp);
            canvas.drawLine(padX + width / 2 - 22 * dp, padY + height / 2 - 6 * dp, padX + width / 2 - 6 * dp, padY + height / 2 - 22 * dp, paint);
            canvas.drawLine(padX + width / 2 - 14 * dp, padY + height / 2 - 6 * dp, padX + width / 2 - 6 * dp, padY + height / 2 - 14 * dp, paint);
        }
        float size = config.integer("remote_cursor_cursor_size_dp") * dp;
        cursor.reset();
        cursor.moveTo(cursorX, cursorY);
        cursor.lineTo(cursorX + size * .22f, cursorY + size);
        cursor.lineTo(cursorX + size * .42f, cursorY + size * .63f);
        cursor.lineTo(cursorX + size * .82f, cursorY + size * .57f);
        cursor.close();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * dp);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(EdgeGoUi.contrastColor(color));
        canvas.drawPath(cursor, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawPath(cursor, paint);
        if (recording || dragging) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3 * dp);
            for (int i = 1; i < path.size(); i++) canvas.drawLine(path.get(i - 1)[0], path.get(i - 1)[1], path.get(i)[0], path.get(i)[1], paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
