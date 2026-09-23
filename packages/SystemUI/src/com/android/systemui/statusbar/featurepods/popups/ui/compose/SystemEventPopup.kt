package com.android.systemui.statusbar.featurepods.popups.ui.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.Icon as StatusBarIcon
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.popups.ui.model.BluetoothBatteryComponent
import com.android.systemui.statusbar.featurepods.popups.ui.model.BluetoothBatteryModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.ChargingDetailModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.SystemEventKind
import kotlinx.coroutines.delay

private val EventPopupShape = RoundedCornerShape(32.dp)

/** Added events use the same surface, badge, typography and controls as the original popups. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SystemEventPopup(model: PopupContentModel.SystemEvent, modifier: Modifier = Modifier) {
    val accent = model.kind.accent
    val isMessage = model.kind == SystemEventKind.Notification || model.kind == SystemEventKind.Ongoing
    val isMedia = model.kind == SystemEventKind.NowPlaying
    val isCharging = model.kind == SystemEventKind.Charging
    val isBluetooth = model.kind == SystemEventKind.Bluetooth
    val compact = isCharging || isBluetooth
    PopupSurface(
        shape = EventPopupShape,
        modifier = modifier.widthIn(min = if (isMessage || isMedia || isCharging) 320.dp else 280.dp,
            max = if (isMessage || isMedia) 400.dp else 360.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .then(if (isCharging) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 20.dp, vertical = if (compact) 16.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        ) {
            when (model.kind) {
                SystemEventKind.Call -> CallIdentity(model, accent)
                else -> EventHeader(model, accent, showSubtitle = !isMessage && model.kind != SystemEventKind.Clipboard)
            }

            if (isMessage || model.kind == SystemEventKind.Clipboard) {
                if (model.text.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = model.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalContentColor.current.copy(alpha = 0.85f),
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(LocalContentColor.current.copy(alpha = 0.06f))
                                .verticalScroll(rememberScrollState())
                                .padding(14.dp),
                        )
                    }
                }
                model.image?.let { image ->
                    StatusBarIcon(
                        icon = image,
                        tint = Color.Unspecified,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 160.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                }
            }

            if (isBluetooth) {
                val earbuds = model.bluetoothBatteries.filter {
                    it.component != BluetoothBatteryComponent.Case
                }
                if (earbuds.isNotEmpty()) BluetoothEarbudBatteries(earbuds, accent)
            }

            model.prominentText?.let { value -> ProminentEventValue(value, accent) }
            if (model.kind == SystemEventKind.Timer) {
                ProminentEventValue(rememberTimerText(model), accent)
            }
            val timerProgress = rememberTimerProgress(model)
            val progress = timerProgress ?: model.progress
            if (model.indeterminate) {
                LinearWavyProgressIndicator(
                    color = accent,
                    trackColor = accent.copy(alpha = 0.16f),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else progress?.let { value ->
                if (model.kind == SystemEventKind.Notification || model.kind == SystemEventKind.Ongoing) {
                    ProminentEventValue("${(value * 100).toInt()}%", accent)
                }
                LinearWavyProgressIndicator(
                    progress = { value.coerceIn(0f, 1f) },
                    color = accent,
                    trackColor = accent.copy(alpha = 0.16f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (isCharging && model.chargingDetails.isNotEmpty()) {
                ChargingDetailsGrid(model.chargingDetails, accent)
            }

            if (model.actions.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    model.actions.forEach { action ->
                        PopupActionChips(
                            actions = listOf(if (model.actions.size == 1) action.copy(emphasized = true) else action),
                            accent = accent,
                            modifier = Modifier.widthIn(max = 320.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BluetoothEarbudBatteries(batteries: List<BluetoothBatteryModel>, accent: Color) {
    val columns = if (LocalDensity.current.fontScale > 1.3f) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        batteries.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { battery ->
                    Row(
                        modifier = Modifier.weight(1f)
                            .heightIn(min = 56.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .background(LocalContentColor.current.copy(alpha = 0.05f))
                            .semantics(mergeDescendants = true) {}
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.dynamic_island_earbud),
                            contentDescription = null,
                            tint = LocalContentColor.current.copy(alpha = 0.85f),
                            modifier = Modifier.size(24.dp)
                                .graphicsLayer {
                                    scaleX =
                                        if (battery.component == BluetoothBatteryComponent.RightEarbud) -1f
                                        else 1f
                                },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = battery.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalContentColor.current.copy(alpha = 0.7f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${battery.level}%",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontFeatureSettings = "tnum",
                                ),
                                fontWeight = FontWeight.SemiBold,
                                color = accent,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChargingDetailsGrid(details: List<ChargingDetailModel>, accent: Color) {
    val columns = if (LocalDensity.current.fontScale > 1.3f) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        details.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { detail ->
                    Column(
                        modifier = Modifier.weight(1f)
                            .heightIn(min = 56.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.06f))
                            .semantics(mergeDescendants = true) {}
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = detail.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current.copy(alpha = 0.7f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = detail.value,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFeatureSettings = "tnum",
                                ),
                                fontWeight = FontWeight.Medium,
                                color = LocalContentColor.current,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                            )
                            Text(
                                text = detail.unit,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalContentColor.current.copy(alpha = 0.7f),
                                maxLines = 1,
                                modifier = Modifier.alignByBaseline(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventHeader(model: PopupContentModel.SystemEvent, accent: Color, showSubtitle: Boolean) {
    val artwork = model.kind == SystemEventKind.NowPlaying
    val isBluetooth = model.kind == SystemEventKind.Bluetooth
    val caseBattery = model.bluetoothBatteries.firstOrNull {
        it.component == BluetoothBatteryComponent.Case
    }.takeIf { isBluetooth }
    Row(horizontalArrangement = Arrangement.spacedBy(if (isBluetooth) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (isBluetooth && model.image != null) {
            StatusBarIcon(icon = model.image, tint = Color.Unspecified,
                modifier = Modifier.size(56.dp))
        } else {
            EventGlyph(model, accent, size = if (artwork) 52.dp else 44.dp, artwork = artwork)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            model.appName?.takeIf { it.isNotBlank() }?.let { app ->
                Text(app, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(model.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (showSubtitle && model.text.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (model.pulse) {
                        Box(Modifier.size(6.dp).background(accent, CircleShape))
                    }
                    Text(model.text, style = if (isBluetooth) MaterialTheme.typography.bodySmall
                            else MaterialTheme.typography.bodyMedium,
                        color = if (model.pulse) accent else LocalContentColor.current.copy(alpha = 0.73f),
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            caseBattery?.let { battery ->
                Row(
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.dynamic_island_earbuds_case),
                        contentDescription = null,
                        tint = LocalContentColor.current.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = battery.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = "${battery.level}%",
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CallIdentity(model: PopupContentModel.SystemEvent, accent: Color) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EventGlyph(model, accent, size = 64.dp)
        model.callStartTimeMs?.let { start ->
            Text(rememberCallDurationText(start), style = MaterialTheme.typography.titleLarge,
                color = accent, textAlign = TextAlign.Center)
        }
        Text(model.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (model.text.isNotBlank()) {
            Text(model.text, style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.73f), textAlign = TextAlign.Center,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EventGlyph(model: PopupContentModel.SystemEvent, accent: Color, size: Dp, artwork: Boolean = false) {
    if (model.icon != null && (artwork || model.kind == SystemEventKind.Call ||
            model.kind == SystemEventKind.Notification || model.kind == SystemEventKind.Ongoing ||
            model.kind == SystemEventKind.RecentApps)) {
        Box(modifier = Modifier.size(size).clip(if (artwork) RoundedCornerShape(16.dp) else CircleShape)
            .background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            StatusBarIcon(icon = model.icon, tint = Color.Unspecified, modifier = Modifier.size(size))
        }
    } else {
        IslandGlyphBadge(accent = accent, size = size) {
            val pulseScale = if (model.pulse) rememberEventPulse() else 1f
            if (model.kind == SystemEventKind.Recording && model.pulse) {
                Box(Modifier.size(16.dp).graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }
                    .background(accent, CircleShape))
            } else if ((model.kind == SystemEventKind.Ringer || model.kind == SystemEventKind.Bluetooth) &&
                model.icon != null) {
                StatusBarIcon(icon = model.icon,
                    tint = if (model.kind == SystemEventKind.Bluetooth) Color.Unspecified else accent,
                    modifier = Modifier.size(if (size > 52.dp) 30.dp else 22.dp))
            } else {
                Icon(imageVector = model.kind.glyph, contentDescription = null, tint = accent,
                    modifier = Modifier.size(if (size > 52.dp) 30.dp else 22.dp)
                        .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale })
            }
        }
    }
}

@Composable
private fun rememberEventPulse(): Float {
    val transition = rememberInfiniteTransition(label = "event_pulse")
    val scale by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "event_scale",
    )
    return scale
}

@Composable
private fun ProminentEventValue(value: String, accent: Color) {
    Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = accent)
}

private val SystemEventKind.accent: Color
    get() = when (this) {
        SystemEventKind.Charging, SystemEventKind.Unlock -> IslandAccents.Battery
        SystemEventKind.Call -> IslandAccents.Calls
        SystemEventKind.Bluetooth, SystemEventKind.Hotspot, SystemEventKind.Vpn -> IslandAccents.Connectivity
        SystemEventKind.Notification, SystemEventKind.Ongoing -> IslandAccents.Notifications
        SystemEventKind.Timer, SystemEventKind.Ringer -> IslandAccents.Timer
        SystemEventKind.Recording -> IslandAccents.Recording
        SystemEventKind.NowPlaying -> IslandAccents.Music
        SystemEventKind.Clipboard, SystemEventKind.RecentApps -> IslandAccents.System
    }

private val SystemEventKind.glyph: ImageVector
    get() = when (this) {
        SystemEventKind.Charging -> Icons.Filled.BatteryChargingFull
        SystemEventKind.Unlock -> Icons.Filled.LockOpen
        SystemEventKind.Bluetooth -> Icons.Filled.Bluetooth
        SystemEventKind.Hotspot -> Icons.Filled.WifiTethering
        SystemEventKind.Vpn -> Icons.Filled.VpnLock
        SystemEventKind.Ringer -> Icons.Filled.VolumeUp
        SystemEventKind.Clipboard -> Icons.Filled.ContentPaste
        SystemEventKind.Call -> Icons.Filled.Call
        SystemEventKind.Notification, SystemEventKind.Ongoing -> Icons.Filled.Notifications
        SystemEventKind.Timer -> Icons.Filled.Timer
        SystemEventKind.Recording -> Icons.Filled.Mic
        SystemEventKind.NowPlaying -> Icons.Filled.MusicNote
        SystemEventKind.RecentApps -> Icons.Filled.Apps
    }
