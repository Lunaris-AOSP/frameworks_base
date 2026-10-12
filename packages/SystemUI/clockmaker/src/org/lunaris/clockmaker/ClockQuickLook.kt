/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import com.android.axion.quicklook.IAxQuickLookService
import com.android.axion.quicklook.IQuickLookCallback
import com.android.axion.quicklook.QuickLookTarget

internal class ClockQuickLook(private val context: Context, private val onChanged: () -> Unit) {
    var targets: List<QuickLookTarget> = emptyList()
        private set
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var userId = context.userId
    private var binding: Binding? = null
    private var retryDelay = RETRY_DELAY
    private val retry = Runnable {
        release()
        if (active) bind()
    }
    private val expire = Runnable { publish(targets.filterNot { it.isExpired }) }

    fun update(active: Boolean, userId: Int) {
        if (this.active == active && this.userId == userId) return
        this.active = active
        this.userId = userId
        main.removeCallbacks(retry)
        retryDelay = RETRY_DELAY
        release()
        publish(emptyList())
        if (active) bind()
    }

    private fun publish(value: List<QuickLookTarget>) {
        targets = value.filterNot { it.isExpired }
        main.removeCallbacks(expire)
        targets.map { it.expiryTime }.filter { it > 0 }.minOrNull()?.let {
            main.postDelayed(expire, (it - System.currentTimeMillis() + 1).coerceAtLeast(1))
        }
        onChanged()
    }

    private fun bind() {
        val connection = Binding()
        binding = connection
        connection.bound = try {
            context.bindServiceAsUser(Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE),
                connection, Context.BIND_AUTO_CREATE, UserHandle.of(userId))
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to bind QuickLook for user $userId", e)
            false
        }
        if (!connection.bound) scheduleRetry()
    }

    private fun release() {
        val previous = binding ?: return
        binding = null
        try {
            previous.service?.unregisterCallback(previous.callback)
        } catch (e: RemoteException) {
            Log.d(TAG, "QuickLook disconnected before unregister", e)
        }
        if (previous.bound) context.unbindService(previous)
    }

    private fun scheduleRetry() {
        if (!active) return
        main.removeCallbacks(retry)
        main.postDelayed(retry, retryDelay)
        retryDelay = (retryDelay * 2).coerceAtMost(60000L)
    }

    private inner class Binding : ServiceConnection {
        var bound = false
        var service: IAxQuickLookService? = null
        val callback = object : IQuickLookCallback.Stub() {
            override fun onTargetsUpdated(targets: MutableList<QuickLookTarget>) {
                val snapshot = targets.toList()
                main.post {
                    if (binding === this@Binding && active && service != null) publish(snapshot)
                }
            }
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (binding !== this) return
            main.removeCallbacks(retry)
            retryDelay = RETRY_DELAY
            service = IAxQuickLookService.Stub.asInterface(binder)
            try {
                service?.registerCallback(callback)
            } catch (e: RemoteException) {
                Log.w(TAG, "Unable to subscribe to QuickLook", e)
                disconnected()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()

        private fun disconnected() {
            if (binding !== this) return
            service = null
            publish(emptyList())
            scheduleRetry()
        }
    }

    companion object {
        const val SERVICE_PACKAGE = "com.android.axion.quicklook"
        private const val SERVICE_ACTION = "$SERVICE_PACKAGE.SERVICE"
        private const val TAG = "ClockQuickLook"
        private const val RETRY_DELAY = 5000L
    }
}

internal data class ClockQuickLookContent(val text: String, val target: QuickLookTarget?) {
    companion object {
        fun create(targets: List<QuickLookTarget>, date: String, showDate: Boolean,
            showWeather: Boolean, allowSensitive: Boolean): ClockQuickLookContent {
            val available = targets.filter {
                !it.isExpired && (!it.title.isNullOrBlank() || !it.subtitle.isNullOrBlank()) &&
                    (allowSensitive || it.extras?.getBoolean(QuickLookTarget.EXTRA_SMARTSPACE_SENSITIVE) != true)
            }
            val priority = compareByDescending<QuickLookTarget> { it.score }.thenByDescending { it.creationTime }
            val weather = if (showWeather) available.filter { it.targetType == QuickLookTarget.TYPE_WEATHER }
                .sortedWith(priority).firstOrNull() else null
            val update = available.filter { it.targetType != QuickLookTarget.TYPE_WEATHER }
                .sortedWith(priority).firstOrNull()
            fun join(vararg parts: String?) = parts.filterNot { it.isNullOrBlank() }
                .distinct().joinToString(" · ")
            val text = if (update != null) {
                listOfNotNull(update.title?.takeIf { it.isNotBlank() },
                    join(update.subtitle, weather?.title).takeIf { it.isNotBlank() }).joinToString("\n")
            } else if (showDate) {
                join(date, weather?.title)
            } else {
                join(weather?.title, weather?.subtitle)
            }
            return ClockQuickLookContent(text, update ?: weather)
        }
    }
}
