package com.android.systemui.statusbar.featurepods.popups.ui.viewmodel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.getValue
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.islandevents.data.source.AppTrackingIslandManager
import com.android.systemui.islandevents.data.source.BiometricIslandManager
import com.android.systemui.islandevents.data.source.ConnectivityIslandManager
import com.android.systemui.islandevents.data.source.NotificationIslandManager
import com.android.systemui.islandevents.data.source.SmartspaceIslandManager
import com.android.systemui.islandevents.data.source.SystemIslandManager
import com.android.systemui.islandevents.model.IslandEvent
import com.android.systemui.islandevents.model.RecordingState
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings
import com.android.systemui.statusbar.featurepods.popups.shared.SystemEventFeature
import com.android.systemui.statusbar.featurepods.popups.shared.SystemEventFeature.*
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.ChargingDetailModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Provider
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Owns an isolated session of the shared sources, never the AxDynamicBar UI/interactor. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SystemEventPopupChipsViewModel @AssistedInject constructor(
    @Application private val context: Context,
    private val systemProvider: Provider<SystemIslandManager>,
    private val connectivityProvider: Provider<ConnectivityIslandManager>,
    private val biometricProvider: Provider<BiometricIslandManager>,
    private val notificationsProvider: Provider<NotificationIslandManager>,
    private val appTrackingProvider: Provider<AppTrackingIslandManager>,
    private val smartspaceProvider: Provider<SmartspaceIslandManager>,
    private val zenModeController: com.android.systemui.statusbar.policy.ZenModeController,
    private val audioManager: android.media.AudioManager,
    private val mapper: SystemEventPopupMapper,
    private val userTracker: UserTracker,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
) : ExclusiveActivatable() {
    private val hydrator = Hydrator("SystemEventPopupChipsViewModel")

    private val users = callbackFlow {
        val callback = object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) {
                trySend(newUser)
            }
        }
        userTracker.addCallback(callback, Executor { it.run() })
        trySend(userTracker.userId)
        awaitClose { userTracker.removeCallback(callback) }
    }

    private fun chargingDetails(): Flow<List<ChargingDetailModel>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                    trySend(mapper.toChargingDetails(intent))
                }
            }
        }
        val stickyIntent = context.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        trySend(stickyIntent?.let(mapper::toChargingDetails).orEmpty())
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    val chips: List<PopupChipModel.Shown> by hydrator.hydratedStateOf(
        traceName = "systemEventChips",
        initialValue = emptyList(),
        source = users.flatMapLatest {
            DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
                context, Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND, defaultValue = false,
            )
                .distinctUntilChanged()
                .flatMapLatest { enabled ->
                    if (enabled) events()
                    else flowOf(emptyList())
                }.onStart { emit(emptyList()) }
        },
    )

    private fun events(): Flow<List<PopupChipModel.Shown>> = channelFlow {
        val system = systemProvider.get()
        val connectivity = connectivityProvider.get()
        val biometric = biometricProvider.get()
        val notifications = notificationsProvider.get()
        val appTracking = appTrackingProvider.get()
        val smartspace = smartspaceProvider.get()

        val features = SystemEventFeature.values().toList()
        val enabledFeatures = combine(features.map { feature ->
            DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled(
                context, feature.settingKey, defaultValue = false,
            )
        }) { enabled ->
            features.filterIndexed { index, _ -> enabled[index] }.toSet()
        }.distinctUntilChanged().stateIn(this, SharingStarted.Eagerly, emptySet())

        val chargingDetailsFlow = enabledFeatures.map { BATTERY in it }
            .distinctUntilChanged()
            .flatMapLatest { enabled ->
                if (enabled) chargingDetails() else flowOf(emptyList())
            }

        var previousHeadsUp: Int? = null
        fun updateHeadsUp(suppress: Boolean) {
            val resolver = context.contentResolver
            val key = Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED
            if (suppress) {
                if (previousHeadsUp == null) {
                    previousHeadsUp = Settings.Global.getInt(resolver, key, 1)
                }
                Settings.Global.putInt(resolver, key, 0)
            } else {
                previousHeadsUp?.let { previous ->
                    if (Settings.Global.getInt(resolver, key, 1) == 0) {
                        Settings.Global.putInt(resolver, key, previous)
                    }
                }
                previousHeadsUp = null
            }
        }

        val expiryJobs = mutableMapOf<String, Job>()
        fun expire(event: IslandEvent, duration: Long, clear: () -> Unit) {
            launch {
                expiryJobs.remove(event.id)?.cancel()
                expiryJobs[event.id] = launch {
                    delay(duration)
                    clear()
                    expiryJobs.remove(event.id)
                }
            }
        }
        system.storageNamespace = "_dynamic_island_${userTracker.userId}"
        system.persistWhilePluggedIn = true
        system.onRingerChanged = { event -> expire(event, 2_500L, system::clearRinger) }
        system.onClipboardCopied = { event -> expire(event, 10_000L, system::clearClipboard) }
        biometric.onBiometricUnlock = { event -> expire(event, 10_000L, biometric::clear) }
        notifications.updateVisibleNotifications = true
        notifications.acceptNotification = { sbn ->
            sbn.userId == UserHandle.USER_ALL || lockscreenUserManager.isCurrentProfile(sbn.userId)
        }

        notifications.onNotificationPosted = { event ->
            val previous = notifications.notificationEvents.value.firstOrNull { it.id == event.id }
            if (NOTIFICATIONS in enabledFeatures.value &&
                (previous != null || !com.android.systemui.islandevents.domain.NotificationAlertPolicy.shouldSuppress(
                    event, enabled = NOTIFICATIONS in enabledFeatures.value, zenMode = zenModeController.zen,
                    ringerMode = audioManager.ringerMode))) {
                val update = if (previous != null &&
                    (event.progress >= 0 || event.isProgressIndeterminate)) {
                    event.copy(createdAt = previous.createdAt)
                } else event
                notifications.coalesceNotification(update)
            }
            if (event.progress < 0 && !event.isProgressIndeterminate) {
                expire(event, 8_000L) { notifications.dismissNotification(event) }
            } else {
                launch { expiryJobs.remove(event.id)?.cancel() }
            }
        }
        val connectivityEvents = combine(
            system.chargingEvent, system.ringerEvent, system.clipboardEvent,
            connectivity.bluetoothEvent, connectivity.hotspotEvent,
        ) { battery, ringer, clipboard, bluetooth, hotspot ->
            listOfNotNull(battery, ringer, clipboard, bluetooth, hotspot)
        }
        val statusEvents = combine(
            connectivity.vpnEvent, biometric.biometricEvent, appTracking.appSwitchEvent,
            notifications.timerEvent, notifications.audioRecordingEvent,
        ) { vpn, unlock, apps, timer, recording -> listOfNotNull(vpn, unlock, apps, timer, recording) }
        val notificationEvents = combine(
            notifications.callEvents, notifications.notificationEvents,
            notifications.promotedOngoingEvents, notifications.nowPlayingEvent,
            smartspace.nowPlayingEvent,
        ) { calls, alerts, ongoing, nowPlaying, recognized ->
            calls + alerts.filter { alert -> calls.none { it.sbn.key == alert.sbn.key } } +
                ongoing + listOfNotNull(recognized ?: nowPlaying)
        }
        try {
            launch(start = CoroutineStart.UNDISPATCHED) {
                notifications.audioRecordingEvent.collect { event ->
                    if (event?.state == RecordingState.SAVED) {
                        expire(event, 5_000L, notifications::clearAudioRecording)
                    }
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                combine(connectivityEvents, statusEvents, notificationEvents, enabledFeatures,
                    chargingDetailsFlow) { a, b, c, enabled, details ->
                    (a + b + c).filter { SystemEventFeature.forEvent(it) in enabled }.let { events ->
                        if (events.any { it.behavior.autoShowsIsland }) events.sorted() else emptyList()
                    } to details
                }.collect { (events, details) ->
                    send(events.mapNotNull { event ->
                        mapper.toChip(event, connectivity::disconnectBluetooth,
                            appTracking::switchToApp, system::setRingerMode,
                            notificationCount = events.count { it is IslandEvent.Notification },
                            chargingDetails = details)?.let { chip ->
                            fun expireNotification() {
                                if (event is IslandEvent.Notification && event.progress < 0 &&
                                    !event.isProgressIndeterminate) {
                                    expire(event, 5_000L) {
                                        if (notifications.notificationEvents.value.any {
                                            it.id == event.id && it.createdAt == event.createdAt
                                        }) notifications.dismissNotification(event)
                                    }
                                }
                            }
                            chip.copy(
                                onAutoPopupShown = {
                                    if (event is IslandEvent.BiometricUnlock)
                                        expire(event, chip.autoPopupDurationMs, biometric::clear)
                                },
                                onPopupShown = {
                                    if (event is IslandEvent.Notification)
                                        launch { expiryJobs.remove(event.id)?.cancel() }
                                },
                                onPopupHidden = { expireNotification() },
                            )
                        }
                    })
                }
            }
            launch {
                enabledFeatures.collect { enabled ->
                    updateHeadsUp(NOTIFICATIONS in enabled)
                    if (BATTERY in enabled) system.startCharging() else system.stopCharging()
                    if (RINGER in enabled) system.startRinger() else system.stopRinger()
                    if (CLIPBOARD in enabled) system.startClipboard() else system.stopClipboard()
                    if (BLUETOOTH in enabled) connectivity.startBluetooth() else connectivity.stopBluetooth()
                    if (HOTSPOT in enabled) connectivity.startHotspot() else connectivity.stopHotspot()
                    if (VPN in enabled) connectivity.startVpn() else connectivity.stopVpn()
                    if (UNLOCK in enabled) biometric.startListening() else biometric.stopListening()
                    if (RECENT_APPS in enabled) appTracking.startListening() else appTracking.stopListening()
                    if (NOW_PLAYING in enabled) smartspace.startListening() else smartspace.stopListening()

                    val notificationTypes = enabled.mapNotNull { it.notificationType }.toSet()
                    notifications.disabledTypes = setOf("alarm", "stopwatch", "sports") +
                        (features.mapNotNull { it.notificationType }.toSet() - notificationTypes)
                    if (notificationTypes.isNotEmpty()) notifications.startListening()
                    else notifications.stopListening()
                }
            }
            awaitCancellation()
        } finally {
            updateHeadsUp(false)
            expiryJobs.values.forEach { it.cancel() }
            system.onChargingStarted = null
            system.onRingerChanged = null
            system.onClipboardCopied = null
            biometric.onBiometricUnlock = null
            notifications.onNotificationPosted = null
            system.stopCharging()
            system.stopRinger()
            system.stopClipboard()
            connectivity.stopBluetooth()
            connectivity.stopHotspot()
            connectivity.stopVpn()
            biometric.stopListening()
            notifications.stopListening()
            appTracking.stopListening()
            smartspace.stopListening()
        }
    }

    override suspend fun onActivated(): Nothing = hydrator.activate()

    @AssistedFactory
    interface Factory {
        fun create(): SystemEventPopupChipsViewModel
    }
}
