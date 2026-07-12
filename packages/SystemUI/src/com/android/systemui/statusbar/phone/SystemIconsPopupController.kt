/*
 * Copyright (C) 2025 the RisingOS Revived Android Project
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
package com.android.systemui.statusbar.phone

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.Intent.ACTION_SCREEN_OFF
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.content.res.Configuration
import android.os.BatteryManager
import android.provider.Settings
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

class SystemIconsPopupController(
    private val context: Context,
    private val onShowPowerMenu: () -> Unit
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var popupView: ComposeView? = null
    var isShowing = false
        private set

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SCREEN_OFF) {
                hidePopup()
            }
        }
    }

    private var isReceiverRegistered = false

    private fun registerScreenOffReceiver() {
        if (!isReceiverRegistered) {
            try {
                val filter = IntentFilter(ACTION_SCREEN_OFF)
                context.registerReceiver(screenOffReceiver, filter)
                isReceiverRegistered = true
            } catch (e: Exception) { }
        }
    }

    private fun unregisterScreenOffReceiver() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(screenOffReceiver)
                isReceiverRegistered = false
            } catch (e: Exception) { }
        }
    }

    private var lifecycleOwner: CustomLifecycleOwner? = null

    fun showPopup(anchorView: View) {
        if (isShowing) {
            hidePopup()
            return
        }

        registerScreenOffReceiver()
        lifecycleOwner = CustomLifecycleOwner()
        popupView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                val isDarkMode = (context.resources.configuration.uiMode and 
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

                val accentColor = Color(context.getColor(android.R.color.system_accent1_500))

                val backgroundColor = if (isDarkMode) Color.Black else Color.White
                val surfaceColor = if (isDarkMode) Color(0xFF1C1C1C) else Color(0xFFF5F5F5)
                val onSurfaceColor = if (isDarkMode) Color.White.copy(alpha = 0.95f) else Color.Black.copy(alpha = 0.87f)
                val surfaceVariant = if (isDarkMode) Color(0xFF1C1C1C) else Color(0xFFE8E8E8)
                val onSurfaceVariant = if (isDarkMode) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.75f)
                
                val customColorScheme = darkColorScheme(
                    surface = backgroundColor,
                    background = backgroundColor,
                    primary = accentColor,
                    onPrimary = Color.White,
                    primaryContainer = accentColor.copy(alpha = 0.25f),
                    onPrimaryContainer = accentColor,
                    secondary = accentColor,
                    onSecondary = Color.White,
                    secondaryContainer = surfaceColor,
                    onSecondaryContainer = onSurfaceColor,
                    tertiary = accentColor.copy(alpha = 0.8f),
                    onTertiary = Color.White,
                    tertiaryContainer = surfaceColor,
                    onTertiaryContainer = onSurfaceColor,
                    surfaceVariant = surfaceVariant,
                    onSurfaceVariant = onSurfaceVariant,
                    onSurface = onSurfaceColor,
                    outline = onSurfaceColor.copy(alpha = 0.2f)
                )
                
                MaterialTheme(colorScheme = customColorScheme) {
                    PopupContent(onDismiss = { hidePopup(anchorView) }, anchorView = anchorView)
                }
            }
        }

        lifecycleOwner?.moveToState(Lifecycle.State.STARTED)
        lifecycleOwner?.moveToState(Lifecycle.State.RESUMED)

        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = location[1] + anchorView.height
        }

        windowManager.addView(popupView, params)
        popupView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hidePopup()
                true
            } else {
                false
            }
        }
        isShowing = true
    }

    fun hidePopup(anchorView: View? = null) {
        if (isShowing && popupView != null) {
            if (anchorView != null) {
                (popupView as? ComposeView)?.let { view ->
                    view.setContent {
                        val isDarkMode = (context.resources.configuration.uiMode and 
                            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                        val accentColor = Color(context.getColor(android.R.color.system_accent1_500))
                        val backgroundColor = if (isDarkMode) Color.Black else Color.White
                        val surfaceColor = if (isDarkMode) Color(0xFF1C1C1C) else Color(0xFFF5F5F5)
                        val onSurfaceColor = if (isDarkMode) Color.White.copy(alpha = 0.95f) else Color.Black.copy(alpha = 0.87f)
                        val surfaceVariant = if (isDarkMode) Color(0xFF1C1C1C) else Color(0xFFE8E8E8)
                        val onSurfaceVariant = if (isDarkMode) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.75f)
                        
                        val customColorScheme = darkColorScheme(
                            surface = backgroundColor,
                            background = backgroundColor,
                            primary = accentColor,
                            onPrimary = Color.White,
                            primaryContainer = accentColor.copy(alpha = 0.25f),
                            onPrimaryContainer = accentColor,
                            secondary = accentColor,
                            onSecondary = Color.White,
                            secondaryContainer = surfaceColor,
                            onSecondaryContainer = onSurfaceColor,
                            tertiary = accentColor.copy(alpha = 0.8f),
                            onTertiary = Color.White,
                            tertiaryContainer = surfaceColor,
                            onTertiaryContainer = onSurfaceColor,
                            surfaceVariant = surfaceVariant,
                            onSurfaceVariant = onSurfaceVariant,
                            onSurface = onSurfaceColor,
                            outline = onSurfaceColor.copy(alpha = 0.2f)
                        )
                        
                        MaterialTheme(colorScheme = customColorScheme) {
                            DismissAnimationContent(anchorView = anchorView) {
                                actuallyHidePopup()
                            }
                        }
                    }
                    return
                }
            }
            actuallyHidePopup()
        }
    }

    private fun actuallyHidePopup() {
        if (popupView != null) {
            unregisterScreenOffReceiver()
            lifecycleOwner?.moveToState(Lifecycle.State.DESTROYED)
            try {
                windowManager.removeView(popupView)
            } catch (e: Exception) {
            }
            popupView = null
            lifecycleOwner = null
            isShowing = false
        }
    }

    @Composable
    private fun PopupContent(onDismiss: () -> Unit, anchorView: View) {
        val scale = remember { Animatable(0.7f) }
        val alpha = remember { Animatable(0f) }
        val offsetX = remember { Animatable(200f) } // Start from right
        val offsetY = remember { Animatable(-50f) } // Start from above

        LaunchedEffect(Unit) {
            launch {
                scale.animateTo(
                    1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
            launch {
                offsetY.animateTo(
                    0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                offsetX.animateTo(
                    0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                alpha.animateTo(1f, animationSpec = tween(200))
            }

        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.TopEnd
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 8.dp, end = 16.dp)
                    .offset(x = offsetX.value.dp)
                    .offset(y = offsetY.value.dp)
                    .scale(scale.value)
                    .graphicsLayer(alpha = alpha.value),
                horizontalAlignment = Alignment.End
            ) {
                Box(
                    modifier = Modifier
                        .width(380.dp)
                        .height(190.dp)
                        .pointerInput(Unit) { detectTapGestures { /* Prevent dismiss */ } }
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1.4f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                QuickTogglesSection(onDismiss = onDismiss)
                                BatteryIndicatorCard()
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                NetworkSection()
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    modifier = Modifier
                        .width(100.dp)
                        .height(40.dp)
                        .pointerInput(Unit) { detectTapGestures { /* Prevent dismiss */ } },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_SETTINGS)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                    onDismiss()
                                } catch (e: Exception) {
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                onDismiss()
                                onShowPowerMenu()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "Power",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DismissAnimationContent(anchorView: View, onAnimationComplete: () -> Unit) {
        val scale = remember { Animatable(1f) }
        val alpha = remember { Animatable(1f) }
        val offsetX = remember { Animatable(0f) }
        val offsetY = remember { Animatable(0f) }
        
        LaunchedEffect(Unit) {
            launch {
                scale.animateTo(
                    0.7f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
            launch {
                offsetY.animateTo(
                    -50f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
            launch {
                offsetX.animateTo(
                    200f, // Move back to right
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
            launch {
                alpha.animateTo(
                    0f,
                    animationSpec = tween(150, easing = FastOutSlowInEasing)
                )
                onAnimationComplete()
            }
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopEnd
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 8.dp, end = 16.dp)
                    .offset(x = offsetX.value.dp)
                    .offset(y = offsetY.value.dp)
                    .scale(scale.value)
                    .graphicsLayer(alpha = alpha.value),
                horizontalAlignment = Alignment.End
            ) {
                Box(
                    modifier = Modifier
                        .width(380.dp)
                        .height(190.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp
                    ) {}
                }

                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    modifier = Modifier
                        .width(100.dp)
                        .height(40.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {}
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun QuickTogglesSection(onDismiss: () -> Unit) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val haptic = LocalHapticFeedback.current

        var isWifiEnabled by remember { mutableStateOf(wifiManager?.isWifiEnabled ?: false) }
        var isBluetoothEnabled by remember { mutableStateOf(bluetoothAdapter?.isEnabled ?: false) }
        var isLocationEnabled by remember {
            mutableStateOf(
                try {
                    locationManager?.isLocationEnabled ?: false
                } catch (e: Exception) {
                    false
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ShapeMorphingToggle(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Wifi,
                isEnabled = isWifiEnabled,
                onToggle = {
                    isWifiEnabled = !isWifiEnabled
                    try {
                        wifiManager?.isWifiEnabled = isWifiEnabled
                    } catch (e: Exception) {
                    }
                },
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    try {
                        val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        onDismiss()
                    } catch (e: Exception) {
                    }
                }
            )

            ShapeMorphingToggle(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Bluetooth,
                isEnabled = isBluetoothEnabled,
                onToggle = {
                    isBluetoothEnabled = !isBluetoothEnabled
                    try {
                        if (isBluetoothEnabled) bluetoothAdapter?.enable()
                        else bluetoothAdapter?.disable()
                    } catch (e: SecurityException) {
                    }
                },
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    try {
                        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        onDismiss()
                    } catch (e: Exception) {
                    }
                }
            )

            ShapeMorphingToggle(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.LocationOn,
                isEnabled = isLocationEnabled,
                onToggle = {
                    isLocationEnabled = !isLocationEnabled
                    try {
                        locationManager?.setLocationEnabledForUser(
                            isLocationEnabled,
                            android.os.Process.myUserHandle()
                        )
                    } catch (e: Exception) {
                    }
                },
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    try {
                        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        onDismiss()
                    } catch (e: Exception) {
                    }
                }
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ShapeMorphingToggle(
        modifier: Modifier = Modifier,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        isEnabled: Boolean,
        onToggle: () -> Unit,
        onLongPress: () -> Unit
    ) {
        val cornerRadius by animateDpAsState(
            targetValue = if (isEnabled) 12.dp else 24.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "cornerMorph"
        )

        Box(
            modifier = modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(cornerRadius))
                .background(
                    if (isEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = onLongPress
                )
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isEnabled) MaterialTheme.colorScheme.onPrimaryContainer 
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    @Composable
    private fun BatteryIndicatorCard() {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        val batteryPercent by produceState(
            initialValue = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        ) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) value = level * 100 / scale
                }
            }
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            awaitDispose { context.unregisterReceiver(receiver) }
        }

        val animatedPercent by animateIntAsState(targetValue = batteryPercent, label = "battery")

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Battery",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "$animatedPercent%",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                CircleBattery(animatedPercent)
            }
        }
    }

    @Composable
    private fun CircleBattery(percentage: Int) {
        val infiniteTransition = rememberInfiniteTransition(label = "battery")
        val waveOffset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(3000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave"
        )

        val strokeColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.3f)
        val fillColor = MaterialTheme.colorScheme.tertiary

        Box(
            modifier = Modifier.size(60.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension * 0.42f

                drawCircle(
                    color = strokeColor,
                    radius = radius,
                    style = Stroke(width = 2.dp.toPx())
                )

                val fillHeight = size.height * (percentage / 100f)
                val wavePath = Path().apply {
                    val startY = size.height - fillHeight
                    val waveAmplitude = 3f
                    val waveLength = size.width / 2

                    moveTo(0f, startY)

                    for (x in 0..size.width.toInt() step 1) {
                        val normalX = x / waveLength
                        val wave = waveAmplitude * sin((normalX * PI + waveOffset * 2 * PI).toFloat())
                        val y = startY + wave
                        lineTo(x.toFloat(), y)
                    }

                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }

                val circlePath = Path().apply {
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            left = center.x - radius,
                            top = center.y - radius,
                            right = center.x + radius,
                            bottom = center.y + radius
                        )
                    )
                }

                val clippedPath = Path.combine(
                    operation = PathOperation.Intersect,
                    path1 = circlePath,
                    path2 = wavePath
                )

                drawPath(
                    path = clippedPath,
                    color = fillColor
                )
            }
        }
    }

    private fun readActiveSubscriptions(subscriptionManager: SubscriptionManager?) =
        try {
            subscriptionManager?.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

    private fun readAirplaneModeOn(): Boolean =
        try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        } catch (e: Exception) {
            false
        }

    private fun networkTypeLabel(type: Int?): String =
        when (type) {
            TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            else -> "unknown"
        }

    @Composable
    private fun NetworkSection() {
        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        val activeSubscriptions by produceState(initialValue = readActiveSubscriptions(subscriptionManager)) {
            val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() {
                    value = readActiveSubscriptions(subscriptionManager)
                }
            }
            subscriptionManager?.addOnSubscriptionsChangedListener(context.mainExecutor, listener)
            awaitDispose { subscriptionManager?.removeOnSubscriptionsChangedListener(listener) }
        }

        val isAirplaneModeOn by produceState(initialValue = readAirplaneModeOn()) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    value = readAirplaneModeOn()
                }
            }
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED))
            awaitDispose { context.unregisterReceiver(receiver) }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isAirplaneModeOn) {
                NetworkCard(
                    label = "Airplane",
                    sublabel = "Mode On",
                    strength = 0,
                    icon = Icons.Default.AirplanemodeActive
                )
            } else if (activeSubscriptions.isEmpty()) {
                NetworkCard(
                    label = "No SIM",
                    sublabel = "Insert card",
                    strength = 0,
                    icon = Icons.Default.SimCardAlert
                )
            } else {
                activeSubscriptions.take(2).forEachIndexed { index, subscription ->
                    val subId = subscription.subscriptionId
                    val subTelephonyManager = remember(subId) {
                        telephonyManager?.createForSubscriptionId(subId)
                    }
                    var signalStrength by remember(subId) { mutableStateOf(
                        try {
                            subTelephonyManager?.signalStrength?.level ?: 4
                        } catch (e: Exception) {
                            0
                        }
                    ) }

                    var networkType by remember(subId) { mutableStateOf(
                        try {
                            networkTypeLabel(subTelephonyManager?.dataNetworkType)
                        } catch (e: Exception) {
                            "unknown"
                        }
                    ) }

                    DisposableEffect(subTelephonyManager) {
                        val callback = object : TelephonyCallback(),
                            TelephonyCallback.SignalStrengthsListener,
                            TelephonyCallback.DataConnectionStateListener {
                            override fun onSignalStrengthsChanged(strength: SignalStrength) {
                                signalStrength = strength.level
                            }

                            override fun onDataConnectionStateChanged(state: Int, netType: Int) {
                                networkType = networkTypeLabel(netType)
                            }
                        }
                        try {
                            subTelephonyManager?.registerTelephonyCallback(context.mainExecutor, callback)
                        } catch (e: Exception) { }
                        onDispose {
                            try {
                                subTelephonyManager?.unregisterTelephonyCallback(callback)
                            } catch (e: Exception) { }
                        }
                    }

                    NetworkCard(
                        label = subscription.carrierName?.toString() ?: "SIM ${subscription.simSlotIndex + 1}",
                        sublabel = networkType,
                        strength = signalStrength,
                        icon = Icons.Default.SignalCellularAlt
                    )
                }
            }

            ConnectionStatusCard()
        }
    }

    @Composable
    private fun ConnectionStatusCard() {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        var refreshTrigger by remember { mutableStateOf(0) }

        LaunchedEffect(Unit) {
            while (true) {
                delay(500)
                refreshTrigger++
            }
        }

        val isWifiConnected = remember(refreshTrigger) {
            try {
                if (wifiManager?.isWifiEnabled == true) {
                    val wifiInfo = wifiManager.connectionInfo
                    wifiInfo != null && wifiInfo.networkId != -1
                } else false
            } catch (e: Exception) {
                false
            }
        }

        val isBluetoothConnected = remember(refreshTrigger) {
            try {
                if (bluetoothAdapter?.isEnabled == true) {
                    bluetoothAdapter.bondedDevices?.any { device ->
                        try {
                            device.javaClass.getMethod("isConnected").invoke(device) as? Boolean ?: false
                        } catch (e: Exception) {
                            false
                        }
                    } ?: false
                } else false
            } catch (e: SecurityException) {
                false
            }
        }

        val isVpnConnected = remember(refreshTrigger) {
            try {
                val network = connectivityManager?.activeNetwork
                val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } catch (e: Exception) {
                false
            }
        }

        data class ConnectionInfo(
            val icon: androidx.compose.ui.graphics.vector.ImageVector
        )

        val connections = mutableListOf<ConnectionInfo>()
        if (isWifiConnected) connections.add(ConnectionInfo(Icons.Default.Wifi))
        if (isBluetoothConnected) connections.add(ConnectionInfo(Icons.Default.Bluetooth))
        if (isVpnConnected) connections.add(ConnectionInfo(Icons.Default.VpnKey))

        if (connections.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    connections.forEach { conn ->
                        Icon(
                            imageVector = conn.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun NetworkCard(
        label: String,
        sublabel: String,
        strength: Int,
        icon: androidx.compose.ui.graphics.vector.ImageVector
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        modifier = Modifier.size(32.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        MarqueeText(
                            text = label,
                            style = TextStyle(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Text(
                            text = sublabel,
                            style = TextStyle(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Normal
                            )
                        )
                    }
                }

                if (strength > 0) {
                    SignalBars(strength = strength)
                }
            }
        }
    }

    @Composable
    private fun SignalBars(strength: Int) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.5.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.height(18.dp)
        ) {
            for (i in 1..5) {
                val isActive = i <= strength
                val targetHeight = (i * 3 + 3).dp

                val animatedHeight by animateDpAsState(
                    targetValue = if (isActive) targetHeight else targetHeight * 0.4f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "barHeight$i"
                )

                Surface(
                    modifier = Modifier
                        .width(3.5.dp)
                        .height(animatedHeight),
                    shape = RoundedCornerShape(2.dp),
                    color = if (isActive) MaterialTheme.colorScheme.primary 
                           else MaterialTheme.colorScheme.surfaceVariant
                ) {}
            }
        }
    }

    @Composable
    private fun MarqueeText(
        text: String,
        style: TextStyle,
        modifier: Modifier = Modifier
    ) {
        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        var containerSize by remember { mutableStateOf(IntSize.Zero) }
        val density = LocalDensity.current

        val textWidth = textLayoutResult?.size?.width ?: 0
        val containerWidth = containerSize.width
        
        val shouldScroll = textWidth > containerWidth && containerWidth > 0

        val offsetX = remember { Animatable(0f) }

        LaunchedEffect(shouldScroll, textWidth, containerWidth) {
            if (shouldScroll) {
                offsetX.snapTo(0f)
                while (true) {
                    delay(1500)
                    offsetX.animateTo(
                        targetValue = -(textWidth - containerWidth).toFloat() - with(density) { 20.dp.toPx() },
                        animationSpec = tween(
                            durationMillis = maxOf(3000, text.length * 80),
                            easing = LinearEasing
                        )
                    )
                    delay(1000)
                    offsetX.snapTo(0f)
                }
            } else {
                offsetX.snapTo(0f)
            }
        }

        Box(
            modifier = modifier
                .onSizeChanged { size ->
                    containerSize = size
                }
                .clipToBounds()
        ) {
            Text(
                text = text,
                style = style,
                maxLines = 1,
                softWrap = false,
                onTextLayout = { layoutResult ->
                    textLayoutResult = layoutResult
                },
                modifier = Modifier
                    .offset(x = with(density) { offsetX.value.toDp() })
            )
        }
    }

    private class CustomLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        init {
            savedStateRegistryController.performAttach()
            savedStateRegistryController.performRestore(null)
        }

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry =
            savedStateRegistryController.savedStateRegistry

        fun moveToState(state: Lifecycle.State) {
            lifecycleRegistry.currentState = state
        }
    }
}
