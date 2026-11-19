/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.edgego;

import android.gesture.Gesture;
import android.gesture.GesturePoint;
import android.gesture.GestureStroke;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;

import com.android.internal.util.lunaris.EdgeGoConfig;
import com.android.internal.util.lunaris.EdgeGoDrawings;

import org.json.JSONObject;

import java.util.ArrayList;

final class EdgeGoGestureRecognizer {
    interface Listener {
        void onClaim();
        void onProgress(EdgeGoGestureController.Zone zone, String action, float startX,
                float startY, float x, float y, float progress, boolean held);
        void onAction(String action, EdgeGoGestureController.Zone zone, float x, float y, boolean held);
        void onTapAction(String action, EdgeGoGestureController.Zone zone, float x, float y);
        boolean onContinuousStart(String action, EdgeGoGestureController.Zone zone,
                float startX, float startY, float x, float y);
        void onContinuousMove(float x, float y);
        void onFinish(boolean canceled, float velocity);
        void onDirectionChanged(boolean held);
    }

    private final Handler handler;
    private final Listener listener;
    private final Runnable hold = this::checkHold;
    private final Runnable tap = this::finishTaps;
    private final Runnable timeout = () -> cancel(true);
    private final ArrayList<GesturePoint> points = new ArrayList<>(256);
    private EdgeGoGestureController.Zone zone, tapZone;
    private EdgeGoConfig config;
    private VelocityTracker velocity;
    private int pointer = -1, tapCount, device, tapDevice, sessionGeneration;
    private float startX, startY, x, y, lastX, lastY, pathLength, maxDistance, density;
    private float tapX, tapY;
    private long downTime, thresholdTime, lastMovement, lastTap;
    private boolean claimed, held, fired, continuous, returned, extended;
    private String direction = "", action = "none";

    EdgeGoGestureRecognizer(Handler handler, Listener listener) {
        this.handler = handler;
        this.listener = listener;
    }

    boolean tracking() { return zone != null; }
    int selectedSector() {
        return zone != null && zone.side() ? sector(direction,
                config.bool("custom_pull_angles_enabled") ? config.integer("custom_pull_angle_slot_count") : 3) : -1;
    }

    void start(EdgeGoGestureController.Zone zone, EdgeGoConfig config, MotionEvent event, float density) {
        cancel(false);
        int token = sessionGeneration;
        if (tapZone != null && (tapZone.id.equals(zone.id)
                && event.getDeviceId() == tapDevice
                && event.getEventTime() >= lastTap
                && event.getEventTime() - lastTap <= config.integer("multi_tap_interval_ms")
                && Math.hypot(event.getX() - tapX, event.getY() - tapY) < config.integer("tap_slop_dp") * density * 2)) {
            handler.removeCallbacks(tap);
        } else {
            finishTaps();
        }
        if (token != sessionGeneration) return;
        this.zone = zone;
        this.config = config;
        this.density = density;
        pointer = event.getPointerId(0);
        device = event.getDeviceId();
        downTime = event.getDownTime();
        lastMovement = event.getEventTime();
        startX = x = lastX = event.getX();
        startY = y = lastY = event.getY();
        maxDistance = pathLength = 0;
        thresholdTime = 0;
        claimed = held = fired = continuous = returned = extended = false;
        direction = "";
        action = "none";
        points.clear();
        addPoint(x, y, downTime);
        velocity = VelocityTracker.obtain();
        velocity.addMovement(event);
        if (zone.captureTaps) claim();
        if (token != sessionGeneration || this.zone != zone) return;
        handler.postAtTime(hold, downTime + config.integer("hold_timeout_ms"));
        handler.postAtTime(timeout, downTime + config.integer("gesture_timeout_ms"));
    }

    void event(MotionEvent event) {
        if (zone == null) return;
        if (event.getDeviceId() != device || event.getDownTime() != downTime) return;
        int masked = event.getActionMasked();
        if (masked == MotionEvent.ACTION_CANCEL || masked == MotionEvent.ACTION_POINTER_DOWN
                || masked == MotionEvent.ACTION_POINTER_UP || event.getPointerCount() != 1) {
            cancel(true);
            return;
        }
        int index = event.findPointerIndex(pointer);
        if (index < 0) { cancel(true); return; }
        velocity.addMovement(event);
        for (int i = 0; zone != null && !fired && i < event.getHistorySize(); i++) {
            update(event.getHistoricalX(index, i), event.getHistoricalY(index, i),
                    event.getHistoricalEventTime(i));
        }
        if (zone == null) return;
        update(event.getX(index), event.getY(index), event.getEventTime());
        if (zone != null && masked == MotionEvent.ACTION_UP) finish(event.getEventTime());
    }

