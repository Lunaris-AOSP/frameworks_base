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

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.common.ui.compose.Icon as StatusBarIcon
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupActionModel
import com.android.systemui.statusbar.featurepods.popups.shared.model.PopupActionRole
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel

@Composable
internal fun CallPopup(model: PopupContentModel.SystemEvent, modifier: Modifier = Modifier) {
    val openAction = model.actions.firstOrNull { it.role == PopupActionRole.OpenApp }
    val callActions = model.actions.filter {
        it.role == PopupActionRole.AnswerCall || it.role == PopupActionRole.EndCall
    }.sortedBy { if (it.role == PopupActionRole.EndCall) 0 else 1 }
    val otherActions = model.actions.filter { it.role == PopupActionRole.Default }
    val callStatus = stringResource(
        if (model.callStartTimeMs == null) R.string.dynamic_island_incoming_call
        else R.string.ongoing_call_content_description
    )

    PopupSurface(
        shape = RoundedCornerShape(32.dp),
        modifier = modifier.widthIn(min = 320.dp, max = 360.dp),
    ) {
        val contentColor = LocalContentColor.current
        val statusColor = if (contentColor.luminance() < 0.5f) Color(0xFF087F5B) else IslandAccents.Calls
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(52.dp).clip(CircleShape)
                        .background(IslandAccents.Calls.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (model.icon != null) {
                        StatusBarIcon(
                            icon = model.icon,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(52.dp),
                        )
                    } else {
                        Icon(Icons.Filled.Person, null, tint = statusColor,
                            modifier = Modifier.size(28.dp))
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).background(statusColor, CircleShape))
                        Text(
                            text = model.callStartTimeMs?.let { rememberCallDurationText(it) }
                                ?: callStatus,
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = model.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model.text.isNotBlank() && model.text != model.title &&
                        !model.text.equals(callStatus, ignoreCase = true)) {
                        Text(
                            text = model.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalContentColor.current.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (openAction != null) {
                    IconButton(onClick = openAction.onClick, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(R.string.dynamic_island_open_call),
                            tint = LocalContentColor.current.copy(alpha = 0.65f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            if (callActions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    callActions.forEach { action ->
                        CallActionButton(action, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            if (otherActions.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    otherActions.forEach { action ->
                        TextButton(
                            onClick = action.onClick,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(action.label, color = contentColor,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallActionButton(action: PopupActionModel, modifier: Modifier = Modifier) {
    val isAnswer = action.role == PopupActionRole.AnswerCall
    Button(
        onClick = action.onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isAnswer) IslandAccents.Calls else Color(0xFFD32F46),
            contentColor = if (isAnswer) Color(0xFF062B1C) else Color.White,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isAnswer) Icons.Filled.Call else Icons.Filled.CallEnd,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = action.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
