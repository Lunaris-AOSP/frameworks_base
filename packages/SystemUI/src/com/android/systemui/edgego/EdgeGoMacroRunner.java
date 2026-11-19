/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.edgego;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.android.internal.util.lunaris.EdgeGoConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class EdgeGoMacroRunner {
    private enum Mode { IDLE, WAIT_RECORD, RECORD, WAIT_PLAY, PLAY }
    private final Context context;
    private final Handler handler;
    private final EdgeGoGestureController host;
    private final EdgeGoActions actions;
    private final EdgeGoInputSession input;
    private volatile Mode mode = Mode.IDLE;
    private final Runnable next = this::nextStep;
    private final Runnable openingTimeout = () -> { if (mode == Mode.WAIT_RECORD || mode == Mode.WAIT_PLAY) cancel(); };
    private final Runnable replayTimeout = () -> { if (mode == Mode.PLAY) cancel(); };
    private JSONObject macro;
    private JSONArray steps;
    private int index, user, returnTask = -1, sessionGeneration, targetTask = -1, recordingBudget;
    private long recordingDown, lastRelease, totalDuration;
    private long lastRecordedDown = -1, lastBackDown = -1;
    private long recordingStarted;
    private long nextReadyAt, remainingDelay;
    private int lastRecordedIndex = -1;
    private boolean paused, ignoredStroke, panelInteraction;
    private final List<float[]> recordingPoints = new ArrayList<>();
    private String target = "";

    EdgeGoMacroRunner(Context context, Handler handler, EdgeGoGestureController host,
            EdgeGoActions actions, EdgeGoInputSession input) {
        this.context = context;
        this.handler = handler;
        this.host = host;
        this.actions = actions;
        this.input = input;
    }

    boolean recording() { return mode == Mode.RECORD; }
    boolean running() { return mode == Mode.PLAY || mode == Mode.WAIT_PLAY; }
    boolean paused() { return paused || panelInteraction; }
    int count() { return steps == null ? 0 : steps.length(); }
    int step() { return index; }

    private JSONObject find(String id) {
        JSONArray macros = host.config().array("macros_v2_json");
        for (int i = 0; i < Math.min(macros.length(), 32); i++) {
            JSONObject macro = macros.optJSONObject(i);
            if (macro != null && macro.optString("id").equals(id)) return EdgeGoConfig.object(macro.toString());
        }
        return null;
    }

    void record(String id, int returnTask) {
        cancel();
        macro = find(id);
        if (macro == null || !host.canRun() || host.locked()) return;
        user = host.userId();
        sessionGeneration++;
        this.returnTask = returnTask;
        target = macro.optString("targetPackage");
        if (target.isEmpty()) return;
        JSONArray previous = macro.optJSONArray("steps");
        recordingBudget = EdgeGoConfig.MAX_SETTINGS_LENGTH - EdgeGoConfig.encode(host.config().snapshot()).length()
                + (previous == null ? 2 : previous.toString().length()) - 128;
        if (recordingBudget < 2) { actions.unavailable(); return; }
        steps = new JSONArray();
        totalDuration = 0;
        lastRecordedIndex = -1;
        lastRecordedDown = lastBackDown = -1;
        mode = Mode.WAIT_RECORD;
        openTarget();
    }

    void run(String id) {
        if (mode != Mode.IDLE) { actions.unavailable(); return; }
        macro = find(id);
        if (macro == null || !host.canRun() || host.locked()) return;
        steps = macro.optJSONArray("steps");
        if (steps == null || steps.length() == 0 || steps.length() > EdgeGoConfig.MAX_MACRO_STEPS) return;
        totalDuration = 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) return;
            long duration = step.optLong("durationMs", 100);
            if (duration < 0 || duration > 30000) return;
            totalDuration += duration;
        }
        if (totalDuration > 300000) return;
        target = macro.optString("targetPackage");
        if (target.isEmpty()) return;
        user = host.userId();
        index = 0;
        sessionGeneration++;
        paused = false;
        mode = Mode.WAIT_PLAY;
        openTarget();
    }

    private void openTarget() {
        if (target.equals(host.foreground())) onForegroundChanged(target);
        else {
            actions.execute("launch_app:" + target, null, 0, 0, false);
            handler.postDelayed(openingTimeout, 5000);
        }
    }

    void onForegroundChanged(String pkg) {
        if (mode != Mode.IDLE && target.equals(pkg) && host.foregroundUserId() != user) { cancel(); return; }
        if ((mode == Mode.RECORD || mode == Mode.PLAY) && target.equals(pkg) && targetTask != host.taskId()) { cancel(); return; }
        if (mode == Mode.WAIT_RECORD && target.equals(pkg)) {
            handler.removeCallbacks(openingTimeout);
            mode = Mode.RECORD;
            recordingStarted = SystemClock.uptimeMillis();
            targetTask = host.taskId();
            host.refreshForMacroState();
            lastRelease = 0;
            paused = false;
            host.showPanel("macro_recording", null, 0, 0, false);
        } else if (mode == Mode.WAIT_PLAY && target.equals(pkg)) {
            handler.removeCallbacks(openingTimeout);
            mode = Mode.PLAY;
            targetTask = host.taskId();
            host.refreshForMacroState();
            host.showPanel("macro_playing", null, 0, 0, false);
            scheduleNext(120);
        } else if ((mode == Mode.RECORD || mode == Mode.PLAY) && !target.equals(pkg)) {
            cancel();
        }
    }

    void observe(MotionEvent event) {
        if (mode == Mode.PLAY && event.getActionMasked() == MotionEvent.ACTION_DOWN
                && !host.panelOwnsTouch((int) event.getX(), (int) event.getY())) {
            cancel();
            return;
        }
        if (mode != Mode.RECORD || paused || !host.canInject(target, user)) return;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            ignoredStroke = host.panelOwnsTouch((int) event.getX(), (int) event.getY())
                    || !host.inputBounds().contains((int) event.getX(), (int) event.getY());
            if ((host.flags() & com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_VISIBLE) != 0) {
                ignoredStroke = true;
            }
            recordingPoints.clear();
            recordingDown = event.getEventTime();
        }
        if (ignoredStroke) return;
        if (event.getPointerCount() != 1 || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            ignoredStroke = true;
            recordingPoints.clear();
            return;
        }
        Rect screen = context.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        for (int i = 0; i < event.getHistorySize(); i++) {
            addRecordingPoint(event.getHistoricalX(i) / screen.width(), event.getHistoricalY(i) / screen.height());
        }
        addRecordingPoint(event.getX() / screen.width(), event.getY() / screen.height());
        if (event.getActionMasked() != MotionEvent.ACTION_UP || recordingPoints.isEmpty()) return;
        if (steps.length() >= EdgeGoConfig.MAX_MACRO_STEPS - 2) { finishRecording(); return; }
        long wait = lastRelease == 0 ? 0 : Math.max(0, Math.min(30000, recordingDown - lastRelease));
        if (wait < 120) wait = 0;
        long duration = Math.max(40, Math.min(5000, event.getEventTime() - recordingDown));
        float[] start = recordingPoints.get(0), end = recordingPoints.get(recordingPoints.size() - 1);
        double distance = 0;
        for (float[] point : recordingPoints) distance = Math.max(distance,
                Math.hypot((point[0] - start[0]) * screen.width(), (point[1] - start[1]) * screen.height()));
        JSONObject step = new JSONObject();
        EdgeGoConfig.put(step, "durationMs", duration);
        if (distance < 12 * context.getResources().getDisplayMetrics().density) {
            EdgeGoConfig.put(step, "type", "tap");
            EdgeGoConfig.put(step, "x", start[0]);
            EdgeGoConfig.put(step, "y", start[1]);
        } else {
            EdgeGoConfig.put(step, "type", "swipe");
            EdgeGoConfig.put(step, "startX", start[0]);
            EdgeGoConfig.put(step, "startY", start[1]);
            EdgeGoConfig.put(step, "endX", end[0]);
            EdgeGoConfig.put(step, "endY", end[1]);
            JSONArray points = new JSONArray();
            for (float[] point : recordingPoints) {
                JSONObject value = new JSONObject();
                EdgeGoConfig.put(value, "x", point[0]);
                EdgeGoConfig.put(value, "y", point[1]);
                points.put(value);
            }
            EdgeGoConfig.put(step, "points", points);
        }
        if (!appendRecorded(step, wait)) { finishRecording(); return; }
        lastRecordedIndex = steps.length() - 1; lastRecordedDown = recordingDown;
        lastRelease = event.getEventTime();
    }

    private boolean appendRecorded(JSONObject step, long wait) {
        long duration = step.optLong("durationMs");
        if (steps.length() + (wait > 0 ? 2 : 1) > EdgeGoConfig.MAX_MACRO_STEPS
                || totalDuration + wait + duration > 300000) return false;
        JSONObject delay = null;
        if (wait > 0) {
            delay = new JSONObject();
            EdgeGoConfig.put(delay, "type", "delay");
            EdgeGoConfig.put(delay, "durationMs", wait);
        }
        int length = steps.toString().length() + step.toString().length() + 1
                + (delay == null ? 0 : delay.toString().length() + 1);
        if (length > recordingBudget) return false;
        if (delay != null) steps.put(delay);
        steps.put(step);
        totalDuration += wait + duration;
        return true;
    }

    void finishRecording() {
        if (mode != Mode.RECORD || macro == null || steps == null || user != host.userId()) return;
        if (steps.length() == 0) {
            paused = ignoredStroke = true;
            recordingPoints.clear();
            actions.unavailable();
            return;
        }
        EdgeGoConfig.put(macro, "steps", steps);
        EdgeGoConfig.put(macro, "schemaVersion", 2);
        EdgeGoConfig.put(macro, "updatedAtMs", System.currentTimeMillis());
        String id = macro.optString("id");
        JSONObject saved = macro;
        boolean success = EdgeGoConfig.edit(context.getContentResolver(), user, values -> {
            JSONArray current = values.optJSONArray("macros_v2_json");
            if (current == null) current = host.config().array("macros_v2_json");
            JSONArray updated = new JSONArray();
            for (int i = 0; i < current.length(); i++) {
                JSONObject entry = current.optJSONObject(i);
                if (entry != null) updated.put(entry.optString("id").equals(id) ? saved : entry);
            }
            EdgeGoConfig.put(values, "macros_v2_json", updated);
        });
        if (!success) {
            paused = ignoredStroke = true;
            recordingPoints.clear();
            actions.unavailable();
            return;
        }
        mode = Mode.IDLE;
        host.refreshForMacroState();
        host.closePanels();
        restoreSettingsTask();
    }

    private void addRecordingPoint(float x, float y) {
        if (recordingPoints.size() >= 128) {
            for (int i = recordingPoints.size() - 2; i > 0; i -= 2) recordingPoints.remove(i);
        }
        recordingPoints.add(new float[]{x, y});
    }

    void recordBack(long downTime) {
        if (mode != Mode.RECORD || paused || downTime < recordingStarted || downTime == lastBackDown || !host.canInject(target, user)) return;
        lastBackDown = downTime;
        ignoredStroke = true; recordingPoints.clear();
        boolean replacing = lastRecordedDown == downTime && lastRecordedIndex == steps.length() - 1;
        if (replacing) {
            JSONObject previous = steps.optJSONObject(lastRecordedIndex);
            if (previous != null) totalDuration -= previous.optLong("durationMs");
            steps.remove(lastRecordedIndex);
        }
        long wait = replacing || lastRelease == 0 ? 0 : Math.max(0, Math.min(30000, downTime - lastRelease));
        if (wait < 120) wait = 0;
        JSONObject step = new JSONObject();
        EdgeGoConfig.put(step, "type", "action"); EdgeGoConfig.put(step, "actionId", "back"); EdgeGoConfig.put(step, "durationMs", 250);
        if (!appendRecorded(step, wait)) { finishRecording(); return; }
        lastRelease = SystemClock.uptimeMillis();
    }

    void discardRecording() {
        if (mode != Mode.RECORD && mode != Mode.WAIT_RECORD) return;
        cancel();
        restoreSettingsTask();
    }

    private void restoreSettingsTask() {
        int taskId = returnTask, expectedUser = user;
        int token = sessionGeneration;
        String expectedPackage = target;
        returnTask = -1;
        if (taskId < 0) return;
        actions.background().execute(() -> {
            try {
                List<android.app.ActivityManager.RecentTaskInfo> tasks = android.app.ActivityTaskManager.getService()
                        .getRecentTasks(64, android.app.ActivityManager.RECENT_IGNORE_UNAVAILABLE, expectedUser).getList();
                boolean found = false;
                for (android.app.ActivityManager.RecentTaskInfo task : tasks) if (task.persistentId == taskId
                        && task.userId == expectedUser && EdgeGoActions.taskPackage(task).equals("com.android.settings")) {
                    found = true; break;
                }
                if (!found) return;
                handler.post(() -> {
                    if (!host.canRun() || host.locked() || expectedUser != host.userId() || token != sessionGeneration
                            || !expectedPackage.equals(host.foreground())) return;
                    try { android.app.ActivityTaskManager.getService().startActivityFromRecents(taskId,
                            android.app.ActivityOptions.makeBasic().toBundle()); }
                    catch (android.os.RemoteException | RuntimeException ignored) { }
                });
            } catch (android.os.RemoteException | RuntimeException ignored) { }
        });
    }

    void togglePause() {
        if (mode != Mode.RECORD && mode != Mode.PLAY) return;
        paused = !paused;
        if (mode == Mode.RECORD) {
            recordingPoints.clear();
            ignoredStroke = true;
            lastRelease = 0;
        }
        if (mode == Mode.PLAY) {
            if (paused) {
                if (!panelInteraction) remainingDelay = Math.max(0, nextReadyAt - SystemClock.uptimeMillis());
                handler.removeCallbacks(next);
                handler.removeCallbacks(replayTimeout);
                input.suspend();
            } else if (!panelInteraction) resumePlayback();
        }
    }

    void beginPanelTouch() {
        if (mode != Mode.PLAY || panelInteraction || paused) return;
        panelInteraction = true;
        remainingDelay = Math.max(0, nextReadyAt - SystemClock.uptimeMillis());
        handler.removeCallbacks(next);
        handler.removeCallbacks(replayTimeout);
        input.suspend();
    }

    void endPanelTouch() {
        if (!panelInteraction) return;
        panelInteraction = false;
        if (mode == Mode.PLAY && !paused) resumePlayback();
    }

    private void resumePlayback() {
        if (input.suspended()) {
            handler.postDelayed(replayTimeout, 8000);
            if (!input.resume()) cancel();
        } else if (!input.running()) scheduleNext(remainingDelay);
    }

    private void scheduleNext(long delay) {
        handler.removeCallbacks(next);
        remainingDelay = Math.max(0, delay);
        nextReadyAt = SystemClock.uptimeMillis() + remainingDelay;
        if (!paused && !panelInteraction) handler.postAtTime(next, nextReadyAt);
    }

    private void nextStep() {
        if (mode != Mode.PLAY || paused || panelInteraction || input.running()) return;
        nextReadyAt = remainingDelay = 0;
        if (!host.canInject(target, user) || index >= steps.length()) { cancel(); return; }
        JSONObject step = steps.optJSONObject(index);
        if (step == null) { cancel(); return; }
        long duration = Math.max(40, Math.min(30000, step.optLong("durationMs", 100)));
        String type = step.optString("type");
        if (type.equals("delay")) {
            index++;
            scheduleNext(duration);
            return;
        }
        Rect screen = context.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        List<float[]> points = new ArrayList<>();
        if (type.equals("tap")) points.add(point(step, "x", "y", screen));
        else if (type.equals("swipe")) {
            JSONArray path = step.optJSONArray("points");
            if (path != null) for (int i = 0; i < Math.min(path.length(), 256); i++) {
                JSONObject point = path.optJSONObject(i);
                if (point != null) points.add(point(point, "x", "y", screen));
            }
            if (points.isEmpty()) {
                points.add(point(step, "startX", "startY", screen));
                points.add(point(step, "endX", "endY", screen));
            }
        } else if (type.equals("action")) {
            String action = EdgeGoConfig.normalizeAction(step.optString("actionId"));
            if (!EdgeGoConfig.macroAction(action)) { cancel(); return; }
            actions.execute(action, null, 0, 0, false);
            index++;
            scheduleNext(duration);
            return;
        } else { cancel(); return; }
        handler.postDelayed(replayTimeout, Math.min(duration, 5000) + 3000);
        if (!input.play(points, Math.min(duration, 5000), () -> {
            handler.removeCallbacks(replayTimeout);
            if (mode == Mode.PLAY) { index++; scheduleNext(80); }
        }, this::cancel)) cancel();
    }

    private float[] point(JSONObject point, String x, String y, Rect screen) {
        double px = point.optDouble(x, .5), py = point.optDouble(y, .5);
        return new float[]{(float) (Math.max(0, Math.min(1, Double.isFinite(px) ? px : .5)) * (screen.width() - 1)),
                (float) (Math.max(0, Math.min(1, Double.isFinite(py) ? py : .5)) * (screen.height() - 1))};
    }

    void cancel() {
        boolean active = mode != Mode.IDLE;
        if (active) sessionGeneration++;
        mode = Mode.IDLE;
        paused = panelInteraction = false;
        nextReadyAt = remainingDelay = 0;
        handler.removeCallbacks(next);
        handler.removeCallbacks(openingTimeout);
        handler.removeCallbacks(replayTimeout);
        input.cancel();
        recordingPoints.clear();
        if (active) host.closePanels();
        if (active) host.refreshForMacroState();
    }
}
