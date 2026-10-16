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

import android.content.Intent
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.compose.animation.Expandable
import com.android.compose.animation.scene.ContentScope
import com.android.compose.gesture.gesturesDisabled
import com.android.systemui.animation.Expandable as SystemUiExpandable
import com.android.systemui.common.ui.compose.Icon as SystemUiIcon
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.composefragment.ui.axQuickSettingsSceneMotion
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel
import com.android.systemui.qs.panels.shared.model.AxQsGridItem
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel
import com.android.systemui.res.R
import com.android.systemui.shade.ui.composable.ShadeHeader
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import com.android.internal.util.lunaris.OmniJawsClient

val LocalAxQsExpansionProgress = compositionLocalOf<() -> Float> { { 1f } }

private val DragHandleWidth = 56.dp
private val DragHandleHeight = 4.dp

@Composable
internal fun <T> ContentScope.AxQQS(
    toolbarViewModel: ToolbarViewModel,
    shadeHeaderViewModel: ShadeHeaderViewModel,
    controlItems: List<AxQsGridItem<T>>,
    tileItems: List<AxQsGridItem<T>>,
    controlColumns: Int,
    controlRows: Int,
    tileColumns: Int,
    tileRows: Int,
    showTileLabels: Boolean,
    rowHeight: Dp,
    spacing: Dp,
    circleCells: Boolean,
    isFullyVisible: () -> Boolean,
    editButtonProgress: () -> Float,
    separateMode: Boolean,
    showWeather: Boolean = true,
    modifier: Modifier = Modifier,
    controlContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileContent: @Composable (AxQsGridItem<T>) -> Unit,
) {
    val statusBarHeight =
        ShadeHeader.Dimensions.StatusBarHeight
    Column(
        modifier = modifier.fillMaxWidth().heightIn(min = statusBarHeight),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        AxQsDateHeader(
            toolbarViewModel = toolbarViewModel,
            shadeHeaderViewModel = shadeHeaderViewModel,
            showEdit = false,
            showWeather = showWeather,
            isFullyVisible = isFullyVisible,
            editButtonProgress = editButtonProgress,
        )
        if (!separateMode) {
            val pagerState = rememberAxQsTilePagerState(tileItems, tileColumns, tileRows)
            if (controlItems.isNotEmpty()) {
                AxQsGrid(
                    items = controlItems,
                    columns = controlColumns,
                    rowHeight = rowHeight,
                    spacing = spacing,
                    maxRows = controlRows,
                    squareCells = circleCells,
                    modifier = Modifier.fillMaxWidth(),
                    content = controlContent,
                )
            }
            if (tileItems.isNotEmpty()) {
                AxQsTileGrid(
                    items = tileItems,
                    columns = tileColumns,
                    rows = tileRows,
                    spacing = spacing,
                    showLabels = showTileLabels,
                    circleCells = circleCells,
                    pagerState = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    content = tileContent,
                )
            }
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.width(DragHandleWidth)
                        .height(DragHandleHeight)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(2.dp),
                        )
                )
            }
        }
    }
}

