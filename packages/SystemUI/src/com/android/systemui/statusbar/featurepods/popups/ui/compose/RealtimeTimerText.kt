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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel

@Composable
fun rememberElapsedDurationText(baseElapsedRealtimeMs: Long): String {
    var now by remember(baseElapsedRealtimeMs) {
        mutableLongStateOf(android.os.SystemClock.elapsedRealtime())
    }

    LaunchedEffect(baseElapsedRealtimeMs) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(1000L - (now - baseElapsedRealtimeMs).coerceAtLeast(0L) % 1000L)
        }
    }

    val elapsedSeconds = ((now - baseElapsedRealtimeMs).coerceAtLeast(0L) / 1000L)
    return formatElapsedDuration(elapsedSeconds)
}

private fun formatElapsedDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
fun rememberStopwatchText(
    model: com.android.systemui.statusbar.featurepods.stopwatch.shared.model.StopwatchPopupModel,
): String = model.elapsedTimeText.orEmpty()

@Composable
fun rememberTimerText(
    model: PopupContentModel.SystemEvent,
): String {
    val end = model.timerEndElapsedRealtimeMs ?: return model.text.ifBlank { "0:00" }
    var now by remember(end, model.timerPaused) {
        mutableLongStateOf(android.os.SystemClock.elapsedRealtime())
    }
    LaunchedEffect(end, model.timerPaused) {
        while (!model.timerPaused) {
            now = android.os.SystemClock.elapsedRealtime()
            val remaining = (end - now).coerceAtLeast(0L)
            if (remaining == 0L) break
            delay(((remaining - 1L) % 1000L) + 1L)
        }
    }
    val remaining = if (model.timerPaused) model.timerRemainingMs ?: 0L else end - now
    return formatElapsedDuration((remaining.coerceAtLeast(0L) + 999L) / 1000L)
}

@Composable
fun rememberTimerProgress(
    model: PopupContentModel.SystemEvent,
): Float? {
    val total = model.timerOriginalDurationMs ?: return null
    if (total <= 0L) return null
    val end = model.timerEndElapsedRealtimeMs
    var now by remember(end, model.timerPaused) {
        mutableLongStateOf(android.os.SystemClock.elapsedRealtime())
    }
    LaunchedEffect(end, model.timerPaused) {
        while (!model.timerPaused && end != null) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(250L)
        }
    }
    val remaining = if (model.timerPaused) model.timerRemainingMs ?: 0L
        else (end?.minus(now) ?: 0L).coerceAtLeast(0L)
    return (remaining.toFloat() / total).coerceIn(0f, 1f)
}
