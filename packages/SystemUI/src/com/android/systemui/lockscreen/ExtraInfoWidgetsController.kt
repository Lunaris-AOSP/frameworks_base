/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.lockscreen

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.HotspotController
import com.android.systemui.util.ProgressImageView
import javax.inject.Inject
import org.lunaris.extrainfowidgets.ExtraInfoCatalog
import org.lunaris.extrainfowidgets.ExtraInfoGridView
import org.lunaris.extrainfowidgets.ExtraInfoLayout
import org.lunaris.extrainfowidgets.InfoAppearance
import org.lunaris.extrainfowidgets.InfoData

class ExtraInfoWidgetsController
@Inject
constructor(
    private val users: UserTracker,
    private val status: StatusBarStateController,
    private val notifications: NotifPipeline,
    private val privacy: NotificationLockscreenUserManager,
    private val starter: ActivityStarter,
    private val bluetooth: BluetoothController,
    private val flashlight: FlashlightController,
    private val hotspot: HotspotController,
    private val network: NetworkController,
) {
    fun createView(context: Context): View = Container(context)

    private inner class Container(context: Context) : FrameLayout(context) {
        private val main = Handler(Looper.getMainLooper())
        private val legacy =
            LayoutInflater.from(context).inflate(R.layout.keyguard_info_widgets, this, false)
        private val extra = ExtraInfoGridView(context)
        private val catalog = ExtraInfoCatalog.load(context)
        private var source: ExtraInfoDataSource? = null
        private var sourceKey: String? = null
        private var data = InfoData()
        private var enabled = false
        private var attached = false
        private var switchingUser = false
        private var tileKinds = emptySet<String>()
        private var showsNotifications = false
        private val settings =
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    updateSettings()
                }
            }
        private val userCallback =
            object : UserTracker.Callback {
                override fun onBeforeUserSwitching(newUser: Int) {
                    switchingUser = true
                    source?.stop()
                    source = null
                    sourceKey = null
                    data = InfoData()
                    extra.data = data
                    visibility = GONE
                }

                override fun onUserChanged(newUser: Int, userContext: Context) {
                    switchingUser = false
                    source?.stop()
                    source = null
                    sourceKey = null
                    data = InfoData()
                    updateSettings()
                }
            }
        private val privacyCallback =
            NotificationLockscreenUserManager.NotificationStateChangedListener {
                main.post { if (attached) render() }
            }
        private val statusCallback =
            object : StatusBarStateController.StateListener {
                override fun onDozingChanged(dozing: Boolean) {
                    updateSettings()
                }

                override fun onStateChanged(newState: Int) {
                    updateAwake()
                    render()
                }
            }
        private val notificationCallback =
            object : NotifCollectionListener {
                override fun onEntryAdded(entry: NotificationEntry) {
                    main.post { if (attached) render() }
                }

                override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                    main.post { if (attached) render() }
                }

                override fun onEntryUpdated(entry: NotificationEntry) {
                    main.post { if (attached) render() }
                }
            }
        private val torchCallback =
            object : FlashlightController.FlashlightListener {
                override fun onFlashlightChanged(enabled: Boolean) {
                    main.post { if (attached) render() }
                }

                override fun onFlashlightError() {
                    main.post { if (attached) render() }
                }

                override fun onFlashlightAvailabilityChanged(available: Boolean) {
                    main.post { if (attached) render() }
                }
            }

        init {
            addView(legacy, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(
                extra,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (12 * resources.displayMetrics.density).toInt()
                },
            )
            extra.onAction = ::performAction
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            attached = true
            switchingUser = users.isUserSwitching
            KEYS.forEach {
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(it),
                    false,
                    settings,
                    UserHandle.USER_ALL,
                )
            }
            users.addCallback(userCallback, context.mainExecutor)
            status.addCallback(statusCallback)
            notifications.addCollectionListener(notificationCallback)
            privacy.addNotificationStateChangedListener(privacyCallback)
            flashlight.addCallback(torchCallback)
            updateSettings()
        }

        override fun onDetachedFromWindow() {
            attached = false
            source?.stop()
            source = null
            sourceKey = null
            context.contentResolver.unregisterContentObserver(settings)
            users.removeCallback(userCallback)
            status.removeCallback(statusCallback)
            notifications.removeCollectionListener(notificationCallback)
            privacy.removeNotificationStateChangedListener(privacyCallback)
            flashlight.removeCallback(torchCallback)
            main.removeCallbacksAndMessages(null)
            super.onDetachedFromWindow()
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            if (attached) updateAwake()
        }

        private fun integer(key: String, default: Int) =
            Settings.System.getIntForUser(context.contentResolver, key, default, users.userId)

        private fun string(key: String) =
            Settings.System.getStringForUser(context.contentResolver, key, users.userId)

        private fun updateSettings() {
            if (!attached || switchingUser) return
            enabled = integer(ExtraInfoLayout.ENABLED, 0) == 1
            val oldEnabled = integer("lockscreen_info_widgets_enabled", 0) == 1
            val layout = ExtraInfoLayout.parse(string(ExtraInfoLayout.LAYOUT), catalog)
            val selected = catalog.filter { it.id in layout.rows.flatten() }
            val layers =
                selected.flatMap { widget ->
                    val array = widget.design.getJSONArray("layers")
                    (0 until array.length()).map { array.getJSONObject(it) }
                }
            tileKinds =
                layers
                    .map { it.optString("tile") }
                    .filter { it.isNotEmpty() && !it.startsWith("MEDIA") }
                    .toSet()
            showsNotifications = layers.any { it.optString("type").startsWith("NOTIFICATION_") }
            extra.selection = layout
            extra.appearance =
                InfoAppearance(
                    string("lockscreen_widgets_color_mode") ?: "default",
                    integer("lockscreen_widgets_custom_color", -1),
                    integer("lockscreen_widgets_transparency", 100),
                    integer(ExtraInfoLayout.BLUR, 0) == 1,
                    status.isDozing,
                )
            listOf(
                    R.id.battery_progress,
                    R.id.memory_progress,
                    R.id.temperature_progress,
                    R.id.volume_progress,
                )
                .forEach { legacy.findViewById<ProgressImageView>(it)?.refreshSettings() }
            legacy.visibility = if (oldEnabled && !enabled) VISIBLE else GONE
            extra.visibility = if (enabled) VISIBLE else GONE
            visibility =
                if ((enabled && layout.rows.any { it.isNotEmpty() }) || (!enabled && oldEnabled))
                    VISIBLE
                else GONE
            val key =
                if (enabled && layout.rows.any { it.isNotEmpty() })
                    "${users.userId}:${layout.encode()}"
                else null
            if (sourceKey != key) {
                source?.stop()
                source = null
                sourceKey = key
                data = InfoData()
                if (key != null) {
                    val sourceUser = users.userId
                    source =
                        ExtraInfoDataSource(users.userContext, users.userId, selected) { snapshot ->
                                if (
                                    !switchingUser &&
                                        !users.isUserSwitching &&
                                        sourceUser == users.userId
                                ) {
                                    data = snapshot
                                    render()
                                }
                            }
                            .also { it.start() }
                }
            }
            updateAwake()
            render()
        }

        private fun updateAwake() {
            source?.setAwake(isShown && status.state == StatusBarState.KEYGUARD && !status.isDozing)
        }

        private fun render() {
            if (!attached || !enabled || switchingUser || users.isUserSwitching) return
            val user = users.userId
            val userContext = users.userContext
            val tiles =
                tileKinds.associateWith { tile ->
                    runCatching {
                            when (tile) {
                                "WIFI" ->
                                    if (
                                        userContext
                                            .getSystemService(WifiManager::class.java)
                                            ?.isWifiEnabled == true
                                    )
                                        1
                                    else 0
                                "BLUETOOTH" -> if (bluetooth.isBluetoothEnabled) 1 else 0
                                "FLASHLIGHT" -> if (flashlight.isEnabled) 1 else 0
                                "HOTSPOT" -> if (hotspot.isHotspotEnabled) 1 else 0
                                "MOBILE_DATA" ->
                                    if (network.mobileDataController.isMobileDataEnabled) 1 else 0
                                "RINGER" ->
                                    when (
                                        userContext
                                            .getSystemService(AudioManager::class.java)
                                            ?.ringerMode
                                    ) {
                                        AudioManager.RINGER_MODE_NORMAL -> 1
                                        AudioManager.RINGER_MODE_VIBRATE -> 2
                                        else -> 0
                                    }
                                "DND" ->
                                    when (
                                        userContext
                                            .getSystemService(NotificationManager::class.java)
                                            ?.currentInterruptionFilter
                                    ) {
                                        null,
                                        NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
                                        NotificationManager.INTERRUPTION_FILTER_ALL -> 0
                                        else -> 1
                                    }
                                else -> 0
                            }
                        }
                        .getOrDefault(0)
                }
            val count =
                if (
                    showsNotifications &&
                        privacy.shouldShowLockscreenNotifications() &&
                        privacy.userAllowsNotificationsInPublic(user)
                )
                    notifications.allNotifs.count { entry ->
                        (entry.sbn.userId == user || entry.sbn.userId == UserHandle.USER_ALL) &&
                            (entry.sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) ==
                                0 &&
                            entry.sbn.notification.visibility != Notification.VISIBILITY_SECRET
                    }
                else 0
            val private =
                !privacy.isLockscreenPublicMode(user) ||
                    privacy.userAllowsPrivateNotificationsInPublic(user)
            extra.data =
                data.copy(
                    tiles = tiles,
                    notifications = count,
                    eventTitle = data.eventTitle.takeIf { private },
                    eventTime = data.eventTime.takeIf { private },
                    mediaTitle = data.mediaTitle.takeIf { private },
                    mediaArtist = data.mediaArtist.takeIf { private },
                    art = data.art.takeIf { private },
                )
        }

        private fun performAction(action: String) {
            if (!enabled || status.isDozing || switchingUser || users.isUserSwitching) return
            if (action.startsWith("MEDIA")) {
                source?.mediaAction(action)
                return
            }
            if (action == "FLASHLIGHT") {
                flashlight.setFlashlight(!flashlight.isEnabled)
                return
            }
            val user = users.userId
            starter.dismissKeyguardThenExecute(
                {
                    if (
                        users.userId == user &&
                            !users.isUserSwitching &&
                            integer(ExtraInfoLayout.ENABLED, 0) == 1
                    ) {
                        runCatching {
                            val userContext = users.userContext
                            when (action) {
                                "WIFI" ->
                                    network.setWifiEnabled(
                                        !userContext
                                            .getSystemService(WifiManager::class.java)
                                            .isWifiEnabled
                                    )
                                "BLUETOOTH" ->
                                    bluetooth.setBluetoothEnabled(!bluetooth.isBluetoothEnabled)
                                "HOTSPOT" -> hotspot.setHotspotEnabled(!hotspot.isHotspotEnabled)
                                "MOBILE_DATA" ->
                                    network.mobileDataController.let {
                                        it.setMobileDataEnabled(!it.isMobileDataEnabled)
                                    }
                                "RINGER" ->
                                    userContext.getSystemService(AudioManager::class.java).let {
                                        it.ringerMode =
                                            when (it.ringerMode) {
                                                AudioManager.RINGER_MODE_NORMAL ->
                                                    AudioManager.RINGER_MODE_VIBRATE
                                                AudioManager.RINGER_MODE_VIBRATE ->
                                                    AudioManager.RINGER_MODE_SILENT
                                                else -> AudioManager.RINGER_MODE_NORMAL
                                            }
                                    }
                                "DND" ->
                                    userContext
                                        .getSystemService(NotificationManager::class.java)
                                        .let {
                                            it.setInterruptionFilter(
                                                if (
                                                    it.currentInterruptionFilter ==
                                                        NotificationManager.INTERRUPTION_FILTER_ALL
                                                )
                                                    NotificationManager.INTERRUPTION_FILTER_PRIORITY
                                                else NotificationManager.INTERRUPTION_FILTER_ALL
                                            )
                                        }
                            }
                        }
                        main.postDelayed(
                            {
                                if (attached) {
                                    render()
                                    source?.refresh()
                                }
                            },
                            400,
                        )
                    }
                    false
                },
                null,
                true,
            )
        }
    }

    companion object {
        private val KEYS =
            listOf(
                ExtraInfoLayout.ENABLED,
                ExtraInfoLayout.LAYOUT,
                ExtraInfoLayout.BLUR,
                "lockscreen_info_widgets_enabled",
                "lockscreen_widgets_color_mode",
                "lockscreen_widgets_custom_color",
                "lockscreen_widgets_transparency",
            )
    }
}
