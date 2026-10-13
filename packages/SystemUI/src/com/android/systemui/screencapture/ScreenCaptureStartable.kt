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

package com.android.systemui.screencapture

import android.content.Context
import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.display.data.repository.FocusedDisplayRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureType
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureUiState
import com.android.systemui.screencapture.domain.interactor.ScreenCaptureComponentInteractor
import com.android.systemui.screencapture.domain.interactor.ScreenCaptureUiInteractor
import com.android.systemui.screencapture.ui.ScreenCaptureUi
import com.android.systemui.settings.UserTracker
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

@SysUISingleton
class ScreenCaptureStartable
@Inject
constructor(
    @Application private val appScope: CoroutineScope,
    private val screenCaptureComponentInteractor: ScreenCaptureComponentInteractor,
    private val screenCaptureUiInteractor: ScreenCaptureUiInteractor,
    private val focusedDisplayRepository: FocusedDisplayRepository,
    private val displayRepository: DisplayRepository,
    private val userTracker: UserTracker,
    private val keyguardInteractor: KeyguardInteractor,
    @Main private val mainExecutor: Executor,
) : CoreStartable {

    private val windows = mutableMapOf<ScreenCaptureType, ScreenCaptureUi>()
    private val windowDisplays = mutableMapOf<ScreenCaptureType, Int>()

    override fun start() {
        userTracker.addCallback(
            object : UserTracker.Callback {
                override fun onUserChanged(newUser: Int, userContext: Context) {
                    dismissRecordingToolbar()
                }
            },
            mainExecutor,
        )
        appScope.launch {
            keyguardInteractor.isKeyguardShowing.collect { showing ->
                if (showing) dismissRecordingToolbar()
            }
        }
        appScope.launch {
            displayRepository.displayRemovalEvent.collect { displayId ->
                windowDisplays
                    .filterValues { it == displayId }
                    .keys
                    .toList()
                    .forEach { type ->
                        screenCaptureUiInteractor.hide(type)
                        windows.remove(type)?.removeWindow()
                        windowDisplays.remove(type)
                    }
            }
        }
        appScope.launch { screenCaptureComponentInteractor.initialize() }
        ScreenCaptureType.entries.forEach { observeUiState(it) }
    }

    private fun dismissRecordingToolbar() {
        screenCaptureUiInteractor.hide(ScreenCaptureType.RECORD)
        windows.remove(ScreenCaptureType.RECORD)?.removeWindow()
        windowDisplays.remove(ScreenCaptureType.RECORD)
    }

    private fun observeUiState(type: ScreenCaptureType) {
        combine(
                screenCaptureUiInteractor.uiState(type),
                screenCaptureComponentInteractor.screenCaptureComponent(type),
            ) { state, screenCaptureComponent ->
                if (state is ScreenCaptureUiState.Visible && screenCaptureComponent != null) {
                    val displayId = focusedDisplayRepository.focusedDisplayId.value
                    val display = displayRepository.getDisplay(displayId)

                    if (display == null) {
                        Log.e("ScreenCapture", "Couldn't find display for id=$displayId")
                        screenCaptureUiInteractor.hide(type)
                    } else {
                        windows.remove(type)?.removeWindow()
                        try {
                            val window =
                                screenCaptureComponent
                                    .screenCaptureUiFactory()
                                    .create(
                                        type = state.parameters.screenCaptureType,
                                        display = display,
                                    )
                            windows[type] = window
                            window.onRemoved = {
                                if (windows[type] === window) {
                                    windows.remove(type)
                                    windowDisplays.remove(type)
                                }
                            }
                            windowDisplays[type] = displayId
                            window.attachWindow()
                        } catch (e: android.view.WindowManager.InvalidDisplayException) {
                            Log.w("ScreenCapture", "Display removed while opening capture UI", e)
                            windows.remove(type)?.removeWindow()
                            windowDisplays.remove(type)
                            screenCaptureUiInteractor.hide(type)
                        }
                    }
                }
            }
            .launchIn(appScope)
    }
}
