/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.widgets

import android.app.Service
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHost.AppWidgetHostListener
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.widget.RemoteViews
import com.android.systemui.qs.panels.data.repository.LunarisQsWidgetStore
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.LunarisQsWidget

class LunarisQsWidgetService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val sessions = mutableMapOf<AxQsLayout, Session>()
    private val clients = mutableMapOf<IBinder, Client>()

    private inner class Client(
        val listener: ILunarisQsWidgetListener,
        val session: Session,
        var active: Boolean = false,
    ) : IBinder.DeathRecipient {
        override fun binderDied() {
            main.post { remove(listener) }
        }
    }

    private inner class Session(val layout: AxQsLayout, val widgetId: Int) : AppWidgetHostListener {
        val host =
            object : AppWidgetHost(this@LunarisQsWidgetService, LunarisQsWidget.hostId(layout)) {
                override fun onAppWidgetRemoved(appWidgetId: Int) {
                    if (appWidgetId == widgetId) notifyClients { it.onRemoved() }
                }
            }
        var info: AppWidgetProviderInfo? = null
        var views: RemoteViews? = null
        var listening = false

        fun notifyClients(action: (ILunarisQsWidgetListener) -> Unit) {
            clients.values
                .filter { it.session === this }
                .toList()
                .forEach { client ->
                    try {
                        action(client.listener)
                    } catch (_: RemoteException) {
                        remove(client.listener)
                    }
                }
        }

        fun updateListening() {
            val active = clients.values.any { it.session === this && it.active }
            if (active == listening) return
            try {
                if (active) host.startListening()
                else {
                    host.stopListening()
                    host.setAppWidgetHidden()
                }
                listening = active
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cannot change widget listening state", e)
                listening = false
                notifyClients { it.onRemoved() }
            }
        }

        override fun updateAppWidget(remoteViews: RemoteViews?) {
            views = remoteViews
            notifyClients { it.onUpdate(info, remoteViews) }
        }

        override fun onUpdateProviderInfo(appWidget: AppWidgetProviderInfo?) {
            info = appWidget
            notifyClients { it.onUpdate(info, views) }
        }

        override fun onViewDataChanged(viewId: Int) = notifyClients { it.onDataChanged(viewId) }

        override fun updateAppWidgetDeferred(packageName: String?, appWidgetId: Int) {
            if (listening) host.setListener(widgetId, this)
        }

        fun close() {
            try {
                host.removeListener(widgetId)
                host.stopListening()
                host.setAppWidgetHidden()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cannot close widget host", e)
            } finally {
                listening = false
                views = null
            }
        }
    }

    private fun remove(listener: ILunarisQsWidgetListener) {
        val client = clients.remove(listener.asBinder()) ?: return
        listener.asBinder().unlinkToDeath(client, 0)
        client.session.updateListening()
        closeIfUnused(client.session)
    }

    private fun closeIfUnused(session: Session) {
        if (clients.values.none { it.session === session }) {
            session.close()
            sessions.remove(session.layout, session)
        }
    }

    private val binder =
        object : ILunarisQsWidgetHost.Stub() {
            override fun connect(
                layout: String,
                widgetId: Int,
                listener: ILunarisQsWidgetListener,
            ) {
                main.post {
                    val panel = AxQsLayout.entries.firstOrNull { it.name == layout } ?: return@post
                    remove(listener)
                    try {
                        val saved = LunarisQsWidgetStore.read(this@LunarisQsWidgetService, panel)
                        val manager = AppWidgetManager.getInstance(this@LunarisQsWidgetService)
                        val info = manager.getAppWidgetInfo(widgetId)
                        if (
                            !saved.isConfigured ||
                                saved.appWidgetId != widgetId ||
                                info == null ||
                                info.provider != saved.provider
                        ) {
                            listener.onRemoved()
                            return@post
                        }
                        val old = sessions[panel]
                        if (old != null && old.widgetId != widgetId) {
                            old.notifyClients { it.onRemoved() }
                            clients.values
                                .filter { it.session === old }
                                .toList()
                                .forEach { remove(it.listener) }
                        }
                        val session = sessions.getOrPut(panel) { Session(panel, widgetId) }
                        if (!session.host.appWidgetIds.contains(widgetId)) {
                            closeIfUnused(session)
                            listener.onRemoved()
                            return@post
                        }
                        val client = Client(listener, session)
                        listener.asBinder().linkToDeath(client, 0)
                        clients[listener.asBinder()] = client
                        session.info = info
                        session.host.setListener(widgetId, session)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot connect widget", e)
                        remove(listener)
                        sessions[panel]?.let(::closeIfUnused)
                        try {
                            listener.onRemoved()
                        } catch (_: RemoteException) {}
                    }
                }
            }

            override fun setActive(listener: ILunarisQsWidgetListener, active: Boolean) {
                main.post {
                    clients[listener.asBinder()]?.let {
                        it.active = active
                        it.session.updateListening()
                    }
                }
            }

            override fun resize(listener: ILunarisQsWidgetListener, width: Float, height: Float) {
                main.post {
                    val client = clients[listener.asBinder()] ?: return@post
                    if (!width.isFinite() || !height.isFinite()) return@post
                    try {
                        AppWidgetManager.getInstance(this@LunarisQsWidgetService)
                            .updateAppWidgetOptions(
                                client.session.widgetId,
                                qsWidgetOptions(width, height),
                            )
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "Cannot resize widget", e)
                    }
                }
            }

            override fun disconnect(listener: ILunarisQsWidgetListener) {
                main.post { remove(listener) }
            }
        }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLEAR) {
            AxQsLayout.entries
                .firstOrNull { it.name == intent.getStringExtra(EXTRA_LAYOUT) }
                ?.let { layout ->
                    try {
                        LunarisQsWidgetStore.save(this, layout, LunarisQsWidget(userId))
                        sessions[layout]?.let { session ->
                            session.notifyClients { it.onRemoved() }
                            clients.values
                                .filter { it.session === session }
                                .toList()
                                .forEach { remove(it.listener) }
                        }
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "Cannot remove widget", e)
                    }
                }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        clients.values.toList().forEach { remove(it.listener) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_CLEAR = "com.android.systemui.qs.CLEAR_APP_WIDGET"
        const val EXTRA_LAYOUT = "layout"
        private const val TAG = "LunarisQsWidgetHost"
    }
}
