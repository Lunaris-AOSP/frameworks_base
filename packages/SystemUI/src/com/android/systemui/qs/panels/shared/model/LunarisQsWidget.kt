/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.shared.model

import android.content.ComponentName
import org.json.JSONException
import org.json.JSONObject

data class LunarisQsWidget(
    val userId: Int = -1,
    val appWidgetId: Int = -1,
    val provider: ComponentName? = null,
) {
    val isConfigured: Boolean
        get() = userId >= 0 && appWidgetId > 0 && provider != null

    fun toJson(): String =
        JSONObject()
            .put("id", appWidgetId)
            .put("provider", provider?.flattenToString())
            .toString()

    companion object {
        fun settingKey(layout: AxQsLayout): String =
            when (layout) {
                AxQsLayout.QQS -> "lunaris_qqs_app_widget"
                AxQsLayout.QS -> "lunaris_qs_app_widget"
                AxQsLayout.SPLIT_SHADE -> "lunaris_qs_split_shade_app_widget"
            }

        fun hostId(layout: AxQsLayout): Int =
            when (layout) {
                AxQsLayout.QQS -> 0x4c515301
                AxQsLayout.QS -> 0x4c515302
                AxQsLayout.SPLIT_SHADE -> 0x4c515303
            }

        fun fromJson(value: String?, userId: Int): LunarisQsWidget {
            if (value.isNullOrBlank()) return LunarisQsWidget(userId)
            return try {
                val json = JSONObject(value)
                LunarisQsWidget(
                    userId,
                    json.optInt("id", -1),
                    ComponentName.unflattenFromString(json.optString("provider")),
                ).takeIf { it.isConfigured } ?: LunarisQsWidget(userId)
            } catch (_: JSONException) {
                LunarisQsWidget(userId)
            }
        }
    }
}
