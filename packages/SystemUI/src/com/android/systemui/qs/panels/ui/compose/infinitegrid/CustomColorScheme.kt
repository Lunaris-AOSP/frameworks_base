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
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.qs.composefragment.LocalBlurEnabled

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
            return if (useAlternateColor) surfaceEffect2 else surfaceEffect1
        }

    val surfaceEffect1: Color
        @Composable
        @ReadOnlyComposable
        get() = surfaceColor(com.android.internal.R.color.surface_effect_1)

    val surfaceEffect2: Color
        @Composable
        @ReadOnlyComposable
        get() = surfaceColor(com.android.internal.R.color.surface_effect_2)

    @Composable
    @ReadOnlyComposable
    private fun surfaceColor(blurColor: Int): Color {
        val colorRes =
            if (LocalBlurEnabled.current) blurColor
            else com.android.internal.R.color.materialColorSurfaceBright
        return Color(context.resources.getColor(colorRes, context.theme))
    }

    companion object {
        val current: CustomColorScheme
            @Composable
            @ReadOnlyComposable
            get() = CustomColorScheme(LocalContext.current)
    }
}

