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

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.TextClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.internal.util.lunaris.LunarisBarSettings
import com.android.systemui.Dependency
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.FlashlightController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray

@Composable
internal fun LunarisBarUtilityCard(card: LunarisBarSettings.Card, close: () -> Unit) {
    val colors = LocalLunarisBarColors.current
    val haptics = LocalLunarisBarState.current.settings.isHapticsEnabled
    val view = LocalView.current
    val currentClose by rememberUpdatedState(close)
    var lastInteraction by remember(card.id) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val timeout = card.integer("hide_after", 0, 0, 60)
    LaunchedEffect(card.id, timeout, lastInteraction) {
        if (timeout > 0) {
            delay(timeout * 1000L)
            currentClose()
        }
    }
    Column(
        Modifier.widthIn(max = 480.dp)
            .fillMaxWidth()
            .heightIn(max = 540.dp)
            .pointerInput(card.id, haptics) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        lastInteraction = SystemClock.elapsedRealtime()
                        if (haptics && event.changes.any { it.pressed && !it.previousPressed })
                            view.performHapticFeedback(
                                android.view.HapticFeedbackConstants.CONTEXT_CLICK
                            )
                    }
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LunarisBarCardHeader(card.title, glyph = lunarisBarUtilityGlyph(card.type))
        when (card.type) {
            "shortcuts" -> LunarisBarShortcuts(card, close)
            "widget" -> LunarisBarWidget(card, close)
            "clock" -> LunarisBarClock(card)
            "timer" -> LunarisBarTimer(card, close)
            "barcode" -> LunarisBarBarcode(card)
            "brightness",
            "volume",
            "flashlight" -> LunarisBarSliderCard(card)
            "battery" -> LunarisBarBattery(card)
        }
    }
}

@Composable
private fun LunarisBarClock(card: LunarisBarSettings.Card) {
    val colors = LocalLunarisBarColors.current
    val timeColors = lunarisBarCardPalette(card, "time", 5, colors.surface to colors.accent)
    val dateColors = lunarisBarCardPalette(card, "date", 0, colors.surface to colors.content)
    AndroidView(
        factory = { TextClock(it) },
        update = { clock ->
            clock.format12Hour = if (card.enabled("seconds", false)) "h:mm:ss a" else "h:mm a"
            clock.format24Hour = if (card.enabled("seconds", false)) "HH:mm:ss" else "HH:mm"
            clock.timeZone = card.text("timezone", "").takeIf { it.isNotBlank() }
            clock.textSize = 42f
            clock.setTextColor(timeColors.second.toArgb())
            clock.setOnClickListener {
                lunarisBarLaunch(clock.context, Intent(AlarmClock.ACTION_SHOW_ALARMS))
            }
        },
        modifier =
            Modifier.fillMaxWidth()
                .clip(LunarisBarSquircle(24.dp))
                .background(timeColors.first)
                .padding(16.dp),
    )
    AndroidView(
        factory = { TextClock(it) },
        update = {
            it.format12Hour = "EEEE, MMMM d"
            it.format24Hour = "EEEE, MMMM d"
            it.timeZone = card.text("timezone", "").takeIf { value -> value.isNotBlank() }
            it.textSize = 16f
            it.setTextColor(dateColors.second.toArgb())
        },
        modifier =
            Modifier.fillMaxWidth()
                .clip(LunarisBarSquircle(24.dp))
                .background(dateColors.first)
                .padding(12.dp),
    )
}

@Composable
private fun LunarisBarTimer(card: LunarisBarSettings.Card, close: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalLunarisBarColors.current
    val timeColors = lunarisBarCardPalette(card, "time", 5, colors.surface to colors.content)
    val buttonColors = lunarisBarCardPalette(card, "button", 5, colors.accent to colors.content)
    var minutes by
        remember(card) { mutableFloatStateOf(card.integer("minutes", 5, 1, 180).toFloat()) }
    Text(
        stringResource(R.string.lunaris_bar_minutes_value, minutes.roundToInt()),
        style = MaterialTheme.typography.displaySmall,
        color = timeColors.second,
        modifier =
            Modifier.fillMaxWidth()
                .clip(LunarisBarSquircle(24.dp))
                .background(timeColors.first)
                .padding(16.dp),
    )
    LunarisBarControlSlider(
        card,
        value = minutes,
        onValueChange = { minutes = it },
        valueRange = 1f..180f,
        steps = 178,
    )
    Button(
        onClick = {
            close()
            lunarisBarLaunch(
                context,
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, minutes.roundToInt() * 60)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, card.title)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
            )
        },
        shape = lunarisBarButtonShape(card.integer("toggle_shape", 20, 0, 31)),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = buttonColors.first,
                contentColor = buttonColors.second,
            ),
    ) {
        Text(stringResource(R.string.lunaris_bar_start_timer))
    }
}