    private void update(float nextX, float nextY, long time) {
        int token = sessionGeneration;
        float movement = (float) Math.hypot(nextX - x, nextY - y);
        pathLength += movement;
        x = nextX;
        y = nextY;
        if (Math.hypot(x - lastX, y - lastY) > 6 * density) {
            lastMovement = time;
            lastX = x;
            lastY = y;
        }
        if (movement > 2 * density) addPoint(x, y, time);
        float distance = (float) Math.hypot(x - startX, y - startY);
        maxDistance = Math.max(maxDistance, distance);
        if (continuous) {
            if (maxDistance >= config.integer("swipe_threshold_dp") * density
                    && distance < config.integer("tap_slop_dp") * density * 1.4f
                    && config.bool("cancel_hold_on_return_to_start")) {
                cancel(true);
                return;
            }
            listener.onContinuousMove(x, y);
            return;
        }
        if (fired) return;
        float threshold = config.integer("swipe_threshold_dp") * density;
        float tapSlop = config.integer("tap_slop_dp") * density;
        if (maxDistance > tapSlop && tapZone != null) {
            handler.removeCallbacks(tap);
            tapZone = null;
            tapCount = 0;
        }
        if (claimed && maxDistance >= threshold && distance < tapSlop * 1.4f
                && config.bool("cancel_hold_on_return_to_start")) {
            returned = true;
            action = "none";
            handler.removeCallbacks(hold);
            handler.postAtTime(hold, lastMovement + config.integer("hold_timeout_ms"));
            listener.onProgress(zone, action, startX, startY, x, y, 0, false);
            return;
        }
        if (returned) {
            handler.removeCallbacks(hold);
            handler.postAtTime(hold, lastMovement + config.integer("hold_timeout_ms"));
            return;
        }
        if (distance < threshold && thresholdTime == 0) return;
        boolean becameReady = thresholdTime == 0 && distance >= threshold;
        if (becameReady) thresholdTime = time;
        String next = direction(x - startX, y - startY);
        float extendedThreshold = Math.max(config.integer("extended_threshold_dp") * density, threshold * 1.5f);
        if (distance >= extendedThreshold) extended = true;
        else if (distance < extendedThreshold - tapSlop * 1.5f) extended = false;
        String nextAction = config.action(zone.id, next + (held ? "_and_hold" : extended ? "_extended" : ""));
        if (nextAction.equals("none") && !held) nextAction = config.action(zone.id, next);
        boolean configured = !next.equals("outward") && (!nextAction.equals("none")
                || !config.action(zone.id, next + "_and_hold").equals("none")
                || !config.action(zone.id, next + "_extended").equals("none")
                || !config.drawings(zone.id).isEmpty());
        if (!configured) {
            direction = next;
            action = "none";
            handler.removeCallbacks(hold);
            listener.onProgress(zone, action, startX, startY, x, y, 0, held);
            return;
        }
        claim();
        if (token != sessionGeneration || zone == null) return;
        if (!direction.equals(next) || !action.equals(nextAction)) {
            direction = next;
            action = nextAction;
            if (thresholdTime != 0) listener.onDirectionChanged(held);
            if (token != sessionGeneration || zone == null) return;
            handler.removeCallbacks(hold);
            if (!held && thresholdTime != 0) scheduleHold();
        }
        if (becameReady) {
            listener.onDirectionChanged(false);
            if (token != sessionGeneration || zone == null) return;
            if (!held) scheduleHold();
        }
        if (distance < threshold * .7f) action = "none";
        listener.onProgress(zone, action, startX, startY, x, y, Math.min(1, distance / threshold), held);
        if (token == sessionGeneration && zone != null && !held && distance >= threshold && canStartContinuous()) {
            beginContinuous();
        }
    }

    private boolean canStartContinuous() {
        if (!config.bool("continuous_swipe_enabled") || !EdgeGoConfig.adjustable(action)
                || !config.drawings(zone.id).isEmpty()) return false;
        String holdAction = config.action(zone.id, direction + "_and_hold");
        String extendedAction = config.action(zone.id, direction + "_extended");
        return (holdAction.equals("none") || holdAction.equals(action))
                && (extended || extendedAction.equals("none") || extendedAction.equals(action));
    }

