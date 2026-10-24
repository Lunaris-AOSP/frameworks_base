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

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.android.internal.util.ScreenshotHelper
import com.android.systemui.Dependency
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.policy.FlashlightController

internal fun lunarisBarLaunch(context: Context, intent: Intent) {
    runCatching {
            Dependency.get(ActivityStarter::class.java)
                .postStartActivityDismissingKeyguard(
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    0,
                )
        }
        .onFailure { Log.w("LunarisBar", "Unable to open activity", it) }
}

internal fun lunarisBarAction(
    action: String,
    context: Context,
    model: PopupChipModel.Shown? = null,
    expand: () -> Unit = {},
    page: (Int) -> Unit = {},
    selectCard: (String) -> Unit = {},
) {
    val media = (model?.popupContent as? PopupContentModel.Media)?.model
    fun mediaKey(code: Int) {
        val manager = context.getSystemService(AudioManager::class.java)
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
    fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        val input = context.getSystemService(InputManager::class.java)
        input.injectInputEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0),
            InputManager.INJECT_INPUT_EVENT_MODE_ASYNC,
        )
        input.injectInputEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0),
            InputManager.INJECT_INPUT_EVENT_MODE_ASYNC,
        )
    }
    runCatching {
            when (action) {
                "none" -> Unit
                "expand" -> expand()
                "next_card" -> page(1)
                "previous_card" -> page(-1)
                "open_app" -> {
                    val open =
                        when (val content = model?.popupContent) {
                            is PopupContentModel.Media -> content.model.openApp
                            is PopupContentModel.Alarm -> content.model.onOpen
                            is PopupContentModel.Stopwatch -> content.model.onOpen
                            is PopupContentModel.LiveScore -> content.model.onOpen
                            is PopupContentModel.SystemEvent ->
                                content.actions
                                    .firstOrNull {
                                        it.role ==
                                            com.android.systemui.statusbar.featurepods.popups.shared
                                                .model
                                                .PopupActionRole
                                                .OpenApp
                                    }
                                    ?.onClick
                            else -> null
                        }
                    if (open != null) open() else expand()
                }
                "play_pause" ->
                    media?.playOrPause?.action?.run() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "next_track" ->
                    media?.nextAction?.action?.run() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                "previous_track" ->
                    media?.previousAction?.action?.run()
                        ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "flashlight" ->
                    Dependency.get(FlashlightController::class.java).let {
                        it.setFlashlight(!it.isEnabled)
                    }
                "notifications" ->
                    context
                        .getSystemService(StatusBarManager::class.java)
                        .expandNotificationsPanel()
                "quick_settings" ->
                    context.getSystemService(StatusBarManager::class.java).expandSettingsPanel()
                "home" -> key(KeyEvent.KEYCODE_HOME)
                "back" -> key(KeyEvent.KEYCODE_BACK)
                "recents" -> key(KeyEvent.KEYCODE_APP_SWITCH)
                "lock" ->
                    context
                        .getSystemService(PowerManager::class.java)
                        .goToSleep(SystemClock.uptimeMillis())
                "screenshot" -> {
                    model?.hidePopup?.invoke()
                    Handler(Looper.getMainLooper())
                        .postDelayed(
                            {
                                ScreenshotHelper(context)
                                    .takeScreenshot(
                                        android.view.WindowManager.ScreenshotSource
                                            .SCREENSHOT_GLOBAL_ACTIONS,
                                        Handler(Looper.getMainLooper()),
                                        null,
                                    )
                            },
                            300L,
                        )
                }
                "settings" -> lunarisBarLaunch(context, Intent(Settings.ACTION_SETTINGS))
                "brightness_settings" ->
                    lunarisBarLaunch(context, Intent(Settings.ACTION_DISPLAY_SETTINGS))
                "volume_settings" -> lunarisBarLaunch(context, Intent(Settings.Panel.ACTION_VOLUME))
                "bluetooth_settings" ->
                    lunarisBarLaunch(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                "wifi_settings" -> lunarisBarLaunch(context, Intent(Settings.ACTION_WIFI_SETTINGS))
                "internet_settings" ->
                    lunarisBarLaunch(context, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                "location_settings" ->
                    lunarisBarLaunch(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                "mute" ->
                    context
                        .getSystemService(AudioManager::class.java)
                        .adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_TOGGLE_MUTE,
                            0,
                        )
                "auto_brightness" -> {
                    val current =
                        Settings.System.getIntForUser(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            0,
                            android.os.UserHandle.USER_CURRENT,
                        )
                    Settings.System.putIntForUser(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        if (current == 0) 1 else 0,
                        android.os.UserHandle.USER_CURRENT,
                    )
                }
                else ->
                    when {
                        action.startsWith("card:") -> selectCard(action.removePrefix("card:"))
                        action.startsWith("app:") -> {
                            val component =
                                ComponentName.unflattenFromString(action.removePrefix("app:"))
                            if (component != null)
                                lunarisBarLaunch(
                                    context,
                                    Intent(Intent.ACTION_MAIN)
                                        .addCategory(Intent.CATEGORY_LAUNCHER)
                                        .setComponent(component),
                                )
                        }
                        action.startsWith("contact:") ->
                            lunarisBarLaunch(
                                context,
                                Intent(
                                    Intent.ACTION_DIAL,
                                    Uri.fromParts("tel", action.removePrefix("contact:"), null),
                                ),
                            )
                        action.startsWith("url:") -> {
                            val uri = Uri.parse(action.removePrefix("url:"))
                            if (uri.scheme == "https" || uri.scheme == "http")
                                lunarisBarLaunch(context, Intent(Intent.ACTION_VIEW, uri))
                        }
                        action.startsWith("intent:") -> {
                            val intent =
                                Intent.parseUri(
                                    action.removePrefix("intent:"),
                                    Intent.URI_INTENT_SCHEME,
                                )
                            intent.selector = null
                            intent.clipData = null
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            lunarisBarLaunch(context, intent)
                        }
                        action.startsWith("system:") ->
                            lunarisBarLaunch(context, Intent(action.removePrefix("system:")))
                    }
            }
        }
        .onFailure { Log.w("LunarisBar", "Unable to perform action", it) }
}
