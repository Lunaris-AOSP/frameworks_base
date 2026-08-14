/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.powerinsight;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.BatteryUsageStats;
import android.os.BatteryStatsManager;
import android.os.UidBatteryConsumer;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.VibrationEffect;
import android.os.Vibrator;

import com.android.internal.os.IPowerInsightService;
import com.android.internal.os.PowerInsightFlowSample;
import com.android.internal.os.PowerInsightHistoryBucket;
import com.android.internal.os.PowerInsightStats;
import com.android.internal.os.PowerInsightAppUsage;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

public class PowerInsightService extends IPowerInsightService.Stub {
    private static final String TAG = "PowerInsightService";
    private static final boolean DEBUG = true;

    private static final String STATS_FILE = "/data/system/power_insight_stats.xml";
    private static final String NOTIF_CHANNEL_ID = "power_insight";
    private static final int NOTIF_ID = 1001;
    
    private static final int MAX_FLOW_SAMPLES = 1440;
    private static final long MONITOR_INTERVAL_MS = 2000;
    private static final long FLOW_SAMPLE_INTERVAL_MS = 2000;

    // Settings Keys
    private static final String KEY_ENABLED = "power_insight_enabled";
    private static final String KEY_NOTIF_ENABLED = "power_insight_notif_enabled";
    private static final String KEY_AUTO_RESET_LEVEL_ENABLED = "power_insight_auto_reset_level_enabled";
    private static final String KEY_AUTO_RESET_LEVEL = "power_insight_auto_reset_level";
    private static final String KEY_RESET_ON_PLUGGED = "power_insight_reset_on_plugged";
    private static final String KEY_RESET_ON_REBOOT = "power_insight_reset_on_reboot";
    private static final String KEY_MONITOR_INTERVAL = "power_insight_monitor_interval";
    private static final String KEY_BATTERY_ALARM_ENABLED = "power_insight_battery_alarm_enabled";
    private static final String KEY_BATTERY_LOW_THRESHOLD = "power_insight_battery_low_threshold";
    private static final String KEY_BATTERY_HIGH_THRESHOLD = "power_insight_battery_high_threshold";
    private static final String KEY_ALARM_FREQUENCY = "power_insight_alarm_frequency";
    private static final String KEY_FULL_CHARGE_ALARM_ENABLED = "power_insight_full_charge_alarm_enabled";
    private static final String KEY_BATTERY_ALARM_SOUND = "power_insight_battery_alarm_sound";
    private static final String KEY_BATTERY_ALARM_VIBRATE = "power_insight_battery_alarm_vibrate";
    private static final int DEFAULT_MONITOR_INTERVAL = 10000;

    private final Context mContext;
    private final Handler mHandler;
    private final PowerManager mPowerManager;
    private final AtomicFile mAtomicFile;
    private boolean mNotifChannelCreated;

    private final List<PowerInsightFlowSample> mFlowSamples = Collections.synchronizedList(new ArrayList<>());
    private final List<PowerInsightHistoryBucket> mHistoryBuckets = Collections.synchronizedList(new ArrayList<>());

    private final PowerInsightStats mCurrentStats = new PowerInsightStats();
    private long mLastMonitorTime = 0;
    private long mLastFlowSampleTime = 0;
    private int mLastBatteryLevel = -1;
    private long mLastHistoryUpdate = 0;
    
    // Internal trackers
    private long mScreenOnTime = 0;
    private long mScreenOffTime = 0;
    private long mDeepSleepTime = 0;
    private int mBatteryDrainOn = 0;
    private int mBatteryDrainOff = 0;
    private long mLastScreenToggleTime = 0;
    private long mBootDeepSleepAtLastCheck = 0;
    private long mLastDiskSaveTime = 0;

    // Charging session trackers
    private long mChargingStartTime = 0;
    private long mChargingEndTime = 0;
    private int mChargingStartLevel = -1;
    private int mChargingEndLevel = -1;
    private int mChargingStartCapacity = 0;
    private int mChargingEndCapacity = 0;
    
    private long mChargingScreenOnTime = 0;
    private int mChargingScreenOnLevelCharged = 0;
    private int mChargingScreenOnMahCharged = 0;
    
    private long mChargingScreenOffTime = 0;
    private int mChargingScreenOffLevelCharged = 0;
    private int mChargingScreenOffMahCharged = 0;
    
    private long mLastChargingScreenToggleTime = 0;

    private String mBatteryBasePath;
    private String mCycleCountPath;
    private boolean mIsCharging = false;
    private boolean mIsPlugged = false;

    private int mMinCurrent = Integer.MAX_VALUE;
    private int mMaxCurrent = Integer.MIN_VALUE;
    private long mTotalCurrent = 0;
    private int mSampleCount = 0;

    private int mLastAlarmNotifiedLevel = -1;
    private long mLastAlarmNotifiedTime = 0;
    private boolean mFullChargeAlarmTriggered = false;

