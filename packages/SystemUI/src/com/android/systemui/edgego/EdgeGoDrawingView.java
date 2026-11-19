/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.edgego;

import android.animation.ValueAnimator;
import android.content.Context;
import android.gesture.Gesture;
import android.gesture.GesturePoint;
import android.gesture.GestureStroke;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import com.android.internal.util.lunaris.EdgeGoConfig;
import com.android.internal.util.lunaris.EdgeGoDrawings;
import com.android.systemui.res.R;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class EdgeGoDrawingView extends View {
    private final EdgeGoConfig config;
    private final EdgeGoActions actions;
    private final EdgeGoIcons icons;
    private final EdgeGoGestureController host;
    private final EdgeGoDrawings drawings;
    private final Runnable close;
    private final Handler handler;
    private final Runnable hold = this::finishHold;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF hint = new RectF(), closeBounds = new RectF();
    private StaticLayout messageLayout;
    private ValueAnimator messageAnimation;
    private float messageOpacity = 1;
    private int messageWidth;
    private final Map<Integer, ArrayList<GesturePoint>> strokes = new LinkedHashMap<>();
    private final Map<Integer, GesturePoint> anchors = new LinkedHashMap<>();
    private String message;
    private long lastMovement;
    private float lastX, lastY;

    EdgeGoDrawingView(Context context, EdgeGoConfig config, EdgeGoActions actions, EdgeGoIcons icons,
            EdgeGoGestureController host, Runnable close) {
        super(context);
        this.config = config;
        this.actions = actions;
        this.icons = icons;
        this.host = host;
        this.close = close;
        handler = new Handler(context.getMainLooper());
        drawings = new EdgeGoDrawings(config.drawings(null), config.bool("drawing_orientation_sensitive"), config.integer("drawing_confidence"));
        message = context.getString(R.string.edgego_draw_hint);
        setContentDescription(message);
        setFocusable(true);
        setHapticFeedbackEnabled(config.bool("haptic_feedback"));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (closeBounds.contains(event.getX(), event.getY())) { close.run(); return true; }
            setMessage(getContext().getString(R.string.edgego_draw_hint));
            strokes.clear(); anchors.clear(); lastX = event.getX(); lastY = event.getY(); lastMovement = event.getEventTime();
        }
        if (event.getPointerCount() > 3 || action == MotionEvent.ACTION_CANCEL) {
            handler.removeCallbacks(hold); strokes.clear(); close.run(); return true;
        }
        if (Math.hypot(event.getX() - lastX, event.getY() - lastY) > 4 * getResources().getDisplayMetrics().density) {
            lastMovement = event.getEventTime(); lastX = event.getX(); lastY = event.getY();
        }
        for (int pointer = 0; pointer < event.getPointerCount(); pointer++) {
            int id = event.getPointerId(pointer);
            ArrayList<GesturePoint> stroke = strokes.computeIfAbsent(id, key -> new ArrayList<>());
            GesturePoint point = new GesturePoint(event.getX(pointer), event.getY(pointer), event.getEventTime());
            GesturePoint anchor = anchors.get(id);
            if (anchor == null || Math.hypot(point.x - anchor.x, point.y - anchor.y) > 4 * getResources().getDisplayMetrics().density) {
                lastMovement = event.getEventTime(); anchors.put(id, point);
            }
            if (stroke.size() >= 256) for (int i = stroke.size() - 2; i > 0; i -= 2) stroke.remove(i);
            stroke.add(point);
        }
        if (strokes.size() > 3) { handler.removeCallbacks(hold); strokes.clear(); close.run(); return true; }
        handler.removeCallbacks(hold);
        handler.postAtTime(hold, lastMovement + config.integer("hold_timeout_ms"));
        if (action == MotionEvent.ACTION_UP) {
            handler.removeCallbacks(hold);
            Gesture gesture = gesture();
            if (gesture.getLength() < 24 * getResources().getDisplayMetrics().density) { close.run(); return true; }
            JSONObject match = drawings.match(gesture);
            if (match == null) { setMessage(getContext().getString(R.string.edgego_draw_no_match)); strokes.clear(); }
            else if (!match.optBoolean("holdAtEnd")) activate(match);
            else { setMessage(getContext().getString(R.string.edgego_draw_hold_hint)); strokes.clear(); }
        }
        invalidate();
        return true;
    }

    private Gesture gesture() {
        Gesture gesture = new Gesture();
        for (ArrayList<GesturePoint> points : strokes.values()) if (points.size() > 1) gesture.addStroke(new GestureStroke(points));
        return gesture;
    }

    private void finishHold() {
        Gesture gesture = gesture();
        if (gesture.getLength() < 48 * getResources().getDisplayMetrics().density) return;
        JSONObject match = drawings.match(gesture);
        if (match != null && match.optBoolean("holdAtEnd")) activate(match);
    }

    private void activate(JSONObject drawing) {
        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        String action = config.drawingAction(null, drawing);
        close.run(); actions.execute(action, null, lastX, lastY, false);
    }

    private void setMessage(String value) {
        if (value.equals(message)) return;
        message = value;
        messageLayout = null;
        setContentDescription(message);
        if (messageAnimation != null) messageAnimation.cancel();
        if (ValueAnimator.areAnimatorsEnabled()) {
            messageAnimation = ValueAnimator.ofFloat(0, 1);
            messageAnimation.setDuration(180);
            messageAnimation.addUpdateListener(animation -> { messageOpacity = (float) animation.getAnimatedValue(); invalidate(); });
            messageAnimation.start();
        } else { messageOpacity = 1; invalidate(); }
    }

    @Override protected void onDetachedFromWindow() { cancelPending(); super.onDetachedFromWindow(); }
    void cancelPending() { handler.removeCallbacks(hold); if (messageAnimation != null) messageAnimation.cancel(); }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.getId()) { close.run(); return true; }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor((icons.surface(config, "continuous_gesture_overlay_background_color_rgb") & 0x00ffffff) | 0x30000000);
        float dp = getResources().getDisplayMetrics().density;
        WindowInsets insets = getRootWindowInsets();
        Insets safe = insets == null ? Insets.NONE : insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        Rect area = host.panelBounds();
        if (!area.isEmpty()) safe = Insets.max(safe, Insets.of(area.left, area.top, getWidth() - area.right, getHeight() - area.bottom));
        int width = Math.max(1, getWidth() - safe.left - safe.right - (int) (116 * dp));
        float textSize = 16 * dp * getResources().getConfiguration().fontScale;
        if (messageLayout == null || width != messageWidth || textPaint.getTextSize() != textSize) {
            messageWidth = width;
            textPaint.setTextSize(textSize);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            messageLayout = StaticLayout.Builder.obtain(message, 0, message.length(), textPaint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setMaxLines(3)
                    .setEllipsize(TextUtils.TruncateAt.END).build();
        }
        hint.set(safe.left + 16 * dp, safe.top + 20 * dp, getWidth() - safe.right - 16 * dp,
                safe.top + 20 * dp + Math.max(64 * dp, messageLayout.getHeight() + 28 * dp));
        paint.setColor(icons.surface(config, "continuous_gesture_overlay_background_color_rgb"));
        canvas.drawRoundRect(hint, 24 * dp, 24 * dp, paint);
        textPaint.setColor(icons.text(config)); textPaint.setAlpha(Math.round(255 * messageOpacity));
        int save = canvas.save();
        canvas.translate(hint.left + 16 * dp, hint.centerY() - messageLayout.getHeight() / 2f);
        messageLayout.draw(canvas);
        canvas.restoreToCount(save);
        closeBounds.set(hint.right - 56 * dp, hint.centerY() - 24 * dp, hint.right - 8 * dp, hint.centerY() + 24 * dp);
        paint.setColor(icons.container(config));
        canvas.drawRoundRect(closeBounds, 24 * dp, 24 * dp, paint);
        paint.setColor(icons.text(config)); paint.setStrokeWidth(2 * dp); paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(closeBounds.centerX() - 6 * dp, closeBounds.centerY() - 6 * dp,
                closeBounds.centerX() + 6 * dp, closeBounds.centerY() + 6 * dp, paint);
        canvas.drawLine(closeBounds.centerX() - 6 * dp, closeBounds.centerY() + 6 * dp,
                closeBounds.centerX() + 6 * dp, closeBounds.centerY() - 6 * dp, paint);
        paint.setColor(icons.accent(config));
        paint.setStrokeWidth(4 * dp); paint.setStrokeCap(Paint.Cap.ROUND);
        for (ArrayList<GesturePoint> points : strokes.values()) {
            for (int i = 1; i < points.size(); i++) canvas.drawLine(points.get(i - 1).x, points.get(i - 1).y,
                    points.get(i).x, points.get(i).y, paint);
        }
    }
}
