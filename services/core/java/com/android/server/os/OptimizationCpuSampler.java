/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.os;

final class OptimizationCpuSampler {
    static final class Sample {
        final long ticks;
        final long startTime;

        Sample(long ticks, long startTime) {
            this.ticks = ticks;
            this.startTime = startTime;
        }
    }

    static Sample parseProcessStat(String stat) {
        // comm (field 2) may contain spaces and parentheses. State is field 3.
        int end = stat.lastIndexOf(')');
        if (end < 0) throw new IllegalArgumentException("Invalid process stat");
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        if (fields.length < 20) throw new IllegalArgumentException("Incomplete process stat");
        return new Sample(Long.parseLong(fields[11]) + Long.parseLong(fields[12]),
                Long.parseLong(fields[19]));
    }

    static long parseTotalTicks(String line) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 5 || !"cpu".equals(fields[0])) {
            throw new IllegalArgumentException("Invalid aggregate CPU stat");
        }
        long total = 0;
        // guest and guest_nice are already included in user and nice.
        for (int i = 1; i < Math.min(fields.length, 9); i++) total += Long.parseLong(fields[i]);
        return total;
    }

    static float percent(Sample first, Sample second, long totalDelta, int cores) {
        if (first == null || second == null || first.startTime != second.startTime
                || second.ticks < first.ticks || totalDelta <= 0 || cores <= 0) return 0f;
        return Math.min(100f * cores, (second.ticks - first.ticks) * 100f * cores / totalDelta);
    }
}
