/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.qs.panels.ui.compose

import android.content.Context
import android.graphics.drawable.Drawable
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.compose.ui.graphics.painter.rememberDrawablePainter
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.composefragment.mist.LocalBlurEnabled
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.shared.model.AxQsTileStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelSettings
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LargeTileLabels
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import com.android.systemui.qs.panels.ui.compose.infinitegrid.TileColors
import com.android.systemui.qs.panels.ui.compose.infinitegrid.bounceScale
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.ui.compose.borderOnFocus

val LocalTileScale = staticCompositionLocalOf { 1f }
val LocalAxQsTileStyle = staticCompositionLocalOf { AxQsTileStyle.PILL }

@Composable
fun computeTileScale(): Float {
    val sw = LocalConfiguration.current.smallestScreenWidthDp
    return (sw / 411f).coerceAtMost(1f)
}

object AxTileDefaults {
    val LargeIconSize = 26.dp
    val IconSize = 24.dp
    val TileHeight = 72.dp
    val TileSpacing = 20.dp
    val InactiveCornerRadius = 50.dp
    val DividerWidth = 1.dp
    val DividerHeight = 16.dp
    val DividerLabelSpacing = 10.dp
    val LargeTileStartPadding = 16.dp
    val LargeTileEndPadding = 16.dp

    @Composable
    fun dividerColor(state: Int): Color {
        return when (state) {
            STATE_ACTIVE -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
            STATE_INACTIVE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
        }
    }

    @Composable
    fun backgroundColor(): Color {
        return if (LocalBlurEnabled.current) {
            LocalAndroidColorScheme.current.surfaceEffect1
        } else {
            MaterialTheme.colorScheme.surfaceBright
        }
    }
}

private fun usesPillShape(span: AxQsSpan, style: AxQsTileStyle, state: Int): Boolean =
    span.rows == 1 && style.usesPillShape(isActive = state == STATE_ACTIVE)

internal fun axQsTileShape(
    span: AxQsSpan,
    style: AxQsTileStyle = AxQsTileStyle.PILL,
    state: Int = STATE_INACTIVE,
): RoundedCornerShape =
    if (usesPillShape(span, style, state)) CircleShape
    else RoundedCornerShape(AxQsControlCornerRadius)

@Composable
internal fun animateAxQsTileShape(span: AxQsSpan, state: Int): RoundedCornerShape {
    val pillProgress by animateFloatAsState(
        targetValue = if (usesPillShape(span, LocalAxQsTileStyle.current, state)) 1f else 0f,
        label = "AxQsTileShape",
    )
    return remember(pillProgress) {
        val corner = object : CornerSize {
            override fun toPx(shapeSize: Size, density: Density): Float {
                val pillRadius = shapeSize.minDimension / 2f
                val squareRadius = with(density) { AxQsControlCornerRadius.toPx() }
                    .coerceAtMost(pillRadius)
                return squareRadius + (pillRadius - squareRadius) * pillProgress.coerceIn(0f, 1f)
            }
        }
        RoundedCornerShape(corner)
    }
}

