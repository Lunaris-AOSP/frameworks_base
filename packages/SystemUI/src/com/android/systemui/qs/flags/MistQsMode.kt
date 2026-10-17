/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.flags

import android.content.Context
import android.os.UserHandle
import android.provider.Settings

/** Process configuration. Changing the design requires recreating SystemUI's dependency graph. */
object MistQsMode {
    const val SETTING = "qs_mist_enabled"
    private const val QS_HEADER_CLOCK_STYLE = "qs_header_clock_style"
    private const val STOCK_CLOCK_STYLE = 0
    private const val OOS_CLOCK_STYLE = 2

    @get:JvmStatic
    var isEnabled: Boolean = false
        private set

    @JvmStatic
    fun initialize(context: Context) {
        val userId = UserHandle.USER_CURRENT
        isEnabled = readEnabled(context, userId)
        syncClockStyle(context, userId)
    }

    @JvmStatic
    fun readEnabled(context: Context, userId: Int): Boolean =
        Settings.System.getIntForUser(context.contentResolver, SETTING, 0, userId) == 1

    private fun syncClockStyle(context: Context, userId: Int) {
        val desiredStyle = if (isEnabled) OOS_CLOCK_STYLE else STOCK_CLOCK_STYLE
        val currentStyle =
            Settings.System.getIntForUser(
                context.contentResolver,
                QS_HEADER_CLOCK_STYLE,
                STOCK_CLOCK_STYLE,
                userId,
            )
        if (currentStyle != desiredStyle) {
            Settings.System.putIntForUser(
                context.contentResolver,
                QS_HEADER_CLOCK_STYLE,
                desiredStyle,
                userId,
            )
        }
    }
}