@Composable
private fun LunarisBarShortcuts(card: LunarisBarSettings.Card, close: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalLunarisBarColors.current
    val iconColors = lunarisBarCardPalette(card, "icon", 2, colors.surface to colors.content)
    val entries =
        remember(card) {
            val array =
                runCatching { JSONArray(card.text("actions", "[]")) }.getOrDefault(JSONArray())
            (0 until minOf(array.length(), 24)).mapNotNull { array.optJSONObject(it) }
        }
    if (entries.isEmpty()) {
        Text(stringResource(R.string.lunaris_bar_no_shortcuts), color = colors.content)
    }
    entries.chunked(card.integer("columns", 4, 2, 6)).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { entry ->
                val action = entry.optString("action")
                var icon by
                    remember(action) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
                LaunchedEffect(action) {
                    icon =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                    if (action.startsWith("app:")) {
                                        val component =
                                            ComponentName.unflattenFromString(
                                                action.removePrefix("app:")
                                            )
                                        component?.let {
                                            context.packageManager.getActivityIcon(it)
                                        }
                                    } else null
                                }
                                .getOrNull()
                        }
                }
                Column(
                    Modifier.weight(1f).clickable {
                        if (card.enabled("close_on_tap", true)) close()
                        lunarisBarAction(action, context)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size((48 * card.integer("icon_scale", 100, 70, 150) / 100f).dp)
                            .clip(lunarisBarIconShape(card.integer("icon_shape", 2, 0, 31)))
                            .background(iconColors.first),
                        contentAlignment = Alignment.Center,
                    ) {
                        icon?.let {
                            Icon(
                                IconModel.Loaded(it, null),
                                modifier = Modifier.size(32.dp),
                                tint =
                                    if (card.enabled("themed_icons", false)) iconColors.second
                                    else Color.Unspecified,
                            )
                        }
                            ?: Text(
                                entry.optString("label").take(1),
                                style = MaterialTheme.typography.titleLarge,
                            )
                    }
                    Text(
                        entry.optString("label"),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                        color = colors.content,
                    )
                }
            }
        }
    }
}

@Composable
private fun LunarisBarBarcode(card: LunarisBarSettings.Card) {
    val colors = LocalLunarisBarColors.current
    val barcodeColors = lunarisBarCardPalette(card, "barcode", 6, Color.White to Color.Black)
    var bitmap by remember(card) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(card) { mutableStateOf(false) }
    LaunchedEffect(card, barcodeColors) {
        bitmap =
            withContext(Dispatchers.Default) {
                runCatching {
                        val format = BarcodeFormat.valueOf(card.text("format", "QR_CODE"))
                        val square =
                            format in
                                listOf(
                                    BarcodeFormat.QR_CODE,
                                    BarcodeFormat.AZTEC,
                                    BarcodeFormat.DATA_MATRIX,
                                )
                        val matrix =
                            MultiFormatWriter()
                                .encode(
                                    card.text("value", ""),
                                    format,
                                    640,
                                    if (square) 640 else 240,
                                    mapOf(
                                        EncodeHintType.MARGIN to 4,
                                        EncodeHintType.CHARACTER_SET to "UTF-8",
                                    ),
                                )
                        val pixels =
                            IntArray(matrix.width * matrix.height) { index ->
                                if (matrix[index % matrix.width, index / matrix.width])
                                    barcodeColors.second.toArgb()
                                else barcodeColors.first.toArgb()
                            }
                        val encoded =
                            Bitmap.createBitmap(
                                pixels,
                                matrix.width,
                                matrix.height,
                                Bitmap.Config.ARGB_8888,
                            )
                        if (card.enabled("rotate", false)) {
                            val rotated =
                                Bitmap.createBitmap(
                                    encoded,
                                    0,
                                    0,
                                    encoded.width,
                                    encoded.height,
                                    android.graphics.Matrix().apply { postRotate(90f) },
                                    false,
                                )
                            if (rotated !== encoded) encoded.recycle()
                            rotated
                        } else encoded
                    }
                    .getOrNull()
            }
        failed = bitmap == null
    }
    if (failed) Text(stringResource(R.string.lunaris_bar_barcode_error))
    bitmap?.let { image ->
        Image(
            image.asImageBitmap(),
            contentDescription = card.title,
            modifier =
                Modifier.fillMaxWidth()
                    .height(card.height.dp)
                    .background(barcodeColors.first, RoundedCornerShape(12.dp))
                    .padding(8.dp),
        )
    }
    val label = card.text("label", "")
    if (label.isNotBlank())
        Text(
            label,
            color = LocalLunarisBarColors.current.content,
            style = MaterialTheme.typography.labelLarge,
        )
}

