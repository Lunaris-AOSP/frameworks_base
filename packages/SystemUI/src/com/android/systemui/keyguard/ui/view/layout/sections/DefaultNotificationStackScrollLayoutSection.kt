/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.HORIZONTAL_GUIDELINE
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import com.android.systemui.customization.clocks.R as clocksR
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.res.R
import com.android.systemui.shade.LargeScreenHeaderHelper
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.SharedNotificationContainerBinder
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.settings.SecureSettings
import dagger.Lazy
import javax.inject.Inject

/** Single column format for notifications (default for phones) */
class DefaultNotificationStackScrollLayoutSection
@Inject
constructor(
    @ShadeDisplayAware context: Context,
    notificationPanelView: NotificationPanelView,
    sharedNotificationContainer: SharedNotificationContainer,
    sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    sharedNotificationContainerBinder: SharedNotificationContainerBinder,
    private val largeScreenHeaderHelperLazy: Lazy<LargeScreenHeaderHelper>,
    private val secureSettings: SecureSettings,
    private val tunerService: TunerService,
    private val blueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
    private val keyguardClockViewModel: KeyguardClockViewModel,
) :
    NotificationStackScrollLayoutSection(
        context,
        notificationPanelView,
        sharedNotificationContainer,
        sharedNotificationContainerViewModel,
        sharedNotificationContainerBinder,
    ) {
    private val positioningTunable = TunerService.Tunable { _, _ ->
        blueprintInteractor.get().refreshBlueprint()
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        super.bindData(constraintLayout)
        tunerService.addTunable(
            positioningTunable,
            Settings.Secure.LOCK_SCREEN_NOTIFICATIONS_ABOVE_CLOCK,
            Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE,
            org.lunaris.lockscreenwidgets.WidgetContract.ENABLED,
        )
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        tunerService.removeTunable(positioningTunable)
        super.removeViews(constraintLayout)
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            val bottomMargin =
                context.resources.getDimensionPixelSize(R.dimen.keyguard_status_view_bottom_margin)
            val useLargeScreenHeader =
                context.resources.getBoolean(R.bool.config_use_large_screen_shade_header)
            val marginTopLargeScreen =
                largeScreenHeaderHelperLazy.get().getLargeScreenHeaderHeight()
            val notificationsAboveClock =
                secureSettings.getIntForUser(
                    Settings.Secure.LOCK_SCREEN_NOTIFICATIONS_ABOVE_CLOCK,
                    0,
                    UserHandle.USER_CURRENT,
                ) != 0 &&
                    secureSettings.getIntForUser(
                        Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE,
                        0,
                        UserHandle.USER_CURRENT,
                    ) != 0
            if (notificationsAboveClock) {
                val normalClockBottom =
                    keyguardClockViewModel.getSmallClockTopMargin() +
                        context.resources.getDimensionPixelSize(clocksR.dimen.small_clock_height)
                val dateWeatherHeight =
                    if (keyguardClockViewModel.shouldDateWeatherBeBelowSmallClock.value) {
                        context.resources.getDimensionPixelSize(
                            clocksR.dimen.date_weather_view_height
                        )
                    } else {
                        0
                    }
                create(R.id.nssl_placeholder_guideline_top, HORIZONTAL_GUIDELINE)
                setGuidelineBegin(
                    R.id.nssl_placeholder_guideline_top,
                    normalClockBottom + dateWeatherHeight,
                )
            }
            connect(
                R.id.nssl_placeholder,
                TOP,
                if (notificationsAboveClock) R.id.nssl_placeholder_guideline_top
                else R.id.smart_space_barrier_bottom,
                BOTTOM,
                bottomMargin +
                    if (useLargeScreenHeader) {
                        marginTopLargeScreen
                    } else {
                        0
                    },
            )
            connect(R.id.nssl_placeholder, START, PARENT_ID, START)
            connect(R.id.nssl_placeholder, END, PARENT_ID, END)

            addNotificationPlaceholderBarrier(this)
            if (notificationsAboveClock && secureSettings.getIntForUser(
                    org.lunaris.lockscreenwidgets.WidgetContract.ENABLED, 0, UserHandle.USER_CURRENT) != 0) {
                connect(R.id.nssl_placeholder, BOTTOM, R.id.keyguard_app_widgets, TOP)
            }
        }
    }
}
