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
import android.util.AttributeSet
import android.view.View
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.statusbar.notification.shared.NotificationMinimalism
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.StatusBarIconView
import kotlin.math.max

/** The NotificationIconContainer for the NotificationShelf. */
class NotificationShelfIconContainer
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) :
    NotificationIconContainer(context, attrs) {

    /** Whether the notification shelf is aligned to end. */
    var alignToEnd = false

    private var mAlignToCenterShelf = false

    override fun setAlignToCenter(alignToCenter: Boolean) {
        super.setAlignToCenter(alignToCenter)
        mAlignToCenterShelf = alignToCenter
    }

    override fun isAlignToCenter(): Boolean = mAlignToCenterShelf

    /**
     * @return The left boundary (not the RTL compatible start) of the area that icons can be added.
     */
    public override fun getLeftBound(): Float {
        if (!NotificationMinimalism.isEnabled) {
            return super.getLeftBound()
        }

        if (mAlignToCenterShelf) {
            return centeredIconLeftBound()
        }

        if (isAlignedToRight) {
            return (max(width - actualWidth, 0) + actualPaddingStart)
        }
        return actualPaddingStart
    }

    /**
     * @return The right boundary (not the RTL compatible end) of the area that icons can be added.
     */
    @VisibleForTesting
    public override fun getRightBound(): Float {
        if (!NotificationMinimalism.isEnabled) {
            return super.getRightBound()
        }

        if (mAlignToCenterShelf) {
            val contentWidth = calculateCenteredContentWidth()
            val blockLeft = centeredBlockLeft(contentWidth)
            return (blockLeft + contentWidth - actualPaddingEnd).coerceAtMost(width.toFloat())
        }

        if (isAlignedToRight) {
            return width - actualPaddingEnd
        }
        return actualWidth - actualPaddingEnd
    }

    private fun centeredIconLeftBound(): Float {
        val contentWidth = calculateCenteredContentWidth()
        val blockLeft = centeredBlockLeft(contentWidth)
        return (blockLeft + actualPaddingStart).coerceAtLeast(0f)
    }

    private fun centeredBlockLeft(contentWidth: Float): Float {
        return ((width - contentWidth) / 2f).coerceAtLeast(0f)
    }

    private fun calculateCenteredContentWidth(): Float {
        return actualWidth.toFloat()
    }

    /**
     * For RTL, the icons' x positions should be mirrored around the middle of the shelf so that the
     * icons are also added to the shelf from right to left. This function should only be called
     * when RTL.
     */
    override fun getRtlIconTranslationX(iconState: IconState, iconView: View): Float {
        if (!NotificationMinimalism.isEnabled) {
            return super.getRtlIconTranslationX(iconState, iconView)
        }

        if (mAlignToCenterShelf) {
            val contentWidth = calculateCenteredContentWidth()
            val blockLeft = centeredBlockLeft(contentWidth)
            val iconLeft = (blockLeft + actualPaddingStart).coerceAtLeast(0f)
            val iconRight = (blockLeft + contentWidth - actualPaddingEnd).coerceAtMost(width.toFloat())
            return (iconRight - (iconState.xTranslation - iconLeft) - iconView.width)
                .coerceAtLeast(0f)
        }

        if (!isLayoutRtl) {
            return iconState.xTranslation
        }

        if (isAlignedToRight) {
            return width * 2 - actualWidth - iconState.xTranslation - iconView.width
        }
        return actualWidth - iconState.xTranslation - iconView.width
    }

    @VisibleForTesting
    val isAlignedToRight: Boolean
        get() {
            if (!NotificationMinimalism.isEnabled) {
                return isLayoutRtl
            }
            return alignToEnd xor isLayoutRtl
        }
}
