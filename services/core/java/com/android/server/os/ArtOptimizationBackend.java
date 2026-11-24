/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.os;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.CancellationSignal;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Slog;
import com.android.server.LocalManagerRegistry;
import com.android.server.pm.PackageManagerLocal;
import com.android.server.art.ArtManagerLocal;
import com.android.server.art.model.DexoptParams;
import com.android.server.art.model.DexoptResult;
import com.android.server.art.model.ArtFlags;
import com.android.server.art.model.DexoptStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.function.Consumer;

final class ArtOptimizationBackend {
    private final Context context;
    ArtOptimizationBackend(Context context) { this.context = context; }

    static DexoptParams perAppParams(String filter) {
        return new DexoptParams.Builder("cmdline")
                .setFlags(ArtFlags.FLAG_FOR_PRIMARY_DEX | ArtFlags.FLAG_SKIP_IF_STORAGE_LOW)
                .setPriorityClass(ArtFlags.PRIORITY_BACKGROUND)
                .setCompilerFilter(filter).build();
    }

    static boolean eligible(ApplicationInfo app) {
        return app != null && (app.flags & ApplicationInfo.FLAG_HAS_CODE) != 0
                && !app.isInstantApp() && !"android".equals(app.packageName);
    }

    Bundle appStatus(String packageName) {
        ArtManagerLocal art = LocalManagerRegistry.getManager(ArtManagerLocal.class);
        PackageManagerLocal pm = LocalManagerRegistry.getManager(PackageManagerLocal.class);
        if (art == null || pm == null) throw new UnsupportedOperationException("ART Service is not ready");
        try (PackageManagerLocal.FilteredSnapshot snapshot = pm.withFilteredSnapshot()) {
            DexoptStatus status = art.getDexoptStatus(snapshot, packageName, ArtFlags.FLAG_FOR_PRIMARY_DEX);
            LinkedHashSet<String> filters = new LinkedHashSet<>();
            List<String> details = new ArrayList<>();
            boolean missing = false, error = false;
            for (DexoptStatus.DexContainerFileDexoptStatus item : status.getDexContainerFileDexoptStatuses()) {
                String filter = item.getCompilerFilter();
                filters.add(filter);
                missing |= filter.startsWith("run-from-apk");
                error |= "error".equals(filter);
                details.add(new java.io.File(item.getDexContainerFile()).getName() + " ("
                        + item.getAbi() + "): " + filter + " · " + item.getCompilationReason());
            }
            Bundle row = new Bundle();
            row.putString("current_filter", limited(String.join(", ", filters)));
            row.putString("current_status", error ? "unavailable" : filters.isEmpty() || missing
                    ? "not_compiled" : filters.size() > 1 ? "mixed" : "compiled");
            row.putString("current_detail", limited(String.join("\n", details)));
            return row;
        }
    }

