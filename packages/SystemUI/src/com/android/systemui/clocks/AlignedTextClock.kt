/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.clocks

import android.content.Context
import android.util.AttributeSet
import android.widget.TextClock
import androidx.annotation.Keep
import org.lunaris.clockmaker.ClockDigitAlignment
import org.lunaris.clockmaker.ClockDigits

class AlignedTextClock @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextClock(context, attrs, defStyleAttr) {
    private var configuredAlignment: ClockDigitAlignment? = null
    var digitAlignment: ClockDigitAlignment
        get() = configuredAlignment ?: ClockDigitAlignment.FONT
        set(value) {
            if (digitAlignment == value) return
            configuredAlignment = value
            setText(text)
        }

    @Keep
    fun setDigitAlignment(value: String) {
        digitAlignment = ClockDigitAlignment.parse(value)
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text?.let { ClockDigits.align(it, digitAlignment) }, type)
    }
}
