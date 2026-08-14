/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.os;

import android.os.Parcel;
import android.os.Parcelable;

public class PowerInsightStats implements Parcelable {
    public int level;
    public boolean isCharging;
    public int temp;
    public int voltage;
    public int currentNow;
    public String health = "Unknown";
    public long screenOnTime;
    public long screenOffTime;
    public long deepSleepTime;
    public float activeDrainRate;
    public float idleDrainRate;
    public int totalCapacity;
    public int currentCapacity;
    public float healthPercent;
    public int cycleCount;
    public float capacityHealth;
    public float cycleHealth;
    public int status;
    public int plugged;
    public long awakeTime;
    public int batteryDrainScreenOn;
    public int batteryDrainScreenOff;
    public int minCurrent;
    public int maxCurrent;
    public int avgCurrent;
    public float powerWatts;
    
    // Settings state
    public boolean isNotificationEnabled;
    public int monitorInterval;
    public boolean isAutoResetLevelEnabled;
    public int autoResetLevel;
    public boolean isResetOnPlugged;
    public boolean isResetOnReboot;
    public boolean isBatteryAlarmEnabled;
    public int batteryLowThreshold;
    public int batteryHighThreshold;
    public int alarmFrequency;
    public boolean isFullChargeAlarmEnabled;
    public String batteryAlarmSound;
    public boolean isBatteryAlarmVibrate;

    // Charging stats
    public long chargingStartTime;
    public long chargingEndTime;
    public long chargingDurationTime;
    public int chargingLevelCharged;
    public int chargingMahCharged;
    public float chargingRatePercentPerHour;
    public long chargingScreenOnTime;
    public int chargingScreenOnLevelCharged;
    public int chargingScreenOnMahCharged;
    public float chargingScreenOnRatePercentPerHour;
    public long chargingScreenOffTime;
    public int chargingScreenOffLevelCharged;
    public int chargingScreenOffMahCharged;
    public float chargingScreenOffRatePercentPerHour;

    public PowerInsightStats() {}

    protected PowerInsightStats(Parcel in) {
        level = in.readInt();
        isCharging = in.readByte() != 0;
        temp = in.readInt();
        voltage = in.readInt();
        currentNow = in.readInt();
        health = in.readString();
        screenOnTime = in.readLong();
        screenOffTime = in.readLong();
        deepSleepTime = in.readLong();
        activeDrainRate = in.readFloat();
        idleDrainRate = in.readFloat();
        totalCapacity = in.readInt();
        currentCapacity = in.readInt();
        healthPercent = in.readFloat();
        cycleCount = in.readInt();
        capacityHealth = in.readFloat();
        cycleHealth = in.readFloat();
        status = in.readInt();
        plugged = in.readInt();
        awakeTime = in.readLong();
        batteryDrainScreenOn = in.readInt();
        batteryDrainScreenOff = in.readInt();
        minCurrent = in.readInt();
        maxCurrent = in.readInt();
        avgCurrent = in.readInt();
        powerWatts = in.readFloat();
        isNotificationEnabled = in.readByte() != 0;
        monitorInterval = in.readInt();
        isAutoResetLevelEnabled = in.readByte() != 0;
        autoResetLevel = in.readInt();
        isResetOnPlugged = in.readByte() != 0;
        isResetOnReboot = in.readByte() != 0;
        isBatteryAlarmEnabled = in.readByte() != 0;
        batteryLowThreshold = in.readInt();
        batteryHighThreshold = in.readInt();
        alarmFrequency = in.readInt();
        isFullChargeAlarmEnabled = in.readByte() != 0;
        batteryAlarmSound = in.readString();
        isBatteryAlarmVibrate = in.readByte() != 0;
        chargingStartTime = in.readLong();
        chargingEndTime = in.readLong();
        chargingDurationTime = in.readLong();
        chargingLevelCharged = in.readInt();
        chargingMahCharged = in.readInt();
        chargingRatePercentPerHour = in.readFloat();
        chargingScreenOnTime = in.readLong();
        chargingScreenOnLevelCharged = in.readInt();
        chargingScreenOnMahCharged = in.readInt();
        chargingScreenOnRatePercentPerHour = in.readFloat();
        chargingScreenOffTime = in.readLong();
        chargingScreenOffLevelCharged = in.readInt();
        chargingScreenOffMahCharged = in.readInt();
        chargingScreenOffRatePercentPerHour = in.readFloat();
    }

    public static final Creator<PowerInsightStats> CREATOR = new Creator<PowerInsightStats>() {
        @Override
        public PowerInsightStats createFromParcel(Parcel in) {
            return new PowerInsightStats(in);
        }

        @Override
        public PowerInsightStats[] newArray(int size) {
            return new PowerInsightStats[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(level);
        dest.writeByte((byte) (isCharging ? 1 : 0));
        dest.writeInt(temp);
        dest.writeInt(voltage);
        dest.writeInt(currentNow);
        dest.writeString(health);
        dest.writeLong(screenOnTime);
        dest.writeLong(screenOffTime);
        dest.writeLong(deepSleepTime);
        dest.writeFloat(activeDrainRate);
        dest.writeFloat(idleDrainRate);
        dest.writeInt(totalCapacity);
        dest.writeInt(currentCapacity);
        dest.writeFloat(healthPercent);
        dest.writeInt(cycleCount);
        dest.writeFloat(capacityHealth);
        dest.writeFloat(cycleHealth);
        dest.writeInt(status);
        dest.writeInt(plugged);
        dest.writeLong(awakeTime);
        dest.writeInt(batteryDrainScreenOn);
        dest.writeInt(batteryDrainScreenOff);
        dest.writeInt(minCurrent);
        dest.writeInt(maxCurrent);
        dest.writeInt(avgCurrent);
        dest.writeFloat(powerWatts);
        dest.writeByte((byte) (isNotificationEnabled ? 1 : 0));
        dest.writeInt(monitorInterval);
        dest.writeByte((byte) (isAutoResetLevelEnabled ? 1 : 0));
        dest.writeInt(autoResetLevel);
        dest.writeByte((byte) (isResetOnPlugged ? 1 : 0));
        dest.writeByte((byte) (isResetOnReboot ? 1 : 0));
        dest.writeByte((byte) (isBatteryAlarmEnabled ? 1 : 0));
        dest.writeInt(batteryLowThreshold);
        dest.writeInt(batteryHighThreshold);
        dest.writeInt(alarmFrequency);
        dest.writeByte((byte) (isFullChargeAlarmEnabled ? 1 : 0));
        dest.writeString(batteryAlarmSound);
        dest.writeByte((byte) (isBatteryAlarmVibrate ? 1 : 0));
        dest.writeLong(chargingStartTime);
        dest.writeLong(chargingEndTime);
        dest.writeLong(chargingDurationTime);
        dest.writeInt(chargingLevelCharged);
        dest.writeInt(chargingMahCharged);
        dest.writeFloat(chargingRatePercentPerHour);
        dest.writeLong(chargingScreenOnTime);
        dest.writeInt(chargingScreenOnLevelCharged);
        dest.writeInt(chargingScreenOnMahCharged);
        dest.writeFloat(chargingScreenOnRatePercentPerHour);
        dest.writeLong(chargingScreenOffTime);
        dest.writeInt(chargingScreenOffLevelCharged);
        dest.writeInt(chargingScreenOffMahCharged);
        dest.writeFloat(chargingScreenOffRatePercentPerHour);
    }
}
