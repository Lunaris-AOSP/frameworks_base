/*
 * Copyright (C) 2026 Lunaris-AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.lockscreen

import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lunaris.lockscreenwidgets.WidgetEntry
import org.lunaris.lockscreenwidgets.WidgetSizes
import org.lunaris.lockscreenwidgets.R as WidgetR

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun KeyguardWidgetActions(
    entry: WidgetEntry,
    entries: List<WidgetEntry>,
    info: AppWidgetProviderInfo?,
    gridWidth: Int,
    onChange: (WidgetEntry) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    MaterialExpressiveTheme(
        colorScheme = MaterialTheme.colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = MaterialTheme.shapes.copy(
            extraSmall = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    info?.loadLabel(context.packageManager)?.toString()
                        ?: stringResource(WidgetR.string.app_widgets_unavailable),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, stringResource(WidgetR.string.app_widgets_close))
                }
            }
            if (info != null && gridWidth > 0) {
                val sizes = (WidgetSizes(context, gridWidth).allowed(info) +
                    (entry.width to entry.height)).distinct().filter { (w, h) ->
                    WidgetEntry.place(entry.id, w, h, entries) != null
                }
                WidgetChoice(
                    stringResource(WidgetR.string.app_widgets_size),
                    sizes.map { (w, h) ->
                        stringResource(WidgetR.string.app_widgets_dimensions, w, h)
                    },
                    sizes.indexOf(entry.width to entry.height),
                ) { index ->
                    val (w, h) = sizes[index]
                    val resized = entry.copy(width = w, height = h)
                    val placed = if (resized.fits(entries)) resized
                        else WidgetEntry.place(entry.id, w, h, entries)
                    placed?.let(onChange)
                }
            }
            val positions = buildList {
                for (y in 0..4 - entry.height) for (x in 0..4 - entry.width) {
                    entry.copy(x = x, y = y).takeIf { it.fits(entries) }?.let(::add)
                }
            }
            WidgetChoice(
                stringResource(WidgetR.string.app_widgets_position),
                positions.map {
                    stringResource(WidgetR.string.app_widgets_position_value, it.x + 1, it.y + 1)
                },
                positions.indexOf(entry),
            ) { onChange(positions[it]) }
            TextButton(
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp))
                Text(
                    stringResource(WidgetR.string.app_widgets_remove_widget),
                    Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun WidgetChoice(title: String, values: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember(values, selected) { mutableStateOf(false) }
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = values.size > 1,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(values.getOrElse(selected) { "" }, Modifier.weight(1f))
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                values.forEachIndexed { index, value ->
                    DropdownMenuItem(
                        text = { Text(value) },
                        onClick = { expanded = false; onSelect(index) },
                        trailingIcon = if (index == selected) {
                            { Icon(Icons.Rounded.Check, null) }
                        } else null,
                    )
                }
            }
        }
    }
}
