/*
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

import android.media.AudioManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.shared.model.LunarisQsSliderStyle
import com.android.systemui.qs.panels.ui.compose.infinitegrid.SmallTileContent
import com.android.systemui.qs.panels.ui.viewmodel.AxQsViewModel
import com.android.systemui.qs.tiles.ringer.LocalRingerSliderViewModel
import com.android.systemui.res.R
import com.android.systemui.utils.PolicyRestriction
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import kotlinx.coroutines.launch

@Composable
internal fun LunarisBrightnessTile(
    brightnessViewModel: BrightnessSliderViewModel,
    axQsViewModel: AxQsViewModel,
    sliderStyle: LunarisQsSliderStyle,
    interactive: Boolean = true,
    entranceProgress: () -> Float = { 1f },
    modifier: Modifier = Modifier,
) {
    val darkTheme by axQsViewModel.darkTheme.collectAsStateWithLifecycle(initialValue = null)
    val restriction by
        brightnessViewModel.policyRestriction.collectAsStateWithLifecycle(
            initialValue = PolicyRestriction.NoRestriction
        )
    val overridden by brightnessViewModel.brightnessOverriddenByWindow.collectAsStateWithLifecycle()
    val hasAutoBrightness =
        booleanResource(com.android.internal.R.bool.config_automatic_brightness_available)
    val scope = rememberCoroutineScope()
    LunarisSliderTile(
        modifier = modifier,
        interactive = interactive,
        slider = {
            AxQsBrightnessControl(
                vertical = true,
                viewModel = brightnessViewModel,
                sliderStyle = sliderStyle,
                entranceProgress = entranceProgress,
                interactive = interactive,
                longPressEnabled = false,
                inactiveTrackColor = AxTileDefaults.sliderTileInnerBackgroundColor(),
                modifier = Modifier.fillMaxSize(),
            )
        },
    ) { buttonSize ->
        val theme = darkTheme
        val dark = theme?.isNightMode == true
        LunarisSliderTileButton(
            icon =
                if (dark) R.drawable.qs_light_dark_theme_icon_on
                else R.drawable.qs_light_dark_theme_icon_off,
            label = R.string.quick_settings_ui_mode_night_label,
            active = dark,
            available = theme?.isPowerSave == false,
            interactive = interactive,
            size = buttonSize,
            onClick = { if (theme != null) scope.launch { axQsViewModel.toggleDarkTheme(theme) } },
        )
        LunarisSliderTileButton(
            icon = R.drawable.ic_empty_shade_wb_sunny,
            label = R.string.quick_settings_autobrightness_label,
            active = brightnessViewModel.autoMode,
            available =
                hasAutoBrightness && !overridden && restriction !is PolicyRestriction.Restricted,
            interactive = interactive,
            size = buttonSize,
            onClick = brightnessViewModel::onIconClick,
        )
    }
}

@Composable
internal fun LunarisVolumeTile(
    volumeViewModel: AudioStreamSliderViewModel?,
    sliderStyle: LunarisQsSliderStyle,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val ringer = LocalRingerSliderViewModel.current
    val mode by
        ringer.ringerModeChanges.collectAsStateWithLifecycle(initialValue = ringer.currentMode)
    val zenMuted by ringer.isZenMuted.collectAsStateWithLifecycle()
    LunarisSliderTile(
        modifier = modifier,
        interactive = interactive,
        slider = {
            if (volumeViewModel != null) {
                AxQsVolumeControl(
                    vertical = true,
                    viewModel = volumeViewModel,
                    sliderStyle = sliderStyle,
                    interactive = interactive,
                    longPressEnabled = false,
                    inactiveTrackColor = AxTileDefaults.sliderTileInnerBackgroundColor(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
    ) { buttonSize ->
        for (target in RingerButtonModes) {
            val silent = target == AudioManager.RINGER_MODE_SILENT
            LunarisSliderTileButton(
                icon =
                    if (silent) R.drawable.ic_volume_ringer_mute
                    else R.drawable.ic_volume_ringer_vibrate,
                label =
                    if (silent) R.string.quick_settings_sound_silent
                    else R.string.quick_settings_sound_vibrate,
                active = mode == target,
                available = !zenMuted && ringer.availableModes.any { it.mode == target },
                interactive = interactive,
                size = buttonSize,
                onClick = {
                    ringer.setRingerMode(
                        if (ringer.currentMode == target) AudioManager.RINGER_MODE_NORMAL
                        else target
                    )
                },
            )
        }
    }
}

private val RingerButtonModes =
    listOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE)

@Composable
private fun LunarisSliderTile(
    interactive: Boolean,
    modifier: Modifier,
    slider: @Composable () -> Unit,
    buttons: @Composable (Dp) -> Unit,
) {
    val shape = axQsTileShape(AxQsSpan.SliderTile, LocalAxQsTileStyle.current)
    BoxWithConstraints(
        modifier.fillMaxSize()
            .clip(shape)
            .background(AxTileDefaults.backgroundColor())
            .lunarisQsCosTileBorder(shape)
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {}),
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight)
        val inset = side * 0.075f
        val gap = side * 0.06f
        val buttonSize = (side - inset * 2 - gap) / 2
        Row(
            modifier = Modifier.height(side).padding(vertical = inset),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Box(Modifier.width(buttonSize).fillMaxHeight()) { slider() }
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                buttons(buttonSize)
            }
        }
        if (!interactive) {
            Box(Modifier.matchParentSize().pointerInput(Unit) {})
        }
    }
}

@Composable
private fun LunarisSliderTileButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    active: Boolean,
    available: Boolean,
    interactive: Boolean,
    size: Dp,
    onClick: () -> Unit,
) {
    AxQsButtonControl(
        description = stringResource(label),
        active = active,
        available = available,
        interactive = interactive,
        inSliderTile = true,
        onClick = onClick,
        modifier = Modifier.size(size),
    ) { tint ->
        SmallTileContent(
            iconProvider = { Icon.Resource(icon, null) },
            color = tint,
            size = { size * 0.48f },
        )
    }
}
