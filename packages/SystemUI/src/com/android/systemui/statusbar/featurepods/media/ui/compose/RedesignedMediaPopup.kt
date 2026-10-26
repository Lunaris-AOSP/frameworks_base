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

package com.android.systemui.statusbar.featurepods.media.ui.compose

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.widget.SeekBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.media.controls.shared.MediaSeekBarStyle
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.media.controls.ui.view.CosSeekBar
import com.android.systemui.media.controls.ui.view.GlowingSeekBar
import com.android.systemui.media.controls.ui.view.GradientWaveSeekBar
import com.android.systemui.media.controls.ui.view.WaveformSeekBar
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.featurepods.media.ui.view.RedesignedMediaView
import com.android.systemui.statusbar.featurepods.popups.ui.compose.LocalIslandPopupRevealMillis

@Composable
fun RedesignedMediaPopup(
    model: MediaControlChipModel,
    seekBarStyle: MediaSeekBarStyle,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appIcon =
        remember(model.packageName, model.appIcon, context) {
            model.packageName?.let { packageName ->
                runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
            } ?: model.appIcon.drawable(context)
        }
    val artwork =
        remember(model.artworkIcon, appIcon, context) {
            model.artworkIcon.drawable(context)
                ?: appIcon
                ?: context.getDrawable(com.android.internal.R.drawable.ic_audio_media)
        }
    val state =
        remember(model, artwork, appIcon) {
            val controls = model.transportActions
            RedesignedMediaView.State(
                title = model.songName,
                artist = model.artistName,
                artwork = artwork,
                appIcon = appIcon,
                trackKey = "${model.packageName}\u0000${model.songName}\u0000${model.artistName}",
                playing = model.isPlaying,
                position = model.positionMs,
                duration = model.durationMs,
                playbackSpeed = model.playbackSpeed,
                canSeek = model.canBeScrubbed,
                seekTo = model.seekTo,
                previous =
                    (if (controls != null) controls.prevOrCustom else model.previousAction)
                        .toViewAction(context.getString(R.string.controls_media_button_prev)),
                playPause =
                    model.playOrPause.toViewAction(
                        context.getString(
                            if (model.isPlaying) R.string.controls_media_button_pause
                            else R.string.controls_media_button_play
                        )
                    ),
                next =
                    (if (controls != null) controls.nextOrCustom else model.nextAction)
                        .toViewAction(context.getString(R.string.controls_media_button_next)),
                custom = model.customActions.take(2).map { it.toViewAction() },
            )
        }
    val reveal = LocalIslandPopupRevealMillis.current ?: Float.POSITIVE_INFINITY
    val maxWidth = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) 380.dp else 420.dp
    AndroidView(
        factory = { RedesignedMediaView(it) },
        update = { view ->
            view.seekDescription = { position, duration ->
                context.getString(R.string.controls_media_seekbar_description, position, duration)
            }
            view.setSeekBar(seekBarStyle, { createSeekBar(context, seekBarStyle) }) {
                bar,
                playing,
                scrubbing ->
                when (bar) {
                    is GradientWaveSeekBar -> {
                        bar.setArtwork(artwork)
                        bar.isScrubbing = scrubbing
                        bar.setPlaybackState(playing)
                    }
                    is GlowingSeekBar -> {
                        bar.isScrubbing = scrubbing
                        bar.setPlaybackState(playing)
                    }
                    is CosSeekBar -> {
                        bar.setArtwork(artwork)
                        bar.isScrubbing = scrubbing
                        bar.setPlaybackState(playing)
                    }
                    is WaveformSeekBar -> {
                        if (playing && !bar.isPlaying) bar.startWaveAnimation()
                        else if (!playing && bar.isPlaying) bar.stopWaveAnimation()
                    }
                }
                (bar.progressDrawable as? SquigglyProgress)?.animate =
                    playing && !scrubbing && bar.isEnabled
            }
            view.bind(state, reveal)
        },
        modifier =
            modifier.widthIn(max = maxWidth).fillMaxWidth().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = model.openApp != null,
            ) {
                model.openApp?.invoke()
            },
    )
}

private fun Icon?.drawable(context: Context): Drawable? {
    val drawable =
        when (this) {
            is Icon.Loaded -> drawable
            is Icon.Resource -> context.getDrawable(resId)
            null -> null
        }
    return drawable?.let { (it.constantState?.newDrawable(context.resources) ?: it).mutate() }
}

private fun MediaAction?.toViewAction(fallbackLabel: String? = null): RedesignedMediaView.Action =
    RedesignedMediaView.Action(
        icon =
            this?.icon?.let {
                (it.constantState?.newDrawable() ?: it).mutate().apply { setTint(Color.WHITE) }
            },
        label = this?.contentDescription ?: fallbackLabel,
        run = this?.action,
    )

private fun createSeekBar(context: Context, style: MediaSeekBarStyle): SeekBar =
    when (style) {
        MediaSeekBarStyle.GRADIENT_WAVE -> GradientWaveSeekBar(context)
        MediaSeekBarStyle.GLOWING -> GlowingSeekBar(context)
        MediaSeekBarStyle.COS -> CosSeekBar(context)
        MediaSeekBarStyle.WAVEFORM ->
            WaveformSeekBar(context).apply {
                followsMediaColors = false
                setWaveformColor(Color.WHITE)
                setThumbColor(Color.WHITE)
            }
        MediaSeekBarStyle.DEFAULT ->
            SeekBar(context, null, 0, R.style.MediaPlayer_ProgressBar).apply {
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                progressTintList = ColorStateList.valueOf(Color.WHITE)
                progressBackgroundTintList = ColorStateList.valueOf(0x33ffffff)
                (progressDrawable as? SquigglyProgress)?.apply {
                    waveLength =
                        resources.getDimension(R.dimen.qs_media_seekbar_progress_wavelength)
                    lineAmplitude =
                        resources.getDimension(R.dimen.qs_media_seekbar_progress_amplitude)
                    phaseSpeed = resources.getDimension(R.dimen.qs_media_seekbar_progress_phase)
                    strokeWidth =
                        resources.getDimension(R.dimen.qs_media_seekbar_progress_stroke_width)
                    setTint(Color.WHITE)
                }
            }
    }
