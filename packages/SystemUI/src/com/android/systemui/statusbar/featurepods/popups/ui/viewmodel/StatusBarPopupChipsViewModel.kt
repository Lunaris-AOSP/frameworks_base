/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.featurepods.popups.ui.viewmodel

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.featurepods.alarm.ui.viewmodel.AlarmPopupChipViewModel
import com.android.systemui.statusbar.featurepods.flashlight.ui.viewmodel.FlashlightPopupChipViewModel
import com.android.systemui.statusbar.featurepods.livescore.ui.viewmodel.LiveScorePopupChipViewModel
import com.android.systemui.statusbar.featurepods.av.ui.viewmodel.AvControlsChipViewModel
import com.android.systemui.statusbar.featurepods.media.ui.viewmodel.MediaControlChipViewModel
import com.android.systemui.statusbar.featurepods.popups.StatusBarPopupChips
import com.android.systemui.statusbar.featurepods.popups.data.repository.DynamicIslandState
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.CALLS
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.LOCKSCREEN
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.readDynamicIslandFeatureEnabled
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.screenrecord.ui.viewmodel.ScreenRecordPopupChipViewModel
import com.android.systemui.statusbar.featurepods.sharescreen.ui.viewmodel.ShareScreenPrivacyIndicatorViewModel
import com.android.systemui.statusbar.featurepods.stopwatch.ui.viewmodel.StopwatchPopupChipViewModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * View model deciding which system process chips to show in the status bar. Emits a list of
 * PopupChipModels.
 */
class StatusBarPopupChipsViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val userTracker: UserTracker,
    systemEventChipsFactory: SystemEventPopupChipsViewModel.Factory,
    mediaControlChipFactory: MediaControlChipViewModel.Factory,
    screenRecordChipFactory: ScreenRecordPopupChipViewModel.Factory,
    liveScoreChipFactory: LiveScorePopupChipViewModel.Factory,
    flashlightChipFactory: FlashlightPopupChipViewModel.Factory,
    stopwatchChipFactory: StopwatchPopupChipViewModel.Factory,
    alarmChipFactory: AlarmPopupChipViewModel.Factory,
    avControlsChipFactory: AvControlsChipViewModel.Factory,
    shareScreenPrivacyIndicatorFactory: ShareScreenPrivacyIndicatorViewModel.Factory,
) : ExclusiveActivatable() {

    private val systemEventChips by lazy { systemEventChipsFactory.create() }
    private val mediaControlChip by lazy { mediaControlChipFactory.create() }
    private val screenRecordChip by lazy { screenRecordChipFactory.create() }
    private val liveScoreChip by lazy { liveScoreChipFactory.create() }
    private val flashlightChip by lazy { flashlightChipFactory.create() }
    private val stopwatchChip by lazy { stopwatchChipFactory.create() }
    private val alarmChip by lazy { alarmChipFactory.create() }
    private val avControlsChip by lazy { avControlsChipFactory.create() }
    private val shareScreenPrivacyIndicator by lazy { shareScreenPrivacyIndicatorFactory.create() }
    private var isDynamicIslandEnabled by mutableStateOf(readDynamicIslandEnabled())
    private var isCallsEnabled by
        mutableStateOf(context.contentResolver.readDynamicIslandFeatureEnabled(CALLS, false))
    private var isLockscreenEnabled by
        mutableStateOf(
            context.contentResolver.readDynamicIslandFeatureEnabled(LOCKSCREEN, defaultValue = false)
        )
    private val dynamicIslandObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                isDynamicIslandEnabled = readDynamicIslandEnabled()
                isCallsEnabled = context.contentResolver.readDynamicIslandFeatureEnabled(CALLS, false)
                isLockscreenEnabled =
                    context.contentResolver.readDynamicIslandFeatureEnabled(
                        LOCKSCREEN,
                        defaultValue = false,
                    )
                if (!isDynamicIslandEnabled || (isOnLockscreen && !isLockscreenEnabled)) {
                    showPopup(null)
                }
            }
        }
    private val userChangedCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                showPopup(null)
                dynamicIslandObserver.onChange(false)
            }
        }

    private var isOnLockscreen by
        mutableStateOf(
            keyguardTransitionInteractor.currentKeyguardState.value == KeyguardState.LOCKSCREEN
        )
    private var isShadeVisible by
        mutableStateOf(
            (shadeInteractor.isAnyExpanded.value && !isOnLockscreen) ||
                shadeInteractor.anyExpansion.value > 0f ||
                shadeInteractor.isUserInteracting.value
        )
    private var isReadyForAutoPopup by mutableStateOf(false)
    private var autoPopupJob: Job? = null

    private fun showPopup(id: PopupChipId?) {
        if (
            id != null &&
                (!isDynamicIslandEnabled || isShadeVisible ||
                    (isOnLockscreen && !isLockscreenEnabled))
        ) {
            return
        }
        autoPopupJob?.cancel()
        autoPopupJob = null
        if (currentShownPopupChipId != id) {
            systemEventChips.chips.firstOrNull { it.chipId == currentShownPopupChipId }?.onPopupHidden?.invoke()
        }
        currentShownPopupChipId = id
        systemEventChips.chips.firstOrNull { it.chipId == id }?.onPopupShown?.invoke()
    }
    /** The ID of the current chip that is showing its popup, or `null` if no chip is shown. */
    private var currentShownPopupChipId by mutableStateOf<PopupChipId?>(null)

    private val incomingPopupChipBundle: PopupChipBundle by derivedStateOf {
        PopupChipBundle(
            media = mediaControlChip.chip,
            screenRecord = screenRecordChip.chip,
            liveScore = liveScoreChip.chip,
            flashlight = flashlightChip.chip,
            stopwatch = stopwatchChip.chip,
            alarm = alarmChip.chip,
            privacy = avControlsChip.chip,
            shareScreen = shareScreenPrivacyIndicator.chip,
        )
    }

    val shownPopupChips: List<PopupChipModel.Shown>
        get() = if (isOnLockscreen) emptyList() else visiblePopupChips

    val lockscreenPopupChips: List<PopupChipModel.Shown>
        get() = if (isOnLockscreen) visiblePopupChips else emptyList()

    internal val dynamicIslandState: DynamicIslandState
        get() = DynamicIslandState(
            canShowCalls = isDynamicIslandEnabled && isCallsEnabled && !isShadeVisible &&
                (isReadyForAutoPopup || (isOnLockscreen && isLockscreenEnabled)),
            callNotificationKeys = visiblePopupChips.mapNotNull { chip ->
                (chip.chipId as? PopupChipId.SystemEvent)?.eventId
                    ?.takeIf { it.startsWith("call_") }?.removePrefix("call_")
            }.toSet(),
            isScreenRecordingShown = visiblePopupChips.any { it.chipId == PopupChipId.ScreenRecord },
        )

    private val visiblePopupChips: List<PopupChipModel.Shown> by derivedStateOf {
        if (!isDynamicIslandEnabled || (isOnLockscreen && !isLockscreenEnabled) || isShadeVisible) {
            return@derivedStateOf emptyList()
        }

        val bundle = incomingPopupChipBundle
        val candidateChips =
            if (StatusBarPopupChips.isEnabled) {
                listOfNotNull(
                    bundle.media,
                    bundle.screenRecord,
                    bundle.liveScore,
                    bundle.stopwatch,
                    bundle.alarm,
                    bundle.flashlight,
                    bundle.privacy,
                    bundle.shareScreen,
                )
            } else {
                listOfNotNull(
                    bundle.media,
                    bundle.screenRecord,
                    bundle.liveScore,
                    bundle.stopwatch,
                    bundle.alarm,
                    bundle.flashlight,
                )
            }

        (systemEventChips.chips + candidateChips.filterIsInstance<PopupChipModel.Shown>()).map { chip ->
            chip.copy(
                isPopupShown = chip.chipId == currentShownPopupChipId,
                showPopup = { showPopup(chip.chipId) },
                hidePopup = {
                    if (currentShownPopupChipId == chip.chipId) showPopup(null)
                },
            )
        }
    }

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(CALLS),
                false,
                dynamicIslandObserver,
                UserHandle.USER_ALL,
            )
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(LOCKSCREEN),
                false,
                dynamicIslandObserver,
                UserHandle.USER_ALL,
            )
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND
                ),
                false,
                dynamicIslandObserver,
                UserHandle.USER_ALL,
            )
            userTracker.addCallback(userChangedCallback, context.mainExecutor)
            dynamicIslandObserver.onChange(false)
            launch {
                combine(
                        shadeInteractor.isAnyExpanded,
                        shadeInteractor.anyExpansion,
                        shadeInteractor.isUserInteracting,
                        keyguardTransitionInteractor.isFinishedIn(KeyguardState.LOCKSCREEN),
                    ) { expanded, expansion, interacting, onLockscreen ->
                        (expanded && !onLockscreen) || expansion > 0f || interacting
                    }
                    .distinctUntilChanged()
                    .collect { visible ->
                        isShadeVisible = visible
                        if (visible) showPopup(null)
                    }
            }
            launch {
                keyguardTransitionInteractor.isFinishedIn(KeyguardState.LOCKSCREEN)
                    .collectLatest {
                        if (isOnLockscreen != it) showPopup(null)
                        isOnLockscreen = it
                    }
            }
            launch {
                keyguardTransitionInteractor.isFinishedIn(KeyguardState.GONE)
                    .collectLatest { isReadyForAutoPopup = it }
            }
            launch {
                val consumedRequests = mutableMapOf<PopupChipId, Long>()
                snapshotFlow {
                    Triple(
                        systemEventChips.chips,
                        isDynamicIslandEnabled,
                        (isReadyForAutoPopup || (isOnLockscreen && isLockscreenEnabled)) &&
                            !isShadeVisible,
                    )
                }.collect { (chips, enabled, canAutoPopup) ->
                    val ids = chips.map { it.chipId }.toSet()
                    consumedRequests.keys.retainAll(ids)
                    if (!enabled || !canAutoPopup) {
                        showPopup(null)
                        return@collect
                    }
                    if (currentShownPopupChipId is PopupChipId.SystemEvent &&
                        currentShownPopupChipId !in ids) {
                        showPopup(null)
                    }
                    val requests = chips.filter { chip ->
                        chip.autoPopupRequest != null &&
                            consumedRequests[chip.chipId] != chip.autoPopupRequest
                    }
                    requests.forEach { consumedRequests[it.chipId] = it.autoPopupRequest!! }
                    requests.firstOrNull()?.let { chip ->
                        showPopup(chip.chipId)
                        chip.onAutoPopupShown()
                        if (chip.autoPopupDurationMs > 0L) autoPopupJob = launch {
                            delay(chip.autoPopupDurationMs)
                            if (currentShownPopupChipId == chip.chipId) {
                                showPopup(null)
                            }
                        }
                    }
                }
            }
            launch { systemEventChips.activate() }
            launch { avControlsChip.activate() }
            launch { mediaControlChip.activate() }
            launch { screenRecordChip.activate() }
            launch { liveScoreChip.activate() }
            launch { flashlightChip.activate() }
            launch { stopwatchChip.activate() }
            launch { alarmChip.activate() }
            launch { shareScreenPrivacyIndicator.activate() }
            try {
                awaitCancellation()
            } finally {
                showPopup(null)
                userTracker.removeCallback(userChangedCallback)
                context.contentResolver.unregisterContentObserver(dynamicIslandObserver)
            }
        }
    }

    private data class PopupChipBundle(
        val media: PopupChipModel = PopupChipModel.Hidden(chipId = PopupChipId.MediaControl),
        val screenRecord: PopupChipModel =
            PopupChipModel.Hidden(chipId = PopupChipId.ScreenRecord),
        val liveScore: PopupChipModel = PopupChipModel.Hidden(chipId = PopupChipId.LiveScore),
        val flashlight: PopupChipModel = PopupChipModel.Hidden(chipId = PopupChipId.Flashlight),
        val stopwatch: PopupChipModel = PopupChipModel.Hidden(chipId = PopupChipId.Stopwatch),
        val alarm: PopupChipModel = PopupChipModel.Hidden(chipId = PopupChipId.Alarm),
        val privacy: PopupChipModel =
            PopupChipModel.Hidden(chipId = PopupChipId.AvControlsIndicator),
        val shareScreen: PopupChipModel =
            PopupChipModel.Hidden(chipId = PopupChipId.ShareScreenPrivacyIndicator),
    )

    private fun readDynamicIslandEnabled(): Boolean {
        return Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND,
            0,
            UserHandle.USER_CURRENT,
        ) != 0
    }

    @AssistedFactory
    interface Factory {
        fun create(): StatusBarPopupChipsViewModel
    }
}