@Composable
internal fun <T> ContentScope.AxQS(
    toolbarViewModel: ToolbarViewModel,
    shadeHeaderViewModel: ShadeHeaderViewModel,
    isFullyVisible: () -> Boolean,
    controlItems: List<AxQsGridItem<T>>,
    tileItems: List<AxQsGridItem<T>>,
    controlColumns: Int,
    controlRows: Int,
    tileColumns: Int,
    tileRows: Int,
    showTileLabels: Boolean,
    rowHeight: Dp,
    spacing: Dp,
    editButtonProgress: () -> Float,
    scrollState: ScrollState,
    circleCells: Boolean,
    showDate: Boolean = false,
    showWeather: Boolean = true,
    modifier: Modifier = Modifier,
    controlContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileLabel: @Composable (AxQsGridItem<T>) -> Unit,
) {
    val pagerState = rememberAxQsTilePagerState(tileItems, tileColumns, tileRows)
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        AxQsDateHeader(
            toolbarViewModel = toolbarViewModel,
            shadeHeaderViewModel = shadeHeaderViewModel,
            showEdit = true,
            showDate = showDate,
            showWeather = showWeather,
            isFullyVisible = isFullyVisible,
            editButtonProgress = editButtonProgress,
        )
        Column(
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .clipScrollableContainer(Orientation.Vertical)
                    .verticalScroll(scrollState, overscrollEffect = null),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                if (controlItems.isNotEmpty()) {
                    AxQsGrid(
                        items = controlItems,
                        columns = controlColumns,
                        rowHeight = rowHeight,
                        spacing = spacing,
                        maxRows = controlRows,
                        squareCells = circleCells,
                        modifier = Modifier.fillMaxWidth(),
                        content = controlContent,
                    )
                }
                if (tileItems.isNotEmpty()) {
                    val expansionProgress = LocalAxQsExpansionProgress.current
                    Column(
                        modifier = Modifier.axQuickSettingsSceneMotion(expansionProgress)
                    ) {
                        AxQsTileGrid(
                            items = tileItems,
                            columns = tileColumns,
                            rows = tileRows,
                            spacing = spacing,
                            showLabels = showTileLabels,
                            circleCells = circleCells,
                            pagerState = pagerState,
                            modifier = Modifier.fillMaxWidth(),
                            content = tileContent,
                            label = tileLabel,
                        )
                        AxQsPagerIndicator(
                            pagerState = pagerState,
                            modifier = Modifier.fillMaxWidth().padding(top = spacing),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> rememberAxQsTilePagerState(
    items: List<AxQsGridItem<T>>,
    columns: Int,
    rows: Int,
): PagerState = rememberPagerState { axQsTileGridPageCount(items.size, columns, rows) }

@Composable
internal fun AxQsDateHeader(
    toolbarViewModel: ToolbarViewModel,
    shadeHeaderViewModel: ShadeHeaderViewModel,
    showEdit: Boolean,
    showDate: Boolean = false,
    showWeather: Boolean = true,
    isFullyVisible: () -> Boolean,
    editButtonProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showDate) {
                AxQuickSettingsDate(viewModel = shadeHeaderViewModel)
            }
            if (showWeather) {
                AxQsWeather()
            }
        }
        AxQsHeaderActions(
            viewModel = toolbarViewModel,
            isFullyVisible = isFullyVisible,
            editButtonProgress = editButtonProgress,
            showEdit = showEdit,
            modifier = Modifier.offset(x = 6.dp),
        )
    }
}

@Composable
fun AxQsWeather(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var weatherInfo by remember { mutableStateOf<OmniJawsClient.WeatherInfo?>(null) }
    var weatherIcon by remember { mutableStateOf<Drawable?>(null) }

    DisposableEffect(context) {
        val client = OmniJawsClient.get()

        fun readWeather() {
            try {
                val info = client.weatherInfo.takeIf { client.isOmniJawsEnabled(context) }
                weatherInfo = info
                weatherIcon =
                    if (info != null) {
                        client.getWeatherConditionImage(context, info.conditionCode)
                    } else {
                        null
                    }
            } catch (e: Exception) {
                Log.e("AxQsWeather", "Failed to update OmniJaws weather", e)
                weatherInfo = null
                weatherIcon = null
            }
        }

        fun requestWeather() {
            try {
                if (client.isOmniJawsEnabled(context)) {
                    client.queryWeather(context)
                } else {
                    weatherInfo = null
                    weatherIcon = null
                }
            } catch (e: Exception) {
                Log.e("AxQsWeather", "Failed to update OmniJaws weather", e)
                weatherInfo = null
                weatherIcon = null
            }
        }

        val observer =
            object : OmniJawsClient.OmniJawsObserver {
                override fun weatherUpdated() {
                    requestWeather()
                    readWeather()
                }

                override fun weatherError(errorReason: Int) {
                    if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
                        weatherInfo = null
                        weatherIcon = null
                    }
                }

                override fun updateSettings() {
                    requestWeather()
                    readWeather()
                }
            }

        val contentObserver =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    requestWeather()
                    readWeather()
                }
            }
        try {
            context.contentResolver.registerContentObserver(
                OmniJawsClient.WEATHER_URI,
                true,
                contentObserver,
            )
            context.contentResolver.registerContentObserver(
                OmniJawsClient.SETTINGS_URI,
                true,
                contentObserver,
            )
        } catch (e: Exception) {
            Log.e("AxQsWeather", "Failed to register ContentObserver", e)
        }

        client.addObserver(context, observer)
        requestWeather()
        readWeather()

        onDispose {
            try {
                context.contentResolver.unregisterContentObserver(contentObserver)
            } catch (_: Exception) {}
            client.removeObserver(context, observer)
        }
    }

    val info = weatherInfo ?: return
    if (info.temp.isNullOrEmpty() || info.temp == "-") {
        return
    }

    val tempUnits = info.tempUnits ?: "°C"
    val condition = info.condition?.takeIf { it.isNotEmpty() }
    val weatherText =
        if (condition != null) "${info.temp}$tempUnits · $condition" else "${info.temp}$tempUnits"

    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    try {
                        val intent =
                            OmniJawsClient.get().getWeatherActivityIntent(context).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        weatherIcon?.let { icon ->
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                },
                update = { view -> view.setImageDrawable(icon) },
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = weatherText,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun AxQsHeaderActions(
    viewModel: ToolbarViewModel,
    isFullyVisible: () -> Boolean,
    editButtonProgress: () -> Float,
    showEdit: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showEdit) {
            AxEditButton(viewModel, isFullyVisible, editButtonProgress)
        }
        FooterIconButton(
            model = viewModel.settingsButtonViewModel,
            containerColor = AxTileDefaults.backgroundColor(),
            modifier =
                Modifier.sysuiResTag("settings_button_container").minimumInteractiveComponentSize(),
        )
        AxFooterOverflowMenu(viewModel)
    }
}

