/*
 * Copyright 2025-2026 AxionOS
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

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
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
import com.android.systemui.qs.panels.shared.model.AxQsVerticalSliderStyle
import com.android.systemui.qs.panels.ui.media.AxMediaPanel
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.qs.tiles.ringer.RingerSliderTileContent
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
private fun AxQsButtonControl(
    description: String,
    active: Boolean,
    interactive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val background by
        animateColorAsState(
            targetValue =
                if (active) MaterialTheme.colorScheme.primary else AxTileDefaults.backgroundColor(),
            label = "AxQsButtonBackground",
        )
    val foreground by
        animateColorAsState(
            targetValue =
                if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
            label = "AxQsButtonForeground",
        )
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(background)
                .clickable(enabled = interactive, role = Role.Switch, onClick = onClick)
                .semantics { contentDescription = description },
    ) {
        icon(foreground)
    }
}

private val AxButtonControlIconSize = 24.dp

@Composable
fun AxQsControlPreview(
    control: AxQsControl,
    span: AxQsSpan,
    maxColumns: Int,
    verticalSliderStyle: AxQsVerticalSliderStyle,
    brightnessViewModel: BrightnessSliderViewModel,
    volumeViewModel: AudioStreamSliderViewModel? = null,
    mediaViewModel: AxMediaViewModel,
    modifier: Modifier = Modifier,
) {
    when (control) {
        AxQsControl.BRIGHTNESS,
        AxQsControl.BRIGHTNESS_HORIZONTAL,
        AxQsControl.VOLUME,
        AxQsControl.VOLUME_HORIZONTAL ->
            AxQsSliderPreview(
                control = control,
                verticalStyle = verticalSliderStyle,
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
                shape = axQsControlShape(AxQsControl.RINGER, span),
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
