/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.thenIf
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.popups.ui.model.ChipIcon
import com.android.systemui.statusbar.featurepods.popups.ui.model.ColorsModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.HoverBehavior
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A clickable chip that can show an anchored popup containing relevant system controls. The chip
 * can show an icon that can have its own separate action distinct from its parent chip. Moreover,
 * the chip can show text containing contextual information.
 */
@Composable
fun StatusBarPopupChip(
    viewModel: PopupChipModel.Shown,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val hasHoverBehavior = viewModel.hoverBehavior !is HoverBehavior.None
    val hoveredState by interactionSource.collectIsHoveredAsState()
    val isHovered = hasHoverBehavior && hoveredState
    val isPopupShown = viewModel.isPopupShown
    val indication = if (hoveredState) null else LocalIndication.current
    val chipShape =
        RoundedCornerShape(dimensionResource(id = R.dimen.ongoing_activity_chip_corner_radius))
    val colors = viewModel.colors
    val isMediaChip = viewModel.chipId == PopupChipId.MediaControl
    val chipBackgroundColor =
        colors.chipBackground(isPopupShown = isPopupShown, colorScheme = MaterialTheme.colorScheme)

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .thenIf(viewModel.contentDescription != null) {
                    Modifier.semantics { contentDescription = viewModel.contentDescription!! }
                }
                .thenIf(!isPopupShown) {
                    Modifier.clickable(
                        onClick = { viewModel.showPopup() },
                        indication = null,
                        interactionSource = interactionSource,
                    )
                },
    ) {
        val text = viewModel.chipText
        val startPadding = if (isMediaChip) 6.dp else 4.dp
        val endPadding = if (text != null) 8.dp else startPadding
        val chipHeight =
            if (isMediaChip) {
                dimensionResource(R.dimen.ongoing_appops_chip_height) + 2.dp
            } else {
                dimensionResource(R.dimen.ongoing_appops_chip_height)
            }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.height(chipHeight)
                    .defaultMinSize(minWidth = 0.dp)
                    .clip(chipShape)
                    .background(chipBackgroundColor)
                    .border(
                        width = dimensionResource(id = R.dimen.ongoing_activity_chip_outline_width),
                        color =
                            colors.chipOutline(
                                isPopupShown = isPopupShown,
                                colorScheme = MaterialTheme.colorScheme,
                            ),
                        shape = chipShape,
                    )
                    .indication(interactionSource, indication)
                    .padding(start = startPadding, end = endPadding),
        ) {
            val hoverBehavior = viewModel.hoverBehavior
            val chipIcons =
                when {
                    isHovered && hoverBehavior is HoverBehavior.Buttons -> hoverBehavior.icons
                    else -> viewModel.icons
                }

            ChipIcons(
                chipIcons = chipIcons,
                colors = colors,
                isPopupShown = isPopupShown,
                isHovered = isHovered,
                isMediaChip = isMediaChip,
            )

            if (text != null) {
                val textStyle = MaterialTheme.typography.labelLarge
                val textMeasurer = rememberTextMeasurer()
                var textOverflow by remember { mutableStateOf(false) }
                val maxTextWidth =
                    dimensionResource(id = R.dimen.ongoing_activity_chip_max_text_width) +
                        if (isMediaChip) 32.dp else 0.dp

                Text(
                    text = text,
                    style = textStyle,
                    softWrap = false,
                    color =
                        colors.chipContent(
                            isPopupShown = isPopupShown,
                            colorScheme = MaterialTheme.colorScheme,
                        ),
                    modifier =
                        Modifier.widthIn(max = maxTextWidth)
                            .layout { measurables, constraints ->
                                val placeable = measurables.measure(constraints)
                                val intrinsicWidth =
                                    textMeasurer
                                        .measure(text, textStyle, softWrap = false)
                                        .size
                                        .width
                                textOverflow = intrinsicWidth > constraints.maxWidth

                                layout(placeable.width, placeable.height) {
                                    if (textOverflow) {
                                        placeable.placeWithLayer(0, 0) {
                                            compositingStrategy = CompositingStrategy.Offscreen
                                        }
                                    } else {
                                        placeable.place(0, 0)
                                    }
                                }
                            }
                            .overflowFadeOut(
                                hasOverflow = { textOverflow },
                                fadeLength =
                                    dimensionResource(
                                        id = R.dimen.ongoing_activity_chip_text_fading_edge_length
                                    ),
                            ),
                )
            }

            if (isMediaChip) {
                MusicVisualizerBars(
                    isPlaying = isMediaPlaying(viewModel),
                    color =
                        colors.chipContent(
                            isPopupShown = isPopupShown,
                            colorScheme = MaterialTheme.colorScheme,
                        ),
                )
            }
        }
    }
}

