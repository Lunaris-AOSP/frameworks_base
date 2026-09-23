package com.android.systemui.statusbar.featurepods.popups.ui.viewmodel

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.BatteryManager
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import com.android.settingslib.fuelgauge.BatteryStatus
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.islandevents.model.IslandEvent
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.featurepods.popups.shared.toActivityLaunchAction
import com.android.systemui.statusbar.featurepods.popups.shared.toSendAction
import com.android.systemui.statusbar.featurepods.popups.ui.model.BluetoothBatteryComponent
import com.android.systemui.statusbar.featurepods.popups.ui.model.BluetoothBatteryModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.ChargingDetailModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.ChipIcon
import com.android.systemui.statusbar.featurepods.popups.ui.model.ColorsModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.SystemEventKind
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject

/** Presentation adapter owned by featurepods; shares no AxDynamicBar rendering code. */
class SystemEventPopupMapper @Inject constructor(
    @Application private val context: Context,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardStateController: KeyguardStateController,
) {
    private fun loadAppIcon(sbn: StatusBarNotification): Drawable? = try {
        val appContext = if (sbn.user == UserHandle.ALL) context
            else context.createContextAsUser(sbn.user, 0)
        appContext.packageManager.getApplicationIcon(sbn.packageName)
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        null
    }

    fun toChargingDetails(intent: Intent): List<ChargingDetailModel> {
        val status = BatteryStatus(intent)
        if (!status.isPluggedIn) return emptyList()

        val divider = context.resources.getInteger(R.integer.config_currentInfoDivider).coerceAtLeast(1)
        val locale = context.resources.configuration.locales[0]
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_MAX_CHARGING_VOLTAGE, -1)
        return buildList {
            val currentMa = status.maxChargingCurrent / divider
            if (currentMa > 0f) {
                val useAmps = currentMa >= 1000f
                add(ChargingDetailModel(
                    label = context.getString(R.string.dynamic_island_charging_current),
                    value = String.format(locale, if (useAmps) "%.1f" else "%.0f",
                        if (useAmps) currentMa / 1000f else currentMa),
                    unit = if (useAmps) "A" else "mA",
                ))
            }
            if (status.maxChargingWattage > 0f && voltage > 0) {
                add(ChargingDetailModel(
                    label = context.getString(R.string.dynamic_island_charging_power),
                    value = String.format(locale, "%.1f", status.maxChargingWattage / divider / 1000f),
                    unit = "W",
                ))
            }
            if (voltage > 0) {
                add(ChargingDetailModel(
                    label = context.getString(R.string.dynamic_island_charging_voltage),
                    value = String.format(locale, "%.1f", status.maxChargingVoltage / 1000000f),
                    unit = "V",
                ))
            }
            if (status.temperature > 0f) {
                add(ChargingDetailModel(
                    label = context.getString(R.string.dynamic_island_charging_temperature),
                    value = String.format(locale, "%.1f", status.temperature / 10f),
                    unit = "°C",
                ))
            }
        }
    }

    fun toChip(
        event: IslandEvent,
        disconnect: (String) -> Unit,
        switchApp: (Int) -> Unit,
        setRinger: (Int) -> Unit,
        notificationCount: Int = 1,
        chargingDetails: List<ChargingDetailModel> = emptyList(),
    ): PopupChipModel.Shown? {
        val kind = when (event) {
            is IslandEvent.Charging -> SystemEventKind.Charging
            is IslandEvent.BiometricUnlock -> SystemEventKind.Unlock
            is IslandEvent.Bluetooth -> SystemEventKind.Bluetooth
            is IslandEvent.Hotspot -> SystemEventKind.Hotspot
            is IslandEvent.Vpn -> SystemEventKind.Vpn
            is IslandEvent.RingerMode -> SystemEventKind.Ringer
            is IslandEvent.Clipboard -> SystemEventKind.Clipboard
            is IslandEvent.Call -> SystemEventKind.Call
            is IslandEvent.Notification -> SystemEventKind.Notification
            is IslandEvent.PromotedOngoing -> SystemEventKind.Ongoing
            is IslandEvent.Timer -> SystemEventKind.Timer
            is IslandEvent.AudioRecording -> SystemEventKind.Recording
            is IslandEvent.NowPlaying -> SystemEventKind.NowPlaying
            is IslandEvent.AppSwitch -> SystemEventKind.RecentApps
            else -> return null
        }
        var prominentText: String? = null
        var bluetoothBatteries = emptyList<BluetoothBatteryModel>()
        var appName: String? = null
        var pulse = false
        var image: Icon? = null
        var icon: Drawable? = null
        var chipDrawable: Drawable? = null
        var iconRes = android.R.drawable.ic_dialog_info
        var progress: Float? = null
        var timerEndTimeMs: Long? = null
        var timerPaused = false
        var indeterminate = false
        val actions = mutableListOf<PopupActionModel>()
        fun notificationActions(items: List<IslandEvent.NotificationAction>) {
            items.forEach { item ->
                item.action.actionIntent.toSendAction()?.let {
                    actions += PopupActionModel(item.label.toString(), it)
                }
            }
        }
        fun open(intent: PendingIntent?) {
            intent.toActivityLaunchAction(activityStarter, activityIntentHelper,
                lockscreenUserManager, keyguardStateController)?.let {
                actions += PopupActionModel(context.getString(R.string.dynamic_island_event_open), it)
            }
        }
        val (title, text) = when (event) {
            is IslandEvent.Charging -> {
                iconRes = R.drawable.dynamic_island_charging
                prominentText = "${event.level}%"
                pulse = event.isCharging
                progress = event.level / 100f
                context.getString(if (event.isWireless) R.string.dynamic_island_event_wireless_charging
                    else R.string.dynamic_island_event_charging) to
                    listOfNotNull(event.timeRemaining,
                        context.getString(R.string.dynamic_island_event_battery_saver).takeIf { event.isPowerSave })
                        .joinToString(" · ")
            }
            is IslandEvent.BiometricUnlock -> {
                iconRes = R.drawable.dynamic_island_unlock
                context.getString(R.string.dynamic_island_event_unlocked) to event.sourceName
            }
            is IslandEvent.Bluetooth -> {
                prominentText = "${event.batteryLevel}%".takeIf { event.batteryLevel >= 0 }
                progress = event.batteryLevel.takeIf { it >= 0 }?.div(100f)
                icon = event.deviceIcon
                image = event.deviceImage?.let { Icon.Loaded(it, null) }
                bluetoothBatteries = listOfNotNull(
                    event.leftBatteryLevel?.takeIf { it in 0..100 }?.let {
                        BluetoothBatteryModel(
                            label = context.getString(R.string.dynamic_island_bluetooth_left_earbud),
                            level = it,
                            component = BluetoothBatteryComponent.LeftEarbud,
                        )
                    },
                    event.rightBatteryLevel?.takeIf { it in 0..100 }?.let {
                        BluetoothBatteryModel(
                            label = context.getString(R.string.dynamic_island_bluetooth_right_earbud),
                            level = it,
                            component = BluetoothBatteryComponent.RightEarbud,
                        )
                    },
                    event.caseBatteryLevel?.takeIf { it in 0..100 }?.let {
                        BluetoothBatteryModel(
                            label = context.getString(R.string.dynamic_island_bluetooth_case),
                            level = it,
                            component = BluetoothBatteryComponent.Case,
                        )
                    },
                )
                iconRes = R.drawable.dynamic_island_headphones
                if (event.address.isNotEmpty()) actions += PopupActionModel(
                    context.getString(R.string.dynamic_island_event_disconnect), { disconnect(event.address) })
                event.deviceName to event.deviceTypeLabel.ifBlank { context.getString(R.string.dynamic_island_event_connected) }
            }
            is IslandEvent.Hotspot -> {
                iconRes = R.drawable.dynamic_island_hotspot
                context.getString(R.string.dynamic_island_event_hotspot) to
                    context.getString(R.string.dynamic_island_event_connected_devices, event.numDevices)
            }
            is IslandEvent.Vpn -> context.getString(R.string.dynamic_island_event_vpn) to
                context.getString(if (event.isValidated) R.string.dynamic_island_event_connected
                    else R.string.dynamic_island_event_connecting)
            is IslandEvent.RingerMode -> {
                listOf(R.string.dynamic_island_event_ring to AudioManager.RINGER_MODE_NORMAL,
                    R.string.dynamic_island_event_vibrate to AudioManager.RINGER_MODE_VIBRATE,
                    R.string.dynamic_island_event_silent to AudioManager.RINGER_MODE_SILENT).forEach { (label, mode) ->
                    actions += PopupActionModel(context.getString(label), { setRinger(mode) }, emphasized = mode == event.mode)
                }
                iconRes = when (event.mode) {
                    AudioManager.RINGER_MODE_VIBRATE -> R.drawable.dynamic_island_vibrate
                    AudioManager.RINGER_MODE_SILENT -> R.drawable.dynamic_island_silent
                    else -> R.drawable.dynamic_island_ringer
                }
                context.getString(when (event.mode) {
                    AudioManager.RINGER_MODE_VIBRATE -> R.string.dynamic_island_event_vibrate
                    AudioManager.RINGER_MODE_SILENT -> R.string.dynamic_island_event_silent
                    else -> R.string.dynamic_island_event_ring
                }) to ""
            }
            is IslandEvent.Clipboard -> {
                iconRes = R.drawable.dynamic_island_clipboard
                context.getString(R.string.dynamic_island_event_clipboard) to event.preview
            }
            is IslandEvent.Call -> {
                icon = event.callerPhoto ?: event.appIcon
                iconRes = android.R.drawable.sym_action_call
                notificationActions(event.actions)
                open(event.sbn.notification.contentIntent)
                (event.callerName ?: event.number ?: context.getString(R.string.dynamic_island_event_call)) to
                    (event.sbn.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty())
            }
            is IslandEvent.Notification -> {
                appName = event.appName
                chipDrawable = loadAppIcon(event.sbn)
                iconRes = R.drawable.dynamic_island_notification
                image = event.notificationImage?.let { Icon.Loaded(it, null) }
                indeterminate = event.isProgressIndeterminate
                icon = event.senderIcon ?: event.appIcon
                notificationActions(event.actions)
                open(event.sbn.notification.contentIntent)
                if (event.progress >= 0 && event.progressMax > 0) progress = event.progress.toFloat() / event.progressMax
                (event.title ?: event.appName) to event.text.orEmpty()
            }
            is IslandEvent.PromotedOngoing -> {
                iconRes = R.drawable.dynamic_island_notification
                appName = event.appName
                chipDrawable = loadAppIcon(event.sbn)
                indeterminate = event.isIndeterminate
                icon = event.appIcon
                notificationActions(event.actions)
                open(event.sbn.notification.contentIntent)
                progress = event.progress.takeIf { it >= 0f }
                event.title.ifBlank { event.appName } to event.text
            }
            is IslandEvent.Timer -> {
                icon = event.appIcon
                notificationActions(event.actions)
                timerEndTimeMs = event.endTimeMs.takeIf { it > 0L }
                timerPaused = event.isPaused
                context.getString(R.string.dynamic_island_event_timer) to event.label
            }
            is IslandEvent.AudioRecording -> {
                pulse = event.state == com.android.systemui.islandevents.model.RecordingState.RECORDING
                prominentText = context.getString(when (event.state) {
                    com.android.systemui.islandevents.model.RecordingState.RECORDING -> R.string.dynamic_island_event_recording
                    com.android.systemui.islandevents.model.RecordingState.PAUSED -> R.string.dynamic_island_event_paused
                    com.android.systemui.islandevents.model.RecordingState.SAVED -> R.string.dynamic_island_event_saved
                })
                icon = event.appIcon
                notificationActions(event.actions)
                context.getString(R.string.dynamic_island_event_recording) to event.appName
            }
            is IslandEvent.NowPlaying -> {
                icon = event.appIcon
                notificationActions(event.actions)
                open(event.sbn?.notification?.contentIntent)
                event.songTitle to event.artist
            }
            is IslandEvent.AppSwitch -> {
                val previous = event.previousApp ?: event.recentApps.firstOrNull() ?: return null
                icon = previous.appIcon
                iconRes = R.drawable.dynamic_island_recent_apps
                event.recentApps.take(3).forEach { app ->
                    actions += PopupActionModel(app.appName, { switchApp(app.taskId) })
                }
                context.getString(R.string.dynamic_island_event_recent_apps) to previous.appName
            }
            else -> return null
        }
        val compactIcon = when (event) {
            is IslandEvent.Bluetooth -> Icon.Resource(R.drawable.dynamic_island_bluetooth, null)
            is IslandEvent.BiometricUnlock, is IslandEvent.Call,
            is IslandEvent.Hotspot, is IslandEvent.RingerMode, is IslandEvent.AppSwitch ->
                Icon.Resource(iconRes, null)
            is IslandEvent.Notification -> if (progress == null && !indeterminate)
                Icon.Resource(R.drawable.dynamic_island_notification, null)
                else (chipDrawable ?: icon)?.let { Icon.Loaded(it, null) } ?: Icon.Resource(iconRes, null)
            else -> (chipDrawable ?: icon)?.let { Icon.Loaded(it, null) } ?: Icon.Resource(iconRes, null)
        }
        val description = listOfNotNull(title, text, prominentText).distinct().filter { it.isNotBlank() }.joinToString(": ")
        return PopupChipModel.Shown(
            chipId = PopupChipId.SystemEvent(event.id),
            icons = listOf(ChipIcon(
                icon = compactIcon,
                tint = compactIcon is Icon.Resource,
            )),
            chipText = when (event) {
                is IslandEvent.Charging -> "${event.level}%"
                is IslandEvent.Call, is IslandEvent.BiometricUnlock,
                is IslandEvent.Clipboard -> null
                is IslandEvent.Notification -> when {
                    indeterminate -> null
                    progress != null -> "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
                    else -> notificationCount.toString()
                }
                is IslandEvent.PromotedOngoing -> if (indeterminate) null
                    else progress?.let { "${(it.coerceIn(0f, 1f) * 100).toInt()}%" }
                is IslandEvent.Bluetooth -> null
                is IslandEvent.Hotspot -> event.numDevices.toString()
                is IslandEvent.AppSwitch -> event.recentApps.size.toString()
                else -> title
            },

            callStartTimeMs = (event as? IslandEvent.Call)?.takeIf {
                it.callType == "Phone:active"
            }?.callStartTimeMs,
            autoPopupRequest = when (event) {
                is IslandEvent.Call -> event.callStartTimeMs.takeIf { event.callType == "Phone:incoming" }
                is IslandEvent.BiometricUnlock -> event.createdAt
                is IslandEvent.Notification -> event.createdAt
                else -> null
            },
            autoPopupDurationMs = when (event) {
                is IslandEvent.BiometricUnlock -> 2_000L
                is IslandEvent.Notification -> 4_500L
                else -> 0L
            },
            colors = ColorsModel.DynamicIsland,
            contentDescription = description,
            popupContent = PopupContentModel.SystemEvent(
                kind = kind,
                title = title,
                text = text,
                actions = actions,
                progress = progress?.coerceIn(0f, 1f)?.takeIf { bluetoothBatteries.isEmpty() },
                callStartTimeMs = (event as? IslandEvent.Call)?.takeIf {
                    it.callType == "Phone:active"
                }?.callStartTimeMs,
                timerEndTimeMs = timerEndTimeMs,
                timerEndElapsedRealtimeMs = (event as? IslandEvent.Timer)?.endElapsedRealtimeMs,
                timerRemainingMs = (event as? IslandEvent.Timer)?.remainingMs,
                timerOriginalDurationMs = (event as? IslandEvent.Timer)?.originalDurationMs,
                timerPaused = timerPaused,
                indeterminate = indeterminate,
                icon = if (event is IslandEvent.RingerMode) Icon.Resource(iconRes, null)
                    else icon?.let { Icon.Loaded(it, null) },
                appName = appName,
                prominentText = prominentText.takeIf { bluetoothBatteries.isEmpty() },
                pulse = pulse,
                image = image,
                bluetoothBatteries = bluetoothBatteries,
                chargingDetails = chargingDetails.takeIf { event is IslandEvent.Charging }.orEmpty(),
            ),
        )
    }
}
