/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.screenrecord.domain

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.DisplayMetrics
import android.view.Display
import com.android.systemui.Prefs
import com.android.systemui.res.R
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureTarget
import com.android.systemui.screencapture.record.shared.model.ScreenCaptureRecordParametersModel
import com.android.systemui.screenrecord.ScreenRecordingAudioSource
import kotlin.math.roundToInt

object ScreenRecordingOptions {
    val audioSources =
        listOf(
            ScreenRecordingAudioSource.INTERNAL,
            ScreenRecordingAudioSource.MIC,
            ScreenRecordingAudioSource.MIC_AND_INTERNAL,
        )
    val bitrates = listOf(1f, 0.5f, 0.25f)
    val timeLimits = listOf(0, 300000, 600000, 1800000, 3600000)
    val fileSizes = listOf(0L, 10L shl 20, 100L shl 20, 500L shl 20, 1L shl 30, 15L shl 30)

    fun load(context: Context, displayId: Int): ScreenCaptureRecordParametersModel {
        fun flag(key: String, default: Int = 0) = Prefs.getInt(context, key, default) == 1
        return ScreenCaptureRecordParametersModel(
            target = ScreenCaptureTarget.Fullscreen(displayId),
            audioSource =
                if (flag("screenrecord_use_audio")) {
                    audioSources.getOrElse(Prefs.getInt(context, "screenrecord_audio_source", 0)) {
                        ScreenRecordingAudioSource.INTERNAL
                    }
                } else ScreenRecordingAudioSource.NONE,
            shouldShowTaps = flag("screenrecord_show_taps"),
            shouldShowFrontCamera = flag("screenrecord_show_front_camera"),
            lowQuality = flag("screenrecord_use_low_quality"),
            longerDuration = flag("screenrecord_use_longer_timeout"),
            hevc = flag("screenrecord_use_hevc", 1),
            skipTimer = flag("screenrecord_skip_timer"),
            resolutionMode = Prefs.getInt(context, "screenrecord_resolution_mode", 0),
            fpsMode =
                when (val fps = Prefs.getInt(context, "screenrecord_fps_mode", 0)) {
                    1 -> 30
                    2 -> 60
                    else -> fps
                },
            timeLimitMs =
                timeLimits.getOrElse(Prefs.getInt(context, "screenrecord_time_limit", 0)) { 0 },
            fileSizeBytes =
                fileSizes.getOrElse(Prefs.getInt(context, "screenrecord_file_size", 0)) { 0L },
            bitrateMultiplier =
                bitrates.getOrElse(Prefs.getInt(context, "screenrecord_bitrate", 0)) { 1f },
        )
    }

    fun save(context: Context, options: ScreenCaptureRecordParametersModel) {
        val editor = Prefs.get(context).edit()
        fun flag(key: String, value: Boolean) {
            editor.putInt(key, if (value) 1 else 0)
        }
        with(options) {
            flag("screenrecord_show_taps", shouldShowTaps)
            flag("screenrecord_show_front_camera", shouldShowFrontCamera)
            flag("screenrecord_use_low_quality", lowQuality)
            flag("screenrecord_use_longer_timeout", longerDuration)
            flag("screenrecord_use_audio", audioSource != ScreenRecordingAudioSource.NONE)
            audioSources
                .indexOf(audioSource)
                .takeIf { it >= 0 }
                ?.let { editor.putInt("screenrecord_audio_source", it) }
            flag("screenrecord_skip_timer", skipTimer)
            flag("screenrecord_use_hevc", hevc)
            editor.putInt("screenrecord_resolution_mode", resolutionMode)
            editor.putInt("screenrecord_fps_mode", fpsMode)
            editor.putInt(
                "screenrecord_bitrate",
                bitrates.indexOf(bitrateMultiplier).coerceAtLeast(0),
            )
            editor.putInt(
                "screenrecord_time_limit",
                timeLimits.indexOf(timeLimitMs).coerceAtLeast(0),
            )
            editor.putInt(
                "screenrecord_file_size",
                fileSizes.indexOf(fileSizeBytes).coerceAtLeast(0),
            )
        }
        editor.apply()
    }

    fun resolutions(display: Display?): List<Int> {
        val metrics = DisplayMetrics().also { display?.getRealMetrics(it) }
        val longEdge = maxOf(metrics.widthPixels, metrics.heightPixels)
        return listOf(0) + listOf(2560, 1920, 1280).filter { it < longEdge }
    }

    fun frameRates(context: Context, display: Display?): List<Int> {
        val maxFps = context.resources.getInteger(R.integer.config_screenRecorderMaxFramerate)
        val rates = mutableSetOf(30, 60)
        display?.let {
            rates.add(it.refreshRate.roundToInt())
            it.supportedModes.forEach { mode ->
                rates.add(mode.refreshRate.roundToInt())
                mode.alternativeRefreshRates.forEach { rate -> rates.add(rate.roundToInt()) }
            }
        }
        return listOf(0) + rates.filter { it > 0 && (maxFps == 0 || it <= maxFps) }.sorted()
    }

    fun hasHevcEncoder(): Boolean =
        runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                    codec.isEncoder &&
                        codec.isHardwareAccelerated &&
                        codec.supportedTypes.any {
                            it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true)
                        }
                }
            }
            .getOrDefault(false)
}