    private void beginContinuous() {
        int token = sessionGeneration;
        EdgeGoGestureController.Zone starting = zone;
        boolean started = listener.onContinuousStart(action, zone, startX, startY, x, y);
        if (token != sessionGeneration || zone != starting) {
            if (started && zone == null) listener.onFinish(true, 0);
            return;
        }
        continuous = started;
        handler.removeCallbacks(hold);
        handler.removeCallbacks(timeout);
        if (!continuous) {
            action = "none";
            fired = true;
        }
    }

    private String direction(float dx, float dy) {
        if (!zone.side()) {
            float inward = zone.edge.equals("top") ? dy : -dy;
            if (inward < -config.integer("tap_slop_dp") * density && Math.abs(inward) > Math.abs(dx)) return "outward";
            if (inward > Math.abs(dx) * .75f) return "pull_inward";
            return dx < 0 ? "slide_left" : "slide_right";
        }
        float inward = zone.edge.equals("left") ? dx : -dx;
        if (inward < -config.integer("tap_slop_dp") * density) return "outward";
        double angle = Math.toDegrees(Math.atan2(dy, Math.max(0, inward)));
        double[] dividers = config.sectorDividers();
        double hysteresis = config.integer("direction_hysteresis_deg");
        int previous = sector(direction, dividers.length - 1);
        if (direction.equals("swipe_up") && angle < dividers[0] + hysteresis) return direction;
        if (direction.equals("swipe_down") && angle > dividers[dividers.length - 1] - hysteresis) return direction;
        if (previous >= 0) hysteresis = Math.min(hysteresis, (dividers[previous + 1] - dividers[previous]) * .4);
        if (previous >= 0 && angle > dividers[previous] - hysteresis
                && angle < dividers[previous + 1] + hysteresis) return direction;
        if (angle < dividers[0]) return "swipe_up";
        if (angle > dividers[dividers.length - 1]) return "swipe_down";
        int sector = 0;
        while (sector < dividers.length - 2 && angle > dividers[sector + 1]) sector++;
        return config.bool("custom_pull_angles_enabled") ? "custom_pull_" + (sector + 1)
                : sector == 0 ? "pull_diagonal_up" : sector == 1 ? "pull_inward" : "pull_diagonal_down";
    }

    private int sector(String direction, int count) {
        if (direction.startsWith("custom_pull_")) {
            try { return EdgeGoConfig.clamp(Integer.parseInt(direction.substring(12)) - 1, 0, count - 1); }
            catch (NumberFormatException ignored) { return -1; }
        }
        return direction.equals("pull_diagonal_up") ? 0 : direction.equals("pull_inward") ? 1
                : direction.equals("pull_diagonal_down") ? 2 : -1;
    }

    private void scheduleHold() {
        long when = Math.max(thresholdTime + config.integer("hold_timeout_ms"),
                lastMovement + config.integer("hold_settle_ms"));
        handler.postAtTime(hold, Math.max(SystemClock.uptimeMillis() + 1, when));
    }

    private void checkHold() {
        if (zone == null || fired || continuous || held) return;
        int token = sessionGeneration;
        JSONObject drawn = matchDrawing();
        if (drawn != null) {
            if (!drawn.optBoolean("holdAtEnd")) return;
            long when = lastMovement + config.integer("hold_timeout_ms");
            if (SystemClock.uptimeMillis() < when) { handler.postAtTime(hold, when); return; }
            action = drawnAction(drawn);
        } else if (returned) return;
        else if (thresholdTime == 0) {
            if (maxDistance > config.integer("tap_slop_dp") * density) return;
            action = config.action(zone.id, "long_press");
        } else {
            if (Math.hypot(x - startX, y - startY) < config.integer("swipe_threshold_dp") * density * .7f) return;
            if (SystemClock.uptimeMillis() - lastMovement < config.integer("hold_settle_ms")) {
                scheduleHold();
                return;
            }
            String selected = action;
            action = config.action(zone.id, direction + "_and_hold");
            if (action.equals("none") && config.bool("continuous_swipe_enabled")
                    && EdgeGoConfig.adjustable(selected)
                    && (config.drawings(zone.id).isEmpty() || pathLength <= Math.hypot(x - startX, y - startY) * 1.3f)) {
                action = selected;
            }
        }
        if (action.equals("none")) return;
        held = true;
        returned = false;
        tapCount = 0;
        tapZone = null;
        handler.removeCallbacks(tap);
        claim();
        if (token != sessionGeneration || zone == null) return;
        listener.onDirectionChanged(true);
        if (token != sessionGeneration || zone == null) return;
        listener.onProgress(zone, action, startX, startY, x, y, 1, true);
        if (token != sessionGeneration || zone == null) return;
        if (EdgeGoConfig.continuous(action)) {
            beginContinuous();
        } else if (!config.bool("hold_actions_on_release")) {
            fired = true;
            listener.onAction(action, zone, x, y, true);
        }
    }

