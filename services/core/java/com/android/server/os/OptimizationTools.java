/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.os;

import android.app.ActivityManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Debug;
import android.os.UserHandle;
import android.text.format.DateUtils;
import android.util.ArrayMap;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class OptimizationTools {
    static final Set<String> KINDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "app_cache", "data_cache", "rare_apps", "resources")));
    private static final String[] DATA_CACHE_PATHS = {
            "/data/resources_cache", "/data/system/package_cache"
    };
    private static final int MAX_PACKAGES = 1000;
    private static final long RARE_AGE_MS = DateUtils.DAY_IN_MILLIS;
    private static final String OPTIMIZER_PACKAGE = "com.lunaris.optimizer";
    private final Context context;

    OptimizationTools(Context context) { this.context = context; }

    static List<String> validatePackages(String[] requested) {
        if (requested == null || requested.length == 0) return Collections.emptyList();
        if (requested.length > MAX_PACKAGES) throw new IllegalArgumentException("Too many packages");
        ArrayList<String> result = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String name : requested) {
            if (name == null || name.length() > 255
                    || !name.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) {
                throw new IllegalArgumentException("Invalid package name");
            }
            if (seen.add(name)) result.add(name);
        }
        return result;
    }

    Bundle run(String kind, List<String> packages, CancellationSignal cancel,
            Consumer<String> progress) throws Exception {
        switch (kind) {
            case "app_cache": return scanAppCaches(cancel);
            case "data_cache": return cleanDataCaches(cancel, progress);
            case "rare_apps": return packages.isEmpty()
                    ? scanRareApps(cancel) : stopRareApps(packages, cancel);
            case "resources": return scanResources(cancel);
            default: throw new IllegalArgumentException("Unknown tool");
        }
    }

    OptimizationResult cleanAppCaches(List<String> packages, CancellationSignal cancel,
            Consumer<String> progress) throws Exception {
        PackageManager pm = context.getPackageManager();
        int cleaned = 0;
        ArrayList<String> failures = new ArrayList<>();
        for (int i = 0; i < packages.size(); i++) {
            cancel.throwIfCanceled();
            String packageName = packages.get(i);
            progress.accept("App " + (i + 1) + " / " + packages.size() + ": " + packageName);
            CountDownLatch latch = new CountDownLatch(1);
            boolean[] succeeded = new boolean[1];
            IPackageDataObserver observer = new IPackageDataObserver.Stub() {
                @Override public void onRemoveCompleted(String ignored, boolean success) {
                    succeeded[0] = success;
                    latch.countDown();
                }
            };
            try {
                pm.deleteApplicationCacheFilesAsUser(packageName, UserHandle.USER_SYSTEM, observer);
                if (!awaitCacheObserver(latch, cancel, 120, TimeUnit.SECONDS)) failures.add(packageName + " (timeout)");
                else if (!succeeded[0]) failures.add(packageName);
                else cleaned++;
            } catch (IllegalArgumentException | SecurityException e) {
                failures.add(packageName);
            }
        }
        String detail = "Cleaned cache for " + cleaned + " of " + packages.size() + " selected apps.";
        if (!failures.isEmpty()) {
            return OptimizationResult.partial(detail + " Failed: " + String.join(", ", failures));
        }
        return OptimizationResult.success(detail);
    }

    static boolean awaitCacheObserver(CountDownLatch latch, CancellationSignal cancel,
            long timeout, TimeUnit unit) throws InterruptedException {
        cancel.setOnCancelListener(latch::countDown);
        try {
            cancel.throwIfCanceled();
            boolean completed = latch.await(timeout, unit);
            cancel.throwIfCanceled();
            return completed;
        } finally {
            cancel.setOnCancelListener(null);
        }
    }

    private Bundle scanAppCaches(CancellationSignal cancel) throws Exception {
        PackageManager pm = context.getPackageManager();
        StorageStatsManager stats = context.getSystemService(StorageStatsManager.class);
        if (stats == null) throw new UnsupportedOperationException("Storage stats are unavailable");
        ArrayList<Bundle> rows = new ArrayList<>();
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            cancel.throwIfCanceled();
            try {
                StorageStats value = stats.queryStatsForPackage(
                        app.storageUuid, app.packageName, UserHandle.SYSTEM);
                long cacheBytes = Math.max(0L, value.getCacheBytes());
                long dataBytes = Math.max(0L, value.getDataBytes());
                Bundle row = row(app.packageName, app.loadLabel(pm).toString(),
                        app.packageName, cacheBytes);
                row.putLong("storage_bytes", Math.max(0L, value.getAppBytes())
                        + Math.max(0L, dataBytes - cacheBytes));
                rows.add(row);
            } catch (PackageManager.NameNotFoundException | java.io.IOException ignored) { }
        }
        rows.sort((a, b) -> Long.compare(b.getLong("bytes"), a.getLong("bytes")));
        return result(rows, "Found cache data for " + rows.size() + " apps.");
    }

    private Bundle cleanDataCaches(CancellationSignal cancel, Consumer<String> progress)
            throws Exception {
        long removedBytes = 0;
        int removedFiles = 0;
        for (String path : DATA_CACHE_PATHS) {
            cancel.throwIfCanceled();
            progress.accept("Cleaning " + path);
            File root = new File(path);
            if (!root.exists()) continue;
            if (Files.isSymbolicLink(root.toPath())) throw new SecurityException("Cache root is a symbolic link: " + path);
            File[] children = root.listFiles();
            if (children == null) throw new java.io.IOException("Cannot read " + path);
            for (File child : children) {
                long bytes = sizeOf(child, cancel);
                deleteTree(child, cancel);
                removedBytes += bytes;
                removedFiles++;
            }
        }
        return result(new ArrayList<>(), "Removed " + removedFiles + " entries ("
                + android.text.format.Formatter.formatFileSize(context, removedBytes)
                + "). Restart is recommended.");
    }

    private Bundle scanRareApps(CancellationSignal cancel) {
        PackageManager pm = context.getPackageManager();
        UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (usage == null || am == null) throw new UnsupportedOperationException("Usage data is unavailable");
        long now = System.currentTimeMillis();
        Map<String, UsageStats> history = usage.queryAndAggregateUsageStats(now - RARE_AGE_MS, now);
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        if (history == null) throw new UnsupportedOperationException("Usage data is unavailable");
        ArrayList<Bundle> rows = new ArrayList<>();
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            cancel.throwIfCanceled();
            if (!canStop(app) || !isBackgroundApp(app.uid, processes)) continue;
            UsageStats item = history.get(app.packageName);
            long lastUsed = item == null ? 0 : item.getLastTimeUsed();
            if (lastUsed != 0 && now - lastUsed < RARE_AGE_MS) continue;
            String detail = lastUsed == 0 ? "No foreground use recorded in the last 24 hours"
                    : "Last used " + DateUtils.getRelativeTimeSpanString(lastUsed, now, DateUtils.HOUR_IN_MILLIS);
            Bundle row = row(app.packageName, app.loadLabel(pm).toString(), detail, 0);
            row.putBoolean("background", true);
            rows.add(row);
        }
        rows.sort((a, b) -> a.getString("title").compareToIgnoreCase(b.getString("title")));
        return result(rows, "Found " + rows.size() + " rarely used apps active in the background.");
    }

    private Bundle stopRareApps(List<String> packages, CancellationSignal cancel) throws Exception {
        PackageManager pm = context.getPackageManager();
        ActivityManager am = context.getSystemService(ActivityManager.class);
        UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        if (am == null || usage == null) throw new UnsupportedOperationException("Usage data is unavailable");
        ArrayList<Bundle> rows = new ArrayList<>();
        int stopped = 0;
        for (String packageName : packages) {
            cancel.throwIfCanceled();
            ApplicationInfo app;
            try {
                app = pm.getApplicationInfo(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                rows.add(stopResult(packageName, packageName, "Skipped: app is no longer installed"));
                continue;
            }
            long now = System.currentTimeMillis();
            Map<String, UsageStats> history = usage.queryAndAggregateUsageStats(
                    now - RARE_AGE_MS, now);
            if (history == null) throw new UnsupportedOperationException("Usage data is unavailable");
            UsageStats item = history.get(packageName);
            long lastUsed = item == null ? 0 : item.getLastTimeUsed();
            if (!canStop(app) || (lastUsed != 0 && now - lastUsed < RARE_AGE_MS)
                    || !isBackgroundApp(app.uid, am.getRunningAppProcesses())) {
                rows.add(stopResult(packageName, app.loadLabel(pm).toString(),
                        "Skipped: app is no longer eligible for background cleanup"));
                continue;
            }
            cancel.throwIfCanceled();
            am.forceStopPackage(packageName);
            stopped++;
            rows.add(stopResult(packageName, app.loadLabel(pm).toString(), "Stopped"));
        }
        return result(rows, "Stopped " + stopped + " selected apps; skipped "
                + (rows.size() - stopped) + ". Scan again before selecting more apps.");
    }

    private static Bundle stopResult(String packageName, String label, String detail) {
        Bundle result = row(packageName, label, detail, 0);
        result.putBoolean("selectable", false);
        return result;
    }

    static boolean isBackgroundApp(int uid, List<ActivityManager.RunningAppProcessInfo> processes) {
        if (UserHandle.getUserId(uid) != UserHandle.USER_SYSTEM || processes == null) return false;
        boolean running = false;
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.uid != uid) continue;
            if (process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) return false;
            running = true;
        }
        return running;
    }

    private Bundle scanResources(CancellationSignal cancel) throws Exception {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) throw new UnsupportedOperationException("Activity manager is unavailable");
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
        processes = processes == null ? new ArrayList<>() : new ArrayList<>(processes);
        processes.removeIf(process -> OPTIMIZER_PACKAGE.equals(process.processName)
                || (process.processName != null
                        && process.processName.startsWith(OPTIMIZER_PACKAGE + ":"))
                || (process.pkgList != null
                        && Arrays.asList(process.pkgList).contains(OPTIMIZER_PACKAGE)));
        ArrayMap<Integer, Long> first = readProcessTicks(processes);
        long totalFirst = readTotalCpuTicks();
        Thread.sleep(400);
        cancel.throwIfCanceled();
        ArrayMap<Integer, Long> second = readProcessTicks(processes);
        long totalDelta = Math.max(1, readTotalCpuTicks() - totalFirst);
        int[] pids = new int[processes.size()];
        for (int i = 0; i < processes.size(); i++) pids[i] = processes.get(i).pid;
        Debug.MemoryInfo[] memory = am.getProcessMemoryInfo(pids);
        ArrayList<Bundle> rows = new ArrayList<>();
        int cores = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < processes.size(); i++) {
            ActivityManager.RunningAppProcessInfo process = processes.get(i);
            long delta = Math.max(0, second.getOrDefault(process.pid, 0L) - first.getOrDefault(process.pid, 0L));
            float cpu = Math.min(100f * cores, delta * 100f * cores / totalDelta);
            int memoryMb = i < memory.length ? memory[i].getTotalPss() / 1024 : 0;
            boolean background = process.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
            String packages = process.pkgList == null ? "System process" : String.join(", ", process.pkgList);
            Bundle row = row(Integer.toString(process.pid), process.processName, packages, 0);
            row.putFloat("cpu_percent", cpu);
            row.putInt("memory_mb", memoryMb);
            row.putBoolean("background", background);
            rows.add(row);
        }
        rows.sort((a, b) -> Float.compare(resourceScore(b), resourceScore(a)));
        return result(rows, "Analyzed " + rows.size() + " running processes.");
    }

    private boolean canStop(ApplicationInfo app) {
        return UserHandle.getUserId(app.uid) == UserHandle.USER_SYSTEM && UserHandle.isApp(app.uid)
                && (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_PERSISTENT)) == 0;
    }

    private static float resourceScore(Bundle row) {
        return row.getInt("memory_mb") + row.getFloat("cpu_percent") * 10f;
    }

    private static Bundle row(String id, String title, String detail, long bytes) {
        Bundle row = new Bundle();
        row.putString("id", id); row.putString("title", title); row.putString("detail", detail);
        row.putLong("bytes", bytes);
        return row;
    }

    private static Bundle result(ArrayList<Bundle> rows, String detail) {
        Bundle result = new Bundle();
        result.putParcelableArrayList("rows", rows); result.putString("detail", detail);
        return result;
    }

    private static long sizeOf(File file, CancellationSignal cancel) {
        cancel.throwIfCanceled();
        if (!file.isDirectory() || Files.isSymbolicLink(file.toPath())) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += sizeOf(child, cancel);
        return total;
    }

    private static void deleteTree(File file, CancellationSignal cancel) throws java.io.IOException {
        cancel.throwIfCanceled();
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children == null) throw new java.io.IOException("Cannot read " + file);
            for (File child : children) deleteTree(child, cancel);
        }
        if (!file.delete() && file.exists()) throw new java.io.IOException("Cannot delete " + file);
    }

    private static ArrayMap<Integer, Long> readProcessTicks(
            List<ActivityManager.RunningAppProcessInfo> processes) {
        ArrayMap<Integer, Long> values = new ArrayMap<>();
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            try {
                String stat = Files.readString(new File("/proc/" + process.pid + "/stat").toPath(), StandardCharsets.US_ASCII);
                int end = stat.lastIndexOf(')');
                String[] fields = stat.substring(end + 2).split("\\s+");
                values.put(process.pid, Long.parseLong(fields[11]) + Long.parseLong(fields[12]));
            } catch (Exception ignored) { }
        }
        return values;
    }

    private static long readTotalCpuTicks() throws java.io.IOException {
        String line = Files.readAllLines(new File("/proc/stat").toPath(), StandardCharsets.US_ASCII).get(0);
        long total = 0;
        for (String value : line.substring(3).trim().split("\\s+")) total += Long.parseLong(value);
        return total;
    }
}
