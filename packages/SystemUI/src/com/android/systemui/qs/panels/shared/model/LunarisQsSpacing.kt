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

data class LunarisQsSpacing(
    val tileGap: Int = 20,
    val sidePadding: Int = 24,
    val topPadding: Int = 8,
) {
    companion object {
        val TILE_GAP_RANGE = 8..28
        val SIDE_PADDING_RANGE = 8..40
        val TOP_PADDING_RANGE = 0..32
    }
}
