/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.os;

import com.android.internal.os.PowerInsightStats;
import com.android.internal.os.PowerInsightFlowSample;
import com.android.internal.os.PowerInsightHistoryBucket;
import com.android.internal.os.PowerInsightAppUsage;

/** {@hide} */
interface IPowerInsightService {
    PowerInsightStats getBatteryState();
    PowerInsightFlowSample[] getCurrentFlow(int minutes);
    PowerInsightHistoryBucket[] getHistory();
    PowerInsightAppUsage[] getAppUsageSinceLastCharge(int maxEntries);
    void resetStats();

    boolean isEnabled();
    void setEnabled(boolean enabled);
    
    boolean isNotificationEnabled();
    void setNotificationEnabled(boolean enabled);

    void setAutoResetLevel(int level);
    void setAutoResetLevelEnabled(boolean enabled);
    void setResetOnPlugged(boolean enabled);
    void setResetOnReboot(boolean enabled);
    
    int getMonitorInterval();
    void setMonitorInterval(int intervalMs);

    void setBatteryAlarmEnabled(boolean enabled);
    void setBatteryLowThreshold(int threshold);
    void setBatteryHighThreshold(int threshold);
    void setAlarmFrequency(int frequency);
    void setFullChargeAlarmEnabled(boolean enabled);
    void setBatteryAlarmSound(String uri);
    void setBatteryAlarmVibrate(boolean enabled);
}
