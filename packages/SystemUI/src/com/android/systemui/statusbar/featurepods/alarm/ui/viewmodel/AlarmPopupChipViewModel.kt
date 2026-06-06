/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.featurepods.alarm.ui.viewmodel

import android.app.AlarmManager
import android.app.Notification
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.getValue
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.featurepods.popups.shared.toSendAction
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.ALARMS
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled
import com.android.systemui.statusbar.featurepods.alarm.shared.model.AlarmPopupModel
import com.android.systemui.statusbar.featurepods.popups.shared.toActivityLaunchAction
import com.android.systemui.statusbar.featurepods.popups.ui.model.ChipIcon
import com.android.systemui.statusbar.featurepods.popups.ui.model.ColorsModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.NextAlarmController
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

/** ViewModel backing the next-alarm page inside the dynamic island. */
class AlarmPopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val nextAlarmController: NextAlarmController,
    private val notifCollection: CommonNotifCollection,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardStateController: KeyguardStateController,
) : StatusBarPopupChipViewModel, ExclusiveActivatable() {
    private val hydrator = Hydrator("AlarmPopupChipViewModel.hydrator")

    override val chip: PopupChipModel by
        hydrator.hydratedStateOf(
            traceName = "chip",
            initialValue = PopupChipModel.Hidden(PopupChipId.Alarm),
            source =
                callbackFlow<AlarmPopupState> {
                        var nextAlarm: AlarmManager.AlarmClockInfo? = null
                        fun emitState() {
                            trySend(
                                AlarmPopupState(
                                    nextAlarm = nextAlarm,
                                    activeNotification =
                                        notifCollection.allNotifs.firstOrNull { it.isAlarmCandidate() },
                                )
                            )
                        }

                        val callback =
                            NextAlarmController.NextAlarmChangeCallback { updatedAlarm ->
                                nextAlarm = updatedAlarm
                                emitState()
                            }
                        val notificationListener =
                            object : NotifCollectionListener {
                                override fun onEntryAdded(entry: NotificationEntry) = emitState()

                                override fun onEntryUpdated(entry: NotificationEntry) = emitState()

                                override fun onEntryRemoved(entry: NotificationEntry, reason: Int) =
                                    emitState()

                                override fun onRankingApplied() = emitState()
                            }

                        nextAlarmController.addCallback(callback)
                        notifCollection.addCollectionListener(notificationListener)
                        emitState()
                        awaitClose {
                            nextAlarmController.removeCallback(callback)
                            notifCollection.removeCollectionListener(notificationListener)
                        }
                    }
                    .map(::toPopupChipModel)
                    .combine(observeDynamicIslandFeatureEnabled(context, ALARMS)) { model, enabled ->
                        if (enabled) model else PopupChipModel.Hidden(PopupChipId.Alarm)
                    },
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    private fun toPopupChipModel(state: AlarmPopupState): PopupChipModel {
        val alarm = state.nextAlarm?.takeIf { it.triggerTime > 0L }
        val activeNotification = state.activeNotification
        if (alarm == null && activeNotification == null) {
            return PopupChipModel.Hidden(PopupChipId.Alarm)
        }

        val chipTimeText =
            alarm?.let { formatAlarmTime(it.triggerTime, skeleton = chipSkeleton()) }
                ?: activeNotification?.notificationText().orEmpty().ifBlank {
                    context.getString(R.string.dynamic_island_alarm_active_title)
                }
        val fullTimeText =
            alarm?.let { formatAlarmTime(it.triggerTime, skeleton = chipSkeleton()) }
                ?: chipTimeText
        val dayText =
            alarm?.let { formatAlarmTime(it.triggerTime, skeleton = daySkeleton()) }
                ?: activeNotification?.notificationTitle().orEmpty().ifBlank {
                    context.getString(R.string.dynamic_island_alarm_active_title)
                }
        val onOpen =
            activeNotification?.sbn?.notification?.contentIntent.toActivityLaunchAction(
                activityStarter = activityStarter,
                activityIntentHelper = activityIntentHelper,
                lockscreenUserManager = lockscreenUserManager,
                keyguardStateController = keyguardStateController,
            )
                ?: alarm?.showIntent.toActivityLaunchAction(
                    activityStarter = activityStarter,
                    activityIntentHelper = activityIntentHelper,
                    lockscreenUserManager = lockscreenUserManager,
                    keyguardStateController = keyguardStateController,
                )

        val model =
            AlarmPopupModel(
                title =
                    if (activeNotification?.hasSnoozeAction() == true) {
                        context.getString(R.string.dynamic_island_alarm_active_title)
                    } else {
                        context.getString(R.string.dynamic_island_alarm_title)
                    },
                triggerTimeMs = alarm?.triggerTime ?: 0L,
                chipTimeText = chipTimeText,
                fullTimeText = fullTimeText,
                dayText = dayText,
                onOpen = onOpen,
                actions =
                    buildList {
                        activeNotification?.snoozeAction()?.toSendAction()?.let {
                            add(
                                PopupActionModel(
                                    context.getString(R.string.dynamic_island_action_snooze),
                                    it,
                                    emphasized = true,
                                )
                            )
                        }
                    },
            )
        val contentDescription =
            alarm?.let {
                context.getString(
                    R.string.accessibility_quick_settings_alarm,
                    formatAlarmTime(it.triggerTime, skeleton = fullDescriptionSkeleton()),
                )
            } ?: model.title

        return PopupChipModel.Shown(
            chipId = PopupChipId.Alarm,
            icons =
                listOf(
                    ChipIcon(
                        icon =
                            Icon.Resource(
                                resId = R.drawable.ic_dynamic_island_alarm,
                                contentDescription = ContentDescription.Resource(R.string.status_bar_alarm),
                            ),
                        onClick = model.onOpen,
                    )
                ),
            chipText = model.chipTimeText,
            colors = ColorsModel.DynamicIsland,
            contentDescription = contentDescription,
            popupContent = PopupContentModel.Alarm(model),
        )
    }

    private fun chipSkeleton(): String = if (DateFormat.is24HourFormat(context)) "Hm" else "hma"

    private fun fullDescriptionSkeleton(): String =
        if (DateFormat.is24HourFormat(context)) "EHm" else "Ehma"

    private fun daySkeleton(): String = "EEEE"

    private fun formatAlarmTime(triggerTimeMs: Long, skeleton: String): String {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        return DateFormat.format(pattern, triggerTimeMs).toString()
    }

    @AssistedFactory
    interface Factory {
        fun create(): AlarmPopupChipViewModel
    }
}

private data class AlarmPopupState(
    val nextAlarm: AlarmManager.AlarmClockInfo?,
    val activeNotification: NotificationEntry?,
)

private fun NotificationEntry.isAlarmCandidate(): Boolean {
    val notification = sbn.notification
    return notification.contentIntent != null &&
        (
            notification.category == Notification.CATEGORY_ALARM ||
                sbn.packageName.contains("clock", ignoreCase = true) ||
                notification.actions.orEmpty().any { action ->
                    action.title?.toString()?.contains("snooze", ignoreCase = true) == true
                }
        )
}

private fun NotificationEntry.notificationTitle(): String? {
    return sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
}

private fun NotificationEntry.notificationText(): String? {
    return sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
}

private fun NotificationEntry.hasSnoozeAction(): Boolean {
    return snoozeAction() != null
}

private fun NotificationEntry.snoozeAction() =
    sbn.notification.actions.orEmpty().firstOrNull { action ->
        action.title?.toString()?.contains("snooze", ignoreCase = true) == true
    }?.actionIntent
        ?: sbn.notification.actions.orEmpty()
            .takeIf {
                sbn.notification.category == Notification.CATEGORY_ALARM && it.size > 1
            }
            ?.firstOrNull()
            ?.actionIntent