@Composable
private fun ChipIcons(
    chipIcons: List<ChipIcon>,
    colors: ColorsModel,
    isPopupShown: Boolean,
    isHovered: Boolean,
    isMediaChip: Boolean,
) {
    val iconHoverBackgroundColor =
        colors.iconBackgroundOnHover(
            isPopupShown = isPopupShown,
            colorScheme = MaterialTheme.colorScheme,
        )
    val iconColor =
        colors.icon(
            isPopupShown = isPopupShown,
            isHovered = isHovered,
            colorScheme = MaterialTheme.colorScheme,
        )

    chipIcons.forEachIndexed { index, chipIcon ->
        val shouldUseArtworkStyle = isMediaChip && index == 0
        Icon(
            icon = chipIcon.icon,
            modifier =
                Modifier.size(if (shouldUseArtworkStyle) 18.dp else 20.dp)
                    .thenIf(shouldUseArtworkStyle) {
                        Modifier.clip(RoundedCornerShape(5.dp))
                    }
                    .thenIf(chipIcon.onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = chipIcon.onClick!!)
                    }
                    .thenIf(isHovered && iconHoverBackgroundColor != Color.Unspecified) {
                        Modifier.background(color = iconHoverBackgroundColor, shape = CircleShape)
                            .padding(2.dp)
                    },
            tint = if (shouldUseArtworkStyle) Color.Unspecified else iconColor,
        )
    }
}

private fun Modifier.overflowFadeOut(hasOverflow: () -> Boolean, fadeLength: Dp): Modifier {
    return drawWithCache {
        val width = size.width
        val start = (width - fadeLength.toPx()).coerceAtLeast(0f)
        val gradient =
            Brush.horizontalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startX = start,
                endX = width,
            )
        onDrawWithContent {
            drawContent()
            if (hasOverflow()) drawRect(brush = gradient, blendMode = BlendMode.DstIn)
        }
    }
}

private fun isMediaPlaying(viewModel: PopupChipModel.Shown): Boolean {
    val buttonIcon =
        (viewModel.hoverBehavior as? HoverBehavior.Buttons)?.icons?.firstOrNull()?.icon
            ?: return false
    val description =
        (buttonIcon.contentDescription as? ContentDescription.Loaded)?.description
            ?.lowercase()
            ?: return false
    return description.contains("pause")
}

@Composable
private fun MusicVisualizerBars(isPlaying: Boolean, color: Color) {
    val levels = remember { mutableStateListOf(0.22f, 0.45f, 0.34f, 0.56f) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(isPlaying) {
        if (!isPlaying) {
            levels[0] = 0.22f
            levels[1] = 0.45f
            levels[2] = 0.34f
            levels[3] = 0.56f
            onDispose {}
        } else {
            var visualizer: Visualizer? = null
            val listener =
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (waveform == null || waveform.isEmpty()) return
                    val quarter = waveform.size / 4
                    val secondQuarter = quarter * 2
                    val thirdQuarter = quarter * 3
                    val newLevels =
                        floatArrayOf(
                            waveform.windowEnergy(0, quarter),
                            waveform.windowEnergy(quarter, secondQuarter),
                            waveform.windowEnergy(secondQuarter, thirdQuarter),
                            waveform.windowEnergy(thirdQuarter, waveform.size),
                        )

                        mainHandler.post {
                            for (i in newLevels.indices) {
                                levels[i] =
                                    (levels[i] * 0.65f + newLevels[i] * 0.35f).coerceIn(0.1f, 1f)
                            }
                        }
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) = Unit
                }

            runCatching {
                    visualizer =
                        Visualizer(0).apply {
                            val captureSizes = Visualizer.getCaptureSizeRange()
                            captureSize = captureSizes[1].coerceAtMost(256)
                            setDataCaptureListener(
                                listener,
                                Visualizer.getMaxCaptureRate() / 2,
                                true,
                                false,
                            )
                            enabled = true
                        }
                }
                .onFailure {
                    levels[0] = 0.24f
                    levels[1] = 0.48f
                    levels[2] = 0.36f
                    levels[3] = 0.52f
                }

            onDispose {
                runCatching { visualizer?.enabled = false }
                runCatching { visualizer?.release() }
            }
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        modifier = Modifier.padding(start = 2.dp),
    ) {
        repeat(levels.size) { index ->
            val scale = levels[index]
            Box(
                modifier = Modifier.size(width = 2.dp, height = 11.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier =
                        Modifier.size(width = 2.dp, height = 11.dp * scale)
                            .clip(RoundedCornerShape(8.dp))
                            .background(color)
                )
            }
        }
    }
}

private fun ByteArray.windowEnergy(start: Int, end: Int): Float {
    if (start >= end || start < 0 || end > size) return 0.1f
    var sum = 0f
    var count = 0
    for (i in start until end) {
        val normalized = abs(this[i].toInt()) / 128f
        sum += normalized * normalized
        count++
    }
    if (count == 0) return 0.1f
    return sqrt(sum / count).coerceIn(0.1f, 1f)
}
