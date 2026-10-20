/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.featurepods.popups.data.repository

import android.app.Notification
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.view.Display
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DynamicIslandState(
    val canShowCalls: Boolean = false,
    val callNotificationKeys: Set<String> = emptySet(),
    val isScreenRecordingShown: Boolean = false,
)

@SysUISingleton
class DynamicIslandStateRepository
@Inject
constructor(private val lockscreenUserManager: NotificationLockscreenUserManager) {
    private val mutableStates = MutableStateFlow<Map<Int, DynamicIslandState>>(emptyMap())
    val states = mutableStates.asStateFlow()

    fun setState(displayId: Int, state: DynamicIslandState) {
        mutableStates.update { it + (displayId to state) }
    }

    fun removeDisplay(displayId: Int) {
        mutableStates.update { it - displayId }
    }

    fun shouldSuppressCallHeadsUp(sbn: StatusBarNotification): Boolean {
        val state = states.value[Display.DEFAULT_DISPLAY] ?: return false
        if (!state.canShowCalls) return false
        if (sbn.userId != UserHandle.USER_ALL && !lockscreenUserManager.isCurrentProfile(sbn.userId)) {
            return false
        }

        val notification = sbn.notification
        val extras = notification.extras
        return notification.category == Notification.CATEGORY_CALL &&
            (extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
                extras.containsKey(Notification.EXTRA_DECLINE_INTENT) ||
                extras.containsKey(Notification.EXTRA_HANG_UP_INTENT))
    }
}
