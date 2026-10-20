/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.android.systemui.qs.panels.ui.edit

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.android.compose.theme.PlatformTheme
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.LunarisQsPhotoFilter
import com.android.systemui.qs.panels.shared.model.LunarisQsPhotoFrame
import com.android.systemui.qs.panels.ui.compose.LunarisQsPhotoCard
import com.android.systemui.qs.panels.ui.viewmodel.LunarisQsPhotoEditorViewModel
import com.android.systemui.res.R
import kotlin.math.roundToInt

class LunarisQsPhotoActivity : ComponentActivity() {
    private val editor: LunarisQsPhotoEditorViewModel by viewModels()
    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            editor.importPhoto(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        enableEdgeToEdge()
        val layout =
            AxQsLayout.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_LAYOUT) }
                ?: run {
                    finish()
                    return
                }
        onBackPressedDispatcher.addCallback(this) { editor.cancel() }
        setContent {
            LaunchedEffect(editor.finished) { if (editor.finished) finish() }
            PlatformTheme {
                Surface {
                    PhotoEditor(
                        photo = editor.photo,
                        layout = layout,
                        frameWidth = frameDimension(EXTRA_WIDTH),
                        frameHeight = frameDimension(EXTRA_HEIGHT),
                        busy = editor.busy,
                        canEdit = editor.canEdit,
                        error = editor.error,
                        onChange = editor::change,
                        onChoose = ::choosePhoto,
                        onSave = editor::save,
                        onCancel = editor::cancel,
                    )
                }
            }
        }
    }

    private fun frameDimension(extra: String): Float =
        intent.getFloatExtra(extra, 160f).takeIf { it.isFinite() }?.coerceIn(32f, 1200f) ?: 160f

    private fun choosePhoto() {
        if (!editor.beginPicking()) return
        try {
            picker.launch(arrayOf("image/*"))
        } catch (_: ActivityNotFoundException) {
            editor.pickerUnavailable()
        } catch (_: SecurityException) {
            editor.pickerUnavailable()
        }
    }

    companion object {
        const val EXTRA_LAYOUT = "layout"
        const val EXTRA_WIDTH = "width_dp"
        const val EXTRA_HEIGHT = "height_dp"
    }
}

