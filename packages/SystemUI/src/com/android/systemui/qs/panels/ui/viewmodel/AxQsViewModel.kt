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

package com.android.systemui.qs.panels.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.internal.jank.InteractionJankMonitor
import com.android.keyguard.CarrierTextManager
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.classifier.domain.interactor.FalsingInteractor
import com.android.systemui.classifier.domain.interactor.runIfNotFalseTap
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.panels.data.repository.AxQsSettingsRepository
import com.android.systemui.qs.panels.data.repository.LunarisBluetoothArtworkRepository
import com.android.systemui.qs.panels.data.repository.QSColumnsRepository
import com.android.systemui.qs.panels.shared.model.AxQsControl
import com.android.systemui.qs.panels.shared.model.AxQsGridLayout
import com.android.systemui.qs.panels.shared.model.AxQsGridPosition
import com.android.systemui.qs.panels.shared.model.AxQsGridSection
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.AxQsLayoutPadding
import com.android.systemui.qs.panels.shared.model.AxQsPanelMode
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.shared.model.AxQsTileStacks
import com.android.systemui.qs.panels.shared.model.AxQsTileStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsSliderKey
import com.android.systemui.qs.panels.shared.model.LunarisQsSliderStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsTileFolder
import com.android.systemui.qs.panels.shared.model.LunarisQsTileFolderController
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.policy.ui.dialog.viewmodel.ModeTileViewModel
import com.android.systemui.statusbar.policy.ui.dialog.viewmodel.ModesDialogViewModel
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.map

