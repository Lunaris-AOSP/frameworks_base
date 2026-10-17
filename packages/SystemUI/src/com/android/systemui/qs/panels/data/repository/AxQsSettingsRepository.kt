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

package com.android.systemui.qs.panels.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.panels.shared.model.AxQsControl
import com.android.systemui.qs.panels.shared.model.AxQsGridLayout
import com.android.systemui.qs.panels.shared.model.AxQsGridPosition
import com.android.systemui.qs.panels.shared.model.AxQsGridSection
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.AxQsPanelMode
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.shared.model.AxQsTileStyle
import com.android.systemui.qs.panels.shared.model.AxQsVerticalSliderKey
import com.android.systemui.qs.panels.shared.model.AxQsVerticalSliderStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsSpacing
import com.android.systemui.user.data.repository.UserRepository
import android.provider.Settings
import com.android.systemui.util.settings.SystemSettings
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@SysUISingleton
class AxQsSettingsRepository
@Inject
constructor(
    private val secureSettings: SecureSettings,
    private val systemSettings: SystemSettings,
    private val userRepository: UserRepository,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    val defaultControlSpans: Map<String, AxQsSpan> = parseSpans(DEFAULT_SPANS_STRING)
    val defaultControls: List<String> = DEFAULT_CONTROLS_LIST

    val qsOrder: StateFlow<List<String>?> = orderSetting(QS_ORDER)
    val qqsOrder: StateFlow<List<String>?> = orderSetting(QQS_ORDER)
    val landscapeOrder: StateFlow<List<String>?> = orderSetting(LANDSCAPE_ORDER)

    val qqsControlOrder: StateFlow<List<String>?> = orderSetting(QQS_CONTROL_ORDER, DEFAULT_CONTROLS_STRING)
    val qqsTileOrder: StateFlow<List<String>?> = orderSetting(QQS_TILE_ORDER)
    val qsControlOrder: StateFlow<List<String>?> = orderSetting(QS_CONTROL_ORDER, DEFAULT_CONTROLS_STRING)
    val qsTileOrder: StateFlow<List<String>?> = orderSetting(QS_TILE_ORDER)
    val landscapeControlOrder: StateFlow<List<String>?> = orderSetting(LANDSCAPE_CONTROL_ORDER, DEFAULT_CONTROLS_STRING)
    val landscapeTileOrder: StateFlow<List<String>?> = orderSetting(LANDSCAPE_TILE_ORDER)
    val splitShadeControlOrder: StateFlow<List<String>?> = orderSetting(SPLIT_SHADE_CONTROL_ORDER, DEFAULT_CONTROLS_STRING)
    val splitShadeTileOrder: StateFlow<List<String>?> = orderSetting(SPLIT_SHADE_TILE_ORDER)

    val qsSpans: StateFlow<Map<String, AxQsSpan>> = spansSetting(QS_SPANS)
    val qqsSpans: StateFlow<Map<String, AxQsSpan>> = spansSetting(QQS_SPANS)
    val landscapeSpans: StateFlow<Map<String, AxQsSpan>> = spansSetting(LANDSCAPE_SPANS)
    val splitShadeSpans: StateFlow<Map<String, AxQsSpan>> = spansSetting(SPLIT_SHADE_SPANS)

    val qqsControlPositions: StateFlow<Map<String, AxQsGridPosition>> = positionsSetting(QQS_CONTROL_POSITIONS)
    val qsControlPositions: StateFlow<Map<String, AxQsGridPosition>> = positionsSetting(QS_CONTROL_POSITIONS)
    val landscapeControlPositions: StateFlow<Map<String, AxQsGridPosition>> = positionsSetting(LANDSCAPE_CONTROL_POSITIONS)
    val splitShadeControlPositions: StateFlow<Map<String, AxQsGridPosition>> = positionsSetting(SPLIT_SHADE_CONTROL_POSITIONS)

    val panelMode: StateFlow<AxQsPanelMode> =
        userRepository.selectedUserInfo.flatMapLatest { user ->
            systemSettings.observerFlow(user.id, Settings.System.QS_SPLIT_SHADE)
                .onStart { emit(Unit) }
                .map { systemSettings.getIntForUser(Settings.System.QS_SPLIT_SHADE, 0, user.id) }
        }.flowOn(backgroundDispatcher)
            .map(AxQsPanelMode::fromSetting)
            .distinctUntilChanged()
            .stateIn(
                applicationScope,
                SharingStarted.Eagerly,
                AxQsPanelMode.fromSetting(systemSettings.getIntForUser(
                    Settings.System.QS_SPLIT_SHADE, 0, userRepository.getSelectedUserInfo().id
                )),
            )

    val quickPanelOnLeft: StateFlow<Boolean> = boolSetting(QUICK_PANEL_ON_LEFT, false)
    val showWeather: StateFlow<Boolean> = boolSetting(SHOW_WEATHER, true)

    val tileStyle: StateFlow<AxQsTileStyle> =
        intSetting("ax_qs_tile_style", AxQsTileStyle.PILL.settingValue)
            .map(AxQsTileStyle::fromSetting)
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, AxQsTileStyle.PILL)

    val spacing: StateFlow<LunarisQsSpacing> =
        combine(
            intSetting("ax_qs_tile_gap", LunarisQsSpacing().tileGap),
            intSetting("ax_qs_side_padding", LunarisQsSpacing().sidePadding),
            intSetting("ax_qs_top_padding", LunarisQsSpacing().topPadding),
        ) { tileGap, sidePadding, topPadding ->
            LunarisQsSpacing(
                tileGap = tileGap.coerceIn(8, 28),
                sidePadding = sidePadding.coerceIn(8, 40),
                topPadding = topPadding.coerceIn(0, 32),
            )
        }.distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, LunarisQsSpacing())

    val verticalSliderStyles: StateFlow<Map<AxQsVerticalSliderKey, AxQsVerticalSliderStyle>> =
        verticalSliderStyleSettings()

    val gridColumns: StateFlow<Map<AxQsGridLayout, Int>> =
        gridSettings(AxQsGridLayout.entries, ::gridColumnsKey)

    val gridRows: StateFlow<Map<AxQsGridLayout, Int>> =
        gridSettings(AxQsGridLayout.entries.filter { it.section == AxQsGridSection.TILES }, ::gridRowsKey)

    val tileLabels: StateFlow<Map<AxQsGridLayout, Boolean>> = tileLabelSettings()

    fun setOrder(order: List<String>, layout: AxQsLayout, section: AxQsGridSection) {
        putString(sectionOrderKey(layout, section), order.distinct())
    }

    fun setSpan(id: String, span: AxQsSpan, layout: AxQsLayout) {
        val userId = userRepository.getSelectedUserInfo().id
        val key = spansKey(layout)
        applicationScope.launch(backgroundDispatcher) {
            val spans = parseSpans(secureSettings.getStringForUser(key, userId)).toMutableMap()
            spans[id] = span
            val value = spans.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
            secureSettings.putStringForUser(key, value, null, false, userId, true)
        }
    }

    fun setControlPositions(positions: Map<String, AxQsGridPosition>, layout: AxQsLayout) {
        val value = positions.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        putString(controlPositionsKey(layout), value)
    }

    fun setPanelMode(mode: AxQsPanelMode) {
        val userId = userRepository.getSelectedUserInfo().id
        applicationScope.launch(backgroundDispatcher) {
            systemSettings.putIntForUser(Settings.System.QS_SPLIT_SHADE, mode.settingValue, userId)
        }
    }

    fun setQuickPanelOnLeft(onLeft: Boolean) {
        putInt(QUICK_PANEL_ON_LEFT, onLeft.toSetting())
    }

    fun setVerticalSliderStyle(
        layout: AxQsLayout,
        control: AxQsControl,
        style: AxQsVerticalSliderStyle,
    ) {
        putInt(verticalSliderStyleKey(AxQsVerticalSliderKey(layout, control)), style.settingValue)
    }

    fun setColumns(layout: AxQsGridLayout, columns: Int) {
        putInt(gridColumnsKey(layout), columns)
    }

    fun setRows(layout: AxQsGridLayout, rows: Int) {
        putInt(gridRowsKey(layout), rows)
    }

    fun setTileLabels(layout: AxQsGridLayout, showLabels: Boolean) {
        putInt(TILE_LABEL_KEYS.getValue(layout), showLabels.toSetting())
    }

    fun resetLayout(
        defaultControls: List<String> = DEFAULT_CONTROLS_LIST,
        defaultTiles: List<String> = emptyList(),
    ) {
        val userId = userRepository.getSelectedUserInfo().id
        val controlsValue = defaultControls.distinct().joinToString(",")
        val tilesValue = defaultTiles.distinct().joinToString(",")

        val valuesToSet = buildMap {
            CONTROL_ORDER_KEYS.forEach { put(it, controlsValue) }
            TILE_ORDER_KEYS.forEach { put(it, tilesValue) }
            SPANS_KEYS.values.forEach { put(it, DEFAULT_SPANS_STRING) }
            put(LANDSCAPE_SPANS, DEFAULT_SPANS_STRING)
            CONTROL_POSITIONS_KEYS.values.forEach { put(it, "") }
            put(LANDSCAPE_CONTROL_POSITIONS, "")
            LEGACY_ORDER_KEYS.forEach { put(it, "") }
        }

        applicationScope.launch(backgroundDispatcher) {
            valuesToSet.forEach { (key, value) ->
                secureSettings.putStringForUser(key, value, null, false, userId, true)
            }
        }
    }

    fun init() {
        applicationScope.launch(backgroundDispatcher) {
            val userId = userRepository.getSelectedUserInfo().id
            CONTROL_ORDER_KEYS.forEach { key ->
                secureSettings.getStringForUser(key, userId)
            }
            TILE_ORDER_KEYS.forEach { key ->
                secureSettings.getStringForUser(key, userId)
            }
            SPANS_KEYS.values.forEach { key ->
                secureSettings.getStringForUser(key, userId)
            }
        }
    }

    private fun orderSetting(key: String, default: String? = null): StateFlow<List<String>?> =
        stringSetting(key, default)
            .map(::parseOrder)
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, parseOrder(default))

    private fun spansSetting(key: String): StateFlow<Map<String, AxQsSpan>> =
        stringSetting(key, DEFAULT_SPANS_STRING)
            .map(::parseSpans)
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, defaultControlSpans)

    private fun positionsSetting(key: String): StateFlow<Map<String, AxQsGridPosition>> =
        stringSetting(key)
            .map(::parsePositions)
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, emptyMap())

    private fun stringSetting(key: String, default: String? = null): Flow<String?> {
        return userRepository.selectedUserInfo
            .flatMapLatest { user ->
                secureSettings
                    .observerFlow(user.id, key)
                    .onStart { emit(Unit) }
                    .map { secureSettings.getStringForUser(key, user.id) ?: default }
            }
            .flowOn(backgroundDispatcher)
    }

    private fun intSetting(key: String, default: Int): Flow<Int> {
        return userRepository.selectedUserInfo
            .flatMapLatest { user ->
                secureSettings
                    .observerFlow(user.id, key)
                    .onStart { emit(Unit) }
                    .map { secureSettings.getIntForUser(key, default, user.id) }
            }
            .flowOn(backgroundDispatcher)
    }

    private fun boolSetting(key: String, default: Boolean): StateFlow<Boolean> =
        intSetting(key, default.toSetting())
            .map { it != 0 }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, default)

    private fun gridSettings(
        layouts: Iterable<AxQsGridLayout>,
        keyForLayout: (AxQsGridLayout) -> String,
    ): StateFlow<Map<AxQsGridLayout, Int>> {
        return userRepository.selectedUserInfo
            .flatMapLatest { user ->
                combine(
                    layouts.map { layout ->
                        val key = keyForLayout(layout)
                        secureSettings
                            .observerFlow(user.id, key)
                            .onStart { emit(Unit) }
                            .map { layout to secureSettings.getIntForUser(key, 0, user.id) }
                    }
                ) { values ->
                    values
                        .mapNotNull { (layout, value) ->
                            value.takeIf { it > 0 }?.let { layout to it }
                        }
                        .toMap()
                }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)
            .stateIn(applicationScope, SharingStarted.Eagerly, emptyMap())
    }

    private fun tileLabelSettings(): StateFlow<Map<AxQsGridLayout, Boolean>> {
        return userRepository.selectedUserInfo
            .flatMapLatest { user ->
                combine(
                    TILE_LABEL_KEYS.map { (layout, key) ->
                        secureSettings
                            .observerFlow(user.id, key)
                            .onStart { emit(Unit) }
                            .map {
                                layout to
                                    (secureSettings.getIntForUser(
                                        key,
                                        layout.showTileLabelsByDefault.toSetting(),
                                        user.id,
                                    ) != 0)
                            }
                    }
                ) { values -> values.toMap() }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)
            .stateIn(applicationScope, SharingStarted.Eagerly, emptyMap())
    }

    private fun verticalSliderStyleSettings():
        StateFlow<Map<AxQsVerticalSliderKey, AxQsVerticalSliderStyle>> {
        return userRepository.selectedUserInfo
            .flatMapLatest { user ->
                combine(
                    VERTICAL_SLIDER_KEYS.map { slider ->
                        val key = verticalSliderStyleKey(slider)
                        secureSettings
                            .observerFlow(user.id, key)
                            .onStart { emit(Unit) }
                            .map {
                                slider to
                                    AxQsVerticalSliderStyle.fromSetting(
                                        secureSettings.getIntForUser(
                                            key,
                                            AxQsVerticalSliderStyle.M3_EXPRESSIVE.settingValue,
                                            user.id,
                                        )
                                    )
                            }
                    }
                ) { values -> values.toMap() }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)
            .stateIn(applicationScope, SharingStarted.Eagerly, emptyMap())
    }

    private fun putString(key: String, values: List<String>) {
        putString(key, values.filter(String::isNotBlank).joinToString(","))
    }

    private fun putString(
        key: String,
        value: String,
        userId: Int = userRepository.getSelectedUserInfo().id,
    ) {
        applicationScope.launch(backgroundDispatcher) {
            secureSettings.putStringForUser(key, value, null, false, userId, true)
        }
    }

    private fun putInt(key: String, value: Int) {
        val userId = userRepository.getSelectedUserInfo().id
        applicationScope.launch(backgroundDispatcher) {
            secureSettings.putIntForUser(key, value, userId)
        }
    }

    private fun parseOrder(value: String?): List<String>? {
        if (value == null) return null
        return value
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(::normalizeControlId)
            .distinct()
    }

    private fun parseSpans(value: String?): Map<String, AxQsSpan> {
        if (value.isNullOrBlank()) return emptyMap()
        return parseKeyValuePairs(value, AxQsSpan::parse)
    }

    private fun parsePositions(value: String?): Map<String, AxQsGridPosition> {
        if (value.isNullOrBlank()) return emptyMap()
        return parseKeyValuePairs(value, AxQsGridPosition::parse)
    }

    private inline fun <V> parseKeyValuePairs(value: String, parseValue: (String) -> V?): Map<String, V> {
        return buildMap {
            value.split(',').forEach { entry ->
                val separator = entry.lastIndexOf('=')
                if (separator <= 0 || separator == entry.lastIndex) return@forEach
                val id = normalizeControlId(entry.substring(0, separator).trim())
                val parsed = parseValue(entry.substring(separator + 1).trim())
                if (id.isNotEmpty() && parsed != null) put(id, parsed)
            }
        }
    }

    private fun normalizeControlId(id: String): String {
        return when (id) {
            LEGACY_BRIGHTNESS_VERTICAL_ID -> AxQsControl.BRIGHTNESS.id
            LEGACY_VOLUME_VERTICAL_ID -> AxQsControl.VOLUME.id
            LEGACY_RINGER_TILE_ID -> AxQsControl.RINGER.id
            else -> id
        }
    }

    private fun Boolean.toSetting(): Int = if (this) 1 else 0

    private fun spansKey(layout: AxQsLayout): String = SPANS_KEYS.getValue(layout)

    private fun controlPositionsKey(layout: AxQsLayout): String = CONTROL_POSITIONS_KEYS.getValue(layout)

    private fun sectionOrderKey(layout: AxQsLayout, section: AxQsGridSection): String =
        SECTION_ORDER_KEYS.getValue(layout to section)

    private fun gridColumnsKey(layout: AxQsGridLayout): String = GRID_COLUMNS_KEYS.getValue(layout)

    private fun gridRowsKey(layout: AxQsGridLayout): String = GRID_ROWS_KEYS.getValue(layout)

    private fun verticalSliderStyleKey(slider: AxQsVerticalSliderKey): String {
        val prefix =
            when (slider.layout) {
                AxQsLayout.QQS -> "ax_qqs"
                AxQsLayout.QS -> "ax_qs"
                AxQsLayout.SPLIT_SHADE -> "ax_qs_split_shade"
            }
        return when (slider.control) {
            AxQsControl.BRIGHTNESS -> "${prefix}_brightness_vertical_slider_style"
            AxQsControl.VOLUME -> "${prefix}_volume_vertical_slider_style"
            else -> error("Only vertical slider controls have style settings")
        }
    }

    private companion object {
        const val QS_ORDER = "ax_qs_order"
        const val QQS_ORDER = "ax_qqs_order"
        const val QS_SPANS = "ax_qs_spans"
        const val QQS_SPANS = "ax_qqs_spans"
        const val LANDSCAPE_ORDER = "ax_qs_landscape_order"
        const val LANDSCAPE_SPANS = "ax_qs_landscape_spans"
        const val QQS_CONTROL_ORDER = "ax_qqs_control_order"
        const val QQS_TILE_ORDER = "ax_qqs_tile_order"
        const val QS_CONTROL_ORDER = "ax_qs_control_order"
        const val QS_TILE_ORDER = "ax_qs_tile_order"
        const val LANDSCAPE_CONTROL_ORDER = "ax_qs_landscape_control_order"
        const val LANDSCAPE_TILE_ORDER = "ax_qs_landscape_tile_order"
        const val SPLIT_SHADE_CONTROL_ORDER = "ax_qs_split_shade_control_order"
        const val SPLIT_SHADE_TILE_ORDER = "ax_qs_split_shade_tile_order"
        const val QQS_CONTROL_POSITIONS = "ax_qqs_control_positions"
        const val QS_CONTROL_POSITIONS = "ax_qs_control_positions"
        const val LANDSCAPE_CONTROL_POSITIONS = "ax_qs_landscape_control_positions"
        const val SPLIT_SHADE_CONTROL_POSITIONS = "ax_qs_split_shade_control_positions"
        const val SPLIT_SHADE_SPANS = "ax_qs_split_shade_spans"
        const val QUICK_PANEL_ON_LEFT = "ax_qs_quick_panel_on_left"
        const val PORTRAIT_QQS_CONTROL_COLUMNS = "ax_qqs_control_columns"
        const val PORTRAIT_QQS_TILE_COLUMNS = "ax_qqs_tile_columns"
        const val PORTRAIT_QS_CONTROL_COLUMNS = "ax_qs_control_columns"
        const val PORTRAIT_QS_TILE_COLUMNS = "ax_qs_tile_columns"
        const val SPLIT_SHADE_CONTROL_COLUMNS = "ax_qs_split_shade_control_columns"
        const val SPLIT_SHADE_TILE_COLUMNS = "ax_qs_split_shade_tile_columns"
        const val PORTRAIT_QQS_TILE_ROWS = "ax_qqs_tile_rows"
        const val PORTRAIT_QS_TILE_ROWS = "ax_qs_tile_rows"
        const val SPLIT_SHADE_TILE_ROWS = "ax_qs_split_shade_tile_rows"
        const val PORTRAIT_QS_TILE_LABELS = "ax_qs_show_tile_labels"
        const val SPLIT_SHADE_TILE_LABELS = "ax_qs_split_shade_show_tile_labels"
        const val SHOW_WEATHER = "ax_qs_show_weather"

        val VERTICAL_SLIDER_KEYS =
            AxQsLayout.entries.flatMap { layout ->
                listOf(AxQsControl.BRIGHTNESS, AxQsControl.VOLUME).map { control ->
                    AxQsVerticalSliderKey(layout, control)
                }
            }

        val TILE_LABEL_KEYS =
            mapOf(
                AxQsGridLayout.PORTRAIT_QS_TILES to PORTRAIT_QS_TILE_LABELS,
                AxQsGridLayout.SPLIT_SHADE_TILES to SPLIT_SHADE_TILE_LABELS,
            )

        val CONTROL_ORDER_KEYS = listOf(
            QQS_CONTROL_ORDER,
            QS_CONTROL_ORDER,
            LANDSCAPE_CONTROL_ORDER,
            SPLIT_SHADE_CONTROL_ORDER,
        )

        val TILE_ORDER_KEYS = listOf(
            QQS_TILE_ORDER,
            QS_TILE_ORDER,
            LANDSCAPE_TILE_ORDER,
            SPLIT_SHADE_TILE_ORDER,
        )

        val LEGACY_ORDER_KEYS = listOf(
            QS_ORDER,
            QQS_ORDER,
            LANDSCAPE_ORDER,
        )

        val SPANS_KEYS = mapOf(
            AxQsLayout.QQS to QQS_SPANS,
            AxQsLayout.QS to QS_SPANS,
            AxQsLayout.SPLIT_SHADE to SPLIT_SHADE_SPANS,
        )

        val CONTROL_POSITIONS_KEYS = mapOf(
            AxQsLayout.QQS to QQS_CONTROL_POSITIONS,
            AxQsLayout.QS to QS_CONTROL_POSITIONS,
            AxQsLayout.SPLIT_SHADE to SPLIT_SHADE_CONTROL_POSITIONS,
        )

        val SECTION_ORDER_KEYS = mapOf(
            (AxQsLayout.QQS to AxQsGridSection.CONTROLS) to QQS_CONTROL_ORDER,
            (AxQsLayout.QQS to AxQsGridSection.TILES) to QQS_TILE_ORDER,
            (AxQsLayout.QS to AxQsGridSection.CONTROLS) to QS_CONTROL_ORDER,
            (AxQsLayout.QS to AxQsGridSection.TILES) to QS_TILE_ORDER,
            (AxQsLayout.SPLIT_SHADE to AxQsGridSection.CONTROLS) to SPLIT_SHADE_CONTROL_ORDER,
            (AxQsLayout.SPLIT_SHADE to AxQsGridSection.TILES) to SPLIT_SHADE_TILE_ORDER,
        )

        val GRID_COLUMNS_KEYS = mapOf(
            AxQsGridLayout.PORTRAIT_QQS_CONTROLS to PORTRAIT_QQS_CONTROL_COLUMNS,
            AxQsGridLayout.PORTRAIT_QQS_TILES to PORTRAIT_QQS_TILE_COLUMNS,
            AxQsGridLayout.PORTRAIT_QS_CONTROLS to PORTRAIT_QS_CONTROL_COLUMNS,
            AxQsGridLayout.PORTRAIT_QS_TILES to PORTRAIT_QS_TILE_COLUMNS,
            AxQsGridLayout.SPLIT_SHADE_CONTROLS to SPLIT_SHADE_CONTROL_COLUMNS,
            AxQsGridLayout.SPLIT_SHADE_TILES to SPLIT_SHADE_TILE_COLUMNS,
        )

        val GRID_ROWS_KEYS = mapOf(
            AxQsGridLayout.PORTRAIT_QQS_TILES to PORTRAIT_QQS_TILE_ROWS,
            AxQsGridLayout.PORTRAIT_QS_TILES to PORTRAIT_QS_TILE_ROWS,
            AxQsGridLayout.SPLIT_SHADE_TILES to SPLIT_SHADE_TILE_ROWS,
        )

        const val LEGACY_BRIGHTNESS_VERTICAL_ID = "control:brightness_vertical"
        const val LEGACY_VOLUME_VERTICAL_ID = "control:volume_vertical"
        const val LEGACY_RINGER_TILE_ID = "sound"

        const val DEFAULT_NETWORK_ID = "internet"
        val DEFAULT_CONTROLS_LIST =
            listOf(DEFAULT_NETWORK_ID, "bt", AxQsControl.MEDIA.id, AxQsControl.BRIGHTNESS.id, AxQsControl.VOLUME.id)
        val DEFAULT_CONTROLS_STRING = DEFAULT_CONTROLS_LIST.joinToString(",")
        val DEFAULT_SPANS_STRING =
            "bt=2x1,control:brightness=1x2,control:media=2x2,control:volume=1x2,$DEFAULT_NETWORK_ID=2x1"
    }
}
