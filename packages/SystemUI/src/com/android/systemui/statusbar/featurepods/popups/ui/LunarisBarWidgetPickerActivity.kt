/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.statusbar.featurepods.popups.ui

import android.app.Activity
import android.app.ActivityManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import com.android.internal.util.lunaris.LunarisBarSettings

class LunarisBarWidgetPickerActivity : Activity() {
    private lateinit var host: AppWidgetHost
    private lateinit var manager: AppWidgetManager
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var saved = false
    private var targetUser = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        targetUser = state?.getInt("user") ?: ActivityManager.getCurrentUser()
        val userContext = createContextAsUser(UserHandle.of(targetUser), 0)
        host = AppWidgetHost(userContext, LunarisBarSettings.WIDGET_HOST_ID)
        manager = AppWidgetManager.getInstance(userContext)
        widgetId =
            state?.getInt("widget", AppWidgetManager.INVALID_APPWIDGET_ID)
                ?: AppWidgetManager.INVALID_APPWIDGET_ID
        saved = state?.getBoolean("saved", false) ?: false
        if (state != null) {
            if (
                saved ||
                    widgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
                    targetUser != ActivityManager.getCurrentUser()
            )
                finish()
            return
        }
        val provider = intent.getStringExtra("provider")?.let(ComponentName::unflattenFromString)
        val info = manager.installedProviders.firstOrNull { it.provider == provider }
        if (
            info == null ||
                LunarisBarSettings.read(contentResolver, targetUser).cards.size >=
                    LunarisBarSettings.MAX_CARDS
        ) {
            finish()
            return
        }
        widgetId = host.allocateAppWidgetId()
        if (manager.bindAppWidgetIdIfAllowed(widgetId, info.provider)) configure()
        else
            runCatching {
                    startActivityForResult(
                        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider),
                        1,
                    )
                }
                .onFailure { finish() }
    }

    private fun configure() {
        val info = manager.getAppWidgetInfo(widgetId)
        if (info == null) {
            finish()
        } else if (info.configure != null) {
            runCatching {
                    host.startAppWidgetConfigureActivityForResult(this, widgetId, 0, 2, null)
                }
                .onFailure { finish() }
        } else saveWidget()
    }

    private fun saveWidget() {
        if (targetUser != ActivityManager.getCurrentUser()) {
            finish()
            return
        }
        val info =
            manager.getAppWidgetInfo(widgetId)
                ?: run {
                    finish()
                    return
                }
        val settings = LunarisBarSettings.read(contentResolver, targetUser)
        if (settings.cards.size >= LunarisBarSettings.MAX_CARDS) {
            finish()
            return
        }
        val card =
            LunarisBarSettings.Card.create("widget", info.loadLabel(packageManager))
                .with("widget_id", widgetId)
                .with("provider", info.provider.flattenToString())
                .with("height", info.minHeight.coerceIn(64, 480))
        saved = settings.withCards(settings.cards + card).write(contentResolver, targetUser)
        setResult(if (saved) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            finish()
        } else if (requestCode == 1) configure() else if (requestCode == 2) saveWidget()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("user", targetUser)
        outState.putInt("widget", widgetId)
        outState.putBoolean("saved", saved)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing && !saved && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            runCatching { host.deleteAppWidgetId(widgetId) }
        }
        super.onDestroy()
    }
}
