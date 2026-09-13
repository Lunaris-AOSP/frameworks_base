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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel

@Composable
internal fun rememberChipText(model: PopupChipModel.Shown): String? {
    val start = model.callStartTimeMs ?: return model.chipText
    return rememberCallDurationText(start)
}

@Composable
internal fun rememberCallDurationText(start: Long): String {
    val base = remember(start) {
        android.os.SystemClock.elapsedRealtime() -
            (System.currentTimeMillis() - start).coerceAtLeast(0L)
    }
    return rememberElapsedDurationText(base)
}
