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

package com.android.systemui.statusbar.featurepods.flashlight.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.ui.compose.Icon as StatusBarIcon
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.flashlight.shared.model.FlashlightPopupModel
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.featurepods.popups.ui.compose.IslandAccents
import com.android.systemui.statusbar.featurepods.popups.ui.compose.IslandGlyphBadge
import com.android.systemui.statusbar.featurepods.popups.ui.compose.PopupActionChips
import com.android.systemui.statusbar.featurepods.popups.ui.compose.PopupSurface

private val PopupShape = RoundedCornerShape(32.dp)

/** Expanded flashlight card surfaced in the dynamic island. */
@Composable
fun FlashlightPopup(
    model: FlashlightPopupModel,
    modifier: Modifier = Modifier,
) {
    val accent = IslandAccents.Flashlight
    var sliderLevel by remember(model.level) {
        mutableFloatStateOf(model.level?.toFloat() ?: 0f)
    }
    PopupSurface(
        shape = PopupShape,
        modifier = modifier.widthIn(min = 280.dp, max = 340.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IslandGlyphBadge(accent = accent) {
                    StatusBarIcon(
                        icon =
                            Icon.Resource(
                                resId = R.drawable.ic_dynamic_island_flashlight,
                                contentDescription =
                                    ContentDescription.Resource(
                                        R.string.quick_settings_flashlight_label
                                    ),
                            ),
                        modifier = Modifier.size(22.dp),
                        tint = accent,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stringResource(R.string.quick_settings_flashlight_label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.size(6.dp).background(accent, CircleShape))
                        Text(
                            text = stringResource(R.string.dynamic_island_flashlight_enabled),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                        )
                    }
                }
            }

            Text(
                text =
                    model.levelPercent?.let {
                        stringResource(
                            R.string.quick_settings_flashlight_tile_level_percentage,
                            it,
                        )
                    } ?: stringResource(R.string.dynamic_island_flashlight_short),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = accent,
            )

            if (model.level != null && model.maxLevel != null && model.maxLevel > 0) {
                Slider(
                    value = sliderLevel,
                    onValueChange = {
                        sliderLevel = it
                        model.setLevelTemporary(it.toInt())
                    },
                    onValueChangeFinished = {
                        model.setLevel(sliderLevel.toInt())
                    },
                    valueRange = 1f..model.maxLevel.toFloat(),
                )
            }

            PopupActionChips(
                actions =
                    listOf(
                        PopupActionModel(
                            label = stringResource(R.string.flashlight_dialog_turn_off),
                            onClick = model.turnOff,
                            emphasized = true,
                        )
                    ),
                accent = accent,
            )
        }
    }
}
