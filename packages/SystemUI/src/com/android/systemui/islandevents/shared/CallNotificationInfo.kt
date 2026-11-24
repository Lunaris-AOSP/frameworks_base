package com.android.systemui.islandevents.shared

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification

data class CallNotificationInfo(
    val isIncoming: Boolean,
    val answerIntent: PendingIntent?,
    val declineIntent: PendingIntent?,
    val hangUpIntent: PendingIntent?,
    val isLegacyTelegram: Boolean = false,
) {
    companion object {
        private const val TELEGRAM_CALL_RECEIVER = "org.telegram.messenger.voip.VoIPActionsReceiver"

        fun from(sbn: StatusBarNotification): CallNotificationInfo? {
            val notification = sbn.notification
            if (notification.category != Notification.CATEGORY_CALL && !sbn.isOngoing) return null
            val extras = notification.extras
            val answerIntent =
                extras.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
            val declineIntent =
                extras.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java)
            val hangUpIntent =
                extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java)

            if (
                notification.category == Notification.CATEGORY_CALL &&
                    (extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
                        extras.containsKey(Notification.EXTRA_DECLINE_INTENT) ||
                        extras.containsKey(Notification.EXTRA_HANG_UP_INTENT))
            ) {
                return CallNotificationInfo(
                    isIncoming =
                        extras.getInt(Notification.EXTRA_CALL_TYPE) ==
                            Notification.CallStyle.CALL_TYPE_INCOMING,
                    answerIntent = answerIntent,
                    declineIntent = declineIntent,
                    hangUpIntent = hangUpIntent,
                )
            }

            val packageName = sbn.packageName
            val telegramIntents =
                notification.actions
                    .orEmpty()
                    .filter { it.remoteInputs.isNullOrEmpty() }
                    .mapNotNull { action ->
                        val pendingIntent = action.actionIntent ?: return@mapNotNull null
                        val intent =
                            runCatching { pendingIntent.intent }.getOrNull()
                                ?: return@mapNotNull null
                        val component = intent.component ?: return@mapNotNull null
                        if (
                            pendingIntent.creatorPackage != packageName ||
                                component.packageName != packageName ||
                                component.className != TELEGRAM_CALL_RECEIVER
                        ) {
                            return@mapNotNull null
                        }
                        when (intent.action) {
                            "$packageName.ANSWER_CALL" -> "answer" to pendingIntent
                            "$packageName.DECLINE_CALL" -> "decline" to pendingIntent
                            "$packageName.END_CALL" -> "hangUp" to pendingIntent
                            else -> null
                        }
                    }
                    .toMap()
            val telegramAnswerIntent = telegramIntents["answer"]
            val telegramDeclineIntent = telegramIntents["decline"]
            val telegramHangUpIntent = telegramIntents["hangUp"]
            val isIncoming =
                notification.category == Notification.CATEGORY_CALL &&
                    telegramAnswerIntent != null &&
                    telegramDeclineIntent != null
            val isActive =
                sbn.isOngoing &&
                    telegramHangUpIntent != null &&
                    runCatching { notification.contentIntent?.intent?.action }.getOrNull() == "voip"
            if (!isIncoming && !isActive) return null

            return CallNotificationInfo(
                isIncoming = isIncoming,
                answerIntent = telegramAnswerIntent,
                declineIntent = telegramDeclineIntent,
                hangUpIntent = telegramHangUpIntent,
                isLegacyTelegram = true,
            )
        }
    }
}
