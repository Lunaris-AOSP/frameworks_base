/*
 * Copyright 2025-2026 AxionOS
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

package com.android.systemui.qs.panels.data.repository

import android.provider.Settings
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
import com.android.systemui.qs.panels.shared.model.AxQsTileStacks
import com.android.systemui.qs.panels.shared.model.AxQsTileStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsGradientSettings
import com.android.systemui.qs.panels.shared.model.LunarisQsMediaStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsPhotoFrame
import com.android.systemui.qs.panels.shared.model.LunarisQsWidget
import com.android.systemui.qs.panels.shared.model.LunarisQsWidgetKey
import com.android.systemui.qs.panels.shared.model.LunarisQsSliderKey
import com.android.systemui.qs.panels.shared.model.LunarisQsSliderStyle
import com.android.systemui.qs.panels.shared.model.LunarisQsSpacing
import com.android.systemui.qs.panels.shared.model.LunarisQsTileFolder
import com.android.systemui.qs.panels.shared.model.LunarisQsTileFolderController
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.util.settings.SystemSettings
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val spansMutex = Mutex()
    val defaultControlSpans: Map<String, AxQsSpan> = parseSpans(DEFAULT_SPANS_STRING)
    val defaultControls: List<String> = DEFAULT_CONTROLS_LIST

    val photoFrames: StateFlow<Map<AxQsLayout, LunarisQsPhotoFrame>> =
        userRepository.selectedUserInfo
            .flatMapLatest { user ->
                secureSettings
                    .observerFlow(
                        user.id,
                        *AxQsLayout.entries.map(LunarisQsPhotoFrame::settingKey).toTypedArray(),
                    )
                    .onStart { emit(Unit) }
                    .map {
                        AxQsLayout.entries.associateWith { layout ->
                            LunarisQsPhotoFrame.fromJson(
                                secureSettings.getStringForUser(
                                    LunarisQsPhotoFrame.settingKey(layout),
                                    user.id,
                                ),
                                user.id,
                            )
                        }
                    }
            }
            .flowOn(backgroundDispatcher)
            .stateIn(applicationScope, SharingStarted.Eagerly, emptyMap())

    val appWidgets: StateFlow<Map<LunarisQsWidgetKey, LunarisQsWidget>> =
        userRepository.selectedUserInfo
            .flatMapLatest { user ->
                secureSettings
                    .observerFlow(
                        user.id,
                        *LunarisQsWidgetKey.entries.map { it.settingKey }.toTypedArray(),
                    )
                    .onStart { emit(Unit) }
                    .map {
                        LunarisQsWidgetKey.entries.associateWith { key ->
                            LunarisQsWidget.fromJson(
                                secureSettings.getStringForUser(key.settingKey, user.id),
                                user.id,
                            )
                        }
                    }
            }
            .flowOn(backgroundDispatcher)
            .stateIn(applicationScope, SharingStarted.Eagerly, emptyMap())

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
    val panelSwipeEnabled: StateFlow<Boolean> = boolSetting(PANEL_SWIPE_ENABLED, false)
    val sharedQsLayout: StateFlow<Boolean> = boolSetting(SHARED_QS_LAYOUT, false)
    val sharedQqsRows: StateFlow<Int> =
        intSetting(SHARED_QQS_ROWS, 2)
            .map { it.coerceIn(2, 4) }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, 2)
    val showWeather: StateFlow<Boolean> = boolSetting(SHOW_WEATHER, true)
    val showNotificationMedia: StateFlow<Boolean> = boolSetting(SHOW_NOTIFICATION_MEDIA, true)
    val notificationMediaStatusGlow: StateFlow<Boolean> =
        boolSetting(NOTIFICATION_MEDIA_STATUS_GLOW, false)
    val mediaTileStatusGlow: StateFlow<Boolean> = boolSetting(MEDIA_TILE_STATUS_GLOW, false)
    val showSliderPercentage: StateFlow<Boolean> = boolSetting(SHOW_SLIDER_PERCENTAGE, false)
    val strokeStyle: StateFlow<Boolean> = boolSetting(STROKE_STYLE, false)

    val fullColorDualTargetTiles: StateFlow<Boolean> =
        userRepository.selectedUserInfo
            .flatMapLatest { user ->
                systemSettings
                    .observerFlow(user.id, Settings.System.DUAL_TARGET_TILE_STYLE)
                    .onStart { emit(Unit) }
                    .map {
                        systemSettings.getIntForUser(
                            Settings.System.DUAL_TARGET_TILE_STYLE,
                            0,
                            user.id,
                        ) == 1
                    }
            }
            .flowOn(backgroundDispatcher)
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    val gradientSettings: StateFlow<LunarisQsGradientSettings> =
        userRepository.selectedUserInfo
            .flatMapLatest { user ->
                secureSettings
                    .observerFlow(
                        user.id,
                        GRADIENT_ENABLED,
                        GRADIENT_SLIDER_ONLY,
                        GRADIENT_COLOR_MODE,
                        GRADIENT_AUTO_STYLE,
                        GRADIENT_ROTATION,
                        GRADIENT_START_COLOR,
                        GRADIENT_END_COLOR,
                    )
                    .onStart { emit(Unit) }
                    .map {
                        LunarisQsGradientSettings(
                            enabled =
                                secureSettings.getIntForUser(GRADIENT_ENABLED, 0, user.id) != 0,
                            sliderOnly =
                                secureSettings.getIntForUser(GRADIENT_SLIDER_ONLY, 0, user.id) != 0,
                            colorMode =
                                secureSettings
                                    .getIntForUser(GRADIENT_COLOR_MODE, 0, user.id)
                                    .coerceIn(0, 1),
                            autoStyle =
                                secureSettings
                                    .getIntForUser(GRADIENT_AUTO_STYLE, 0, user.id)
                                    .coerceIn(0, 1),
                            rotation =
                                secureSettings
                                    .getIntForUser(GRADIENT_ROTATION, 0, user.id)
                                    .coerceIn(0, 360),
                            startColor =
                                secureSettings.getIntForUser(GRADIENT_START_COLOR, 0, user.id),
                            endColor = secureSettings.getIntForUser(GRADIENT_END_COLOR, 0, user.id),
                        )
                    }
            }
            .flowOn(backgroundDispatcher)
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, LunarisQsGradientSettings())

    val tileStyle: StateFlow<AxQsTileStyle> =
        intSetting(TILE_STYLE, AxQsTileStyle.PILL.settingValue)
            .map(AxQsTileStyle::fromSetting)
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, AxQsTileStyle.PILL)

    val spacing: StateFlow<LunarisQsSpacing> =
        combine(
            intSetting(TILE_GAP, LunarisQsSpacing().tileGap),
            intSetting(SIDE_PADDING, LunarisQsSpacing().sidePadding),
            intSetting(TOP_PADDING, LunarisQsSpacing().topPadding),
            intSetting(HEADER_TOP_OFFSET, LunarisQsSpacing().headerTopOffset),
        ) { tileGap, sidePadding, topPadding, headerTopOffset ->
            LunarisQsSpacing(
                tileGap = tileGap.coerceIn(LunarisQsSpacing.TILE_GAP_RANGE),
                sidePadding = sidePadding.coerceIn(LunarisQsSpacing.SIDE_PADDING_RANGE),
                topPadding = topPadding.coerceIn(LunarisQsSpacing.TOP_PADDING_RANGE),
                headerTopOffset = headerTopOffset.coerceIn(LunarisQsSpacing.HEADER_TOP_OFFSET_RANGE),
            )
        }.distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, LunarisQsSpacing())

    val sliderStyles: StateFlow<Map<LunarisQsSliderKey, LunarisQsSliderStyle>> =
        sliderStyleSettings()

    val mediaStyle: StateFlow<LunarisQsMediaStyle> =
        intSetting(MEDIA_STYLE, LunarisQsMediaStyle.ARTWORK.settingValue)
            .map(LunarisQsMediaStyle::fromSetting)
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, LunarisQsMediaStyle.ARTWORK)

    val mediaMotionArt: StateFlow<Boolean> = boolSetting(MEDIA_MOTION_ART, false)

    val gridColumns: StateFlow<Map<AxQsGridLayout, Int>> =
        gridSettings(AxQsGridLayout.entries, ::gridColumnsKey)

    val gridRows: StateFlow<Map<AxQsGridLayout, Int>> =
        gridSettings(AxQsGridLayout.entries.filter { it.section == AxQsGridSection.TILES }, ::gridRowsKey)

    val tileLabels: StateFlow<Map<AxQsGridLayout, Boolean>> = tileLabelSettings()

    val tileStacks: StateFlow<Map<AxQsLayout, Map<String, List<String>>>> =
        userRepository.selectedUserInfo
            .flatMapLatest { user ->
                combine(
                    STACK_KEYS.map { (layout, key) ->
                        secureSettings
                            .observerFlow(user.id, key)
                            .onStart { emit(Unit) }
                            .map {
                                layout to
                                    AxQsTileStacks.decode(
                                        secureSettings.getStringForUser(key, user.id)
                                    )
                            }
                    }
                ) {
                    it.toMap()
                }
            }
            .flowOn(backgroundDispatcher)
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, emptyMap())

    val tileFolders: StateFlow<Map<AxQsLayout, Map<String, LunarisQsTileFolder>>> =
        userRepository.selectedUserInfo
            .flatMapLatest { user ->
                combine(
                    FOLDER_KEYS.map { (layout, key) ->
                        secureSettings
                            .observerFlow(user.id, key)
                            .onStart { emit(Unit) }
                            .map {
                                layout to
                                    LunarisQsTileFolderController.decode(
                                        secureSettings.getStringForUser(key, user.id)
                                    )
                            }
                    }
                ) {
                    it.toMap()
                }
            }
            .flowOn(backgroundDispatcher)
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, emptyMap())

    fun setOrder(order: List<String>, layout: AxQsLayout, section: AxQsGridSection) {
        putString(sectionOrderKey(layout, section), order.distinct())
    }

    fun setSpan(id: String, span: AxQsSpan, layout: AxQsLayout) {
        val userId = userRepository.getSelectedUserInfo().id
        val key = spansKey(layout)
        applicationScope.launch(backgroundDispatcher) {
            spansMutex.withLock {
                val order = controlOrderKeys(key).firstNotNullOfOrNull {
                    secureSettings.getStringForUser(it, userId)
                }
                val spans = parseSpans(secureSettings.getStringForUser(key, userId), order).toMutableMap()
                spans[id] = span
                val value = spans.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
                secureSettings.putStringForUser(key, value, null, false, userId, true)
            }
        }
    }

    fun setControlPositions(positions: Map<String, AxQsGridPosition>, layout: AxQsLayout) {
        val value = positions.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        putString(controlPositionsKey(layout), value)
    }

    fun saveLayout(
        layout: AxQsLayout,
        controls: List<String>,
        tiles: List<String>,
        controlSpans: Map<String, AxQsSpan>,
        positions: Map<String, AxQsGridPosition>,
        stacks: Map<String, List<String>>,
        folders: Map<String, LunarisQsTileFolder>,
    ) {
        val userId = userRepository.getSelectedUserInfo().id
        applicationScope.launch(backgroundDispatcher) {
            spansMutex.withLock {
                val key = spansKey(layout)
                val order =
                    controlOrderKeys(key).firstNotNullOfOrNull {
                        secureSettings.getStringForUser(it, userId)
                    }
                val spans =
                    parseSpans(secureSettings.getStringForUser(key, userId), order) + controlSpans
                val activeStacks = AxQsTileStacks.active(stacks, controls)
                val activeFolders =
                    LunarisQsTileFolderController.active(
                        folders,
                        controls,
                        activeStacks.values.flatten().toSet(),
                    )
                val groupedSpecs =
                    activeStacks.values.flatten().toSet() +
                        activeFolders.values.flatMap { it.tileSpecs }
                val activeControls =
                    controls.filter {
                        it !in groupedSpecs &&
                            (!LunarisQsTileFolderController.isFolderId(it) || it in activeFolders)
                    }
                val folderValue = LunarisQsTileFolderController.encode(activeFolders)
                val values =
                    mapOf(
                        FOLDER_KEYS.getValue(layout) to folderValue,
                        STACK_KEYS.getValue(layout) to AxQsTileStacks.encode(activeStacks),
                        key to
                            spans.entries
                                .sortedBy { it.key }
                                .joinToString(",") { "${it.key}=${it.value}" },
                        controlPositionsKey(layout) to
                            positions.entries
                                .sortedBy { it.key }
                                .joinToString(",") { "${it.key}=${it.value}" },
                        sectionOrderKey(layout, AxQsGridSection.CONTROLS) to
                            activeControls.distinct().joinToString(","),
                        sectionOrderKey(layout, AxQsGridSection.TILES) to
                            tiles.distinct().filterNot(groupedSpecs::contains).joinToString(","),
                    )
                values.forEach { (setting, value) ->
                    secureSettings.putStringForUser(setting, value, null, false, userId, true)
                }
            }
        }
    }

    fun setFolderTileStyle(id: String, layout: AxQsLayout, style: AxQsTileStyle?) =
        updateFolder(id, layout) { it.copy(tileStyle = style) }

    fun setFolderTileSpan(id: String, layout: AxQsLayout, spec: String, span: AxQsSpan) =
        updateFolder(id, layout) { folder ->
            if (spec in folder.tileSpecs) {
                folder.copy(tileSpans = folder.tileSpans + (spec to span.coerceForControlTile(4)))
            } else {
                folder
            }
        }

    private fun updateFolder(
        id: String,
        layout: AxQsLayout,
        update: (LunarisQsTileFolder) -> LunarisQsTileFolder,
    ) {
        val userId = userRepository.getSelectedUserInfo().id
        applicationScope.launch(backgroundDispatcher) {
            spansMutex.withLock {
                val currentKey = FOLDER_KEYS.getValue(layout)
                val folders =
                    LunarisQsTileFolderController.decode(
                        secureSettings.getStringForUser(currentKey, userId)
                    )
                val folder = folders[id] ?: return@withLock
                val value =
                    LunarisQsTileFolderController.encode(folders + (id to update(folder)))
                secureSettings.putStringForUser(currentKey, value, null, false, userId, true)
            }
        }
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

    fun setPanelSwipeEnabled(enabled: Boolean) = putInt(PANEL_SWIPE_ENABLED, enabled.toSetting())

    fun setSharedQsLayout(enabled: Boolean) = putInt(SHARED_QS_LAYOUT, enabled.toSetting())

    fun setSharedQqsRows(rows: Int) = putInt(SHARED_QQS_ROWS, rows.coerceIn(2, 4))

    fun setShowWeather(show: Boolean) = putInt(SHOW_WEATHER, show.toSetting())

    fun setShowNotificationMedia(show: Boolean) = putInt(SHOW_NOTIFICATION_MEDIA, show.toSetting())

    fun setNotificationMediaStatusGlow(enabled: Boolean) =
        putInt(NOTIFICATION_MEDIA_STATUS_GLOW, enabled.toSetting())

    fun setMediaTileStatusGlow(enabled: Boolean) = putInt(MEDIA_TILE_STATUS_GLOW, enabled.toSetting())

    fun setShowSliderPercentage(show: Boolean) = putInt(SHOW_SLIDER_PERCENTAGE, show.toSetting())

    fun setStrokeStyle(enabled: Boolean) = putInt(STROKE_STYLE, enabled.toSetting())

    fun setFullColorDualTargetTiles(enabled: Boolean) {
        val userId = userRepository.getSelectedUserInfo().id
        applicationScope.launch(backgroundDispatcher) {
            systemSettings.putIntForUser(
                Settings.System.DUAL_TARGET_TILE_STYLE,
                enabled.toSetting(),
                userId,
            )
        }
    }

    fun setGradientEnabled(enabled: Boolean) = putInt(GRADIENT_ENABLED, enabled.toSetting())

    fun setGradientSliderOnly(sliderOnly: Boolean) =
        putInt(GRADIENT_SLIDER_ONLY, sliderOnly.toSetting())

    fun setGradientColorMode(mode: Int) = putInt(GRADIENT_COLOR_MODE, mode.coerceIn(0, 1))

    fun setGradientAutoStyle(style: Int) = putInt(GRADIENT_AUTO_STYLE, style.coerceIn(0, 1))

    fun setGradientRotation(rotation: Int) = putInt(GRADIENT_ROTATION, rotation.coerceIn(0, 360))

    fun setGradientStartColor(color: Int) = putInt(GRADIENT_START_COLOR, color)

    fun setGradientEndColor(color: Int) = putInt(GRADIENT_END_COLOR, color)

    fun setTileStyle(style: AxQsTileStyle) = putInt(TILE_STYLE, style.settingValue)

    fun setMediaStyle(style: LunarisQsMediaStyle) = putInt(MEDIA_STYLE, style.settingValue)

    fun setMediaMotionArt(enabled: Boolean) = putInt(MEDIA_MOTION_ART, enabled.toSetting())

    fun setTileGap(gap: Int) = putInt(TILE_GAP, gap.coerceIn(LunarisQsSpacing.TILE_GAP_RANGE))

    fun setSidePadding(padding: Int) =
        putInt(SIDE_PADDING, padding.coerceIn(LunarisQsSpacing.SIDE_PADDING_RANGE))

    fun setTopPadding(padding: Int) =
        putInt(TOP_PADDING, padding.coerceIn(LunarisQsSpacing.TOP_PADDING_RANGE))

    fun setHeaderTopOffset(offset: Int) =
        putInt(HEADER_TOP_OFFSET, offset.coerceIn(LunarisQsSpacing.HEADER_TOP_OFFSET_RANGE))

    fun setSliderStyle(
        layout: AxQsLayout,
        control: AxQsControl,
        style: LunarisQsSliderStyle,
    ) {
        putInt(sliderStyleKey(LunarisQsSliderKey(layout, control)), style.settingValue)
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
            STACK_KEYS.values.forEach { put(it, "") }
            FOLDER_KEYS.values.forEach { put(it, "") }
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

    private fun spansSetting(key: String): StateFlow<Map<String, AxQsSpan>> {
        val values =
            if (key == SPLIT_SHADE_SPANS) {
                combine(stringSetting(LANDSCAPE_SPANS, DEFAULT_SPANS_STRING), stringSetting(key)) {
                    inherited, current -> listOfNotNull(inherited, current).joinToString(",")
                }
            } else {
                stringSetting(key, DEFAULT_SPANS_STRING)
            }
        return combine(values, controlOrderSetting(key), ::parseSpans)
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, defaultControlSpans)
    }

    private fun positionsSetting(key: String): StateFlow<Map<String, AxQsGridPosition>> =
        combine(stringSetting(key), controlOrderSetting(key), ::parsePositions)
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, emptyMap())

    private fun controlOrderKeys(key: String): List<String> =
        when (key) {
            QQS_SPANS, QQS_CONTROL_POSITIONS -> listOf(QQS_CONTROL_ORDER, QQS_ORDER)
            QS_SPANS, QS_CONTROL_POSITIONS -> listOf(QS_CONTROL_ORDER, QS_ORDER)
            LANDSCAPE_SPANS, LANDSCAPE_CONTROL_POSITIONS ->
                listOf(LANDSCAPE_CONTROL_ORDER, LANDSCAPE_ORDER)
            SPLIT_SHADE_SPANS, SPLIT_SHADE_CONTROL_POSITIONS ->
                listOf(SPLIT_SHADE_CONTROL_ORDER, LANDSCAPE_CONTROL_ORDER, LANDSCAPE_ORDER)
            else -> error("Unknown control layout setting: $key")
        }

    private fun controlOrderSetting(key: String): Flow<String?> =
        combine(controlOrderKeys(key).map { stringSetting(it) }) { orders ->
            orders.firstOrNull { it != null }
        }

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

    private fun sliderStyleSettings():
        StateFlow<Map<LunarisQsSliderKey, LunarisQsSliderStyle>> {
        return userRepository.selectedUserInfo
            .flatMapLatest { user ->
                combine(
                    SLIDER_KEYS.map { slider ->
                        val key = sliderStyleKey(slider)
                        secureSettings
                            .observerFlow(user.id, key)
                            .onStart { emit(Unit) }
                            .map {
                                slider to
                                    LunarisQsSliderStyle.fromSetting(
                                        secureSettings.getIntForUser(
                                            key,
                                            LunarisQsSliderStyle.M3_EXPRESSIVE.settingValue,
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

    private fun parseSpans(value: String?, order: String? = null): Map<String, AxQsSpan> {
        val spans = parseKeyValuePairs(value.orEmpty(), AxQsSpan::parse)
        return normalizeControlValues(spans, order) { id ->
            if (id in LEGACY_HORIZONTAL_SLIDER_IDS) AxQsSpan.TileWideDefault else null
        }
    }

    private fun parsePositions(value: String?, order: String?): Map<String, AxQsGridPosition> {
        return normalizeControlValues(
            parseKeyValuePairs(value.orEmpty(), AxQsGridPosition::parse),
            order,
        )
    }

    private inline fun <V> parseKeyValuePairs(value: String, parseValue: (String) -> V?): Map<String, V> {
        return buildMap {
            value.split(',').forEach { entry ->
                val separator = entry.lastIndexOf('=')
                if (separator <= 0 || separator == entry.lastIndex) return@forEach
                val id = entry.substring(0, separator).trim()
                val parsed = parseValue(entry.substring(separator + 1).trim())
                if (id.isNotEmpty() && parsed != null) put(id, parsed)
            }
        }
    }

    private fun <V> normalizeControlValues(
        values: Map<String, V>,
        order: String?,
        defaultValue: (String) -> V? = { null },
    ): Map<String, V> {
        val result = values.mapKeys { normalizeControlId(it.key) }.toMutableMap()
        val ids = order?.split(',')?.map(String::trim) ?: values.keys.toList()
        listOf(AxQsControl.BRIGHTNESS, AxQsControl.VOLUME).forEach { control ->
            val aliases = ids.filter { normalizeControlId(it) == control.id }
            val preferred =
                aliases.firstOrNull { it == control.id }
                    ?: aliases.firstOrNull { it !in LEGACY_HORIZONTAL_SLIDER_IDS }
                    ?: aliases.firstOrNull()
            if (preferred != null) {
                result.remove(control.id)
                val resolved =
                    values[preferred]
                        ?: if (preferred in LEGACY_HORIZONTAL_SLIDER_IDS) {
                            defaultValue(preferred)
                        } else {
                            values[control.id]
                        }
                resolved?.let { result[control.id] = it }
            }
        }
        return result
    }

    private fun normalizeControlId(id: String): String {
        return when (id) {
            LEGACY_BRIGHTNESS_VERTICAL_ID, LEGACY_BRIGHTNESS_HORIZONTAL_ID -> AxQsControl.BRIGHTNESS.id
            LEGACY_VOLUME_VERTICAL_ID, LEGACY_VOLUME_HORIZONTAL_ID -> AxQsControl.VOLUME.id
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

    private fun sliderStyleKey(slider: LunarisQsSliderKey): String {
        val prefix =
            when (slider.layout) {
                AxQsLayout.QQS -> "ax_qqs"
                AxQsLayout.QS -> "ax_qs"
                AxQsLayout.SPLIT_SHADE -> "ax_qs_split_shade"
            }
        return when (slider.control) {
            AxQsControl.BRIGHTNESS -> "${prefix}_brightness_vertical_slider_style"
            AxQsControl.VOLUME -> "${prefix}_volume_vertical_slider_style"
            AxQsControl.FLASHLIGHT -> "${prefix}_flashlight_slider_style"
            else -> error("Only slider controls have style settings")
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
        const val PANEL_SWIPE_ENABLED = "lunaris_qs_panel_swipe_enabled"
        const val SHARED_QS_LAYOUT = "lunaris_qs_shared_layout"
        const val SHARED_QQS_ROWS = "lunaris_qs_shared_qqs_rows"
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
        const val SHOW_NOTIFICATION_MEDIA = "ax_qs_show_notification_media"
        private const val NOTIFICATION_MEDIA_STATUS_GLOW = "ax_qs_notification_media_status_glow"
        private const val MEDIA_TILE_STATUS_GLOW = "ax_qs_media_tile_status_glow"
        const val SHOW_SLIDER_PERCENTAGE = "ax_qs_show_slider_percentage"
        private const val STROKE_STYLE = "ax_qs_stroke_style"
        private const val GRADIENT_ENABLED = "ax_qs_gradient_enabled"
        private const val GRADIENT_SLIDER_ONLY = "ax_qs_gradient_slider_only"
        private const val GRADIENT_COLOR_MODE = "ax_qs_gradient_color_mode"
        private const val GRADIENT_AUTO_STYLE = "ax_qs_gradient_auto_style"
        private const val GRADIENT_ROTATION = "ax_qs_gradient_rotation"
        private const val GRADIENT_START_COLOR = "ax_qs_gradient_start_color"
        private const val GRADIENT_END_COLOR = "ax_qs_gradient_end_color"
        const val TILE_STYLE = "ax_qs_tile_style"
        private const val MEDIA_STYLE = "ax_qs_media_style"
        private const val MEDIA_MOTION_ART = "ax_qs_media_motion_art"
        const val TILE_GAP = "ax_qs_tile_gap"
        const val SIDE_PADDING = "ax_qs_side_padding"
        const val TOP_PADDING = "ax_qs_top_padding"
        const val HEADER_TOP_OFFSET = "ax_qs_header_top_offset"

        val SLIDER_KEYS =
            AxQsLayout.entries.flatMap { layout ->
                AxQsControl.entries.filter { it.isSlider }.map { control ->
                    LunarisQsSliderKey(layout, control)
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

        val FOLDER_KEYS =
            mapOf(
                AxQsLayout.QQS to "lunaris_qqs_tile_folders",
                AxQsLayout.QS to "lunaris_qs_tile_folders",
                AxQsLayout.SPLIT_SHADE to "lunaris_qs_split_shade_tile_folders",
            )

        val STACK_KEYS =
            mapOf(
                AxQsLayout.QQS to "ax_qqs_tile_stacks",
                AxQsLayout.QS to "ax_qs_tile_stacks",
                AxQsLayout.SPLIT_SHADE to "ax_qs_split_shade_tile_stacks",
            )

        val SPANS_KEYS =
            mapOf(
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
        const val LEGACY_BRIGHTNESS_HORIZONTAL_ID = "control:brightness_horizontal"
        const val LEGACY_VOLUME_HORIZONTAL_ID = "control:volume_horizontal"
        val LEGACY_HORIZONTAL_SLIDER_IDS =
            setOf(LEGACY_BRIGHTNESS_HORIZONTAL_ID, LEGACY_VOLUME_HORIZONTAL_ID)
        const val LEGACY_RINGER_TILE_ID = "sound"

        const val DEFAULT_NETWORK_ID = "internet"
        val DEFAULT_CONTROLS_LIST =
            listOf(DEFAULT_NETWORK_ID, "bt", AxQsControl.MEDIA.id, AxQsControl.BRIGHTNESS.id, AxQsControl.VOLUME.id)
        val DEFAULT_CONTROLS_STRING = DEFAULT_CONTROLS_LIST.joinToString(",")
        val DEFAULT_SPANS_STRING =
            "bt=2x1,control:brightness=1x2,control:media=2x2,control:volume=1x2,$DEFAULT_NETWORK_ID=2x1"
    }
}
