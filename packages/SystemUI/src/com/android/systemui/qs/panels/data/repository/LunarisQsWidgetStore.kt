/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.data.repository

import android.appwidget.AppWidgetHost
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.android.systemui.qs.panels.shared.model.LunarisQsWidget
import com.android.systemui.qs.panels.shared.model.LunarisQsWidgetKey

/** Called in the widget owner's user process, including on secondary users. */
internal object LunarisQsWidgetStore {
    fun read(context: Context, key: LunarisQsWidgetKey): LunarisQsWidget =
        LunarisQsWidget.fromJson(
            Settings.Secure.getString(context.contentResolver, key.settingKey),
            context.userId,
        )

    fun save(context: Context, key: LunarisQsWidgetKey, widget: LunarisQsWidget) {
        require(widget.userId == context.userId)
        val previous = read(context, key)
        check(
            Settings.Secure.putString(
                context.contentResolver,
                key.settingKey,
                if (widget.isConfigured || !widget.showBackground) widget.toJson() else "",
            )
        )
        if (previous.isConfigured && previous.appWidgetId != widget.appWidgetId) {
            try {
                AppWidgetHost(context, key.hostId).deleteAppWidgetId(previous.appWidgetId)
            } catch (e: RuntimeException) {
                Log.w("LunarisQsWidgetStore", "Cannot release previous widget", e)
            }
        }
    }
}