@Composable
private fun PhotoEditor(
    photo: LunarisQsPhotoFrame,
    layout: AxQsLayout,
    frameWidth: Float,
    frameHeight: Float,
    busy: Boolean,
    canEdit: Boolean,
    error: Int?,
    onChange: (LunarisQsPhotoFrame) -> Unit,
    onChoose: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.lunaris_qs_photo_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            stringResource(
                when (layout) {
                    AxQsLayout.QQS -> R.string.ax_qs_settings_layout_top
                    AxQsLayout.QS -> R.string.ax_qs_settings_layout_full
                    AxQsLayout.SPLIT_SHADE -> R.string.ax_qs_settings_layout_large
                }
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val scale = minOf(1f, maxWidth.value / frameWidth, 280f / frameHeight)
            Box(
                Modifier.size((frameWidth * scale).dp, (frameHeight * scale).dp),
                contentAlignment = Alignment.Center,
            ) {
                LunarisQsPhotoCard(
                    photo = photo,
                    playing = !busy,
                    modifier =
                        Modifier.requiredSize(frameWidth.dp, frameHeight.dp).graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    onPositionChanged =
                        if (!canEdit) null
                        else { x, y -> onChange(photo.copy(positionX = x, positionY = y)) },
                )
            }
        }
        Text(stringResource(R.string.lunaris_qs_photo_position_hint))
        Text(
            stringResource(R.string.lunaris_qs_photo_size_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onChoose, enabled = canEdit) {
                Text(stringResource(R.string.lunaris_qs_photo_choose))
            }
            TextButton(
                onClick = { onChange(photo.copy(fileName = null)) },
                enabled = canEdit && photo.fileName != null,
            ) {
                Text(stringResource(R.string.lunaris_qs_photo_remove))
            }
        }
        if (busy) CircularProgressIndicator()
        if (error != null) Text(stringResource(error), color = MaterialTheme.colorScheme.error)
        val hasPhoto = photo.fileName != null && canEdit
        PhotoSlider(
            R.string.lunaris_qs_photo_zoom,
            photo.zoom,
            1f..3f,
            hasPhoto,
            "${(photo.zoom * 100).roundToInt()}%",
        ) {
            onChange(photo.copy(zoom = it))
        }
        PhotoSlider(
            R.string.lunaris_qs_photo_horizontal,
            photo.positionX,
            0f..1f,
            hasPhoto,
            "${(photo.positionX * 100).roundToInt()}%",
        ) {
            onChange(photo.copy(positionX = it))
        }
        PhotoSlider(
            R.string.lunaris_qs_photo_vertical,
            photo.positionY,
            0f..1f,
            hasPhoto,
            "${(photo.positionY * 100).roundToInt()}%",
        ) {
            onChange(photo.copy(positionY = it))
        }
        TextButton(
            onClick = { onChange(photo.copy(positionX = .5f, positionY = .5f, zoom = 1f)) },
            enabled = hasPhoto,
        ) {
            Text(stringResource(R.string.lunaris_qs_photo_center))
        }
        PhotoSlider(
            R.string.lunaris_qs_photo_border,
            photo.borderDp.toFloat(),
            0f..15f,
            canEdit,
            stringResource(R.string.ax_qs_settings_dp, photo.borderDp),
            steps = 14,
        ) {
            onChange(photo.copy(borderDp = it.roundToInt()))
        }
        PhotoSlider(
            R.string.lunaris_qs_photo_corners,
            photo.cornerDp.toFloat(),
            0f..48f,
            canEdit,
            stringResource(R.string.ax_qs_settings_dp, photo.cornerDp),
            steps = 47,
        ) {
            onChange(photo.copy(cornerDp = it.roundToInt()))
        }
        Text(
            stringResource(R.string.lunaris_qs_photo_filter),
            style = MaterialTheme.typography.titleMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LunarisQsPhotoFilter.entries.forEach { filter ->
                FilterChip(
                    selected = photo.filter == filter,
                    onClick = { onChange(photo.copy(filter = filter)) },
                    enabled = hasPhoto,
                    label = {
                        Text(
                            stringResource(
                                when (filter) {
                                    LunarisQsPhotoFilter.ORIGINAL ->
                                        R.string.lunaris_qs_photo_original
                                    LunarisQsPhotoFilter.GRAYSCALE ->
                                        R.string.lunaris_qs_photo_grayscale
                                    LunarisQsPhotoFilter.SEPIA -> R.string.lunaris_qs_photo_sepia
                                    LunarisQsPhotoFilter.WARM -> R.string.lunaris_qs_photo_warm
                                    LunarisQsPhotoFilter.COOL -> R.string.lunaris_qs_photo_cool
                                }
                            )
                        )
                    },
                )
            }
        }
        PhotoSlider(
            R.string.lunaris_qs_photo_grayscale,
            photo.grayscale,
            0f..1f,
            hasPhoto,
            "${(photo.grayscale * 100).roundToInt()}%",
        ) {
            onChange(photo.copy(grayscale = it))
        }
        PhotoSlider(
            R.string.lunaris_qs_photo_dim,
            photo.dim,
            0f..1f,
            hasPhoto,
            "${(photo.dim * 100).roundToInt()}%",
        ) {
            onChange(photo.copy(dim = it))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel, enabled = !busy) {
                Text(stringResource(android.R.string.cancel))
            }
            Button(onClick = onSave, enabled = canEdit) {
                Text(stringResource(R.string.lunaris_qs_photo_save))
            }
        }
    }
}

@Composable
private fun PhotoSlider(
    title: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    valueLabel: String,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    val label = stringResource(title)
    Column {
        Text("$label: $valueLabel", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
internal fun LunarisQsContentEditButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(Icons.Filled.Edit, contentDescription = contentDescription)
    }
}
