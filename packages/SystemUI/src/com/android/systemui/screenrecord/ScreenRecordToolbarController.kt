/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.screenrecord

import android.os.Process
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.mediaprojection.SessionCreationSource
import com.android.systemui.mediaprojection.devicepolicy.ScreenCaptureDisabledDialogDelegate
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureType
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureUiParameters
import com.android.systemui.screencapture.domain.interactor.ScreenCaptureUiInteractor
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import dagger.Lazy
import javax.inject.Inject

@SysUISingleton
class ScreenRecordToolbarController
@Inject
constructor(
    private val activityStarter: ActivityStarter,
    private val userTracker: UserTracker,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val controller: Lazy<ScreenRecordUxController>,
    private val interactor: ScreenCaptureUiInteractor,
    private val disabledDialog: ScreenCaptureDisabledDialogDelegate,
    private val metricsLogger: MediaProjectionMetricsLogger,
) {
    fun show() {
        if (!deviceProvisionedController.isCurrentUserSetup) return
        activityStarter.executeRunnableDismissingKeyguard(
            {
                val recordingController = controller.get()
                when {
                    recordingController.isStarting -> recordingController.cancelCountdown()
                    recordingController.isScreenCaptureDisabled ->
                        disabledDialog.createSysUIDialog().show()
                    else -> {
                        metricsLogger.notifyProjectionInitiated(
                            Process.myUid(),
                            SessionCreationSource.SYSTEM_UI_SCREEN_RECORDER,
                        )
                        interactor.show(
                            ScreenCaptureUiParameters(
                                screenCaptureType = ScreenCaptureType.RECORD,
                                isUserConsentRequired = false,
                                resultReceiver = null,
                                mediaProjection = null,
                                hostAppUserHandle = userTracker.userHandle,
                                hostAppUid = Process.myUid(),
                            )
                        )
                    }
                }
            },
            null,
            true,
            true,
            false,
        )
    }
}
