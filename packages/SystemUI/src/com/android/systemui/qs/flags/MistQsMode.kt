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

    @get:JvmStatic
    var isEnabled: Boolean = false
        private set

    @JvmStatic
    fun initialize(context: Context) {
        isEnabled = readEnabled(context, UserHandle.USER_CURRENT)
    }

    @JvmStatic
    fun readEnabled(context: Context, userId: Int): Boolean =
        Settings.System.getIntForUser(context.contentResolver, SETTING, 0, userId) == 1
}
