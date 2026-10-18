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
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.service.quicksettings.Tile.STATE_UNAVAILABLE
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.trace
import com.android.compose.animation.Expandable
import com.android.compose.animation.bounceable
import com.android.compose.animation.rememberExpandableController
import com.android.compose.animation.scene.ContentScope
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.LocalAndroidColorScheme
import com.android.mechanics.compose.modifier.verticalFadeContentReveal
import com.android.mechanics.compose.modifier.verticalTactileSurfaceReveal
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.composefragment.mist.LocalBlurEnabled
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.ui.compose.AxTileDefaults.InactiveCornerRadius
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelMoreDetails
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.longPressLabelSettings
import com.android.systemui.qs.panels.ui.compose.infinitegrid.DualTargetTileStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import com.android.systemui.qs.panels.ui.compose.infinitegrid.TileColors
import com.android.systemui.qs.panels.ui.compose.infinitegrid.bounceScale
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberDualTargetTileStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberTileHaptic
import com.android.systemui.qs.panels.ui.compose.infinitegrid.verticalSquish
import com.android.systemui.qs.panels.ui.viewmodel.AccessibilityUiState
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconProvider
import com.android.systemui.qs.panels.ui.viewmodel.TileUiState
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toIconProvider
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.ui.composable.QuickSettingsShade
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun ContentScope.AxTile(
    tile: TileViewModel,
    iconOnly: Boolean,
    squishiness: () -> Float,
    coroutineScope: CoroutineScope,
    bounceableInfo: BounceableInfo?,
    interactionSource: MutableInteractionSource?,
    modifier: Modifier = Modifier,
    isVisible: () -> Boolean = { true },
    requestToggleTextFeedback: (TileSpec) -> Unit = {},
    detailsViewModel: DetailsViewModel?,
    enableRevealEffect: Boolean = false,
    span: AxQsSpan = if (iconOnly) AxQsSpan.TileDefault else AxQsSpan.TileWideDefault,
    fillHeight: Boolean = span.rows > 1,
    compactIconSize: Dp = AxTileDefaults.IconSize,
    tileShapeOverride: RoundedCornerShape? = null,
    deviceArtwork: Drawable? = null,
) {
    trace(tile.spec.spec) {
        val compact = span.columns == 1
        val currentBounceableInfo by rememberUpdatedState(bounceableInfo)
        val res = axResources()

        val uiState by
            produceState(tile.currentState.toUiState(res), tile, res) {
                tile.state.collect { value = it.toUiState(res) }
            }
        val isClickable = uiState.state != STATE_UNAVAILABLE
        val pressInteractionSource =
            interactionSource ?: remember(tile) { MutableInteractionSource() }
        val toggleInteractionSource = remember(tile) { MutableInteractionSource() }

        val icon by
            produceState(tile.currentState.toIconProvider(), tile) {
                tile.state.collect { value = it.toIconProvider() }
            }

        val dualTargetTileStyle = rememberDualTargetTileStyle()
        val useDualTargetStyle =
            dualTargetTileStyle == DualTargetTileStyle.DUAL &&
                !compact &&
                span.rows == 1 &&
                uiState.handlesSecondaryClick
        val colors =
            AxTileColorsDefaults.getColorForState(
                uiState = uiState,
                iconOnly = compact,
                useDualTargetStyle = useDualTargetStyle,
            )
        val view = LocalView.current
        val hapticEnabled = rememberTileHaptic()

        val defaultTileShape = animateAxQsTileShape(span, uiState.state)
        val tileShape = tileShapeOverride ?: defaultTileShape
        val animatedColor by animateColorAsState(colors.background, label = "QSTileBackgroundColor")
        val isDualTarget = uiState.handlesSecondaryClick

        val surfaceRevealModifier: Modifier
        val contentRevealModifier: Modifier
        if (enableRevealEffect) {
            val marginBottom =
                with(LocalDensity.current) { QuickSettingsShade.Dimensions.Padding.toPx() }
            surfaceRevealModifier =
                Modifier.verticalTactileSurfaceReveal(deltaY = marginBottom, label = tile.spec.spec)
            contentRevealModifier =
                Modifier.verticalFadeContentReveal(deltaY = marginBottom, label = tile.spec.spec)
        } else {
            surfaceRevealModifier = Modifier
            contentRevealModifier = Modifier
        }

        AxTileExpandable(
            color = { animatedColor },
            shape = tileShape,
            squishiness = squishiness,
            modifier =
                modifier
                    .then(surfaceRevealModifier)
                    .fillMaxWidth()
                    .lunarisTilePressAnimation(
                        interactionSource = pressInteractionSource,
                        secondaryInteractionSource = toggleInteractionSource,
                        enabled = isClickable,
                    )
                    .thenIf(currentBounceableInfo != null) {
                        Modifier.bounceable(
                            currentBounceableInfo!!.bounceable,
                            currentBounceableInfo!!.previousTile,
                            currentBounceableInfo!!.nextTile,
                            orientation = Orientation.Horizontal,
                            bounceEnd = currentBounceableInfo!!.bounceEnd,
                        )
                    }
                    .borderOnFocus(color = MaterialTheme.colorScheme.secondary, tileShape.topEnd),
        ) { expandable ->
            val useLongClickToSettings = !(compact && isDualTarget && isClickable)
            val longClick: (() -> Unit)? =
                {
                    if (hapticEnabled) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }

                    if (useLongClickToSettings) {
                        tile.settingsClick(expandable)
                    } else {
                        tile.mainClick(expandable)
                    }
                }
                .takeIf { !useLongClickToSettings || uiState.handlesLongClick }

            val bounceContainer = uiState.isToggleable && (compact || !isDualTarget)
            val contentBounceable =
                remember(currentBounceableInfo) {
                    currentBounceableInfo?.bounceable ?: BounceableTileViewModel()
                }
            AxTileContainer(
                interactionSource = pressInteractionSource,
                onClick = onClick@{
                    if (!isClickable) return@onClick

                    if (hapticEnabled) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }

                    val hasDetails =
                        QsDetailedView.isEnabled &&
                            detailsViewModel?.onTileClicked(tile.spec) == true
                    if (hasDetails) return@onClick

                    if (compact && isDualTarget) {
                        tile.toggleClick()
                    } else {
                        tile.mainClick(expandable)
                    }

                    coroutineScope.launch {
                        if (!bounceContainer) {
                            currentBounceableInfo?.bounceable?.animateContentBounce(compact)
                        }
                    }
                    if (uiState.isToggleable && compact) {
                        requestToggleTextFeedback(tile.spec)
                    }
                },
                onLongClick = longClick,
                accessibilityUiState = uiState.accessibilityUiState,
                iconOnly = compact,
                isDualTarget = isDualTarget,
                fillHeight = fillHeight,
                modifier =
                    contentRevealModifier.thenIf(colors.backgroundGradient != null) {
                        Modifier.drawBehind { drawRect(brush = colors.backgroundGradient!!) }
                    },
            ) {
                val iconProvider: Context.() -> Icon = { getAxTileIcon(icon = icon) }
                AnimatedContent(
                    targetState = compact,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "AxTileLayout",
                    modifier = Modifier.fillMaxSize(),
                ) { isCompact ->
                    Box(Modifier.fillMaxSize()) {
                        if (isCompact) {
                            SmallTileContent(
                                iconProvider = iconProvider,
                                color = colors.icon,
                                size = { compactIconSize },
                                modifier =
                                    Modifier.align(Alignment.Center).bounceScale {
                                        contentBounceable.iconBounceScale
                                    },
                            )
                        } else {
                            val iconShape =
                                if (useDualTargetStyle) {
                                    tileShape
                                } else {
                                    AxTileColorsDefaults.animateIconShapeAsState(uiState.state).value
                                }
                            val secondaryClick: (() -> Unit)? =
                                {
                                    if (hapticEnabled) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                    tile.toggleClick()
                                }
                                .takeIf { isDualTarget && isClickable }
                            AxLargeTileContent(
                                label = uiState.label,
                                secondaryLabel = uiState.secondaryLabel,
                                iconProvider = iconProvider,
                                sideDrawable = uiState.sideDrawable,
                                showDialogIndicator = uiState.showDialogIndicator,
                                deviceArtwork = deviceArtwork.takeIf { uiState.state == STATE_ACTIVE },
                                colors = colors,
                                iconShape = iconShape,
                                tileState = uiState.state,
                                toggleClick = secondaryClick,
                                toggleInteractionSource = toggleInteractionSource,
                                onLongClick = longClick,
                                accessibilityUiState = uiState.accessibilityUiState,
                                squishiness = squishiness,
                                span = span,
                                isDualTarget = isDualTarget,
                                isVisible = isVisible,
                                textScale = { contentBounceable.textBounceScale },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AxTileExpandable(
    color: () -> Color,
    shape: Shape,
    squishiness: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable (com.android.systemui.animation.Expandable) -> Unit,
) {
    Expandable(
        controller = rememberExpandableController(color = color, shape = shape),
        modifier = modifier.clip(shape).verticalSquish(squishiness),
        useModifierBasedImplementation = true,
        content = content,
    )
}

@Composable
fun AxTileContainer(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    accessibilityUiState: AccessibilityUiState,
    iconOnly: Boolean,
    isDualTarget: Boolean,
    interactionSource: MutableInteractionSource?,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val tileHeight = AxTileDefaults.TileHeight * LocalTileScale.current
    Box(
        modifier =
            modifier
                .thenIf(fillHeight) { Modifier.fillMaxHeight() }
                .thenIf(!fillHeight) { Modifier.height(tileHeight) }
                .fillMaxWidth()
                .axTileCombinedClickable(
                    onClick = onClick ?: {},
                    onLongClick = onLongClick,
                    accessibilityUiState = accessibilityUiState,
                    interactionSource = interactionSource,
                    iconOnly = iconOnly,
                    isDualTarget = isDualTarget,
                ),
        content = content,
    )
}

@Composable
private fun Modifier.axTileCombinedClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    accessibilityUiState: AccessibilityUiState,
    interactionSource: MutableInteractionSource?,
    iconOnly: Boolean,
    isDualTarget: Boolean,
): Modifier {
    val longPressLabel =
        if (iconOnly && isDualTarget) longPressLabelMoreDetails() else longPressLabelSettings()
    return combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onClickLabel = accessibilityUiState.clickLabel,
            onLongClickLabel = longPressLabel,
            hapticFeedbackEnabled = false,
            interactionSource = interactionSource,
        )
        .semantics {
            val accessibilityRole =
                if (iconOnly && isDualTarget) {
                    Role.Switch
                } else {
                    accessibilityUiState.accessibilityRole
                }
            if (accessibilityRole == Role.Switch) {
                accessibilityUiState.toggleableState?.let { toggleableState = it }
            }
            role = accessibilityRole
            stateDescription = accessibilityUiState.stateDescription
        }
        .thenIf(iconOnly) {
            Modifier.semantics { contentDescription = accessibilityUiState.contentDescription }
        }
}

private fun Context.getAxTileIcon(icon: IconProvider): Icon {
    return icon.icon?.let {
        if (it is QSTileImpl.ResourceIcon) {
            Icon.Resource(it.resId, null)
        } else {
            Icon.Loaded(it.getDrawable(this), null)
        }
    } ?: Icon.Resource(R.drawable.ic_error_outline, null)
}

object AxTileColorsDefaults {
    val ActiveIconCornerRadius = 16.dp
    val ActiveTileCornerRadius = 24.dp

    @Composable
    @ReadOnlyComposable
    fun backgroundTileColors(): Color {
        val blurEnabled = LocalBlurEnabled.current
        return if (blurEnabled) {
            LocalAndroidColorScheme.current.surfaceEffect1
        } else {
            MaterialTheme.colorScheme.surfaceBright
        }
    }

    @Composable
    fun activeTileColors(): TileColors {
        val gradient = rememberLunarisQsGradient()
        val brush = gradient.tileBrush()
        val foreground =
            if (brush != null) gradient.contentColor else MaterialTheme.colorScheme.onPrimary
        return TileColors(
            background = MaterialTheme.colorScheme.primary,
            iconBackground = Color.Transparent,
            label = foreground,
            secondaryLabel = foreground,
            icon = foreground,
            outline = MaterialTheme.colorScheme.primary,
            backgroundGradient = brush,
        )
    }

    @Composable
    @ReadOnlyComposable
    fun inactiveTileColors(): TileColors =
        TileColors(
            background = backgroundTileColors(),
            iconBackground = Color.Transparent,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
            outline = MaterialTheme.colorScheme.onSurface,
        )

    @Composable
    fun activeDualTargetTileColors(): TileColors {
        val gradient = rememberLunarisQsGradient()
        val brush = gradient.tileBrush()
        return TileColors(
            background = backgroundTileColors(),
            iconBackground = MaterialTheme.colorScheme.primary,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon =
                if (brush != null) gradient.contentColor else MaterialTheme.colorScheme.onPrimary,
            outline = MaterialTheme.colorScheme.primary,
            iconBackgroundGradient = brush,
        )
    }

    @Composable
    @ReadOnlyComposable
    fun inactiveDualTargetTileColors(): TileColors =
        TileColors(
            background = backgroundTileColors(),
            iconBackground = LocalAndroidColorScheme.current.surfaceEffect2,
            label = MaterialTheme.colorScheme.onSurface,
            secondaryLabel = MaterialTheme.colorScheme.onSurface,
            icon = MaterialTheme.colorScheme.onSurface,
            outline = MaterialTheme.colorScheme.primary,
        )

    @Composable
    @ReadOnlyComposable
    fun unavailableTileColors(): TileColors {
        val blurEnabled = LocalBlurEnabled.current
        if (blurEnabled) {
            val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = .18f)
            val onSurfaceVariantColor =
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
            return TileColors(
                background = surfaceColor,
                iconBackground = surfaceColor,
                label = onSurfaceVariantColor,
                secondaryLabel = onSurfaceVariantColor,
                icon = onSurfaceVariantColor,
                outline = onSurfaceVariantColor,
            )
        } else {
            val bgColor = MaterialTheme.colorScheme.surfaceBright.copy(alpha = .38f)
            val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
            return TileColors(
                background = bgColor,
                iconBackground = bgColor,
                label = textColor,
                secondaryLabel = textColor,
                icon = textColor,
                outline = textColor,
            )
        }
    }

    @Composable
    fun getColorForState(
        uiState: TileUiState,
        iconOnly: Boolean,
        useDualTargetStyle: Boolean = false,
    ): TileColors {
        return when (uiState.state) {
            STATE_ACTIVE ->
                if (useDualTargetStyle && uiState.handlesSecondaryClick && !iconOnly) {
                    activeDualTargetTileColors()
                } else {
                    activeTileColors()
                }
            STATE_INACTIVE ->
                if (useDualTargetStyle && uiState.handlesSecondaryClick && !iconOnly) {
                    inactiveDualTargetTileColors()
                } else {
                    inactiveTileColors()
                }
            else -> unavailableTileColors()
        }
    }

    @Composable
    fun animateIconShapeAsState(state: Int): State<RoundedCornerShape> {
        return animateShapeAsState(
            state = state,
            activeCornerRadius = ActiveIconCornerRadius,
            label = "AxQSTileCornerRadius",
        )
    }

    @Composable
    fun animateTileShapeAsState(state: Int): State<RoundedCornerShape> {
        return animateShapeAsState(
            state = state,
            activeCornerRadius = ActiveTileCornerRadius,
            label = "AxQSTileIconCornerRadius",
        )
    }

    @Composable
    fun animateShapeAsState(
        state: Int,
        activeCornerRadius: Dp,
        label: String,
    ): State<RoundedCornerShape> {
        val animatedCornerRadius by
            animateDpAsState(
                targetValue =
                    if (state == STATE_ACTIVE) {
                        activeCornerRadius
                    } else {
                        InactiveCornerRadius
                    },
                label = label,
            )

        return remember {
            val corner =
                object : CornerSize {
                    override fun toPx(shapeSize: Size, density: Density): Float {
                        return with(density) { animatedCornerRadius.toPx() }
                    }
                }
            mutableStateOf(RoundedCornerShape(corner))
        }
    }
}

@Composable
@ReadOnlyComposable
private fun axResources(): Resources {
    LocalConfiguration.current
    return LocalResources.current
}
