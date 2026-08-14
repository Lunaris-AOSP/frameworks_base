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

package com.android.systemui.qs.panels.ui.compose

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.TetheringManager
import android.net.TetheringManager.TetheringRequest
import android.net.TetheringManager.StartTetheringCallback
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CustomColorScheme
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.Dependency
import com.android.settingslib.net.DataUsageController
import java.util.concurrent.Executor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun MaterialUtilityPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tileColor = CustomColorScheme.current.qsTileColor
    val scope = rememberCoroutineScope()

    val wifiManager = remember {
        context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }
    val connectivityManager = remember {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    val tetheringManager = remember {
        context.getSystemService(Context.TETHERING_SERVICE) as? TetheringManager
    }
    val telephonyManager = remember {
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    }
    val networkController = remember { Dependency.get(NetworkController::class.java) }
    val dataUsageController = remember { networkController.mobileDataController }
    val mainExecutor = remember { context.mainExecutor }

    fun readWifiEnabled(): Boolean = try {
        wifiManager?.isWifiEnabled == true
    } catch (_: Exception) { false }

    fun readHotspotEnabled(): Boolean = try {
        wifiManager?.wifiApState == WifiManager.WIFI_AP_STATE_ENABLED
    } catch (_: Exception) { false }

    fun readMobileDataEnabled(): Boolean = try {
        dataUsageController.isMobileDataEnabled
    } catch (_: Exception) {
        try {
            Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 1
        } catch (_: Exception) { true }
    }

    fun readAirplaneModeEnabled(): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    } catch (_: Exception) { false }

    fun readUtilityTileRounded(): Boolean = try {
        Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.QS_WIDGET_UTILITY_TILE_ROUNDED,
            0,
            UserHandle.USER_CURRENT
        ) == 1
    } catch (_: Exception) { false }

    var wifiOn by remember { mutableStateOf(readWifiEnabled()) }
    var hotspotOn by remember { mutableStateOf(readHotspotEnabled()) }
    var dataOn by remember { mutableStateOf(readMobileDataEnabled()) }
    var airplaneOn by remember { mutableStateOf(readAirplaneModeEnabled()) }
    var utilityTileRounded by remember { mutableStateOf(readUtilityTileRounded()) }

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN
                        )
                        if (state == WifiManager.WIFI_STATE_ENABLED ||
                            state == WifiManager.WIFI_STATE_DISABLED
                        ) {
                            wifiOn = state == WifiManager.WIFI_STATE_ENABLED
                        }
                    }
                    WifiManager.WIFI_AP_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiManager.EXTRA_WIFI_AP_STATE, WifiManager.WIFI_AP_STATE_FAILED
                        )
                        when (state) {
                            WifiManager.WIFI_AP_STATE_ENABLED -> hotspotOn = true
                            WifiManager.WIFI_AP_STATE_DISABLED -> hotspotOn = false
                            else -> {}
                        }
                    }
                    ConnectivityManager.CONNECTIVITY_ACTION -> {
                        dataOn = readMobileDataEnabled()
                    }
                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                        airplaneOn = readAirplaneModeEnabled()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        context.registerReceiver(receiver, filter, null, handler)
        onDispose { context.unregisterReceiver(receiver) }

        val settingsObserver = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                utilityTileRounded = readUtilityTileRounded()
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.QS_WIDGET_UTILITY_TILE_ROUNDED),
                false, settingsObserver, UserHandle.USER_ALL,
            )
        } catch (_: Exception) {}

        onDispose {
            context.unregisterReceiver(receiver)
            context.contentResolver.unregisterContentObserver(settingsObserver)
        }
    }

    fun setWifi(enabled: Boolean) {
        wifiOn = enabled
        scope.launch(Dispatchers.IO) {
            try { wifiManager?.isWifiEnabled = enabled } catch (_: Exception) {}
        }
    }

    fun setHotspot(enabled: Boolean) {
        hotspotOn = enabled
        try {
            if (enabled) {
                val request = TetheringRequest.Builder(TetheringManager.TETHERING_WIFI).build()
                tetheringManager?.startTethering(
                    request,
                    mainExecutor,
                    object : StartTetheringCallback {
                        override fun onTetheringFailed(error: Int) {
                            hotspotOn = false
                        }
                    },
                )
            } else {
                tetheringManager?.stopTethering(TetheringManager.TETHERING_WIFI)
            }
        } catch (_: Exception) {
            hotspotOn = !enabled
        }
    }

    fun setMobileData(enabled: Boolean) {
        dataOn = enabled
        scope.launch(Dispatchers.IO) {
            try {
                dataUsageController.isMobileDataEnabled = enabled
            } catch (_: Exception) {}
        }
    }

    fun setAirplaneMode(enabled: Boolean) {
        airplaneOn = enabled
        scope.launch(Dispatchers.IO) {
            try {
                Settings.Global.putInt(
                    context.contentResolver,
                    Settings.Global.AIRPLANE_MODE_ON,
                    if (enabled) 1 else 0,
                )
                context.sendBroadcast(
                    Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).apply {
                        putExtra("state", enabled)
                    }
                )
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(tileColor)
            .padding(10.dp),
    ) {
        val tiles = listOf(
            UtilityTileSpec("Wi-Fi", Icons.Filled.Wifi, wifiOn, ::setWifi),
            UtilityTileSpec("Hotspot", Icons.Filled.WifiTethering, hotspotOn, ::setHotspot),
            UtilityTileSpec("Mobile data", Icons.Filled.SignalCellularAlt, dataOn, ::setMobileData),
            UtilityTileSpec("Aeroplane mode", Icons.Filled.AirplanemodeActive, airplaneOn, ::setAirplaneMode),
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UtilityTile(tiles[0], rounded = utilityTileRounded, modifier = Modifier.weight(1f).fillMaxHeight())
                UtilityTile(tiles[1], rounded = utilityTileRounded, modifier = Modifier.weight(1f).fillMaxHeight())
            }
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UtilityTile(tiles[2], rounded = utilityTileRounded, modifier = Modifier.weight(1f).fillMaxHeight())
                UtilityTile(tiles[3], rounded = utilityTileRounded, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

private data class UtilityTileSpec(
    val label: String,
    val icon: ImageVector,
    val on: Boolean,
    val onToggle: (Boolean) -> Unit,
)

@Composable
private fun UtilityTile(
    spec: UtilityTileSpec,
    rounded: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "utilityTileScale",
    )

    val haptics = LocalHapticFeedback.current
    var toggleCount by remember { mutableIntStateOf(0) }

    val bg = if (spec.on) MaterialTheme.colorScheme.primary
            else LocalAndroidColorScheme.current.surfaceEffect2
    val fg = if (spec.on) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant

    val shape = if (rounded) CircleShape else RoundedCornerShape(20.dp)

    if (rounded) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .squishAnimation(toggleCount)
                .clip(shape)
                .background(bg)
                .clickable(source, indication = null) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    toggleCount++
                    spec.onToggle(!spec.on)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                spec.icon,
                contentDescription = spec.label,
                tint = fg,
                modifier = Modifier.size(25.dp),
            )
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .squishAnimation(toggleCount)
                .clip(shape)
                .background(bg)
                .clickable(source, indication = null) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    toggleCount++
                    spec.onToggle(!spec.on)
                }
                .padding(10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Box(
                modifier = Modifier.size(30.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(spec.icon, contentDescription = spec.label, tint = fg, modifier = Modifier.size(22.dp))
            }
            Text(
                text = spec.label,
                style = MaterialTheme.typography.labelLarge.copy(color = fg),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Modifier.squishAnimation(toggleCount: Int): Modifier {
    val scaleX = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val scaleY = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val currentToggleCount by rememberUpdatedState(toggleCount)
    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest { count ->
                if (count <= 0) return@collectLatest
                scaleX.snapTo(1f)
                scaleY.snapTo(1f)
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = 1f,
                            animationSpec = keyframes {
                                durationMillis = 500
                                1.07f at 150 using FastOutSlowInEasing
                                0.97f at 300
                                1f at 500
                            },
                        )
                    }
                    scaleY.animateTo(
                        targetValue = 1f,
                        animationSpec = keyframes {
                            durationMillis = 500
                            0.94f at 150 using FastOutSlowInEasing
                            1.04f at 300
                            1f at 500
                        },
                    )
                }
            }
    }
    return this.graphicsLayer {
        this.scaleX = scaleX.value
        this.scaleY = scaleY.value
    }
}
