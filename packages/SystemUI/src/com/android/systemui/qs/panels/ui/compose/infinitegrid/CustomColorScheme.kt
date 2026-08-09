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
package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

class CustomColorScheme(private val context: Context) {
    val qsTileColor: Color
        @Composable
        get() {
            val useAlternateColor =
                rememberObservedSetting(Settings.System.QS_TILE_ALTERNATE_COLOR, false) { ctx ->
                    Settings.System.getIntForUser(
                        ctx.contentResolver,
                        Settings.System.QS_TILE_ALTERNATE_COLOR,
                        0,
                        UserHandle.USER_CURRENT
                    ) == 1
                }
            val disableWindowBlurs = rememberObservedGlobalSetting(
                key = Settings.Global.DISABLE_WINDOW_BLURS,
                default = !blurEnabledByDefault,
            ) { raw -> raw == 1 }
            val blurEnabled = !disableWindowBlurs

            val colorRes = if (blurEnabled) {
                if (useAlternateColor)
                    com.android.internal.R.color.surface_effect_2
                else
                    com.android.internal.R.color.surface_effect_1
            } else {
                com.android.internal.R.color.materialColorSurfaceBright
            }

            return Color(context.resources.getColor(colorRes, context.theme))
        }

    companion object {
        private val blurEnabledByDefault: Boolean by lazy {
            SystemProperties.getBoolean("ro.custom.blur.enable", false)
        }

        val current: CustomColorScheme
            @Composable
            @ReadOnlyComposable
            get() = CustomColorScheme(LocalContext.current)
    }
}

