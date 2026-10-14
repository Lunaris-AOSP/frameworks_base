/*
 * Copyright (C) 2025 AxionOS Project
 * Copyright (C) 2026 Lunaris-AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.lockscreen

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import com.android.internal.appwidget.IAppWidgetHost
import org.lunaris.lockscreenwidgets.WidgetContract

class KeyguardAppWidgetHost(context: Context) : AppWidgetHost(context, WidgetContract.HOST_ID) {
    private val store = KeyguardWidgetStore(context)
    private val main = Handler(Looper.getMainLooper())
    private val clients = mutableMapOf<IBinder, Pair<IAppWidgetHost, IBinder.DeathRecipient>>()
    private var ids = emptySet<Int>()

    fun listen(callback: IAppWidgetHost) {
        val binder = callback.asBinder()
        if (binder in clients) return
        val death = IBinder.DeathRecipient { main.post { unlisten(binder) } }
        binder.linkToDeath(death, 0)
        clients[binder] = callback to death
        sync()
        try {
            startListening()
        } catch (e: RuntimeException) {
            unlisten(binder)
            throw e
        }
        ids.forEach { id ->
            setListener(id, listener(id))
        }
    }

    fun unlisten(binder: IBinder) {
        val client = clients.remove(binder) ?: return
        binder.unlinkToDeath(client.second, 0)
        if (clients.isEmpty()) {
            runCatching { stopListening() }
            clearViews()
            ids = emptySet()
        }
    }

    fun sync() {
        if (clients.isEmpty()) return
        val current = store.entries.map { it.id }.toSet().intersect(appWidgetIds.toSet())
        (ids - current).forEach(::removeListener)
        (current - ids).forEach { setListener(it, listener(it)) }
        ids = current
        send { providersChanged() }
    }

    private fun listener(id: Int) = object : AppWidgetHostListener {
        override fun updateAppWidget(views: RemoteViews?) { send { updateAppWidget(id, views) } }
        override fun onUpdateProviderInfo(info: AppWidgetProviderInfo?) { send { providerChanged(id, info) } }
        override fun onViewDataChanged(viewId: Int) { send { viewDataChanged(id, viewId) } }
        override fun updateAppWidgetDeferred(packageName: String?, appWidgetId: Int) {
            send { updateAppWidgetDeferred(id) }
        }
    }

    override fun onAppWidgetRemoved(id: Int) {
        store.save(store.entries.filter { it.id != id })
        send { appWidgetRemoved(id) }
    }
    override fun onProvidersChanged() { send { providersChanged() } }

    private fun send(block: IAppWidgetHost.() -> Unit) {
        clients.toMap().forEach { (binder, client) ->
            runCatching { client.first.block() }.onFailure { unlisten(binder) }
        }
    }
}
