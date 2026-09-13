/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.os;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class OptimizationPlan {
    static final List<String> IDS = Arrays.asList("cache", "trim", "zram", "dexopt");
    final List<String> actions;
    final String filter;
    final long cacheTargetBytes;
    final boolean continueOnError;
    private OptimizationPlan(List<String> actions, String filter, long bytes, boolean keepGoing) {
        this.actions = java.util.Collections.unmodifiableList(actions);
        this.filter = filter; cacheTargetBytes = bytes; continueOnError = keepGoing;
    }
    static OptimizationPlan parse(Bundle options) {
        if (options == null) throw new IllegalArgumentException("Missing plan");
        for (String key : options.keySet()) {
            if (!IDS.contains(key) && !Arrays.asList("filter", "cache_target_gib", "continue_on_error").contains(key)) {
                throw new IllegalArgumentException("Unknown option: " + key);
            }
        }
        List<String> actions = new ArrayList<>();
        for (String id : IDS) if (options.getBoolean(id, false)) actions.add(id);
        if (actions.isEmpty()) throw new IllegalArgumentException("Select at least one action");
        String filter = options.getString("filter", "speed-profile");
        if (!"speed-profile".equals(filter) && !"speed".equals(filter) && !"adaptive".equals(filter)) throw new IllegalArgumentException("Invalid compiler filter");
        int target = options.getInt("cache_target_gib", 4);
        if (target < 1 || target > 16) throw new IllegalArgumentException("Cache target must be 1–16 GiB");
        return new OptimizationPlan(actions, filter, target * 1024L * 1024 * 1024,
            options.getBoolean("continue_on_error", true));
    }
}
