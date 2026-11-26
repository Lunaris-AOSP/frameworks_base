/*
 * Copyright (C) 2025-2026 AxionOS
 * Copyright (C) 2026 Lunaris AOSP
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

import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.service.quicksettings.Tile.STATE_UNAVAILABLE
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.qs.panels.shared.model.AxQsControl
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.shared.model.LunarisQsSliderStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberQSTileAnimationStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.rememberTileHaptic
import com.android.systemui.qs.panels.ui.compose.infinitegrid.tileToggleAnimation
import com.android.systemui.qs.panels.ui.media.AxMediaPanel
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.qs.panels.ui.viewmodel.AxQsViewModel
import com.android.systemui.qs.tiles.ringer.RingerSliderTileContent
import com.android.systemui.qs.ui.compose.borderOnFocus
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel

@Composable
private fun axString(resName: String, fallback: String): String {
    val context = LocalContext.current
    val id = remember(resName) { context.resources.getIdentifier(resName, "string", context.packageName) }
    return if (id != 0) stringResource(id) else fallback
}

@Composable
internal fun AxQsBrightnessButton(
    viewModel: BrightnessSliderViewModel,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val active = viewModel.autoMode
    AxQsButtonControl(
        description = axString("ax_qs_auto_brightness", "Auto brightness"),
        active = active,
        interactive = interactive,
        onClick = viewModel::onIconClick,
        modifier = modifier,
    ) { tint ->
        MaterialIcon(
            painter =
                painterResource(
                    if (active) {
                        R.drawable.ic_qs_brightness_auto_on
                    } else {
                        R.drawable.ic_qs_brightness_auto_off
                    }
                ),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(AxButtonControlIconSize),
        )
    }
}

@Composable
internal fun AxQsVolumeMuteButton(
    viewModel: AudioStreamSliderViewModel,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.slider.collectAsStateWithLifecycle()
    val muted = state.value <= state.valueRange.start
    AxQsButtonControl(
        description = axString("ax_qs_volume_mute", "Mute volume"),
        active = muted,
        interactive = interactive && state.isMutable,
        onClick = { viewModel.toggleMuted(state) },
        modifier = modifier,
    ) { tint ->
        state.icon?.let { icon ->
            Icon(icon = icon, tint = tint, modifier = Modifier.size(AxButtonControlIconSize))
        }
    }
}

@Composable
internal fun AxQsButtonControl(
    description: String,
    active: Boolean,
    interactive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    inSliderTile: Boolean = false,
    available: Boolean = true,
    icon: @Composable (Color) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = if (inSliderTile) animateAxQsInsetTileShape(active) else CircleShape
    val view = LocalView.current
    val hapticEnabled = inSliderTile && interactive && rememberTileHaptic()
    val animationStyle = if (inSliderTile && interactive) rememberQSTileAnimationStyle() else 0
    val state =
        when {
            !available -> STATE_UNAVAILABLE
            active -> STATE_ACTIVE
            else -> STATE_INACTIVE
        }
    val backgroundColor =
        if (inSliderTile) AxTileDefaults.sliderTileInnerBackgroundColor()
        else AxTileDefaults.backgroundColor()
    val gradient = rememberLunarisQsGradient()
    val brush = gradient.tileBrush()
    val iconOnly = inSliderTile && usesIconOnlyActiveColor(AxQsSpan.SliderTile)
    val strokeStyle = LocalLunarisQsTileStrokeStyle.current
    val foreground by
        animateColorAsState(
            targetValue =
                if (!available) AxTileColorsDefaults.unavailableTileColors().icon
                else if (active && brush != null) gradient.contentColor
                else if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
            label = "AxQsButtonForeground",
        )
    val fillAlpha =
        animateFloatAsState(
            if (active && available && !iconOnly) 1f else 0f,
            label = "AxQsButtonFill",
        )
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxSize()
                .lunarisTilePressAnimation(interactionSource, interactive && available)
                .clip(shape)
                .background(backgroundColor)
                .lunarisQsInsetBackground(
                    brush = brush ?: SolidColor(MaterialTheme.colorScheme.primary),
                    shape = shape,
                    insetWidth =
                        if (strokeStyle)
                            LocalLunarisQsTileStrokeWidth.current * LocalTileScale.current
                        else 0.dp,
                    alpha = { fillAlpha.value },
                )
                .lunarisQsCosTileBorder(
                    shape,
                    enabled =
                        LocalLunarisQsCosTileBackground.current &&
                            available &&
                            (!inSliderTile || strokeStyle),
                )
                .borderOnFocus(MaterialTheme.colorScheme.secondary, shape.topEnd)
                .toggleable(
                    value = active,
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    enabled = interactive && available,
                    role = Role.Switch,
                    onValueChange = {
                        if (hapticEnabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        onClick()
                    },
                )
                .semantics { contentDescription = description },
    ) {
        val iconBackground =
            if (iconOnly) {
                Modifier.fillMaxSize(0.70f).lunarisQsAnimatedIconBackground(
                    color =
                        if (active && available) MaterialTheme.colorScheme.primary
                        else Color.Transparent,
                    brush = brush.takeIf { active && available },
                )
            } else Modifier
        Box(
            iconBackground.tileToggleAnimation(animationStyle, state),
            contentAlignment = Alignment.Center,
        ) {
            icon(foreground)
        }
    }
}

private val AxButtonControlIconSize = 24.dp

@Composable
fun AxQsControlPreview(
    control: AxQsControl,
    span: AxQsSpan,
    maxColumns: Int,
    sliderStyle: LunarisQsSliderStyle,
    brightnessViewModel: BrightnessSliderViewModel,
    volumeViewModel: AudioStreamSliderViewModel? = null,
    mediaViewModel: AxMediaViewModel,
    axQsViewModel: AxQsViewModel,
    modifier: Modifier = Modifier,
) {
    when (control) {
        AxQsControl.APP_WIDGET,
        AxQsControl.APP_WIDGET_2,
        AxQsControl.APP_WIDGET_3 ->
            LunarisQsWidgetCard(control = control, modifier = modifier.fillMaxSize())
        AxQsControl.PHOTO -> LunarisQsPhotoCard(modifier = modifier.fillMaxSize())
        AxQsControl.BRIGHTNESS_TILE ->
            LunarisBrightnessTile(
                brightnessViewModel = brightnessViewModel,
                axQsViewModel = axQsViewModel,
                sliderStyle = sliderStyle,
                interactive = false,
                modifier = modifier,
            )
        AxQsControl.VOLUME_TILE ->
            LunarisVolumeTile(volumeViewModel, sliderStyle, interactive = false, modifier = modifier)
        AxQsControl.FLASHLIGHT ->
            LunarisFlashlightSlider(
                vertical = span.columns == 1,
                sliderStyle = sliderStyle,
                interactive = false,
                modifier = modifier,
            )
        AxQsControl.BRIGHTNESS,
        AxQsControl.VOLUME ->
            AxQsSliderPreview(
                control = control,
                span = span,
                sliderStyle = sliderStyle,
                brightnessViewModel = brightnessViewModel,
                volumeViewModel = volumeViewModel,
                modifier = modifier,
            )
        AxQsControl.AUTO_BRIGHTNESS ->
            Box(modifier, contentAlignment = Alignment.Center) {
                AxQsBrightnessButton(
                    viewModel = brightnessViewModel,
                    interactive = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        AxQsControl.VOLUME_MUTE ->
            Box(modifier, contentAlignment = Alignment.Center) {
                if (volumeViewModel != null) {
                    AxQsVolumeMuteButton(
                        viewModel = volumeViewModel,
                        interactive = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        AxQsControl.RINGER ->
            RingerSliderTileContent(
                interactable = false,
                compact = span == AxQsSpan.TileDefault,
                vertical = span.columns == 1,
                modifier = modifier.fillMaxSize(),
            )
        AxQsControl.MEDIA ->
            AxMediaPanel(
                viewModel = mediaViewModel,
                span = span,
                modifier = modifier.fillMaxSize(),
                showPlaceholder = true,
                interactive = false,
            )
    }
}
