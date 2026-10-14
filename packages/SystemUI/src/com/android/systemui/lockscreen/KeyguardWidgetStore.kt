/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.lockscreen

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import com.android.systemui.res.R
import org.lunaris.lockscreenwidgets.WidgetContract
import org.lunaris.lockscreenwidgets.WidgetEntry

class KeyguardWidgetStore(private val context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    private val resolver = context.contentResolver
    private val pending = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("lockscreen_app_widgets_pending", Context.MODE_PRIVATE)
    val host get() = AppWidgetHost(context, WidgetContract.HOST_ID)
    val entries: List<WidgetEntry>
        get() = WidgetEntry.decode(Settings.Secure.getString(resolver, WidgetContract.CONFIG))
    val gridWidth: Int
        get() {
            val resources = context.resources
            val metrics = resources.displayMetrics
            val columns = if (resources.getBoolean(R.bool.config_use_split_notification_shade)) 2 else 1
            return (minOf(metrics.widthPixels / columns,
                resources.getDimensionPixelSize(R.dimen.shade_panel_width)) - 32 * metrics.density).toInt().coerceAtLeast(4)
        }
    val enabled: Boolean
        get() = Settings.Secure.getInt(resolver, WidgetContract.ENABLED, 0) != 0

    fun owns(id: Int) = id in host.appWidgetIds

    fun save(entries: List<WidgetEntry>): Boolean {
        if (entries.size > 16 || entries.any { !it.fits(entries) } ||
            entries.map { it.id }.distinct().size != entries.size) return false
        val success = Settings.Secure.putString(resolver, WidgetContract.CONFIG, WidgetEntry.encode(entries))
        if (success) resolver.notifyChange(WidgetContract.URI, null)
        return success
    }

    fun setEnabled(enabled: Boolean): Boolean =
        Settings.Secure.putInt(resolver, WidgetContract.ENABLED, if (enabled) 1 else 0).also {
            if (it) resolver.notifyChange(WidgetContract.URI, null)
        }

    fun change(entry: WidgetEntry): Boolean {
        val current = entries
        if (current.none { it.id == entry.id } || !entry.fits(current)) return false
        if (!owns(entry.id)) return false
        val info = manager.getAppWidgetInfo(entry.id) ?: return false
        val original = current.first { it.id == entry.id }
        if ((entry.width != original.width || entry.height != original.height) &&
            (entry.width to entry.height) !in
                org.lunaris.lockscreenwidgets.WidgetSizes(context, gridWidth).allowed(info)) return false
        return save(current.map { if (it.id == entry.id) entry else it })
    }

    fun remove(id: Int): Boolean {
        val current = entries
        if (current.none { it.id == id }) return false
        if (!save(current.filter { it.id != id })) return false
        if (owns(id)) host.deleteAppWidgetId(id)
        return true
    }

    fun begin(id: Int) {
        check(pending.edit().putLong(id.toString(), System.currentTimeMillis()).commit())
    }
    fun complete(id: Int) { pending.edit().remove(id.toString()).commit() }
    fun cancel(id: Int) {
        if (id <= 0 || entries.any { it.id == id }) return
        host.deleteAppWidgetId(id)
        complete(id)
    }
    fun ownsPending(id: Int) = pending.contains(id.toString()) && id in host.appWidgetIds

    fun reconcile() {
        val saved = entries.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        host.appWidgetIds.filter { id ->
            id !in saved && (!pending.contains(id.toString()) ||
                now - pending.getLong(id.toString(), 0) > 86_400_000)
        }.forEach(::cancel)
        pending.all.keys.mapNotNull(String::toIntOrNull).filter { it in saved }.forEach(::complete)
    }

    fun snapshot(): Bundle = Bundle().apply {
        putString("config", WidgetEntry.encode(entries))
        putBoolean("enabled", enabled)
        putInt("gridWidth", gridWidth)
        val owned = host.appWidgetIds.toSet()
        entries.forEach { entry ->
            if (entry.id in owned) manager.getAppWidgetInfo(entry.id)?.let { putParcelable(entry.id.toString(), it) }
        }
    }
}
