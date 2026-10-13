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

import android.hardware.display.DisplayManager
import android.os.Handler
import android.view.Display
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.mediaprojection.permission.MediaProjectionPermissionUtils
import com.android.systemui.res.R
import com.android.systemui.screencapture.common.ScreenCaptureUi
import com.android.systemui.screencapture.common.domain.interactor.ScreenCaptureLabelInteractor
import com.android.systemui.screencapture.common.domain.model.ScreenCaptureRecentTask
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureTarget
import com.android.systemui.screencapture.record.domain.interactor.ScreenCaptureRecordParametersInteractor
import com.android.systemui.screencapture.record.domain.interactor.ScreenCaptureRecordRecentTaskInteractor
import com.android.systemui.screencapture.record.domain.interactor.ScreenCaptureRecordingInteractor
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

sealed interface RecordDetailsTargetItemViewModel {

    val labelRes: Int
    val isSelectable: Boolean
    val screenCaptureTarget: ScreenCaptureTarget?

    data class EntireScreen(
        override val screenCaptureTarget: ScreenCaptureTarget,
        val displayName: String? = null,
    ) : RecordDetailsTargetItemViewModel {

        constructor(display: Display) : this(ScreenCaptureTarget.Fullscreen(display.displayId))

        override val labelRes: Int = R.string.screen_record_entire_screen
        override val isSelectable: Boolean = true
    }

    data class SingleApp(val task: ScreenCaptureRecentTask) : RecordDetailsTargetItemViewModel {

        override val screenCaptureTarget: ScreenCaptureTarget =
            ScreenCaptureTarget.App(displayId = task.displayId, taskId = task.taskId)

        override val labelRes: Int = R.string.screen_record_single_app
        override val isSelectable: Boolean = true
    }

    data class CapturedApp(override val screenCaptureTarget: ScreenCaptureTarget.App) :
        RecordDetailsTargetItemViewModel {
        override val labelRes = R.string.screen_record_single_app
        override val isSelectable = false
    }

    data object SingleAppNoRecents : RecordDetailsTargetItemViewModel {

        override val labelRes: Int = R.string.screen_record_single_app_no_recents
        override val isSelectable: Boolean = false
        override val screenCaptureTarget: ScreenCaptureTarget? = null
    }
}