@Composable
fun AxLargeTileContent(
    label: String,
    secondaryLabel: String?,
    iconProvider: Context.() -> Icon,
    sideDrawable: Drawable?,
    colors: TileColors,
    squishiness: () -> Float,
    tileState: Int,
    span: AxQsSpan,
    isDualTarget: Boolean,
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
    accessibilityUiState: AccessibilityUiState? = null,
    iconShape: RoundedCornerShape = RoundedCornerShape(AxTileDefaults.InactiveCornerRadius),
    textScale: () -> Float = { 1f },
    toggleClick: (() -> Unit)? = null,
    toggleInteractionSource: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    showDialogIndicator: Boolean = false,
    deviceArtwork: Drawable? = null,
) {
    val animatedIconBackgroundColor by
        animateColorAsState(
            targetValue = colors.iconBackground,
            label = "AxQsDualTargetIconBackgroundColor",
        )

    if (span.rows == 1) {
        val useDualTargetStyle = isDualTarget && colors.iconBackground != Color.Transparent
        val scale = LocalTileScale.current
        val iconTargetModifier =
            if (useDualTargetStyle) {
                Modifier.size(CommonTileDefaults.ToggleTargetSize * scale)
            } else {
                Modifier.fillMaxHeight().width(48.dp)
            }
        val longPressLabel = longPressLabelSettings().takeIf { onLongClick != null }
        val focusBorderColor = MaterialTheme.colorScheme.secondary
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                modifier.padding(
                    start = 8.dp * scale,
                    end = AxTileDefaults.LargeTileEndPadding * scale,
                ),
        ) {
            Box(
                modifier =
                    iconTargetModifier
                        .thenIf(useDualTargetStyle) {
                            Modifier.clip(iconShape)
                                .background(
                                    colors.iconBackgroundGradient
                                        ?: SolidColor(animatedIconBackgroundColor),
                                    iconShape,
                                )
                        }
                        .thenIf(toggleClick != null) {
                            Modifier.borderOnFocus(color = focusBorderColor, iconShape.topEnd)
                                .combinedClickable(
                                    interactionSource = toggleInteractionSource,
                                    onClick = toggleClick!!,
                                    onLongClick = onLongClick,
                                    onLongClickLabel = longPressLabel,
                                    hapticFeedbackEnabled = false,
                                )
                        },
                contentAlignment = Alignment.Center,
            ) {
                SmallTileContent(
                    iconProvider = iconProvider,
                    color = colors.icon,
                    size = { AxTileDefaults.LargeIconSize * scale },
                )
            }

            if (isDualTarget && !useDualTargetStyle) {
                Box(
                    Modifier.width(AxTileDefaults.DividerWidth)
                        .height(AxTileDefaults.DividerHeight * scale)
                        .background(AxTileDefaults.dividerColor(tileState))
                )
            }
            Spacer(Modifier.width(AxTileDefaults.DividerLabelSpacing * scale))

            LargeTileLabels(
                label = label,
                secondaryLabel = secondaryLabel,
                colors = colors,
                accessibilityUiState = accessibilityUiState,
                isVisible = isVisible,
                modifier =
                    Modifier.weight(1f)
                        .bounceScale(TransformOrigin(0f, .5f), textScale),
            )
        }
        return
    }

    val scale = LocalTileScale.current
    val layoutDirection = LocalLayoutDirection.current
    val textOrigin = if (layoutDirection == LayoutDirection.Ltr) 0f else 1f

    Column(
        modifier =
            modifier.padding(
                start = AxTileDefaults.LargeTileStartPadding * scale,
                top = AxTileDefaults.LargeTileEndPadding * scale,
                end = AxTileDefaults.LargeTileEndPadding * scale,
                bottom = AxTileDefaults.LargeTileEndPadding * scale,
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AxLargeTileIcon(
                iconProvider = iconProvider,
                colors = colors,
                scale = scale,
                toggleClick = toggleClick,
                toggleInteractionSource = toggleInteractionSource,
                onLongClick = onLongClick,
                deviceArtwork = deviceArtwork,
                modifier = Modifier.size(CommonTileDefaults.ToggleTargetSize),
            )
            if (showDialogIndicator || sideDrawable != null) {
                val indicatorModifier =
                    Modifier.size(32.dp * scale)
                        .background(colors.icon.copy(alpha = ICON_CHIP_ALPHA), CircleShape)
                        .padding(8.dp * scale)
                if (sideDrawable != null) {
                    Image(
                        painter = rememberDrawablePainter(sideDrawable),
                        contentDescription = null,
                        modifier = indicatorModifier,
                    )
                } else {
                    MaterialIcon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = colors.icon,
                        modifier = indicatorModifier,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        LargeTileLabels(
            label = label,
            secondaryLabel = secondaryLabel,
            colors = colors,
            accessibilityUiState = accessibilityUiState,
            isVisible = isVisible,
            modifier =
                Modifier.fillMaxWidth()
                    .bounceScale(TransformOrigin(textOrigin, 1f), textScale),
        )
    }
}

@Composable
private fun AxLargeTileIcon(
    iconProvider: Context.() -> Icon,
    colors: TileColors,
    scale: Float,
    toggleClick: (() -> Unit)?,
    toggleInteractionSource: MutableInteractionSource?,
    onLongClick: (() -> Unit)?,
    deviceArtwork: Drawable?,
    modifier: Modifier = Modifier,
) {
    val longPressLabel = longPressLabelSettings().takeIf { onLongClick != null }
    val focusBorderColor = MaterialTheme.colorScheme.secondary
    val chipColor by
        animateColorAsState(
            targetValue =
                if (deviceArtwork != null) {
                    Color.White
                } else if (colors.iconBackground == Color.Transparent) {
                    colors.icon.copy(alpha = ICON_CHIP_ALPHA)
                } else {
                    colors.iconBackground
                },
            label = "AxLargeTileIconBackground",
        )
    Box(
        modifier =
            modifier.background(chipColor, CircleShape).thenIf(toggleClick != null) {
                Modifier.borderOnFocus(color = focusBorderColor, CircleShape.topEnd)
                    .combinedClickable(
                        interactionSource = toggleInteractionSource,
                        onClick = toggleClick!!,
                        onLongClick = onLongClick,
                        onLongClickLabel = longPressLabel,
                        hapticFeedbackEnabled = false,
                    )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (deviceArtwork != null) {
            Image(
                painter = rememberDrawablePainter(deviceArtwork),
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(CircleShape),
            )
        } else {
            SmallTileContent(
                iconProvider = iconProvider,
                color = colors.icon,
                size = { AxTileDefaults.LargeIconSize * scale },
            )
        }
    }
}

private const val ICON_CHIP_ALPHA = 0.12f
