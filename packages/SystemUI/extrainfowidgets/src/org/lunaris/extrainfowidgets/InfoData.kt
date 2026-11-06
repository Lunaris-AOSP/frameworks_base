/* SPDX-License-Identifier: GPL-3.0-only */
package org.lunaris.extrainfowidgets

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.text.format.Formatter
import java.text.NumberFormat

data class InfoData(
    val battery: Int? = null,
    val charging: Boolean = false,
    val steps: Int? = null,
    val usage: Long? = null,
    val alarm: Long? = null,
    val alarmText: String? = null,
    val mediaTitle: String? = null,
    val mediaArtist: String? = null,
    val playing: Boolean = false,
    val art: Bitmap? = null,
    val notifications: Int? = null,
    val eventTitle: String? = null,
    val eventTime: String? = null,
    val tiles: Map<String, Int> = emptyMap(),
) {
    fun tile(name: String) =
        if (name == "MEDIA") {
            if (playing) 1 else 0
        } else tiles[name] ?: 0

    fun text(context: Context, type: String, format: String): String =
        when (type) {
            "TEXT" -> format
            "BATTERY_LEVEL" -> battery?.let { "$it%" } ?: "—"
            "STEPS" -> steps?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—"
            "DATA_USAGE" -> usage?.let { Formatter.formatShortFileSize(context, it) } ?: "—"
            "ALARM_TIME" ->
                alarmText
                    ?: alarm?.let { DateFormat.getTimeFormat(context).format(it) }
                    ?: context.getString(R.string.extra_info_no_alarm)
            "CHARGE_STATUS" ->
                context.getString(
                    when {
                        charging && battery == 100 -> R.string.extra_info_charged
                        charging -> R.string.extra_info_charging
                        else -> R.string.extra_info_not_charging
                    }
                )
            "MEDIA_TITLE" -> mediaTitle ?: context.getString(R.string.extra_info_no_media)
            "MEDIA_ARTIST" -> mediaArtist.orEmpty()
            "NOTIFICATION_COUNT" -> notifications?.toString() ?: "—"
            "EVENT_TITLE" -> eventTitle ?: context.getString(R.string.extra_info_no_events)
            "EVENT_TIME" -> eventTime.orEmpty()
            "BATTERY_ICON" -> {
                val index = (((battery ?: 100).coerceIn(0, 100) * 7 + 50) / 100)
                (if (charging) CHARGING else BATTERY)[index].toString()
            }
            "ALARM_ICON" -> "\uE855"
            "CHARGE_ICON" -> "\uEA0B"
            "MEDIA_ICON" -> if (playing) "\uE1B8" else "\uE405"
            "NOTIFICATION_ICON" -> "\uE7F7"
            "EVENT_ICON" -> "\uE878"
            else -> ""
        }

    fun glyph(tile: String): String {
        val on = tile(tile) != 0
        return when (tile) {
            "WIFI" -> if (on) "\uE63E" else "\uE648"
            "BLUETOOTH" -> if (on) "\uE1A7" else "\uE1A9"
            "FLASHLIGHT" -> if (on) "\uF00B" else "\uF00A"
            "MOBILE_DATA" -> if (on) "\uE202" else "\uF034"
            "HOTSPOT" -> if (on) "\uE1E2" else "\uF087"
            "RINGER" ->
                when (tile(tile)) {
                    2 -> "\uF2CB"
                    1 -> "\uE7F7"
                    else -> "\uE7F6"
                }
            "DND" -> if (on) "\uF08F" else "\uF08E"
            "MEDIA" -> if (playing) "\uE034" else "\uE037"
            "MEDIA_PREVIOUS" -> "\uE045"
            "MEDIA_NEXT" -> "\uE044"
            else -> ""
        }
    }

    companion object {
        private val BATTERY =
            charArrayOf(
                '\uEBDC',
                '\uF09C',
                '\uF09D',
                '\uF09E',
                '\uF09F',
                '\uF0A0',
                '\uF0A1',
                '\uE1A5',
            )
        private val CHARGING =
            charArrayOf(
                '\uF0A2',
                '\uF0A2',
                '\uF0A3',
                '\uF0A4',
                '\uF0A5',
                '\uF0A6',
                '\uF0A7',
                '\uE1A3',
            )
        val SAMPLE =
            InfoData(
                battery = 76,
                charging = true,
                steps = 6240,
                usage = 2_684_354_560,
                alarm = 1_799_456_400_000,
                mediaTitle = "Morning light",
                mediaArtist = "Your music",
                playing = true,
                notifications = 3,
                eventTitle = "Next event",
                eventTime = "10:30",
                tiles = mapOf("WIFI" to 1, "BLUETOOTH" to 1, "MOBILE_DATA" to 1, "RINGER" to 1),
            )
    }
}