@Composable
private fun LunarisBarWidget(card: LunarisBarSettings.Card, close: () -> Unit) {
    val context = LocalContext.current
    val userId = LocalLunarisBarState.current.userId
    val userContext =
        remember(context, userId) { context.createContextAsUser(UserHandle.of(userId), 0) }
    val widgetContext =
        remember(userContext, card.integer("widget_theme", 0, 0, 2)) {
            val configuration = Configuration(userContext.resources.configuration)
            when (card.integer("widget_theme", 0, 0, 2)) {
                1 ->
                    configuration.uiMode =
                        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            Configuration.UI_MODE_NIGHT_NO
                2 ->
                    configuration.uiMode =
                        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            Configuration.UI_MODE_NIGHT_YES
            }
            userContext.createConfigurationContext(configuration)
        }
    val host =
        remember(widgetContext) { AppWidgetHost(widgetContext, LunarisBarSettings.WIDGET_HOST_ID) }
    val manager = remember(userContext) { AppWidgetManager.getInstance(userContext) }
    val info = remember(manager, card.widgetId) { manager.getAppWidgetInfo(card.widgetId) }
    if (info == null) {
        Text(stringResource(R.string.lunaris_bar_widget_unavailable))
        return
    }
    DisposableEffect(host) {
        runCatching { host.startListening() }
        onDispose { runCatching { host.stopListening() } }
    }
    AndroidView(
        factory = {
            host.createView(widgetContext, card.widgetId, info).apply {
                setAppWidget(card.widgetId, info)
            }
        },
        update = { widget ->
            val width =
                minOf(
                        card.integer("width", 360, 64, 480),
                        context.resources.configuration.screenWidthDp - 64,
                    )
                    .coerceAtLeast(1)
            widget.updateAppWidgetSize(null, width, card.height, width, card.height)
        },
        modifier =
            Modifier.widthIn(max = card.integer("width", 360, 64, 480).dp)
                .fillMaxWidth()
                .height(card.height.dp)
                .pointerInput(card.id, card.enabled("close_on_tap", false)) {
                    if (!card.enabled("close_on_tap", false)) return@pointerInput
                    var start = androidx.compose.ui.geometry.Offset.Zero
                    var moved = false
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { change ->
                                if (change.pressed && !change.previousPressed) {
                                    start = change.position
                                    moved = false
                                }
                                if (
                                    (change.position - start).getDistance() >
                                        viewConfiguration.touchSlop
                                )
                                    moved = true
                                if (!change.pressed && change.previousPressed && !moved) {
                                    Handler(Looper.getMainLooper()).postDelayed({ close() }, 150)
                                }
                            }
                        }
                    }
                },
    )
}

@Composable
private fun LunarisBarSliderCard(card: LunarisBarSettings.Card) {
    when (card.type) {
        "volume" -> LunarisBarVolume(card)
        "brightness" -> LunarisBarBrightness(card)
        "flashlight" -> LunarisBarFlashlight(card)
    }
    val action = card.text("toggle_action", "none")
    if (action != "none") {
        val context = LocalContext.current
        val toggleColors =
            lunarisBarCardPalette(
                card,
                "toggle",
                2,
                LocalLunarisBarColors.current.accent to LocalLunarisBarColors.current.content,
            )
        Button(
            onClick = { lunarisBarAction(action, context) },
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = toggleColors.first,
                    contentColor = toggleColors.second,
                ),
            shape = lunarisBarButtonShape(card.integer("toggle_shape", 2, 0, 31)),
        ) {
            Text(card.text("toggle_label", context.getString(R.string.lunaris_bar_open)))
        }
    }
}

@Composable
private fun LunarisBarVolume(card: LunarisBarSettings.Card) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val stream =
        when (card.integer("stream", 0, 0, 4)) {
            1 -> AudioManager.STREAM_RING
            2 -> AudioManager.STREAM_NOTIFICATION
            3 -> AudioManager.STREAM_ALARM
            4 -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
    val min = audio.getStreamMinVolume(stream)
    val max = audio.getStreamMaxVolume(stream).coerceAtLeast(min + 1)
    var volume by remember(stream) { mutableFloatStateOf(audio.getStreamVolume(stream).toFloat()) }
    DisposableEffect(stream) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    volume = audio.getStreamVolume(stream).toFloat()
                }
            }
        context.registerReceiver(
            receiver,
            IntentFilter(AudioManager.VOLUME_CHANGED_ACTION),
            Context.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    if (!card.enabled("hide_value", false))
        Text(
            "${((volume - min) / (max - min) * 100).roundToInt()}%",
            style = MaterialTheme.typography.displaySmall,
            color = LocalLunarisBarColors.current.accent,
        )
    LunarisBarControlSlider(
        card,
        value = volume.coerceIn(min.toFloat(), max.toFloat()),
        onValueChange = {
            volume = it
            audio.setStreamVolume(stream, it.roundToInt(), 0)
        },
        valueRange = min.toFloat()..max.toFloat(),
        steps = (max - min - 1).coerceAtLeast(0),
    )
}

