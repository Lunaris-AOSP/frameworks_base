/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.statusbar.pipeline.mobile.ui.view

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import com.android.systemui.Dependency
import com.android.systemui.res.R
import com.android.systemui.statusbar.core.NewStatusBarIcons
import com.android.systemui.tuner.TunerService
import kotlin.math.max
import kotlin.math.min

class MobileSignalIconLayout(context: Context, attrs: AttributeSet?) :
    LinearLayout(context, attrs), TunerService.Tunable {

    private var smallNetworkType = false
    private val networkTypeContainer: View
        get() = requireViewById(R.id.mobile_type_container)
    private val signalContainer: View
        get() = requireViewById(R.id.mobile_signal_container)
    private val useSmallLayout: Boolean
        get() = smallNetworkType && networkTypeContainer.visibility != GONE
    private val smallNetworkTypePadding: Int
        get() = resources.getDimensionPixelSize(R.dimen.status_bar_mobile_type_small_padding)

    init {
        setChildrenDrawingOrderEnabled(true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Dependency.get(TunerService::class.java).addTunable(this, SETTING)
    }

    override fun onDetachedFromWindow() {
        Dependency.get(TunerService::class.java).removeTunable(this)
        super.onDetachedFromWindow()
    }

    override fun onTuningChanged(key: String, newValue: String?) {
        if (key != SETTING) return
        val enabled = TunerService.parseIntegerSwitch(newValue, false)
        if (smallNetworkType == enabled) return
        smallNetworkType = enabled
        updateNetworkTypeSize()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (smallNetworkType) {
            updateNetworkTypeSize()
        }
    }

    fun updateNetworkTypeSize() {
        val typeSize =
            when {
                smallNetworkType -> R.dimen.status_bar_mobile_type_size_small
                NewStatusBarIcons.isEnabled -> R.dimen.status_bar_mobile_type_size_updated
                else -> R.dimen.status_bar_mobile_type_size
            }
        val containerSize =
            when {
                smallNetworkType -> R.dimen.status_bar_mobile_type_size_small
                NewStatusBarIcons.isEnabled -> R.dimen.status_bar_mobile_container_height_updated
                else -> R.dimen.status_bar_mobile_container_height
            }
        requireViewById<ImageView>(R.id.mobile_type).updateLayoutParams {
            height = resources.getDimensionPixelSize(typeSize)
        }
        networkTypeContainer.updateLayoutParams {
            height = resources.getDimensionPixelSize(containerSize)
        }
    }

    private fun overlap(): Int {
        if (requireViewById<View>(R.id.mobile_roaming_space).visibility != GONE) return 0
        return min(
            networkTypeContainer.measuredWidth,
            (signalContainer.measuredWidth / 4 - smallNetworkTypePadding).coerceAtLeast(0),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!useSmallLayout) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        measureChild(networkTypeContainer, widthMeasureSpec, heightMeasureSpec)
        measureChild(signalContainer, widthMeasureSpec, heightMeasureSpec)
        val width = networkTypeContainer.measuredWidth + signalContainer.measuredWidth - overlap()
        val height =
            max(
                networkTypeContainer.measuredHeight,
                signalContainer.measuredHeight + 2 * smallNetworkTypePadding,
            )
        val childState =
            combineMeasuredStates(networkTypeContainer.measuredState, signalContainer.measuredState)
        setMeasuredDimension(
            resolveSizeAndState(width + paddingLeft + paddingRight, widthMeasureSpec, childState),
            resolveSizeAndState(
                height + paddingTop + paddingBottom,
                heightMeasureSpec,
                childState shl MEASURED_HEIGHT_STATE_SHIFT,
            ),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!useSmallLayout) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }

        fun place(child: View, start: Int, y: Int) {
            val x =
                if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                    width - paddingRight - start - child.measuredWidth
                } else {
                    paddingLeft + start
                }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }

        place(networkTypeContainer, 0, paddingTop)
        place(
            signalContainer,
            networkTypeContainer.measuredWidth - overlap(),
            paddingTop + (height - paddingTop - paddingBottom - signalContainer.measuredHeight) / 2,
        )
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int =
        if (useSmallLayout) childCount - drawingPosition - 1 else drawingPosition

    companion object {
        const val SETTING = "system:" + Settings.System.STATUS_BAR_SMALL_NETWORK_TYPE
    }
}
