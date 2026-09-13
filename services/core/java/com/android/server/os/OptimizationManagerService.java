/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.os;

import android.app.ActivityManager;
import android.content.Context;
import android.os.*;
import android.util.Slog;
import com.android.server.SystemService;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

public final class OptimizationManagerService extends SystemService {
    private static final String TAG = "OptimizationManager";
    private static final String ZRAM_MM_STAT = "/sys/block/zram0/mm_stat";
    private final Object lock = new Object();
    private final HandlerThread thread = new HandlerThread("optimization-engine");
    private final RemoteCallbackList<IOptimizationCallback> callbacks = new RemoteCallbackList<>();
    private Handler worker;
    private Bundle snapshot = new Bundle();
    private CancellationSignal cancellation = new CancellationSignal();
    private boolean busy;
    private long nextId;
    private OptimizationActions actions;
    private long lastNotification;
    private static final int MAX_PACKAGE_RESULTS = 300;
    private final ArrayList<Bundle> packageResults = new ArrayList<>();

    public OptimizationManagerService(Context context) { super(context); }
    @Override public void onStart() {
        actions = new OptimizationActions(getContext());
        thread.start(); worker = new Handler(thread.getLooper());
        snapshot.putString("status", "idle");
        publishBinderService(Context.OPTIMIZATION_SERVICE, binder);
    }
    private void enforcePermission() {
        getContext().enforceCallingPermission(
            "android.permission.MANAGE_DEVICE_OPTIMIZATION", "Device maintenance is privileged");
        if (UserHandle.getCallingUserId() != UserHandle.USER_SYSTEM) {
            throw new SecurityException("Device maintenance is restricted to the owner user");
        }
    }
    private final IOptimizationEngine.Stub binder = new IOptimizationEngine.Stub() {
        @Override public Bundle getSnapshot() {
            enforcePermission();
            synchronized (lock) { return snapshot.deepCopy(); }
        }
        @Override public Bundle getMetrics() {
            enforcePermission();
            long token = Binder.clearCallingIdentity();
            try { return scanMetrics(); } finally { Binder.restoreCallingIdentity(token); }
        }
        @Override public void refreshMetrics() {
            enforcePermission(); worker.post(() -> notifyMetrics(scanMetrics()));
        }
        @Override public long startConfigured(Bundle options) {
            enforcePermission();
            OptimizationPlan plan = OptimizationPlan.parse(options);
            synchronized (lock) {
                if (busy) throw new IllegalStateException("A maintenance run is already active");
                busy = true;
                cancellation = new CancellationSignal();
                snapshot = new Bundle();
                packageResults.clear();
                snapshot.putLong("id", ++nextId);
                snapshot.putLong("started", SystemClock.elapsedRealtime());
                snapshot.putString("status", "running");
                snapshot.putStringArrayList("actions", new ArrayList<>(plan.actions));
                for (String action : plan.actions) snapshot.putString(action + ".status", "queued");
                worker.post(() -> execute(plan));
                return nextId;
            }
        }
        @Override public void startOptimization(int mode, IOptimizationCallback callback) {
            enforcePermission();
            if (mode != 0 && mode != 1) throw new IllegalArgumentException("Unknown mode");
            Bundle options = new Bundle();
            options.putBoolean("trim", true); options.putBoolean("dexopt", true);
            options.putString("filter", mode == 1 ? "speed" : "speed-profile");
            startConfigured(options);
            if (callback != null) callbacks.register(callback);
        }
        @Override public void cancelOptimization() {
            enforcePermission();
            CancellationSignal signal;
            synchronized (lock) {
                if (!busy) return;
                snapshot.putString("status", "cancelling"); signal = cancellation;
            }
            signal.cancel();
            new Handler(Looper.getMainLooper()).post(() -> updateNotification(true));
        }
        @Override public void registerCallback(IOptimizationCallback callback) {
            enforcePermission(); if (callback != null) callbacks.register(callback);
        }
        @Override public void unregisterCallback(IOptimizationCallback callback) {
            enforcePermission(); if (callback != null) callbacks.unregister(callback);
        }
    };
    private void execute(OptimizationPlan plan) {
        int completed = 0;
        boolean errors = false;
        notifyState(true);
        updateNotification(true);
        try {
            for (String action : plan.actions) {
                if (cancellation.isCanceled()) break;
                result(action, "running", "");
                try {
                    OptimizationResult outcome = actions.execute(action, plan, cancellation,
                        message -> result(action, "running", message), this::packageProgress);
                    result(action, outcome.status, outcome.detail);
                    if (outcome.hasErrors()) {
                        errors = true;
                        if (!plan.continueOnError) break;
                    }
                } catch (OperationCanceledException e) {
                    result(action, "cancelled", "Cancellation acknowledged"); break;
                } catch (UnsupportedOperationException e) {
                    errors = true; result(action, "unavailable", e.getMessage());
                    if (!plan.continueOnError) break;
                } catch (Exception e) {
                    errors = true; result(action, "failed", e.getClass().getSimpleName() + ": " + e.getMessage());
                    Slog.w(TAG, "Maintenance action failed: " + action, e);
                    if (!plan.continueOnError) break;
                }
                synchronized (lock) { snapshot.putFloat("progress", ++completed / (float) plan.actions.size()); }
                updateNotification(true);
            }
        } finally {
            synchronized (lock) {
                for (String action : plan.actions) {
                    if ("queued".equals(snapshot.getString(action + ".status"))) {
                        snapshot.putString(action + ".status", "skipped");
                        snapshot.putString(action + ".detail", "Run stopped before this action");
                    }
                }
                snapshot.putString("status", cancellation.isCanceled() ? "cancelled" : errors ? "partial" : "completed");
                snapshot.putLong("finished", SystemClock.elapsedRealtime());
                busy = false;
            }
            updateNotification(true);
            notifyState(false);
        }
    }
    private void result(String action, String status, String detail) {
        synchronized (lock) {
            snapshot.putString(action + ".status", status);
            snapshot.putString(action + ".detail", detail == null ? "" : detail.substring(0, Math.min(2000, detail.length())));
        }
        updateNotification(!"running".equals(status) || detail == null || detail.isEmpty());
    }
    private void packageProgress(Bundle row) {
        synchronized (lock) {
            String name = row.getString("package");
            int existing = -1;
            for (int i = 0; i < packageResults.size(); i++) {
                if (name.equals(packageResults.get(i).getString("package"))) { existing = i; break; }
            }
            if (existing >= 0) packageResults.set(existing, new Bundle(row));
            else if (packageResults.size() < MAX_PACKAGE_RESULTS) packageResults.add(new Bundle(row));
            else snapshot.putBoolean("packages_truncated", true);
            snapshot.putParcelableArrayList("packages", new ArrayList<>(packageResults));
            ArrayList<String> ids = snapshot.getStringArrayList("actions");
            int position = ids.indexOf("dexopt");
            float fraction = row.getInt("done") / (float) Math.max(1, row.getInt("total"));
            snapshot.putFloat("progress", (position + fraction) / ids.size());
            snapshot.putString("dexopt.detail", "App " + row.getInt("done") + " / " + row.getInt("total")
                    + ": " + name + " · " + row.getString("filter") + " · " + row.getString("status"));
        }
        updateNotification(false);
    }
    private synchronized void updateNotification(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastNotification < 500) return;
        lastNotification = now;
        Bundle current;
        synchronized (lock) { current = snapshot.deepCopy(); }
        try {
            android.app.NotificationManager manager = getContext().getSystemService(android.app.NotificationManager.class);
            if (manager == null) return;
            String channel = "optimization_engine_progress";
            manager.createNotificationChannel(new android.app.NotificationChannel(channel,
                    "Optimization Engine", android.app.NotificationManager.IMPORTANCE_LOW));
            String status = current.getString("status", "idle");
            boolean active = "running".equals(status) || "cancelling".equals(status);
            String detail = status;
            for (String action : current.getStringArrayList("actions")) {
                if ("running".equals(current.getString(action + ".status"))) {
                    detail = action + ": " + current.getString(action + ".detail", "");
                }
            }
            android.content.Intent intent = new android.content.Intent()
                    .setComponent(new android.content.ComponentName("com.lunaris.optimizer", "com.lunaris.optimizer.MainActivity"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent open = android.app.PendingIntent.getActivityAsUser(getContext(), 0,
                    intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE,
                    null, UserHandle.SYSTEM);
            android.app.Notification.Builder builder = new android.app.Notification.Builder(getContext(), channel)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Optimization Engine · " + status)
                    .setContentText(detail).setStyle(new android.app.Notification.BigTextStyle().bigText(detail))
                    .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(active).setAutoCancel(!active)
                    .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
                    .setProgress(active ? 100 : 0, Math.round(current.getFloat("progress") * 100), false);
            manager.notifyAsUser(TAG, 1001, builder.build(), UserHandle.SYSTEM);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to publish optimization notification", e);
        }
    }
    private void notifyState(boolean active) {
        int count = callbacks.beginBroadcast();
        try { for (int i = 0; i < count; i++) {
            try { callbacks.getBroadcastItem(i).onStateChanged(active); } catch (RemoteException ignored) { }
        } } finally { callbacks.finishBroadcast(); }
    }
    private void notifyMetrics(Bundle metrics) {
        int count = callbacks.beginBroadcast();
        try { for (int i = 0; i < count; i++) {
            try { callbacks.getBroadcastItem(i).onMetricsUpdated(metrics); } catch (RemoteException ignored) { }
        } } finally { callbacks.finishBroadcast(); }
    }
    private String readFile(String path) {
        try { return java.nio.file.Files.readString(new File(path).toPath(), StandardCharsets.US_ASCII); }
        catch (Exception e) { return null; }
    }
    private Bundle scanMetrics() {
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        ActivityManager am = getContext().getSystemService(ActivityManager.class);
        if (am != null) {
            am.getMemoryInfo(memInfo);
        }
        int memAvailMb = (int) (memInfo.availMem / (1024 * 1024));

        long zramMb = 0;
        String mmStat = readFile(ZRAM_MM_STAT);
        if (mmStat != null) {
            String[] zParts = mmStat.trim().split("\\s+");
            if (zParts.length >= 3) {
                try {
                    zramMb = Long.parseLong(zParts[2]) / (1024 * 1024);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        String storage = "—";
        try {
            StatFs statFs = new StatFs("/data");
            long availBytes = statFs.getAvailableBytes();
            storage = String.format(Locale.US, "%.1fGB", availBytes / (1024.0 * 1024 * 1024));
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "StatFs(/data) failed", e);
        }

        Bundle b = new Bundle();
        if (am != null) b.putInt("ram_available_mb", memAvailMb);
        b.putLong("zram_used_mb", zramMb);
        b.putString("storage_available", storage);
        b.putInt("health_score", -1);
        b.putBoolean("art_available", false);
        if (am != null) b.putLong("ram_total_mb", memInfo.totalMem / (1024 * 1024));
        return b;
    }

}
