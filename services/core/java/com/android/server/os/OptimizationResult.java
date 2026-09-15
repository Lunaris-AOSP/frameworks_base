/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.os;

import java.util.List;

final class OptimizationResult {
    final String status;
    final String detail;

    private OptimizationResult(String status, String detail) {
        this.status = status;
        this.detail = detail;
    }

    static OptimizationResult success(String detail) {
        return new OptimizationResult("success", detail);
    }

    static OptimizationResult partial(String detail) {
        return new OptimizationResult("partial", detail);
    }

    boolean hasErrors() { return !"success".equals(status); }

    static OptimizationResult compilation(int performed, int skipped, List<String> failures) {
        String detail = performed + " compiled, " + skipped + " already optimized or skipped, "
                + failures.size() + " failed";
        if (failures.isEmpty()) return success(detail);
        int shown = Math.min(5, failures.size());
        detail += ". Failed packages: " + String.join(", ", failures.subList(0, shown));
        if (shown < failures.size()) detail += " (and " + (failures.size() - shown) + " more)";
        detail += ". See ArtService/OptimizationManager logs for failure details.";
        return new OptimizationResult(performed > 0 || skipped > 0 ? "partial" : "failed", detail);
    }
}
