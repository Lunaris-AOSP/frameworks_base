/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.view.View
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.shared.R as SharedR

internal object DynamicClockLayout {
    fun findClock(root: View): ClockStyle? =
        root.findViewById<ClockStyle>(R.id.dynamic_clock_ls)
            ?: root.findViewById<ClockStyle>(R.id.clock_ls)

    fun findClock(views: Map<Int, View>): ClockStyle? =
        (views[R.id.dynamic_clock_ls] ?: views[R.id.clock_ls]) as? ClockStyle

    fun applyConstraints(root: ConstraintLayout, constraints: ConstraintSet, nativeClock: ClockController?) {
        val host = findClock(root) ?: return
        host.applyNativeClockConstraints(constraints, nativeClock)
        if (!host.isDynamicClockOnLockscreen) return
        val nativeIds = nativeClock?.let {
            (it.smallClock.layout.views + it.largeClock.layout.views).map { view -> view.id }
        }.orEmpty() + listOf(ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
            ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE, R.id.lockscreen_clock_view)
        val followers = listOf(R.id.keyguard_slice_view, R.id.keyguard_weather,
            R.id.default_weather_image, R.id.default_weather_text, R.id.keyguard_info_widgets,
            SharedR.id.bc_smartspace_view, SharedR.id.date_smartspace_view,
            SharedR.id.weather_smartspace_view, SharedR.id.date_smartspace_view_large,
            SharedR.id.weather_smartspace_view_large).filter { root.findViewById<View>(it) != null }
        constraints.apply {
            connect(host.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP,
                (root.resources.getDimensionPixelSize(R.dimen.status_bar_height) * 1.25f).toInt())
            setVisibilityMode(host.id, ConstraintSet.VISIBILITY_MODE_IGNORE)
            followers.forEach { id ->
                val layout = getConstraint(id)?.layout ?: return@forEach
                if (layout.topToBottom in nativeIds || layout.topToTop in nativeIds) {
                    clear(id, ConstraintSet.BOTTOM)
                    connect(id, ConstraintSet.TOP, host.id, ConstraintSet.BOTTOM, layout.topMargin)
                }
                if (layout.startToEnd in nativeIds) {
                    connect(id, ConstraintSet.START, host.id, ConstraintSet.START,
                        root.resources.getDimensionPixelSize(R.dimen.below_clock_padding_start))
                } else if (layout.startToStart in nativeIds) {
                    connect(id, ConstraintSet.START, host.id, ConstraintSet.START, layout.startMargin)
                }
                if (layout.endToEnd in nativeIds) {
                    connect(id, ConstraintSet.END, host.id, ConstraintSet.END, layout.endMargin)
                }
            }
            val content = intArrayOf(host.id, *followers.toIntArray())
            fun barrier(id: Int, references: IntArray) {
                createBarrier(id, Barrier.BOTTOM, 0, *references)
                getConstraint(id).layout.mBarrierAllowsGoneWidgets = false
            }
            barrier(R.id.keyguard_widget_content_bottom, content)
            val widgets = listOf(R.id.keyguard_widgets, R.id.keyguard_app_widgets)
                .filter { root.findViewById<View>(it) != null }
            val media = if (root.resources.getBoolean(R.bool.config_use_split_notification_shade))
                listOf(R.id.status_view_media_container).filter { root.findViewById<View>(it) != null }
                else emptyList()
            barrier(R.id.keyguard_app_widget_anchor,
                content + widgets.filter { it != R.id.keyguard_app_widgets }.toIntArray() + media.toIntArray())
            barrier(R.id.smart_space_barrier_bottom, content + widgets.toIntArray())
        }
    }
}
