/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.os;

import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.IPackageDataObserver;
import android.os.CancellationSignal;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.storage.IStorageManager;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
                CountDownLatch done = new CountDownLatch(1);
                AtomicBoolean success = new AtomicBoolean();
                AppGlobals.getPackageManager().freeStorageAndNotify(null, plan.cacheTargetBytes, 0,
                    new IPackageDataObserver.Stub() {
                        @Override public void onRemoveCompleted(String name, boolean succeeded) {
                            success.set(succeeded); done.countDown();
                        }
                    });
                if (!done.await(120, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Cache request timed out; Android may still finish it in the background");
                }
                if (!success.get()) throw new IllegalStateException("Android could not meet the free-space target");
                return OptimizationResult.success("Free-space target processed. Cached files were removed only as needed; app data was preserved.");
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
                    android.os.BatteryManager battery = context.getSystemService(android.os.BatteryManager.class);
                    if (battery == null || !battery.isCharging()) throw new IllegalStateException("Connect a charger for full compilation");
                }
                try {
                    return new ArtOptimizationBackend(context).compile(plan.filter, cancel, progress, packageProgress);
                } catch (LinkageError e) {
                    throw new UnsupportedOperationException("ART adapter does not match this ROM branch", e);
                }
            default: throw new IllegalArgumentException("Unknown action");
        }
    }
}
