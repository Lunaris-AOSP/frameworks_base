/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.os;

import android.content.Context;
import android.os.BatteryManager;
import android.os.CancellationSignal;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.storage.IStorageManager;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

final class OptimizationActions {
    private final Context context;
    OptimizationActions(Context context) { this.context = context; }
    OptimizationResult execute(String id, OptimizationPlan plan, CancellationSignal cancel,
            Consumer<String> progress, Consumer<android.os.Bundle> packageProgress) throws Exception {
        cancel.throwIfCanceled();
        PowerManager power = context.getSystemService(PowerManager.class);
        if (power != null && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
            throw new IllegalStateException("Device is too warm. Let it cool down before retrying.");
        }
        switch (id) {
            case "cache":
                return new OptimizationTools(context).cleanAppCaches(plan.cachePackages, cancel, progress);
            case "trim":
                IStorageManager storage = IStorageManager.Stub.asInterface(ServiceManager.getService("mount"));
                if (storage == null) throw new UnsupportedOperationException("Storage maintenance service unavailable");
                storage.runMaintenance();
                return OptimizationResult.success("Storage maintenance request complete.");
            case "zram":
                File compact = new File("/sys/block/zram0/compact");
                if (!compact.exists()) throw new UnsupportedOperationException("This kernel does not expose zRAM compaction");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(compact)) {
                    out.write("1".getBytes(StandardCharsets.US_ASCII));
                }
                return OptimizationResult.success("zRAM compaction request accepted by the kernel.");
            case "dexopt":
                if ("speed".equals(plan.filter)) {
                    enforceFullCompilationPower(context);
                }
                try {
                    return new ArtOptimizationBackend(context).compile(plan.filter, cancel, progress, packageProgress);
                } catch (LinkageError e) {
                    throw new UnsupportedOperationException("ART adapter does not match this ROM branch", e);
                }
            default: throw new IllegalArgumentException("Unknown action");
        }
    }

    static void enforceFullCompilationPower(Context context) {
        BatteryManager battery = context.getSystemService(BatteryManager.class);
        if (battery == null) {
            throw new IllegalStateException("Full compilation stopped: battery status unavailable");
        }
        if (battery.isCharging()) return;
        int percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (percent < 0 || percent > 100) {
            throw new IllegalStateException("Full compilation stopped: battery level unavailable. Connect a charger.");
        }
        if (percent <= 25) {
            throw new IllegalStateException("Full compilation stopped: battery is " + percent
                    + "%. Connect a charger or charge above 25%.");
        }
    }
}
