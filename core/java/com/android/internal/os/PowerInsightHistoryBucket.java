/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.os;

import android.os.Parcel;
import android.os.Parcelable;

public class PowerInsightHistoryBucket implements Parcelable {
    public int hour;
    public long screenOnMs;
    public int drainPercent;

    public PowerInsightHistoryBucket() {}

    public PowerInsightHistoryBucket(int hour) {
        this.hour = hour;
    }

    protected PowerInsightHistoryBucket(Parcel in) {
        hour = in.readInt();
        screenOnMs = in.readLong();
        drainPercent = in.readInt();
    }

    public static final Creator<PowerInsightHistoryBucket> CREATOR = new Creator<PowerInsightHistoryBucket>() {
        @Override
        public PowerInsightHistoryBucket createFromParcel(Parcel in) {
            return new PowerInsightHistoryBucket(in);
        }

        @Override
        public PowerInsightHistoryBucket[] newArray(int size) {
            return new PowerInsightHistoryBucket[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(hour);
        dest.writeLong(screenOnMs);
        dest.writeInt(drainPercent);
    }
}
