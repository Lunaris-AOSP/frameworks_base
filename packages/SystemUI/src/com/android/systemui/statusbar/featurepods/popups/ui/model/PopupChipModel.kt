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

package com.android.systemui.statusbar.featurepods.popups.ui.model

import com.android.systemui.statusbar.featurepods.alarm.shared.model.AlarmPopupModel
import com.android.systemui.statusbar.featurepods.flashlight.shared.model.FlashlightPopupModel
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.featurepods.livescore.shared.model.LiveScoreChipModel
import com.android.systemui.statusbar.featurepods.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.featurepods.screenrecord.shared.model.ScreenRecordPopupModel
import com.android.systemui.statusbar.featurepods.stopwatch.shared.model.StopwatchPopupModel

/**
 * Ids used to track different types of popup chips. Will be used to ensure only one chip is
 * displaying its popup at a time.
 */
sealed class PopupChipId(val value: String) {
    data class SystemEvent(val eventId: String) : PopupChipId("SystemEvent:$eventId")

    data object MediaControl : PopupChipId("MediaControl")

    data object ScreenRecord : PopupChipId("ScreenRecord")

    data object LiveScore : PopupChipId("LiveScore")

    data object Flashlight : PopupChipId("Flashlight")

    data object Stopwatch : PopupChipId("Stopwatch")

    data object Alarm : PopupChipId("Alarm")

    data object AvControlsIndicator : PopupChipId("AvControlsIndicator")

    data object ShareScreenPrivacyIndicator : PopupChipId("ShareScreenPrivacyIndicator")
}

/** Model for an optionally clickable icon that is displayed on the chip. */
data class ChipIcon(
    val icon: Icon,
    val onClick: (() -> Unit)? = null,
    val tint: Boolean = true,
)

/** Defines the behavior of the chip when hovered over. */
sealed interface HoverBehavior {
    /** No specific hover behavior. The default icon will be shown. */
    data object None : HoverBehavior

    /** Shows a list of buttons on hover with the given [icons] */
    data class Buttons(val icons: List<ChipIcon>) : HoverBehavior
}

/** Rich popup contents associated with a status bar chip. */
sealed interface PopupContentModel {
    data class SystemEvent(
        val kind: SystemEventKind,
        val title: String,
        val text: String,
        val actions: List<com.android.systemui.statusbar.featurepods.popups.shared.model.PopupActionModel>,
        val progress: Float? = null,
        val callStartTimeMs: Long? = null,
        val timerEndTimeMs: Long? = null,
        val timerEndElapsedRealtimeMs: Long? = null,
        val timerRemainingMs: Long? = null,
        val timerOriginalDurationMs: Long? = null,
        val timerPaused: Boolean = false,
        val indeterminate: Boolean = false,
        val icon: Icon? = null,
        val appName: String? = null,
        val prominentText: String? = null,
        val pulse: Boolean = false,
        val image: Icon? = null,
    ) : PopupContentModel

    data object None : PopupContentModel

    data class Media(
        val model: MediaControlChipModel,
        val useWaveform: Boolean = false,
    ) : PopupContentModel

    data class ScreenRecord(val model: ScreenRecordPopupModel) : PopupContentModel

    data class LiveScore(val model: LiveScoreChipModel) : PopupContentModel

    data class Flashlight(val model: FlashlightPopupModel) : PopupContentModel

    data class Stopwatch(val model: StopwatchPopupModel) : PopupContentModel

    data class Alarm(val model: AlarmPopupModel) : PopupContentModel
}

/** Model for individual status bar popup chips. */
sealed class PopupChipModel {
    abstract val logName: String
    abstract val chipId: PopupChipId

    data class Hidden(override val chipId: PopupChipId, val shouldAnimate: Boolean = true) :
        PopupChipModel() {
        override val logName = "Hidden(id=$chipId, anim=$shouldAnimate)"
    }

    data class Shown(
        override val chipId: PopupChipId,
        /** Icons shown on the chip when no specific hover behavior. */
        val icons: List<ChipIcon>,
        val chipText: String?,
        /** Determines the colors used for the chip. Defaults to system themed colors. */
        val colors: ColorsModel = ColorsModel.SystemTheme,
        val isPopupShown: Boolean = false,
        val showPopup: () -> Unit = {},
        val hidePopup: () -> Unit = {},
        val hoverBehavior: HoverBehavior = HoverBehavior.None,
        val contentDescription: String? = null,
        val popupContent: PopupContentModel = PopupContentModel.None,
        /** Wall-clock start of an active call; null for ringing calls and other chips. */
        val callStartTimeMs: Long? = null,
        /** A new event revision requests one automatic expansion once the device is unlocked. */
        val autoPopupRequest: Long? = null,
        val autoPopupDurationMs: Long = 0L,
        val onAutoPopupShown: () -> Unit = {},
        val onPopupShown: () -> Unit = {},
        val onPopupHidden: () -> Unit = {},
    ) : PopupChipModel() {
        override val logName = "Shown(id=$chipId, toggled=$isPopupShown)"
    }
}
