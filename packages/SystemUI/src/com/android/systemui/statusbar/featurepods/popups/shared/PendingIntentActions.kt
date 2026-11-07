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

package com.android.systemui.statusbar.featurepods.popups.shared

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.RemoteException
import android.util.Log
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.policy.KeyguardStateController

fun PendingIntent?.toActivityLaunchAction(
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): (() -> Unit)? {
    return this?.let { pendingIntent ->
        {
            val showOverLockscreen =
                keyguardStateController.isShowing &&
                    activityIntentHelper.wouldPendingShowOverLockscreen(
                        pendingIntent,
                        lockscreenUserManager.currentUserId,
                    )

            if (showOverLockscreen) {
                activityStarter.startPendingIntentMaybeDismissingKeyguard(
                    pendingIntent,
                    null,
                    null,
                )
            } else {
                activityStarter.postStartActivityDismissingKeyguard(pendingIntent, null)
            }
        }
    }
}

fun PendingIntent?.toSendAction(
    context: Context? = null,
    activityStarter: ActivityStarter? = null,
    authenticationRequired: Boolean = false,
): (() -> Unit)? {
    return this?.let { pendingIntent ->
        {
            try {
                ActivityManager.getService().resumeAppSwitches()
            } catch (e: RemoteException) {
                Log.w("DynamicIsland", "Unable to resume app switches", e)
            }

            try {
                when {
                    activityStarter != null && authenticationRequired ->
                        activityStarter.startPendingIntentDismissingKeyguard(pendingIntent)
                    activityStarter != null && pendingIntent.isActivity ->
                        activityStarter.startPendingIntentMaybeDismissingKeyguard(
                            pendingIntent,
                            null,
                            null,
                        )
                    else -> {
                        val options =
                            ActivityOptions.makeBasic().apply {
                                pendingIntentBackgroundActivityStartMode =
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                                pendingIntentLaunchFlags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
                    }
                }
            } catch (e: Exception) {
                Log.w("DynamicIsland", "Unable to deliver pending intent action", e)
            }
        }
    }
}
