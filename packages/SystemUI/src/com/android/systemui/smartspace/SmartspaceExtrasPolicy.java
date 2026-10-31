/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.smartspace;

final class SmartspaceExtrasPolicy {
    static final int NIGHT = 0;
    static final int MORNING = 1;
    static final int AFTERNOON = 2;
    static final int EVENING = 3;

    static int greetingPeriod(int hour) {
        if (hour >= 5 && hour < 12) return MORNING;
        if (hour >= 12 && hour < 17) return AFTERNOON;
        if (hour >= 17 && hour < 21) return EVENING;
        return NIGHT;
    }

    static int parseBattery(String value) {
        try {
            int level = Integer.parseInt(value);
            return level >= 0 && level <= 100 ? level : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private SmartspaceExtrasPolicy() {}
}
