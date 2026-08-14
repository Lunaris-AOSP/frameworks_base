/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.os;

import android.os.Parcel;
import android.os.Parcelable;

public class PowerInsightFlowSample implements Parcelable {
    public long timestamp;
    public int current;
    public boolean isCharging;

    public PowerInsightFlowSample() {}

    public PowerInsightFlowSample(long timestamp, int current, boolean isCharging) {
        this.timestamp = timestamp;
        this.current = current;
        this.isCharging = isCharging;
    }

    protected PowerInsightFlowSample(Parcel in) {
        timestamp = in.readLong();
        current = in.readInt();
        isCharging = in.readByte() != 0;
    }

    public static final Creator<PowerInsightFlowSample> CREATOR = new Creator<PowerInsightFlowSample>() {
        @Override
        public PowerInsightFlowSample createFromParcel(Parcel in) {
            return new PowerInsightFlowSample(in);
        }

        @Override
        public PowerInsightFlowSample[] newArray(int size) {
            return new PowerInsightFlowSample[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(timestamp);
        dest.writeInt(current);
        dest.writeByte((byte) (isCharging ? 1 : 0));
    }
}
