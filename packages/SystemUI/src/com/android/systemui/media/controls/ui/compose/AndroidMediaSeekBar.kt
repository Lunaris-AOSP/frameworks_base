/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
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

package com.android.systemui.media.controls.ui.compose

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.MotionEvent
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.media.controls.shared.MediaSeekBarStyle
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.media.controls.ui.view.GlowingSeekBar
import com.android.systemui.media.controls.ui.view.WaveformSeekBar
import com.android.systemui.res.R
import kotlin.math.roundToInt

private val CANCELLED_DRAG = Offset(0f, Float.MAX_VALUE)

@Composable
@SuppressLint("ClickableViewAccessibility")
internal fun AndroidMediaSeekBar(
    progress: Float,
    durationMs: Long,
    onScrubChange: (Float) -> Unit,
    onScrubFinished: (Offset) -> Unit,
    style: MediaSeekBarStyle,
    isPlaying: Boolean,
    enabled: Boolean,
    contentDescription: String,
    color: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    isScrubbing: Boolean = false,
    animationsAllowed: Boolean = true,
) {
    val currentOnScrubChange by rememberUpdatedState(onScrubChange)
    val currentOnScrubFinished by rememberUpdatedState(onScrubFinished)
    val tint = remember(color) { ColorStateList.valueOf(color.toArgb()) }
    val backgroundTint =
        remember(backgroundColor) { ColorStateList.valueOf(backgroundColor.toArgb()) }

    key(style, enabled) {
        var isTrackingTouch by remember { mutableStateOf(false) }
        AndroidView(
            factory = { context ->
                val seekBar =
                    when (style) {
                        MediaSeekBarStyle.GLOWING -> GlowingSeekBar(context)
                        MediaSeekBarStyle.WAVEFORM ->
                            WaveformSeekBar(context).apply {
                                followsMediaColors = false
                                setWaveformColor(tint.defaultColor)
                                setThumbColor(tint.defaultColor)
                            }
                        MediaSeekBarStyle.DEFAULT ->
                            SeekBar(context).apply {
                                thumb = context.getDrawable(R.drawable.media_seekbar_thumb)
                                progressDrawable =
                                    SquigglyProgress().apply {
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
                var start = Offset.Zero
                var delta = Offset.Zero
                seekBar.apply {
                    max = durationMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    splitTrack = false
                    setPadding(0, 0, 0, 0)
                    thumbOffset =
                        if (style == MediaSeekBarStyle.DEFAULT) (thumb?.intrinsicWidth ?: 0) / 2
                        else 0
                    setOnTouchListener { view, event ->
                        val position = Offset(event.x, event.y)
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                start = position
                                delta = Offset.Zero
                            }
                            MotionEvent.ACTION_MOVE -> delta = position - start
                            MotionEvent.ACTION_UP -> {
                                delta = position - start
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                delta = CANCELLED_DRAG
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        false
                    }
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(
                                seekBar: SeekBar,
                                progress: Int,
                                fromUser: Boolean,
                            ) {
                                if (!fromUser) return
                                currentOnScrubChange(progress.toFloat() / seekBar.max)
                                if (!isTrackingTouch) currentOnScrubFinished(Offset.Zero)
                            }

                            override fun onStartTrackingTouch(seekBar: SeekBar) {
                                isTrackingTouch = true
                                currentOnScrubChange(seekBar.progress.toFloat() / seekBar.max)
                            }

                            override fun onStopTrackingTouch(seekBar: SeekBar) {
                                currentOnScrubFinished(delta)
                                isTrackingTouch = false
                            }
                        }
                    )
                }
            },
            update = { seekBar ->
                seekBar.max = durationMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                if (!isTrackingTouch) {
                    seekBar.progress =
                        (progress.coerceIn(0f, 1f) * seekBar.max)
                            .roundToInt()
                            .coerceIn(0, seekBar.max)
                }
                seekBar.isEnabled = enabled
                seekBar.contentDescription = contentDescription
                if (seekBar.progressTintList != tint) {
                    seekBar.progressTintList = tint
                    seekBar.thumbTintList = tint
                    (seekBar as? WaveformSeekBar)?.apply {
                        setWaveformColor(tint.defaultColor)
                        setThumbColor(tint.defaultColor)
                    }
                }
                seekBar.progressBackgroundTintList = backgroundTint
                val scrubbing = isScrubbing || isTrackingTouch
                val animate = isPlaying && !scrubbing && animationsAllowed
                (seekBar as? GlowingSeekBar)?.apply {
                    this.isScrubbing = scrubbing
                    setPlaybackState(isPlaying, animate = animationsAllowed)
                }
                (seekBar.progressDrawable as? SquigglyProgress)?.apply { this.animate = animate }
                (seekBar as? WaveformSeekBar)?.apply {
                    if (animate) startWaveAnimation() else stopWaveAnimation()
                }
            },
            onRelease = { seekBar ->
                seekBar.setOnTouchListener(null)
                seekBar.setOnSeekBarChangeListener(null)
                (seekBar.progressDrawable as? SquigglyProgress)?.animate = false
                (seekBar as? WaveformSeekBar)?.stopWaveAnimation()
                (seekBar as? GlowingSeekBar)?.setPlaybackState(false, animate = false)
                if (isTrackingTouch) {
                    seekBar.parent?.requestDisallowInterceptTouchEvent(false)
                    currentOnScrubFinished(CANCELLED_DRAG)
                    isTrackingTouch = false
                }
            },
            modifier = modifier,
        )
    }
}
