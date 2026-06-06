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

package com.android.systemui.statusbar.featurepods.livescore.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import com.android.systemui.ActivityIntentHelper
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.featurepods.livescore.shared.model.LiveScoreChipModel
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.LIVE_SCORES
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandFeatureEnabled
import com.android.systemui.statusbar.featurepods.popups.shared.toActivityLaunchAction
import com.android.systemui.statusbar.featurepods.popups.ui.model.ChipIcon
import com.android.systemui.statusbar.featurepods.popups.ui.model.ColorsModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.policy.KeyguardStateController
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.combine

/** ViewModel backing live-score notifications surfaced inside the dynamic island. */
class LiveScorePopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    activeNotificationsInteractor: ActiveNotificationsInteractor,
    private val activityStarter: ActivityStarter,
    private val activityIntentHelper: ActivityIntentHelper,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardStateController: KeyguardStateController,
) : StatusBarPopupChipViewModel, ExclusiveActivatable() {
    private val hydrator = Hydrator("LiveScorePopupChipViewModel.hydrator")

    override val chip: PopupChipModel by
        hydrator.hydratedStateOf(
            traceName = "chip",
            initialValue = PopupChipModel.Hidden(PopupChipId.LiveScore),
            source =
                combine(
                    activeNotificationsInteractor.promotedOngoingNotifications,
                    activeNotificationsInteractor.allRepresentativeNotifications,
                ) { promotedNotifications, allNotifications ->
                    val orderedNotifications =
                        promotedNotifications.mapNotNull { notif -> allNotifications[notif.key] }
                    val candidate =
                        orderedNotifications.firstOrNull { it.isLiveScoreCandidate() }
                            ?: allNotifications.values.firstOrNull { it.isLiveScoreCandidate() }
                    toPopupChipModel(
                        candidate?.toLiveScoreModel(
                            context = context,
                            activityStarter = activityStarter,
                            activityIntentHelper = activityIntentHelper,
                            lockscreenUserManager = lockscreenUserManager,
                            keyguardStateController = keyguardStateController,
                        )
                    )
                }
                .combine(observeDynamicIslandFeatureEnabled(context, LIVE_SCORES)) { model, enabled ->
                    if (enabled) model else PopupChipModel.Hidden(PopupChipId.LiveScore)
                },
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    private fun toPopupChipModel(model: LiveScoreChipModel?): PopupChipModel {
        if (model == null) {
            return PopupChipModel.Hidden(PopupChipId.LiveScore)
        }

        return PopupChipModel.Shown(
            chipId = PopupChipId.LiveScore,
            icons =
                listOfNotNull(
                    model.icon?.let {
                        ChipIcon(
                            icon = it,
                            onClick = model.onOpen,
                        )
                    }
                ),
            chipText = buildCollapsedText(model),
            colors = ColorsModel.DynamicIsland,
            contentDescription = listOfNotNull(model.title, model.score, model.subtitle).joinToString(" "),
            popupContent = PopupContentModel.LiveScore(model),
        )
    }

    private fun buildCollapsedText(model: LiveScoreChipModel): String {
        val title = model.title?.takeUnless { it.isBlank() } ?: model.appName
        val score = model.score.takeUnless { it.isBlank() }
        return listOfNotNull(score, title.takeUnless { it.isBlank() }).joinToString(" • ")
    }

    @AssistedFactory
    interface Factory {
        fun create(): LiveScorePopupChipViewModel
    }
}

private fun ActiveNotificationModel.isLiveScoreCandidate(): Boolean {
    val content = promotedContent?.privateVersion ?: return false
    if (callType != com.android.systemui.statusbar.notification.shared.CallType.None) {
        return false
    }
    if (content.shortCriticalText.isNullOrBlank()) {
        return false
    }
    return !content.title.isNullOrBlank() || !content.text.isNullOrBlank()
}

private fun ActiveNotificationModel.toLiveScoreModel(
    context: Context,
    activityStarter: ActivityStarter,
    activityIntentHelper: ActivityIntentHelper,
    lockscreenUserManager: NotificationLockscreenUserManager,
    keyguardStateController: KeyguardStateController,
): LiveScoreChipModel {
    val content = checkNotNull(promotedContent).privateVersion
    val contentDescription = ContentDescription.Loaded(appName)
    return LiveScoreChipModel(
        key = key,
        icon = statusBarIcon?.loadDrawable(context)?.let { Icon.Loaded(it, contentDescription) },
        appName = appName,
        title = content.title?.toString(),
        score = content.shortCriticalText.orEmpty(),
        subtitle = content.text?.toString(),
        onOpen =
            contentIntent.toActivityLaunchAction(
                activityStarter = activityStarter,
                activityIntentHelper = activityIntentHelper,
                lockscreenUserManager = lockscreenUserManager,
                keyguardStateController = keyguardStateController,
            ),
    )
}
