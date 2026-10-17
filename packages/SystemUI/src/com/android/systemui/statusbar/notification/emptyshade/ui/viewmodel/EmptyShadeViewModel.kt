/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.emptyshade.ui.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.icu.text.MessageFormat
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dump.DumpManager
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shared.notifications.domain.interactor.NotificationSettingsInteractor
import com.android.systemui.statusbar.notification.NotificationActivityStarter.SettingsIntent
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor
import com.android.systemui.statusbar.notification.emptyshade.ui.shared.flag.ShowIconInEmptyShade
import com.android.systemui.statusbar.notification.emptyshade.ui.shared.model.IconMessageModel
import com.android.systemui.statusbar.notification.footer.ui.viewmodel.FooterMessageViewModel
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.statusbar.policy.domain.model.ActiveZenModes
import com.android.systemui.util.kotlin.FlowDumperImpl
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * ViewModel for the empty shade (aka the "No notifications" text shown when there are no
 * notifications.
 */
@SuppressLint("FlowExposedFromViewModel")
class EmptyShadeViewModel
@AssistedInject
constructor(
    @ShadeDisplayAware private val context: Context,
    zenModeInteractor: ZenModeInteractor,
    seenNotificationsInteractor: SeenNotificationsInteractor,
    notificationSettingsInteractor: NotificationSettingsInteractor,
    configurationInteractor: ConfigurationInteractor,
    @Background bgDispatcher: CoroutineDispatcher,
    dumpManager: DumpManager,
) : FlowDumperImpl(dumpManager) {
    private val emptyMessageIndex = MutableStateFlow(0)

    private companion object {
        val EMPTY_MESSAGES =
            arrayOf(
                R.string.caught_up_shade_text to R.drawable.ic_trophy,
                R.string.empty_shade_all_clear to R.drawable.ic_empty_shade_task_alt,
                R.string.empty_shade_breathe to R.drawable.ic_empty_shade_spa,
                R.string.empty_shade_break to R.drawable.ic_empty_shade_coffee,
                R.string.empty_shade_enjoy_day to R.drawable.ic_empty_shade_wb_sunny,
                R.string.empty_shade_quiet_moment to R.drawable.ic_empty_shade_bedtime,
                R.string.empty_shade_nothing_waiting to
                    R.drawable.ic_empty_shade_sentiment_satisfied,
                R.string.empty_shade_nice_and_clear to R.drawable.ic_empty_shade_auto_awesome,
            )
    }

    fun onShadeOpened() {
        emptyMessageIndex.value = (emptyMessageIndex.value + 1) % EMPTY_MESSAGES.size
    }

    val areNotificationsHiddenInShade: Flow<Boolean> by lazy {
        zenModeInteractor.areNotificationsHiddenInShade
            .dumpWhileCollecting("areNotificationsHiddenInShade")
            .flowOn(bgDispatcher)
    }

    val hasFilteredOutSeenNotifications: StateFlow<Boolean> =
        seenNotificationsInteractor.hasFilteredOutSeenNotifications.dumpValue(
            "hasFilteredOutSeenNotifications"
        )

    private val primaryLocale by lazy {
        configurationInteractor.configurationValues
            .map { it.locales.get(0) ?: Locale.getDefault() }
            .onStart { emit(Locale.getDefault()) }
            .distinctUntilChanged()
    }

    /**
     * A combination of icon + text that replaces the old approach with the text followed by a
     * footer that shows an icon.
     */
    val message: Flow<IconMessageModel> by lazy {
        combine(
                zenModeInteractor.modesHidingNotifications,
                primaryLocale,
                hasFilteredOutSeenNotifications,
                notificationSettingsInteractor.areEmptyShadeMessagesEnabled,
                emptyMessageIndex,
            ) { modes, locale, hasFilteredOutSeenNotificationsValue, messagesEnabled, index ->
                when {
                    hasFilteredOutSeenNotificationsValue ->
                        IconMessageModel(
                            message = context.getString(R.string.unlock_to_see_notif_text),
                            icon = Icon.Resource(R.drawable.ic_friction_lock_closed, null),
                        )
                    modes.main != null ->
                        IconMessageModel(
                            message = formatModesString(locale, modes),
                            icon = modes.main.icon,
                        )
                    !messagesEnabled ->
                        IconMessageModel(
                            message = context.getString(R.string.empty_shade_text),
                            icon = null,
                        )
                    else -> {
                        val (text, icon) = EMPTY_MESSAGES[index]
                        IconMessageModel(
                            message = context.getString(text),
                            icon = Icon.Resource(icon, null),
                        )
                    }
                }
            }
            .distinctUntilChanged()
            .flowOn(bgDispatcher)
    }

    val text: Flow<String> by lazy {
        ShowIconInEmptyShade.assertInLegacyMode()
        combine(zenModeInteractor.modesHidingNotifications, primaryLocale) { modes, locale ->
                formatModesString(locale, modes)
            }
            .flowOn(bgDispatcher)
    }

    private fun formatModesString(locale: Locale?, modes: ActiveZenModes): String {
        // Create a string that is either "No notifications" if no modes are filtering them
        // out, or something like "Notifications paused by SomeMode" otherwise.
        val msgFormat =
            MessageFormat(context.getString(R.string.modes_suppressing_shade_text), locale)
        val args: MutableMap<String, Any> = HashMap()
        args["count"] = modes.count
        if (modes.main != null) {
            args["mode"] = modes.main.name
        }
        return msgFormat.format(args)
    }

    val footer: FooterMessageViewModel by lazy {
        ShowIconInEmptyShade.assertInLegacyMode()
        FooterMessageViewModel(
            messageId = R.string.unlock_to_see_notif_text,
            iconId = R.drawable.ic_friction_lock_closed,
            isVisible = hasFilteredOutSeenNotifications,
        )
    }

    val onClick: Flow<SettingsIntent> by lazy {
        combine(
                zenModeInteractor.modesHidingNotifications,
                notificationSettingsInteractor.isNotificationHistoryEnabled,
            ) { modes, isNotificationHistoryEnabled ->
                if (modes.main != null) {
                    if (modes.count == 1) {
                        SettingsIntent.forModeSettings(modes.main.id)
                    } else {
                        SettingsIntent.forModesSettings()
                    }
                } else {
                    if (isNotificationHistoryEnabled) {
                        SettingsIntent.forNotificationHistory()
                    } else {
                        SettingsIntent.forNotificationSettings()
                    }
                }
            }
            .flowOn(bgDispatcher)
    }

    @AssistedFactory
    interface Factory {
        fun create(): EmptyShadeViewModel
    }
}
