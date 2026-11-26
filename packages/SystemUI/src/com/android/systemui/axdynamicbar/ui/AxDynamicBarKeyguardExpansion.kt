/*
 * Copyright 2025-2026 AxionOS
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

import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.islandevents.model.IslandEvent
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class AxDynamicBarKeyguardExpansion
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val interactor: AxDynamicBarInteractor,
    batteryInteractor: BatteryInteractor,
    batteryController: BatteryController,
) {
    private enum class Target { EVENT, CHARGING }

    private val target = MutableStateFlow<Target?>(null)

    val batteryInfo: StateFlow<IslandEvent.Charging> =
        combine(
            batteryInteractor.level,
            batteryInteractor.isCharging,
            batteryInteractor.powerSave,
            batteryInteractor.batteryTimeRemainingEstimate,
        ) { level, charging, powerSave, timeRemaining ->
            IslandEvent.Charging(
                level = level ?: 0,
                isWireless = batteryController.isPluggedInWireless,
                isCharging = charging,
                isPowerSave = powerSave,
                timeRemaining = timeRemaining,
            )
        }.stateIn(
            applicationScope,
            SharingStarted.Lazily,
            IslandEvent.Charging(level = 0, isWireless = false, isCharging = false),
        )

    private val chargingChip =
        combine(
            batteryInfo,
            interactor.settings.isKeyguardEnabled,
            interactor.settings.keyguardBatteryChipMode,
        ) { battery, enabled, mode ->
            battery.takeIf { enabled && mode > 0 && it.isCharging }
        }.stateIn(applicationScope, SharingStarted.Eagerly, null)

    // The lock-screen battery chip has no entry in uiState. Keep its card selected after a tap.
    val panelEvent: StateFlow<IslandEvent?> =
        combine(interactor.uiState, chargingChip, target) { state, battery, requested ->
            val event = state.topEvent.takeIf { state.shouldShow }
            when (requested) {
                Target.CHARGING -> battery
                Target.EVENT -> event
                null -> event ?: battery
            }
        }.stateIn(applicationScope, SharingStarted.Eagerly, null)

    val canShow: StateFlow<Boolean> = run {
        val contextAndDoze =
            combine(
                interactor.isOnKeyguard,
                interactor.isDozing,
                interactor.dozeAmount.map { it > 0f }.distinctUntilChanged(),
                interactor.qsExpansion.map { it > 0f }.distinctUntilChanged(),
                interactor.isPanelExpanded,
            ) { onKg, dozing, dozeAmt, qs, panelExp ->
                onKg && !dozing && !dozeAmt && !qs && !panelExp
            }
        val shadeAndBouncer =
            combine(
                interactor.isBouncerShowing,
                interactor.legacyShadeExpansion.map { it >= 0.95f }.distinctUntilChanged(),
                interactor.isDreaming,
                panelEvent.map { it != null }.distinctUntilChanged(),
            ) { bouncer, shadeFull, dreaming, chip ->
                !bouncer && shadeFull && !dreaming && chip
            }
        val settingsAllowKeyguard = combine(
            interactor.settings.isKeyguardEnabled,
            interactor.settings.isKeyguardMusicPillEnabled,
        ) { keyguardEnabled, musicPillEnabled ->
            keyguardEnabled || musicPillEnabled
        }
        combine(contextAndDoze, shadeAndBouncer, settingsAllowKeyguard) { a, b, enabled ->
            a && b && enabled
        }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)
    }

    val isExpanded: StateFlow<Boolean> =
        combine(target, canShow) { requested, show -> requested != null && show }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Lazily, false)

    private val _collapseSettled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val collapseSettled: SharedFlow<Unit> = _collapseSettled.asSharedFlow()

    fun notifyCollapseSettled() {
        _collapseSettled.tryEmit(Unit)
    }

    init {
        canShow
            .onEach { if (!it) collapse() }
            .launchIn(applicationScope)

        interactor.isOnKeyguard
            .onEach { if (!it) collapse() }
            .launchIn(applicationScope)
    }

    fun expand() {
        val state = interactor.uiState.value
        if (canShow.value && state.shouldShow && state.topEvent != null) target.value = Target.EVENT
    }

    fun expandCharging() {
        if (canShow.value && chargingChip.value != null) target.value = Target.CHARGING
    }

    fun collapse() {
        target.value = null
    }

    fun toggle() {
        if (target.value != null) collapse() else expand()
    }
}
