/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.smartspace

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.systemui.smartspace.InterceptingViewPager

class SmartspaceExtrasViewPager
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : InterceptingViewPager(context, attrs) {
    override fun setEnabled(enabled: Boolean) {
        if (!enabled && isEnabled) {
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try {
                super.dispatchTouchEvent(cancel)
            } finally {
                cancel.recycle()
            }
            cancelScheduledLongPress()
        }
        super.setEnabled(enabled)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        isEnabled && super.dispatchTouchEvent(event)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.EXACTLY) return

        val childWidth =
            View.MeasureSpec.makeMeasureSpec(
                (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0),
                View.MeasureSpec.EXACTLY,
            )
        val childHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var height = minimumHeight
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            child.measure(childWidth, childHeight)
            height = maxOf(height, child.measuredHeight + paddingTop + paddingBottom)
        }
        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(
                View.resolveSize(height, heightMeasureSpec),
                View.MeasureSpec.EXACTLY,
            ),
        )
    }
}
