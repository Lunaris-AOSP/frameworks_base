/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package android.os;

import android.os.Bundle;

/**
 * @hide
 */
oneway interface IOptimizationCallback {
    void onLog(String line, int kind);
    void onProgress(float progress);
    void onStateChanged(boolean isOptimizing);
    void onMetricsUpdated(in Bundle metrics);
}
