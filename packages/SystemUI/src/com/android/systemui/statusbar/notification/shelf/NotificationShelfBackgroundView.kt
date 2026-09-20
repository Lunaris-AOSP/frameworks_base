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

package com.android.systemui.statusbar.notification.shelf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.ViewTreeObserver
import androidx.annotation.VisibleForTesting
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.row.NotificationBackgroundView
import com.android.systemui.statusbar.notification.shared.NotificationMinimalism
import kotlin.math.min

/** The background view for the NotificationShelf. */
class NotificationShelfBackgroundView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) :
    NotificationBackgroundView(context, attrs) {

    /** Whether the notification shelf is aligned to end, need to keep persistent with the shelf. */
    var alignToEnd = false

    /** Whether the notification shelf is in center-alignment mode. */
    var alignToCenter = false

    /** When set, draw the shelf background only around its individual visible icons. */
    var motoIconContainer: NotificationShelfIconContainer? = null
        set(value) {
            if (field === value) return
            field = value
            motoIconBounds.clear()
            motoBackgroundPath.rewind()
            invalidate()
        }

    private val motoIconBounds = ArrayList<RectF>()
    private val motoBackgroundPath = Path()
    private val motoBlurKeys = Array(7) { Any() }
    private val motoBlurBounds = Rect()
    private val motoBlurRadii = FloatArray(8)
    private val motoSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var motoSelectedIndex = -1
    private val motoPreDrawListener = ViewTreeObserver.OnPreDrawListener {
        updateMotoIconBounds()
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(motoPreDrawListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(motoPreDrawListener)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (motoIconContainer == null) {
            super.onDraw(canvas)
            return
        }

        updateMotoIconBounds()
        val saveCount = canvas.save()
        canvas.clipPath(motoBackgroundPath)
        // Keep the shelf's tint, ripple, clipping and transparency inside each circle.
        super.onDraw(canvas)
        if (motoSelectedIndex >= 0) {
            motoSelectedPaint.color =
                context.getColor(com.android.internal.R.color.materialColorPrimary)
            canvas.drawOval(motoIconBounds[motoSelectedIndex], motoSelectedPaint)
        }
        canvas.restoreToCount(saveCount)
    }

    override fun drawAxBlurBackground(canvas: Canvas, bounds: Rect): Boolean {
        if (motoIconContainer == null) return super.drawAxBlurBackground(canvas, bounds)
        var drewBlur = false
        motoIconBounds.forEachIndexed { index, iconBounds ->
            iconBounds.round(motoBlurBounds)
            motoBlurRadii.fill(iconBounds.width() / 2f)
            drewBlur =
                super.drawAxBlurBackground(
                    canvas,
                    motoBlurBounds,
                    motoBlurKeys[index],
                    motoBlurRadii,
                ) || drewBlur
        }
        return drewBlur
    }

    private fun updateMotoIconBounds() {
        val icons = motoIconContainer ?: return
        val baseRadius =
            min(
                resources.getDimension(R.dimen.moto_compact_notification_background_size),
                height.toFloat(),
            ) / 2f
        var count = 0
        var selectedIndex = -1
        var changed = false
        for (i in 0 until icons.childCount) {
            if (count == motoBlurKeys.size) break
            val icon = icons.getChildAt(i) as? StatusBarIconView ?: continue
            if (icon.visibility != VISIBLE || icon.visibleState == StatusBarIconView.STATE_HIDDEN) {
                continue
            }
            val radius = baseRadius * min(icon.scaleX, icon.scaleY) * icon.alpha
            if (radius <= 0f) continue
            if (icon.isSelected && icon.visibleState == StatusBarIconView.STATE_ICON) {
                selectedIndex = count
            }
            // Both views share the shelf as their parent. Account for centered and RTL layouts,
            // and track the actual animated position instead of the icon's destination.
            val centerX = icons.x + icon.x + icon.width / 2f - x
            val centerY = icons.y + icon.y + icon.height / 2f - y
            val left = centerX - radius
            val top = centerY - radius
            val right = centerX + radius
            val bottom = centerY + radius
            if (count == motoIconBounds.size) {
                motoIconBounds.add(RectF(left, top, right, bottom))
                changed = true
            } else {
                val bounds = motoIconBounds[count]
                if (
                    bounds.left != left ||
                        bounds.top != top ||
                        bounds.right != right ||
                        bounds.bottom != bottom
                ) {
                    bounds.set(left, top, right, bottom)
                    changed = true
                }
            }
            count++
        }
        while (motoIconBounds.size > count) {
            motoIconBounds.removeAt(motoIconBounds.lastIndex)
            changed = true
        }
        if (motoSelectedIndex != selectedIndex) {
            motoSelectedIndex = selectedIndex
            changed = true
        }
        if (changed) {
            motoBackgroundPath.rewind()
            motoIconBounds.forEach { motoBackgroundPath.addOval(it, Path.Direction.CW) }
            invalidate()
        }
    }

    /** @return whether the alignment of the notification shelf is right. */
    @VisibleForTesting
    public override fun isAlignedToRight(): Boolean {
        if (!NotificationMinimalism.isEnabled) {
            return super.isAlignedToRight()
        }
        if (alignToCenter) {
            return false
        }
        return alignToEnd xor isLayoutRtl
    }

    override fun toDumpString(): String {
        return super.toDumpString() +
            " alignToEnd=" + alignToEnd +
            " alignToCenter=" + alignToCenter +
            " motoCompact=" + (motoIconContainer != null)
    }
}
