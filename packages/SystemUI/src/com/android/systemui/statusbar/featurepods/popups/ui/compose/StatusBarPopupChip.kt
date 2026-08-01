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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

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
    onChipBoundsChanged: (Rect) -> Unit = {},
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
    val view = LocalView.current
    var toggleCount by remember { mutableStateOf(0) }
    LaunchedEffect(isPopupShown) { toggleCount++ }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .onGloballyPositioned { coordinates ->
                    onChipBoundsChanged(coordinates.boundsInScreen(view))
                }
                .squishAnimation(toggleCount)
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
    AudioReactiveBars(isPlaying = isPlaying, color = color, startPadding = 2.dp)
}

private fun LayoutCoordinates.boundsInScreen(view: android.view.View): Rect {
    val location = IntArray(2)
    view.getLocationOnScreen(location)
    return boundsInRoot().translate(Offset(location[0].toFloat(), location[1].toFloat()))
}

@Composable
private fun Modifier.squishAnimation(toggleCount: Int): Modifier {
    val scaleX = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val scaleY = remember { Animatable(1f, visibilityThreshold = 0.01f) }
    val currentToggleCount by rememberUpdatedState(toggleCount)
    LaunchedEffect(Unit) {
        snapshotFlow { currentToggleCount }
            .drop(1)
            .collectLatest {
                scaleX.snapTo(1f)
                scaleY.snapTo(1f)
                coroutineScope {
                    launch {
                        scaleX.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                keyframes {
                                    durationMillis = 400
                                    1.066f at 120 using FastOutSlowInEasing
                                    0.967f at 260
                                    1f at 400
                                },
                        )
                    }
                    launch {
                        scaleY.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                keyframes {
                                    durationMillis = 400
                                    0.945f at 120 using FastOutSlowInEasing
                                    1.033f at 260
                                    1f at 400
                                },
                        )
                    }
                }
            }
    }
    return this.graphicsLayer {
        this.scaleX = scaleX.value
        this.scaleY = scaleY.value
    }
}
