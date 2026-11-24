/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.os;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.SystemClock;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PerAppDexoptController {
    private final Context context;
    private final Object lock;
    private final ArtOptimizationBackend backend;
    private final Map<String, Bundle> apps = new LinkedHashMap<>();
    private final ArrayList<String> filters = new ArrayList<>();
    private final Bundle state = new Bundle();
    private CancellationSignal cancellation = new CancellationSignal();
    private long nextId;
    private long revision;

    PerAppDexoptController(Context context, Object lock) {
        this.context = context;
        this.lock = lock;
        backend = new ArtOptimizationBackend(context);
        for (String filter : Arrays.asList("speed-profile", "speed", "verify", "space-profile",
                "space", "everything-profile", "everything")) {
            try {
                ArtOptimizationBackend.perAppParams(filter);
                filters.add(filter);
            } catch (IllegalArgumentException ignored) { }
            catch (LinkageError e) {
                Slog.w("OptimizationManager", "Per-app ART adapter is unavailable", e);
                break;
            }
        }
        state.putString("status", "idle");
    }

    boolean active() {
        synchronized (lock) {
            String status = state.getString("status");
            return "loading".equals(status) || "running".equals(status) || "cancelling".equals(status);
        }
    }

    void beginScan() {
        synchronized (lock) {
            if (active()) throw new IllegalStateException("Per-app dexopt is already active");
            state.putString("status_before_scan", state.getString("status", "ready"));
            state.putString("detail_before_scan", state.getString("detail", ""));
            state.putString("status", "loading");
            state.putString("detail", "Reading app compilation state");
            revision++;
        }
    }

    void scan() {
        try {
            List<ApplicationInfo> installed = new ArrayList<>(
                    context.getPackageManager().getInstalledApplications(0));
            installed.removeIf(app -> !ArtOptimizationBackend.eligible(app));
            installed.sort((a, b) -> a.packageName.compareTo(b.packageName));
            Map<String, Bundle> found = new LinkedHashMap<>();
            for (ApplicationInfo app : installed) {
                Bundle row = new Bundle();
                synchronized (lock) {
                    Bundle prior = apps.get(app.packageName);
                    if (prior != null) row.putAll(prior);
                }
                row.putString("package", app.packageName);
                CharSequence label = app.loadLabel(context.getPackageManager());
                String title = label == null ? app.packageName : label.toString();
                row.putString("title", title.substring(0, Math.min(255, title.length())));
                row.putBoolean("system", (app.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
                if (!row.containsKey("status")) row.putString("status", "ready");
                try { row.putAll(backend.appStatus(app.packageName)); }
                catch (RuntimeException e) {
                    row.putString("current_filter", "");
                    row.putString("current_status", "unavailable");
                    row.putString("current_detail", message(e));
                }
                found.put(app.packageName, row);
            }
            synchronized (lock) {
                apps.clear();
                apps.putAll(found);
                state.putString("status", nextId == 0 ? "ready"
                        : state.getString("status_before_scan", "ready"));
                state.putString("detail", nextId == 0 ? "Found " + found.size() + " apps with compilable code"
                        : state.getString("detail_before_scan", ""));
                state.remove("status_before_scan");
                state.remove("detail_before_scan");
                state.putBoolean("loaded", true);
                revision++;
            }
        } catch (Exception | LinkageError e) {
            synchronized (lock) {
                state.putString("status", "failed");
                state.putString("detail", message(e));
                revision++;
            }
            Slog.w("OptimizationManager", "Cannot read per-app dexopt state", e);
        }
    }

    long beginCompilation(List<String> packages, String filter) {
        synchronized (lock) {
            if (active()) throw new IllegalStateException("Per-app dexopt is already active");
            if (packages.isEmpty()) throw new IllegalArgumentException("Select at least one app");
            if (!filters.contains(filter)) throw new IllegalArgumentException("Unsupported compiler filter");
            if (!state.getBoolean("loaded") || !apps.keySet().containsAll(packages)) {
                throw new IllegalArgumentException("Refresh the app list before compiling these apps");
            }
            cancellation = new CancellationSignal();
            state.putLong("id", ++nextId);
            state.putLong("started", SystemClock.elapsedRealtime());
            state.putLong("finished", 0);
            state.putInt("done", 0);
            state.putInt("selected_count", packages.size());
            state.putString("status", "running");
            state.putString("detail", "Starting selected app compilation");
            for (Bundle row : apps.values()) {
                row.putString("status", "ready");
                row.remove("detail");
                row.remove("requested_filter");
            }
            for (String name : packages) {
                Bundle row = apps.get(name);
                row.putString("status", "queued");
                row.putString("requested_filter", filter);
            }
            revision++;
            return nextId;
        }
    }

    void compile(List<String> packages, String filter) {
        int compiled = 0, skipped = 0, failed = 0;
        String stopped = "";
        boolean wasCancelled = false;
        try {
            for (String name : packages) {
                cancellation.throwIfCanceled();
                synchronized (lock) {
                    apps.get(name).putString("status", "running");
                    state.putString("detail", "App " + (state.getInt("done") + 1) + " / "
                            + packages.size() + ": " + name + " · " + filter);
                    revision++;
                }
                Bundle result = backend.compileApp(name, filter, cancellation);
                synchronized (lock) {
                    apps.get(name).putAll(result);
                    state.putInt("done", state.getInt("done") + 1);
                    revision++;
                }
                switch (result.getString("status", "failed")) {
                    case "compiled": compiled++; break;
                    case "skipped": skipped++; break;
                    case "cancelled": throw new OperationCanceledException();
                    default: failed++;
                }
            }
        } catch (OperationCanceledException e) {
            stopped = "Compilation cancelled";
            wasCancelled = true;
        } catch (Exception | LinkageError e) {
            stopped = message(e);
            failed++;
            Slog.w("OptimizationManager", "Per-app dexopt stopped", e);
        } finally {
            synchronized (lock) {
                boolean cancelled = wasCancelled || cancellation.isCanceled()
                        || "cancelling".equals(state.getString("status"));
                for (String name : packages) {
                    Bundle row = apps.get(name);
                    String status = row.getString("status");
                    if ("queued".equals(status) || "running".equals(status)) {
                        row.putString("status", cancelled ? "cancelled"
                                : "running".equals(status) ? "failed" : "skipped");
                        row.putString("detail", stopped.isEmpty() ? "Run stopped before this app" : stopped);
                        if (!cancelled && "queued".equals(status)) skipped++;
                    }
                }
                state.putString("status", cancelled ? "cancelled" : failed > 0 ? "partial" : "completed");
                state.putString("detail", "Compiled " + compiled + " · skipped " + skipped + " · failed "
                        + failed + (stopped.isEmpty() ? "" : "\n" + stopped));
                state.putLong("finished", SystemClock.elapsedRealtime());
                revision++;
            }
        }
    }

    void cancel() {
        CancellationSignal signal;
        synchronized (lock) {
            if (!"running".equals(state.getString("status"))) return;
            state.putString("status", "cancelling");
            revision++;
            signal = cancellation;
        }
        signal.cancel();
    }

    Bundle snapshot(int offset) {
        if (offset < 0) throw new IllegalArgumentException("Invalid app offset");
        synchronized (lock) {
            Bundle page = new Bundle(state);
            page.putLong("revision", revision);
            page.putStringArrayList("filters", new ArrayList<>(filters));
            page.putInt("total", apps.size());
            ArrayList<Bundle> rows = new ArrayList<>();
            if (offset < apps.size()) {
                int index = 0;
                for (Bundle row : apps.values()) {
                    if (index++ < offset) continue;
                    rows.add(new Bundle(row));
                    if (rows.size() == 50) break;
                }
            }
            page.putParcelableArrayList("rows", rows);
            return page;
        }
    }

    private static String message(Throwable e) {
        String value = e.getClass().getSimpleName() + ": " + e.getMessage();
        return value.substring(0, Math.min(2000, value.length()));
    }
}
