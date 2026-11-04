/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ClockTimePart {
    FULL, HOUR_TENS, HOUR_ONES, MINUTE_TENS, MINUTE_ONES, SECOND_TENS, SECOND_ONES;

    val isHour: Boolean get() = this == HOUR_TENS || this == HOUR_ONES
    val isSecond: Boolean get() = this == SECOND_TENS || this == SECOND_ONES
}

enum class ClockHourFormat { SYSTEM, H12, H24 }

object ClockTime {
    fun format(part: ClockTimePart, hourFormat: ClockHourFormat, customFormat: String,
        now: Date, locale: Locale, system24Hour: Boolean): String {
        val use24Hour = when (hourFormat) {
            ClockHourFormat.SYSTEM -> system24Hour
            ClockHourFormat.H12 -> false
            ClockHourFormat.H24 -> true
        }
        val pattern = when (part) {
            ClockTimePart.FULL -> customFormat.ifEmpty { if (system24Hour) "HH:mm" else "h:mm" }
            ClockTimePart.HOUR_TENS, ClockTimePart.HOUR_ONES -> if (use24Hour) "HH" else "hh"
            ClockTimePart.MINUTE_TENS, ClockTimePart.MINUTE_ONES -> "mm"
            ClockTimePart.SECOND_TENS, ClockTimePart.SECOND_ONES -> "ss"
        }
        val text = runCatching { SimpleDateFormat(pattern, locale).format(now) }.getOrDefault("")
        if (part == ClockTimePart.FULL) return text
        val index = when (part) {
            ClockTimePart.HOUR_TENS, ClockTimePart.MINUTE_TENS, ClockTimePart.SECOND_TENS -> 0
            else -> 1
        }
        if (text.codePointCount(0, text.length) <= index) return ""
        val start = text.offsetByCodePoints(0, index)
        return text.substring(start, text.offsetByCodePoints(start, 1))
    }
}
