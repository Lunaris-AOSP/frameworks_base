/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.data.repository

import com.android.systemui.tuner.TunerService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class LockscreenLockIconSettings(val onTop: Boolean, val hidden: Boolean) {
    companion object {
        private const val LOCK_ICON_ON_TOP = "system:ls_lock_icon_top"
        private const val HIDE_LOCK_ICON = "lockscreen_hide_lock_icon"

        fun observe(tunerService: TunerService): Flow<LockscreenLockIconSettings> =
            callbackFlow<LockscreenLockIconSettings> {
                    val callback =
                        object : TunerService.Tunable {
                            override fun onTuningChanged(key: String, newValue: String?) {
                                trySend(
                                    LockscreenLockIconSettings(
                                        onTop = tunerService.getValue(LOCK_ICON_ON_TOP, 0) != 0,
                                        hidden = tunerService.getValue(HIDE_LOCK_ICON, 0) != 0,
                                    )
                                )
                            }
                        }
                    tunerService.addTunable(callback, LOCK_ICON_ON_TOP, HIDE_LOCK_ICON)
                    awaitClose { tunerService.removeTunable(callback) }
                }
                .distinctUntilChanged()
    }
}
