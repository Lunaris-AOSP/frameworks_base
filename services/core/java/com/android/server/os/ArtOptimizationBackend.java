/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.os;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.CancellationSignal;
import android.os.PowerManager;
import android.util.Slog;
import com.android.server.LocalManagerRegistry;
import com.android.server.pm.PackageManagerLocal;
import com.android.server.art.ArtManagerLocal;
import com.android.server.art.model.DexoptParams;
import com.android.server.art.model.DexoptResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

final class ArtOptimizationBackend {
    private final Context context;
    ArtOptimizationBackend(Context context) { this.context = context; }
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
