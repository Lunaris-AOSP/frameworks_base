/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.os;

import android.os.Parcel;
import android.os.Parcelable;

public class PowerInsightAppUsage implements Parcelable {
    public int uid;
    public String packageName;
    public String appLabel;
    public double consumedPowerMah;
    public long foregroundTimeMs;
    public long backgroundTimeMs;

    public PowerInsightAppUsage() {}

    protected PowerInsightAppUsage(Parcel in) {
        uid = in.readInt();
        packageName = in.readString();
        appLabel = in.readString();
        consumedPowerMah = in.readDouble();
        foregroundTimeMs = in.readLong();
        backgroundTimeMs = in.readLong();
    }

    public static final Creator<PowerInsightAppUsage> CREATOR = new Creator<PowerInsightAppUsage>() {
        @Override
        public PowerInsightAppUsage createFromParcel(Parcel in) {
            return new PowerInsightAppUsage(in);
        }

        @Override
        public PowerInsightAppUsage[] newArray(int size) {
            return new PowerInsightAppUsage[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(uid);
        dest.writeString(packageName);
        dest.writeString(appLabel);
        dest.writeDouble(consumedPowerMah);
        dest.writeLong(foregroundTimeMs);
        dest.writeLong(backgroundTimeMs);
    }
}
