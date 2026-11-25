/*
 * Copyright (C) 2026 Lunaris AOSP
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

package com.android.systemui.axdynamicbar.ui

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.islandevents.model.IslandEvent
import java.util.concurrent.CopyOnWriteArraySet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

fun IslandEvent.hasAssociatedLockscreenNotification(): Boolean = when (this) {
    is IslandEvent.Media,
    is IslandEvent.Notification,
    is IslandEvent.Call,
    is IslandEvent.AudioRecording,
    is IslandEvent.Timer,
    is IslandEvent.Stopwatch,
    is IslandEvent.Alarm,
    is IslandEvent.PromotedOngoing,
    is IslandEvent.Sports,
    is IslandEvent.NowPlaying,
    is IslandEvent.AospChip -> true

    is IslandEvent.Torch,
    is IslandEvent.Bluetooth,
    is IslandEvent.Hotspot,
    is IslandEvent.Vpn,
    is IslandEvent.Clipboard,
    is IslandEvent.RingerMode,
    is IslandEvent.Charging,
    is IslandEvent.BiometricUnlock,
    is IslandEvent.AppSwitch,
    is IslandEvent.KeyguardIndication -> false
}

@SysUISingleton
class AxDynamicBarColorOsController
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    @Main private val mainHandler: Handler,
    private val interactor: AxDynamicBarInteractor,
    private val keyguardExpansion: AxDynamicBarKeyguardExpansion,
) {
    fun interface Listener {
        fun onColorOsStateChanged(active: Boolean, revealed: Boolean)
    }

    private val pillAnchorRect = RectF()
    private val anchorLock = Any()

    val isModeActive: StateFlow<Boolean> =
        combine(
            interactor.settings.isKeyguardEnabled,
            interactor.settings.isKeyguardColorOsStyleEnabled,
        ) { keyguardEnabled, colorOsEnabled ->
            keyguardEnabled && colorOsEnabled
        }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    private val _isRevealed = MutableStateFlow(false)
    val isRevealed: StateFlow<Boolean> = _isRevealed.asStateFlow()

    private val hasRevealableEvents: StateFlow<Boolean> =
        combine(
            interactor.uiState,
            interactor.settings.isKeyguardMusicPillEnabled,
        ) { state, musicPillEnabled ->
            !state.manuallyHidden &&
                state.events.any { event ->
                    event.hasAssociatedLockscreenNotification() &&
                        (musicPillEnabled || event !is IslandEvent.Media)
                }
        }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    private val hasRevealablePill: StateFlow<Boolean> =
        combine(
            interactor.uiState.map { it.shouldShow }.distinctUntilChanged(),
            hasRevealableEvents,
        ) { shouldShow, hasEvents ->
            shouldShow && hasEvents
        }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    private val stackGatingState: StateFlow<Pair<Boolean, Boolean>> = run {
        val lockscreenOrAod =
            combine(
                isModeActive,
                interactor.isOnKeyguard,
                interactor.isBouncerShowing,
            ) { modeActive, onKeyguard, bouncer ->
                modeActive && onKeyguard && !bouncer
            }
        val shadeClosed =
            combine(
                interactor.qsExpansion.map { it > 0f }.distinctUntilChanged(),
                interactor.isPanelExpanded,
                keyguardExpansion.isExpanded,
            ) { qsOpen, panelExpanded, kgExpanded ->
                !qsOpen && !panelExpanded && !kgExpanded
            }
        combine(
            lockscreenOrAod,
            shadeClosed,
            hasRevealableEvents,
            _isRevealed,
        ) { onKg, closed, hasEvents, revealed ->
            val active = onKg && closed && (hasEvents || revealed)
            active to (active && revealed)
        }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false to false)
    }

    /**
     * True while ColorOS mode is keeping lockscreen notifications/media tucked behind the pill
     * (including across doze/AOD so large clock does not flicker on screen wake).
     */
    val isHidingClockNotifications: StateFlow<Boolean> = run {
        val lockscreenOrAod =
            combine(
                isModeActive,
                interactor.isOnKeyguard,
                interactor.isBouncerShowing,
            ) { modeActive, onKeyguard, bouncer ->
                modeActive && onKeyguard && !bouncer
            }
        val shadeClosed =
            combine(
                interactor.qsExpansion.map { it > 0f }.distinctUntilChanged(),
                interactor.isPanelExpanded,
                keyguardExpansion.isExpanded,
            ) { qsOpen, panelExpanded, kgExpanded ->
                !qsOpen && !panelExpanded && !kgExpanded
            }
        combine(
            lockscreenOrAod,
            shadeClosed,
            hasRevealableEvents,
            _isRevealed,
        ) { onKg, closed, hasEvents, revealed ->
            onKg && closed && hasEvents && !revealed
        }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)
    }

    init {
        INSTANCE = this
        val (initActive, initRevealed) = stackGatingState.value
        notifyListeners(initActive, initRevealed)

        combine(
            isModeActive,
            interactor.isOnKeyguard,
            interactor.isDozing,
            interactor.dozeAmount.map { it > 0f }.distinctUntilChanged(),
            interactor.isBouncerShowing,
        ) { modeActive, onKeyguard, dozing, dozeAmt, bouncer ->
            modeActive && onKeyguard && !dozing && !dozeAmt && !bouncer
        }
            .distinctUntilChanged()
            .onEach { canStayRevealed ->
                if (!canStayRevealed) collapse()
            }
            .launchIn(applicationScope)

        combine(
            interactor.qsExpansion.map { it > 0f }.distinctUntilChanged(),
            interactor.isPanelExpanded,
            keyguardExpansion.isExpanded,
            hasRevealablePill,
        ) { qsOpen, panelExpanded, kgExpanded, hasPill ->
            !qsOpen && !panelExpanded && !kgExpanded && hasPill
        }
            .distinctUntilChanged()
            .onEach { canStayRevealed ->
                if (!canStayRevealed) collapse()
            }
            .launchIn(applicationScope)

        stackGatingState
            .onEach { (active, revealed) ->
                if (!active) updateMediaBlurFraction(1f)
                notifyListeners(active, revealed)
            }
            .launchIn(applicationScope)

        isHidingClockNotifications
            .onEach { hiding ->
                _hidingClockNotificationsStatic.value = hiding
                notifyClockListeners(hiding)
            }
            .launchIn(applicationScope)
    }

    fun isStackGatingActive(): Boolean = stackGatingState.value.first

    fun isStackRevealed(): Boolean = stackGatingState.value.second

    fun shouldUseColorOsRevealFor(event: IslandEvent): Boolean =
        isModeActive.value && event.hasAssociatedLockscreenNotification()

    fun reveal() {
        if (!isModeActive.value ||
            !interactor.isOnKeyguard.value ||
            interactor.isDozing.value ||
            interactor.dozeAmount.value > 0f ||
            interactor.isBouncerShowing.value ||
            interactor.isPanelExpanded.value ||
            interactor.qsExpansion.value > 0f
        ) {
            return
        }
        if (keyguardExpansion.isExpanded.value) {
            keyguardExpansion.collapse()
        }
        _isRevealed.value = true
    }

    fun collapse() {
        if (_isRevealed.value) {
            _isRevealed.value = false
        }
    }

    fun toggleReveal() {
        if (_isRevealed.value) collapse() else reveal()
    }

    fun updatePillAnchorRect(left: Float, top: Float, right: Float, bottom: Float) {
        if (right <= left || bottom <= top) return
        synchronized(anchorLock) {
            pillAnchorRect.set(left, top, right, bottom)
        }
    }

    fun getPillAnchorRect(out: RectF): Boolean {
        synchronized(anchorLock) {
            if (pillAnchorRect.isEmpty) return false
            out.set(pillAnchorRect)
            return true
        }
    }

    fun addListener(listener: Listener) {
        staticListeners.add(listener)
        val (active, revealed) = stackGatingState.value
        postToMain { listener.onColorOsStateChanged(active, revealed) }
    }

    fun removeListener(listener: Listener) {
        staticListeners.remove(listener)
    }

    private fun notifyListeners(active: Boolean, revealed: Boolean) {
        postToMain {
            for (listener in staticListeners) {
                listener.onColorOsStateChanged(active, revealed)
            }
        }
    }

    private fun notifyClockListeners(hiding: Boolean) {
        postToMain {
            for (listener in clockListeners) {
                listener.onClockNotificationHidingChanged(hiding)
            }
        }
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    fun interface ClockListener {
        fun onClockNotificationHidingChanged(hiding: Boolean)
    }

    companion object {
        @Volatile
        private var INSTANCE: AxDynamicBarColorOsController? = null

        private val staticListeners = CopyOnWriteArraySet<Listener>()
        private val clockListeners = CopyOnWriteArraySet<ClockListener>()
        private val _hidingClockNotificationsStatic = MutableStateFlow(false)
        private val _mediaBlurFractionStatic = MutableStateFlow(1f)

        val hidingClockNotificationsFlow: StateFlow<Boolean> =
            _hidingClockNotificationsStatic.asStateFlow()

        val mediaBlurFractionFlow: StateFlow<Float> =
            _mediaBlurFractionStatic.asStateFlow()

        @JvmStatic
        fun updateMediaBlurFraction(fraction: Float) {
            val clamped = fraction.coerceIn(0f, 1f)
            val current = _mediaBlurFractionStatic.value
            if (kotlin.math.abs(current - clamped) < 0.005f &&
                !((clamped == 0f || clamped == 1f) && current != clamped)
            ) {
                return
            }
            _mediaBlurFractionStatic.value = clamped
        }

        @JvmStatic
        fun getOrNull(): AxDynamicBarColorOsController? = INSTANCE

        @JvmStatic
        fun isHidingClockNotificationsStatic(): Boolean = _hidingClockNotificationsStatic.value

        @JvmStatic
        fun registerListener(listener: Listener) {
            staticListeners.add(listener)
            INSTANCE?.let { controller ->
                val (active, revealed) = controller.stackGatingState.value
                controller.postToMain { listener.onColorOsStateChanged(active, revealed) }
            }
        }

        @JvmStatic
        fun unregisterListener(listener: Listener) {
            staticListeners.remove(listener)
        }

        @JvmStatic
        fun registerClockListener(listener: ClockListener) {
            clockListeners.add(listener)
            val hiding = _hidingClockNotificationsStatic.value
            INSTANCE?.postToMain { listener.onClockNotificationHidingChanged(hiding) }
        }

        @JvmStatic
        fun unregisterClockListener(listener: ClockListener) {
            clockListeners.remove(listener)
        }
    }
}
