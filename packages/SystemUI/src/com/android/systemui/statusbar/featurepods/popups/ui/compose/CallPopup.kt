/*
 * Copyright (C) 2026 The Android Open Source Project
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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupActionRole
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel

@Composable
internal fun CallPopup(model: PopupContentModel.SystemEvent, modifier: Modifier = Modifier) {
    val status = model.prominentText ?: stringResource(R.string.dynamic_island_event_call)
    LunarisBarCard(modifier) {
        LunarisBarCardHeader(
            title = model.title,
            subtitle = model.text.takeIf { it.isNotBlank() && it != model.title } ?: status,
            icon = model.icon,
            glyph = Icons.Default.Person,
            appName = model.appName,
        )
        LunarisBarCardValue(model.callStartTimeMs?.let { rememberCallDurationText(it) } ?: status)
        PopupActionChips(
            model.actions.sortedBy {
                when (it.role) {
                    PopupActionRole.EndCall -> 0
                    PopupActionRole.AnswerCall -> 1
                    else -> 2
                }
            },
            LocalLunarisBarColors.current.accent,
        )
    }
}
