/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.featurepods.popups.shared

import android.content.ComponentCallbacks
import android.content.ContentResolver
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object DynamicIslandFeatureSettings {
    /** Opt-in; independent of every AxDynamicBar setting. */
    const val BATTERY = "status_bar_dynamic_island_battery"
    const val UNLOCK = "status_bar_dynamic_island_unlock"
    const val BLUETOOTH = "status_bar_dynamic_island_bluetooth"
    const val CALLS = "status_bar_dynamic_island_calls"
    const val NOTIFICATIONS = "status_bar_dynamic_island_notifications"
    const val ONGOING_ACTIVITIES = "status_bar_dynamic_island_ongoing_activities"
    const val HOTSPOT = "status_bar_dynamic_island_hotspot"
    const val VPN = "status_bar_dynamic_island_vpn"
    const val RINGER = "status_bar_dynamic_island_ringer"
    const val CLIPBOARD = "status_bar_dynamic_island_clipboard"
    const val TIMER = "status_bar_dynamic_island_timer"
    const val AUDIO_RECORDING = "status_bar_dynamic_island_audio_recording"
    const val NOW_PLAYING = "status_bar_dynamic_island_now_playing"
    const val RECENT_APPS = "status_bar_dynamic_island_recent_apps"

    const val MEDIA_CONTROLS = "status_bar_dynamic_island_media_controls"
    const val SCREEN_RECORDING = "status_bar_dynamic_island_screen_recording"
    const val ALARMS = "status_bar_dynamic_island_alarms"
    const val FLASHLIGHT = "status_bar_dynamic_island_flashlight"
    const val STOPWATCH = "status_bar_dynamic_island_stopwatch"
    const val LIVE_SCORES = "status_bar_dynamic_island_live_scores"
    const val SHOW_LYRICS = "status_bar_dynamic_island_lyrics"
    const val WIDTH_SCALE = "status_bar_dynamic_island_width_scale"
    const val HEIGHT_SCALE = "status_bar_dynamic_island_height_scale"
    const val POPUP_COLOR_MODE = "status_bar_dynamic_island_popup_color_mode"
    const val LOCKSCREEN = "status_bar_dynamic_island_lockscreen"
    const val DISABLE_IN_LANDSCAPE = Settings.System.STATUS_BAR_DYNAMIC_ISLAND_DISABLE_IN_LANDSCAPE

    const val SCALE_MIN = 0.7f
    const val SCALE_MAX = 1.4f
    private const val SCALE_DEFAULT = 1.0f
    private const val SCALE_PERCENT_DEFAULT = 100

    const val POPUP_COLOR_MODE_DEFAULT = 0
    const val POPUP_COLOR_MODE_BLUR = 1
    const val POPUP_COLOR_MODE_SOLID_BLACK = 2

    fun readDynamicIslandEnabled(
        context: Context,
        configuration: Configuration = context.resources.configuration,
    ): Boolean =
        context.contentResolver.readDynamicIslandFeatureEnabled(
            Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND,
            defaultValue = false,
        ) &&
            !(configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                context.contentResolver.readDynamicIslandFeatureEnabled(
                    DISABLE_IN_LANDSCAPE,
                    defaultValue = false,
                ))

    fun observeDynamicIslandEnabled(context: Context): Flow<Boolean> =
        callbackFlow {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(readDynamicIslandEnabled(context))
                    }
                }
            val configurationCallback =
                object : ComponentCallbacks {
                    override fun onConfigurationChanged(newConfig: Configuration) {
                        trySend(readDynamicIslandEnabled(context, newConfig))
                    }

                    override fun onLowMemory() {}
                }
            for (key in listOf(Settings.System.STATUS_BAR_SHOW_DYNAMIC_ISLAND, DISABLE_IN_LANDSCAPE)) {
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(key),
                    false,
                    observer,
                    UserHandle.USER_ALL,
                )
            }
            context.registerComponentCallbacks(configurationCallback)
            trySend(readDynamicIslandEnabled(context))
            awaitClose {
                context.unregisterComponentCallbacks(configurationCallback)
                context.contentResolver.unregisterContentObserver(observer)
            }
        }.distinctUntilChanged()

    fun ContentResolver.readDynamicIslandPopupColorMode(): Int {
        return Settings.System.getIntForUser(
            this,
            POPUP_COLOR_MODE,
            POPUP_COLOR_MODE_DEFAULT,
            UserHandle.USER_CURRENT,
        )
    }

    fun observeDynamicIslandPopupColorMode(context: Context): Flow<Int> =
        callbackFlow {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(context.contentResolver.readDynamicIslandPopupColorMode())
                    }
                }
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(POPUP_COLOR_MODE),
                false,
                observer,
                UserHandle.USER_ALL,
            )
            trySend(context.contentResolver.readDynamicIslandPopupColorMode())
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }

    fun ContentResolver.readDynamicIslandFeatureEnabled(
        key: String,
        defaultValue: Boolean = true,
    ): Boolean {
        return Settings.System.getIntForUser(
            this,
            key,
            if (defaultValue) 1 else 0,
            UserHandle.USER_CURRENT,
        ) != 0
    }

    fun ContentResolver.readDynamicIslandScale(key: String): Float {
        val percent = Settings.System.getIntForUser(
            this,
            key,
            SCALE_PERCENT_DEFAULT,
            UserHandle.USER_CURRENT,
        )
        return (percent / 100f).coerceIn(SCALE_MIN, SCALE_MAX)
    }

    fun observeDynamicIslandFeatureEnabled(
        context: Context,
        key: String,
        defaultValue: Boolean = true,
    ): Flow<Boolean> =
        callbackFlow {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(
                            context.contentResolver.readDynamicIslandFeatureEnabled(
                                key,
                                defaultValue,
                            )
                        )
                    }
                }

            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key),
                false,
                observer,
                UserHandle.USER_ALL,
            )
            trySend(context.contentResolver.readDynamicIslandFeatureEnabled(key, defaultValue))
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }

    fun observeDynamicIslandScale(
        context: Context,
        key: String,
    ): Flow<Float> =
        callbackFlow {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(context.contentResolver.readDynamicIslandScale(key))
                    }
                }

            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key),
                false,
                observer,
                UserHandle.USER_ALL,
            )
            trySend(context.contentResolver.readDynamicIslandScale(key))
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }   
}
