package com.android.systemui.islandevents.domain

import android.app.Notification
import android.media.AudioManager
import android.provider.Settings.Global
import com.android.systemui.islandevents.model.IslandEvent

/** The common interruption policy; each consumer supplies its own enable setting. */
object NotificationAlertPolicy {
    fun shouldSuppress(
        event: IslandEvent.Notification,
        enabled: Boolean,
        zenMode: Int,
        ringerMode: Int,
    ): Boolean {
        val notification = event.sbn.notification
        val extras = notification?.extras
        if (extras != null && (extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
                extras.containsKey(Notification.EXTRA_DECLINE_INTENT) ||
                extras.containsKey(Notification.EXTRA_HANG_UP_INTENT))) return false
        if (!enabled) return true
        if (notification?.category == Notification.CATEGORY_CALL ||
            notification?.category == Notification.CATEGORY_ALARM) return false
        return zenMode == Global.ZEN_MODE_NO_INTERRUPTIONS || zenMode == Global.ZEN_MODE_ALARMS ||
            ringerMode == AudioManager.RINGER_MODE_SILENT
    }
}
