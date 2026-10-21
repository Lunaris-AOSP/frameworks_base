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

data class LunarisQsWidgetKey(val layout: AxQsLayout, val control: AxQsControl) {
    init {
        require(control.isAppWidget)
    }

    private val slot: Int
        get() = controls.indexOf(control)

    val settingKey: String
        get() {
            val base =
                when (layout) {
                    AxQsLayout.QQS -> "lunaris_qqs_app_widget"
                    AxQsLayout.QS -> "lunaris_qs_app_widget"
                    AxQsLayout.SPLIT_SHADE -> "lunaris_qs_split_shade_app_widget"
                }
            return if (slot == 0) base else "${base}_${slot + 1}"
        }

    val hostId: Int
        get() {
            val base =
                when (layout) {
                    AxQsLayout.QQS -> 0x4c515301
                    AxQsLayout.QS -> 0x4c515302
                    AxQsLayout.SPLIT_SHADE -> 0x4c515303
                }
            return base + slot * 0x10
        }

    companion object {
        val controls =
            listOf(AxQsControl.APP_WIDGET, AxQsControl.APP_WIDGET_2, AxQsControl.APP_WIDGET_3)
        val entries =
            AxQsLayout.entries.flatMap { layout -> controls.map { LunarisQsWidgetKey(layout, it) } }

        fun nextControl(currentIds: Set<String>): AxQsControl? =
            controls.firstOrNull { it.id !in currentIds }

        fun from(layout: String?, controlId: String?): LunarisQsWidgetKey? {
            val panel = AxQsLayout.entries.firstOrNull { it.name == layout } ?: return null
            val control =
                controls.firstOrNull { it.id == (controlId ?: AxQsControl.APP_WIDGET.id) }
                    ?: return null
            return LunarisQsWidgetKey(panel, control)
        }
    }
}