class AxQsViewModel
@Inject
constructor(
    private val repository: AxQsSettingsRepository,
    val folderDialogFactory: SystemUIDialogFactory,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    configurationInteractor: ConfigurationInteractor,
    shadeInteractor: ShadeInteractor,
    columnsRepository: QSColumnsRepository,
    carrierTextManagerBuilder: CarrierTextManager.Builder,
    bluetoothArtworkRepository: LunarisBluetoothArtworkRepository,
    modesDialogViewModel: ModesDialogViewModel,
    private val falsingInteractor: FalsingInteractor,
    @ShadeDisplayAware private val context: Context,
) : ExclusiveActivatable() {
    val bluetoothArtwork = bluetoothArtworkRepository.artwork
    val modes = modesDialogViewModel.tiles

    fun onModeClick(mode: ModeTileViewModel) {
        falsingInteractor.runIfNotFalseTap { mode.onClick() }
    }

    fun onModeLongClick(mode: ModeTileViewModel) {
        falsingInteractor.runIfNotFalseTap { mode.onLongClick() }
    }

    private val hydrator = Hydrator("AxQsViewModel")
    val carrierText: String by
        hydrator.hydratedStateOf(
            traceName = "carrierText",
            initialValue = "",
            source = conflatedCallbackFlow {
                val manager = carrierTextManagerBuilder
                    .setShowAirplaneMode(true)
                    .setShowMissingSim(true)
                    .setDebugLocationString("MistQS")
                    .build()
                manager.setListening(object : CarrierTextManager.CarrierTextCallback {
                    override fun updateCarrierInfo(info: CarrierTextManager.CarrierTextCallbackInfo) {
                        trySend(info.carrierText?.toString()?.trim().orEmpty())
                    }
                })
                awaitClose { manager.setListening(null) }
            },
        )
    private val defaultTileIds = QSHost.getDefaultSpecs(context.resources)
    private val normalDefaultColumns by
        hydrator.hydratedStateOf(
            traceName = "normalDefaultColumns",
            initialValue = columnsRepository.defaultColumns,
            source = columnsRepository.columns,
        )
    private val splitShadeDefaultColumns by
        hydrator.hydratedStateOf(
            traceName = "splitShadeDefaultColumns",
            initialValue =
                context.resources.getInteger(R.integer.quick_settings_split_shade_num_columns),
            source = columnsRepository.splitShadeColumns,
        )
    private val screenWidthDp by
        hydrator.hydratedStateOf(
            traceName = "screenWidthDp",
            initialValue = context.resources.configuration.screenWidthDp,
            source =
                configurationInteractor.configurationValues.map { configuration ->
                    configuration.screenWidthDp
                },
        )
    val defaultTileSpecs: List<String>
        get() = defaultTileIds

    private val qsOrder by
        hydrator.hydratedStateOf(
            traceName = "qsOrder",
            initialValue = repository.qsOrder.value ?: (repository.defaultControls + defaultTileIds),
            source = repository.qsOrder,
        )
    private val qqsOrder by
        hydrator.hydratedStateOf(
            traceName = "qqsOrder",
            initialValue = repository.qqsOrder.value ?: (repository.defaultControls + defaultTileIds),
            source = repository.qqsOrder,
        )
    private val qsSpans by
        hydrator.hydratedStateOf(
            traceName = "qsSpans",
            initialValue = repository.qsSpans.value,
            source = repository.qsSpans,
        )
    private val tileFolders by
        hydrator.hydratedStateOf(
            traceName = "tileFolders",
            initialValue = repository.tileFolders.value,
            source = repository.tileFolders,
        )
    private val tileStacks by
        hydrator.hydratedStateOf(
            traceName = "tileStacks",
            initialValue = repository.tileStacks.value,
            source = repository.tileStacks,
        )
    private val qqsSpans by
        hydrator.hydratedStateOf(
            traceName = "qqsSpans",
            initialValue = repository.qqsSpans.value,
            source = repository.qqsSpans,
        )
    private val landscapeOrder by
        hydrator.hydratedStateOf(
            traceName = "landscapeOrder",
            initialValue = repository.landscapeOrder.value ?: (repository.defaultControls + defaultTileIds),
            source = repository.landscapeOrder,
        )
    private val qqsControlOrder by
        hydrator.hydratedStateOf(
            traceName = "qqsControlOrder",
            initialValue = repository.qqsControlOrder.value ?: repository.defaultControls,
            source = repository.qqsControlOrder,
        )
    private val qqsTileOrder by
        hydrator.hydratedStateOf(
            traceName = "qqsTileOrder",
            initialValue = repository.qqsTileOrder.value ?: defaultTileIds,
            source = repository.qqsTileOrder,
        )
    private val qsControlOrder by
        hydrator.hydratedStateOf(
            traceName = "qsControlOrder",
            initialValue = repository.qsControlOrder.value ?: repository.defaultControls,
            source = repository.qsControlOrder,
        )
    private val qsTileOrder by
        hydrator.hydratedStateOf(
            traceName = "qsTileOrder",
            initialValue = repository.qsTileOrder.value ?: defaultTileIds,
            source = repository.qsTileOrder,
        )
    private val landscapeControlOrder by
        hydrator.hydratedStateOf(
            traceName = "landscapeControlOrder",
            initialValue = repository.landscapeControlOrder.value ?: repository.defaultControls,
            source = repository.landscapeControlOrder,
        )
    private val landscapeTileOrder by
        hydrator.hydratedStateOf(
            traceName = "landscapeTileOrder",
            initialValue = repository.landscapeTileOrder.value ?: defaultTileIds,
            source = repository.landscapeTileOrder,
        )
    private val splitShadeControlOrder by
        hydrator.hydratedStateOf(
            traceName = "splitShadeControlOrder",
            initialValue = repository.splitShadeControlOrder.value ?: repository.defaultControls,
            source = repository.splitShadeControlOrder,
        )
    private val splitShadeTileOrder by
        hydrator.hydratedStateOf(
            traceName = "splitShadeTileOrder",
            initialValue = repository.splitShadeTileOrder.value ?: defaultTileIds,
            source = repository.splitShadeTileOrder,
        )
    private val qqsControlPositions by
        hydrator.hydratedStateOf(
            traceName = "qqsControlPositions",
            initialValue = emptyMap(),
            source = repository.qqsControlPositions,
        )
    private val qsControlPositions by
        hydrator.hydratedStateOf(
            traceName = "qsControlPositions",
            initialValue = emptyMap(),
            source = repository.qsControlPositions,
        )
    private val landscapeControlPositions by
        hydrator.hydratedStateOf(
            traceName = "landscapeControlPositions",
            initialValue = emptyMap(),
            source = repository.landscapeControlPositions,
        )
    private val landscapeSpans by
        hydrator.hydratedStateOf(
            traceName = "landscapeSpans",
            initialValue = emptyMap(),
            source = repository.landscapeSpans,
        )
    private val splitShadeControlPositions by
        hydrator.hydratedStateOf(
            traceName = "splitShadeControlPositions",
            initialValue = emptyMap(),
            source = repository.splitShadeControlPositions,
        )
    private val splitShadeSpans by
        hydrator.hydratedStateOf(
            traceName = "splitShadeSpans",
            initialValue = emptyMap(),
            source = repository.splitShadeSpans,
        )
    val panelMode by
        hydrator.hydratedStateOf(
            traceName = "panelMode",
            initialValue = AxQsPanelMode.TOGETHER,
            source = repository.panelMode,
        )
    val isQsBypassingShade by
        hydrator.hydratedStateOf(
            traceName = "isQsBypassingShade",
            initialValue = false,
            source = shadeInteractor.isQsBypassingShade,
        )
    var holdQsSceneDuringCollapse by mutableStateOf(false)
        private set

    val quickPanelOnLeft by
        hydrator.hydratedStateOf(
            traceName = "quickPanelOnLeft",
            initialValue = false,
            source = repository.quickPanelOnLeft,
        )
    val showWeather by
        hydrator.hydratedStateOf(
            traceName = "showWeather",
            initialValue = true,
            source = repository.showWeather,
        )
    val showSliderPercentage by
        hydrator.hydratedStateOf(
            traceName = "showSliderPercentage",
            initialValue = repository.showSliderPercentage.value,
            source = repository.showSliderPercentage,
        )
    val spacing by
        hydrator.hydratedStateOf(
            traceName = "spacing",
            initialValue = repository.spacing.value,
            source = repository.spacing,
        )
    val tileStyle by
        hydrator.hydratedStateOf(
            traceName = "tileStyle",
            initialValue = repository.tileStyle.value,
            source = repository.tileStyle,
        )
    private val sliderStyles by
        hydrator.hydratedStateOf(
            traceName = "sliderStyles",
            initialValue = emptyMap(),
            source = repository.sliderStyles,
        )
    private val gridColumns by
        hydrator.hydratedStateOf(
            traceName = "gridColumns",
            initialValue = emptyMap(),
            source = repository.gridColumns,
        )
    private val gridRows by
        hydrator.hydratedStateOf(
            traceName = "gridRows",
            initialValue = emptyMap(),
            source = repository.gridRows,
        )
    private val tileLabels by
        hydrator.hydratedStateOf(
            traceName = "tileLabels",
            initialValue = emptyMap(),
            source = repository.tileLabels,
        )

    fun columns(layout: AxQsGridLayout): Int {
        val default = defaultColumns(layout)
        return (gridColumns[layout] ?: default).coerceIn(columnRange(layout))
    }

    fun columnRange(layout: AxQsGridLayout): IntRange {
        if (layout.section == AxQsGridSection.CONTROLS) {
            return 2..4
        }
        val configuredRange = layout.columnRange(defaultColumns(layout))
        val safeMax = maxColumnsForWidth(layout)
        val last = minOf(configuredRange.last, safeMax).coerceAtLeast(configuredRange.first)
        return configuredRange.first..last
    }

    fun rows(layout: AxQsGridLayout): Int {
        val range = rowRange(layout) ?: return 0
        return (gridRows[layout] ?: defaultRows(layout)).coerceIn(range)
    }

    fun rowRange(layout: AxQsGridLayout): IntRange? {
        if (layout.section != AxQsGridSection.TILES) return null
        return MIN_TILE_GRID_ROWS..MAX_TILE_GRID_ROWS
    }

    fun showTileLabels(layout: AxQsGridLayout): Boolean {
        return tileLabels[layout] ?: layout.showTileLabelsByDefault
    }

    fun orderedIds(
        layout: AxQsLayout,
        section: AxQsGridSection,
        availableIds: List<String>,
        defaultIds: List<String>,
    ): List<String> {
        val available = availableIds.toSet()
        val saved = configuredSectionOrder(layout, section)?.filter(available::contains)
        if (saved != null) return saved
        val legacy = configuredOrder(layout)?.filter { id ->
            id in available && sectionForLegacyId(id, layout) == section
        }
        if (legacy != null) return legacy
        val controlDefaults = defaultControlIds(available)
        return if (section == AxQsGridSection.CONTROLS) {
            controlDefaults
        } else {
            (defaultIds + defaultTileIds).distinct().filter {
                it in available && it !in controlDefaults
            }
        }
    }

    fun order(layout: AxQsLayout, section: AxQsGridSection): List<String>? {
        return configuredSectionOrder(layout, section)
            ?: configuredOrder(layout)
                ?.filter { sectionForLegacyId(it, layout) == section }
    }

    fun isInGrid(id: String, layout: AxQsLayout, section: AxQsGridSection): Boolean {
        val order = order(layout, section)
        if (order != null) return id in order
        return section == AxQsGridSection.CONTROLS && id in DEFAULT_CONTROL_IDS
    }

    private fun configuredOrder(layout: AxQsLayout): List<String>? {
        return when (layout) {
            AxQsLayout.QQS -> qqsOrder
            AxQsLayout.QS -> qsOrder
            AxQsLayout.SPLIT_SHADE -> landscapeOrder
        }?.takeIf { it.isNotEmpty() }
    }

    private fun configuredSectionOrder(
        layout: AxQsLayout,
        section: AxQsGridSection,
    ): List<String>? {
        return when (layout) {
            AxQsLayout.QQS ->
                if (section == AxQsGridSection.CONTROLS) qqsControlOrder else qqsTileOrder
            AxQsLayout.QS ->
                if (section == AxQsGridSection.CONTROLS) qsControlOrder else qsTileOrder
            AxQsLayout.SPLIT_SHADE ->
                if (section == AxQsGridSection.CONTROLS) {
                    splitShadeControlOrder ?: landscapeControlOrder
                } else {
                    splitShadeTileOrder ?: landscapeTileOrder
                }
        }
    }

    fun spans(layout: AxQsLayout): Map<String, AxQsSpan> {
        return when (layout) {
            AxQsLayout.QQS -> qqsSpans
            AxQsLayout.QS -> qsSpans
            AxQsLayout.SPLIT_SHADE -> landscapeSpans + splitShadeSpans
        }
    }

    fun span(id: String, layout: AxQsLayout, default: AxQsSpan): AxQsSpan {
        val resolvedDefault =
            if (
                default == AxQsSpan.TileDefault &&
                    (id in DEFAULT_NETWORK_IDS || id == BLUETOOTH_TILE_ID)
            ) {
                AxQsSpan.TileWideDefault
            } else {
                default
            }
        return if (
            configuredSectionOrder(layout, AxQsGridSection.CONTROLS) == null &&
                configuredOrder(layout) == null
        ) {
            resolvedDefault
        } else {
            spans(layout)[id] ?: resolvedDefault
        }
    }

    fun setOrder(order: List<String>, layout: AxQsLayout, section: AxQsGridSection) {
        repository.setOrder(order, layout, section)
    }

    fun saveLayout(
        layout: AxQsLayout,
        controls: List<String>,
        tiles: List<String>,
        controlSpans: Map<String, AxQsSpan>,
        positions: Map<String, AxQsGridPosition>,
        stacks: Map<String, List<String>>,
        folders: Map<String, LunarisQsTileFolder>,
    ) = repository.saveLayout(layout, controls, tiles, controlSpans, positions, stacks, folders)

    fun folders(layout: AxQsLayout): Map<String, LunarisQsTileFolder> =
        LunarisQsTileFolderController.active(
            tileFolders[layout].orEmpty(),
            order(layout, AxQsGridSection.CONTROLS).orEmpty(),
            stacks(layout).values.flatten().toSet(),
        )

    fun setFolderTileStyle(id: String, layout: AxQsLayout, style: AxQsTileStyle?) =
        repository.setFolderTileStyle(id, layout, style)

    fun onFolderClick(open: () -> Unit) {
        falsingInteractor.runIfNotFalseTap { open() }
    }

    fun showFolderDialog(dialog: SystemUIDialog, expandable: Expandable) {
        val controller =
            expandable.dialogTransitionController(
                DialogCuj(
                    InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                    FOLDER_DIALOG_INTERACTION_JANK_TAG,
                )
            )
        controller?.let {
            dialogTransitionAnimator.show(
                dialog,
                it,
                animateBackgroundBoundsChange = true,
            )
        } ?: dialog.show()
    }

    fun stacks(layout: AxQsLayout): Map<String, List<String>> =
        AxQsTileStacks.active(
            tileStacks[layout].orEmpty(),
            order(layout, AxQsGridSection.CONTROLS).orEmpty(),
        )

    fun controlPositions(layout: AxQsLayout): Map<String, AxQsGridPosition> {
        return when (layout) {
            AxQsLayout.QQS -> qqsControlPositions
            AxQsLayout.QS -> qsControlPositions
            AxQsLayout.SPLIT_SHADE ->
                if (hasSplitShadeOrder()) {
                    splitShadeControlPositions
                } else {
                    landscapeControlPositions
                }
        }
    }

    fun setControlPositions(positions: Map<String, AxQsGridPosition>, layout: AxQsLayout) {
        repository.setControlPositions(positions, layout)
    }

    fun setSpan(id: String, span: AxQsSpan, layout: AxQsLayout, columns: Int) {
        val control = AxQsControl.entries.firstOrNull { it.id == id }
        repository.setSpan(
            id,
            if (LunarisQsTileFolderController.isFolderId(id)) {
                LunarisQsTileFolderController.coerceSpan(span, columns)
            } else {
                control?.coerceSpan(span, columns) ?: span.coerceForControlTile(columns)
            },
            layout,
        )
    }

    fun setPanelMode(mode: AxQsPanelMode) = repository.setPanelMode(mode)

    fun setForceQsEvent(forceQsEvent: Boolean) {
        holdQsSceneDuringCollapse = forceQsEvent
    }

    fun clearCollapseGuard() {
        holdQsSceneDuringCollapse = false
    }

    fun setQuickPanelOnLeft(onLeft: Boolean) = repository.setQuickPanelOnLeft(onLeft)

    fun setShowWeather(show: Boolean) = repository.setShowWeather(show)

    fun setShowSliderPercentage(show: Boolean) = repository.setShowSliderPercentage(show)

    fun setTileStyle(style: AxQsTileStyle) = repository.setTileStyle(style)

    fun setTileGap(gap: Int) = repository.setTileGap(gap)

    fun setSidePadding(padding: Int) = repository.setSidePadding(padding)

    fun setTopPadding(padding: Int) = repository.setTopPadding(padding)

    fun configuredColumns(layout: AxQsGridLayout): Int? =
        gridColumns[layout]?.coerceIn(columnRange(layout))

    fun configuredRows(layout: AxQsGridLayout): Int? =
        rowRange(layout)?.let { gridRows[layout]?.coerceIn(it) }

    fun resetColumns(layout: AxQsGridLayout) = repository.setColumns(layout, 0)

    fun resetRows(layout: AxQsGridLayout) = repository.setRows(layout, 0)

    fun sliderStyle(
        layout: AxQsLayout,
        control: AxQsControl,
    ): LunarisQsSliderStyle =
        sliderStyles[LunarisQsSliderKey(layout, control)]
            ?: LunarisQsSliderStyle.M3_EXPRESSIVE

    fun setSliderStyle(
        layout: AxQsLayout,
        control: AxQsControl,
        style: LunarisQsSliderStyle,
    ) = repository.setSliderStyle(layout, control, style)

    fun setColumns(layout: AxQsGridLayout, columns: Int) {
        repository.setColumns(layout, columns.coerceIn(columnRange(layout)))
    }

    fun setRows(layout: AxQsGridLayout, rows: Int) {
        rowRange(layout)?.let { repository.setRows(layout, rows.coerceIn(it)) }
    }

    fun setTileLabels(layout: AxQsGridLayout, showLabels: Boolean) {
        if (layout.supportsTileLabels) {
            repository.setTileLabels(layout, showLabels)
        }
    }

    fun resetLayout() {
        val defaultControls =
            listOf("internet", "bt", AxQsControl.MEDIA.id, AxQsControl.BRIGHTNESS.id, AxQsControl.VOLUME.id)
        val defaultTiles = defaultTileIds.filterNot(defaultControls::contains)
        repository.resetLayout(
            defaultControls = defaultControls,
            defaultTiles = defaultTiles,
        )
    }

    override suspend fun onActivated(): Nothing = hydrator.activate()

    fun defaultColumns(layout: AxQsGridLayout): Int =
        if (layout.section == AxQsGridSection.CONTROLS) {
            DEFAULT_CONTROL_COLUMNS
        } else if (layout.isSplitShade) {
            splitShadeDefaultColumns
        } else {
            normalDefaultColumns
        }

    fun defaultRows(layout: AxQsGridLayout): Int =
        if (layout.layout == AxQsLayout.QQS) DEFAULT_QQS_TILE_ROWS else DEFAULT_TILE_GRID_ROWS

    private fun defaultControlIds(available: Set<String>): List<String> {
        val network = DEFAULT_NETWORK_IDS.firstOrNull(available::contains)
        return listOfNotNull(
                network,
                BLUETOOTH_TILE_ID,
                AxQsControl.MEDIA.id,
                AxQsControl.BRIGHTNESS.id,
                AxQsControl.VOLUME.id,
            )
            .filter(available::contains)
            .distinct()
    }

    private fun sectionForLegacyId(id: String, layout: AxQsLayout): AxQsGridSection {
        AxQsControl.entries.firstOrNull { it.id == id }?.let { control ->
            return if (control.canUseTileGrid) {
                AxQsGridSection.TILES
            } else {
                AxQsGridSection.CONTROLS
            }
        }
        return if ((spans(layout)[id] ?: AxQsSpan.TileDefault) == AxQsSpan.TileDefault) {
            AxQsGridSection.TILES
        } else {
            AxQsGridSection.CONTROLS
        }
    }

    private fun hasSplitShadeOrder(): Boolean =
        splitShadeControlOrder != null || splitShadeTileOrder != null

    private fun maxColumnsForWidth(layout: AxQsGridLayout): Int {
        val contentWidth = (screenWidthDp - spacing.sidePadding * 2).toFloat()
        val gridWidth =
            if (layout.isSplitShade) {
                (contentWidth - AxQsLayoutPadding.LANDSCAPE_SPLIT_GRID_SPACING_DP) / 2f
            } else {
                contentWidth
            }
        return ((gridWidth + spacing.tileGap) / (MIN_TILE_WIDTH_DP + spacing.tileGap))
            .toInt()
            .coerceAtLeast(1)
    }

    private companion object {
        const val FOLDER_DIALOG_INTERACTION_JANK_TAG = "lunaris_qs_folder"
        const val MIN_TILE_WIDTH_DP = 56f
        const val MIN_TILE_GRID_ROWS = 1
        const val MAX_TILE_GRID_ROWS = 3
        const val DEFAULT_CONTROL_COLUMNS = 4
        const val DEFAULT_QQS_TILE_ROWS = 2
        const val DEFAULT_TILE_GRID_ROWS = 3
        val DEFAULT_NETWORK_IDS = listOf("wifi", "internet")
        val DEFAULT_CONTROL_IDS =
            setOf(
                BLUETOOTH_TILE_ID,
                AxQsControl.MEDIA.id,
                AxQsControl.BRIGHTNESS.id,
                AxQsControl.VOLUME.id,
            )
        const val BLUETOOTH_TILE_ID = "bt"
    }
}
