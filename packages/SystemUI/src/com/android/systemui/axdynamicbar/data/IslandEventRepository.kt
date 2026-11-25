package com.android.systemui.axdynamicbar.data

import android.util.Log
import com.android.systemui.axdynamicbar.data.source.AospChipIslandManager
import com.android.systemui.axdynamicbar.data.source.AppTrackingIslandManager
import com.android.systemui.axdynamicbar.data.source.BiometricIslandManager
import com.android.systemui.axdynamicbar.data.source.ConnectivityIslandManager
import com.android.systemui.axdynamicbar.data.source.MediaIslandManager
import com.android.systemui.axdynamicbar.data.source.NotificationIslandManager
import com.android.systemui.axdynamicbar.data.source.SmartspaceIslandManager
import com.android.systemui.axdynamicbar.data.source.SystemIslandManager
import com.android.systemui.axdynamicbar.data.source.TorchIslandManager
import com.android.systemui.axdynamicbar.domain.AxDynamicBarSettings
import com.android.systemui.islandevents.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@SysUISingleton
class IslandEventRepository
@Inject
constructor(
    val media: MediaIslandManager,
    val connectivity: ConnectivityIslandManager,
    val system: SystemIslandManager,
    val notification: NotificationIslandManager,
    val appTracking: AppTrackingIslandManager,
    val torch: TorchIslandManager,
    val biometric: BiometricIslandManager,
    val smartspace: SmartspaceIslandManager,
    val aospChip: AospChipIslandManager,
    private val settings: AxDynamicBarSettings,
) {
    companion object {
        private const val TAG = "IslandEventRepository"
    }

    @Volatile private var listenersStarted = false

    private val _indicationEvents =
        MutableStateFlow<Map<String, IslandEvent.KeyguardIndication>>(emptyMap())

    fun updateIndicationEvent(event: IslandEvent.KeyguardIndication) {
        _indicationEvents.update { it + (event.indicationType.name to event) }
    }

    fun clearIndicationEvent(type: IslandEvent.KeyguardIndication.IndicationType) {
        _indicationEvents.update { it - type.name }
    }

    fun clearAllIndicationEvents() {
        _indicationEvents.value = emptyMap()
    }

    val events: Flow<List<IslandEvent>> = buildEventsFlow()

    private val disabled: Set<String>
        get() = when {
            settings.isEnabled.value && settings.isKeyguardEnabled.value ->
                settings.disabledEventTypes.value intersect settings.keyguardDisabledEventTypes.value
            settings.isKeyguardEnabled.value -> settings.keyguardDisabledEventTypes.value
            else -> settings.disabledEventTypes.value
        }

    private fun isTypeEnabled(typeId: String): Boolean =
        settings.isEventSourceNeeded(typeId)

    private fun isMediaSourceNeeded(): Boolean =
        isTypeEnabled("media") || settings.isKeyguardMusicPillEnabled.value

    fun startListening() {
        if (!listenersStarted) {
            listenersStarted = true
            Log.d(TAG, "Starting event listeners")
        }
        refreshListeners()
    }

    fun stopListening() {
        if (!listenersStarted) return
        listenersStarted = false
        Log.d(TAG, "Stopping event listeners")
        media.stopListening()
        connectivity.stopListening()
        system.stopListening()
        notification.stopListening()
        torch.stopListening()
        appTracking.stopListening()
        biometric.stopListening()
        smartspace.stopListening()
    }

    fun refreshListeners() {
        if (!listenersStarted) return
        syncDisabledTypes()

        if (isMediaSourceNeeded()) media.startListening()
        else media.stopListening()

        if (settings.isAnyBarEnabled()) notification.startListening()
        else notification.stopListening()

        if (isTypeEnabled("bluetooth")) connectivity.startBluetooth()
        else connectivity.stopBluetooth()
        if (isTypeEnabled("hotspot")) connectivity.startHotspot()
        else connectivity.stopHotspot()
        if (isTypeEnabled("vpn")) connectivity.startVpn()
        else connectivity.stopVpn()

        if (isTypeEnabled("charging")) system.startCharging()
        else system.stopCharging()
        if (isTypeEnabled("ringer")) system.startRinger()
        else system.stopRinger()
        if (isTypeEnabled("clipboard")) system.startClipboard()
        else system.stopClipboard()

        if (isTypeEnabled("app_switch")) appTracking.startListening()
        else appTracking.stopListening()
        if (isTypeEnabled("torch")) torch.startListening()
        else torch.stopListening()
        if (isTypeEnabled("biometric_unlock")) biometric.startListening()
        else biometric.stopListening()

        if (isTypeEnabled("media") || isTypeEnabled("sports")) smartspace.startListening()
        else smartspace.stopListening()
    }

    private fun syncDisabledTypes() {
        notification.disabledTypes = disabled
    }

    private fun buildEventsFlow(): Flow<List<IslandEvent>> {

        val sportsGroup = combine(
            smartspace.sportsEvents,
            notification.sportsEvents,
        ) { qlSports, notifSports ->
            qlSports + notifSports.filter { ns ->
                qlSports.none { qs ->
                    qs.team1Name.equals(ns.team1Name, ignoreCase = true) &&
                        qs.team2Name.equals(ns.team2Name, ignoreCase = true)
                }
            }
        }
        val promotedGroup = combine(
            notification.promotedOngoingEvents,
            sportsGroup,
        ) { promoted, sports ->
            promoted + sports
        }
        val highGroup = combine(
            notification.callEvents,
            torch.torchEvent,
            biometric.biometricEvent
        ) { call, t, bio ->
            call + listOfNotNull(t, bio)
        }
        val midGroup =
            combine(
                media.mediaEvent,
                connectivity.bluetoothEvent,
                connectivity.hotspotEvent,
                system.chargingEvent,
                notification.alarmEvent,
            ) { m, bt, hotspot, charging, alarm ->
                listOfNotNull(m, bt, hotspot, charging, alarm)
            }
        val lowGroupA =
            combine(
                notification.timerEvent,
                notification.stopwatchEvent,
                system.ringerEvent,
                connectivity.vpnEvent,
                system.clipboardEvent,
            ) { timer, stopwatch, ringer, vpn, clipboard ->
                listOfNotNull(timer, stopwatch, ringer, vpn, clipboard)
            }
        val lowGroup =
            combine(
                lowGroupA,
                appTracking.appSwitchEvent,
                notification.audioRecordingEvent,
                smartspace.nowPlayingEvent,
            ) { a, appSwitch, audioRec, nowPlaying ->
                a + listOfNotNull(appSwitch, audioRec, nowPlaying)
            }
        val transientGroup = combine(midGroup, lowGroup) { mid, low -> mid + low }

        val indicationGroup = _indicationEvents.map { it.values.toList() }

        val allEvents = combine(
            highGroup,
            transientGroup,
            promotedGroup,
            indicationGroup,
            aospChip.aospChipEvents,
        ) { high, transient, promoted, indication, aosp ->
            high + transient + promoted + indication + aosp
        }

        return allEvents.map { events ->
            events.sorted()
        }.distinctUntilChanged { old, new ->
            old.size == new.size && old.indices.all { i ->
                old[i].withoutDrawables() == new[i].withoutDrawables()
            }
        }
    }
}
