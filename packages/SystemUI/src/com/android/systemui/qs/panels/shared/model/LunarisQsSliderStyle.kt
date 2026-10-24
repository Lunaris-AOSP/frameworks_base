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

package com.android.systemui.qs.panels.shared.model

enum class LunarisQsSliderStyle(val settingValue: Int) {
    M3_EXPRESSIVE(0),
    PLATFORM(1),
    ROUNDED_SQUARE(2),
    ROUNDED_SQUARE_FLAT(3);

    val usesPlatformSlider: Boolean
        get() = this != M3_EXPRESSIVE

    companion object {
        fun fromSetting(value: Int): LunarisQsSliderStyle =
            entries.firstOrNull { it.settingValue == value } ?: M3_EXPRESSIVE
    }
}

data class LunarisQsSliderKey(val layout: AxQsLayout, val control: AxQsControl)
