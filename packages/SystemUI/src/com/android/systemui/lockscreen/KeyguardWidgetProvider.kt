/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.lockscreen

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.database.ContentObserver
import android.provider.Settings
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.util.SizeF
import com.android.internal.appwidget.IAppWidgetHost
import java.util.concurrent.FutureTask
import java.util.concurrent.ExecutionException
import android.os.ServiceManager
import android.os.UserHandle
import com.android.internal.appwidget.IAppWidgetService
import org.lunaris.lockscreenwidgets.WidgetContract
import org.lunaris.lockscreenwidgets.WidgetEntry

class KeyguardWidgetProvider : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())
    private val hostToken = Binder()
    private lateinit var host: KeyguardAppWidgetHost
    override fun onCreate(): Boolean {
        val context = requireNotNull(context)
        host = KeyguardAppWidgetHost(context)
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) { host.sync() }
        }
        for (key in listOf(WidgetContract.CONFIG, WidgetContract.ENABLED)) {
            context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
        }
        runCatching { KeyguardWidgetStore(context).reconcile() }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        val callingUid = Binder.getCallingUid()
        if (!UserHandle.isSameApp(callingUid, Process.myUid())) {
            context.enforceCallingOrSelfPermission(WidgetContract.PERMISSION, "Lockscreen widget management")
            if (UserHandle.getUserId(callingUid) != context.userId) {
                throw SecurityException("Widgets belong to the calling user")
            }
        }
        val identity = Binder.clearCallingIdentity()
        try {
            val task = FutureTask {
                val store = KeyguardWidgetStore(context)
                when (method) {
                    "list" -> store.snapshot()
                    "listen" -> Bundle().apply {
                        host.listen(IAppWidgetHost.Stub.asInterface(requireNotNull(extras).getBinder("callback")))
                        putBinder("host", hostToken)
                    }
                    "unlisten" -> Bundle().apply {
                        extras?.getBinder("callback")?.let(host::unlisten)
                    }
                    "size" -> Bundle().apply {
                        val data = requireNotNull(extras)
                        val id = data.getInt("id")
                        require(store.owns(id) && store.entries.any { it.id == id })
                        val info = store.manager.getAppWidgetInfo(id)
                        if (info != null) {
                            val padding = AppWidgetHostView.getDefaultPaddingForWidget(context, info.provider, null)
                            val density = context.resources.displayMetrics.density
                            val width = ((data.getInt("width") - padding.left - padding.right) / density).coerceAtLeast(1f)
                            val height = ((data.getInt("height") - padding.top - padding.bottom) / density).coerceAtLeast(1f)
                            store.manager.updateAppWidgetOptions(id, Bundle().apply {
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width.toInt())
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width.toInt())
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height.toInt())
                                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height.toInt())
                                putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(width, height)))
                            })
                        }
                        putBoolean("ok", true)
                    }
                    "preview" -> {
                        val id = arg?.toIntOrNull()
                        require(id != null && store.owns(id) && store.entries.any { it.id == id })
                        val service = IAppWidgetService.Stub.asInterface(
                            ServiceManager.getService(android.content.Context.APPWIDGET_SERVICE))
                        Bundle().apply { putParcelable("views", service.getAppWidgetViews(context.packageName, id)) }
                    }
                    "remove" -> Bundle().apply { putBoolean("ok", store.remove(arg?.toIntOrNull() ?: -1)) }
                    "change" -> Bundle().apply {
                        val data = requireNotNull(extras)
                        putBoolean("ok", store.change(WidgetEntry.from(data)))
                    }
                    "enable" -> Bundle().apply { putBoolean("ok", store.setEnabled(arg == "true")) }
                    else -> throw IllegalArgumentException("Unknown widget operation")
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
            return try {
                task.get()
            } catch (e: ExecutionException) {
                throw (e.cause as? RuntimeException ?: IllegalStateException(e.cause))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException(e)
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?) = 0
}
