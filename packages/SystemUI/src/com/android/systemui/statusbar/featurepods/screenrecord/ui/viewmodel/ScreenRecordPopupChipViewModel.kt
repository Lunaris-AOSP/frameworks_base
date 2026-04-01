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

package com.android.systemui.statusbar.featurepods.screenrecord.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.model.ScreenRecordModel.Starting.Companion.toCountdownSeconds
import com.android.systemui.statusbar.chips.screenrecord.domain.interactor.ScreenRecordChipInteractor
import com.android.systemui.statusbar.chips.screenrecord.domain.model.ScreenRecordChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.ChipIcon
import com.android.systemui.statusbar.featurepods.popups.ui.model.ColorsModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.statusbar.featurepods.screenrecord.shared.model.ScreenRecordPopupModel
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.util.time.SystemClock
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** ViewModel backing the screen-recording page inside the dynamic island. */
class ScreenRecordPopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val interactor: ScreenRecordChipInteractor,
    private val systemClock: SystemClock,
) : StatusBarPopupChipViewModel, ExclusiveActivatable() {
    private val hydrator = Hydrator("ScreenRecordPopupChipViewModel.hydrator")

    private val popupModel: Flow<ScreenRecordPopupModel?> =
        interactor.screenRecordState
            .map { state ->
                when (state) {
                    is ScreenRecordChipModel.DoingNothing -> null
                    is ScreenRecordChipModel.Starting ->
                        ScreenRecordPopupModel.Starting(
                            secondsUntilStarted = state.millisUntilStarted.toCountdownSeconds()
                        )
                    is ScreenRecordChipModel.Recording ->
                        ScreenRecordPopupModel.Recording(
                            startElapsedRealtimeMs = systemClock.elapsedRealtime(),
                            stopRecording = interactor::stopRecording,
                        )
                }
            }
            .pairwise(initialValue = null)
            .map { (old, new) ->
                if (
                    old is ScreenRecordPopupModel.Recording &&
                        new is ScreenRecordPopupModel.Recording
                ) {
                    new.copy(startElapsedRealtimeMs = old.startElapsedRealtimeMs)
                } else {
                    new
                }
            }

    override val chip: PopupChipModel by
        hydrator.hydratedStateOf(
            traceName = "chip",
            initialValue = PopupChipModel.Hidden(PopupChipId.ScreenRecord),
            source = popupModel.map { model -> toPopupChipModel(model) },
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    private fun toPopupChipModel(model: ScreenRecordPopupModel?): PopupChipModel {
        if (model == null) {
            return PopupChipModel.Hidden(PopupChipId.ScreenRecord)
        }

        val recordingDescription =
            ContentDescription.Resource(R.string.screenrecord_ongoing_screen_only)
                .loadContentDescription(context)

        return PopupChipModel.Shown(
            chipId = PopupChipId.ScreenRecord,
            icons =
                listOf(
                    ChipIcon(
                        Icon.Resource(
                            resId = R.drawable.ic_screenrecord,
                            contentDescription = ContentDescription.Loaded(recordingDescription),
                        )
                    )
                ),
            chipText =
                when (model) {
                    is ScreenRecordPopupModel.Starting -> model.secondsUntilStarted.toString()
                    is ScreenRecordPopupModel.Recording ->
                        context.getString(R.string.dynamic_island_screen_record_short)
                },
            colors = ColorsModel.DynamicIslandAlert,
            contentDescription = recordingDescription,
            popupContent = PopupContentModel.ScreenRecord(model),
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): ScreenRecordPopupChipViewModel
    }
}
