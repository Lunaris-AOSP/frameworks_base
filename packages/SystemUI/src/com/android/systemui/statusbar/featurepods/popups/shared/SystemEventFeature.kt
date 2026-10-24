package com.android.systemui.statusbar.featurepods.popups.shared

import com.android.systemui.islandevents.model.IslandEvent

/** Each added event has an independent, opt-in System setting and source filter. */
enum class SystemEventFeature(val settingKey: String, val notificationType: String? = null) {
    BATTERY(DynamicIslandFeatureSettings.BATTERY),
    UNLOCK(DynamicIslandFeatureSettings.UNLOCK),
    BLUETOOTH(DynamicIslandFeatureSettings.BLUETOOTH),
    CALLS(DynamicIslandFeatureSettings.CALLS, "call"),
    NOTIFICATIONS(DynamicIslandFeatureSettings.NOTIFICATIONS, "notification"),
    ONGOING_ACTIVITIES(DynamicIslandFeatureSettings.ONGOING_ACTIVITIES, "promoted_ongoing"),
    HOTSPOT(DynamicIslandFeatureSettings.HOTSPOT),
    VPN(DynamicIslandFeatureSettings.VPN),
    RINGER(DynamicIslandFeatureSettings.RINGER),
    TIMER(DynamicIslandFeatureSettings.TIMER, "timer"),
    AUDIO_RECORDING(DynamicIslandFeatureSettings.AUDIO_RECORDING, "audio_recording"),
    NOW_PLAYING(DynamicIslandFeatureSettings.NOW_PLAYING, "now_playing");

    companion object {
        fun forEvent(event: IslandEvent): SystemEventFeature? = when (event) {
            is IslandEvent.Charging -> BATTERY
            is IslandEvent.BiometricUnlock -> UNLOCK
            is IslandEvent.Bluetooth -> BLUETOOTH
            is IslandEvent.Call -> CALLS
            is IslandEvent.Notification -> NOTIFICATIONS
            is IslandEvent.PromotedOngoing -> ONGOING_ACTIVITIES
            is IslandEvent.Hotspot -> HOTSPOT
            is IslandEvent.Vpn -> VPN
            is IslandEvent.RingerMode -> RINGER
            is IslandEvent.Timer -> TIMER
            is IslandEvent.AudioRecording -> AUDIO_RECORDING
            is IslandEvent.NowPlaying -> NOW_PLAYING
            else -> null
        }
    }
}
