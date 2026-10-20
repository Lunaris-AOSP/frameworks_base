/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.data.repository

import android.appwidget.AppWidgetHost
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.LunarisQsWidget

/** Called in the widget owner's user process, including on secondary users. */
internal object LunarisQsWidgetStore {
    fun read(context: Context, layout: AxQsLayout): LunarisQsWidget =
        LunarisQsWidget.fromJson(
            Settings.Secure.getString(context.contentResolver, LunarisQsWidget.settingKey(layout)),
            context.userId,
        )

    fun save(context: Context, layout: AxQsLayout, widget: LunarisQsWidget) {
        require(widget.userId == context.userId)
        val previous = read(context, layout)
        check(
            Settings.Secure.putString(
                context.contentResolver,
                LunarisQsWidget.settingKey(layout),
                if (widget.isConfigured) widget.toJson() else "",
            )
        )
        if (previous.isConfigured && previous.appWidgetId != widget.appWidgetId) {
            try {
                AppWidgetHost(context, LunarisQsWidget.hostId(layout))
                    .deleteAppWidgetId(previous.appWidgetId)
            } catch (e: RuntimeException) {
                Log.w("LunarisQsWidgetStore", "Cannot release previous widget", e)
            }
        }
    }
}
