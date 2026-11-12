/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.extrainfowidgets

import org.lunaris.clockmaker.ClockGlassStyle

object InfoBackground {
    const val SETTING = "lockscreen_extra_info_widgets_background_style"
    const val GLASS_SETTING = "lockscreen_extra_info_widgets_glass_style"
    const val AX_BLUR = "ax_blur"
    const val LIQUID_GLASS = "liquid_glass"

    val GLASS_DEFAULT = ClockGlassStyle(glyphBlur = 0, tintOpacity = 20)

    fun style(value: String?): String = if (value == LIQUID_GLASS) LIQUID_GLASS else AX_BLUR

    fun glass(value: String?): ClockGlassStyle = ClockGlassStyle.parse(value, GLASS_DEFAULT)
}