@Composable
private fun LunarisBarBrightness(card: LunarisBarSettings.Card) {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(DisplayManager::class.java) }
    val displayId = context.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
    val info = manager.getDisplay(displayId)?.brightnessInfo
    val min = info?.brightnessMinimum?.coerceAtLeast(0f) ?: 0f
    val max = info?.brightnessMaximum?.coerceAtLeast(min + 0.01f) ?: 1f
    var brightness by
        remember(displayId) {
            mutableFloatStateOf(
                manager.getBrightness(displayId).takeIf { it.isFinite() }?.coerceIn(min, max) ?: min
            )
        }
    var automatic by remember {
        mutableStateOf(
            Settings.System.getIntForUser(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                0,
                UserHandle.USER_CURRENT,
            ) == 1
        )
    }
    DisposableEffect(manager, displayId) {
        val listener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(id: Int) {}

                override fun onDisplayRemoved(id: Int) {}

                override fun onDisplayChanged(id: Int) {
                    if (id == displayId)
                        manager
                            .getBrightness(id)
                            .takeIf { it.isFinite() }
                            ?.let { brightness = it.coerceIn(min, max) }
                }
            }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    automatic =
                        Settings.System.getIntForUser(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            0,
                            UserHandle.USER_CURRENT,
                        ) == 1
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        onDispose {
            manager.unregisterDisplayListener(listener)
            context.contentResolver.unregisterContentObserver(observer)
            manager.setTemporaryBrightness(displayId, Float.NaN)
        }
    }
    if (!card.enabled("hide_value", false))
        Text(
            "${((brightness - min) / (max - min) * 100).roundToInt()}%",
            style = MaterialTheme.typography.displaySmall,
            color = LocalLunarisBarColors.current.accent,
        )
    LunarisBarControlSlider(
        card,
        value = brightness,
        onValueChange = {
            brightness = it
            manager.setTemporaryBrightness(displayId, it)
        },
        onValueChangeFinished = { manager.setBrightness(displayId, brightness) },
        valueRange = min..max,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.lunaris_bar_auto_brightness), modifier = Modifier.weight(1f))
        Switch(
            checked = automatic,
            onCheckedChange = {
                automatic = it
                Settings.System.putIntForUser(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    if (it) 1 else 0,
                    UserHandle.USER_CURRENT,
                )
            },
        )
    }
}

@Composable
private fun LunarisBarFlashlight(card: LunarisBarSettings.Card) {
    val controller = remember { Dependency.get(FlashlightController::class.java) }
    var enabled by remember { mutableStateOf(controller.isEnabled) }
    var available by remember { mutableStateOf(controller.isAvailable) }
    var level by remember { mutableFloatStateOf(controller.currentLevel.toFloat()) }
    DisposableEffect(controller) {
        val listener =
            object : FlashlightController.FlashlightListener {
                override fun onFlashlightChanged(value: Boolean) {
                    enabled = value
                }

                override fun onFlashlightError() {
                    enabled = false
                }

                override fun onFlashlightAvailabilityChanged(value: Boolean) {
                    available = value
                }

                override fun onFlashlightStrengthChanged(value: Int) {
                    level = value.toFloat()
                }
            }
        controller.addCallback(listener)
        onDispose { controller.removeCallback(listener) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (enabled) R.string.lunaris_bar_on else R.string.lunaris_bar_off),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(enabled = available, checked = enabled, onCheckedChange = controller::setFlashlight)
    }
    if (controller.isStrengthControlSupported && controller.maxLevel > 1) {
        LunarisBarControlSlider(
            card,
            value = level.coerceIn(1f, controller.maxLevel.toFloat()),
            onValueChange = {
                level = it
                controller.setFlashlightStrengthLevel(it.roundToInt())
            },
            enabled = available && enabled,
            valueRange = 1f..controller.maxLevel.toFloat(),
            steps = (controller.maxLevel - 2).coerceAtLeast(0),
        )
    }
}

