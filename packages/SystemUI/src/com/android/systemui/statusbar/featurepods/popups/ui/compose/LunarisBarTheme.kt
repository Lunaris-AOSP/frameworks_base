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

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.android.internal.util.lunaris.LunarisBarSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class LunarisBarState(
    val settings: LunarisBarSettings = LunarisBarSettings(),
    val alternativeAnimation: Boolean = true,
    val alternativeMedia: Boolean = true,
    val alternativeFlashlight: Boolean = true,
    val userId: Int = UserHandle.USER_SYSTEM,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
)

internal data class LunarisBarColors(
    val surface: Color = Color.Black,
    val content: Color = Color.White,
    val miniSurface: Color = surface,
    val miniContent: Color = content,
    val accent: Color = Color(0xFFD0BCFF),
    val outline: Color = content.copy(alpha = 0.2f),
    val dark: Boolean = true,
)

internal val LocalLunarisBarState = staticCompositionLocalOf { LunarisBarState() }
internal val LocalLunarisBarColors = staticCompositionLocalOf { LunarisBarColors() }

internal fun readLunarisBarState(context: Context): LunarisBarState {
    val resolver = context.contentResolver
    val userId = ActivityManager.getCurrentUser()
    return LunarisBarState(
        settings = LunarisBarSettings.read(resolver, userId),
        alternativeAnimation =
            Settings.System.getIntForUser(
                resolver,
                LunarisBarSettings.ALTERNATIVE_ANIMATION,
                1,
                userId,
            ) != 0,
        alternativeMedia =
            Settings.System.getIntForUser(
                resolver,
                LunarisBarSettings.ALTERNATIVE_MEDIA,
                1,
                userId,
            ) != 0,
        alternativeFlashlight =
            Settings.System.getIntForUser(
                resolver,
                LunarisBarSettings.ALTERNATIVE_FLASHLIGHT,
                1,
                userId,
            ) != 0,
        userId = userId,
        screenWidth = LunarisBarSettings.screenWidth(context),
        screenHeight = LunarisBarSettings.screenHeight(context),
    )
}

internal fun observeLunarisBarState(context: Context) =
    callbackFlow {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(readLunarisBarState(context))
                    }
                }
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        trySend(readLunarisBarState(context))
                    }
                }
            for (key in
                listOf(
                    LunarisBarSettings.KEY,
                    LunarisBarSettings.ALTERNATIVE_ANIMATION,
                    LunarisBarSettings.ALTERNATIVE_MEDIA,
                    LunarisBarSettings.ALTERNATIVE_FLASHLIGHT,
                )) {
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(key),
                    false,
                    observer,
                    UserHandle.USER_ALL,
                )
            }
            context.registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_USER_SWITCHED).apply {
                    addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                },
                Context.RECEIVER_EXPORTED,
            )
            trySend(readLunarisBarState(context))
            awaitClose {
                context.contentResolver.unregisterContentObserver(observer)
                context.unregisterReceiver(receiver)
            }
        }
        .distinctUntilChanged()

@Composable
internal fun LunarisBarTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val state by
        remember(context) { observeLunarisBarState(context) }
            .collectAsState(initial = remember(context) { readLunarisBarState(context) })
    val configuration = LocalConfiguration.current
    val settings = state.settings.forScreen(state.screenWidth, state.screenHeight)
    val dark =
        when (settings.theme) {
            1 -> false
            2 -> true
            else -> isSystemInDarkTheme()
        }
    val scheme =
        remember(context, dark, configuration) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
    val lightScheme = remember(context, configuration) { dynamicLightColorScheme(context) }
    val darkScheme = remember(context, configuration) { dynamicDarkColorScheme(context) }
    fun palette(mini: Boolean): Pair<Color, Color> =
        lunarisBarPalette(
            settings.palette(mini, dark),
            scheme,
            lightScheme,
            darkScheme,
            Color(settings.color(mini, dark, false)) to Color(settings.color(mini, dark, true)),
        )
    val (surface, foreground) = palette(false)
    val (miniSurface, miniForeground) = palette(true)
    val colors =
        LunarisBarColors(
            surface = surface.copy(alpha = settings.opacity(false, dark)),
            content = foreground,
            miniSurface = miniSurface.copy(alpha = settings.opacity(true, dark)),
            miniContent = miniForeground,
            accent = scheme.primary,
            outline = foreground.copy(alpha = if (dark) 0.22f else 0.14f),
            dark = dark,
        )
    CompositionLocalProvider(
        LocalLunarisBarState provides state.copy(settings = settings),
        LocalLunarisBarColors provides colors,
    ) {
        MaterialTheme(
            colorScheme =
                scheme.copy(
                    surface = surface,
                    surfaceContainerHigh = surface,
                    onSurface = foreground,
                    onSurfaceVariant = foreground.copy(alpha = 0.72f),
                ),
            content = content,
        )
    }
}

internal fun lunarisBarPalette(
    index: Int,
    scheme: ColorScheme,
    light: ColorScheme = scheme,
    dark: ColorScheme = scheme,
    custom: Pair<Color, Color> = scheme.surface to scheme.onSurface,
): Pair<Color, Color> =
    when (index) {
        1 -> scheme.primaryContainer to scheme.onPrimaryContainer
        2 -> scheme.secondaryContainer to scheme.onSecondaryContainer
        3 -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        4 -> Color.Black to Color.White
        5 -> scheme.primary to scheme.onPrimary
        6 -> custom
        7 -> light.surfaceBright to light.onSurface
        8 -> dark.surfaceBright to dark.onSurface
        9 -> light.primaryContainer to light.onPrimaryContainer
        10 -> dark.primaryContainer to dark.onPrimaryContainer
        11 -> light.secondaryContainer to light.onSecondaryContainer
        12 -> dark.secondaryContainer to dark.onSecondaryContainer
        13 -> light.tertiaryContainer to light.onTertiaryContainer
        14 -> dark.tertiaryContainer to dark.onTertiaryContainer
        15 -> light.primary to light.onPrimary
        16 -> dark.primary to dark.onPrimary
        17 -> scheme.tertiary to scheme.onTertiary
        18 -> light.tertiary to light.onTertiary
        19 -> dark.tertiary to dark.onTertiary
        20 -> scheme.surfaceContainerHighest to scheme.primary
        else -> scheme.surfaceBright to scheme.onSurface
    }

@Composable
internal fun rememberLunarisBarPalette(index: Int, custom: Pair<Color, Color>): Pair<Color, Color> {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val light = remember(context, configuration) { dynamicLightColorScheme(context) }
    val dark = remember(context, configuration) { dynamicDarkColorScheme(context) }
    return lunarisBarPalette(index, MaterialTheme.colorScheme, light, dark, custom)
}
