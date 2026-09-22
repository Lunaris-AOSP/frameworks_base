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

package com.android.systemui.lunaris;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LunarisIdleManager {

    private static final String TAG = "LunarisIdleManager";

    private static final String ACTION_SCAN = "com.android.systemui.lunaris.ACTION_IDLE_SCAN";
    private static final String ACTION_HALT = "com.android.systemui.lunaris.ACTION_IDLE_HALT";

    private static final int PI_SCAN_REQUEST = 0x4C494D01;
    private static final int PI_HALT_REQUEST = 0x4C494D02;

    public static final long IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(15);

    private static final long MIN_KILL_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long ALARM_BUFFER_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long MIN_DELAY_MS = 100L;

    private static final long SCAN_INTERVAL_CHARGING_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long SCAN_INTERVAL_HIGH_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long SCAN_INTERVAL_MS = TimeUnit.MINUTES.toMillis(20);
    private static final long SCAN_INTERVAL_LOW_MS = TimeUnit.MINUTES.toMillis(30);

    private static final long INITIAL_SCAN_DELAY_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long SCAN_DEBOUNCE_MS = TimeUnit.SECONDS.toMillis(10);

    private static final int BATTERY_LOW_THRESHOLD = 15;
    private static final int BATTERY_HIGH_THRESHOLD = 35;

    public static final int STANDBY_BUCKET_ACTIVE = 10;
    public static final int STANDBY_BUCKET_WORKING_SET = 20;
    public static final int STANDBY_BUCKET_FREQUENT = 30;
    public static final int STANDBY_BUCKET_RARE = 40;
    public static final int STANDBY_BUCKET_RESTRICTED = 45;

    private volatile long mSession;
    private long mLastScanStartMs = -SCAN_DEBOUNCE_MS;
    private boolean mScanQueued;

    private volatile boolean mSleepModeTriggerEnabled = false;
    private volatile boolean mIsSleepModeActive = false;

    public enum IdleAction {
        STANDBY_BUCKET_RARE,
        STANDBY_BUCKET_RESTRICTED,
        KILL_BACKGROUND,
        FULL_KILL;

        public String toDisplayName() {
            switch (this) {
                case STANDBY_BUCKET_RARE:
                    return "Rare Bucket";
                case STANDBY_BUCKET_RESTRICTED:
                    return "Restricted";
                case KILL_BACKGROUND:
                    return "Kill BG";
                case FULL_KILL:
                    return "Full Kill";
                default:
                    return name();
            }
        }
    }

    public static final class AppConfig {
        public final String packageName;
        public final IdleAction action;

        public AppConfig(@NonNull String packageName, @NonNull IdleAction action) {
            this.packageName = packageName;
            this.action = action;
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("package", packageName);
            o.put("action", action.name());
            return o;
        }

        public static AppConfig fromJson(@NonNull JSONObject o) throws Exception {
            String pkg = o.getString("package");
            IdleAction act = parseAction(o.optString("action",
                    IdleAction.STANDBY_BUCKET_RARE.name()));
            return new AppConfig(pkg, act);
        }

        private static IdleAction parseAction(String s) {
            try   { return IdleAction.valueOf(s); }
            catch (Exception e) { return IdleAction.STANDBY_BUCKET_RARE; }
        }
    }

    public static final class AppEnforcementRecord {
        public final String packageName;
        public final IdleAction actionTaken;
        public final long timestampMs;
        public final int killCount;

        public AppEnforcementRecord(String pkg, IdleAction action, long ts, int count) {
            this.packageName = pkg;
            this.actionTaken = action;
            this.timestampMs = ts;
            this.killCount = count;
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("package", packageName);
            o.put("action", actionTaken.name());
            o.put("ts", timestampMs);
            o.put("kill_count", killCount);
            return o;
        }

        public static AppEnforcementRecord fromJson(JSONObject o) throws Exception {
            return new AppEnforcementRecord(
                    o.getString("package"),
                    IdleAction.valueOf(o.optString("action",
                            IdleAction.STANDBY_BUCKET_RARE.name())),
                    o.optLong("ts", 0L),
                    o.optInt("kill_count", 0)
            );
        }
    }

    private static final class AppIdleState {
        final int originalBucket;
        final int appliedBucket;
        boolean restoreRequested;

        AppIdleState(int originalBucket, int appliedBucket, boolean restoreRequested) {
            this.originalBucket = originalBucket;
            this.appliedBucket = appliedBucket;
            this.restoreRequested = restoreRequested;
        }
    }

    private static volatile LunarisIdleManager sInstance;
    private static final Object sLock = new Object();

    private final Context mContext;
    private final Handler mMainHandler;
    private final ActivityManager mActivityManager;
    private final AlarmManager mAlarmManager;
    private final AudioManager mAudioManager;
    private final UsageStatsManager mUsageStatsManager;
    private final PowerManager mPowerManager;
    private final TelephonyManager mTelephonyManager;
    private final ExecutorService mIoExecutor;

    private volatile boolean mEnabled = true;
    private volatile boolean mDestroyed = false;
    private volatile Map<String, AppConfig> mAppConfigCache = Collections.emptyMap();

    private final Map<String, AppIdleState> mAppIdleStates = new HashMap<>();
    private final Map<String, Long> mLastKillTime = new HashMap<>();
    private boolean mBucketStateLoaded;

    private volatile int mBatteryLevel = 100;
    private volatile boolean mIsCharging = false;

    private volatile boolean mHasScanCompleted = false;
    private int mHaltRetries = 0;

    private BroadcastReceiver mBatteryReceiver;
    private BroadcastReceiver mAlarmReceiver;
    private BroadcastReceiver mDozeReceiver;
    private ContentObserver mSettingsObserver;
    private volatile boolean mIsRunning = false;

    private PowerManager.WakeLock mScanWakeLock;

    private LunarisIdleManager(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mMainHandler = new Handler(Looper.getMainLooper());
        mIoExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "LunarisIdleManager-IO");
            t.setDaemon(true);
            return t;
        });
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mUsageStatsManager = (UsageStatsManager) mContext.getSystemService(Context.USAGE_STATS_SERVICE);
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);

        loadConfigFromSettings();
        initScanWakeLock();
        mIoExecutor.execute(() -> {
            loadBucketStates();
            boolean pending = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.IDLE_MANAGER_RESTORE_PENDING, 0) == 1;
            boolean hasPendingRecords = mAppIdleStates.values().stream()
                    .anyMatch(state -> state.restoreRequested);
            restoreBuckets(!isPolicyEnabled() || (pending && !hasPendingRecords), mAppConfigCache);
        });
        registerSettingsObserver();
        registerBatteryReceiver();
        registerAlarmReceiver();
        registerDozeReceiver();
    }

    public static void initManager(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (sLock) {
                if (sInstance == null) {
                    sInstance = new LunarisIdleManager(context);
                }
            }
        }
    }

    @Nullable
    public static LunarisIdleManager getInstance() { return sInstance; }

    public synchronized void executeManager() {
        if (mDestroyed) {
            Log.w(TAG, "executeManager called on destroyed instance");
            return;
        }
        if (!mEnabled) {
            Log.d(TAG, "LunarisIdleManager disabled — skipping");
            return;
        }
        if (mSleepModeTriggerEnabled && !mIsSleepModeActive) {
            Log.d(TAG, "Sleep-Mode trigger enabled but Sleep Mode is off — skipping");
            return;
        }
        if (mAppConfigCache.isEmpty() || mAlarmManager == null) return;
        if (mIsRunning) {
            Log.d(TAG, "Already running — ignoring duplicate start");
            return;
        }
        mSession++;
        mLastScanStartMs = -SCAN_DEBOUNCE_MS;
        mIsRunning = true;
        mHasScanCompleted = false;
        mHaltRetries = 0;
        cancelCallbacks();

        Log.d(TAG, "executeManager: appCount=" + mAppConfigCache.size()
                + " enabled=" + mEnabled);

        long timeUntilAlarm = getMillisUntilNextAlarm();
        long firstDelay;

        if (timeUntilAlarm > 0 && timeUntilAlarm < IDLE_TIMEOUT_MS) {
            firstDelay = MIN_DELAY_MS;
            Log.d(TAG, "Alarm soon — scheduling immediate scan");
        } else {
            firstDelay = INITIAL_SCAN_DELAY_MS;
            Log.d(TAG, "First scan in "
                    + TimeUnit.MILLISECONDS.toSeconds(firstDelay) + " sec");
        }

        scheduleScanAlarm(firstDelay);

        if (timeUntilAlarm > ALARM_BUFFER_MS) {
            scheduleHaltAlarm(timeUntilAlarm - ALARM_BUFFER_MS);
        }
    }

    public synchronized void haltManager() {
        mIsRunning = false;
        mSession++;
        cancelCallbacks();
        if (!mDestroyed) {
            Map<String, AppConfig> configs = mAppConfigCache;
            mIoExecutor.execute(() -> restoreBuckets(false, configs));
        }
    }

    public synchronized void cleanup() {
        if (mDestroyed) return;
        haltManager();
        mDestroyed = true;
        unregisterSettingsObserver();
        unregisterBatteryReceiver();
        unregisterDozeReceiver();
        unregisterAlarmReceiver();
        restoreAllBuckets();
        mIoExecutor.execute(() -> {
            synchronized (sLock) {
                if (sInstance == this) sInstance = null;
            }
        });
        mIoExecutor.shutdown();
        releaseWakeLockIfHeld();
        Log.d(TAG, "LunarisIdleManager cleaned up");
    }

    public boolean isEnabled() {
        if (mDestroyed) {
            return false;
        }
        return mEnabled;
    }

    public boolean isRunning() {
        if (mDestroyed) {
            return false;
        }
        return mIsRunning;
    }

    public Map<String, AppConfig> getAppConfigs() {
        if (mDestroyed) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(mAppConfigCache);
    }

    public synchronized void setEnabled(boolean enabled) {
        if (mDestroyed) return;
        if (Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.IDLE_MANAGER, enabled ? 1 : 0)) {
            onSettingsChanged();
        }
    }

    public synchronized void saveAppConfigs(@NonNull Map<String, AppConfig> configs) {
        if (mDestroyed) return;
        if (persistAppConfigs(new HashMap<>(configs))) {
            onSettingsChanged();
        }
    }

    public synchronized void addOrUpdateApp(@NonNull AppConfig config) {
        Map<String, AppConfig> updated = new HashMap<>(mAppConfigCache);
        updated.put(config.packageName, config);
        saveAppConfigs(updated);
    }

    public synchronized void removeApp(@NonNull String packageName) {
        Map<String, AppConfig> updated = new HashMap<>(mAppConfigCache);
        updated.remove(packageName);
        saveAppConfigs(updated);
    }

    @NonNull
    public List<AppEnforcementRecord> getEnforcementRecords() {
        if (mDestroyed) return Collections.emptyList();
        List<AppEnforcementRecord> records = new ArrayList<>();
        String json = Settings.Secure.getString(
                mContext.getContentResolver(), Settings.Secure.IDLE_MANAGER_KILL_STATS);
        if (json == null || json.isEmpty()) return records;
        try {
            JSONObject root = new JSONObject(json);
            for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
                String pkg = it.next();
                JSONObject entry = root.optJSONObject(pkg);
                if (entry == null) continue;
                records.add(new AppEnforcementRecord(
                        pkg,
                        parseAction(entry.optString("last_action",
                                IdleAction.STANDBY_BUCKET_RARE.name())),
                        entry.optLong("last_kill", 0L),
                        entry.optInt("count", 0)
                ));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse enforcement records", e);
        }
        records.sort((a, b) -> Long.compare(b.timestampMs, a.timestampMs));
        return records;
    }

    private static IdleAction parseAction(String s) {
        try { return IdleAction.valueOf(s); }
        catch (Exception e) { return IdleAction.STANDBY_BUCKET_RARE; }
    }

    public boolean isSleepModeTriggerEnabled() {
        if (mDestroyed) return false;
        return mSleepModeTriggerEnabled;
    }

    public synchronized void setSleepModeTriggerEnabled(boolean enabled) {
        if (mDestroyed) return;
        if (Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.IDLE_MANAGER_SLEEP_MODE_TRIGGER, enabled ? 1 : 0)) {
            onSettingsChanged();
        }
    }

    private boolean isPolicyEnabled() {
        return mEnabled && (!mSleepModeTriggerEnabled || mIsSleepModeActive);
    }

    private boolean canScan(long session) {
        return !mDestroyed && mIsRunning && mSession == session && isPolicyEnabled()
                && !mAppConfigCache.isEmpty() && mPowerManager != null
                && !mPowerManager.isInteractive();
    }

    private void initScanWakeLock() {
        if (mPowerManager != null) {
            mScanWakeLock = mPowerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, TAG + ":ScanLock");
            mScanWakeLock.setReferenceCounted(false);
        }
    }

    private void acquireWakeLock() {
        if (mScanWakeLock != null && !mScanWakeLock.isHeld()) {
            mScanWakeLock.acquire(TimeUnit.MINUTES.toMillis(3));
        }
    }

    private synchronized void releaseWakeLockIfHeld() {
        if (mScanWakeLock != null && mScanWakeLock.isHeld()) {
            mScanWakeLock.release();
        }
    }

    private void scheduleScanAlarm(long delayMs) {
        if (mAlarmManager == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(
                mContext, PI_SCAN_REQUEST,
                new Intent(ACTION_SCAN).setPackage(mContext.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = SystemClock.elapsedRealtime() + Math.max(delayMs, MIN_DELAY_MS);
        mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        Log.d(TAG, "Scan alarm set in " + TimeUnit.MILLISECONDS.toMinutes(delayMs) + " min");
    }

    private void cancelScanAlarm() {
        if (mAlarmManager == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(
                mContext, PI_SCAN_REQUEST,
                new Intent(ACTION_SCAN).setPackage(mContext.getPackageName()),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) mAlarmManager.cancel(pi);
    }

    private void scheduleHaltAlarm(long delayMs) {
        if (mAlarmManager == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(
                mContext, PI_HALT_REQUEST,
                new Intent(ACTION_HALT).setPackage(mContext.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = SystemClock.elapsedRealtime() + Math.max(delayMs, MIN_DELAY_MS);
        mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
    }

    private void cancelHaltAlarm() {
        if (mAlarmManager == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(
                mContext, PI_HALT_REQUEST,
                new Intent(ACTION_HALT).setPackage(mContext.getPackageName()),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) mAlarmManager.cancel(pi);
    }

    private void registerAlarmReceiver() {
        mAlarmReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (ACTION_SCAN.equals(action)) {
                    onScanAlarmFired();
                } else if (ACTION_HALT.equals(action)) {
                    onHaltAlarmFired();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SCAN);
        filter.addAction(ACTION_HALT);
        mContext.registerReceiver(mAlarmReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterAlarmReceiver() {
        if (mAlarmReceiver != null) {
            try { mContext.unregisterReceiver(mAlarmReceiver); }
            catch (IllegalArgumentException ignored) {}
            mAlarmReceiver = null;
        }
    }

    private synchronized void onScanAlarmFired() {
        if (!mIsRunning || mDestroyed || !isPolicyEnabled()) return;
        long now = SystemClock.elapsedRealtime();
        long remaining = SCAN_DEBOUNCE_MS - (now - mLastScanStartMs);
        if (mScanQueued || remaining > 0) {
            scheduleScanAlarm(Math.max(remaining, SCAN_DEBOUNCE_MS));
            return;
        }
        mLastScanStartMs = now;
        mScanQueued = true;
        final long session = mSession;
        acquireWakeLock();
        mIoExecutor.execute(() -> {
            try {
                if (canScan(session)) performIdleScan(session);
            } catch (Exception e) {
                Log.e(TAG, "Idle scan failed", e);
            } finally {
                synchronized (LunarisIdleManager.this) {
                    releaseWakeLockIfHeld();
                    mScanQueued = false;
                    if (mSession == session && mIsRunning && !mDestroyed
                            && isPolicyEnabled()) {
                        mHasScanCompleted = true;
                        scheduleScanAlarm(getDynamicScanIntervalMs());
                    }
                }
            }
        });
    }

    private synchronized void onHaltAlarmFired() {
        if (!mIsRunning) return;
        if (!mHasScanCompleted && mHaltRetries < 3) {
            mHaltRetries++;
            Log.d(TAG, "Halt deferred (attempt " + mHaltRetries + ") — scan not yet run");
            scheduleHaltAlarm(ALARM_BUFFER_MS * 2);
            return;
        }
        haltManager();
    }

    private void performIdleScan(long session) {
        if (!canScan(session) || mActivityManager == null || mUsageStatsManager == null) return;
        Map<String, AppConfig> configs = mAppConfigCache;
        restoreBuckets(false, configs);
        if (!mBucketStateLoaded || !canScan(session)) return;

        Log.d(TAG, "performIdleScan: trigger=[screenOff]"
                + " evaluating " + mAppConfigCache.size() + " configured apps");

        List<ActivityManager.RunningAppProcessInfo> processes;
        try {
            processes = mActivityManager.getRunningAppProcesses();
        } catch (Exception e) {
            Log.e(TAG, "Error fetching processes", e);
            return;
        }
        if (processes == null) return;

        final long now = System.currentTimeMillis();
        final Map<String, UsageStats> stats;
        try {
            stats = mUsageStatsManager.queryAndAggregateUsageStats(now - IDLE_TIMEOUT_MS, now);
        } catch (Exception e) {
            Log.w(TAG, "Usage statistics unavailable; skipping scan", e);
            return;
        }
        if (stats == null || stats.isEmpty()) {
            Log.w(TAG, "Usage statistics empty; skipping scan");
            return;
        }

        Set<String> foregroundPkgs = getForegroundPackages(processes);
        boolean audioActive = isAudioActive();
        Map<String, IdleAction> actions = new HashMap<>();
        int restricted = 0;
        int killed = 0;

        for (Map.Entry<String, AppConfig> entry : configs.entrySet()) {
            if (!canScan(session)) break;
            String pkg = entry.getKey();
            AppConfig cfg = entry.getValue();

            if (LunarisIdleConstants.PROTECTED_PACKAGES.contains(pkg)) {
                Log.w(TAG, "Skipping protected package: " + pkg);
                continue;
            }

            if (foregroundPkgs.contains(pkg)) {
                Log.v(TAG, "Skipping foreground: " + pkg);
                continue;
            }

            if (audioActive && isActiveMediaApp(pkg, processes)) {
                Log.v(TAG, "Skipping active media: " + pkg);
                continue;
            }

            if (!isAppIdleLongEnough(pkg, now, stats)) {
                Log.v(TAG, "Not idle long enough: " + pkg);
                continue;
            }

            boolean didAct = false;

            switch (cfg.action) {
                case STANDBY_BUCKET_RARE:
                    didAct = applyStandbyBucket(pkg, STANDBY_BUCKET_RARE, session);
                    if (didAct) restricted++;
                    break;
                case STANDBY_BUCKET_RESTRICTED:
                    didAct = applyStandbyBucket(pkg, STANDBY_BUCKET_RESTRICTED, session);
                    if (didAct) restricted++;
                    break;
                case KILL_BACKGROUND:
                    didAct = killBackground(pkg, session);
                    if (didAct) killed++;
                    break;
                case FULL_KILL:
                    boolean r = applyStandbyBucket(pkg, STANDBY_BUCKET_RESTRICTED, session);
                    boolean k = forceStop(pkg, session);
                    didAct = r || k;
                    if (r) restricted++;
                    if (k) killed++;
                    break;
            }

            if (didAct) {
                actions.put(pkg, cfg.action);
            }
        }

        updateKillStats(actions, now);
        Log.i(TAG, "Scan done — restricted=" + restricted
                + " killed=" + killed
                + " total=" + mAppConfigCache.size());
    }

    private boolean applyStandbyBucket(String pkg, int targetBucket, long session) {
        if (!mBucketStateLoaded || !canScan(session)) return false;
        try {
            int currentBucket = readBucket(pkg);
            AppIdleState previous = mAppIdleStates.get(pkg);
            if (previous != null && previous.restoreRequested) return false;
            if (currentBucket < STANDBY_BUCKET_ACTIVE || currentBucket >= targetBucket) {
                return false;
            }
            int originalBucket = previous != null && currentBucket == previous.appliedBucket
                    ? previous.originalBucket : currentBucket;
            mAppIdleStates.put(pkg, new AppIdleState(originalBucket, targetBucket, false));
            if (!persistBucketStates()) {
                if (previous == null) mAppIdleStates.remove(pkg);
                else mAppIdleStates.put(pkg, previous);
                return false;
            }
            if (!canScan(session)) return false;
            mUsageStatsManager.setAppStandbyBucket(pkg, targetBucket);
            if (readBucket(pkg) != targetBucket) {
                Log.w(TAG, "Standby bucket change not applied for " + pkg);
                return false;
            }
            Log.d(TAG, "Bucket applied [" + bucketName(targetBucket) + "]: " + pkg);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to set standby bucket for " + pkg, e);
            return false;
        }
    }

    private int readBucket(String pkg) {
        Integer bucket = mUsageStatsManager.getAppStandbyBuckets().get(pkg);
        if (bucket == null) throw new IllegalStateException("No standby bucket for " + pkg);
        return bucket;
    }

    private boolean killBackground(String pkg, long session) {
        if (!canScan(session)) return false;
        long now = SystemClock.elapsedRealtime();
        Long lastKill = mLastKillTime.get(pkg);
        if (lastKill != null && (now - lastKill) < MIN_KILL_INTERVAL_MS) {
            return false;
        }
        try {
            mActivityManager.killBackgroundProcesses(pkg);
            mLastKillTime.put(pkg, now);
            Log.d(TAG, "Killed background: " + pkg);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to kill " + pkg + ": " + e.getMessage());
            return false;
        }
    }

    private boolean forceStop(String pkg, long session) {
        if (!canScan(session)) return false;
        long now = SystemClock.elapsedRealtime();
        Long lastKill = mLastKillTime.get(pkg);
        if (lastKill != null && (now - lastKill) < MIN_KILL_INTERVAL_MS) {
            return false;
        }

        boolean stopped = false;

        try {
            mActivityManager.forceStopPackage(pkg);
            stopped = true;
            Log.d(TAG, "Force stopped: " + pkg);
        } catch (Exception e) {
            Log.w(TAG, "forceStop unexpected error for " + pkg + ": " + e.getMessage());
        }

        try {
            if (!stopped && canScan(session)) {
                mActivityManager.killBackgroundProcesses(pkg);
                stopped = true;
                Log.d(TAG, "killBackgroundProcesses called: " + pkg);
            }
        } catch (Exception e) {
            Log.w(TAG, "killBackgroundProcesses failed for " + pkg + ": " + e.getMessage());
        }

        if (stopped) {
            mLastKillTime.put(pkg, now);
        }

        return stopped;
    }

    private void restoreBucket(String pkg) {
        AppIdleState state = mAppIdleStates.get(pkg);
        if (state == null || !state.restoreRequested) return;
        try {
            int currentBucket = readBucket(pkg);
            if (currentBucket == state.appliedBucket) {
                mUsageStatsManager.setAppStandbyBucket(pkg, state.originalBucket);
                if (readBucket(pkg) != state.originalBucket) {
                    Log.w(TAG, "Bucket restore not applied for " + pkg + "; keeping pending");
                    return;
                }
            }
            mAppIdleStates.remove(pkg);
            if (!persistBucketStates()) mAppIdleStates.put(pkg, state);
        } catch (Exception e) {
            Log.w(TAG, "Failed to restore bucket for " + pkg + "; keeping pending", e);
        }
    }

    private void restoreAllBuckets() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.IDLE_MANAGER_RESTORE_PENDING, 1);
        mIoExecutor.execute(() -> restoreBuckets(true, Collections.emptyMap()));
    }

    private static int configuredBucket(@Nullable AppConfig config) {
        if (config == null) return -1;
        switch (config.action) {
            case STANDBY_BUCKET_RARE: return STANDBY_BUCKET_RARE;
            case STANDBY_BUCKET_RESTRICTED:
            case FULL_KILL: return STANDBY_BUCKET_RESTRICTED;
            default: return -1;
        }
    }

    private void restoreBuckets(boolean all, Map<String, AppConfig> configs) {
        if (!mBucketStateLoaded) loadBucketStates();
        if (!mBucketStateLoaded) return;
        boolean requested = false;
        for (Map.Entry<String, AppIdleState> entry : mAppIdleStates.entrySet()) {
            AppIdleState state = entry.getValue();
            if (all || configuredBucket(configs.get(entry.getKey())) != state.appliedBucket) {
                state.restoreRequested = true;
            }
            requested |= state.restoreRequested;
        }
        if (requested) {
            if (!persistBucketStates()) return;
            for (String pkg : new HashSet<>(mAppIdleStates.keySet())) restoreBucket(pkg);
        }
        boolean pending = false;
        for (AppIdleState state : mAppIdleStates.values()) pending |= state.restoreRequested;
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.IDLE_MANAGER_RESTORE_PENDING, pending ? 1 : 0);
        mLastKillTime.keySet().retainAll(configs.keySet());
    }

    private void loadBucketStates() {
        try {
            String json = Settings.Secure.getString(mContext.getContentResolver(),
                    Settings.Secure.IDLE_MANAGER_BUCKET_STATE);
            Map<String, AppIdleState> restored = new HashMap<>();
            if (json != null && !json.isEmpty()) {
                JSONObject root = new JSONObject(json);
                for (java.util.Iterator<String> it = root.keys(); it.hasNext();) {
                    String pkg = it.next();
                    JSONObject entry = root.getJSONObject(pkg);
                    int original = entry.getInt("original_bucket");
                    int applied = entry.getInt("applied_bucket");
                    if (original < STANDBY_BUCKET_ACTIVE || original >= applied
                            || (applied != STANDBY_BUCKET_RARE
                            && applied != STANDBY_BUCKET_RESTRICTED)) {
                        throw new IllegalArgumentException("Invalid bucket record for " + pkg);
                    }
                    restored.put(pkg, new AppIdleState(original, applied,
                            entry.optBoolean("restore_requested", false)));
                }
            }
            mAppIdleStates.putAll(restored);
            mBucketStateLoaded = true;
        } catch (Exception e) {
            Log.e(TAG, "Cannot load bucket recovery state; enforcement disabled", e);
        }
    }

    private boolean persistBucketStates() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, AppIdleState> entry : mAppIdleStates.entrySet()) {
                AppIdleState state = entry.getValue();
                JSONObject value = new JSONObject();
                value.put("original_bucket", state.originalBucket);
                value.put("applied_bucket", state.appliedBucket);
                value.put("restore_requested", state.restoreRequested);
                root.put(entry.getKey(), value);
            }
            return Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.IDLE_MANAGER_BUCKET_STATE, root.toString());
        } catch (Exception e) {
            Log.e(TAG, "Cannot persist bucket recovery state", e);
            return false;
        }
    }

    private static String bucketName(int bucket) {
        switch (bucket) {
            case STANDBY_BUCKET_ACTIVE:
                return "ACTIVE";
            case STANDBY_BUCKET_WORKING_SET:
                return "WORKING_SET";
            case STANDBY_BUCKET_FREQUENT:
                return "FREQUENT";
            case STANDBY_BUCKET_RARE:
                return "RARE";
            case STANDBY_BUCKET_RESTRICTED:
                return "RESTRICTED";
            default:
                return "UNKNOWN(" + bucket + ")";
        }
    }

    private boolean isAppIdleLongEnough(String pkg, long now, Map<String, UsageStats> stats) {
        UsageStats appStats = stats.get(pkg);
        return appStats == null || now - appStats.getLastTimeUsed() >= IDLE_TIMEOUT_MS;
    }

    private Set<String> getForegroundPackages(
            @NonNull List<ActivityManager.RunningAppProcessInfo> processes) {
        Set<String> fg = new HashSet<>();
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (p.importance
                    <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
                    && p.pkgList != null) {
                for (String pkg : p.pkgList) fg.add(pkg);
            }
        }
        return fg;
    }

    private boolean isAudioActive() {
        if (mAudioManager == null) return false;
        try {
            if (mAudioManager.isMusicActive()) return true;

            int mode = mAudioManager.getMode();
            if (mode == AudioManager.MODE_IN_CALL
                    || mode == AudioManager.MODE_IN_COMMUNICATION
                    || mode == AudioManager.MODE_RINGTONE) {
                Log.d(TAG, "Audio mode active (" + mode + ") — skipping enforcement");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "isAudioActive audio check failed: " + e.getMessage());
        }

        if (mTelephonyManager != null) {
            try {
                int callState = mTelephonyManager.getCallState();
                if (callState != TelephonyManager.CALL_STATE_IDLE) {
                    Log.d(TAG, "Call state active (" + callState + ") — skipping enforcement");
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "TelephonyManager.getCallState() unavailable: " + e.getMessage());
            }
        }

        return false;
    }

    private boolean isActiveMediaApp(@NonNull String pkg,
            @NonNull List<ActivityManager.RunningAppProcessInfo> processes) {
        for (ActivityManager.RunningAppProcessInfo p : processes) {
            if (p.pkgList == null) continue;
            for (String name : p.pkgList) {
                if (name.equals(pkg)) {
                    return p.importance
                            <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
                }
            }
        }
        return false;
    }

    private void loadConfigFromSettings() {
        ContentResolver cr = mContext.getContentResolver();
        mEnabled = Settings.Secure.getInt(cr, Settings.Secure.IDLE_MANAGER, 1) == 1;

        mSleepModeTriggerEnabled = Settings.Secure.getInt(
                cr, Settings.Secure.IDLE_MANAGER_SLEEP_MODE_TRIGGER, 0) == 1;
        mIsSleepModeActive = Settings.Secure.getInt(
                cr, Settings.Secure.SLEEP_MODE_ENABLED, 0) == 1;

        String appsJson = Settings.Secure.getString(cr, Settings.Secure.IDLE_MANAGER_APPS);
        mAppConfigCache = Collections.unmodifiableMap(parseAppConfigs(appsJson));
        Log.d(TAG, "Config loaded — enabled=" + mEnabled
                + ", apps=" + mAppConfigCache.size());
    }

    private synchronized void onSettingsChanged() {
        if (mDestroyed) return;
        boolean enabled = mEnabled;
        boolean trigger = mSleepModeTriggerEnabled;
        boolean sleepActive = mIsSleepModeActive;
        Map<String, AppConfig> previous = mAppConfigCache;
        loadConfigFromSettings();
        if (enabled == mEnabled && trigger == mSleepModeTriggerEnabled
                && sleepActive == mIsSleepModeActive && sameConfigs(previous, mAppConfigCache)) {
            return;
        }
        haltManager();
        Map<String, AppConfig> configs = mAppConfigCache;
        if (!isPolicyEnabled()) {
            restoreAllBuckets();
        } else {
            mIoExecutor.execute(() -> restoreBuckets(false, configs));
            if (mPowerManager != null && !mPowerManager.isInteractive()) executeManager();
        }
    }

    private static boolean sameConfigs(Map<String, AppConfig> a, Map<String, AppConfig> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (String pkg : a.keySet()) {
            if (a.get(pkg).action != b.get(pkg).action) return false;
        }
        return true;
    }

    private void registerSettingsObserver() {
        ContentResolver cr = mContext.getContentResolver();
        mSettingsObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange, @Nullable Uri uri) {
                onSettingsChanged();
            }
        };
        for (String key : new String[]{
                Settings.Secure.IDLE_MANAGER,
                Settings.Secure.IDLE_MANAGER_APPS,
                Settings.Secure.IDLE_MANAGER_SLEEP_MODE_TRIGGER,
                Settings.Secure.SLEEP_MODE_ENABLED}) {
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, mSettingsObserver);
        }
    }

    private void unregisterSettingsObserver() {
        if (mSettingsObserver != null)
            mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
    }

    private void registerBatteryReceiver() {
        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
                if (level >= 0 && scale > 0)
                    mBatteryLevel = (int) ((level / (float) scale) * 100);
                mIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = mContext.registerReceiver(mBatteryReceiver, filter,
                Context.RECEIVER_NOT_EXPORTED);
        if (sticky != null) mBatteryReceiver.onReceive(mContext, sticky);
    }

    private void registerDozeReceiver() {
        mDozeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(intent.getAction())) {
                    return;
                }
                if (mDestroyed || !mIsRunning) return;
                boolean idle = mPowerManager != null && mPowerManager.isDeviceIdleMode();
                Log.d(TAG, "Doze mode changed — idle=" + idle);
                if (idle) {
                    onScanAlarmFired();
                }
            }
        };
        IntentFilter filter = new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        mContext.registerReceiver(mDozeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterDozeReceiver() {
        if (mDozeReceiver != null) {
            try {
                mContext.unregisterReceiver(mDozeReceiver);
            } catch (IllegalArgumentException ignored) {}
            mDozeReceiver = null;
        }
    }

    private void unregisterBatteryReceiver() {
        if (mBatteryReceiver != null) {
            try { mContext.unregisterReceiver(mBatteryReceiver); }
            catch (IllegalArgumentException ignored) {}
            mBatteryReceiver = null;
        }
    }

    private boolean persistAppConfigs(@NonNull Map<String, AppConfig> configs) {
        try {
            JSONArray arr = new JSONArray();
            for (AppConfig c : configs.values()) arr.put(c.toJson());
            return Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.IDLE_MANAGER_APPS, arr.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to persist configs", e);
            return false;
        }
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
            Log.e(TAG, "Failed to parse configs: " + e.getMessage());
        }
        return map;
    }

    private void updateKillStats(Map<String, IdleAction> actions, long tsMs) {
        if (actions.isEmpty()) return;
        ContentResolver cr = mContext.getContentResolver();
        try {
            String existing = Settings.Secure.getString(cr, Settings.Secure.IDLE_MANAGER_KILL_STATS);
            JSONObject root = (existing != null && !existing.isEmpty())
                    ? new JSONObject(existing) : new JSONObject();
            for (Map.Entry<String, IdleAction> action : actions.entrySet()) {
                String pkg = action.getKey();
                JSONObject entry = root.optJSONObject(pkg);
                if (entry == null) entry = new JSONObject();
                entry.put("count", entry.optInt("count", 0) + 1);
                entry.put("last_kill", tsMs);
                entry.put("last_action", action.getValue().name());
                root.put(pkg, entry);
            }
            Settings.Secure.putString(cr, Settings.Secure.IDLE_MANAGER_KILL_STATS, root.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to update enforcement statistics", e);
        }
    }

    private long getDynamicScanIntervalMs() {
        if (mIsCharging) 
            return SCAN_INTERVAL_CHARGING_MS;
        if (mBatteryLevel > BATTERY_HIGH_THRESHOLD) 
            return SCAN_INTERVAL_HIGH_MS;
        if (mBatteryLevel > BATTERY_LOW_THRESHOLD)  
            return SCAN_INTERVAL_MS;
        return SCAN_INTERVAL_LOW_MS;
    }

    private long getMillisUntilNextAlarm() {
        if (mAlarmManager == null) return 0;
        try {
            AlarmManager.AlarmClockInfo info = mAlarmManager.getNextAlarmClock();
            if (info != null)
                return Math.max(0, info.getTriggerTime() - System.currentTimeMillis());
        } catch (Exception e) {
            Log.e(TAG, "Error reading next alarm", e);
        }
        return 0;
    }

    private void cancelCallbacks() {
        cancelScanAlarm();
        cancelHaltAlarm();
    }
}
