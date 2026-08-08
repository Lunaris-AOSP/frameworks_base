/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.shade.ui

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import com.android.internal.graphics.ColorUtils
import com.android.systemui.res.R

object ShadeColors {

    @JvmField
    val CUSTOM_COLOR_KEYS = arrayOf(
        Settings.System.CUSTOM_SHADE_COLOR_ENABLED,
        Settings.System.CUSTOM_SHADE_COLOR,
        Settings.System.CUSTOM_NOTIF_SCRIM_COLOR_ENABLED,
        Settings.System.CUSTOM_NOTIF_SCRIM_COLOR,
    )

    /**
     * Calculate notification shade panel color.
     *
     * @param context Context to resolve colors.
     * @param blurSupported Whether blur is enabled (can be off due to battery saver)
     * @param withScrim Whether to composite a scrim when blur is enabled (used by legacy shade).
     * @return color for the shade panel.
     */
    @JvmStatic
    fun shadePanel(
        context: Context,
        blurSupported: Boolean,
        withScrim: Boolean,
        allowCustomColor: Boolean = true,
    ): Int {
        return if (blurSupported) {
            if (withScrim) {
                ColorUtils.compositeColors(
                    shadePanelStandard(context, allowCustomColor),
                    shadePanelScrimBehind(context),
                )
            } else {
                shadePanelStandard(context, allowCustomColor)
            }
        } else {
            shadePanelFallback(context)
        }
    }

    @JvmStatic
    fun notificationScrim(context: Context, blurSupported: Boolean): Int {
        return if (blurSupported) {
            notificationScrimStandard(context)
        } else {
            notificationScrimFallback(context)
        }
    }

    @JvmStatic
    fun shadePanelScrimBehind(context: Context): Int {
        return context.resources.getColor(
            com.android.internal.R.color.shade_panel_scrim,
            context.theme,
        )
    }

    @JvmStatic
    private fun shadePanelStandard(context: Context, allowCustomColor: Boolean = true): Int {
        val customColor = if (allowCustomColor) getCustomShadeColor(context) else null
        if (customColor != null) {
            return customColor
        }
        val layerAbove =
            context.resources.getColor(com.android.internal.R.color.shade_panel_fg, context.theme)
        val layerBelow =
            context.resources.getColor(com.android.internal.R.color.shade_panel_bg, context.theme)
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    @JvmStatic
    private fun shadePanelFallback(context: Context): Int {
        val customColor = getCustomShadeColor(context)
        if (customColor != null) {
            return customColor
        }
        return ColorUtils.blendARGB(
            context.getColor(R.color.nt_scrim_behind_1),
            context.getColor(R.color.nt_scrim_behind_2),
            0.5f
        )
    }

    @JvmStatic
    private fun notificationScrimStandard(context: Context): Int {
        val customColor = getCustomNotifScrimColor(context)
        if (customColor != null) {
            return ColorUtils.setAlphaComponent(customColor, (0.5f * 255).toInt())
        }
        return ColorUtils.setAlphaComponent(
            context.getColor(R.color.notification_scrim_base),
            (0.5f * 255).toInt(),
        )
    }

    @JvmStatic
    private fun notificationScrimFallback(context: Context): Int {
        val customColor = getCustomNotifScrimColor(context)
        if (customColor != null) {
            return ColorUtils.setAlphaComponent(customColor, (0.2f * 255).toInt())
        }
        return ColorUtils.blendARGB(
            context.getColor(R.color.nt_notification_behind_1),
            context.getColor(R.color.nt_notification_behind_2),
            0.2f
        )
    }

    @JvmStatic
    private fun getCustomShadeColor(context: Context): Int? {
        val enabled = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.CUSTOM_SHADE_COLOR_ENABLED,
            0,
            UserHandle.USER_CURRENT,
        ) == 1
        if (!enabled) return null

        val color = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.CUSTOM_SHADE_COLOR,
            Int.MIN_VALUE,
            UserHandle.USER_CURRENT,
        )
        return if (color == Int.MIN_VALUE) null else color
    }

    @JvmStatic
    private fun getCustomNotifScrimColor(context: Context): Int? {
        val enabled = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.CUSTOM_NOTIF_SCRIM_COLOR_ENABLED,
            0,
            UserHandle.USER_CURRENT,
        ) == 1
        if (!enabled) return null

        val color = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.CUSTOM_NOTIF_SCRIM_COLOR,
            Int.MIN_VALUE,
            UserHandle.USER_CURRENT,
        )
        return if (color == Int.MIN_VALUE) null else color
    }
}
