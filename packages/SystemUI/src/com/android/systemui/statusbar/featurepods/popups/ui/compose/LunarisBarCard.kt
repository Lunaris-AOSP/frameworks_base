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

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.ui.compose.Icon as StatusBarIcon

@Composable
internal fun LunarisBarCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun LunarisBarCardHeader(
    title: String,
    subtitle: String? = null,
    icon: IconModel? = null,
    glyph: ImageVector? = null,
    appName: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    val settings = LocalLunarisBarState.current.settings
    val colors = LocalLunarisBarColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp)
                .clip(lunarisBarIconShape(settings.integer("card_icon_shape", 2, 0, 31)))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                StatusBarIcon(
                    icon,
                    tint = if (icon is IconModel.Loaded) Color.Unspecified else colors.accent,
                    modifier = if (icon is IconModel.Loaded) Modifier.fillMaxSize() else Modifier.size(24.dp),
                )
            } else if (glyph != null) {
                Icon(glyph, null, Modifier.size(24.dp), tint = colors.accent)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!appName.isNullOrBlank() && appName != title) {
                Text(appName, color = colors.accent, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = colors.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = colors.content.copy(alpha = 0.72f), maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

@Composable
internal fun LunarisBarCardValue(value: String, modifier: Modifier = Modifier) {
    val colors = LocalLunarisBarColors.current
    Text(
        value,
        modifier.fillMaxWidth()
            .clip(LunarisBarSquircle(24.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
        color = colors.content,
        fontWeight = FontWeight.Medium,
    )
}

internal fun lunarisBarUtilityGlyph(type: String): ImageVector = when (type) {
    "clock" -> Icons.Default.Schedule
    "timer" -> Icons.Default.Timer
    "battery" -> Icons.Default.BatteryFull
    "brightness" -> Icons.Default.Brightness6
    "volume" -> Icons.Default.VolumeUp
    "flashlight" -> Icons.Default.FlashlightOn
    "barcode" -> Icons.Default.QrCode
    "widget" -> Icons.Default.Widgets
    else -> Icons.Default.Apps
}
