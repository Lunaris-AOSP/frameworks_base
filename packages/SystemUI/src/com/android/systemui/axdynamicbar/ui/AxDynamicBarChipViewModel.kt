package com.android.systemui.axdynamicbar.ui

import androidx.compose.ui.geometry.Rect
import com.android.systemui.animation.Expandable
import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.islandevents.model.IslandEvent
import com.android.systemui.media.controls.shared.MediaSeekBarStyle
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AxDynamicBarChipState(
    val event: IslandEvent,
    val eventCount: Int,
    val pinnedIndex: Int,
    val allEvents: List<IslandEvent>,
    val notificationAlert: IslandEvent.Notification? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class AxDynamicBarChipViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    val interactor: AxDynamicBarInteractor,
    authController: AuthController,
    udfpsOverlayInteractor: UdfpsOverlayInteractor,
    keyguardIndicationController: KeyguardIndicationController,
    val keyguardExpansion: AxDynamicBarKeyguardExpansion,
    val statusBarExpansion: AxDynamicBarStatusBarExpansion,
    val colorOsController: AxDynamicBarColorOsController,
    activeNotificationsInteractor: ActiveNotificationsInteractor,
) {
    val isLowUdfps: StateFlow<Boolean> =
        udfpsOverlayInteractor.udfpsOverlayParams
            .map { params ->
                if (!authController.isUdfpsSupported) return@map false
                val sensorBottom = params.sensorBounds.bottom
                val displayHeight = params.naturalDisplayHeight
                if (displayHeight <= 0) return@map false
                sensorBottom > displayHeight * LOW_UDFPS_THRESHOLD
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    val chipState: StateFlow<AxDynamicBarChipState?> =
        interactor.uiState
            .map { uiState ->
                if (!uiState.shouldShow) return@map null
                val alert = uiState.notificationAlert
                val topEvent = uiState.topEvent ?: alert ?: return@map null
                AxDynamicBarChipState(
                    event = topEvent,
                    eventCount = uiState.events.count { it !is IslandEvent.Notification },
                    pinnedIndex = uiState.pinnedEventIndex,
                    allEvents = uiState.events,
                    notificationAlert = alert,
                )
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Lazily, null)

    val isEnabled: StateFlow<Boolean> = interactor.settings.isEnabled
    val isKeyguardEnabled: StateFlow<Boolean> = interactor.settings.isKeyguardEnabled
    val keyguardBatteryChipMode: StateFlow<Int> = interactor.settings.keyguardBatteryChipMode
    val isKeyguardMusicPillEnabled: StateFlow<Boolean> = interactor.settings.isKeyguardMusicPillEnabled
    val isKeyguardColorOsActive: StateFlow<Boolean> = colorOsController.isModeActive
    val isKeyguardColorOsRevealed: StateFlow<Boolean> = colorOsController.isRevealed
    val totalNotificationCount: StateFlow<Int> =
        activeNotificationsInteractor.allNotificationsCount
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, 0)
    val isMediaEventTypeEnabled: StateFlow<Boolean> =
        interactor.settings.disabledEventTypes
            .map { "media" !in it }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, true)
    val mediaSeekBarStyle: StateFlow<MediaSeekBarStyle> = interactor.mediaSeekBarStyle
    val chipStyle: StateFlow<Int> = interactor.settings.chipStyle

    val keyguardBatteryInfo = keyguardExpansion.batteryInfo

    init {
        applicationScope.launch {
            val incomingCall =
                combine(interactor.uiState, interactor.isOnKeyguard) { state, onKeyguard ->
                    val enabled = if (onKeyguard) isKeyguardEnabled.value else isEnabled.value
                    val incomingCallId = state.events
                        .firstOrNull {
                            state.shouldShow && enabled && it is IslandEvent.Call &&
                                it.callType == "Phone:incoming" &&
                                interactor.settings.isEventEnabled(it, onKeyguard)
                        }?.id
                    incomingCallId to onKeyguard
                }
            combine(
                incomingCall,
                keyguardExpansion.canShow,
                interactor.isPanelExpanded,
                interactor.qsExpansion.map { it > 0f }.distinctUntilChanged(),
                combine(
                    interactor.legacyShadeExpansion.map { it > 0f }.distinctUntilChanged(),
                    colorOsController.isModeActive,
                ) { shadeOpen, colorOsActive -> shadeOpen to colorOsActive },
            ) { (incomingCallId, onKeyguard), keyguardCanShow, panelExpanded, qsOpen, (shadeOpen, colorOsActive) ->
                val canExpand =
                    if (onKeyguard) keyguardCanShow && !colorOsActive
                    else !panelExpanded && !qsOpen && !shadeOpen
                Triple(incomingCallId, onKeyguard, canExpand)
            }
                .distinctUntilChanged()
                .collect { (incomingCallId, onKeyguard, canExpand) ->
                    if (incomingCallId != null && canExpand) {
                        interactor.dismissNotificationAlert()
                        if (onKeyguard) {
                            keyguardExpansion.expand()
                        } else {
                            statusBarExpansion.expand()
                        }
                    } else {
                        statusBarExpansion.collapse()
                        keyguardExpansion.collapse()
                    }
                }
        }
    }

    private val batteryIndicationTick: kotlinx.coroutines.flow.Flow<Unit> = callbackFlow {
        val listener = KeyguardIndicationController.IndicationListener { type, _ ->
            if (type == KeyguardIndicationController.AX_TYPE_BATTERY) trySend(Unit)
        }
        keyguardIndicationController.addIndicationListener(listener)
        awaitClose { keyguardIndicationController.removeIndicationListener(listener) }
    }

    val batteryString: StateFlow<String> =
        combine(
            keyguardBatteryInfo,
            batteryIndicationTick.onStart { emit(Unit) },
        ) { info, _ -> info }
            .map {
                if (it.isCharging) {
                    formatChargingString(keyguardIndicationController.powerChargingString)
                } else {
                    ""
                }
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Lazily, "")

    val isOnKeyguard: StateFlow<Boolean> = interactor.isOnKeyguard

    val shouldSuppressKeyguardIndication: StateFlow<Boolean> =
        combine(isOnKeyguard, isKeyguardEnabled) { onKeyguard, keyguardEnabled ->
            onKeyguard && keyguardEnabled
        }.distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    val isKeyguardChipVisible: StateFlow<Boolean> =
        combine(
            isOnKeyguard,
            isKeyguardEnabled,
            isKeyguardMusicPillEnabled,
            chipState,
        ) { onKeyguard, keyguardEnabled, pillEnabled, state ->
            onKeyguard && (
                keyguardEnabled ||
                    (pillEnabled && state?.allEvents?.any { it is IslandEvent.Media } == true)
            )
        }.distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    val isKeyguardFadingAway: StateFlow<Boolean> = interactor.isKeyguardFadingAway

    val isBouncerShowing: StateFlow<Boolean> = interactor.isBouncerShowing

    private val _keyguardCarrierText = MutableStateFlow("")
    val keyguardCarrierText: StateFlow<String> = _keyguardCarrierText.asStateFlow()

    fun updateKeyguardCarrierText(text: String) {
        _keyguardCarrierText.value = text
    }

    private val _chipBoundsOnScreen = MutableStateFlow<Rect?>(null)
    val chipBoundsOnScreen: StateFlow<Rect?> = _chipBoundsOnScreen.asStateFlow()

    fun updateChipBounds(bounds: Rect) {
        if (bounds.width > 0f && bounds.height > 0f) _chipBoundsOnScreen.value = bounds
    }

    val isExpanded: StateFlow<Boolean> = statusBarExpansion.isExpanded

    val isKeyguardExpanded: StateFlow<Boolean> = keyguardExpansion.isExpanded

    @Volatile private var outsideCollapseDownTime = NO_OUTSIDE_COLLAPSE

    fun collapseFromOutsideTouch(downTime: Long) {
        if (!statusBarExpansion.isExpanded.value || downTime <= 0L) return
        outsideCollapseDownTime = downTime
        statusBarExpansion.collapse()
    }

    fun chipTapClosesPanel(downTime: Long): Boolean {
        val outside = outsideCollapseDownTime
        outsideCollapseDownTime = NO_OUTSIDE_COLLAPSE
        val sameGesture =
            outside != NO_OUTSIDE_COLLAPSE && abs(downTime - outside) <= SAME_GESTURE_TOLERANCE_MS
        return sameGesture || statusBarExpansion.isExpanded.value
    }

    fun cycleNext() = interactor.cycleNext()

    fun cyclePrev() = interactor.cyclePrev()

    fun switchToPreviousApp(event: IslandEvent.AppSwitch) = interactor.switchToPreviousApp(event)

    fun dismissEvent(event: IslandEvent) = interactor.dismissEvent(event)

    fun togglePlayPause() = interactor.togglePlayPause()

    fun skipNext() = interactor.skipNext()

    fun skipPrev() = interactor.skipPrev()

    fun toggleTorch() = interactor.toggleTorch()

    fun launchNotificationFromKeyguard(event: IslandEvent.Notification) {
        interactor.launchNotificationDismissingKeyguard(event)
    }

    fun handleAospChipTap(event: IslandEvent.AospChip, expandable: Expandable): Boolean {
        val active = event.active
        return when (val behavior = active.clickBehavior) {
            is OngoingActivityChipModel.ClickBehavior.ShowHeadsUpNotification -> {
                behavior.onClick()
                true
            }
            is OngoingActivityChipModel.ClickBehavior.HideHeadsUpNotification -> {
                behavior.onClick()
                true
            }
            is OngoingActivityChipModel.ClickBehavior.ExpandAction -> {
                behavior.onClick(expandable)
                true
            }
            is OngoingActivityChipModel.ClickBehavior.None -> false
        }
    }

    companion object {
        private const val LOW_UDFPS_THRESHOLD = 0.93f
        private const val NO_OUTSIDE_COLLAPSE = -1L
        private const val SAME_GESTURE_TOLERANCE_MS = 100L
    }

    private fun formatChargingString(text: String?): String {
        val cleaned = text?.trim()
        return if (cleaned.isNullOrEmpty()) "" else cleaned
    }
}
