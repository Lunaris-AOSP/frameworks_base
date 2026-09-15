/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.os;

final class UsageDexoptPolicy {
    static String select(boolean known, long foregroundMs, long lastUsed, long now) {
        if (!known) return "speed-profile";
        long age = Math.max(0, now - lastUsed);
        if (foregroundMs >= 60L * 60 * 1000 && age <= 7L * 86400000) return "speed";
        if (foregroundMs > 0 && age <= 7L * 86400000) return "speed-profile";
        return "verify";
    }
}
