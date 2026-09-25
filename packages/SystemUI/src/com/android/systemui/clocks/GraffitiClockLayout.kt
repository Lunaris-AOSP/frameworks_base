/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import java.util.Locale

/** Fixed slots keep the clock steady as the handwritten numbers and date change. */
internal object GraffitiClockLayout {
    data class Glyph(
        val name: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
    )

    data class Layout(val width: Float, val height: Float, val glyphs: List<Glyph>)

    fun create(
        mode: Int,
        hour: Int,
        minute: Int,
        month: Int,
        day: Int,
        weekday: Int,
    ): Layout {
        val hourName = String.format(Locale.ROOT, "graffiti_hour_%02d", hour)
        val minuteName = String.format(Locale.ROOT, "graffiti_minute_%02d", minute)
        val dayName = String.format(Locale.ROOT, "graffiti_day_%02d", day)
        val monthName = "graffiti_month_$month"
        // The supplied stickers run Monday (1) through Sunday (7).
        val weekName = "graffiti_week_${(weekday + 5) % 7 + 1}"

        return when (mode) {
            HyperClockView.MODE_GRAFFITI_ART -> Layout(
                1080f, 720f,
                listOf(
                    Glyph(hourName, 130f, 0f, 380f, 420f),
                    Glyph(minuteName, 530f, 292f, 396f, 423f),
                    Glyph("graffiti_dot", 500f, 196f, 50f, 96f),
                    Glyph(monthName, 590f, 0f, 474f, 154f),
                    Glyph(dayName, 858f, 170f, 200f, 178f),
                    Glyph("graffiti_today", 30f, 506f, 192f, 66f),
                    Glyph(weekName, 0f, 548f, 442f, 172f),
                ),
            )
            HyperClockView.MODE_GRAFFITI_STACK -> Layout(
                1080f, 1250f,
                listOf(
                    Glyph(monthName, 236f, 0f, 608f, 150f),
                    Glyph(hourName, 330f, 135f, 420f, 464f),
                    Glyph(minuteName, 330f, 615f, 420f, 448f),
                    Glyph(dayName, 42f, 340f, 224f, 200f),
                    Glyph("graffiti_greeting", 822f, 590f, 210f, 246f),
                    Glyph(weekName, 262f, 1080f, 556f, 170f),
                ),
            )
            HyperClockView.MODE_GRAFFITI_COMPACT -> Layout(
                1080f, 590f,
                listOf(
                    Glyph(monthName, 24f, 0f, 464f, 134f),
                    Glyph(dayName, 530f, 22f, 120f, 108f),
                    Glyph(weekName, 702f, 0f, 354f, 146f),
                    Glyph(hourName, 72f, 142f, 400f, 442f),
                    Glyph("graffiti_dot", 512f, 290f, 56f, 108f),
                    Glyph(minuteName, 610f, 148f, 400f, 427f),
                ),
            )
            else -> error("Not a graffiti clock mode: $mode")
        }
    }
}