@Composable
internal fun AxQsPagerIndicator(pagerState: PagerState, modifier: Modifier = Modifier) {
    if (pagerState.pageCount > 1) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            PagerDots(
                pagerState = pagerState,
                activeColor = MaterialTheme.colorScheme.primary,
                nonActiveColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                showArrows = false,
            )
        }
    }
}

@Composable
private fun AxEditButton(
    viewModel: ToolbarViewModel,
    isFullyVisible: () -> Boolean,
    editButtonProgress: () -> Float,
) {
    val editModeButtonViewModel =
        rememberViewModel("AxQsActions") { viewModel.editModeButtonViewModelFactory.create() }
    if (!editModeButtonViewModel.isEditButtonVisible) return
    val editEnabled by remember(editButtonProgress, isFullyVisible) {
        derivedStateOf {
            editButtonProgress() >= EDIT_BUTTON_ENABLE_THRESHOLD && isFullyVisible()
        }
    }
    Box(
        modifier =
            Modifier.graphicsLayer { alpha = editButtonProgress().coerceIn(0f, 1f) }
                .then(
                    if (editEnabled) Modifier
                    else Modifier.gesturesDisabled().clearAndSetSemantics {}
                )
    ) {
        Expandable(
            color = AxTileDefaults.backgroundColor(),
            shape = CircleShape,
            onClick = { editModeButtonViewModel.onButtonClick() },
            modifier =
                Modifier.sysuiResTag("qs_edit_mode_button").minimumInteractiveComponentSize(),
            useModifierBasedImplementation = true,
        ) {
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.accessibility_quick_settings_edit),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun AxFooterOverflowMenu(viewModel: ToolbarViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var expandable by remember { mutableStateOf<SystemUiExpandable?>(null) }
    val context = LocalContext.current
    val menuColor =
        AxTileDefaults.backgroundColor().compositeOver(MaterialTheme.colorScheme.surface)
    Box {
        Expandable(
            color = AxTileDefaults.backgroundColor(),
            shape = CircleShape,
            onClick = {
                expandable = it
                expanded = true
            },
            modifier = Modifier.minimumInteractiveComponentSize(),
            useModifierBasedImplementation = true,
        ) {
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.qs_edit_menu_content_description),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, (-12).dp),
            shape = RoundedCornerShape(24.dp),
            containerColor = menuColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            AxPowerMenuItem(viewModel.powerButtonViewModel) {
                val source = expandable ?: return@AxPowerMenuItem
                expanded = false
                viewModel.powerButtonViewModel.onClick(source)
            }
        }
    }
}

@Composable
private fun AxPowerMenuItem(model: FooterActionsButtonViewModel, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(R.string.accessibility_quick_settings_power_menu),
                style = MaterialTheme.typography.labelLarge,
            )
        },
        leadingIcon = {
            SystemUiIcon(
                icon = model.icon,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 52.dp),
    )
}

@Composable
private fun FooterIconButton(
    model: FooterActionsButtonViewModel?,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    if (model == null) return
    Expandable(
        color = containerColor,
        shape = CircleShape,
        onClick = model.onClick,
        modifier = modifier,
        useModifierBasedImplementation = true,
    ) {
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            SystemUiIcon(
                icon = model.icon,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private const val EDIT_BUTTON_ENABLE_THRESHOLD = 0.99f