class RecordDetailsTargetViewModel
@AssistedInject
constructor(
    @ScreenCaptureUi private val display: Display,
    private val recordingInteractor: ScreenCaptureRecordingInteractor,
    private val displayManager: DisplayManager,
    @Main private val handler: Handler,
    private val parametersInteractor: ScreenCaptureRecordParametersInteractor,
    private val screenCaptureRecentTaskInteractor: ScreenCaptureRecordRecentTaskInteractor,
    private val labelInteractor: ScreenCaptureLabelInteractor,
) : HydratedActivatable() {

    private val _selectedIndex = MutableStateFlow(0)
    private val selectedDisplayId = MutableStateFlow<Int?>(null)
    private val selectedTask = MutableStateFlow<ScreenCaptureRecentTask?>(null)
    private val _items: MutableStateFlow<List<RecordDetailsTargetItemViewModel>?> =
        MutableStateFlow(null)
    private val _currentTarget = MutableStateFlow<RecordDetailsTargetItemViewModel?>(null)

    val canChangeTarget: Boolean by
        recordingInteractor.state
            .map { it is ScreenRecordModel.DoingNothing }
            .hydratedStateOf(
                traceName = "RecordDetailsTargetViewModel#canChangeTarget",
                initialValue = !recordingInteractor.isBusy,
            )
    val currentTarget: RecordDetailsTargetItemViewModel? by
        _currentTarget.hydratedStateOf(traceName = "RecordDetailsTargetViewModel#currentTarget")
    val items: List<RecordDetailsTargetItemViewModel>? by
        _items.hydratedStateOf(traceName = "RecordDetailsTargetViewModel#items")
    private val selectedIndexFlow =
        combine(_selectedIndex, _items, selectedDisplayId) { requested, targets, displayId ->
            val index =
                if (displayId == null) requested
                else
                    targets?.indexOfFirst {
                        (it.screenCaptureTarget as? ScreenCaptureTarget.Fullscreen)?.displayId ==
                            displayId
                    } ?: -1
            if (targets?.getOrNull(index)?.isSelectable == true) index
            else targets?.indexOfFirst { it is RecordDetailsTargetItemViewModel.EntireScreen } ?: 0
        }
    val selectedIndex: Int by
        selectedIndexFlow.hydratedStateOf("RecordDetailsTargetViewModel#selectedIndex", 0)
    val shouldShowAppSelector: Boolean by derivedStateOf {
        items?.getOrNull(selectedIndex) is RecordDetailsTargetItemViewModel.SingleApp
    }
    var selectedAppName: Result<CharSequence>? by mutableStateOf(null)
        private set

    override suspend fun onActivated() {
        coroutineScope {
            launchTraced("RecordDetailsTargetViewModel#_currentTarget") {
                combine(_items, selectedIndexFlow) { targets, idx ->
                        when (val target = recordingInteractor.activeOptions?.target) {
                            is ScreenCaptureTarget.Fullscreen ->
                                RecordDetailsTargetItemViewModel.EntireScreen(target)
                            is ScreenCaptureTarget.App ->
                                RecordDetailsTargetItemViewModel.CapturedApp(target)
                            else ->
                                targets?.getOrNull(idx)?.takeIf { it.isSelectable }
                                    ?: targets?.firstOrNull {
                                        it is RecordDetailsTargetItemViewModel.EntireScreen
                                    }
                        }
                    }
                    .onEach {
                        it?.screenCaptureTarget?.let { target ->
                            parametersInteractor.setRecordTarget(target)
                        }
                        val appViewModel = it as? RecordDetailsTargetItemViewModel.SingleApp
                        selectedAppName =
                            appViewModel?.task?.let { task -> labelInteractor.loadLabel(task) }
                    }
                    .collect(_currentTarget)
            }
            launchTraced("RecordDetailsTargetViewModel#_items") {
                combine(
                        screenCaptureRecentTaskInteractor.recentTasks,
                        selectedTask,
                        displayChanges(),
                    ) { tasks, selected, _ ->
                        val currentTask =
                            if (selected == null) tasks?.firstOrNull()
                            else tasks?.firstOrNull { it.taskId == selected.taskId }
                        if (selected == null && currentTask != null)
                            selectedTask.value = currentTask
                        buildList {
                            add(
                                currentTask?.let { RecordDetailsTargetItemViewModel.SingleApp(it) }
                                    ?: RecordDetailsTargetItemViewModel.SingleAppNoRecents
                            )
                            add(
                                RecordDetailsTargetItemViewModel.EntireScreen(
                                    displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: display
                                )
                            )
                            MediaProjectionPermissionUtils.getConnectedDisplays(displayManager)
                                .forEach {
                                    add(
                                        RecordDetailsTargetItemViewModel.EntireScreen(
                                            ScreenCaptureTarget.Fullscreen(it.displayId),
                                            it.name,
                                        )
                                    )
                                }
                        }
                    }
                    .collect(_items)
            }
        }
    }

    fun select(index: Int) {
        if (!recordingInteractor.isBusy && _items.value?.getOrNull(index)?.isSelectable == true) {
            selectedDisplayId.value =
                (_items.value?.getOrNull(index)?.screenCaptureTarget
                        as? ScreenCaptureTarget.Fullscreen)
                    ?.displayId
            _selectedIndex.value = index
        }
    }

    fun selectTask(task: ScreenCaptureRecentTask) {
        if (!recordingInteractor.isBusy) {
            selectedTask.value = task
            selectedDisplayId.value = null
            _selectedIndex.value = 0
        }
    }

    private fun displayChanges() =
        com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow {
            val listener =
                object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) {
                        trySend(Unit)
                    }

                    override fun onDisplayRemoved(displayId: Int) {
                        trySend(Unit)
                    }

                    override fun onDisplayChanged(displayId: Int) {
                        trySend(Unit)
                    }
                }
            displayManager.registerDisplayListener(listener, handler)
            trySend(Unit)
            awaitClose { displayManager.unregisterDisplayListener(listener) }
        }

    @AssistedFactory
    interface Factory {
        fun create(): RecordDetailsTargetViewModel
    }
}