    private void claim() {
        if (claimed) return;
        claimed = true;
        listener.onClaim();
    }

    private void finish(long time) {
        int token = sessionGeneration;
        EdgeGoGestureController.Zone finishing = zone;
        handler.removeCallbacks(hold);
        handler.removeCallbacks(timeout);
        float releaseVelocity = 0;
        if (velocity != null) {
            velocity.computeCurrentVelocity(1000);
            releaseVelocity = (float) Math.hypot(velocity.getXVelocity(pointer), velocity.getYVelocity(pointer));
        }
        boolean tapped = false;
        if (!fired && !continuous) {
            JSONObject drawn = held ? null : matchDrawing();
            if (drawn != null) {
                action = drawn.optBoolean("holdAtEnd") ? "none" : drawnAction(drawn);
                returned = false;
                if (!action.equals("none")) listener.onAction(action, zone, x, y, false);
            } else if (!returned) {
                if (maxDistance <= config.integer("tap_slop_dp") * density && !held
                        && time - downTime < config.integer("hold_timeout_ms")) {
                    countTap(time);
                    tapped = true;
                } else if (claimed && (held || thresholdTime != 0)) {
                    if (!action.equals("none")) listener.onAction(action, zone, x, y, held);
                }
            }
        }
        if (!tapped && tapZone != null && token == sessionGeneration) {
            tapped = true;
            finishTaps();
        }
        if (token != sessionGeneration || zone != finishing) return;
        clearTracking();
        listener.onFinish(returned || !tapped && action.equals("none") && claimed, releaseVelocity);
    }

    private JSONObject matchDrawing() {
        float direct = (float) Math.hypot(x - startX, y - startY);
        if (!claimed || thresholdTime == 0 || points.size() < 6
                || pathLength <= config.integer("swipe_threshold_dp") * density || pathLength <= direct * 1.3f) return null;
        Gesture gesture = new Gesture();
        gesture.addStroke(new GestureStroke(points));
        return zone.drawings.match(gesture);
    }

    private String drawnAction(JSONObject drawing) {
        return config.drawingAction(zone.id, drawing);
    }

    private void countTap(long time) {
        tapZone = zone;
        tapX = x;
        tapY = y;
        lastTap = time;
        tapDevice = device;
        tapCount++;
        boolean doubleTap = !config.action(zone.id, "double_tap").equals("none");
        boolean tripleTap = !config.action(zone.id, "triple_tap").equals("none");
        if (tapCount == 3 || tapCount == 2 && !tripleTap || !doubleTap && !tripleTap) finishTaps();
        else handler.postAtTime(tap, time + config.integer("multi_tap_interval_ms"));
    }

    private void finishTaps() {
        handler.removeCallbacks(tap);
        EdgeGoGestureController.Zone finishing = tapZone;
        int count = tapCount;
        tapCount = 0;
        tapZone = null;
        if (finishing != null && config != null && count > 0) {
            String gesture = count >= 3 ? "triple_tap" : count == 2 ? "double_tap" : "tap";
            String mapped = config.action(finishing.id, gesture);
            if (!mapped.equals("none")) listener.onTapAction(mapped, finishing, tapX, tapY);
        }
    }

    void cancel(boolean taps) {
        sessionGeneration++;
        handler.removeCallbacks(hold);
        handler.removeCallbacks(timeout);
        boolean tracking = zone != null;
        clearTracking();
        if (taps) {
            handler.removeCallbacks(tap);
            tapZone = null;
            tapCount = 0;
        }
        if (tracking) listener.onFinish(true, 0);
    }

    private void clearTracking() {
        zone = null;
        pointer = -1;
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }

    private void addPoint(float x, float y, long time) {
        if (points.size() >= 256) {
            for (int i = points.size() - 2; i > 0; i -= 2) points.remove(i);
        }
        points.add(new GesturePoint(x, y, time));
    }
}
