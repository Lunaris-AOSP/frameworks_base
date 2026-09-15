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
    final List<String> cachePackages;
    final boolean continueOnError;
    private OptimizationPlan(List<String> actions, String filter, List<String> packages, boolean keepGoing) {
        this.actions = java.util.Collections.unmodifiableList(actions);
        this.filter = filter; cachePackages = java.util.Collections.unmodifiableList(new ArrayList<>(packages)); continueOnError = keepGoing;
    }
    static OptimizationPlan parse(Bundle options) {
        if (options == null) throw new IllegalArgumentException("Missing plan");
        for (String key : options.keySet()) {
            if (!IDS.contains(key) && !Arrays.asList("filter", "cache_packages", "continue_on_error").contains(key)) {
                throw new IllegalArgumentException("Unknown option: " + key);
            }
        }
        List<String> actions = new ArrayList<>();
        for (String id : IDS) if (options.getBoolean(id, false)) actions.add(id);
        if (actions.isEmpty()) throw new IllegalArgumentException("Select at least one action");
        String filter = options.getString("filter", "speed-profile");
        if (!"speed-profile".equals(filter) && !"speed".equals(filter) && !"adaptive".equals(filter)) throw new IllegalArgumentException("Invalid compiler filter");
        String[] requested = options.getStringArray("cache_packages");
        List<String> packages = OptimizationTools.validatePackages(requested);
        if (actions.contains("cache") && packages.isEmpty()) {
            throw new IllegalArgumentException("Select apps for cache cleaning first");
        }
        return new OptimizationPlan(actions, filter, packages,
            options.getBoolean("continue_on_error", true));
    }
}
