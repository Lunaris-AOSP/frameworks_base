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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.ui.compose.Icon as StatusBarIcon
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.popups.ui.model.BluetoothBatteryComponent
import com.android.systemui.statusbar.featurepods.popups.ui.model.BluetoothBatteryModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.ChargingDetailModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.SystemEventKind

@Composable
fun SystemEventPopup(model: PopupContentModel.SystemEvent, modifier: Modifier = Modifier) {
    if (model.kind == SystemEventKind.Call) {
        CallPopup(model, modifier)
        return
    }
    val colors = LocalLunarisBarColors.current
    val settings = LocalLunarisBarState.current.settings
    val message =
        model.kind == SystemEventKind.Notification || model.kind == SystemEventKind.Ongoing
    val bluetooth = model.kind == SystemEventKind.Bluetooth
    val showBattery = !bluetooth || settings.enabled("feature_bluetooth_show_battery", true)
    LunarisBarCard(modifier) {
        LunarisBarCardHeader(
            title = model.title,
            subtitle = model.text.takeUnless { message },
            icon = model.icon.takeUnless { bluetooth && model.image != null },
            glyph = model.kind.glyph,
            appName = model.appName,
        )
        if (bluetooth) {
            val artwork = (model.image as? IconModel.Loaded)?.drawable
            if (artwork != null) {
                Image(
                    painter = rememberDrawablePainter(artwork),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(128.dp)
                            .clip(LunarisBarSquircle(24.dp))
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                            )
                            .padding(12.dp),
                )
            }
        }
        if (message) {
            if (model.text.isNotBlank()) {
                SelectionContainer {
                    Text(
                        model.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.content,
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .clip(LunarisBarSquircle(24.dp))
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                )
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                    )
                }
            }
            model.image?.let {
                StatusBarIcon(
                    it,
                    tint = Color.Unspecified,
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 100.dp, max = 180.dp)
                            .clip(LunarisBarSquircle(24.dp)),
                )
            }
        }
        if (bluetooth && showBattery && model.bluetoothBatteries.isNotEmpty()) {
            LunarisBarBluetoothBatteries(model.bluetoothBatteries)
        }
        if (showBattery) model.prominentText?.let { LunarisBarCardValue(it) }
        if (model.kind == SystemEventKind.Timer) LunarisBarCardValue(rememberTimerText(model))
        val progress = rememberTimerProgress(model) ?: model.progress
        if (model.indeterminate || (progress != null && showBattery)) {
            LunarisBarEventProgress(progress, model.indeterminate)
        }
        if (model.kind == SystemEventKind.Charging && model.chargingDetails.isNotEmpty()) {
            LunarisBarChargingDetails(model.chargingDetails)
        }
        PopupActionChips(model.actions, colors.accent)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LunarisBarEventProgress(progress: Float?, indeterminate: Boolean = false) {
    val settings = LocalLunarisBarState.current.settings
    val colors = LocalLunarisBarColors.current
    val foreground =
        rememberLunarisBarPalette(
                settings.integer("progress_palette", 5, 0, 20),
                lunarisBarPartColors("progress", colors.accent to colors.content),
            )
            .first
    val track =
        rememberLunarisBarPalette(
                settings.integer("track_palette", 2, 0, 20),
                lunarisBarPartColors("track", colors.accent.copy(alpha = 0.16f) to colors.content),
            )
            .first
    if (settings.isReducedMotion) {
        if (indeterminate)
            LinearProgressIndicator(
                progress = { 0.5f },
                modifier = Modifier.fillMaxWidth(),
                color = foreground,
                trackColor = track,
            )
        else
            LinearProgressIndicator(
                progress = { progress?.coerceIn(0f, 1f) ?: 0f },
                modifier = Modifier.fillMaxWidth(),
                color = foreground,
                trackColor = track,
            )
    } else if (indeterminate) {
        LinearWavyProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = foreground,
            trackColor = track,
        )
    } else {
        LinearWavyProgressIndicator(
            progress = { progress?.coerceIn(0f, 1f) ?: 0f },
            modifier = Modifier.fillMaxWidth(),
            color = foreground,
            trackColor = track,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LunarisBarBluetoothBatteries(batteries: List<BluetoothBatteryModel>) {
    val colors = LocalLunarisBarColors.current
    val settings = LocalLunarisBarState.current.settings
    val progress =
        rememberLunarisBarPalette(
                settings.integer("progress_palette", 5, 0, 20),
                lunarisBarPartColors("progress", colors.accent to colors.content),
            )
            .first
    val track =
        rememberLunarisBarPalette(
                settings.integer("track_palette", 2, 0, 20),
                lunarisBarPartColors("track", colors.accent.copy(alpha = 0.16f) to colors.content),
            )
            .first
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.3f) 2 else 3,
    ) {
        batteries.forEach { battery ->
            Column(
                Modifier.weight(1f)
                    .clip(LunarisBarSquircle(24.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                    .semantics(mergeDescendants = true) {}
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { battery.level / 100f },
                        modifier = Modifier.size(44.dp),
                        color = progress,
                        trackColor = track,
                        strokeWidth = 3.dp,
                    )
                    Icon(
                        painterResource(
                            if (battery.component == BluetoothBatteryComponent.Case)
                                R.drawable.dynamic_island_earbuds_case
                            else R.drawable.dynamic_island_earbud
                        ),
                        null,
                        tint = colors.content,
                        modifier =
                            Modifier.size(22.dp).graphicsLayer {
                                scaleX =
                                    if (battery.component == BluetoothBatteryComponent.RightEarbud)
                                        -1f
                                    else 1f
                            },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (battery.isCharging) {
                        Icon(
                            Icons.Filled.Bolt,
                            contentDescription = stringResource(R.string.lunaris_bar_charging),
                            tint = colors.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        "${battery.level}%",
                        style =
                            MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = colors.content,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    battery.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.content.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun LunarisBarChargingDetails(details: List<ChargingDetailModel>) {
    val colors = LocalLunarisBarColors.current
    val columns = if (LocalDensity.current.fontScale > 1.3f) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        details.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { detail ->
                    Column(
                        Modifier.weight(1f)
                            .clip(LunarisBarSquircle(24.dp))
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                            )
                            .semantics(mergeDescendants = true) {}
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            detail.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.content.copy(alpha = 0.72f),
                        )
                        Text(
                            "${detail.value} ${detail.unit}",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.content,
                        )
                    }
                }
            }
        }
    }
}

private val SystemEventKind.glyph: ImageVector
    get() =
        when (this) {
            SystemEventKind.Charging -> Icons.Filled.BatteryChargingFull
            SystemEventKind.Unlock -> Icons.Filled.LockOpen
            SystemEventKind.Bluetooth -> Icons.Filled.Bluetooth
            SystemEventKind.Hotspot -> Icons.Filled.WifiTethering
            SystemEventKind.Vpn -> Icons.Filled.VpnLock
            SystemEventKind.Ringer -> Icons.Filled.VolumeUp
            SystemEventKind.Call -> Icons.Filled.Call
            SystemEventKind.Notification,
            SystemEventKind.Ongoing -> Icons.Filled.Notifications
            SystemEventKind.Timer -> Icons.Filled.Timer
            SystemEventKind.Recording -> Icons.Filled.Mic
            SystemEventKind.NowPlaying -> Icons.Filled.MusicNote
        }