    public PowerInsightService(Context context) {
        mContext = context;
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mAtomicFile = new AtomicFile(new File(STATS_FILE));
        Slog.i(TAG, "Initializing PowerInsight service");

        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new PowerInsightHandler(thread.getLooper());

        detectHardwarePaths();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_REBOOT);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        
        // Handle broadcasts on handler thread to avoid blocking main thread
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleBroadcast(intent);
            }
        }, filter, null, mHandler);

        mContext.getContentResolver().registerContentObserver(
            Settings.System.getUriFor(KEY_ENABLED), false, new SettingsObserver(mHandler));
        mContext.getContentResolver().registerContentObserver(
            Settings.System.getUriFor(KEY_NOTIF_ENABLED), false, new SettingsObserver(mHandler));
        mContext.getContentResolver().registerContentObserver(
            Settings.System.getUriFor(KEY_MONITOR_INTERVAL), false, new SettingsObserver(mHandler));

        mLastScreenToggleTime = SystemClock.elapsedRealtime();
        mBootDeepSleepAtLastCheck = getSystemDeepSleepTimeSafe();
        mLastDiskSaveTime = System.currentTimeMillis();
        
        // Defer heavy initialization to avoid blocking system server boot
        mHandler.sendEmptyMessage(MSG_INIT);
    }

    private void updateScreenTimeDeltas(long now) {
        long delta = now - mLastScreenToggleTime;
        if (delta > 0 && !mIsCharging) {
            if (mPowerManager.isInteractive()) {
                mScreenOnTime += delta;
            } else {
                mScreenOffTime += delta;
            }
        }
        mLastScreenToggleTime = now;
    }

    private void updateDeepSleepDelta() {
        long currentBootDeepSleep = getSystemDeepSleepTimeSafe();
        long deltaDeepSleep = currentBootDeepSleep - mBootDeepSleepAtLastCheck;
        if (deltaDeepSleep > 0 && !mIsCharging) {
            mDeepSleepTime += deltaDeepSleep;
        }
        mBootDeepSleepAtLastCheck = currentBootDeepSleep;
    }

    private void handleBroadcast(Intent intent) {
        String action = intent.getAction();
        long now = SystemClock.elapsedRealtime();
        
        if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            
            boolean wasPlugged = mIsPlugged;
            mIsPlugged = plugged != 0;
            boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING 
                        || status == BatteryManager.BATTERY_STATUS_FULL;

            if (isCharging != mIsCharging) {
                // Flush the screen time delta accumulated under the OLD charging state before transition
                long delta = now - mLastScreenToggleTime;
                if (delta > 0 && !mIsCharging) {
                    if (mPowerManager.isInteractive()) {
                        mScreenOnTime += delta;
                    } else {
                        mScreenOffTime += delta;
                    }
                }
                
                // Flush deep sleep delta under the OLD charging state before transition
                updateDeepSleepDelta();
                
                if (isCharging) {
                    mChargingStartTime = System.currentTimeMillis();
                    mChargingEndTime = 0;
                    mChargingStartLevel = level;
                    mChargingEndLevel = level;
                    mChargingStartCapacity = getCurrentCapacityMah();
                    mChargingEndCapacity = mChargingStartCapacity;
                    mChargingScreenOnTime = 0;
                    mChargingScreenOffTime = 0;
                    mChargingScreenOnLevelCharged = 0;
                    mChargingScreenOnMahCharged = 0;
                    mChargingScreenOffLevelCharged = 0;
                    mChargingScreenOffMahCharged = 0;
                    mLastChargingScreenToggleTime = now;
                } else {
                    long chgDelta = now - mLastChargingScreenToggleTime;
                    if (chgDelta > 0) {
                        if (mPowerManager.isInteractive()) {
                            mChargingScreenOnTime += chgDelta;
                        } else {
                            mChargingScreenOffTime += chgDelta;
                        }
                    }
                    mChargingEndTime = System.currentTimeMillis();
                    mChargingEndLevel = level;
                    mChargingEndCapacity = getCurrentCapacityMah();
                }
                
                mLastScreenToggleTime = now;
                mIsCharging = isCharging;
            }

            if (mIsCharging) {
                mChargingEndLevel = level;
                mChargingEndTime = System.currentTimeMillis();
                
                if (mLastBatteryLevel != -1 && level > mLastBatteryLevel) {
                    int levelDiff = level - mLastBatteryLevel;
                    int totalCap = getTotalCapacityMah();
                    int mahDiff = levelDiff * totalCap / 100;
                    if (mPowerManager.isInteractive()) {
                        mChargingScreenOnLevelCharged += levelDiff;
                        mChargingScreenOnMahCharged += mahDiff;
                    } else {
                        mChargingScreenOffLevelCharged += levelDiff;
                        mChargingScreenOffMahCharged += mahDiff;
                    }
                }
            } else if (mLastBatteryLevel != -1 && level < mLastBatteryLevel) {
                int drain = mLastBatteryLevel - level;
                if (mPowerManager.isInteractive()) mBatteryDrainOn += drain;
                else mBatteryDrainOff += drain;
                updateHistoryDrain(drain);
            }
            
            if (!wasPlugged && mIsPlugged && getResetOnPlugged()) {
                resetStatsInternal("Plugged in reset");
            }
            
            if (getAutoResetLevelEnabled()) {
                int target = getAutoResetLevel();
                if (level >= target && mLastBatteryLevel < target) {
                    resetStatsInternal("Battery level reset (" + target + "%)");
                }
            }

            checkBatteryAlarms(level);

            mLastBatteryLevel = level;
            updateRealtimeMetrics(intent);
            if (isNotificationEnabled()) updateNotification();
            
        } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
            long delta = now - mLastScreenToggleTime;
            if (delta > 0 && !mIsCharging) {
                mScreenOffTime += delta;
            }
            if (mIsCharging) {
                long chgDelta = now - mLastChargingScreenToggleTime;
                if (chgDelta > 0) mChargingScreenOffTime += chgDelta;
                mLastChargingScreenToggleTime = now;
            }
            mLastScreenToggleTime = now;
            mLastHistoryUpdate = now;
            updateDeepSleepDelta();
            
            // Resume polling when screen is interactive
            mHandler.removeMessages(MSG_MONITOR);
            if (isEnabled()) {
                mHandler.sendEmptyMessage(MSG_MONITOR);
            }
        } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
            long delta = now - mLastScreenToggleTime;
            if (delta > 0 && !mIsCharging) {
                mScreenOnTime += delta;
            }
            if (mIsCharging) {
                long chgDelta = now - mLastChargingScreenToggleTime;
                if (chgDelta > 0) mChargingScreenOnTime += chgDelta;
                mLastChargingScreenToggleTime = now;
            }
            mLastScreenToggleTime = now;
            updateHistorySot(now);
            updateDeepSleepDelta();
            
            // Save immediately on screen off and stop background CPU polling
            saveStats();
            mHandler.removeMessages(MSG_MONITOR);
        } else if (Intent.ACTION_REBOOT.equals(action) || Intent.ACTION_SHUTDOWN.equals(action)) {
            updateScreenTimeDeltas(now);
            updateDeepSleepDelta();
            if (mIsCharging) {
                long chgDelta = now - mLastChargingScreenToggleTime;
                if (chgDelta > 0) {
                    if (mPowerManager.isInteractive()) {
                        mChargingScreenOnTime += chgDelta;
                    } else {
                        mChargingScreenOffTime += chgDelta;
                    }
                }
                mChargingEndLevel = getBatteryLevelInternal();
                mChargingEndCapacity = getCurrentCapacityMah();
                mChargingEndTime = System.currentTimeMillis();
            }
            saveStats();
        }
    }

    private void detectHardwarePaths() {
        String[] possibleBatteryPaths = {"/sys/class/power_supply/battery", "/sys/class/power_supply/bms", "/sys/class/power_supply/BAT0"};
        for (String path : possibleBatteryPaths) {
            if (new File(path).isDirectory()) { 
                mBatteryBasePath = path; 
                Slog.i(TAG, "Found battery base path: " + path);
                break; 
            }
        }
        if (mBatteryBasePath == null) {
            mBatteryBasePath = "/sys/class/power_supply/battery";
            Slog.w(TAG, "No battery base path found, defaulting to: " + mBatteryBasePath);
        }

        String[] cyclePaths = {
            mBatteryBasePath + "/cycle_count", 
            mBatteryBasePath + "/battery_cycle_count", 
            "/sys/class/power_supply/bms/cycle_count",
            "/sys/class/power_supply/battery/cycle_count"
        };
        for (String path : cyclePaths) {
            if (new File(path).exists()) { 
                mCycleCountPath = path; 
                Slog.i(TAG, "Found cycle count path: " + path);
                break; 
            }
        }
    }

    @Override
    public PowerInsightStats getBatteryState() {
        synchronized (mCurrentStats) {
            fillStats(mCurrentStats);
            return mCurrentStats;
        }
    }

    private void fillStats(PowerInsightStats stats) {
        long now = SystemClock.elapsedRealtime();
        long delta = now - mLastScreenToggleTime;
        boolean screenOn = mPowerManager.isInteractive();
        
        stats.screenOnTime = mScreenOnTime + (screenOn && !mIsCharging ? delta : 0);
        stats.screenOffTime = mScreenOffTime + (!screenOn && !mIsCharging ? delta : 0);
        
        updateDeepSleepDelta();
        
        stats.deepSleepTime = mDeepSleepTime;
        stats.awakeTime = Math.max(0, stats.screenOffTime - stats.deepSleepTime);
        
        stats.batteryDrainScreenOn = mBatteryDrainOn;
        stats.batteryDrainScreenOff = mBatteryDrainOff;

        float hoursOn = stats.screenOnTime / 3600000f;
        float hoursOff = stats.screenOffTime / 3600000f;
        stats.activeDrainRate = hoursOn > 0.01f ? mBatteryDrainOn / hoursOn : 0;
        stats.idleDrainRate = hoursOff > 0.01f ? mBatteryDrainOff / hoursOff : 0;

        // HW metrics
        stats.currentNow = readIntFromFile(mBatteryBasePath + "/current_now");
        if (stats.currentNow == 0) stats.currentNow = readIntFromFile("/sys/class/power_supply/bms/current_now");
        stats.currentNow /= 1000;

        stats.voltage = readIntFromFile(mBatteryBasePath + "/voltage_now");
        if (stats.voltage == 0) stats.voltage = readIntFromFile(mBatteryBasePath + "/voltage_avg");
        if (stats.voltage == 0) stats.voltage = readIntFromFile("/sys/class/power_supply/bms/voltage_now");
        stats.voltage /= 1000;

        stats.temp = readIntFromFile(mBatteryBasePath + "/temp");
        if (stats.temp == 0) stats.temp = readIntFromFile("/sys/class/power_supply/bms/temp");

        stats.cycleCount = mCycleCountPath != null ? readIntFromFile(mCycleCountPath) : 0;
        
        int chargeFull = readIntFromFile(mBatteryBasePath + "/charge_full");
        if (chargeFull == 0) chargeFull = readIntFromFile("/sys/class/power_supply/bms/charge_full");
        
        int chargeDesign = readIntFromFile(mBatteryBasePath + "/charge_full_design");
        if (chargeDesign == 0) chargeDesign = readIntFromFile("/sys/class/power_supply/bms/charge_full_design");
        
        int chargeCounter = readIntFromFile(mBatteryBasePath + "/charge_counter");
        if (chargeCounter == 0) chargeCounter = readIntFromFile(mBatteryBasePath + "/charge_now");
        if (chargeCounter == 0) chargeCounter = readIntFromFile("/sys/class/power_supply/bms/charge_counter");
        
        int capacityFull = readIntFromFile(mBatteryBasePath + "/capacity_full");
        if (capacityFull == 0) capacityFull = readIntFromFile("/sys/class/power_supply/bms/capacity_full");

        // Handle both mAh and uAh (some kernels use uAh)
        if (chargeFull > 20000) chargeFull /= 1000;
        if (chargeDesign > 20000) chargeDesign /= 1000;
        if (chargeCounter > 20000) chargeCounter /= 1000;
        if (capacityFull > 20000) capacityFull /= 1000;

        if (chargeDesign > 0) {
            stats.totalCapacity = chargeDesign;
            stats.currentCapacity = chargeFull;
            stats.capacityHealth = (float) chargeFull * 100f / chargeDesign;
            float cycleHealth = Math.max(0, 100f - ((stats.cycleCount / 800f) * 20f));
            stats.healthPercent = (stats.capacityHealth * 0.7f) + (cycleHealth * 0.3f);
        } else {
            stats.totalCapacity = capacityFull;
            stats.currentCapacity = chargeCounter;
            if (stats.totalCapacity > 0 && stats.currentCapacity > 0) {
                stats.capacityHealth = (float) stats.currentCapacity * 100f / stats.totalCapacity;
                stats.healthPercent = stats.capacityHealth;
            } else {
                stats.capacityHealth = 100f;
                stats.healthPercent = 100f;
            }
        }
        
        if (stats.totalCapacity <= 0) stats.totalCapacity = 5000;
        if (stats.currentCapacity <= 0) stats.currentCapacity = stats.totalCapacity;
        if (stats.healthPercent <= 0f || Float.isNaN(stats.healthPercent)) stats.healthPercent = 100f;

        stats.isNotificationEnabled = isNotificationEnabled();
        stats.monitorInterval = getMonitorInterval();
        stats.isAutoResetLevelEnabled = getAutoResetLevelEnabled();
        stats.autoResetLevel = getAutoResetLevel();
        stats.isResetOnPlugged = getResetOnPlugged();
        stats.isResetOnReboot = getResetOnReboot();
        stats.isBatteryAlarmEnabled = isBatteryAlarmEnabled();
        stats.batteryLowThreshold = getBatteryLowThreshold();
        stats.batteryHighThreshold = getBatteryHighThreshold();
        stats.alarmFrequency = getAlarmFrequency();
        stats.isFullChargeAlarmEnabled = isFullChargeAlarmEnabled();
        stats.batteryAlarmSound = getBatteryAlarmSound();
        stats.isBatteryAlarmVibrate = isBatteryAlarmVibrate();

        // Populate charging session details
        long wallNow = System.currentTimeMillis();
        long elapsedNow = SystemClock.elapsedRealtime();
        
        long chargingDelta = elapsedNow - mLastChargingScreenToggleTime;
        long curChargingScreenOn = mChargingScreenOnTime + (mIsCharging && screenOn ? chargingDelta : 0);
        long curChargingScreenOff = mChargingScreenOffTime + (mIsCharging && !screenOn ? chargingDelta : 0);
        
        stats.chargingStartTime = mChargingStartTime;
        stats.chargingEndTime = mChargingEndTime == 0 && mIsCharging ? wallNow : mChargingEndTime;
        stats.chargingScreenOnTime = curChargingScreenOn;
        stats.chargingScreenOffTime = curChargingScreenOff;
        stats.chargingDurationTime = curChargingScreenOn + curChargingScreenOff;
        
        stats.chargingScreenOnLevelCharged = mChargingScreenOnLevelCharged;
        stats.chargingScreenOnMahCharged = mChargingScreenOnMahCharged;
        stats.chargingScreenOffLevelCharged = mChargingScreenOffLevelCharged;
        stats.chargingScreenOffMahCharged = mChargingScreenOffMahCharged;
        
        stats.chargingLevelCharged = mChargingScreenOnLevelCharged + mChargingScreenOffLevelCharged;
        stats.chargingMahCharged = mChargingScreenOnMahCharged + mChargingScreenOffMahCharged;
        
        float chargingHours = stats.chargingDurationTime / 3600000f;
        float chargingOnHours = stats.chargingScreenOnTime / 3600000f;
        float chargingOffHours = stats.chargingScreenOffTime / 3600000f;
        
        stats.chargingRatePercentPerHour = chargingHours > 0.01f ? stats.chargingLevelCharged / chargingHours : 0f;
        stats.chargingScreenOnRatePercentPerHour = chargingOnHours > 0.01f ? stats.chargingScreenOnLevelCharged / chargingOnHours : 0f;
        stats.chargingScreenOffRatePercentPerHour = chargingOffHours > 0.01f ? stats.chargingScreenOffLevelCharged / chargingOffHours : 0f;

        // Analytics
        int current = stats.currentNow;
        if (current != 0) {
            if (current < mMinCurrent) mMinCurrent = current;
            if (current > mMaxCurrent) mMaxCurrent = current;
            mTotalCurrent += current;
            mSampleCount++;
        }
        
        stats.minCurrent = mMinCurrent == Integer.MAX_VALUE ? 0 : mMinCurrent;
        stats.maxCurrent = mMaxCurrent == Integer.MIN_VALUE ? 0 : mMaxCurrent;
        stats.avgCurrent = mSampleCount > 0 ? (int)(mTotalCurrent / mSampleCount) : 0;
        stats.powerWatts = (Math.abs(stats.currentNow) * stats.voltage) / 1000000f;
    }

    private void updateRealtimeMetrics(Intent batteryIntent) {
        synchronized (mCurrentStats) {
            mCurrentStats.level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            mCurrentStats.isCharging = mIsCharging;
            mCurrentStats.status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            mCurrentStats.plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            mCurrentStats.health = readBatteryHealth(batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1));
            fillStats(mCurrentStats);
        }
    }

    private void monitor() {
        if (!isEnabled() || !mPowerManager.isInteractive()) return;
        
        long wallNow = System.currentTimeMillis();
        if (wallNow - mLastFlowSampleTime >= FLOW_SAMPLE_INTERVAL_MS) {
            synchronized (mCurrentStats) { fillStats(mCurrentStats); }
            addFlowSample(new PowerInsightFlowSample(wallNow, mCurrentStats.currentNow, mIsCharging));
            mLastFlowSampleTime = wallNow;
            updateHistoryBuckets(wallNow);
            
            // Save to disk periodically
            if (wallNow - mLastDiskSaveTime >= 5 * 60 * 1000) {
                saveStats();
                mLastDiskSaveTime = wallNow;
            }
        }

        if (isNotificationEnabled()) updateNotification();
        mHandler.sendEmptyMessageDelayed(MSG_MONITOR, getMonitorInterval());
    }

    private void addFlowSample(PowerInsightFlowSample sample) {
        synchronized (mFlowSamples) {
            mFlowSamples.add(sample);
            if (mFlowSamples.size() > MAX_FLOW_SAMPLES) mFlowSamples.remove(0);
        }
    }

    private void updateHistoryBuckets(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);

        synchronized (mHistoryBuckets) {
            PowerInsightHistoryBucket current = null;
            if (!mHistoryBuckets.isEmpty()) {
                PowerInsightHistoryBucket last = mHistoryBuckets.get(mHistoryBuckets.size() - 1);
                if (last.hour == hour) current = last;
            }
            if (current == null) {
                current = new PowerInsightHistoryBucket(hour);
                mHistoryBuckets.add(current);
                if (mHistoryBuckets.size() > 24) mHistoryBuckets.remove(0);
            }
            updateHistorySot(SystemClock.elapsedRealtime());
        }
    }

    private void updateHistorySot(long now) {
        if (mLastHistoryUpdate == 0) { mLastHistoryUpdate = now; return; }
        if (!mPowerManager.isInteractive()) { mLastHistoryUpdate = now; return; }
        long delta = now - mLastHistoryUpdate;
        if (delta > 0) {
            synchronized (mHistoryBuckets) {
                if (!mHistoryBuckets.isEmpty()) {
                    mHistoryBuckets.get(mHistoryBuckets.size() - 1).screenOnMs += delta;
                }
            }
            mLastHistoryUpdate = now;
        }
    }

    private void updateHistoryDrain(int drain) {
        synchronized (mHistoryBuckets) {
            if (!mHistoryBuckets.isEmpty()) {
                mHistoryBuckets.get(mHistoryBuckets.size() - 1).drainPercent += drain;
            }
        }
    }

    private void updateNotification() {
        NotificationManager notifManager = getNotificationManager();
        if (notifManager == null) return;
        if (!ensureNotificationReady(notifManager)) return;

        PowerInsightStats s = new PowerInsightStats();
        synchronized (mCurrentStats) { 
            fillStats(s);
            s.level = mCurrentStats.level;
            s.isCharging = mCurrentStats.isCharging;
        }

        String currentStr = (s.currentNow > 0 ? "+" : s.currentNow < 0 ? "-" : "") + Math.abs(s.currentNow) + " mA";
        String statusStr = s.isCharging ? mContext.getString(com.android.internal.R.string.power_insight_notif_charging) 
                                     : mContext.getString(com.android.internal.R.string.power_insight_notif_discharging);
        
        String title = s.level + "% • " + statusStr + " • " + currentStr + " • " + String.format("%.1f", s.temp / 10f) + "°C";
        
        StringBuilder content = new StringBuilder();
        content.append(mContext.getString(com.android.internal.R.string.power_insight_notif_active, String.format("%.1f", s.activeDrainRate))).append(" • ");
        content.append(mContext.getString(com.android.internal.R.string.power_insight_notif_idle, String.format("%.1f", s.idleDrainRate))).append("\n");
        
        content.append(mContext.getString(com.android.internal.R.string.power_insight_notif_screen_on, formatDuration(s.screenOnTime), String.valueOf(s.batteryDrainScreenOn))).append("\n");
        content.append(mContext.getString(com.android.internal.R.string.power_insight_notif_screen_off, formatDuration(s.screenOffTime), String.valueOf(s.batteryDrainScreenOff))).append("\n");
        
        float totalOff = Math.max(1, s.screenOffTime);
        content.append(mContext.getString(com.android.internal.R.string.power_insight_notif_awake, formatDuration(s.awakeTime), String.format("%.1f", s.awakeTime * 100f / totalOff))).append("\n");
        content.append(mContext.getString(com.android.internal.R.string.power_insight_notif_deep_sleep, formatDuration(s.deepSleepTime), String.format("%.1f", s.deepSleepTime * 100f / totalOff)));

        Notification.Builder builder = new Notification.Builder(mContext, NOTIF_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(s.level + "% • " + currentStr)
            .setStyle(new Notification.BigTextStyle().bigText(content.toString()))
            .setSmallIcon(generateNotificationIcon())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC);

        notifManager.notify(NOTIF_ID, builder.build());
    }

    private boolean ensureNotificationReady(NotificationManager notifManager) {
        if (mNotifChannelCreated) return true;
        try {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID, "Power Insight", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            notifManager.createNotificationChannel(channel);
            mNotifChannelCreated = true;
            return true;
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to create notification channel", t);
            return false;
        }
    }

    private NotificationManager getNotificationManager() {
        try {
            return mContext.getSystemService(NotificationManager.class);
        } catch (Throwable t) {
            if (DEBUG) Slog.w(TAG, "NotificationManager unavailable", t);
            return null;
        }
    }

    private String formatDuration(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        long h = m / 60;
        return h > 0 ? String.format("%dh %dm", h, m % 60) : String.format("%dm %ds", m, s % 60);
    }

    private int readIntFromFile(String path) {
        if (path == null) return 0;
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] buffer = new byte[64];
            int len = fis.read(buffer);
            if (len <= 0) return 0;
            String content = new String(buffer, 0, len, StandardCharsets.UTF_8).trim();
            return Integer.parseInt(content);
        } catch (Exception e) {
            if (DEBUG) Slog.w(TAG, "Failed to read " + path + ": " + e.getMessage());
            return 0;
        }
    }

    private String readBatteryHealth(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over voltage";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Cold";
            default: return "Unknown";
        }
    }


    private long getSystemDeepSleepTimeSafe() {
        return Math.max(0, SystemClock.elapsedRealtime() - SystemClock.uptimeMillis());
    }

    private void checkBatteryAlarms(int level) {
        if (level == 100 && isFullChargeAlarmEnabled() && !mFullChargeAlarmTriggered) {
            sendAlarmNotification("Battery fully charged", "Your device is 100% charged");
            mFullChargeAlarmTriggered = true;
            return;
        } else if (level < 100) {
            mFullChargeAlarmTriggered = false;
        }

        if (!isBatteryAlarmEnabled()) return;

        int low = getBatteryLowThreshold();
        int high = getBatteryHighThreshold();
        boolean isThresholdHit = level <= low || level >= high;

        if (!isThresholdHit) {
            mLastAlarmNotifiedLevel = -1;
            return;
        }

        int freq = getAlarmFrequency();
        long now = SystemClock.elapsedRealtime();

        boolean shouldNotify = false;
        switch (freq) {
            case 0: // Only once
                if (mLastAlarmNotifiedLevel == -1) shouldNotify = true;
                break;
            case 1: // Every 1% change
                if (level != mLastAlarmNotifiedLevel) shouldNotify = true;
                break;
            case 2: // Every 5% change
                if (mLastAlarmNotifiedLevel == -1 || Math.abs(level - mLastAlarmNotifiedLevel) >= 5) shouldNotify = true;
                break;
            case 3: // Every 10% change
                if (mLastAlarmNotifiedLevel == -1 || Math.abs(level - mLastAlarmNotifiedLevel) >= 10) shouldNotify = true;
                break;
            case 4: // Every 5 minutes
                if (now - mLastAlarmNotifiedTime >= 5 * 60 * 1000) shouldNotify = true;
                break;
        }

        if (shouldNotify) {
            String title = level <= low ? "Low Battery Alarm" : "Battery Level Alert";
            String text = "Battery reached " + level + "%";
            sendAlarmNotification(title, text);
            mLastAlarmNotifiedLevel = level;
            mLastAlarmNotifiedTime = now;
        }
    }

    private void sendAlarmNotification(String title, String text) {
        NotificationManager nm = getNotificationManager();
        if (nm == null) return;
        ensureNotificationReady(nm);

        Notification.Builder builder = new Notification.Builder(mContext, NOTIF_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(com.android.internal.R.drawable.ic_lock_idle_low_battery)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL);

        nm.notify(NOTIF_ID + 1, builder.build());

        // Custom Sound
        String soundUri = getBatteryAlarmSound();
        if (soundUri != null && !soundUri.isEmpty()) {
            try {
                Uri uri = Uri.parse(soundUri);
                Ringtone r = RingtoneManager.getRingtone(mContext, uri);
                if (r != null) {
                    r.play();
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to play alarm sound", e);
            }
        }

        // Custom Vibration
        if (isBatteryAlarmVibrate()) {
            Vibrator v = mContext.getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) {
                long[] pattern = {0, 500, 200, 500, 200, 500}; // Long triple buzz
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            }
        }
    }

    // AIDL Implementation
    @Override public boolean isEnabled() { return Settings.System.getInt(mContext.getContentResolver(), KEY_ENABLED, 0) != 0; }
    @Override public void setEnabled(boolean enabled) { 
        Slog.i(TAG, "setEnabled: " + enabled);
        Settings.System.putInt(mContext.getContentResolver(), KEY_ENABLED, enabled ? 1 : 0);
        if (enabled) { 
            if (mPowerManager.isInteractive()) {
                mHandler.sendEmptyMessage(MSG_MONITOR); 
            }
        } else {
            mHandler.removeMessages(MSG_MONITOR);
            NotificationManager nm = getNotificationManager();
            if (nm != null) nm.cancel(NOTIF_ID);
        }
    }
    @Override public boolean isNotificationEnabled() { return Settings.System.getInt(mContext.getContentResolver(), KEY_NOTIF_ENABLED, 0) != 0; }
    @Override public void setNotificationEnabled(boolean enabled) { 
        Settings.System.putInt(mContext.getContentResolver(), KEY_NOTIF_ENABLED, enabled ? 1 : 0);
        if (!enabled) {
            NotificationManager nm = getNotificationManager();
            if (nm != null) nm.cancel(NOTIF_ID);
        }
        else if (isEnabled()) updateNotification();
    }
    @Override public void resetStats() { resetStatsInternal("Manual reset"); }
    @Override public void setAutoResetLevel(int level) { Settings.System.putInt(mContext.getContentResolver(), KEY_AUTO_RESET_LEVEL, level); }
    @Override public void setAutoResetLevelEnabled(boolean enabled) { Settings.System.putInt(mContext.getContentResolver(), KEY_AUTO_RESET_LEVEL_ENABLED, enabled ? 1 : 0); }
    @Override public void setResetOnPlugged(boolean enabled) { Settings.System.putInt(mContext.getContentResolver(), KEY_RESET_ON_PLUGGED, enabled ? 1 : 0); }
    @Override public void setResetOnReboot(boolean enabled) { Settings.System.putInt(mContext.getContentResolver(), KEY_RESET_ON_REBOOT, enabled ? 1 : 0); }
 
    @Override public int getMonitorInterval() { return Settings.System.getInt(mContext.getContentResolver(), KEY_MONITOR_INTERVAL, DEFAULT_MONITOR_INTERVAL); }
    @Override public void setMonitorInterval(int intervalMs) { 
        Settings.System.putInt(mContext.getContentResolver(), KEY_MONITOR_INTERVAL, intervalMs);
        mHandler.removeMessages(MSG_MONITOR);
        if (isEnabled() && mPowerManager.isInteractive()) {
            mHandler.sendEmptyMessage(MSG_MONITOR);
        }
    }

    @Override public void setBatteryAlarmEnabled(boolean enabled) { Settings.System.putInt(mContext.getContentResolver(), KEY_BATTERY_ALARM_ENABLED, enabled ? 1 : 0); }
    @Override public void setBatteryLowThreshold(int threshold) { Settings.System.putInt(mContext.getContentResolver(), KEY_BATTERY_LOW_THRESHOLD, threshold); }
    @Override public void setBatteryHighThreshold(int threshold) { Settings.System.putInt(mContext.getContentResolver(), KEY_BATTERY_HIGH_THRESHOLD, threshold); }
    @Override public void setAlarmFrequency(int frequency) { Settings.System.putInt(mContext.getContentResolver(), KEY_ALARM_FREQUENCY, frequency); }
    @Override public void setFullChargeAlarmEnabled(boolean enabled) { Settings.System.putInt(mContext.getContentResolver(), KEY_FULL_CHARGE_ALARM_ENABLED, enabled ? 1 : 0); }
    @Override public void setBatteryAlarmSound(String uri) { Settings.System.putString(mContext.getContentResolver(), KEY_BATTERY_ALARM_SOUND, uri); }
    @Override public void setBatteryAlarmVibrate(boolean enabled) { Settings.System.putInt(mContext.getContentResolver(), KEY_BATTERY_ALARM_VIBRATE, enabled ? 1 : 0); }

    private boolean isBatteryAlarmEnabled() { return Settings.System.getInt(mContext.getContentResolver(), KEY_BATTERY_ALARM_ENABLED, 0) != 0; }
    private int getBatteryLowThreshold() { return Settings.System.getInt(mContext.getContentResolver(), KEY_BATTERY_LOW_THRESHOLD, 20); }
    private int getBatteryHighThreshold() { return Settings.System.getInt(mContext.getContentResolver(), KEY_BATTERY_HIGH_THRESHOLD, 80); }
    private int getAlarmFrequency() { return Settings.System.getInt(mContext.getContentResolver(), KEY_ALARM_FREQUENCY, 0); }
    private boolean isFullChargeAlarmEnabled() { return Settings.System.getInt(mContext.getContentResolver(), KEY_FULL_CHARGE_ALARM_ENABLED, 0) != 0; }
    private String getBatteryAlarmSound() { return Settings.System.getString(mContext.getContentResolver(), KEY_BATTERY_ALARM_SOUND); }
    private boolean isBatteryAlarmVibrate() { return Settings.System.getInt(mContext.getContentResolver(), KEY_BATTERY_ALARM_VIBRATE, 0) != 0; }

    @Override
    public PowerInsightFlowSample[] getCurrentFlow(int minutes) {
        synchronized (mFlowSamples) {
            if (minutes <= 0) {
                return mFlowSamples.toArray(new PowerInsightFlowSample[0]);
            }
            long now = System.currentTimeMillis();
            long cutoff = now - (minutes * 60000L);
            List<PowerInsightFlowSample> result = new ArrayList<>();
            for (PowerInsightFlowSample s : mFlowSamples) {
                if (s.timestamp >= cutoff) result.add(s);
            }
            return result.toArray(new PowerInsightFlowSample[0]);
        }
    }

    @Override
    public PowerInsightHistoryBucket[] getHistory() {
        synchronized (mHistoryBuckets) {
            return mHistoryBuckets.toArray(new PowerInsightHistoryBucket[0]);
        }
    }

    @Override
    public PowerInsightAppUsage[] getAppUsageSinceLastCharge(int maxEntries) {
        List<PowerInsightAppUsage> result = new ArrayList<>();
        BatteryStatsManager usageManager = mContext.getSystemService(BatteryStatsManager.class);
        if (usageManager == null) return new PowerInsightAppUsage[0];
        try (BatteryUsageStats stats = usageManager.getBatteryUsageStats()) {
            if (stats == null) return new PowerInsightAppUsage[0];
            PackageManager pm = mContext.getPackageManager();
            for (UidBatteryConsumer c : stats.getUidBatteryConsumers()) {
                int uid = c.getUid();
                String[] packages = pm.getPackagesForUid(uid);
                if (packages == null || packages.length == 0) continue;
                String packageName = packages[0];
                if (packageName == null || packageName.startsWith("android.") || "android".equals(packageName)) continue;

                PowerInsightAppUsage app = new PowerInsightAppUsage();
                app.uid = uid;
                app.packageName = packageName;
                app.consumedPowerMah = c.getConsumedPower();
                app.foregroundTimeMs = c.getTimeInStateMs(UidBatteryConsumer.STATE_FOREGROUND);
                app.backgroundTimeMs = c.getTimeInStateMs(UidBatteryConsumer.STATE_BACKGROUND);

                try {
                    ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                    CharSequence label = pm.getApplicationLabel(info);
                    app.appLabel = label != null ? label.toString() : packageName;
                } catch (Exception ignored) {
                    app.appLabel = packageName;
                }
                result.add(app);
            }
            result.sort(Comparator.comparingDouble((PowerInsightAppUsage a) -> a.consumedPowerMah).reversed());
            if (maxEntries > 0 && result.size() > maxEntries) {
                result = new ArrayList<>(result.subList(0, maxEntries));
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to collect app usage stats", t);
        }
        return result.toArray(new PowerInsightAppUsage[0]);
    }

    private int getAutoResetLevel() { return Settings.System.getInt(mContext.getContentResolver(), KEY_AUTO_RESET_LEVEL, 100); }
    private boolean getAutoResetLevelEnabled() { return Settings.System.getInt(mContext.getContentResolver(), KEY_AUTO_RESET_LEVEL_ENABLED, 0) != 0; }
    private boolean getResetOnPlugged() { return Settings.System.getInt(mContext.getContentResolver(), KEY_RESET_ON_PLUGGED, 0) != 0; }
    private boolean getResetOnReboot() { return Settings.System.getInt(mContext.getContentResolver(), KEY_RESET_ON_REBOOT, 0) != 0; }

    private void resetStatsInternal(String reason) {
        Slog.i(TAG, "Resetting stats: " + reason);
        long now = SystemClock.elapsedRealtime();
        mScreenOnTime = 0; mScreenOffTime = 0; mDeepSleepTime = 0;
        mBatteryDrainOn = 0; mBatteryDrainOff = 0;
        mLastScreenToggleTime = now;
        mBootDeepSleepAtLastCheck = getSystemDeepSleepTimeSafe();
        mLastHistoryUpdate = now;
        
        mChargingStartTime = 0;
        mChargingEndTime = 0;
        mChargingStartLevel = -1;
        mChargingEndLevel = -1;
        mChargingStartCapacity = 0;
        mChargingEndCapacity = 0;
        mChargingScreenOnTime = 0;
        mChargingScreenOnLevelCharged = 0;
        mChargingScreenOnMahCharged = 0;
        mChargingScreenOffTime = 0;
        mChargingScreenOffLevelCharged = 0;
        mChargingScreenOffMahCharged = 0;
        mLastChargingScreenToggleTime = now;

        mFlowSamples.clear();
        mHistoryBuckets.clear();
        mMinCurrent = Integer.MAX_VALUE;
        mMaxCurrent = Integer.MIN_VALUE;
        mTotalCurrent = 0;
        mSampleCount = 0;
        mLastDiskSaveTime = System.currentTimeMillis();
        saveStats();
        if (isNotificationEnabled()) updateNotification();
    }

    private int getCurrentCapacityMah() {
        int chargeCounter = readIntFromFile(mBatteryBasePath + "/charge_counter");
        if (chargeCounter == 0) chargeCounter = readIntFromFile(mBatteryBasePath + "/charge_now");
        if (chargeCounter == 0) chargeCounter = readIntFromFile("/sys/class/power_supply/bms/charge_counter");
        if (chargeCounter > 20000) chargeCounter /= 1000;
        if (chargeCounter <= 0) {
            int level = getBatteryLevelInternal();
            int total = getTotalCapacityMah();
            return level * total / 100;
        }
        return chargeCounter;
    }

    private int getTotalCapacityMah() {
        int chargeDesign = readIntFromFile(mBatteryBasePath + "/charge_full_design");
        if (chargeDesign == 0) chargeDesign = readIntFromFile("/sys/class/power_supply/bms/charge_full_design");
        if (chargeDesign > 20000) chargeDesign /= 1000;
        if (chargeDesign > 0) return chargeDesign;

        int capacityFull = readIntFromFile(mBatteryBasePath + "/capacity_full");
        if (capacityFull == 0) capacityFull = readIntFromFile("/sys/class/power_supply/bms/capacity_full");
        if (capacityFull > 20000) capacityFull /= 1000;
        if (capacityFull > 0) return capacityFull;

        return 5000;
    }

    private int getBatteryLevelInternal() {
        Intent batteryStatus = mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryStatus != null) {
            return batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        }
        return mLastBatteryLevel != -1 ? mLastBatteryLevel : 100;
    }

    private void loadStats() {
        mLastDiskSaveTime = System.currentTimeMillis();
        File file = mAtomicFile.getBaseFile();
        if (!file.exists()) return;
        try (FileInputStream fis = mAtomicFile.openRead()) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("current-stats".equals(tag)) {
                        mScreenOnTime = parseLongAttr(parser, "sot", 0L);
                        mScreenOffTime = parseLongAttr(parser, "soft", 0L);
                        mBatteryDrainOn = parseIntAttr(parser, "don", 0);
                        mBatteryDrainOff = parseIntAttr(parser, "doff", 0);
                        mDeepSleepTime = parseLongAttr(parser, "deep_sleep", 0L);
                    } else if ("charging-stats".equals(tag)) {
                        mChargingStartTime = parseLongAttr(parser, "start_time", 0L);
                        mChargingEndTime = parseLongAttr(parser, "end_time", 0L);
                        mChargingStartLevel = parseIntAttr(parser, "start_level", -1);
                        mChargingEndLevel = parseIntAttr(parser, "end_level", -1);
                        mChargingStartCapacity = parseIntAttr(parser, "start_cap", 0);
                        mChargingEndCapacity = parseIntAttr(parser, "end_cap", 0);
                        mChargingScreenOnTime = parseLongAttr(parser, "sot", 0L);
                        mChargingScreenOnLevelCharged = parseIntAttr(parser, "son_lvl", 0);
                        mChargingScreenOnMahCharged = parseIntAttr(parser, "son_mah", 0);
                        mChargingScreenOffTime = parseLongAttr(parser, "soft", 0L);
                        mChargingScreenOffLevelCharged = parseIntAttr(parser, "soff_lvl", 0);
                        mChargingScreenOffMahCharged = parseIntAttr(parser, "soff_mah", 0);
                    } else if ("flow-sample".equals(tag)) {
                        addFlowSample(new PowerInsightFlowSample(
                                parseLongAttr(parser, "t", 0L),
                                parseIntAttr(parser, "c", 0),
                                "1".equals(parser.getAttributeValue(null, "ch"))));
                    } else if ("history-bucket".equals(tag)) {
                        PowerInsightHistoryBucket b = new PowerInsightHistoryBucket(
                                parseIntAttr(parser, "h", 0));
                        b.screenOnMs = parseLongAttr(parser, "s", 0L);
                        b.drainPercent = parseIntAttr(parser, "d", 0);
                        mHistoryBuckets.add(b);
                    }
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Load failed, resetting persisted stats", e);
            resetStatsInternal("Corrupt persisted stats");
        }
    }

    private int parseIntAttr(XmlPullParser parser, String key, int def) {
        String value = parser.getAttributeValue(null, key);
        if (value == null) return def;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            if (DEBUG) Slog.w(TAG, "Invalid int attr " + key + "=" + value);
            return def;
        }
    }

    private long parseLongAttr(XmlPullParser parser, String key, long def) {
        String value = parser.getAttributeValue(null, key);
        if (value == null) return def;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            if (DEBUG) Slog.w(TAG, "Invalid long attr " + key + "=" + value);
            return def;
        }
    }

    private void saveStats() {
        FileOutputStream fos = null;
        try {
            fos = mAtomicFile.startWrite();
            XmlSerializer s = Xml.newSerializer();
            s.setOutput(fos, StandardCharsets.UTF_8.name());
            s.startDocument(null, true);
            s.startTag(null, "power-insight-stats");
            s.startTag(null, "current-stats");
            s.attribute(null, "sot", String.valueOf(mScreenOnTime));
            s.attribute(null, "soft", String.valueOf(mScreenOffTime));
            s.attribute(null, "don", String.valueOf(mBatteryDrainOn));
            s.attribute(null, "doff", String.valueOf(mBatteryDrainOff));
            s.attribute(null, "deep_sleep", String.valueOf(mDeepSleepTime));
            s.endTag(null, "current-stats");

            s.startTag(null, "charging-stats");
            s.attribute(null, "start_time", String.valueOf(mChargingStartTime));
            s.attribute(null, "end_time", String.valueOf(mChargingEndTime));
            s.attribute(null, "start_level", String.valueOf(mChargingStartLevel));
            s.attribute(null, "end_level", String.valueOf(mChargingEndLevel));
            s.attribute(null, "start_cap", String.valueOf(mChargingStartCapacity));
            s.attribute(null, "end_cap", String.valueOf(mChargingEndCapacity));
            s.attribute(null, "sot", String.valueOf(mChargingScreenOnTime));
            s.attribute(null, "son_lvl", String.valueOf(mChargingScreenOnLevelCharged));
            s.attribute(null, "son_mah", String.valueOf(mChargingScreenOnMahCharged));
            s.attribute(null, "soft", String.valueOf(mChargingScreenOffTime));
            s.attribute(null, "soff_lvl", String.valueOf(mChargingScreenOffLevelCharged));
            s.attribute(null, "soff_mah", String.valueOf(mChargingScreenOffMahCharged));
            s.endTag(null, "charging-stats");
            synchronized (mFlowSamples) {
                for (PowerInsightFlowSample f : mFlowSamples) {
                    s.startTag(null, "flow-sample");
                    s.attribute(null, "t", String.valueOf(f.timestamp));
                    s.attribute(null, "c", String.valueOf(f.current));
                    s.attribute(null, "ch", f.isCharging ? "1" : "0");
                    s.endTag(null, "flow-sample");
                }
            }
            synchronized (mHistoryBuckets) {
                for (PowerInsightHistoryBucket b : mHistoryBuckets) {
                    s.startTag(null, "history-bucket");
                    s.attribute(null, "h", String.valueOf(b.hour));
                    s.attribute(null, "s", String.valueOf(b.screenOnMs));
                    s.attribute(null, "d", String.valueOf(b.drainPercent));
                    s.endTag(null, "history-bucket");
                }
            }
            s.endTag(null, "power-insight-stats");
            s.endDocument();
            mAtomicFile.finishWrite(fos);
        } catch (IOException e) { 
            if (fos != null) mAtomicFile.failWrite(fos); 
        }
    }

    private static final int MSG_MONITOR = 1;
    private static final int MSG_INIT = 2;
    private class PowerInsightHandler extends Handler {
        public PowerInsightHandler(Looper looper) { super(looper); }
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_MONITOR:
                    monitor();
                    break;
                case MSG_INIT:
                    loadStats();
                    if (getResetOnReboot()) {
                        resetStatsInternal("Reboot reset");
                    }
                    if (isEnabled() && mPowerManager.isInteractive()) {
                        sendEmptyMessage(MSG_MONITOR);
                    }
                    Slog.i(TAG, "PowerInsight init done. enabled=" + isEnabled() + " notif=" + isNotificationEnabled());
                    break;
            }
        }
    }

    private class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler h) { super(h); }
        @Override public void onChange(boolean selfChange) {
            if (isEnabled()) { 
                if (mPowerManager.isInteractive() && !mHandler.hasMessages(MSG_MONITOR)) {
                    mHandler.sendEmptyMessage(MSG_MONITOR); 
                }
            } else {
                mHandler.removeMessages(MSG_MONITOR);
                NotificationManager nm = getNotificationManager();
                if (nm != null) nm.cancel(NOTIF_ID);
            }
            if (isNotificationEnabled() && isEnabled()) updateNotification();
            else {
                NotificationManager nm = getNotificationManager();
                if (nm != null) nm.cancel(NOTIF_ID);
            }
        }
    }



    private Icon generateNotificationIcon() {
        return Icon.createWithResource(mContext, com.android.internal.R.drawable.ic_power_insight_notify);
    }

}
