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

package com.android.systemui.statusbar.featurepods.media.ui.compose

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.format.DateUtils
import android.widget.SeekBar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon as UiIcon
import com.android.systemui.common.ui.compose.Icon as UiIconView
import com.android.systemui.media.controls.shared.MediaSeekBarStyle
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.media.controls.ui.composable.StyledMediaSeekBar
import com.android.systemui.media.controls.ui.view.WaveformSeekBar
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.compose.islandPopupRow
import com.android.systemui.statusbar.featurepods.popups.ui.compose.PopupSurface
import com.android.systemui.statusbar.featurepods.popups.ui.compose.pressScale
import com.android.systemui.statusbar.featurepods.popups.ui.compose.rememberAlbumArtAccent
import kotlinx.coroutines.delay

private val PopupShape = RoundedCornerShape(34.dp)
private val ArtworkShape = RoundedCornerShape(16.dp)

/** Expanded media controls for the centered dynamic island. */
@Composable
fun MediaControlPopup(
    model: MediaControlChipModel,
    seekBarStyle: MediaSeekBarStyle = MediaSeekBarStyle.DEFAULT,
    modifier: Modifier = Modifier,
) {
    val artworkDrawable =
        remember(model.artworkIcon) { (model.artworkIcon as? UiIcon.Loaded)?.drawable }
    val accent = rememberAlbumArtAccent(artworkDrawable, fallback = MaterialTheme.colorScheme.primary)
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    PopupSurface(
        shape = PopupShape,
        modifier = modifier.widthIn(min = 320.dp, max = 400.dp),
        animateContentAsUnit = false,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier =
                    Modifier.islandPopupRow(0).fillMaxWidth().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = model.openApp != null,
                    ) {
                        model.openApp?.invoke()
                    },
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaArtwork(model = model)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    model.appName?.takeIf { it.isNotBlank() }?.let { appName ->
                        Text(
                            text = appName.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = accent.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = model.songName?.toString().orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = LocalContentColor.current,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    model.artistName?.takeIf { it.isNotBlank() }?.let { artist ->
                        Text(
                            text = artist.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalContentColor.current.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (model.durationMs > 0L) {
                MediaProgressSection(
                    modifier = Modifier.islandPopupRow(1),
                    artwork = artworkDrawable,
                    positionMs = model.positionMs,
                    durationMs = model.durationMs,
                    isPlaying = model.isPlaying,
                    canBeScrubbed = model.canBeScrubbed,
                    onSeekTo = model.seekTo,
                    accent = accent,
                    seekBarStyle = seekBarStyle,
                )
            }

            Row(
                modifier = Modifier.islandPopupRow(2).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaActionButton(
                    action = model.previousAction,
                    iconOverrideId = R.drawable.ic_skip_previous_filled,
                    containerColor = LocalContentColor.current.copy(alpha = 0.11f),
                    iconTint = LocalContentColor.current,
                    buttonSize = 52.dp,
                )
                MediaActionButton(
                    action = model.playOrPause,
                    containerColor = accent,
                    iconTint = onAccent,
                    buttonSize = 64.dp,
                    iconSize = 28.dp,
                    emphasized = true,
                )
                MediaActionButton(
                    action = model.nextAction,
                    iconOverrideId = R.drawable.ic_skip_next_filled,
                    containerColor = LocalContentColor.current.copy(alpha = 0.11f),
                    iconTint = LocalContentColor.current,
                    buttonSize = 52.dp,
                )
            }
        }
    }
}

@Composable
private fun MediaArtwork(model: MediaControlChipModel) {
    val contentDescription = model.appName?.let { ContentDescription.Loaded(it) }
    val artwork =
        model.artworkIcon
            ?: model.appIcon
            ?: UiIcon.Resource(
                resId = com.android.internal.R.drawable.ic_audio_media,
                contentDescription = contentDescription,
            )
    Box(
        modifier =
            Modifier.size(52.dp)
                .clip(ArtworkShape)
                .background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        UiIconView(
            icon = artwork,
            modifier = Modifier.size(52.dp),
            tint = Color.Unspecified,
        )
    }
}

@Composable
private fun MediaProgressSection(
    artwork: Drawable?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    canBeScrubbed: Boolean,
    onSeekTo: ((Long) -> Unit)?,
    accent: Color,
    seekBarStyle: MediaSeekBarStyle = MediaSeekBarStyle.DEFAULT,
    modifier: Modifier = Modifier,
) {
    val seekBarHeight =
        if (
            seekBarStyle == MediaSeekBarStyle.GLOWING ||
                seekBarStyle == MediaSeekBarStyle.GRADIENT_WAVE
        ) 34.dp else 28.dp
    var isScrubbing by remember { mutableStateOf(false) }
    var displayedPositionMs by remember { mutableStateOf(positionMs.coerceIn(0L, durationMs)) }
    val currentSeekAction by rememberUpdatedState(onSeekTo)

    LaunchedEffect(positionMs, durationMs, isPlaying, isScrubbing) {
        if (isScrubbing) {
            return@LaunchedEffect
        }

        displayedPositionMs = positionMs.coerceIn(0L, durationMs)

        if (!isPlaying || durationMs <= 0L) {
            return@LaunchedEffect
        }

        val startWallClockMs = System.currentTimeMillis()
        val startPositionMs = displayedPositionMs
        while (!isScrubbing) {
            delay(16L)
            val elapsedMs = System.currentTimeMillis() - startWallClockMs
            displayedPositionMs = (startPositionMs + elapsedMs).coerceAtMost(durationMs)
            if (displayedPositionMs >= durationMs) {
                break
            }
        }
    }

    val accentArgb = accent.toArgb()
    val trackAlphaArgb = accent.copy(alpha = 0.20f).toArgb()

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = formatElapsedTime(displayedPositionMs),
            style = MaterialTheme.typography.labelMedium,
            color = LocalContentColor.current.copy(alpha = 0.60f),
        )

        Box(modifier = Modifier.weight(1f).height(seekBarHeight)) {
            var boxWidthPx by remember { mutableStateOf(0) }

            fun applyFraction(fraction: Float) {
                displayedPositionMs = (fraction.coerceIn(0f, 1f) * durationMs).toLong()
            }
            val gestureModifier = Modifier
                .onSizeChanged { boxWidthPx = it.width }
                .pointerInput(canBeScrubbed, durationMs) {
                    if (!canBeScrubbed) return@pointerInput
                    detectTapGestures { offset ->
                        applyFraction(offset.x / boxWidthPx.coerceAtLeast(1).toFloat())
                        currentSeekAction?.invoke(displayedPositionMs)
                    }
                }
                .pointerInput(canBeScrubbed, durationMs) {
                    if (!canBeScrubbed) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isScrubbing = true
                            applyFraction(offset.x / boxWidthPx.coerceAtLeast(1).toFloat())
                        },
                        onDragEnd = {
                            currentSeekAction?.invoke(displayedPositionMs)
                            isScrubbing = false
                        },
                        onDragCancel = { isScrubbing = false },
                        onHorizontalDrag = { change, _ ->
                            applyFraction(change.position.x / boxWidthPx.coerceAtLeast(1).toFloat())
                            change.consume()
                        },
                    )
                }

            if (
                seekBarStyle == MediaSeekBarStyle.GLOWING ||
                    seekBarStyle == MediaSeekBarStyle.GRADIENT_WAVE
            ) {
                StyledMediaSeekBar(
                    style = seekBarStyle,
                    artwork = artwork,
                    positionMs = displayedPositionMs,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    isScrubbing = isScrubbing,
                    modifier = Modifier.fillMaxWidth().height(seekBarHeight).then(gestureModifier),
                )
            } else if (seekBarStyle == MediaSeekBarStyle.WAVEFORM) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(seekBarHeight).then(gestureModifier),
                    factory = { context ->
                        WaveformSeekBar(context).apply {
                            max = 10_000
                            followsMediaColors = false
                            setWaveformColor(accentArgb)
                            setThumbColor(accentArgb)
                            isEnabled = false
                        }
                    },
                    update = { bar ->
                        bar.setWaveformColor(accentArgb)
                        bar.setThumbColor(accentArgb)
                        val target = if (durationMs > 0L) {
                            ((displayedPositionMs.toFloat() / durationMs) * 10_000f)
                                .toInt().coerceIn(0, 10_000)
                        } else 0
                        if (bar.progress != target) bar.progress = target
                        when {
                            isPlaying && !bar.isPlaying -> bar.startWaveAnimation()
                            !isPlaying && bar.isPlaying -> bar.stopWaveAnimation()
                        }
                    },
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(seekBarHeight).then(gestureModifier),
                    factory = { context ->
                        SeekBar(context).apply {
                            max = durationMs.toClampedInt()
                            splitTrack = false
                            setPadding(0, 0, 0, 0)
                            isEnabled = false
                            thumb = createSeekBarThumb(context, accentArgb)
                            thumbOffset = thumb.intrinsicWidth / 2

                            val layer = progressDrawable?.mutate() as? LayerDrawable
                            if (layer != null) {
                                layer.findDrawableByLayerId(android.R.id.background)
                                    ?.mutate()?.setTint(trackAlphaArgb)
                                layer.findDrawableByLayerId(android.R.id.secondaryProgress)
                                    ?.mutate()?.setTint(
                                        com.android.internal.graphics.ColorUtils
                                            .setAlphaComponent(accentArgb, 60)
                                    )
                                val squiggle = SquigglyProgress().apply {
                                    waveLength = context.resources.getDimensionPixelSize(
                                        R.dimen.qs_media_seekbar_progress_wavelength
                                    ).toFloat()
                                    lineAmplitude = context.resources.getDimensionPixelSize(
                                        R.dimen.qs_media_seekbar_progress_amplitude
                                    ).toFloat()
                                    phaseSpeed = context.resources.getDimensionPixelSize(
                                        R.dimen.qs_media_seekbar_progress_phase
                                    ).toFloat()
                                    strokeWidth = context.resources.getDimensionPixelSize(
                                        R.dimen.qs_media_seekbar_progress_stroke_width
                                    ).toFloat()
                                    setTint(accentArgb)
                                    drawRemainingLine = false
                                    transitionEnabled = true
                                    animate = false
                                }
                                layer.setDrawableByLayerId(android.R.id.progress, squiggle)
                                progressDrawable = layer
                            }
                        }
                    },
                    update = { seekBar ->
                        seekBar.max = durationMs.toClampedInt().coerceAtLeast(1)
                        seekBar.thumb?.alpha = if (canBeScrubbed) 255 else 120
                        if (!isScrubbing) {
                            seekBar.progress = displayedPositionMs.coerceIn(0L, durationMs).toClampedInt()
                        }

                        (seekBar.thumb as? GradientDrawable)?.setColor(accentArgb)

                        val layer = seekBar.progressDrawable as? LayerDrawable
                        layer?.findDrawableByLayerId(android.R.id.background)
                            ?.setTint(trackAlphaArgb)
                        layer?.findDrawableByLayerId(android.R.id.secondaryProgress)
                            ?.setTint(
                                com.android.internal.graphics.ColorUtils
                                    .setAlphaComponent(accentArgb, 60)
                            )
                        val squiggle =
                            layer?.findDrawableByLayerId(android.R.id.progress) as? SquigglyProgress
                        squiggle?.apply {
                            setTint(accentArgb)
                            setAlpha(if (canBeScrubbed) 255 else 120)
                            animate = isPlaying && canBeScrubbed && !isScrubbing
                        }
                    },
                )
            }
        }

        Text(
            text = formatElapsedTime(durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = LocalContentColor.current.copy(alpha = 0.60f),
        )
    }
}

