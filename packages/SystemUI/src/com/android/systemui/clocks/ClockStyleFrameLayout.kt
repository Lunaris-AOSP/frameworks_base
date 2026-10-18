/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.ceil
import org.lunaris.clockmaker.ClockDesignView

class ClockStyleFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var clockScale: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val clock = getChildAt(0)?.takeIf { it.visibility != GONE } ?: return
        val height = clock.measuredHeight
        val pivotY = if (clock is ClockDesignView) clock.measuredWidth / 2f else height / 2f
        val bottom = paddingTop + pivotY + (height - pivotY) * clockScale + paddingBottom
        setMeasuredDimension(
            measuredWidthAndState,
            resolveSizeAndState(
                ceil(bottom).toInt().coerceAtLeast(suggestedMinimumHeight),
                heightMeasureSpec,
                measuredState shl MEASURED_HEIGHT_STATE_SHIFT,
            ),
        )
    }
}
