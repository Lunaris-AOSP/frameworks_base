/*
 * Copyright (C) 2025 the RisingOS Revived Android Project
 * Copyright (C) 2026 Lunaris-AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.graphics.Rect
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.Barrier
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.communal.domain.interactor.WidgetTrampolineInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.lockscreen.KeyguardWidgetHostController
import com.android.systemui.lockscreen.LockScreenWidgets
import com.android.systemui.lockscreen.WidgetViewport
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import com.android.systemui.shared.R as SharedR

class KeyguardWidgetViewSection @Inject constructor(
    private val context: Context,
    private val activityStarter: ActivityStarter,
    private val falsingManager: FalsingManager,
    private val userTracker: UserTracker,
    private val keyguardStateController: KeyguardStateController,
    private val trampolineInteractor: WidgetTrampolineInteractor,
) : KeyguardSection() {
    private var widgetView: LockScreenWidgets? = null
    private var appWidgets: WidgetViewport? = null
    private var controller: KeyguardWidgetHostController? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var contentIds = intArrayOf()
    private var splitShade = false

    override fun addViews(constraintLayout: ConstraintLayout) {
        widgetView = (LayoutInflater.from(context).inflate(R.layout.keyguard_clock_widgets,
            constraintLayout, false) as LockScreenWidgets).apply { id = R.id.keyguard_widgets }
        constraintLayout.addView(widgetView)
        appWidgets = WidgetViewport(context).apply {
            id = R.id.keyguard_app_widgets
            visibility = View.GONE
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            clipToPadding = true
        }
        constraintLayout.addView(appWidgets)
        contentIds = intArrayOf(
            R.id.clock_ls,
            ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
            ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
            R.id.keyguard_slice_view,
            R.id.keyguard_weather,
            R.id.default_weather_image,
            R.id.default_weather_text,
            R.id.keyguard_info_widgets,
            SharedR.id.bc_smartspace_view,
            SharedR.id.date_smartspace_view,
            SharedR.id.date_smartspace_view_large,
        )
        splitShade = context.resources.getBoolean(R.bool.config_use_split_notification_shade)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val viewport = appWidgets ?: return
        controller = KeyguardWidgetHostController(context, viewport, activityStarter, falsingManager,
            userTracker, keyguardStateController, trampolineInteractor).also { it.attach() }
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (viewport.visibility != View.VISIBLE) return@OnGlobalLayoutListener
            val density = context.resources.displayMetrics.density
            val reserved = (96 * density).toInt()
            val rootBounds = Rect()
            constraintLayout.getBoundsOnScreen(rootBounds, false)
            fun visualBottom(view: View): Int {
                if (view.visibility != View.VISIBLE || view.alpha == 0f) return 0
                val bounds = Rect()
                view.getBoundsOnScreen(bounds, false)
                var bottom = bounds.bottom - rootBounds.top
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) bottom = maxOf(bottom, visualBottom(view.getChildAt(i)))
                }
                return bottom
            }
            val clockBottom = listOf(R.id.clock_ls, ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE)
                .mapNotNull { constraintLayout.findViewById<View>(it) }.maxOfOrNull(::visualBottom) ?: 0
            val anchor = constraintLayout.findViewById<View>(R.id.keyguard_app_widget_anchor)
            val params = viewport.layoutParams as? ConstraintLayout.LayoutParams
            if (anchor != null && params != null) {
                val margin = (8 * density).toInt() + (clockBottom - anchor.bottom).coerceAtLeast(0)
                if (params.topMargin != margin) { params.topMargin = margin; viewport.layoutParams = params }
            }
            val limit = listOf(R.id.device_entry_icon_view, R.id.keyguard_indication_area,
                R.id.start_button, R.id.end_button, R.id.ambient_indication_container)
                .mapNotNull { constraintLayout.findViewById<View>(it) }
                .filter { it.visibility != View.GONE && it.top > 0 }
                .minOfOrNull { it.top } ?: (constraintLayout.height - reserved)
            viewport.maximumHeight = minOf((constraintLayout.height * 0.45f).toInt(),
                limit - viewport.top - reserved).coerceAtLeast(0)
        }
        layoutListener = listener
        constraintLayout.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        splitShade = context.resources.getBoolean(R.bool.config_use_split_notification_shade)
        constraintSet.apply {
            createBarrier(R.id.keyguard_widget_content_bottom, Barrier.BOTTOM, 0, *contentIds)
            getConstraint(R.id.keyguard_widget_content_bottom).layout.mBarrierAllowsGoneWidgets = false
            val gap = (8 * context.resources.displayMetrics.density).toInt()
            connect(R.id.keyguard_widgets, ConstraintSet.TOP, R.id.keyguard_widget_content_bottom,
                ConstraintSet.BOTTOM, gap)
            val anchorIds = contentIds + R.id.keyguard_widgets +
                if (splitShade) intArrayOf(R.id.status_view_media_container) else intArrayOf()
            createBarrier(R.id.keyguard_app_widget_anchor, Barrier.BOTTOM, 0, *anchorIds)
            getConstraint(R.id.keyguard_app_widget_anchor).layout.mBarrierAllowsGoneWidgets = false
            connect(R.id.keyguard_app_widgets, ConstraintSet.TOP, R.id.keyguard_app_widget_anchor,
                ConstraintSet.BOTTOM, gap)
            for (id in listOf(R.id.keyguard_widgets, R.id.keyguard_app_widgets)) {
                connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                connect(id, ConstraintSet.END,
                    if (splitShade) R.id.split_shade_guideline else ConstraintSet.PARENT_ID,
                    ConstraintSet.END)
                constrainWidth(id, ConstraintSet.MATCH_CONSTRAINT)
                constrainHeight(id, ConstraintSet.WRAP_CONTENT)
                setVisibilityMode(id, ConstraintSet.VISIBILITY_MODE_IGNORE)
            }
            constrainMaxWidth(R.id.keyguard_app_widgets,
                context.resources.getDimensionPixelSize(R.dimen.shade_panel_width))
            createBarrier(R.id.smart_space_barrier_bottom, Barrier.BOTTOM, 0,
                *(contentIds + R.id.keyguard_widgets + R.id.keyguard_app_widgets))
            getConstraint(R.id.smart_space_barrier_bottom).layout.mBarrierAllowsGoneWidgets = false
            if (splitShade) {
                connect(R.id.status_view_media_container, ConstraintSet.TOP,
                    R.id.keyguard_widgets, ConstraintSet.BOTTOM, gap)
            }
            connect(R.id.left_aligned_notification_icon_container, ConstraintSet.TOP,
                R.id.smart_space_barrier_bottom, ConstraintSet.BOTTOM,
                context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_start_icons))
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        layoutListener?.let { constraintLayout.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        layoutListener = null
        controller?.detach()
        controller = null
        widgetView?.let(constraintLayout::removeView)
        appWidgets?.let(constraintLayout::removeView)
        for (id in listOf(R.id.keyguard_widget_content_bottom, R.id.keyguard_app_widget_anchor)) {
            constraintLayout.findViewById<View>(id)?.let(constraintLayout::removeView)
        }
        widgetView = null
        appWidgets = null
    }
}