@Composable
private fun LunarisBarBattery(card: LunarisBarSettings.Card) {
    val battery = rememberLunarisBarBattery()
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
    val scale = (battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
    LunarisBarBatteryDetails(card, battery, level, scale)
}

@Composable
internal fun rememberLunarisBarBattery(): Intent? {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<Intent?>(null) }
    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    battery = intent
                }
            }
        battery =
            context.registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return battery
}

@Composable
private fun LunarisBarBatteryDetails(
    card: LunarisBarSettings.Card,
    battery: Intent?,
    level: Int,
    scale: Int,
) {
    val colors = LocalLunarisBarColors.current
    val batteryColors = lunarisBarCardPalette(card, "battery", 5, colors.surface to colors.content)
    val detailColors = lunarisBarCardPalette(card, "details", 2, colors.surface to colors.content)
    val buttonColors = lunarisBarCardPalette(card, "button", 5, colors.accent to colors.content)
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            Modifier.size(88.dp)
                .clip(lunarisBarIconShape(card.integer("icon_shape", 2, 0, 31)))
                .background(batteryColors.first)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { (level.toFloat() / scale).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 6.dp,
                color = batteryColors.second,
            )
            Text(
                "${level * 100 / scale}%",
                style = MaterialTheme.typography.titleLarge,
                color = batteryColors.second,
            )
        }
        Column(
            Modifier.weight(1f)
                .clip(LunarisBarSquircle(24.dp))
                .background(detailColors.first)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides detailColors.second
            ) {
                val temperature =
                    (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
                val fahrenheit = card.enabled("fahrenheit", false)
                Text(
                    if (fahrenheit) "${(temperature * 1.8f + 32).roundToInt()} °F"
                    else "$temperature °C"
                )
                Text("${(battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f} V")
                Text(
                    stringResource(
                        if ((battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
                            R.string.lunaris_bar_charging
                        else R.string.lunaris_bar_on_battery
                    )
                )
            }
        }
    }
    Button(
        onClick = { lunarisBarLaunch(context, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) },
        shape = lunarisBarButtonShape(card.integer("toggle_shape", 20, 0, 31)),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = buttonColors.first,
                contentColor = buttonColors.second,
            ),
    ) {
        Text(stringResource(R.string.lunaris_bar_battery_settings))
    }
}

@Composable
private fun LunarisBarControlSlider(
    card: LunarisBarSettings.Card,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val style = card.integer("slider_style", 0, 0, 2)
    val scheme = MaterialTheme.colorScheme
    val active = lunarisBarCardPalette(card, "slider", 5, scheme.primary to scheme.onPrimary)
    val track =
        lunarisBarCardPalette(
            card,
            "track",
            2,
            scheme.secondaryContainer to scheme.onSecondaryContainer,
        )
    val colors =
        SliderDefaults.colors(
            thumbColor = active.first,
            activeTrackColor = active.first,
            inactiveTrackColor = track.first,
        )
    val interaction = remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    val state =
        remember(valueRange, steps) { SliderState(value, steps, onValueChangeFinished, valueRange) }
    SideEffect {
        state.value = value.coerceIn(valueRange)
        state.onValueChange = onValueChange
        state.onValueChangeFinished = onValueChangeFinished
    }
    if (style == 2) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            VerticalSlider(
                state,
                modifier = Modifier.height(200.dp).width(64.dp),
                enabled = enabled,
                colors = colors,
            )
        }
    } else {
        Slider(
            state = state,
            enabled = enabled,
            colors = colors,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth(),
            track = {
                SliderDefaults.Track(
                    sliderState = it,
                    colors = colors,
                    enabled = enabled,
                    modifier = Modifier.height(if (style == 1) 32.dp else 16.dp),
                )
            },
        )
    }
}

@Composable
internal fun lunarisBarCardPalette(
    card: LunarisBarSettings.Card,
    part: String,
    default: Int,
    fallback: Pair<Color, Color>,
): Pair<Color, Color> {
    val suffix = if (LocalLunarisBarColors.current.dark) "night" else "day"
    val palette =
        card.integer(
            "${part}_palette_$suffix",
            card.integer("${part}_palette", default, 0, 20),
            0,
            20,
        )
    val custom =
        Color(
            card.integer(
                "${part}_background_$suffix",
                fallback.first.toArgb(),
                Int.MIN_VALUE,
                Int.MAX_VALUE,
            )
        ) to
            Color(
                card.integer(
                    "${part}_foreground_$suffix",
                    fallback.second.toArgb(),
                    Int.MIN_VALUE,
                    Int.MAX_VALUE,
                )
            )
    return rememberLunarisBarPalette(palette, custom)
}
