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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupActionRole

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PopupActionChips(
    actions: List<PopupActionModel>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val settings = LocalLunarisBarState.current.settings
    val scheme = MaterialTheme.colorScheme
    val view = LocalView.current
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEach { action ->
            val background =
                when {
                    action.role == PopupActionRole.EndCall -> scheme.error
                    action.role == PopupActionRole.AnswerCall || action.emphasized -> accent
                    else -> scheme.secondaryContainer
                }
            val foreground =
                when {
                    action.role == PopupActionRole.EndCall -> scheme.onError
                    action.role == PopupActionRole.AnswerCall || action.emphasized ->
                        if (background.luminance() > 0.45f) Color.Black else Color.White
                    else -> scheme.onSecondaryContainer
                }
            Button(
                onClick = {
                    if (settings.isHapticsEnabled)
                        view.performHapticFeedback(
                            android.view.HapticFeedbackConstants.CONTEXT_CLICK
                        )
                    action.onClick()
                },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = lunarisBarButtonShape(settings.integer("card_button_shape", 20, 0, 31)),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = background,
                        contentColor = foreground,
                    ),
            ) {
                if (
                    action.role == PopupActionRole.AnswerCall ||
                        action.role == PopupActionRole.EndCall
                ) {
                    Icon(
                        if (action.role == PopupActionRole.AnswerCall) Icons.Default.Call
                        else Icons.Default.CallEnd,
                        contentDescription = null,
                    )
                }
                Text(
                    action.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
