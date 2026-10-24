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

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.statusbar.featurepods.media.shared.model.MediaControlChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.compose.LocalLunarisBarColors
import com.android.systemui.statusbar.featurepods.popups.ui.compose.LocalLunarisBarState
import com.android.systemui.statusbar.featurepods.popups.ui.compose.LunarisBarSurface
import kotlinx.coroutines.delay

data class LyricLine(val timestampMs: Long, val text: String)

@Composable
fun LyricsCard(
    model: MediaControlChipModel,
    modifier: Modifier = Modifier,
    useRedesignedMedia: Boolean = false,
) {
    val syncedLyrics = model.syncedLyrics
    val plainLyrics = model.lyrics

    val lyricLines =
        remember(syncedLyrics) {
            if (syncedLyrics.isNullOrBlank()) emptyList() else parseLrc(syncedLyrics)
        }

    val settings = LocalLunarisBarState.current.settings
    var positionMs by
        remember(model.packageName, model.songName) { mutableLongStateOf(model.positionMs) }
    LaunchedEffect(
        model.packageName,
        model.songName,
        syncedLyrics,
        model.positionMs,
        model.isPlaying,
        model.playbackSpeed,
    ) {
        val start = SystemClock.elapsedRealtime()
        do {
            positionMs =
                model.positionMs +
                    if (model.isPlaying)
                        ((SystemClock.elapsedRealtime() - start) * model.playbackSpeed).toLong()
                    else 0L
            if (!model.isPlaying) break
            delay(200)
        } while (true)
    }
    val colors = LocalLunarisBarColors.current
    LunarisBarSurface(modifier = modifier.widthIn(max = 480.dp).fillMaxWidth().height(200.dp)) {
        if (lyricLines.isNotEmpty()) {
            val activeIndex by
                remember(lyricLines, positionMs) {
                    derivedStateOf {
                        var index = -1
                        for (i in lyricLines.indices) {
                            if (positionMs >= lyricLines[i].timestampMs) {
                                index = i
                            } else {
                                break
                            }
                        }
                        index
                    }
                }

            val lazyListState = rememberLazyListState()

            LaunchedEffect(activeIndex) {
                if (activeIndex >= 0 && activeIndex < lyricLines.size) {
                    if (settings.isReducedMotion) lazyListState.scrollToItem(activeIndex)
                    else lazyListState.animateScrollToItem(activeIndex)
                }
            }

            LazyColumn(
                state = lazyListState,
                contentPadding = PaddingValues(vertical = 80.dp),
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                itemsIndexed(lyricLines) { index, line ->
                    val isActive = index == activeIndex
                    val alpha by
                        animateFloatAsState(
                            if (isActive) 1f else 0.4f,
                            animationSpec =
                                androidx.compose.animation.core.tween(
                                    if (settings.isReducedMotion) 0 else 200
                                ),
                            label = "lyric_alpha",
                        )
                    val scale = if (isActive) 1.1f else 0.9f
                    val fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal

                    Text(
                        text = line.text,
                        color = if (isActive) colors.accent else colors.content,
                        fontSize = (16.sp * scale),
                        fontWeight = fontWeight,
                        textAlign = TextAlign.Start,
                        modifier =
                            Modifier.fillMaxWidth()
                                .background(
                                    if (isActive) colors.accent.copy(alpha = 0.1f)
                                    else Color.Transparent,
                                    RoundedCornerShape(16.dp),
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .alpha(alpha),
                    )
                }
            }
        } else if (!plainLyrics.isNullOrBlank()) {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 16.dp),
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                item {
                    Text(
                        text = plainLyrics,
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                        fontSize = 15.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun parseLrc(lrcText: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    val pattern = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\](.*)")
    lrcText.split("\n").forEach { lineStr ->
        val match = pattern.matchEntire(lineStr.trim())
        if (match != null) {
            val min =
                match.groupValues[1].toLongOrNull()?.takeIf { it < Long.MAX_VALUE / 60000L }
                    ?: return@forEach
            val sec = match.groupValues[2].toLongOrNull()?.takeIf { it < 60L } ?: return@forEach
            val fracMs = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            val timestampMs = (min * 60 + sec) * 1000L + fracMs
            val text = match.groupValues[4].trim()
            if (text.isNotEmpty() || lines.isNotEmpty()) {
                lines.add(LyricLine(timestampMs, text))
            }
        }
    }
    return lines.sortedBy { it.timestampMs }
}
