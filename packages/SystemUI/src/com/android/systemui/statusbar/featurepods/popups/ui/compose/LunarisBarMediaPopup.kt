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

import android.os.SystemClock
import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.media.controls.shared.MediaSeekBarStyle
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.ui.composable.StyledMediaSeekBar
import com.android.systemui.qs.composefragment.cosStatusGlow
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.media.shared.model.MediaControlChipModel
import kotlin.math.sin
import kotlinx.coroutines.delay

@Composable
internal fun LunarisBarMediaPopup(model: MediaControlChipModel, seekBarStyle: MediaSeekBarStyle) {
    val settings = LocalLunarisBarState.current.settings
    val colors = LocalLunarisBarColors.current
    val artwork = (model.artworkIcon as? IconModel.Loaded)?.drawable
    var scrubbing by remember(model.packageName, model.songName) { mutableStateOf(false) }
    var position by
        remember(model.packageName, model.songName) { mutableLongStateOf(model.positionMs) }
    var showSlider by
        remember(settings.isSliderVisible) { mutableStateOf(settings.isSliderVisible) }
    LaunchedEffect(
        model.packageName,
        model.songName,
        model.positionMs,
        model.durationMs,
        model.isPlaying,
        model.playbackSpeed,
        scrubbing,
    ) {
        if (scrubbing) return@LaunchedEffect
        val start = SystemClock.elapsedRealtime()
        do {
            position =
                (model.positionMs +
                        if (model.isPlaying)
                            ((SystemClock.elapsedRealtime() - start) * model.playbackSpeed).toLong()
                        else 0L)
                    .coerceIn(0L, model.durationMs.coerceAtLeast(0L))
            if (!model.isPlaying) break
            delay(200)
        } while (true)
    }
    var wavePhase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(settings.mediaBackground, settings.isReducedMotion, model.isPlaying) {
        if (settings.mediaBackground != 3 || settings.isReducedMotion || !model.isPlaying)
            return@LaunchedEffect
        var previous = 0L
        while (true) withFrameNanos { now ->
            if (previous != 0L)
                wavePhase = (wavePhase + (now - previous) / 1e9f * 0.7f) % (2f * Math.PI.toFloat())
            previous = now
        }
    }
    Box(
        Modifier.widthIn(max = 420.dp)
            .fillMaxWidth()
            .cosStatusGlow(
                trigger =
                    Triple(
                        model.packageName,
                        model.songName?.toString(),
                        model.artistName?.toString(),
                    ),
                cornerRadius =
                    if (settings.expandedShape == 0) 48.dp else settings.cornerRadius.dp,
                enabled = settings.enabled("status_glow", false) && !settings.isReducedMotion,
            )
    ) {
        when (settings.mediaBackground) {
            1,
            2 ->
                if (artwork != null) {
                    AndroidView(
                        factory = {
                            ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                        },
                        update = { it.setImageDrawable(artwork) },
                        modifier =
                            Modifier.matchParentSize()
                                .then(
                                    if (settings.mediaBackground == 2) Modifier.blur(24.dp)
                                    else Modifier
                                ),
                    )
                    Box(
                        Modifier.matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        colors.surface.copy(alpha = 0.92f),
                                        colors.surface.copy(alpha = 0.55f),
                                    )
                                )
                            )
                    )
                }
            3,
            4 -> {
                val primary = MaterialTheme.colorScheme.primaryContainer
                val secondary = MaterialTheme.colorScheme.tertiaryContainer
                Canvas(Modifier.matchParentSize()) {
                    drawRect(Brush.linearGradient(listOf(primary, secondary)))
                    if (settings.mediaBackground == 3) {
                        for (layer in 0..2) {
                            val path =
                                Path().apply {
                                    moveTo(0f, size.height)
                                    var x = 0f
                                    while (x <= size.width) {
                                        lineTo(
                                            x,
                                            size.height * (0.5f + layer * 0.12f) +
                                                sin(
                                                    x / size.width.coerceAtLeast(1f) * 7f +
                                                        layer +
                                                        wavePhase
                                                ) * size.height * 0.12f,
                                        )
                                        x += 3.dp.toPx()
                                    }
                                    lineTo(size.width, size.height)
                                    close()
                                }
                            drawPath(path, colors.accent.copy(alpha = 0.08f + layer * 0.04f))
                        }
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                (model.artworkIcon ?: model.appIcon)?.let {
                    Icon(
                        it,
                        modifier =
                            Modifier.size(56.dp)
                                .clip(lunarisBarIconShape(settings.artworkShape))
                                .combinedClickable(
                                    onClick = { model.openApp?.invoke() },
                                    onLongClick = { showSlider = !showSlider },
                                ),
                        tint = Color.Unspecified,
                    )
                }
                Column(
                    Modifier.weight(1f)
                        .combinedClickable(
                            onClick = { model.openApp?.invoke() },
                            onLongClick = { showSlider = !showSlider },
                        )
                ) {
                    Text(
                        model.songName?.toString().orEmpty(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!model.artistName.isNullOrEmpty()) {
                        Text(
                            model.artistName.toString(),
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.content.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (settings.playerIcon == 0) {
                    model.appIcon?.let {
                        Icon(
                            it,
                            modifier =
                                Modifier.size(32.dp)
                                    .clip(lunarisBarIconShape(4))
                                    .combinedClickable(onClick = { model.openApp?.invoke() }),
                            tint = Color.Unspecified,
                        )
                    }
                }
            }
            if (settings.playerIcon == 1 && !model.appName.isNullOrEmpty()) {
                Text(
                    model.appName,
                    modifier =
                        Modifier.align(Alignment.CenterHorizontally)
                            .clip(lunarisBarIconShape(20))
                            .background(colors.content.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.content.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
            AnimatedVisibility(showSlider && model.durationMs > 0L) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        android.text.format.DateUtils.formatElapsedTime(position / 1000),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.content.copy(alpha = 0.6f),
                    )
                    StyledMediaSeekBar(
                        style = seekBarStyle,
                        contentColor = colors.content.toArgb(),
                        artwork = artwork,
                        positionMs = position,
                        durationMs = model.durationMs,
                        isPlaying = model.isPlaying,
                        isScrubbing = scrubbing,
                        interactive = model.canBeScrubbed,
                        onScrubStart = { scrubbing = true },
                        onScrubStop = { scrubbing = false },
                        onSeek = {
                            position = it
                            model.seekTo?.invoke(it)
                        },
                        modifier = Modifier.weight(1f).height(34.dp),
                    )
                    Text(
                        android.text.format.DateUtils.formatElapsedTime(model.durationMs / 1000),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.content.copy(alpha = 0.6f),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LunarisBarMediaButton(
                    action = model.customActions.firstOrNull(),
                    fallbackIcon = Icons.Outlined.FavoriteBorder,
                    fallbackDescription = stringResource(R.string.lunaris_bar_media_favorite),
                )
                LunarisBarMediaButton(model.transportActions?.prevOrCustom ?: model.previousAction)
                LunarisBarMediaButton(
                    model.transportActions?.playOrPause ?: model.playOrPause,
                    primary = true,
                )
                LunarisBarMediaButton(model.transportActions?.nextOrCustom ?: model.nextAction)
                LunarisBarMediaButton(
                    action = null,
                    fallbackIcon = Icons.Outlined.Sensors,
                    fallbackDescription = stringResource(R.string.lunaris_bar_media_output),
                    onClick = model.openOutput,
                )
            }
        }
    }
}

@Composable
private fun LunarisBarMediaButton(
    action: MediaAction?,
    primary: Boolean = false,
    fallbackIcon: ImageVector? = null,
    fallbackDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val settings = LocalLunarisBarState.current.settings
    val colors = LocalLunarisBarColors.current
    val view = LocalView.current
    val enabled = onClick != null || action?.action != null
    Surface(
        modifier = Modifier.size(if (primary) 56.dp else 48.dp),
        shape = lunarisBarIconShape(settings.integer("media_button_shape", 2, 0, 31)),
        color = if (primary) colors.content.copy(alpha = 0.12f) else Color.Transparent,
        border = if (primary) BorderStroke(0.5.dp, colors.content.copy(alpha = 0.25f)) else null,
    ) {
        IconButton(
            onClick = {
                if (settings.isHapticsEnabled)
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                if (onClick != null) onClick() else action?.action?.run()
            },
            enabled = enabled,
            modifier = Modifier.fillMaxSize(),
        ) {
            val tint = colors.content.copy(alpha = if (enabled) 1f else 0.35f)
            val icon = action?.icon
            if (icon != null) {
                Icon(
                    IconModel.Loaded(
                        icon,
                        action.contentDescription?.toString()?.let(ContentDescription::Loaded),
                    ),
                    modifier = Modifier.size(if (primary) 30.dp else 26.dp),
                    tint = tint,
                )
            } else if (fallbackIcon != null) {
                androidx.compose.material3.Icon(
                    fallbackIcon,
                    contentDescription = fallbackDescription,
                    modifier = Modifier.size(26.dp),
                    tint = tint,
                )
            } else if (!action?.contentDescription.isNullOrEmpty()) {
                Text(
                    action?.contentDescription.toString(),
                    maxLines = 1,
                    color = tint,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
