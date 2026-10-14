/* SPDX-License-Identifier: Apache-2.0 */
package org.lunaris.lockscreenwidgets

import android.appwidget.AppWidgetProviderInfo
import android.content.ContentProviderClient
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.widget.RemoteViews
import com.android.internal.appwidget.IAppWidgetHost
import java.util.concurrent.Executors

class WidgetClient(private val context: Context, private val onSnapshot: (Bundle) -> Unit,
    private val onViews: (Int, RemoteViews?) -> Unit,
    private val onDataChanged: (Int, Int) -> Unit = { _, _ -> },
    private val onError: (Error) -> Unit = {}) : AutoCloseable {
    enum class Error(val messageResId: Int) {
        HOST_UNAVAILABLE(R.string.app_widgets_host_unavailable),
        REQUEST_FAILED(R.string.app_widgets_failed),
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val submissionLock = Any()
    private val reconnect: Runnable = Runnable { if (!closed) start() }
    private var provider: ContentProviderClient? = null
    private var hostToken: IBinder? = null
    private val hostDeath = IBinder.DeathRecipient { refresh() }
    private var retryDelay = 2000L
    @Volatile private var closed = false
    private val callback = object : IAppWidgetHost.Stub() {
        override fun updateAppWidgetDeferred(id: Int) {
            submit { client ->
                val views = client.call("preview", id.toString(), null)
                    ?.getParcelable("views", RemoteViews::class.java)
                main.post { if (!closed) onViews(id, views) }
            }
        }
        override fun updateAppWidget(id: Int, views: RemoteViews?) {
            main.post { if (!closed) onViews(id, views) }
        }
        override fun providerChanged(id: Int, info: AppWidgetProviderInfo?) { refresh() }
        override fun providersChanged() { refresh() }
        override fun viewDataChanged(id: Int, viewId: Int) {
            main.post { if (!closed) onDataChanged(id, viewId) }
        }
        override fun appWidgetRemoved(id: Int) { refresh() }
    }

    fun start(): Unit = refresh()

    fun refresh(): Unit = submit { client ->
        publishSnapshot(client)
    }

    fun change(method: String, arg: String? = null, extras: Bundle? = null): Unit = submit { client ->
        check(client.call(method, arg, extras)?.getBoolean("ok") == true) {
            "Widget host rejected $method"
        }
        if (method != "size") {
            publishSnapshot(client)
        }
    }

    private fun publishSnapshot(client: ContentProviderClient) {
        val data = checkNotNull(client.call("list", null, null)) {
            "Widget host returned no snapshot"
        }
        main.post { if (!closed) onSnapshot(data) }
    }

    private fun connect(): ContentProviderClient {
        val client = context.contentResolver.acquireUnstableContentProviderClient(WidgetContract.URI)
        if (client == null) {
            val registered = context.packageManager.resolveContentProvider(WidgetContract.AUTHORITY, 0) != null
            throw HostUnavailableException(registered,
                "Widget provider ${WidgetContract.AUTHORITY} for user ${context.userId}: " +
                    if (registered) "registered but unavailable" else "not registered or disabled")
        }
        try {
            val response = client.call("listen", null,
                Bundle().apply { putBinder("callback", callback.asBinder()) })
            val token = checkNotNull(response?.getBinder("host")) {
                "Widget host returned no connection token"
            }
            token.linkToDeath(hostDeath, 0)
            hostToken = token
        } catch (e: Exception) {
            runCatching { client.call("unlisten", null,
                Bundle().apply { putBinder("callback", callback.asBinder()) }) }
            client.close()
            throw e
        }
        provider = client
        return client
    }

    private fun releaseProvider() {
        hostToken?.unlinkToDeath(hostDeath, 0)
        hostToken = null
        provider?.close()
        provider = null
    }

    private fun submit(block: (ContentProviderClient) -> Unit): Unit = synchronized(submissionLock) {
        if (closed) return@synchronized
        worker.execute {
            if (closed) return@execute
            try {
                val client = provider ?: connect()
                block(client)
                retryDelay = 2000L
                main.post { if (!closed) main.removeCallbacks(reconnect) }
            } catch (e: Exception) {
                Log.w("LockscreenWidgets", "Widget host request failed", e)
                val retry = e is RemoteException || e is HostUnavailableException && e.registered
                val error = if (provider == null || e is RemoteException) Error.HOST_UNAVAILABLE
                    else Error.REQUEST_FAILED
                if (retry) releaseProvider()
                val delay = retryDelay
                if (retry) retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                main.post {
                    if (!closed) {
                        onError(error)
                        main.removeCallbacks(reconnect)
                        if (retry) main.postDelayed(reconnect, delay)
                    }
                }
            }
        }
    }

    override fun close(): Unit = synchronized(submissionLock) {
        if (closed) return@synchronized
        closed = true
        main.removeCallbacksAndMessages(null)
        worker.execute {
            runCatching { provider?.call("unlisten", null,
                Bundle().apply { putBinder("callback", callback.asBinder()) }) }
            releaseProvider()
        }
        worker.shutdown()
    }

    private class HostUnavailableException(val registered: Boolean, message: String) :
        IllegalStateException(message)
}
