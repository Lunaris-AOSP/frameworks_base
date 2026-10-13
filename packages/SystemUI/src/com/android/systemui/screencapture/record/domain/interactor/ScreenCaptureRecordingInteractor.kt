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

package com.android.systemui.screencapture.record.domain.interactor

import android.media.projection.StopReason
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.screencapture.record.shared.model.ScreenCaptureRecordParametersModel
import com.android.systemui.screenrecord.ScreenRecordUxController
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.ScreenRecordRepository
import com.android.systemui.screenrecord.domain.ScreenRecordingParameters
import com.android.systemui.screenrecord.domain.interactor.LegacyScreenRecordingStartStopInteractor
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class ScreenCaptureRecordingInteractor
@Inject
constructor(
    @Application scope: CoroutineScope,
    repository: ScreenRecordRepository,
    private val controller: ScreenRecordUxController,
    private val recorder: LegacyScreenRecordingStartStopInteractor,
    private val userTracker: UserTracker,
) {
    val state =
        repository.screenRecordState.stateIn(
            scope,
            SharingStarted.Eagerly,
            when {
                controller.isStarting -> ScreenRecordModel.Starting(0)
                controller.isRecording -> ScreenRecordModel.Recording
                else -> ScreenRecordModel.DoingNothing
            },
        )

    private var savedOptions: ScreenCaptureRecordParametersModel? = null
    val activeOptions: ScreenCaptureRecordParametersModel?
        get() = savedOptions.takeIf { isBusy }

    val isBusy: Boolean
        get() = controller.isStarting || controller.isRecording

    fun start(
        parameters: ScreenRecordingParameters,
        skipTimer: Boolean,
        userId: Int,
        options: ScreenCaptureRecordParametersModel,
    ): Boolean {
        if (isBusy || userTracker.userId != userId || controller.isScreenCaptureDisabled)
            return false
        savedOptions = options
        controller.startCountdown(
            if (skipTimer) 100L else 3000L,
            if (skipTimer) 100L else 1000L,
            {
                if (userTracker.userId == userId && !controller.isScreenCaptureDisabled) {
                    recorder.startRecording(parameters)
                } else {
                    controller.updateState(false)
                }
            },
            { recorder.stopRecording(controller.stopReason) },
        )
        return true
    }

    fun stop() {
        if (controller.isStarting) {
            controller.cancelCountdown()
        } else if (controller.isRecording) {
            controller.stopRecording(StopReason.STOP_HOST_APP)
        }
    }
}
