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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.packageinstaller.v2.ui.compose

import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.packageinstaller.R
import com.android.packageinstaller.v2.model.InstallAppDetails

@Composable
internal fun InstallerBottomSheetContent(
    state: InstallerDialogState,
    enabled: Boolean,
    onAction: (InstallerAction) -> Unit,
    extraContent: @Composable (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().semantics { paneTitle = state.title },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f, fill = false).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SheetHeader(state)
            if (state.busy) {
                SheetProgress(state.progress)
            }
            state.details?.let { SheetAppDetails(it) }
            state.message?.let {
                InstallerMessage(it, Modifier.fillMaxWidth(), TextAlign.Center)
            }
            extraContent(enabled)
        }
        if (state.primary != null || state.secondary != null) {
            SheetActions(state, enabled, onAction)
        }
    }
}

@Composable
private fun SheetHeader(state: InstallerDialogState) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when (state.tone) {
            InstallerTone.Normal -> colors.primaryContainer
            InstallerTone.Success -> colors.tertiaryContainer
            InstallerTone.Error -> colors.errorContainer
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "sheet status color",
    )
    val iconSize = with(LocalDensity.current) { 64.dp.roundToPx() }
    val bitmap = remember(state.appIcon, iconSize) {
        state.appIcon?.toBitmap(iconSize, iconSize)?.asImageBitmap()
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            modifier = Modifier.size(88.dp),
            shape = RoundedCornerShape(28.dp),
            color = container,
        ) {
            Crossfade(
                targetState = state.tone,
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "sheet status icon",
            ) { tone ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (tone == InstallerTone.Normal && bitmap != null) {
                        Image(bitmap, contentDescription = null, modifier = Modifier.size(64.dp))
                    } else {
                        Icon(
                            when (tone) {
                                InstallerTone.Normal -> Icons.Rounded.Download
                                InstallerTone.Success -> Icons.Rounded.Check
                                InstallerTone.Error -> Icons.Rounded.ErrorOutline
                            },
                            contentDescription = null,
                            tint = when (tone) {
                                InstallerTone.Normal -> colors.onPrimaryContainer
                                InstallerTone.Success -> colors.onTertiaryContainer
                                InstallerTone.Error -> colors.onErrorContainer
                            },
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                state.title,
                modifier = Modifier.fillMaxWidth().semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
            )
            state.appLabel?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            state.subtitle?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SheetAppDetails(details: InstallAppDetails) {
    val unknown = stringResource(R.string.installer_detail_unavailable)
    val size = details.sizeBytes?.let { Formatter.formatFileSize(LocalContext.current, it) } ?: unknown
    val target = details.targetSdk?.let { stringResource(R.string.installer_sdk_value, it) } ?: unknown
    val installedVersion = details.installedVersion
    val metrics = buildList {
        add(stringResource(R.string.installer_size) to size)
        if (installedVersion == null) {
            add(stringResource(R.string.installer_version) to (details.version ?: unknown))
        }
        add(stringResource(R.string.installer_target_sdk) to target)
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SelectionContainer {
                    SheetDetailText(stringResource(R.string.installer_package_id), details.packageName)
                }
                if (installedVersion != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    VersionChange(installedVersion, details.version ?: unknown, TextAlign.Center)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    metrics.forEach { (label, value) ->
                        SheetDetailTile(label, value, Modifier.fillMaxWidth())
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    metrics.forEach { (label, value) ->
                        SheetDetailTile(label, value, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetDetailTile(label: String, value: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
            SheetDetailText(label, value)
        }
    }
}

@Composable
private fun SheetDetailText(label: String, value: String) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SheetProgress(progress: Float?) {
    Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
        if (progress == null) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            val animatedProgress by animateFloatAsState(
                targetValue = progress.coerceIn(0f, 1f),
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "sheet install progress",
            )
            LinearWavyProgressIndicator(
                progress = { animatedProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SheetActions(
    state: InstallerDialogState,
    enabled: Boolean,
    onAction: (InstallerAction) -> Unit,
) {
    val view = LocalView.current
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.primary?.let { action ->
            ExpressiveInstallerButton(action.label, true, enabled, Modifier.fillMaxWidth()) {
                onAction(action)
            }
        }
        state.secondary?.let { action ->
            if (state.primary == null) {
                ExpressiveInstallerButton(action.label, false, enabled, Modifier.fillMaxWidth()) {
                    onAction(action)
                }
            } else {
                TextButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        onAction(action)
                    },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text(action.label, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
