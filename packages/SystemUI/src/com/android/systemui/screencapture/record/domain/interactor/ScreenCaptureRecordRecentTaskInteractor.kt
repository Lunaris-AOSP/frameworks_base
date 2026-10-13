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

import android.os.UserHandle
import com.android.systemui.mediaprojection.devicepolicy.ScreenCaptureDevicePolicyResolver
import com.android.systemui.screencapture.common.ScreenCapture
import com.android.systemui.screencapture.common.ScreenCaptureUiScope
import com.android.systemui.screencapture.common.domain.interactor.ScreenCaptureRecentTaskInteractor
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureUiParameters
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.map

@ScreenCaptureUiScope
class ScreenCaptureRecordRecentTaskInteractor
@Inject
constructor(
    interactor: ScreenCaptureRecentTaskInteractor,
    userTracker: UserTracker,
    policyResolver: Provider<ScreenCaptureDevicePolicyResolver>,
    @ScreenCapture parameters: ScreenCaptureUiParameters,
) {
    val recentTasks =
        interactor.recentTasks.map { tasks ->
            val resolver = policyResolver.get()
            tasks?.filter { task ->
                userTracker.userProfiles.any { it.id == task.userId } &&
                    resolver.isScreenCaptureAllowed(
                        UserHandle.of(task.userId),
                        parameters.hostAppUserHandle,
                    )
            }
        }
}
