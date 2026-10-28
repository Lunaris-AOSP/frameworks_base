/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.ui.edit

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.view.View
import android.widget.RemoteViews
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.LunarisQsWidget
import com.android.systemui.qs.panels.ui.compose.AxQsControlCornerRadius
import com.android.systemui.qs.panels.widgets.qsWidgetSize
import com.android.systemui.res.R
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class WidgetChoice(
    val info: AppWidgetProviderInfo,
    val label: String,
    val appLabel: String,
)

@Composable
internal fun LunarisQsWidgetPicker(
    panel: AxQsLayout,
    frameWidth: Float,
    frameHeight: Float,
    selected: LunarisQsWidget,
    busy: Boolean,
    error: Int?,
    onShowBackgroundChange: (Boolean) -> Unit,
    onSelect: (AppWidgetProviderInfo) -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var loadFailed by remember { mutableStateOf(false) }
    val choices by
        produceState<List<WidgetChoice>?>(null, context) {
            value =
                try {
                    withContext(Dispatchers.IO) {
                        AppWidgetManager.getInstance(context)
                            .getInstalledProvidersForProfile(
                                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                                Process.myUserHandle(),
                                null,
                            )
                            .filter {
                                it.widgetFeatures and
                                    AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0
                            }
                            .map { info ->
                                WidgetChoice(
                                    info,
                                    info.loadLabel(context.packageManager),
                                    info.providerInfo.applicationInfo
                                        .loadLabel(context.packageManager)
                                        .toString(),
                                )
                            }
                            .sortedWith(
                                compareBy({ it.appLabel.lowercase() }, { it.label.lowercase() })
                            )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: RuntimeException) {
                    loadFailed = true
                    emptyList()
                }
        }
    val filtered =
        remember(choices, query) {
            choices.orEmpty().filter {
                it.label.contains(query.trim(), ignoreCase = true) ||
                    it.appLabel.contains(query.trim(), ignoreCase = true)
            }
        }
    Surface {
        LazyColumn(
            modifier =
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.lunaris_qs_widget_back),
                        )
                    }
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(
                            stringResource(R.string.lunaris_qs_widget_title),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            stringResource(
                                when (panel) {
                                    AxQsLayout.QQS -> R.string.ax_qs_settings_layout_top
                                    AxQsLayout.QS -> R.string.ax_qs_settings_layout_full
                                    AxQsLayout.SPLIT_SHADE -> R.string.ax_qs_settings_layout_large
                                }
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.lunaris_qs_widget_search)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            stringResource(R.string.lunaris_qs_widget_performance_warning),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(
                        R.string.lunaris_qs_widget_frame_size,
                        frameWidth.roundToInt(),
                        frameHeight.roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .toggleable(
                                    value = selected.showBackground,
                                    enabled = !busy,
                                    role = Role.Switch,
                                    onValueChange = onShowBackgroundChange,
                                )
                                .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.lunaris_qs_widget_show_background),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(R.string.lunaris_qs_widget_show_background_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = selected.showBackground,
                            onCheckedChange = null,
                            enabled = !busy,
                        )
                    }
                }
            }
            if (selected.isConfigured) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                stringResource(R.string.lunaris_qs_widget_current),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                choices
                                    ?.firstOrNull { it.info.provider == selected.provider }
                                    ?.label ?: selected.provider!!.packageName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            TextButton(
                                onClick = {
                                    keyboard?.hide()
                                    onRemove()
                                },
                                enabled = !busy,
                            ) {
                                Text(stringResource(R.string.lunaris_qs_widget_remove))
                            }
                        }
                    }
                }
            }
            if (error != null || loadFailed) {
                item {
                    Text(
                        stringResource(error ?: R.string.lunaris_qs_widget_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (choices == null) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (filtered.isEmpty()) {
                item { Text(stringResource(R.string.lunaris_qs_widget_empty)) }
            }
            items(filtered, key = { it.info.provider.flattenToString() }) { choice ->
                WidgetChoiceCard(
                    choice,
                    frameWidth,
                    frameHeight,
                    selected.provider == choice.info.provider,
                    busy,
                    onSelect = {
                        keyboard?.hide()
                        onSelect(choice.info)
                    },
                )
            }
        }
    }
}

private data class WidgetPreview(val bitmap: Bitmap?)

@Composable
private fun WidgetChoiceCard(
    choice: WidgetChoice,
    frameWidth: Float,
    frameHeight: Float,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
) {
    val context = LocalContext.current
    val preview by
        produceState<WidgetPreview?>(null, choice.info, frameWidth, frameHeight) {
            value = WidgetPreview(loadWidgetPreview(context, choice.info, frameWidth, frameHeight))
        }
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                choice.appLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(choice.label, style = MaterialTheme.typography.titleLarge)
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val scale = minOf(maxWidth.value / frameWidth, 220f / frameHeight, 1f)
                Surface(
                    modifier = Modifier.size((frameWidth * scale).dp, (frameHeight * scale).dp),
                    shape = RoundedCornerShape(AxQsControlCornerRadius * scale),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    val bitmap = preview?.bitmap
                    if (bitmap != null) {
                        Image(
                            bitmap.asImageBitmap(),
                            contentDescription =
                                stringResource(R.string.lunaris_qs_widget_preview, choice.label),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (preview == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    } else {
                        Column(
                            Modifier.fillMaxSize().padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement =
                                Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                        ) {
                            Icon(Icons.Outlined.Widgets, contentDescription = null)
                            if (frameHeight * scale >= 80f) {
                                Text(
                                    stringResource(R.string.lunaris_qs_widget_no_preview),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
            Button(onClick = onSelect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (selected) R.string.lunaris_qs_widget_select_again
                        else R.string.lunaris_qs_widget_choose
                    )
                )
            }
        }
    }
}

private suspend fun loadWidgetPreview(
    context: Context,
    info: AppWidgetProviderInfo,
    widthDp: Float,
    heightDp: Float,
): Bitmap? {
    val density = context.resources.displayMetrics.density
    val previewScale = minOf(1f, 800f / (maxOf(widthDp, heightDp) * density))
    val width = (widthDp * density * previewScale).roundToInt().coerceAtLeast(1)
    val height = (heightDp * density * previewScale).roundToInt().coerceAtLeast(1)
    val generated =
        withContext(Dispatchers.IO) {
            try {
                if (
                    info.generatedPreviewCategories and
                        AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0
                ) {
                    AppWidgetManager.getInstance(context)
                        .getWidgetPreview(
                            info.provider,
                            info.profile,
                            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                        )
                } else null
            } catch (_: RuntimeException) {
                null
            }
        }
    val layout =
        info.previewLayout.takeIf { it != 0 }?.let { RemoteViews(info.provider.packageName, it) }
    for (views in listOfNotNull(generated, layout)) {
        try {
            return withContext(Dispatchers.Main) {
                var failed = false
                val host =
                    object :
                        AppWidgetHostView(
                            context,
                            RemoteViews.InteractionHandler { _, _, _ -> true },
                        ) {
                        override fun isVisibilityTrackingPermitted() = false

                        override fun getErrorView(): View {
                            failed = true
                            return super.getErrorView()
                        }
                    }
                try {
                    host.setAppWidget(AppWidgetManager.INVALID_APPWIDGET_ID, info)
                    host.setOnLightBackground(
                        context.resources.configuration.uiMode and
                            Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
                    )
                    host.updateAppWidget(views)
                    check(!failed)
                    val padding =
                        android.graphics.Rect(
                            host.paddingLeft,
                            host.paddingTop,
                            host.paddingRight,
                            host.paddingBottom,
                        )
                    val size =
                        qsWidgetSize(
                            context,
                            info,
                            (widthDp * density).roundToInt(),
                            (heightDp * density).roundToInt(),
                            padding,
                        )
                    host.measure(
                        View.MeasureSpec.makeMeasureSpec(size.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(size.height, View.MeasureSpec.EXACTLY),
                    )
                    host.layout(0, 0, size.width, size.height)
                    check(!failed)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        val canvas = Canvas(it)
                        canvas.translate(size.offsetX * previewScale, size.offsetY * previewScale)
                        canvas.scale(size.scale * previewScale, size.scale * previewScale)
                        host.draw(canvas)
                    }
                } finally {
                    host.visibility = View.INVISIBLE
                    host.dispatchWindowVisibilityChanged(View.INVISIBLE)
                    host.removeAllViews()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {} catch (_: OutOfMemoryError) {
            return null
        }
    }
    return withContext(Dispatchers.IO) {
        try {
            val drawable =
                info.loadPreviewImage(context, context.resources.displayMetrics.densityDpi)
                    ?: return@withContext null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val imageWidth = drawable.intrinsicWidth.coerceAtLeast(1)
            val imageHeight = drawable.intrinsicHeight.coerceAtLeast(1)
            val scale = minOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
            val left = ((width - imageWidth * scale) / 2).roundToInt()
            val top = ((height - imageHeight * scale) / 2).roundToInt()
            drawable.setBounds(
                left,
                top,
                left + (imageWidth * scale).roundToInt(),
                top + (imageHeight * scale).roundToInt(),
            )
            drawable.draw(Canvas(bitmap))
            bitmap
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}
