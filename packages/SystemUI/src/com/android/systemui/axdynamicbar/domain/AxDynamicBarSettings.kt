package com.android.systemui.axdynamicbar.domain

import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.provider.Settings.Global
import android.service.notification.StatusBarNotification
import com.android.systemui.islandevents.shared.CallNotificationInfo
import com.android.systemui.islandevents.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.EVENT_TYPE_IDS
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.controls.shared.MediaSeekBarStyle
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

@SysUISingleton
class AxDynamicBarSettings @Inject constructor(
    @Main private val mainHandler: Handler,
    private val secureSettings: SecureSettings,
    private val context: android.content.Context,
    private val globalSettings: GlobalSettings,
    private val userTracker: UserTracker,
    private val statusBarStateController: StatusBarStateController,
) {
    companion object {
        const val KEY_ENABLED = "ax_dynamic_bar_enabled"
        const val KEY_EVENTS = "ax_dynamic_bar_events"
        const val KEY_KEYGUARD_ENABLED = "ax_dynamic_bar_lockscreen_enabled"
        const val KEY_KEYGUARD_EVENTS = "ax_dynamic_bar_lockscreen_events"
        private const val KEY_LEGACY_KEYGUARD_ENABLED = "ax_dynamic_bar_keyguard_enabled"
        const val KEY_KEYGUARD_MUSIC_PILL_ENABLED = "ax_dynamic_bar_keyguard_music_pill"
        const val KEY_KEYGUARD_BATTERY_CHIP_MODE = "ax_dynamic_bar_keyguard_battery_chip_mode"
        const val KEY_CHIP_STYLE = "ax_dynamic_bar_chip_style"
        const val KEY_COMPACT_NOTIFICATIONS = "ax_dynamic_bar_compact_notifications"
    }

    private val contentResolver = context.contentResolver

    private val _isEnabled = MutableStateFlow(false)
    @get:JvmName("getIsEnabled") val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isKeyguardEnabled = MutableStateFlow(false)
    val isKeyguardEnabled: StateFlow<Boolean> = _isKeyguardEnabled.asStateFlow()

    private val _isKeyguardMusicPillEnabled = MutableStateFlow(false)
    val isKeyguardMusicPillEnabled: StateFlow<Boolean> = _isKeyguardMusicPillEnabled.asStateFlow()

    private val _keyguardBatteryChipMode = MutableStateFlow(1)
    val keyguardBatteryChipMode: StateFlow<Int> = _keyguardBatteryChipMode.asStateFlow()

    private val _seekBarStyle = MutableStateFlow(MediaSeekBarStyle.DEFAULT)
    val seekBarStyle: StateFlow<MediaSeekBarStyle> = _seekBarStyle.asStateFlow()
    
    private val _chipStyle = MutableStateFlow(0)
    val chipStyle: StateFlow<Int> = _chipStyle.asStateFlow()

    private val _compactNotifications = MutableStateFlow(true)
    val compactNotifications: StateFlow<Boolean> = _compactNotifications.asStateFlow()

    private val _isHeadsUpEnabled = MutableStateFlow(true)
    val isHeadsUpEnabled: StateFlow<Boolean> = _isHeadsUpEnabled.asStateFlow()

    private val _disabledEventTypes = MutableStateFlow<Set<String>>(emptySet())
    val disabledEventTypes: StateFlow<Set<String>> = _disabledEventTypes.asStateFlow()

    private val _keyguardDisabledEventTypes = MutableStateFlow<Set<String>>(emptySet())
    val keyguardDisabledEventTypes: StateFlow<Set<String>> =
        _keyguardDisabledEventTypes.asStateFlow()

    init {
        refresh()
    }

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }

    private var initialized = false
    private val userCallback = object : UserTracker.Callback {
        override fun onUserChanged(newUser: Int, userContext: android.content.Context) {
            refresh()
        }
    }

    fun init() {
        if (initialized) return
        initialized = true
        userTracker.addCallback(userCallback, context.mainExecutor)
        refresh()
        secureSettings.registerContentObserverForUserSync(
            KEY_ENABLED,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_EVENTS,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_KEYGUARD_ENABLED,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_KEYGUARD_EVENTS,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_KEYGUARD_MUSIC_PILL_ENABLED,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_KEYGUARD_BATTERY_CHIP_MODE,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_CHIP_STYLE,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.MEDIA_WAVEFORM_SEEKBAR),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_COMPACT_NOTIFICATIONS,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        globalSettings.registerContentObserverSync(
            Global.HEADS_UP_NOTIFICATIONS_ENABLED,
            false,
            settingsObserver,
        )
    }

    fun destroy() {
        if (!initialized) return
        initialized = false
        userTracker.removeCallback(userCallback)
        secureSettings.getContentResolver().unregisterContentObserver(settingsObserver)
        contentResolver.unregisterContentObserver(settingsObserver)
        globalSettings.getContentResolver().unregisterContentObserver(settingsObserver)
    }

    private fun refresh() {
        if (secureSettings.getStringForUser(KEY_KEYGUARD_ENABLED, UserHandle.USER_CURRENT) == null) {
            val wasEnabled =
                secureSettings.getIntForUser(KEY_ENABLED, 0, UserHandle.USER_CURRENT) == 1 &&
                    secureSettings.getIntForUser(
                        KEY_LEGACY_KEYGUARD_ENABLED, 1, UserHandle.USER_CURRENT,
                    ) == 1
            secureSettings.putIntForUser(
                KEY_KEYGUARD_ENABLED, if (wasEnabled) 1 else 0, UserHandle.USER_CURRENT,
            )
        }
        if (secureSettings.getStringForUser(KEY_KEYGUARD_EVENTS, UserHandle.USER_CURRENT) == null) {
            secureSettings.putStringForUser(
                KEY_KEYGUARD_EVENTS,
                secureSettings.getStringForUser(KEY_EVENTS, UserHandle.USER_CURRENT) ?: "",
                UserHandle.USER_CURRENT,
            )
        }
        _isEnabled.value =
            secureSettings.getIntForUser(KEY_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        _isKeyguardEnabled.value =
            secureSettings.getIntForUser(KEY_KEYGUARD_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        _keyguardBatteryChipMode.value =
            secureSettings.getIntForUser(KEY_KEYGUARD_BATTERY_CHIP_MODE, 1, UserHandle.USER_CURRENT)
        _isKeyguardMusicPillEnabled.value =
            secureSettings.getIntForUser(KEY_KEYGUARD_MUSIC_PILL_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        _chipStyle.value =
            secureSettings.getIntForUser(KEY_CHIP_STYLE, 0, UserHandle.USER_CURRENT)
        _seekBarStyle.value = MediaSeekBarStyle.read(context, userTracker.userId)
        _compactNotifications.value =
            secureSettings.getIntForUser(KEY_COMPACT_NOTIFICATIONS, 1, UserHandle.USER_CURRENT) == 1
        _isHeadsUpEnabled.value =
            globalSettings.getInt(Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1) == 1

        _disabledEventTypes.value = readDisabledEvents(KEY_EVENTS)
        _keyguardDisabledEventTypes.value = readDisabledEvents(KEY_KEYGUARD_EVENTS)
    }

    private fun readDisabledEvents(key: String): Set<String> {
        val json = secureSettings.getStringForUser(key, UserHandle.USER_CURRENT) ?: ""
        return try {
            if (json.isBlank()) emptySet()
            else {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { arr.optString(it) }.toSet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun isEventEnabled(event: IslandEvent, onKeyguard: Boolean = false): Boolean {
        val typeId = when {
            event is IslandEvent.NowPlaying -> "media"
            event is IslandEvent.AospChip && event.active.key.startsWith("callChip-") -> "call"
            event is IslandEvent.KeyguardIndication &&
                event.indicationType == IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC ->
                "biometric_unlock"
            else -> EVENT_TYPE_IDS[event::class.java] ?: return true
        }
        val disabled = if (onKeyguard) _keyguardDisabledEventTypes.value else _disabledEventTypes.value
        return typeId !in disabled
    }

    fun isKeyguardBiometricUnlockEventsActive(): Boolean =
        _isKeyguardEnabled.value && "biometric_unlock" !in _keyguardDisabledEventTypes.value

    fun isNotificationEventsActive(): Boolean =
        _isEnabled.value && statusBarStateController.state == StatusBarState.SHADE &&
            "notification" !in _disabledEventTypes.value

    fun shouldSuppressHeadsUp(notification: StatusBarNotification): Boolean {
        if (CallNotificationInfo.from(notification) != null) {
            return when (statusBarStateController.state) {
                StatusBarState.KEYGUARD ->
                    _isKeyguardEnabled.value && "call" !in _keyguardDisabledEventTypes.value
                StatusBarState.SHADE -> _isEnabled.value && "call" !in _disabledEventTypes.value
                else -> false
            }
        }
        return isNotificationEventsActive()
    }

    fun isAnyBarEnabled(): Boolean = _isEnabled.value || _isKeyguardEnabled.value

    fun isEventSourceNeeded(typeId: String): Boolean =
        (_isEnabled.value && typeId !in _disabledEventTypes.value) ||
            (_isKeyguardEnabled.value && typeId !in _keyguardDisabledEventTypes.value)
}
