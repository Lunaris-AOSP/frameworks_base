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

data class LunarisQsGradientSettings(
    val enabled: Boolean = false,
    val sliderOnly: Boolean = false,
    val colorMode: Int = 0,
    val autoStyle: Int = 0,
    val rotation: Int = 0,
    val startColor: Int = 0,
    val endColor: Int = 0,
)
