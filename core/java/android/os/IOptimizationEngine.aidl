/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package android.os;

import android.os.Bundle;
import android.os.IOptimizationCallback;

/**
 * @hide
 */
interface IOptimizationEngine {
    Bundle getSnapshot();
    long startConfigured(in Bundle options);
    Bundle getMetrics();
    void refreshMetrics();
    void startOptimization(int mode, IOptimizationCallback callback);
    void cancelOptimization();
    void registerCallback(IOptimizationCallback callback);
    void unregisterCallback(IOptimizationCallback callback);
}
