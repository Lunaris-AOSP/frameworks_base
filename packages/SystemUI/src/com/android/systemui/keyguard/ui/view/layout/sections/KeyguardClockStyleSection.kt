/*
 * Copyright (C) 2025 the RisingOS Revived Android Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.keyguard.ui.view.layout.sections

import com.android.systemui.dagger.SysUISingleton
import android.content.Context
import android.os.UserHandle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.clocks.ClockStyle
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Config
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Type
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.settings.SecureSettings
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.lunaris.clockmaker.DynamicClockSettings
import com.android.systemui.shared.R as SharedR

@SysUISingleton
class KeyguardClockStyleSection
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val secureSettings: SecureSettings,
    private val activeNotificationsInteractor: ActiveNotificationsInteractor,
    private val mediaCarouselInteractor: MediaCarouselInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val tunerService: TunerService,
    private val blueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
) : KeyguardSection() {
    private var clockStyleView: ClockStyle? = null
    private var disposableHandle: DisposableHandle? = null
    private var installedClockId = View.NO_ID
    private val positioningTunable = TunerService.Tunable { _, _ ->
        if (installedClockId != clockViewId()) {
            blueprintInteractor.get().refreshBlueprint(Config(Type.NoTransition,
                checkPriority = false, rebuildSections = listOf(this)))
        }
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        for (id in listOf(R.id.clock_ls, R.id.dynamic_clock_ls)) {
            constraintLayout.findViewById<View>(id)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }
        installedClockId = clockViewId()
        if (installedClockId == View.NO_ID) return
        clockStyleView = (LayoutInflater.from(context)
            .inflate(R.layout.keyguard_clock_style, constraintLayout, false) as ClockStyle).apply {
            id = installedClockId
            clipChildren = false
            clipToPadding = false
            onDynamicClockLayoutChanged = {
                blueprintInteractor.get().refreshBlueprint(Config(Type.NoTransition,
                    checkPriority = false, terminatePrevious = false))
            }
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        clockStyleView?.let { constraintLayout.addView(it) }
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        disposableHandle?.dispose()
        disposableHandle = constraintLayout.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(
                    activeNotificationsInteractor.allNotificationsCount,
                    mediaCarouselInteractor.hasActiveMedia,
                    mediaCarouselInteractor.allowMediaOnLockscreen,
                    keyguardTransitionInteractor.currentKeyguardState,
                    keyguardTransitionInteractor.isInTransition,
                ) { notificationCount, hasActiveMedia, allowMediaOnLockscreen, state, transitioning ->
                    val present = notificationCount > 0 || (hasActiveMedia && allowMediaOnLockscreen)
                    present to (state == KeyguardState.LOCKSCREEN && !transitioning)
                }.distinctUntilChanged().collect { (present, allowed) ->
                    val clock = clockStyleView ?: return@collect
                    if (!allowed) clock.setClockSwitchingAllowed(false)
                    clock.setNotificationPresence(present)
                    if (allowed) clock.setClockSwitchingAllowed(true)
                }
            }
        }
        tunerService.removeTunable(positioningTunable)
        tunerService.addTunable(positioningTunable, DynamicClockSettings.ENABLED, ClockStyle.CLOCK_STYLE_KEY)
        clockStyleView?.onTimeChanged()
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val clockId = installedClockId
        if (clockId == View.NO_ID) return
        constraintSet.apply {
            connect(clockId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(clockId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            val topMargin = (context.resources.getDimensionPixelSize(R.dimen.status_bar_height) * 1.25f).toInt()
            connect(clockId, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, topMargin)
            constrainHeight(clockId, ConstraintSet.WRAP_CONTENT)
            constrainWidth(clockId, ConstraintSet.MATCH_CONSTRAINT)
            setMargin(clockId, ConstraintSet.START, 0)
            setMargin(clockId, ConstraintSet.END, 0)
            setElevation(clockId, 1f)
            setVisibilityMode(clockId, ConstraintSet.VISIBILITY_MODE_IGNORE)
            if (clockId == R.id.clock_ls) createNotificationConstraints(this)
        }
    }

    private fun clockViewId(): Int = when {
        secureSettings.getIntForUser(ClockStyle.CLOCK_STYLE_KEY, 0, UserHandle.USER_CURRENT) != 0 -> R.id.clock_ls
        secureSettings.getIntForUser(DynamicClockSettings.ENABLED, 0, UserHandle.USER_CURRENT) != 0 -> R.id.dynamic_clock_ls
        else -> View.NO_ID
    }

    private fun createNotificationConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            createBarrier(R.id.smart_space_barrier_bottom, Barrier.BOTTOM, 0,
                *intArrayOf(R.id.keyguard_slice_view, R.id.keyguard_weather,
                    R.id.default_weather_image, R.id.default_weather_text, R.id.clock_ls,
                    R.id.keyguard_info_widgets, R.id.keyguard_widgets, R.id.lockscreen_clock_view,
                    SharedR.id.bc_smartspace_view, SharedR.id.date_smartspace_view,
                    SharedR.id.weather_smartspace_view, SharedR.id.date_smartspace_view_large,
                    SharedR.id.weather_smartspace_view_large))
            if (getConstraint(R.id.left_aligned_notification_icon_container) != null) {
                connect(R.id.left_aligned_notification_icon_container, ConstraintSet.TOP,
                    R.id.smart_space_barrier_bottom, ConstraintSet.BOTTOM,
                    context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_start_icons))
            }
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        disposableHandle?.dispose()
        disposableHandle = null
        tunerService.removeTunable(positioningTunable)
        clockStyleView?.let {
            it.onDynamicClockLayoutChanged = null
            (it.parent as? ViewGroup)?.removeView(it)
        }
        clockStyleView = null
        installedClockId = View.NO_ID
    }
}
