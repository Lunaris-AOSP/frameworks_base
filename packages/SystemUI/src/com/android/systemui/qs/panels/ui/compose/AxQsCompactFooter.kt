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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.animation.Expandable
import com.android.compose.gesture.gesturesDisabled
import com.android.systemui.animation.Expandable as SystemUiExpandable
import com.android.systemui.common.ui.compose.Icon as SystemUiIcon
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel
import com.android.systemui.res.R
import com.android.systemui.shade.ui.composable.ShadeHeader
import com.android.systemui.util.icuMessageFormat

@Composable
internal fun AxQsCompactFooter(
    viewModel: ToolbarViewModel,
    sidePadding: Dp,
    expansionProgress: () -> Float,
    isFullyVisible: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val interactive by
        remember(isFullyVisible, expansionProgress) {
            derivedStateOf { isFullyVisible() && expansionProgress() >= 0.99f }
        }
    val editViewModel =
        rememberViewModel("AxQsCompactFooter") { viewModel.editModeButtonViewModelFactory.create() }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = sharedQsRevealAlpha(expansionProgress()) }
                .then(
                    if (interactive) Modifier
                    else
                        Modifier.gesturesDisabled()
                            .focusProperties { canFocus = false }
                            .clearAndSetSemantics {}
                )
                .windowInsetsPadding(axCompactQsHorizontalInsets())
                .padding(horizontal = (sidePadding - 4.dp).coerceAtLeast(0.dp), vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(horizontal = 4.dp)) { CompactFooterInfo(viewModel) }
        if (viewModel.securityInfoViewModel != null && viewModel.foregroundServicesCount > 0) {
            val count = viewModel.foregroundServicesCount
            val description =
                icuMessageFormat(
                    LocalContext.current.resources,
                    R.string.fgs_manager_footer_label,
                    count,
                )
            CompactFooterButton(
                onClick = viewModel::onForegroundServicesButtonClicked,
                modifier = Modifier.sysuiResTag("fgs_manager_button"),
            ) {
                Text(
                    text = count.toString(),
                    color = ShadeHeader.Colors.textColor,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                )
            }
        }
        CompactFooterIcon(
            model = viewModel.settingsButtonViewModel,
            modifier = Modifier.sysuiResTag("settings_button_container"),
        )
        if (editViewModel.isEditButtonVisible) {
            CompactFooterButton(
                onClick = { editViewModel.onButtonClick() },
                modifier = Modifier.sysuiResTag("qs_edit_mode_button"),
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.accessibility_quick_settings_edit),
                    tint = ShadeHeader.Colors.textColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        CompactFooterIcon(
            model = viewModel.userSwitcherViewModel,
            iconColor = Color.Unspecified,
            modifier = Modifier.sysuiResTag("multi_user_switch"),
        )
        CompactFooterIcon(
            model = viewModel.powerButtonViewModel,
            modifier = Modifier.sysuiResTag("pm_lite"),
        )
    }
}

@Composable
private fun CompactFooterInfo(viewModel: ToolbarViewModel) {
    val context = LocalContext.current
    val security = viewModel.securityInfoViewModel
    val count = viewModel.foregroundServicesCount
    if (security == null && count <= 0) return
    val text =
        security?.text
            ?: icuMessageFormat(context.resources, R.string.fgs_manager_footer_label, count)
    val textColor = ShadeHeader.Colors.textColor
    val onClick: ((SystemUiExpandable) -> Unit)? =
        if (security != null) security.onClick?.let { click -> { click(context, it) } }
        else viewModel::onForegroundServicesButtonClicked
    Expandable(
        color = Color.Transparent,
        borderStroke = BorderStroke(1.dp, textColor.copy(alpha = 0.2f)),
        shape = CircleShape,
        onClick = onClick,
        modifier =
            Modifier.fillMaxWidth()
                .sysuiResTag(if (security == null) "fgs_manager_button" else "qs_footer_security")
                .semantics(mergeDescendants = true) {
                    contentDescription = text
                    if (onClick != null) role = Role.Button
                },
        useModifierBasedImplementation = true,
    ) {
        Row(
            modifier =
                Modifier.heightIn(min = 40.dp).padding(horizontal = 8.dp).clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (security != null) {
                SystemUiIcon(security.icon, tint = textColor, modifier = Modifier.size(16.dp))
            } else {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = text,
                color = textColor,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CompactFooterIcon(
    model: FooterActionsButtonViewModel?,
    modifier: Modifier = Modifier,
    iconColor: Color = ShadeHeader.Colors.textColor,
) {
    if (model == null) return
    CompactFooterButton(
        onClick = model.onClick,
        onLongClick = model.onLongClick,
        modifier = modifier,
    ) {
        SystemUiIcon(icon = model.icon, tint = iconColor, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CompactFooterButton(
    onClick: (SystemUiExpandable) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: ((SystemUiExpandable) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Expandable(
        color = AxTileDefaults.backgroundColor(),
        shape = CircleShape,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.semantics { role = Role.Button },
        useModifierBasedImplementation = true,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { content() }
    }
}
