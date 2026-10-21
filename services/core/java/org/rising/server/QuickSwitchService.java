/*
 * Copyright (C) 2023 The RisingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.rising.server;

import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.Process.THREAD_PRIORITY_DEFAULT;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.os.IUserManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;

import com.android.server.ServiceThread;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class QuickSwitchService extends SystemService {

    private static final String TAG = "QuickSwitchService";
    private static final String WALLPAPER_OVERLAY = "com.android.system.qs.wallpaperoverlay";

    private final Context mContext;
    private final IPackageManager mPM;
    private final IUserManager mUM;
    private final String mOpPackageName;

    private ServiceThread mWorker;
    private Handler mHandler;

    private static volatile List<String> sLauncherPackages = Collections.emptyList();

    public static boolean shouldHide(int callingUid, int userId, String packageName) {
        if (UserHandle.getAppId(callingUid) < FIRST_APPLICATION_UID || packageName == null) {
            return false;
        }
        List<String> launcherPackages = sLauncherPackages;
        if (!launcherPackages.contains(packageName)) {
            return false;
        }
        int defaultLauncher = SystemProperties.getInt("persist.sys.default_launcher", 0);
        return defaultLauncher >= 0 && defaultLauncher < launcherPackages.size()
                && !isLauncherEnabled(packageName, launcherPackages, defaultLauncher);
    }

    public static ParceledListSlice<PackageInfo> recreatePackageList(
            int callingUid, Context context, int userId, ParceledListSlice<PackageInfo> list) {
        if (UserHandle.getAppId(callingUid) < FIRST_APPLICATION_UID) {
            return list;
        }
        List<PackageInfo> appList = new ArrayList<>(list.getList());
        List<String> disabledLaunchers = getDisabledDefaultLaunchers();
        appList.removeIf(info -> disabledLaunchers.contains(info.packageName));
        return new ParceledListSlice<>(appList);
    }

    public static List<ApplicationInfo> recreateApplicationList(
            int callingUid, Context context, int userId, List<ApplicationInfo> list) {
        if (UserHandle.getAppId(callingUid) < FIRST_APPLICATION_UID) {
            return list;
        }
        List<ApplicationInfo> appList = new ArrayList<>(list);
        List<String> disabledLaunchers = getDisabledDefaultLaunchers();
        appList.removeIf(info -> disabledLaunchers.contains(info.packageName));
        return appList;
    }

    private void updateStateForUser(int userId) {
        int defaultLauncher = SystemProperties.getInt("persist.sys.default_launcher", 0);
        List<String> launcherPackages = sLauncherPackages;
        if (defaultLauncher < 0 || defaultLauncher >= launcherPackages.size()) {
            Log.w(TAG, "Invalid default launcher index: " + defaultLauncher);
            return;
        }
        for (String packageName : launcherPackages) {
            int enabledState = isLauncherEnabled(packageName, launcherPackages, defaultLauncher)
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            try {
                mPM.setApplicationEnabledSetting(packageName, enabledState,
                        0, userId, mOpPackageName);
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "Skipping unavailable launcher " + packageName + " for user " + userId);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to update launcher " + packageName + " for user " + userId, e);
            }
        }
    }

    private static boolean isLauncherEnabled(String packageName, List<String> launcherPackages,
            int defaultLauncher) {
        return packageName.equals(launcherPackages.get(defaultLauncher))
                || (defaultLauncher == 2 && packageName.equals("com.android.launcher3"));
    }

    public static List<String> getDisabledDefaultLaunchers() {
        int defaultLauncher = SystemProperties.getInt("persist.sys.default_launcher", 0);
        List<String> launcherPackages = sLauncherPackages;
        List<String> disabledDefaultLaunchers = new ArrayList<>();
        if (defaultLauncher < 0 || defaultLauncher >= launcherPackages.size()) {
            return disabledDefaultLaunchers;
        }
        for (String packageName : launcherPackages) {
            if (!isLauncherEnabled(packageName, launcherPackages, defaultLauncher)) {
                disabledDefaultLaunchers.add(packageName);
            }
        }
        return disabledDefaultLaunchers;
    }

    private void applyOverlayForLauncher(int launcherValue) {
        OverlayManager overlayManager = mContext.getSystemService(OverlayManager.class);
        if (overlayManager == null) {
            Log.e(TAG, "OverlayManager not available");
            return;
        }
        boolean enableOverlay = (launcherValue == 0);
        try {
            OverlayInfo info = overlayManager.getOverlayInfo(WALLPAPER_OVERLAY, UserHandle.CURRENT);
            if (info == null) {
                Log.e(TAG, "Overlay not found: " + WALLPAPER_OVERLAY);
                return;
            }
            OverlayIdentifier overlayId = info.getOverlayIdentifier();
            OverlayManagerTransaction.Builder transaction = new OverlayManagerTransaction.Builder();
            transaction.setEnabled(overlayId, enableOverlay, UserHandle.USER_CURRENT);
            overlayManager.commit(transaction.build());
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply wallpaper overlay", e);
        }
    }

    private void initForUser(int userId) {
        if (userId < 0)
            return;
        updateStateForUser(userId);
    }

    private void init() {
        try {
            for (UserInfo user : mUM.getUsers(false)) {
                initForUser(user.id);
            }
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(new UserReceiver(), filter,
                android.Manifest.permission.MANAGE_USERS, mHandler);
    }

    @Override
    public void onStart() {
        mWorker = new ServiceThread(TAG, THREAD_PRIORITY_DEFAULT, false);
        mWorker.start();
        mHandler = new Handler(mWorker.getLooper());
        init();
    }

    @Override
    public void onBootPhase(int phase) {
        super.onBootPhase(phase);
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            mHandler.post(() -> {
                int defaultLauncher = SystemProperties.getInt("persist.sys.default_launcher", 0);
                applyOverlayForLauncher(defaultLauncher);
            });
        }
    }

    public QuickSwitchService(Context context) {
        super(context);
        mContext = context;
        sLauncherPackages = Collections.unmodifiableList(Arrays.asList(context.getResources()
                .getStringArray(com.android.internal.R.array.config_launcherPackages)));
        mPM = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        mUM = IUserManager.Stub.asInterface(ServiceManager.getService(Context.USER_SERVICE));
        mOpPackageName = context.getOpPackageName();
    }

    private final class UserReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            if (Intent.ACTION_USER_ADDED.equals(intent.getAction())) {
                initForUser(userId);
            }
        }
    }
}
