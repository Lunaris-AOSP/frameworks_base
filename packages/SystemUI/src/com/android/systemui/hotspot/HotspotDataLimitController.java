/*
 * Copyright (C) 2026 Project ASCP
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

package com.android.systemui.hotspot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.TetheringManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;

import javax.inject.Inject;

/**
 * Monitors Wi-Fi hotspot data usage and enforces the "One-time data limit"
 * configured in Settings > Hotspot > One-time data limit.
 *
 * Behavior mirrors the HyperOS-style feature:
 *  - Toggle:        Settings.Global "hotspot_data_limit_enabled" (0/1)
 *  - Limit size:    Settings.Global "hotspot_data_limit_bytes" (long)
 *  - On exceeded:   Settings.Global "hotspot_data_limit_action"
 *                      0 = Turn off and notify
 *                      1 = Notify only
 */
@SysUISingleton
public class HotspotDataLimitController implements CoreStartable {

    private static final String TAG = "HotspotDataLimit";
    private static final long CHECK_INTERVAL_MS = 15_000; // 15 seconds
    private static final String CHANNEL_ID = "hotspot_data_limit";
    private static final int NOTIF_ID = 7001;

    private static final String KEY_ENABLED = "hotspot_data_limit_enabled";
    private static final String KEY_LIMIT_BYTES = "hotspot_data_limit_bytes";
    private static final String KEY_ACTION = "hotspot_data_limit_action";
    private static final int ACTION_TURN_OFF_NOTIFY = 0;
    private static final int ACTION_NOTIFY_ONLY = 1;

    private final Context mContext;
    private final Handler mHandler;
    private final NotificationManager mNotificationManager;
    private final NetworkStatsManager mNetworkStatsManager;
    private final TetheringManager mTetheringManager;

    private boolean mHotspotActive = false;
    private long mBaselineBytes = -1;
    private boolean mLimitNotifiedThisSession = false;

    private final Runnable mCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkDataUsage();
            if (mHotspotActive) {
                mHandler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WifiManager.WIFI_AP_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_AP_STATE,
                        WifiManager.WIFI_AP_STATE_DISABLED);
                if (state == WifiManager.WIFI_AP_STATE_ENABLED) {
                    onHotspotEnabled();
                } else if (state == WifiManager.WIFI_AP_STATE_DISABLED) {
                    onHotspotDisabled();
                }
            }
        }
    };

    @Inject
    public HotspotDataLimitController(Context context, @Background Handler bgHandler) {
        mContext = context;
        mHandler = bgHandler;
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mNetworkStatsManager = context.getSystemService(NetworkStatsManager.class);
        mTetheringManager = context.getSystemService(TetheringManager.class);
    }

    @Override
    public void start() {
        IntentFilter filter = new IntentFilter(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        mContext.registerReceiver(mReceiver, filter);
        createNotificationChannel();
        Log.d(TAG, "HotspotDataLimitController started");
    }

    private void onHotspotEnabled() {
        if (!isFeatureEnabled()) return;
        mHotspotActive = true;
        mLimitNotifiedThisSession = false;
        mBaselineBytes = getCurrentTetherBytes();
        mHandler.postDelayed(mCheckRunnable, CHECK_INTERVAL_MS);
        Log.d(TAG, "Hotspot enabled, baseline=" + mBaselineBytes);
    }

    private void onHotspotDisabled() {
        mHotspotActive = false;
        mBaselineBytes = -1;
        mLimitNotifiedThisSession = false;
        mHandler.removeCallbacks(mCheckRunnable);
    }

    private void checkDataUsage() {
        if (!isFeatureEnabled() || mLimitNotifiedThisSession) return;

        long limitBytes = getLimitBytes();
        if (limitBytes <= 0) return;

        long currentBytes = getCurrentTetherBytes();
        if (mBaselineBytes < 0) {
            mBaselineBytes = currentBytes;
            return;
        }

        long usedSinceEnabled = currentBytes - mBaselineBytes;
        Log.d(TAG, "Used since hotspot enabled: " + usedSinceEnabled + " / limit " + limitBytes);

        if (usedSinceEnabled >= limitBytes) {
            int action = Settings.Global.getInt(
                    mContext.getContentResolver(), KEY_ACTION, ACTION_TURN_OFF_NOTIFY);
            if (action == ACTION_TURN_OFF_NOTIFY) {
                disableHotspot();
            }
            showLimitReachedNotification(action == ACTION_TURN_OFF_NOTIFY);
            // Avoid repeat notifications until hotspot is toggled again.
            mLimitNotifiedThisSession = true;
        }
    }

    /**
     * Queries bytes (rx+tx) attributed to the Wi-Fi AP tethering interface since device boot.
     * NOTE: querySummaryForDevice gives a device-wide WiFi total, not strictly AP-only traffic,
     * on some AOSP versions. If your ROM's NetworkStatsManager supports tag-based tethering
     * stats (TAG_SYSTEM), consider switching to that for stricter accuracy.
     */
    private long getCurrentTetherBytes() {
        try {
            NetworkStats.Bucket bucket = mNetworkStatsManager.querySummaryForDevice(
                    ConnectivityManager.TYPE_WIFI,
                    null /* subscriberId, unused for WiFi */,
                    0,
                    System.currentTimeMillis());
            return bucket != null ? (bucket.getRxBytes() + bucket.getTxBytes()) : 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to query tether stats", e);
            return 0;
        }
    }

    private void disableHotspot() {
        try {
            mTetheringManager.stopTethering(TetheringManager.TETHERING_WIFI);
        } catch (Exception e) {
            Log.e(TAG, "Failed to disable hotspot", e);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Hotspot Data Limit", NotificationManager.IMPORTANCE_HIGH);
        mNotificationManager.createNotificationChannel(channel);
    }

    private void showLimitReachedNotification(boolean hotspotWasTurnedOff) {
        String text = hotspotWasTurnedOff
                ? "Your data limit was reached. Hotspot has been turned off."
                : "Your data limit was reached.";
        Notification notif = new Notification.Builder(mContext, CHANNEL_ID)
                .setContentTitle("Hotspot data limit reached")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .build();
        mNotificationManager.notify(NOTIF_ID, notif);
    }

    private boolean isFeatureEnabled() {
        return Settings.Global.getInt(
                mContext.getContentResolver(), KEY_ENABLED, 0) == 1;
    }

    private long getLimitBytes() {
        return Settings.Global.getLong(
                mContext.getContentResolver(), KEY_LIMIT_BYTES, 0L);
    }
}
