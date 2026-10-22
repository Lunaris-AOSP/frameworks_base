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

package com.android.systemui.screencapture.record.smallscreen.ui.viewmodel

import android.app.ActivityOptions
import android.app.ActivityOptions.LaunchCookie
import android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.UserHandle
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget
import com.android.systemui.mediaprojection.devicepolicy.ScreenCaptureDevicePolicyResolver
import com.android.systemui.res.R
import com.android.systemui.screencapture.common.ScreenCapture
import com.android.systemui.screencapture.common.ScreenCaptureUiScope
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureTarget
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureType
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureUiParameters
import com.android.systemui.screencapture.common.ui.viewmodel.DrawableLoaderViewModel
import com.android.systemui.screencapture.common.ui.viewmodel.DrawableLoaderViewModelImpl
import com.android.systemui.screencapture.domain.interactor.ScreenCaptureUiInteractor
import com.android.systemui.screencapture.record.domain.interactor.ScreenCaptureRecordingInteractor
import com.android.systemui.screencapture.record.ui.viewmodel.ScreenCaptureRecordParametersViewModel
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.domain.ScreenRecordingOptions
import com.android.systemui.screenrecord.domain.ScreenRecordingParameters
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.system.ActivityManagerWrapper
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class SmallScreenCaptureRecordViewModel
@AssistedInject
constructor(
    private val recordingInteractor: ScreenCaptureRecordingInteractor,
    recordDetailsAppSelectorViewModelFactory: RecordDetailsAppSelectorViewModel.Factory,
    screenCaptureRecordParametersViewModelFactory: ScreenCaptureRecordParametersViewModel.Factory,
    recordDetailsTargetViewModelFactory: RecordDetailsTargetViewModel.Factory,
    drawableLoaderViewModel: DrawableLoaderViewModelImpl,
    private val screenCaptureUiInteractor: ScreenCaptureUiInteractor,
    private val activityManager: ActivityManagerWrapper,
    private val displayManager: DisplayManager,
    private val context: Context,
    private val userTracker: UserTracker,
    private val policyResolver: Provider<ScreenCaptureDevicePolicyResolver>,
    @ScreenCapture private val uiParameters: ScreenCaptureUiParameters,
    @Background private val backgroundContext: CoroutineContext,
) : HydratedActivatable(), DrawableLoaderViewModel by drawableLoaderViewModel {
    val recordDetailsAppSelectorViewModel = recordDetailsAppSelectorViewModelFactory.create()
    val recordDetailsParametersViewModel = screenCaptureRecordParametersViewModelFactory.create()
    val recordDetailsTargetViewModel = recordDetailsTargetViewModelFactory.create()

    val recordingState by
        recordingInteractor.state.hydratedStateOf("SmallScreenCaptureRecordViewModel#state")
    val isRecording: Boolean
        get() = recordingState is ScreenRecordModel.Recording

    val isStarting: Boolean
        get() = recordingState is ScreenRecordModel.Starting

    var isProcessing by mutableStateOf(false)
        private set

    var detailsPopup by mutableStateOf(RecordDetailsPopupType.Settings)
        private set

    var shouldShowDetails by mutableStateOf(!recordingInteractor.isBusy)
        private set

    val shouldShowSettingsButton: Boolean
        get() = isRecording

    val canStart: Boolean
        get() =
            recordDetailsTargetViewModel.currentTarget?.screenCaptureTarget != null &&
                recordDetailsParametersViewModel.capabilitiesLoaded &&
                !isProcessing

    override suspend fun onActivated() {
        coroutineScope {
            launchTraced("ScreenRecord#apps") { recordDetailsAppSelectorViewModel.activate() }
            launchTraced("ScreenRecord#options") { recordDetailsParametersViewModel.activate() }
            launchTraced("ScreenRecord#target") { recordDetailsTargetViewModel.activate() }
            launchTraced("ScreenRecord#state") {
                var wasBusy = recordingInteractor.isBusy
                recordingInteractor.state.collect {
                    val busy = it !is ScreenRecordModel.DoingNothing
                    if (wasBusy && !busy) dismiss()
                    if (busy) shouldShowDetails = false
                    wasBusy = busy
                }
            }
        }
    }

    fun showSettings() {
        detailsPopup = RecordDetailsPopupType.Settings
    }

    fun showAppSelector() {
        detailsPopup = RecordDetailsPopupType.AppSelector
    }

    fun shouldShowSettings(visible: Boolean) {
        shouldShowDetails = visible
        if (visible) showSettings()
    }

    fun dismiss() {
        screenCaptureUiInteractor.hide(ScreenCaptureType.RECORD)
    }

    suspend fun onPrimaryButtonTapped() {
        if (isProcessing) return
        if (recordingInteractor.isBusy) {
            recordingInteractor.stop()
            dismiss()
            return
        }
        if (!canStart) return
        isProcessing = true
        try {
            if (startRecording()) dismiss()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ScreenRecordToolbar", "Unable to start recording", e)
            Toast.makeText(context, R.string.screenrecord_start_error, Toast.LENGTH_LONG).show()
        } finally {
            isProcessing = false
        }
    }

    private suspend fun startRecording(): Boolean {
        val user = uiParameters.hostAppUserHandle
        if (userTracker.userId != user.identifier) return false
        val targetItem = recordDetailsTargetViewModel.currentTarget ?: return false
        val target = targetItem.screenCaptureTarget ?: return false
        var options = recordDetailsParametersViewModel.options.copy(target = target)
        var captureTarget: MediaProjectionCaptureTarget? = null
        val displayId =
            when (target) {
                is ScreenCaptureTarget.Fullscreen -> target.displayId
                is ScreenCaptureTarget.App -> {
                    val task = (targetItem as RecordDetailsTargetItemViewModel.SingleApp).task
                    check(
                        policyResolver
                            .get()
                            .isScreenCaptureAllowed(UserHandle.of(task.userId), user)
                    )
                    val cookie = LaunchCookie("screen_record")
                    withContext(backgroundContext) {
                        check(
                            activityManager.startActivityFromRecents(
                                target.taskId,
                                ActivityOptions.makeBasic().apply {
                                    pendingIntentBackgroundActivityStartMode =
                                        MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                                    setLaunchCookie(cookie)
                                },
                            )
                        )
                    }
                    captureTarget = MediaProjectionCaptureTarget(cookie, target.taskId)
                    withContext(backgroundContext) {
                        activityManager
                            .getRunningTasks(false)
                            .firstOrNull { it.taskId == target.taskId }
                            ?.displayId
                            ?: target.displayId.takeUnless { it == Display.INVALID_DISPLAY }
                            ?: Display.DEFAULT_DISPLAY
                    }
                }
                else -> return false
            }
        val display = displayManager.getDisplay(displayId)
        check(display?.isValid == true)
        options =
            options.copy(
                resolutionMode =
                    options.resolutionMode.takeIf {
                        it in ScreenRecordingOptions.resolutions(display)
                    } ?: 0,
                fpsMode =
                    options.fpsMode.takeIf {
                        it in ScreenRecordingOptions.frameRates(context, display)
                    } ?: 0,
            )
        recordDetailsParametersViewModel.updateOptions { options }
        return recordingInteractor.start(
            ScreenRecordingParameters(
                captureTarget = captureTarget,
                audioSource = options.audioSource,
                displayId = displayId,
                shouldShowTaps = captureTarget == null && options.shouldShowTaps,
                shouldShowFrontCamera = captureTarget == null && options.shouldShowFrontCamera,
                lowQuality = options.lowQuality,
                longerDuration = options.longerDuration,
                hevc = options.hevc && recordDetailsParametersViewModel.hasHevcEncoder,
                resolutionMode = options.resolutionMode,
                fpsMode = options.fpsMode,
                timeLimitMs = options.timeLimitMs,
                fileSizeBytes = options.fileSizeBytes,
                bitrateMultiplier = options.bitrateMultiplier,
            ),
            options.skipTimer,
            user.identifier,
            options,
        )
    }

    @AssistedFactory
    @ScreenCaptureUiScope
    interface Factory {
        fun create(): SmallScreenCaptureRecordViewModel
    }
}
