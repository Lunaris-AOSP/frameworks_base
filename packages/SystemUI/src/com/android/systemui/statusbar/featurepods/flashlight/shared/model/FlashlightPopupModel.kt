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

package com.android.systemui.statusbar.featurepods.flashlight.shared.model

/** Popup content for the flashlight page in the dynamic island. */
data class FlashlightPopupModel(
    val level: Int? = null,
    val maxLevel: Int? = null,
    val turnOff: () -> Unit,
    val setLevelTemporary: (Int) -> Unit = {},
    val setLevel: (Int) -> Unit = {},
) {
    val strengthPercent: Int?
        get() {
            val maximum = maxLevel?.takeIf { it > 1 } ?: return null
            val current = level ?: return null
            return kotlin.math.round(current.coerceIn(0, maximum) * 100f / maximum).toInt()
        }
}