    Bundle compileApp(String packageName, String filter, CancellationSignal cancel) {
        cancel.throwIfCanceled();
        if ("speed".equals(filter) || "everything".equals(filter)) {
            OptimizationActions.enforceFullCompilationPower(context);
        }
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power != null && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
            throw new IllegalStateException("Compilation stopped because the device is too warm");
        }
        Bundle row = new Bundle();
        row.putString("package", packageName);
        row.putString("requested_filter", filter);
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(packageName, 0);
            if (!eligible(app)) throw new IllegalArgumentException("App has no eligible code to compile");
            ArtManagerLocal art = LocalManagerRegistry.getManager(ArtManagerLocal.class);
            PackageManagerLocal pm = LocalManagerRegistry.getManager(PackageManagerLocal.class);
            if (art == null || pm == null) throw new UnsupportedOperationException("ART Service is not ready");
            try (PackageManagerLocal.FilteredSnapshot snapshot = pm.withFilteredSnapshot()) {
                DexoptResult result = art.dexoptPackage(snapshot, packageName, perAppParams(filter), cancel);
                int status = result.getFinalStatus();
                if (status == DexoptResult.DEXOPT_CANCELLED) throw new android.os.OperationCanceledException();
                row.putString("status", status == DexoptResult.DEXOPT_PERFORMED ? "compiled"
                        : status == DexoptResult.DEXOPT_SKIPPED ? "skipped" : "failed");
                List<String> details = new ArrayList<>();
                for (DexoptResult.PackageDexoptResult pkg : result.getPackageDexoptResults()) {
                    for (DexoptResult.DexContainerFileDexoptResult item : pkg.getDexContainerFileDexoptResults()) {
                        if (item.getStatus() == DexoptResult.DEXOPT_FAILED) {
                            details.add("ART compilation failed for "
                                    + new java.io.File(item.getDexContainerFile()).getName()
                                    + " (" + item.getAbi() + ", " + item.getActualCompilerFilter()
                                    + "). See ArtService logs for the compiler error.");
                        }
                        if ((item.getExtendedStatusFlags() & DexoptResult.EXTENDED_SKIPPED_STORAGE_LOW) != 0) {
                            details.add("Skipped because available storage is low.");
                        }
                        if (!item.getExternalProfileErrors().isEmpty()) {
                            details.add("An external profile could not be used. See ArtService logs for details.");
                            Slog.w("OptimizationManager", packageName + ": " + item.getExternalProfileErrors());
                        }
                    }
                }
                if (details.isEmpty() && status == DexoptResult.DEXOPT_FAILED) {
                    details.add("ART could not compile this app. See ArtService logs for the compiler error.");
                } else if (details.isEmpty() && status == DexoptResult.DEXOPT_SKIPPED) {
                    details.add("ART kept existing code or found no work for the requested filter.");
                }
                row.putString("detail", limited(String.join("\n", new LinkedHashSet<>(details))));
            }
        } catch (android.os.OperationCanceledException e) {
            row.putString("status", "cancelled");
            row.putString("detail", "Compilation cancelled");
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
            cancel.throwIfCanceled();
            row.putString("status", "failed");
            row.putString("detail", limited(e.getClass().getSimpleName() + ": " + e.getMessage()));
            Slog.w("OptimizationManager", "Per-app compilation failed: " + packageName, e);
        }
        try { row.putAll(appStatus(packageName)); }
        catch (RuntimeException e) {
            row.putString("current_filter", "");
            row.putString("current_status", "unavailable");
            row.putString("current_detail", limited("Unable to read ART state: " + e.getMessage()));
        }
        return row;
    }

    private static String limited(String value) {
        return value == null ? "" : value.substring(0, Math.min(2000, value.length()));
    }
    OptimizationResult compile(String filter, CancellationSignal cancel, Consumer<String> progress, Consumer<android.os.Bundle> packageProgress) {
        ArtManagerLocal art = LocalManagerRegistry.getManager(ArtManagerLocal.class);
        PackageManagerLocal pm = LocalManagerRegistry.getManager(PackageManagerLocal.class);
        if (art == null || pm == null) throw new UnsupportedOperationException("ART Service is not ready");
        List<PackageInfo> packages = new ArrayList<>(context.getPackageManager().getInstalledPackages(0));
        packages.removeIf(pkg -> pkg.applicationInfo == null
                || (pkg.applicationInfo.flags & ApplicationInfo.FLAG_HAS_CODE) == 0
                || "android".equals(pkg.packageName));
        long now = System.currentTimeMillis();
        java.util.Map<String, android.app.usage.UsageStats> usage = java.util.Collections.emptyMap();
        if ("adaptive".equals(filter)) {
            android.app.usage.UsageStatsManager manager = context.getSystemService(android.app.usage.UsageStatsManager.class);
            if (manager != null) {
                java.util.Map<String, android.app.usage.UsageStats> queried =
                        manager.queryAndAggregateUsageStats(now - 14L * 86400000, now);
                if (queried != null) usage = queried;
            }
        }
        packages.sort(Comparator.comparing(p -> p.packageName));
        int performed = 0, skipped = 0, index = 0;
        List<String> failures = new ArrayList<>();
        for (PackageInfo pkg : packages) {
            cancel.throwIfCanceled();
            android.app.usage.UsageStats stats = usage.get(pkg.packageName);
            String selected = "adaptive".equals(filter)
                    ? UsageDexoptPolicy.select(stats != null, stats == null ? 0 : stats.getTotalTimeInForeground(),
                            stats == null ? 0 : stats.getLastTimeUsed(), now) : filter;
            DexoptParams params = new DexoptParams.Builder("cmdline").setCompilerFilter(selected).build();
            if ("speed".equals(selected)) {
                OptimizationActions.enforceFullCompilationPower(context);
            }
            PowerManager power = context.getSystemService(PowerManager.class);
            if (power != null && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
                throw new IllegalStateException("Compilation stopped because the device is too warm");
            }
            android.os.Bundle row = new android.os.Bundle();
            row.putString("package", pkg.packageName);
            row.putString("filter", selected);
            row.putString("status", "running");
            row.putInt("total", packages.size());
            row.putInt("done", index);
            packageProgress.accept(row);
            progress.accept("App " + (++index) + " / " + packages.size() + ": " + pkg.packageName
                + " · " + selected + " · compiled " + performed + " · skipped " + skipped + " · failed " + failures.size());
            try (PackageManagerLocal.FilteredSnapshot snapshot = pm.withFilteredSnapshot()) {
                int status = dexoptPackage(
                        () -> art.dexoptPackage(snapshot, pkg.packageName, params, cancel).getFinalStatus(),
                        cancel, e -> Slog.w("OptimizationManager", "ART rejected package="
                                + pkg.packageName + ", filter=" + selected, e));
                row.putString("status", status == DexoptResult.DEXOPT_PERFORMED ? "compiled"
                        : status == DexoptResult.DEXOPT_SKIPPED ? "skipped"
                        : status == DexoptResult.DEXOPT_CANCELLED ? "cancelled" : "failed");
                row.putInt("done", status == DexoptResult.DEXOPT_CANCELLED ? index - 1 : index);
                packageProgress.accept(row);
                if (status == DexoptResult.DEXOPT_CANCELLED) throw new android.os.OperationCanceledException();
                if (status == DexoptResult.DEXOPT_PERFORMED) performed++;
                else if (status == DexoptResult.DEXOPT_SKIPPED) skipped++;
                else {
                    failures.add(pkg.packageName);
                    Slog.w("OptimizationManager", "ART compilation failed: package=" + pkg.packageName
                            + ", filter=" + selected + ", status=" + status
                            + "; see preceding ArtService logs for the cause");
                }
            } catch (RuntimeException e) {
                row.putString("status", e instanceof android.os.OperationCanceledException ? "cancelled" : "failed");
                packageProgress.accept(row);
                throw e;
            }
        }
        return OptimizationResult.compilation(performed, skipped, failures);
    }

    static int dexoptPackage(java.util.function.IntSupplier operation, CancellationSignal cancel,
            Consumer<IllegalArgumentException> rejected) {
        try {
            return operation.getAsInt();
        } catch (IllegalArgumentException e) {
            cancel.throwIfCanceled();
            rejected.accept(e);
            return DexoptResult.DEXOPT_FAILED;
        }
    }
}