@Composable
private fun MediaActionButton(
    action: MediaAction?,
    containerColor: Color,
    iconTint: Color,
    buttonSize: Dp = 54.dp,
    iconSize: Dp = 22.dp,
    emphasized: Boolean = false,
    iconOverrideId: Int? = null,
) {
    if (action == null || (action.icon == null && iconOverrideId == null)) {
        Spacer(modifier = Modifier.size(buttonSize))
        return
    }

    val haptics = LocalHapticFeedback.current
    val contentDescription =
        action.contentDescription?.toString()?.let { ContentDescription.Loaded(it) }
    Box(
        modifier =
            Modifier.pressScale(enabled = action.action != null) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    action.action?.run()
                }
                .size(buttonSize)
                .clip(CircleShape)
                .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        val actualIcon = if (iconOverrideId != null) {
            UiIcon.Resource(iconOverrideId, contentDescription)
        } else {
            action.icon?.let { UiIcon.Loaded(it, contentDescription) }
        }
        if (actualIcon != null) {
            UiIconView(
                icon = actualIcon,
                modifier = Modifier.size(iconSize).padding(start = if (emphasized) 1.dp else 0.dp),
                tint = iconTint,
            )
        }
    }
}

private fun createSeekBarThumb(context: Context, tintColor: Int): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 999f
        setColor(tintColor)
        setSize(
            (context.resources.displayMetrics.density * 4f).toInt().coerceAtLeast(1),
            (context.resources.displayMetrics.density * 18f).toInt().coerceAtLeast(1),
        )
    }
}

private fun Long.toClampedInt(): Int {
    return coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

private fun formatElapsedTime(milliseconds: Long): String {
    return DateUtils.formatElapsedTime(milliseconds / DateUtils.SECOND_IN_MILLIS)
}
