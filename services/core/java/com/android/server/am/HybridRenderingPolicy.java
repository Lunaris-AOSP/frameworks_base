/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.am;

import android.app.ActivityThread;
import android.app.ApplicationExitInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.pm.PackageManagerInternal;
import android.database.ContentObserver;
import android.os.SystemClock;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.app.ProcessMap;

import java.util.ArrayList;
import java.util.Objects;

final class HybridRenderingPolicy extends ContentObserver {
    private final ActivityManagerService mService;
    private final ContentResolver mResolver;
    private final String mSystemUiPackage;
    private volatile Options mOptions;

    private record Options(boolean systemUi, boolean launcher, ArraySet<String> apps,
            CrashGuard guard) {}

    HybridRenderingPolicy(ActivityManagerService service) {
        super(service.mHandler);
        mService = service;
        mResolver = service.mContext.getContentResolver();
        final ComponentName systemUi =
                service.getPackageManagerInternal().getSystemUiServiceComponent();
        mSystemUiPackage = systemUi == null ? null : systemUi.getPackageName();
        synchronized (this) {
            for (String key : new String[]{Global.HYBRID_RENDERING, Global.HYBRID_RENDERING_SYSTEMUI,
                    Global.HYBRID_RENDERING_LAUNCHER, Global.HYBRID_RENDERING_APPS}) {
                mResolver.registerContentObserver(Global.getUriFor(key), false, this);
            }
            mOptions = readOptions(null);
        }
    }

    private Options readOptions(Options previous) {
        if (Global.getInt(mResolver, Global.HYBRID_RENDERING, 0) == 0) return null;
        final String packages = Global.getString(mResolver, Global.HYBRID_RENDERING_APPS);
        return new Options(Global.getInt(mResolver, Global.HYBRID_RENDERING_SYSTEMUI, 0) != 0,
                Global.getInt(mResolver, Global.HYBRID_RENDERING_LAUNCHER, 0) != 0,
                new ArraySet<>(TextUtils.split(TextUtils.emptyIfNull(packages), ":")),
                previous == null ? new CrashGuard() : previous.guard);
    }

    @Override
    public synchronized void onChange(boolean selfChange) {
        final Options previous = mOptions;
        final Options current = readOptions(previous);
        if (Objects.equals(previous, current)) return;
        synchronized (mService) {
            mOptions = current;
            if (mService.mSafeMode) return;
            final ProcessList processes = mService.mProcessList;
            final var byName = processes.getProcessNamesLOSP().getMap();
            final var affected = new ArrayList<ProcessRecord>();
            final var homes = new SparseArray<String>();
            final PackageManagerInternal pm = mService.getPackageManagerInternal();
            for (int i = 0; i < byName.size(); i++) {
                final var byUid = byName.valueAt(i);
                for (int j = 0; j < byUid.size(); j++) {
                    final ProcessRecord app = byUid.valueAt(j);
                    if (app.getPid() == ActivityManagerService.MY_PID
                            || (app.getPid() <= 0 && !app.isPendingStart())) continue;
                    final String pkg = packageFor(app);
                    if (pkg == null || "android".equals(pkg)) continue;
                    if (requestsVulkan(previous, pkg, app.userId, pm, homes)
                            != requestsVulkan(current, pkg, app.userId, pm, homes)) {
                        affected.add(app);
                    }
                }
            }
            for (ProcessRecord app : affected) {
                processes.removeProcessLocked(app, false, true,
                        ApplicationExitInfo.REASON_USER_REQUESTED, "HWUI renderer selection changed");
            }
        }
    }

    private static String packageFor(ProcessRecord app) {
        return app.isSdkSandbox ? app.sdkSandboxClientAppPackage : app.info.packageName;
    }

    private boolean requestsVulkan(Options options, String pkg, int userId,
            PackageManagerInternal pm, SparseArray<String> homes) {
        if (options == null || pkg == null) return false;
        if (pkg.equals(mSystemUiPackage)) return options.systemUi;
        final boolean selected = options.apps.contains(pkg);
        if (selected == options.launcher) return selected;
        String home;
        if (homes != null && homes.indexOfKey(userId) >= 0) {
            home = homes.get(userId);
        } else {
            try {
                home = pm.getDefaultHomePackage(userId);
            } catch (RuntimeException e) {
                Slog.w("HybridRendering", "Cannot resolve home for user " + userId, e);
                home = null;
            }
            if (homes != null) homes.put(userId, home);
        }
        return pkg.equals(home) ? options.launcher : selected;
    }

    String rendererForStart(ProcessRecord app, PackageManagerInternal pm) {
        app.mHybridRenderingTrial = null;
        final Options options = mOptions;
        if (options == null) return null;
        if (!app.mService.mSafeMode && requestsVulkan(options, packageFor(app), app.userId, pm, null)
                && options.guard.allows(app)) {
            app.mHybridRenderingTrial = options.guard;
            return ActivityThread.HWUI_VULKAN_ARG;
        }
        return ActivityThread.HWUI_OPENGL_ARG;
    }

    static void noteStartupFailure(ProcessRecord app) {
        final CrashGuard guard = app.mHybridRenderingTrial;
        if (guard != null && SystemClock.uptimeMillis() - app.getStartUptime()
                < ActivityManagerConstants.MIN_CRASH_INTERVAL) {
            guard.block(app);
        }
    }

    static final class CrashGuard {
        private final ProcessMap<Boolean> mFailures = new ProcessMap<>();

        synchronized boolean allows(ProcessRecord app) {
            return mFailures.get(app.processName, app.info.uid) == null;
        }

        synchronized void block(ProcessRecord app) {
            if (allows(app)) {
                mFailures.put(app.processName, app.info.uid, true);
                Slog.w("HybridRendering", "Using OpenGL after startup failure of "
                        + app.processName + " (uid " + app.info.uid + ")");
            }
        }
    }
}
