/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.edgego;

import android.hardware.input.InputManager;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

final class EdgeGoInputSession {
    interface Host {
        boolean canInject(String expectedPackage, int user);
        boolean canContinueStatusBarGesture(String expectedPackage, int user);
        String foreground();
        int userId();
        int taskId();
        Rect inputBounds();
        Rect cursorBounds();
        void markInjectedGesture(long downTime);
        void prepareInjection(boolean active);
    }

    private final InputManager input;
    private final Choreographer choreographer;
    private final Host host;
    private final Choreographer.FrameCallback frame = this::frame;
    private List<float[]> path = new ArrayList<>();
    private int user, task, framesToWait;
    private long start, duration;
    private String target;
    private boolean running, down, suspended, statusBarGesture;
    private float lastX, lastY, lastProgress;
    private Runnable completion;
    private Runnable failure;
    private final MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
    private final MotionEvent.PointerCoords[] coordinates = {new MotionEvent.PointerCoords()};

    EdgeGoInputSession(InputManager input, Choreographer choreographer, Host host) {
        this.input = input;
        this.choreographer = choreographer;
        this.host = host;
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        coordinates[0].size = .2f;
    }

    boolean running() { return running; }
    boolean suspended() { return suspended; }
    boolean isStatusBarGesture() { return running && down && statusBarGesture; }

    void suspend() {
        if (!running) return;
        choreographer.removeFrameCallback(frame);
        if (down) {
            send(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis(), lastX, lastY);
            float position = lastProgress * (path.size() - 1);
            int next = Math.min(path.size(), (int) position + 1);
            List<float[]> remaining = new ArrayList<>();
            remaining.add(new float[]{lastX, lastY});
            for (int i = next; i < path.size(); i++) remaining.add(path.get(i));
            path = remaining;
            duration = Math.max(40, Math.round(duration * (1 - lastProgress)));
        }
        running = down = false;
        suspended = true;
        host.prepareInjection(false);
    }

    boolean resume() {
        if (!suspended || path.isEmpty() || !host.canInject(target, user) || host.taskId() != task) return false;
        suspended = false;
        running = true;
        lastProgress = 0;
        framesToWait = 2;
        host.prepareInjection(true);
        choreographer.postFrameCallback(frame);
        return true;
    }

    boolean play(List<float[]> points, long duration, Runnable completion) {
        return play(points, duration, completion, null);
    }

    boolean play(List<float[]> points, long duration, Runnable completion, Runnable failure) {
        return play(points, duration, completion, failure, false);
    }

    boolean playCursor(List<float[]> points, long duration, Runnable completion) {
        return play(points, duration, completion, null, true);
    }

    private boolean play(List<float[]> points, long duration, Runnable completion, Runnable failure, boolean cursor) {
        cancel();
        if (points == null || points.isEmpty()) return false;
        target = host.foreground();
        user = host.userId();
        task = host.taskId();
        if (!host.canInject(target, user)) return false;
        Rect bounds = cursor ? host.cursorBounds() : host.inputBounds();
        if (bounds.isEmpty()) return false;
        path = new ArrayList<>();
        for (float[] point : points) {
            if (path.size() == 256) break;
            if (point == null || point.length < 2 || !Float.isFinite(point[0]) || !Float.isFinite(point[1])) continue;
            path.add(new float[]{Math.max(bounds.left + 1, Math.min(bounds.right - 1, point[0])),
                    Math.max(bounds.top + 1, Math.min(bounds.bottom - 1, point[1]))});
        }
        if (path.isEmpty()) return false;
        statusBarGesture = cursor && path.get(0)[1] < host.inputBounds().top;
        this.duration = Math.max(40, Math.min(duration, 5000));
        this.completion = completion;
        this.failure = failure;
        framesToWait = 2;
        running = true;
        down = false;
        lastProgress = 0;
        host.prepareInjection(true);
        choreographer.postFrameCallback(frame);
        return true;
    }

    boolean tap(float x, float y, long hold, Runnable completion) {
        List<float[]> points = new ArrayList<>();
        points.add(new float[]{x, y});
        points.add(new float[]{x, y});
        return play(points, hold, completion);
    }

    boolean tapCursor(float x, float y, long hold, Runnable completion) {
        List<float[]> points = new ArrayList<>();
        points.add(new float[]{x, y});
        points.add(new float[]{x, y});
        return playCursor(points, hold, completion);
    }

    private void frame(long frameTime) {
        if (!running) return;
        boolean allowed = isStatusBarGesture() ? host.canContinueStatusBarGesture(target, user)
                : host.canInject(target, user);
        if (!allowed || host.taskId() != task) { fail(); return; }
        if (framesToWait-- > 0) { choreographer.postFrameCallback(frame); return; }
        long now = SystemClock.uptimeMillis();
        if (!down) {
            start = now;
            host.markInjectedGesture(start);
            lastX = path.get(0)[0];
            lastY = path.get(0)[1];
            down = send(MotionEvent.ACTION_DOWN, now, lastX, lastY);
            if (!down) { fail(); return; }
        }
        float progress = Math.min(1f, (now - start) / (float) duration);
        float position = progress * (path.size() - 1);
        int index = Math.min(path.size() - 1, (int) position);
        float[] first = path.get(index);
        float[] second = path.get(Math.min(path.size() - 1, index + 1));
        float fraction = position - index;
        lastX = first[0] + (second[0] - first[0]) * fraction;
        lastY = first[1] + (second[1] - first[1]) * fraction;
        if (!send(progress >= 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE, now, lastX, lastY)) {
            fail();
            return;
        }
        lastProgress = progress;
        if (progress >= 1) {
            running = down = statusBarGesture = false;
            host.prepareInjection(false);
            Runnable done = completion;
            completion = null;
            failure = null;
            if (done != null) done.run();
        } else {
            choreographer.postFrameCallback(frame);
        }
    }

    private boolean send(int action, long time, float x, float y) {
        coordinates[0].x = x;
        coordinates[0].y = y;
        coordinates[0].pressure = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ? 0 : 1;
        MotionEvent event = MotionEvent.obtain(start, time, action, 1,
                properties, coordinates,
                0, 0, 1f, 1f, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.FLAG_IS_GENERATED_GESTURE);
        event.setDisplayId(Display.DEFAULT_DISPLAY);
        try { return input.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC); }
        catch (RuntimeException e) { return false; }
        finally { event.recycle(); }
    }

    private void fail() {
        Runnable failed = failure;
        cancel();
        if (failed != null) failed.run();
    }

    void cancel() {
        choreographer.removeFrameCallback(frame);
        if (down) send(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis(), lastX, lastY);
        boolean wasRunning = running;
        down = running = suspended = statusBarGesture = false;
        completion = null;
        failure = null;
        path.clear();
        if (wasRunning) host.prepareInjection(false);
    }

    void key(int keyCode) {
        long when = SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent event = new KeyEvent(when, when, action, keyCode, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY, InputDevice.SOURCE_KEYBOARD);
            event.setDisplayId(Display.DEFAULT_DISPLAY);
            try { input.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC); }
            catch (RuntimeException e) { return; }
        }
    }
}
