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

package com.android.systemui.media.controls.ui.composable

import android.graphics.Color
import android.view.View
import android.widget.SeekBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.media.controls.shared.MediaSeekBarStyle
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.media.controls.ui.view.GlowingSeekBar
import com.android.systemui.media.controls.ui.view.WaveformSeekBar
import com.android.systemui.res.R

@Composable
fun StyledMediaSeekBar(
    style: MediaSeekBarStyle,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isScrubbing: Boolean,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    onScrubStart: () -> Unit = {},
    onScrubStop: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
) {
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnScrubStart by rememberUpdatedState(onScrubStart)
    val currentOnScrubStop by rememberUpdatedState(onScrubStop)
    key(style, interactive && durationMs > 0L) {
        var isTrackingTouch by remember { mutableStateOf(false) }
        AndroidView(
            factory = { context ->
                val bar =
                    when (style) {
                        MediaSeekBarStyle.GLOWING -> GlowingSeekBar(context)
                        MediaSeekBarStyle.WAVEFORM ->
                            WaveformSeekBar(context).apply {
                                followsMediaColors = false
                                setWaveformColor(Color.WHITE)
                                setThumbColor(Color.WHITE)
                                setPadding(0, 0, 0, 0)
                            }
                        MediaSeekBarStyle.DEFAULT ->
                            SeekBar(context, null, 0, R.style.MediaPlayer_ProgressBar).apply {
                                (progressDrawable as? SquigglyProgress)?.apply {
                                    waveLength =
                                        resources.getDimension(
                                            R.dimen.qs_media_seekbar_progress_wavelength
                                        )
                                    lineAmplitude =
                                        resources.getDimension(
                                            R.dimen.qs_media_seekbar_progress_amplitude
                                        )
                                    phaseSpeed =
                                        resources.getDimension(
                                            R.dimen.qs_media_seekbar_progress_phase
                                        )
                                    strokeWidth =
                                        resources.getDimension(
                                            R.dimen.qs_media_seekbar_progress_stroke_width
                                        )
                                }
                            }
                    }
                bar.apply {
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(
                                bar: SeekBar,
                                value: Int,
                                fromUser: Boolean,
                            ) {
                                if (fromUser) currentOnSeek(value.toLong())
                            }

                            override fun onStartTrackingTouch(bar: SeekBar) {
                                isTrackingTouch = true
                                currentOnScrubStart()
                            }

                            override fun onStopTrackingTouch(bar: SeekBar) {
                                currentOnScrubStop()
                                isTrackingTouch = false
                            }
                        }
                    )
                }
            },
            update = { bar ->
                bar.max = durationMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                if (!isTrackingTouch) {
                    bar.progress = positionMs.coerceIn(0L, bar.max.toLong()).toInt()
                }
                bar.isEnabled = interactive && durationMs > 0L
                when (bar) {
                    is GlowingSeekBar -> {
                        bar.isScrubbing = isScrubbing || isTrackingTouch
                        bar.setPlaybackState(isPlaying)
                    }
                    is WaveformSeekBar -> {
                        if (isPlaying && !isScrubbing && !isTrackingTouch) {
                            bar.startWaveAnimation()
                        } else {
                            bar.stopWaveAnimation()
                        }
                    }
                }
                (bar.progressDrawable as? SquigglyProgress)?.animate =
                    isPlaying && !isScrubbing && !isTrackingTouch
            },
            onRelease = { bar ->
                bar.setOnSeekBarChangeListener(null)
                (bar as? GlowingSeekBar)?.setPlaybackState(false, animate = false)
                (bar as? WaveformSeekBar)?.stopWaveAnimation()
                (bar.progressDrawable as? SquigglyProgress)?.animate = false
                if (isTrackingTouch) {
                    bar.parent?.requestDisallowInterceptTouchEvent(false)
                    currentOnScrubStop()
                    isTrackingTouch = false
                }
            },
            modifier = modifier,
        )
    }
}
