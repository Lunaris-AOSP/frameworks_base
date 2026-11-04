/*
 * Copyright (C) 2025 AxionOS Project
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
package com.android.systemui.statusbar.policy

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.view.ViewGroup
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.Dependency
import com.android.systemui.statusbar.StatusIconDisplayable
import com.android.systemui.statusbar.phone.NetworkSpeedIconHolder
import com.android.systemui.statusbar.phone.StatusBarIconControllerImplEx
import com.android.systemui.statusbar.phone.StatusBarIconHolder
import com.android.systemui.statusbar.policy.networkspeed.NetworkSpeedIconState
import com.android.systemui.statusbar.policy.networkspeed.NetworkSpeedView

class NetworkSpeedController private constructor(
    private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val contentResolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private val leftViews = mutableSetOf<NetworkSpeedView>()
    private var initialized = false
    private var isSwitchOn = false
    private var currentNetwork: Network? = null
    private val isConnected: Boolean
        get() = currentNetwork != null
    private var autoHide = false
    private var position = POSITION_RIGHT
    private var singleLine = false
    private var lastTime = 0L
    private var lastTotalBytes = 0L
    private var speed = 0L

    private val speedUpdate = object : Runnable {
        override fun run() {
            if (!isConnected || !isSwitchOn) return
            val currentTime = SystemClock.elapsedRealtime()
            val totalBytes = getTotalBytes()
            val elapsed = currentTime - lastTime
            speed = if (elapsed > 0 && totalBytes >= lastTotalBytes) {
                (totalBytes - lastTotalBytes) * 1000 / elapsed
            } else {
                0L
            }
            lastTime = currentTime
            lastTotalBytes = totalBytes
            publishState()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (currentNetwork == network) return
            currentNetwork = network
            restartSpeedUpdates()
        }

        override fun onLost(network: Network) {
            if (currentNetwork != network) return
            currentNetwork = null
            restartSpeedUpdates()
        }
    }

    private val networkSpeedObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            val wasEnabled = isSwitchOn
            updateSettings()
            if (wasEnabled != isSwitchOn) {
                restartSpeedUpdates()
            } else {
                publishState()
            }
        }
    }

    private val keyguardCallback = object : KeyguardUpdateMonitorCallback() {
        override fun onUserSwitchComplete(newUserId: Int) {
            updateSettings()
            restartSpeedUpdates()
        }
    }

    fun init() {
        if (initialized) return
        initialized = true
        listOf(
            Settings.System.NETWORK_SPEED_ENABLED,
            Settings.System.NETWORK_SPEED_AUTO_HIDE,
            Settings.System.NETWORK_SPEED_POSITION,
            Settings.System.NETWORK_SPEED_LAYOUT,
        ).forEach { key ->
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(key), false, networkSpeedObserver, UserHandle.USER_ALL)
        }
        updateSettings()
        currentNetwork = connectivityManager.activeNetwork
        restartSpeedUpdates()
        connectivityManager.registerDefaultNetworkCallback(networkCallback, handler)
        Dependency.get(KeyguardUpdateMonitor::class.java).registerCallback(keyguardCallback)
    }

    private fun updateSettings() {
        if (Settings.System.getStringForUser(contentResolver,
                Settings.System.NETWORK_SPEED_ENABLED, UserHandle.USER_CURRENT) == null) {
            val hiddenIcons = Settings.Secure.getStringForUser(
                contentResolver, ICON_HIDE_LIST, UserHandle.USER_CURRENT)
            val wasEnabled = !hiddenIcons.isNullOrEmpty() &&
                SLOT_NETWORK_SPEED !in hiddenIcons.split(',').map { it.trim() }
            Settings.System.putIntForUser(contentResolver,
                Settings.System.NETWORK_SPEED_ENABLED, if (wasEnabled) 1 else 0,
                UserHandle.USER_CURRENT)
        }
        isSwitchOn = readSetting(Settings.System.NETWORK_SPEED_ENABLED, 0) != 0
        autoHide = readSetting(Settings.System.NETWORK_SPEED_AUTO_HIDE, 0) != 0
        position = if (readSetting(Settings.System.NETWORK_SPEED_POSITION, POSITION_RIGHT) ==
                POSITION_LEFT) POSITION_LEFT else POSITION_RIGHT
        singleLine = readSetting(Settings.System.NETWORK_SPEED_LAYOUT, 1) == 0
    }

    private fun readSetting(key: String, defaultValue: Int): Int =
        Settings.System.getIntForUser(contentResolver, key, defaultValue, UserHandle.USER_CURRENT)

    private fun restartSpeedUpdates() {
        handler.removeCallbacks(speedUpdate)
        lastTime = SystemClock.elapsedRealtime()
        lastTotalBytes = getTotalBytes()
        speed = 0L
        publishState()
        if (isConnected && isSwitchOn) {
            handler.postDelayed(speedUpdate, REFRESH_INTERVAL_MS)
        }
    }

    private fun stateForPosition(targetPosition: Int) = NetworkSpeedIconState().apply {
        setVisible(isConnected && isSwitchOn && position == targetPosition &&
            (!autoHide || speed > 0))
        setSpeedText(speed)
        setSlot(SLOT_NETWORK_SPEED)
        singleLine = this@NetworkSpeedController.singleLine
    }

    private fun publishState() {
        StatusBarIconControllerImplEx.get().setNetworkSpeedIcon(
            SLOT_NETWORK_SPEED, stateForPosition(POSITION_RIGHT))
        val leftState = stateForPosition(POSITION_LEFT)
        leftViews.forEach { it.applyNetworkState(leftState) }
    }

    fun addLeftView(view: NetworkSpeedView) {
        leftViews.add(view)
        view.applyNetworkState(stateForPosition(POSITION_LEFT))
    }

    fun removeLeftView(view: NetworkSpeedView) {
        leftViews.remove(view)
    }

    private fun getTotalBytes(): Long {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        return if (rx >= 0 && tx >= 0) rx + tx else 0
    }

    fun addHolder(
        index: Int,
        slot: String,
        rootGroup: ViewGroup,
        holder: StatusBarIconHolder,
        blocked: Boolean
    ): StatusIconDisplayable? {
        return StatusBarIconControllerImplEx.get()
            .addHolder(index, slot, rootGroup, holder, blocked)
    }

    fun onSetIconHolder(viewIndex: Int, holder: StatusBarIconHolder, rootGroup: ViewGroup) {
        if (holder is NetworkSpeedIconHolder) {
            val view = rootGroup.getChildAt(viewIndex) as? NetworkSpeedView ?: return
            view.applyNetworkState(holder.getNetworkSpeedIconState())
        }
    }

    companion object {
        @Volatile
        private var instance: NetworkSpeedController? = null

        fun init(context: Context): NetworkSpeedController {
            return instance ?: synchronized(this) {
                instance ?: NetworkSpeedController(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun get(): NetworkSpeedController {
            return instance ?: throw IllegalStateException("init must be called first!!!")
        }

        const val SLOT_NETWORK_SPEED = "network_speed"
        private const val ICON_HIDE_LIST = "icon_blacklist"
        private const val POSITION_LEFT = 0
        private const val POSITION_RIGHT = 1
        private const val REFRESH_INTERVAL_MS = 4000L
    }
}
