/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.ui.edit

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.compose.theme.PlatformTheme
import com.android.systemui.qs.panels.data.repository.LunarisQsWidgetStore
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.LunarisQsWidget
import com.android.systemui.qs.panels.widgets.qsWidgetOptions
import com.android.systemui.qs.panels.widgets.qsWidgetSize
import com.android.systemui.res.R
import kotlin.math.roundToInt

class LunarisQsWidgetActivity : ComponentActivity() {
    private lateinit var panel: AxQsLayout
    private lateinit var host: AppWidgetHost
    private val manager by lazy { AppWidgetManager.getInstance(this) }
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var waiting by mutableStateOf(false)
    private var error by mutableStateOf<Int?>(null)
    private var selected by mutableStateOf(LunarisQsWidget())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        enableEdgeToEdge()
        panel =
            AxQsLayout.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_LAYOUT) }
                ?: run {
                    finish()
                    return
                }
        host = AppWidgetHost(this, LunarisQsWidget.hostId(panel))
        pendingId = savedInstanceState?.getInt(STATE_PENDING, -1) ?: -1
        waiting = savedInstanceState?.getBoolean(STATE_WAITING) ?: false
        try {
            selected = LunarisQsWidgetStore.read(this, panel)
            host.appWidgetIds
                .filter { it != selected.appWidgetId && it != pendingId }
                .forEach(host::deleteAppWidgetId)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot load widget selection", e)
            error = R.string.lunaris_qs_widget_error
        }
        setContent {
            PlatformTheme {
                LunarisQsWidgetPicker(
                    panel = panel,
                    frameWidth = dimension(EXTRA_WIDTH),
                    frameHeight = dimension(EXTRA_HEIGHT),
                    selected = selected,
                    busy = waiting,
                    error = error,
                    onSelect = ::select,
                    onRemove = ::remove,
                    onBack = ::finish,
                )
            }
        }
    }

    private fun dimension(extra: String): Float =
        intent.getFloatExtra(extra, 160f).takeIf { it.isFinite() }?.coerceIn(32f, 1600f) ?: 160f

    private fun select(info: AppWidgetProviderInfo) {
        if (waiting || isFinishing) return
        waiting = true
        error = null
        try {
            cancelPending()
            check(info.profile == Process.myUserHandle())
            pendingId = host.allocateAppWidgetId()
            check(pendingId > 0)
            val density = resources.displayMetrics.density
            val padding = AppWidgetHostView.getDefaultPaddingForWidget(this, info.provider, null)
            val size =
                qsWidgetSize(
                    this,
                    info,
                    (dimension(EXTRA_WIDTH) * density).roundToInt(),
                    (dimension(EXTRA_HEIGHT) * density).roundToInt(),
                    padding,
                )
            val options =
                qsWidgetOptions(
                    (size.width - padding.left - padding.right).coerceAtLeast(1) / density,
                    (size.height - padding.top - padding.bottom).coerceAtLeast(1) / density,
                )
            if (manager.bindAppWidgetIdIfAllowed(pendingId, info.profile, info.provider, options)) {
                configure()
            } else {
                waiting = true
                startActivityForResult(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
                    },
                    REQUEST_BIND,
                )
            }
        } catch (e: RuntimeException) {
            fail(e)
        }
    }

    private fun configure() {
        val info = manager.getAppWidgetInfo(pendingId) ?: return fail()
        if (info.configure != null) {
            waiting = true
            host.startAppWidgetConfigureActivityForResult(
                this,
                pendingId,
                0,
                REQUEST_CONFIGURE,
                null,
            )
        } else {
            commit()
        }
    }

    private fun commit() {
        val info = manager.getAppWidgetInfo(pendingId) ?: return fail()
        check(host.appWidgetIds.contains(pendingId))
        val widget = LunarisQsWidget(userId, pendingId, info.provider)
        LunarisQsWidgetStore.save(this, panel, widget)
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        selected = widget
        waiting = false
        setResult(RESULT_OK)
        finish()
    }

    private fun remove() {
        if (waiting || isFinishing) return
        waiting = true
        try {
            LunarisQsWidgetStore.save(this, panel, LunarisQsWidget(userId))
            setResult(RESULT_OK)
            finish()
        } catch (e: RuntimeException) {
            fail(e)
        }
    }

    private fun cancelPending() {
        if (!::host.isInitialized || pendingId <= 0) return
        val id = pendingId
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        try {
            if (LunarisQsWidgetStore.read(this, panel).appWidgetId != id) host.deleteAppWidgetId(id)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot release pending widget", e)
        }
    }

    private fun fail(exception: Exception? = null) {
        Log.w(TAG, "Cannot select widget", exception)
        waiting = false
        cancelPending()
        error = R.string.lunaris_qs_widget_error
    }

    @Deprecated("Provider configuration uses AppWidgetHost's activity-result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BIND && requestCode != REQUEST_CONFIGURE) return
        waiting = false
        if (resultCode != RESULT_OK) {
            cancelPending()
            return
        }
        try {
            if (requestCode == REQUEST_BIND) configure() else commit()
        } catch (e: RuntimeException) {
            fail(e)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PENDING, pendingId)
        outState.putBoolean(STATE_WAITING, waiting)
    }

    override fun onDestroy() {
        if (isFinishing) cancelPending()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LAYOUT = "layout"
        const val EXTRA_WIDTH = "width_dp"
        const val EXTRA_HEIGHT = "height_dp"
        private const val STATE_PENDING = "pending_widget"
        private const val STATE_WAITING = "waiting_for_provider"
        private const val REQUEST_BIND = 1
        private const val REQUEST_CONFIGURE = 2
        private const val TAG = "LunarisQsWidgetPicker"
    }
}
