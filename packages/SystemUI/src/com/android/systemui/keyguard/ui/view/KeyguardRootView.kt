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

package com.android.systemui.keyguard.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.systemui.res.R

/** Provides a container for all keyguard ui content. */
class KeyguardRootView(
    context: Context,
    attrs: AttributeSet?,
) :
    ConstraintLayout(
        context,
        attrs,
    ) {
    var aodFpOnly: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            notifySubtreeAccessibilityStateChangedIfNeeded()
        }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (aodFpOnly && child.id != R.id.device_entry_icon_view) return false
        return super.drawChild(canvas, child, drawingTime)
    }

    override fun isTransformedTouchPointInView(
        x: Float,
        y: Float,
        child: View,
        outLocalPoint: PointF?,
    ): Boolean {
        if (aodFpOnly && child.id != R.id.device_entry_icon_view) return false
        return super.isTransformedTouchPointInView(x, y, child, outLocalPoint)
    }

    override fun addChildrenForAccessibility(outChildren: ArrayList<View>) {
        if (!aodFpOnly) {
            super.addChildrenForAccessibility(outChildren)
            return
        }
        if (accessibilityNodeProvider != null) return
        val icon = findViewById<View>(R.id.device_entry_icon_view) ?: return
        if (icon.visibility != VISIBLE) return
        if (icon.includeForAccessibility()) {
            outChildren.add(icon)
        } else {
            icon.addChildrenForAccessibility(outChildren)
        }
    }
}
