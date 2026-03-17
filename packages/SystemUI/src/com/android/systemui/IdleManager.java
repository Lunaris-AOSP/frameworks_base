/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class IdleManager {

    private static final String TAG = "IdleManager";

    public static final long POLICY_BALANCED_MS = TimeUnit.MINUTES.toMillis(60);
    public static final long POLICY_AGGRESSIVE_MS = TimeUnit.MINUTES.toMillis(15);
    public static final long DEFAULT_TIMEOUT_MS = POLICY_BALANCED_MS;

    private static final long ALARM_BUFFER_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long MIN_DELAY_MS = 100L;

    private static final long SCAN_INTERVAL_HIGH_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long SCAN_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long SCAN_INTERVAL_CHARGING_MS = TimeUnit.MINUTES.toMillis(20);
    private static final long SCAN_INTERVAL_LOW_MS = TimeUnit.MINUTES.toMillis(3);

    private static final int BATTERY_LOW_THRESHOLD = 15;
    private static final int BATTERY_HIGH_THRESHOLD = 35;

    public enum Policy { BALANCED, AGGRESSIVE, CUSTOM }

    public static final class AppConfig {
        public final String packageName;
        public final Policy policy;
        public final long customTimeoutMs;

        public AppConfig(@NonNull String packageName, @NonNull Policy policy, long customTimeoutMs) {
            this.packageName = packageName;
            this.policy = policy;
            this.customTimeoutMs = customTimeoutMs;
        }

        public long resolvedTimeoutMs() {
            switch (policy) {
                case AGGRESSIVE: return POLICY_AGGRESSIVE_MS;
                case CUSTOM: return customTimeoutMs > 0 ? customTimeoutMs : DEFAULT_TIMEOUT_MS;
                default: return POLICY_BALANCED_MS;
            }
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("package", packageName);
            o.put("policy", policy.name());
            o.put("timeout_minutes", TimeUnit.MILLISECONDS.toMinutes(customTimeoutMs));
            return o;
        }

        public static AppConfig fromJson(@NonNull JSONObject o) throws Exception {
            String pkg = o.getString("package");
            Policy pol = Policy.valueOf(o.optString("policy", "BALANCED"));
            long mins = o.optLong("timeout_minutes", 60);
            return new AppConfig(pkg, pol, TimeUnit.MINUTES.toMillis(mins));
        }
    }

    private static volatile IdleManager sInstance;
    private static final Object sLock = new Object();

    private final Context mContext;
    private final Handler mMainHandler;
    private final ActivityManager mActivityManager;
    private final AlarmManager mAlarmManager;
    private final AudioManager mAudioManager;
    private final Executor mIoExecutor;

    private volatile boolean mEnabled = true;
    private volatile Map<String, AppConfig> mAppConfigCache = Collections.emptyMap();

    private final Map<String, Long> mLastKillTime = new HashMap<>();

    private volatile int mBatteryLevel = 100;
    private volatile boolean mIsCharging = false;

    private BroadcastReceiver mBatteryReceiver;
    private ContentObserver mSettingsObserver;
    private Runnable mScanRunnable;
    private Runnable mHaltRunnable;
    private boolean mIsRunning = false;

    private IdleManager(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mMainHandler = new Handler(Looper.getMainLooper());
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mIoExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "IdleManager-IO");
            t.setDaemon(true);
            return t;
        });

        loadConfigFromSettings();
        registerSettingsObserver();
        registerBatteryReceiver();
        initRunnables();
    }

    public static void initManager(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (sLock) {
                if (sInstance == null) {
                    sInstance = new IdleManager(context);
                }
            }
        }
    }

    @Nullable
    public static IdleManager getInstance() {
        return sInstance;
    }

    public void executeManager() {
        if (!mEnabled) {
            Log.d(TAG, "IdleManager disabled — skipping");
            return;
        }
        if (mIsRunning) {
            Log.d(TAG, "Already running — ignoring duplicate start");
            return;
        }
        mIsRunning = true;
        cancelCallbacks();

        long timeUntilAlarm = getMillisUntilNextAlarm();
        long firstDelay;

        if (timeUntilAlarm > 0 && timeUntilAlarm < getMinConfiguredTimeout()) {
            firstDelay = MIN_DELAY_MS;
            Log.d(TAG, "Alarm soon — scheduling immediate scan");
        } else {
            firstDelay = getMinConfiguredTimeout();
            Log.d(TAG, "First scan in " + TimeUnit.MILLISECONDS.toMinutes(firstDelay) + " min");
        }

        mMainHandler.postDelayed(mScanRunnable, firstDelay);

        if (timeUntilAlarm > ALARM_BUFFER_MS) {
            long haltDelay = timeUntilAlarm - ALARM_BUFFER_MS;
            mMainHandler.postDelayed(mHaltRunnable, haltDelay);
        }
    }

    public void haltManager() {
        Log.d(TAG, "Halting IdleManager");
        cancelCallbacks();
        mIsRunning = false;
    }

    public void cleanup() {
        haltManager();
        unregisterSettingsObserver();
        unregisterBatteryReceiver();
        synchronized (sLock) { sInstance = null; }
    }

    public boolean isEnabled() {
        return mEnabled; 
    }

    public boolean isRunning() {
        return mIsRunning;
    }

    public Map<String, AppConfig> getAppConfigs() {
        return Collections.unmodifiableMap(mAppConfigCache);
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.IDLE_MANAGER, enabled ? 1 : 0);
        if (!enabled) haltManager();
    }

    public void saveAppConfigs(@NonNull Map<String, AppConfig> configs) {
        mAppConfigCache = new HashMap<>(configs);
        persistAppConfigs(configs);
    }

    public void addOrUpdateApp(@NonNull AppConfig config) {
        Map<String, AppConfig> updated = new HashMap<>(mAppConfigCache);
        updated.put(config.packageName, config);
        saveAppConfigs(updated);
    }

    public void removeApp(@NonNull String packageName) {
        Map<String, AppConfig> updated = new HashMap<>(mAppConfigCache);
        updated.remove(packageName);
        saveAppConfigs(updated);
    }

    private void initRunnables() {
        mScanRunnable = () -> {
            mIoExecutor.execute(this::performIdleScan);
            if (mIsRunning) {
                long interval = getDynamicScanIntervalMs();
                Log.d(TAG, "Next scan in " + TimeUnit.MILLISECONDS.toMinutes(interval)
                        + " min [battery=" + mBatteryLevel + "%, charging=" + mIsCharging + "]");
                mMainHandler.postDelayed(mScanRunnable, interval);
            }
        };
        mHaltRunnable = this::haltManager;
    }

    private long getDynamicScanIntervalMs() {
        if (mIsCharging) return SCAN_INTERVAL_CHARGING_MS;
        if (mBatteryLevel <= BATTERY_LOW_THRESHOLD) return SCAN_INTERVAL_LOW_MS;
        if (mBatteryLevel > BATTERY_HIGH_THRESHOLD) return SCAN_INTERVAL_HIGH_MS;
        return SCAN_INTERVAL_MS;
    }

    private void performIdleScan() {
        if (mActivityManager == null) return;

        List<ActivityManager.RunningAppProcessInfo> processes;
        try {
            processes = mActivityManager.getRunningAppProcesses();
        } catch (Exception e) {
            Log.e(TAG, "Error fetching running processes", e);
            return;
        }
        if (processes == null || processes.isEmpty()) return;

        Set<String> foregroundPkgs = getForegroundPackages(processes);
        boolean audioActive = isAudioActive();
        long now = System.currentTimeMillis();
        int killed = 0;

        for (Map.Entry<String, AppConfig> entry : mAppConfigCache.entrySet()) {
            String pkg = entry.getKey();
            AppConfig config = entry.getValue();

            if (foregroundPkgs.contains(pkg)) continue;

            if (audioActive && isActiveMediaApp(pkg, processes)) continue;

            long timeoutMs = config.resolvedTimeoutMs();
            Long lastKill = mLastKillTime.get(pkg);
            if (lastKill != null && (now - lastKill) < timeoutMs) continue;

            try {
                mActivityManager.killBackgroundProcesses(pkg);
                mLastKillTime.put(pkg, now);
                updateKillStats(pkg, now);
                killed++;
                Log.d(TAG, "Killed: " + pkg + " [" + config.policy + "]");
            } catch (SecurityException e) {
                Log.w(TAG, "No permission to kill: " + pkg);
            } catch (Exception e) {
                Log.e(TAG, "Failed to kill: " + pkg, e);
            }
        }

        Log.i(TAG, "Scan done — killed " + killed + "/" + mAppConfigCache.size());
    }

    private Set<String> getForegroundPackages(
            @NonNull List<ActivityManager.RunningAppProcessInfo> processes) {
        Set<String> fg = new HashSet<>();
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
                    && p.pkgList != null) {
                for (String pkg : p.pkgList) fg.add(pkg);
            }
        }
        return fg;
    }

    private boolean isAudioActive() {
        if (mAudioManager == null) return false;
        try { 
            return mAudioManager.isMusicActive(); 
        } catch (Exception e) { 
            return false; 
        }
    }

    private boolean isActiveMediaApp(@NonNull String pkg,
            @NonNull List<ActivityManager.RunningAppProcessInfo> processes) {
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (p.pkgList == null) continue;
            for (String name : p.pkgList) {
                if (name.equals(pkg)) {
                    return p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
                }
            }
        }
        return false;
    }

    private void loadConfigFromSettings() {
        ContentResolver cr = mContext.getContentResolver();
        mEnabled = Settings.Secure.getInt(cr,
            Settings.Secure.IDLE_MANAGER, 1) == 1;
        mAppConfigCache = parseAppConfigs(Settings.Secure.getString(cr,
            Settings.Secure.IDLE_MANAGER_APPS));
        Log.d(TAG, "Config loaded — enabled=" + mEnabled + ", apps=" + mAppConfigCache.size());
    }

    private void registerSettingsObserver() {
        ContentResolver cr = mContext.getContentResolver();
        mSettingsObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange, @Nullable Uri uri) {
                Log.d(TAG, "Settings changed — refreshing cache");
                loadConfigFromSettings();
            }
        };
        for (String key : new String[]{
                Settings.Secure.IDLE_MANAGER,
                Settings.Secure.IDLE_MANAGER_APPS,
                Settings.Secure.IDLE_MANAGER_TIMEOUT }) {
            cr.registerContentObserver(
                Settings.Secure.getUriFor(key), 
                false, 
                mSettingsObserver);
        }
    }

    private void unregisterSettingsObserver() {
        if (mSettingsObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
        }
    }

    private void registerBatteryReceiver() {
        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);

                if (level >= 0 && scale > 0) {
                    mBatteryLevel = (int) ((level / (float) scale) * 100);
                }
                mIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;

                Log.d(TAG, "Battery update — level=" + mBatteryLevel
                        + "%, charging=" + mIsCharging);
            }
        };

        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = mContext.registerReceiver(mBatteryReceiver, filter);
        if (sticky != null) mBatteryReceiver.onReceive(mContext, sticky);
    }

    private void unregisterBatteryReceiver() {
        if (mBatteryReceiver != null) {
            try {
                mContext.unregisterReceiver(mBatteryReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            mBatteryReceiver = null;
        }
    }

    private void persistAppConfigs(@NonNull Map<String, AppConfig> configs) {
        mIoExecutor.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (AppConfig c : configs.values()) arr.put(c.toJson());
                Settings.Secure.putString(
                        mContext.getContentResolver(),
                        Settings.Secure.IDLE_MANAGER_APPS, arr.toString());
            } catch (Exception e) {
                Log.e(TAG, "Failed to persist app configs", e);
            }
        });
    }

    @NonNull
    private Map<String, AppConfig> parseAppConfigs(@Nullable String json) {
        if (json == null || json.isEmpty()) return Collections.emptyMap();
        Map<String, AppConfig> map = new HashMap<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                AppConfig c = AppConfig.fromJson(arr.getJSONObject(i));
                map.put(c.packageName, c);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse app configs", e);
        }
        return map;
    }

    private long getMinConfiguredTimeout() {
        if (mAppConfigCache.isEmpty()) return DEFAULT_TIMEOUT_MS;
        long min = Long.MAX_VALUE;
        for (AppConfig c : mAppConfigCache.values()) min = Math.min(min, c.resolvedTimeoutMs());
        return min == Long.MAX_VALUE ? DEFAULT_TIMEOUT_MS : min;
    }

    private long getMillisUntilNextAlarm() {
        if (mAlarmManager == null) return 0;
        try {
            AlarmManager.AlarmClockInfo info = mAlarmManager.getNextAlarmClock();
            if (info != null) return Math.max(0, info.getTriggerTime() - System.currentTimeMillis());
        } catch (Exception e) {
            Log.e(TAG, "Error reading next alarm", e);
        }
        return 0;
    }

    private void updateKillStats(@NonNull String pkg, long killTimeMs) {
        mIoExecutor.execute(() -> {
            ContentResolver cr = mContext.getContentResolver();
            try {
                String existing = Settings.Secure.getString(cr,
                    Settings.Secure.IDLE_MANAGER_KILL_STATS);
                JSONObject root = (existing != null && !existing.isEmpty())
                        ? new JSONObject(existing)
                        : new JSONObject();

                JSONObject entry = root.optJSONObject(pkg);
                if (entry == null) entry = new JSONObject();

                int count = entry.optInt("count", 0) + 1;
                entry.put("count", count);
                entry.put("last_kill", killTimeMs);
                root.put(pkg, entry);

                Settings.Secure.putString(cr,
                    Settings.Secure.IDLE_MANAGER_KILL_STATS, root.toString());
            } catch (Exception e) {
                Log.e(TAG, "Failed to update kill stats for " + pkg, e);
            }
        });
    }

    private void cancelCallbacks() {
        if (mScanRunnable != null) 
            mMainHandler.removeCallbacks(mScanRunnable);
        if (mHaltRunnable != null) 
            mMainHandler.removeCallbacks(mHaltRunnable);
    }
}
