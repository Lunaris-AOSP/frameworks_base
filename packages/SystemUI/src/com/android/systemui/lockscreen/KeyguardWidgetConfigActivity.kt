/*
 * Copyright (C) 2025-2026 AxionOS Project
 * Copyright (C) 2026 Lunaris-AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.lockscreen

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.lunaris.lockscreenwidgets.R
import org.lunaris.lockscreenwidgets.WidgetEntry
import org.lunaris.lockscreenwidgets.WidgetSizes

class KeyguardWidgetConfigActivity : Activity() {
    private lateinit var store: KeyguardWidgetStore
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var existing = false
    private var waiting = false
    private var spanX = 1
    private var spanY = 1
    private var picker: AlertDialog? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = KeyguardWidgetStore(this)
        widgetId = state?.getInt("id", -1) ?: intent.getIntExtra("id", -1)
        existing = state?.getBoolean("existing") ?: (widgetId > 0)
        waiting = state?.getBoolean("waiting") ?: false
        spanX = state?.getInt("width", 1) ?: 1
        spanY = state?.getInt("height", 1) ?: 1
        if (state != null && waiting) return
        try {
            if (existing) {
                if (!store.owns(widgetId) || store.entries.none { it.id == widgetId }) { finish(); return }
                val info = store.manager.getAppWidgetInfo(widgetId)
                if (info?.configure == null || info.widgetFeatures and
                    AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE == 0) { finish(); return }
                configure()
            } else {
                store.reconcile()
                showPicker()
            }
        } catch (e: RuntimeException) { fail() }
    }

    private fun showPicker() {
        val width = store.gridWidth
        val sizes = WidgetSizes(this, width)
        val choices = store.manager.getInstalledProvidersForProfile(
            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN or AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD,
            android.os.Process.myUserHandle(), null)
            .filter { sizes.allowed(it).isNotEmpty() }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        if (choices.isEmpty()) {
            Toast.makeText(this, R.string.app_widgets_no_providers, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        picker = AlertDialog.Builder(this).setTitle(R.string.app_widgets_add)
            .setItems(choices.map { it.loadLabel(packageManager) }.toTypedArray()) { _, index ->
                val info = choices[index]
                val candidates = sizes.allowed(info)
                val preferred = info.targetCellWidth to info.targetCellHeight
                val ordered = candidates.sortedWith(compareBy<Pair<Int, Int>> { it != preferred }
                    .thenBy { it.first * it.second })
                val size = ordered.firstOrNull { (w, h) -> WidgetEntry.place(Int.MAX_VALUE, w, h, store.entries) != null }
                if (size == null) {
                    Toast.makeText(this, R.string.app_widgets_no_space, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    spanX = size.first
                    spanY = size.second
                    bind(info)
                }
            }.setOnCancelListener { finish() }.show()
    }

    private fun bind(info: AppWidgetProviderInfo) {
        try {
            widgetId = store.host.allocateAppWidgetId()
            check(widgetId > 0)
            store.begin(widgetId)
            val width = store.gridWidth
            val options = WidgetSizes(this, width).options(info, spanX, spanY)
            if (store.manager.bindAppWidgetIdIfAllowed(widgetId, info.profile, info.provider, options)) {
                bound()
            } else {
                waiting = true
                startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
                }, REQUEST_BIND)
            }
        } catch (e: RuntimeException) { fail() }
    }

    private fun bound() {
        val info = store.manager.getAppWidgetInfo(widgetId) ?: return fail()
        val optional = info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0 &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
        if (info.configure != null && !optional) configure() else commit()
    }

    private fun configure() {
        waiting = true
        try {
            store.host.startAppWidgetConfigureActivityForResult(this, widgetId, 0, REQUEST_CONFIGURE, null)
        } catch (e: Exception) { fail() }
    }

    private fun commit() {
        try {
            if (!existing) {
                check(store.ownsPending(widgetId))
                val current = store.entries
                val placed = WidgetEntry.place(widgetId, spanX, spanY, current)
                if (placed == null) {
                    Toast.makeText(this, R.string.app_widgets_no_space, Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
                check(store.save(current + placed))
                store.complete(widgetId)
                store.setEnabled(true)
            }
            setResult(RESULT_OK)
            finish()
        } catch (e: RuntimeException) { fail() }
    }

    private fun fail() {
        waiting = false
        Toast.makeText(this, R.string.app_widgets_failed, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BIND && requestCode != REQUEST_CONFIGURE) return
        waiting = false
        if (resultCode != RESULT_OK) { finish(); return }
        try {
            if (requestCode == REQUEST_BIND) bound() else commit()
        } catch (e: RuntimeException) { fail() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("id", widgetId)
        outState.putBoolean("existing", existing)
        outState.putBoolean("waiting", waiting)
        outState.putInt("width", spanX)
        outState.putInt("height", spanY)
    }

    override fun onDestroy() {
        picker?.dismiss()
        if (isFinishing && !existing) runCatching { store.cancel(widgetId) }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_BIND = 1
        private const val REQUEST_CONFIGURE = 2
    }
}
