/*
 * Copyright (C) 2025 AxionOS Project
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
package com.android.systemui.statusbar.policy.networkspeed

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusIconDisplayable
import com.android.systemui.statusbar.policy.NetworkSpeedController

class NetworkSpeedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), StatusIconDisplayable {

    companion object {
        private const val TAG = "NetworkSpeedView"

        private const val KB = 1024L
        private const val MB = KB * 1024
        private const val GB = MB * 1024

        fun fromContext(context: Context, slot: String, blocked: Boolean): NetworkSpeedView {
            val view = LayoutInflater.from(context)
                .inflate(R.layout.network_speed_view, null) as NetworkSpeedView
            view.setSlot(slot)
            view.isBlocked = blocked
            return view
        }
    }

    private var isBlocked: Boolean = false
    private var speedNumber: TextView? = null
    private var speedUnit: TextView? = null
    private lateinit var speedText: LinearLayout
    private lateinit var dotView: StatusBarIconView
    private var slot: String = NetworkSpeedController.SLOT_NETWORK_SPEED
    private var speed: Long = 0L
    private var iconTint: Int = -1
    private var isStaticDrawableColor: Boolean = false
    private var visibleState: Int = 0
    private var layoutLeft: Int = -1
    private var darkIntensity: Float = 0f
    private var tintArea: MutableList<Rect> = mutableListOf()
    private var state: NetworkSpeedIconState? = null
    private var visibleByPolicy = true

    override fun onFinishInflate() {
        super.onFinishInflate()
        speedNumber = findViewById(R.id.number)
        speedUnit = findViewById(R.id.unit)
        speedText = findViewById(R.id.network_speed_text)
        initDotView()
    }

    private fun initDotView() {
        dotView = StatusBarIconView(context, slot, null)
        dotView.visibleState = StatusBarIconView.STATE_ICON
        dotView.visibility = View.GONE

        val size = context.resources.getDimensionPixelSize(R.dimen.status_bar_icon_size)
        val layoutParams = LayoutParams(size, size).apply {
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        }
        addView(dotView, layoutParams)
    }

    private fun updateNetworkSpeed(parts: List<String>) {
        if (parts.size < 2) return
        speedNumber?.text = parts[0]
        speedUnit?.text = parts[1]
        contentDescription = parts.joinToString(" ")
    }

    private fun speedChanged(newSpeed: Long) {
        if (newSpeed < 0) return

        speed = newSpeed
        val formatted = formatSpeed(speed)
        updateNetworkSpeed(formatted.split(":"))
    }

    private fun formatSpeed(speed: Long): String {
        val divisor: Long
        val unit: String
        val decimals: Int

        when {
            speed < 1024 -> {
                divisor = KB
                unit = "KB"
                decimals = 2
            }
            speed < 10240 -> {
                divisor = KB
                unit = "KB"
                decimals = 2
            }
            speed < 102400 -> {
                divisor = KB
                unit = "KB"
                decimals = 1
            }
            speed < 1024000 -> {
                divisor = KB
                unit = "KB"
                decimals = 0
            }
            speed < 10485760 -> {
                divisor = MB
                unit = "MB"
                decimals = 2
            }
            speed < 104857600 -> {
                divisor = MB
                unit = "MB"
                decimals = 1
            }
            speed < 1048576000 -> {
                divisor = MB
                unit = "MB"
                decimals = 0
            }
            speed < 10737418240L -> {
                divisor = GB
                unit = "GB"
                decimals = 2
            }
            speed < 107374182400L -> {
                divisor = GB
                unit = "GB"
                decimals = 1
            }
            else -> {
                divisor = GB
                unit = "GB"
                decimals = 0
            }
        }

        val value = divToFractionDigits(speed, divisor, decimals)
        return "$value:$unit/s"
    }

    private fun divToFractionDigits(number: Long, divisor: Long, maxNum: Int): String {
        if (divisor == 0L) {
            Log.e(TAG, "Cannot divide by zero")
            return "Error"
        }

        val whole = number / divisor
        var remainder = number % divisor
        val builder = StringBuilder().append(whole)

        if (maxNum > 0) {
            builder.append(".")
            repeat(maxNum) {
                remainder *= 10
                builder.append(remainder / divisor)
                remainder %= divisor
            }
        }

        return builder.toString()
    }

    fun setSlot(slot: String) {
        this.slot = slot
    }

    override fun getSlot(): String = slot

    override fun setVisibleState(state: Int, animate: Boolean) {
        if (this.visibleState == state) return

        this.visibleState = state
        when (state) {
            0 -> {
                speedNumber?.visibility = View.VISIBLE
                speedUnit?.visibility = View.VISIBLE
                dotView.visibility = View.GONE
            }
            1 -> {
                speedNumber?.visibility = View.INVISIBLE
                speedUnit?.visibility = View.INVISIBLE
                dotView.visibility = View.VISIBLE
            }
            else -> {
                speedNumber?.visibility = View.INVISIBLE
                speedUnit?.visibility = View.INVISIBLE
                dotView.visibility = View.INVISIBLE
            }
        }
    }

    override fun getVisibleState(): Int = visibleState

    override fun isIconVisible(): Boolean = state?.isVisible() ?: false

    override fun setDecorColor(color: Int) {
        dotView.setDecorColor(color)
    }

    fun setIconTint(tint: Int) {
        speedNumber?.setTextColor(tint)
        speedUnit?.setTextColor(tint)
    }

    override fun setStaticDrawableColor(color: Int) {
        isStaticDrawableColor = true
        setIconTint(color)
        dotView.setDecorColor(color)
    }

    override fun onDarkChanged(area: ArrayList<Rect>, darkIntensity: Float, tint: Int) {
        if (isStaticDrawableColor) return

        tintArea.clear()
        tintArea.addAll(area)
        iconTint = tint
        this.darkIntensity = darkIntensity

        val appliedTint = DarkIconDispatcher.getTint(area, this, tint)
        setIconTint(appliedTint)
        dotView.setDecorColor(tint)
        dotView.setIconColor(tint, false)
    }

    fun applyNetworkState(newState: NetworkSpeedIconState?) {
        if (newState == null) {
            visibility = View.GONE
            state = null
            return
        }

        if (state == null || state?.singleLine != newState.singleLine) {
            updateTextLayout(newState.singleLine)
        }
        state = newState.copy()
        updateVisibility()

        if (newState.isVisible()) {
            speedChanged(newState.getSpeedText())
        }
    }

    fun setVisibleByPolicy(visible: Boolean) {
        if (visibleByPolicy == visible) return
        visibleByPolicy = visible
        updateVisibility()
    }

    private fun updateVisibility() {
        visibility = when {
            state?.isVisible() != true -> View.GONE
            !visibleByPolicy -> View.INVISIBLE
            else -> View.VISIBLE
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        minimumWidth = resources.getDimensionPixelSize(R.dimen.network_speed_view_width)
        setPaddingRelative(paddingStart, paddingTop,
            resources.getDimensionPixelSize(R.dimen.network_speed_view_padding_end), paddingBottom)
        updateTextLayout(state?.singleLine ?: false)
        val size = resources.getDimensionPixelSize(R.dimen.status_bar_icon_size)
        dotView.layoutParams = dotView.layoutParams.apply {
            width = size
            height = size
        }
    }

    private fun updateTextLayout(singleLine: Boolean) {
        speedText.orientation = if (singleLine) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        speedNumber?.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(
            if (singleLine) R.dimen.network_speed_single_line_text_size
            else R.dimen.network_speed_text_size))
        speedUnit?.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(
            if (singleLine) R.dimen.network_speed_single_line_unit_text_size
            else R.dimen.network_speed_unit_text_size))
        speedUnit?.let { unit ->
            val params = unit.layoutParams as LinearLayout.LayoutParams
            params.marginStart = if (singleLine) resources.getDimensionPixelSize(
                R.dimen.network_speed_single_line_spacing) else 0
            unit.layoutParams = params
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!isStaticDrawableColor && (changed || layoutLeft != left)) {
            layoutLeft = left
            setIconTint(DarkIconDispatcher.getTint(ArrayList(tintArea), this, iconTint))
        }
    }

    override fun isIconBlocked(): Boolean {
        return isBlocked
    }
}
