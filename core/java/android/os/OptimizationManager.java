/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package android.os;

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;

/**
 * @hide
 */
@SystemService(Context.OPTIMIZATION_SERVICE)
public class OptimizationManager {

    public static final int MODE_QUICK = 0;
    public static final int MODE_DEEP = 1;

    private final IOptimizationEngine mService;

    public OptimizationManager(@NonNull IOptimizationEngine service) {
        mService = service;
    }

    /**  
     @hide 
     */
    @NonNull
    public Bundle getSnapshot() {
        try { return mService.getSnapshot(); }
        catch (RemoteException e) { throw e.rethrowFromSystemServer(); }
    }

    /**
     @hide
     */
    public long startConfigured(@NonNull Bundle options) {
        try { return mService.startConfigured(options); }
        catch (RemoteException e) { throw e.rethrowFromSystemServer(); }
    }

    @NonNull
    public Bundle getMetrics() {
        try {
            return mService.getMetrics();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void refreshMetrics() {
        try {
            mService.refreshMetrics();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void startOptimization(int mode, @NonNull IOptimizationCallback callback) {
        try {
            mService.startOptimization(mode, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void cancelOptimization() {
        try {
            mService.cancelOptimization();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void registerCallback(@NonNull IOptimizationCallback callback) {
        try {
            mService.registerCallback(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void unregisterCallback(@NonNull IOptimizationCallback callback) {
        try {
            mService.unregisterCallback(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
