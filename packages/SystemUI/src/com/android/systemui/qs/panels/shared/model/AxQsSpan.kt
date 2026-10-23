/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.qs.panels.shared.model

import androidx.compose.runtime.Immutable

@Immutable
data class AxQsSpan(val columns: Int, val rows: Int) {
    init {
        require(columns in MIN_COLUMNS..MAX_COLUMNS)
        require(rows in MIN_ROWS..MAX_ROWS)
    }

    fun coerceIn(min: AxQsSpan, max: AxQsSpan): AxQsSpan {
        return AxQsSpan(
            columns = columns.coerceIn(min.columns, max.columns),
            rows = rows.coerceIn(min.rows, max.rows),
        )
    }

    fun coerceForControlTile(columns: Int): AxQsSpan {
        return coerceIn(ControlTileMin, controlTileMax(columns))
    }

    override fun toString(): String = "${columns}x$rows"

    companion object {
        const val MIN_COLUMNS = 1
        const val MAX_COLUMNS = 16
        const val MIN_ROWS = 1
        const val MAX_ROWS = 4

        val TileDefault = AxQsSpan(1, 1)
        val TileWideDefault = AxQsSpan(2, 1)
        val ControlTileMin = TileDefault
        val MediaDefault = AxQsSpan(2, 2)
        val MediaMin = AxQsSpan(2, 1)
        val Max = AxQsSpan(4, 4)

        fun controlTileMax(columns: Int): AxQsSpan {
            return AxQsSpan(
                columns.coerceAtLeast(ControlTileMin.columns).coerceAtMost(Max.columns),
                Max.rows,
            )
        }

        fun parse(value: String): AxQsSpan? {
            val parts = value.split('x', limit = 2)
            if (parts.size != 2) return null
            val columns = parts[0].toIntOrNull() ?: return null
            val rows = parts[1].toIntOrNull() ?: return null
            if (columns !in MIN_COLUMNS..MAX_COLUMNS || rows !in MIN_ROWS..MAX_ROWS) return null
            return AxQsSpan(columns, rows)
        }
    }
}

enum class AxQsControl(
    val id: String,
    val defaultSpan: AxQsSpan,
    val minSpan: AxQsSpan,
    val maxSpan: AxQsSpan,
) {
    BRIGHTNESS(
        id = "control:brightness",
        defaultSpan = AxQsSpan(1, 2),
        minSpan = AxQsSpan.TileDefault,
        maxSpan = AxQsSpan(AxQsSpan.MAX_COLUMNS, AxQsSpan.MAX_ROWS),
    ),
    VOLUME(
        id = "control:volume",
        defaultSpan = AxQsSpan(1, 2),
        minSpan = AxQsSpan.TileDefault,
        maxSpan = AxQsSpan(AxQsSpan.MAX_COLUMNS, AxQsSpan.MAX_ROWS),
    ),
    FLASHLIGHT(
        id = "control:flashlight",
        defaultSpan = AxQsSpan(1, 2),
        minSpan = AxQsSpan.TileDefault,
        maxSpan = AxQsSpan(AxQsSpan.MAX_COLUMNS, AxQsSpan.MAX_ROWS),
    ),
    AUTO_BRIGHTNESS(
        id = "control:auto_brightness",
        defaultSpan = AxQsSpan.TileDefault,
        minSpan = AxQsSpan.TileDefault,
        maxSpan = AxQsSpan.TileDefault,
    ),
    VOLUME_MUTE(
        id = "control:volume_mute",
        defaultSpan = AxQsSpan.TileDefault,
        minSpan = AxQsSpan.TileDefault,
        maxSpan = AxQsSpan.TileDefault,
    ),
    RINGER(
        id = "control:ringer",
        defaultSpan = AxQsSpan(4, 1),
        minSpan = AxQsSpan.TileDefault,
        maxSpan = AxQsSpan.Max,
    ),
    MEDIA(
        id = "control:media",
        defaultSpan = AxQsSpan.MediaDefault,
        minSpan = AxQsSpan.MediaMin,
        maxSpan = AxQsSpan(4, 2),
    ),
    PHOTO(
        id = "control:photo",
        defaultSpan = AxQsSpan(2, 2),
        minSpan = AxQsSpan.TileDefault,
        maxSpan = AxQsSpan(2, 2),
    ),
    APP_WIDGET(
        id = "control:app_widget",
        defaultSpan = AxQsSpan(2, 2),
        minSpan = AxQsSpan.TileDefault,
        maxSpan = AxQsSpan(4, 4),
    ),
    APP_WIDGET_2(
        id = "control:app_widget_2",
        defaultSpan = AxQsSpan(2, 2),
        minSpan = AxQsSpan.TileDefault,
        maxSpan = AxQsSpan(4, 4),
    ),
    APP_WIDGET_3(
        id = "control:app_widget_3",
        defaultSpan = AxQsSpan(2, 2),
        minSpan = AxQsSpan.TileDefault,
        maxSpan = AxQsSpan(4, 4),
    );

    fun spans(columns: Int): AxQsControlSpans {
        val resolvedMax =
            if (isResizableContent) {
                maxSpan.copy(columns = columns.coerceIn(1, maxSpan.columns))
            } else if (this == MEDIA || isSlider || this == RINGER) {
                maxSpan.copy(columns = columns.coerceIn(minSpan.columns, AxQsSpan.MAX_COLUMNS))
            } else {
                maxSpan
            }
        return AxQsControlSpans(defaultSpan.coerceIn(minSpan, resolvedMax), minSpan, resolvedMax)
    }

    fun isSpanAllowed(span: AxQsSpan, columns: Int): Boolean {
        return span == coerceSpan(span, columns)
    }

    fun coerceSpan(span: AxQsSpan, columns: Int): AxQsSpan {
        val spans = spans(columns)
        return when {
            isResizableContent -> span.coerceIn(spans.min, spans.max)
            supportsOrientation && span.columns == 1 ->
                AxQsSpan(1, span.rows.coerceIn(minOrientationLength, spans.max.rows))
            supportsOrientation ->
                AxQsSpan(span.columns.coerceIn(minOrientationLength, spans.max.columns), 1)
            else -> span.coerceIn(spans.min, spans.max)
        }
    }

    fun nextSpan(span: AxQsSpan, columns: Int): AxQsSpan {
        if (isResizableContent) {
            val sizes = contentSizes(columns)
            return sizes[(sizes.indexOf(span) + 1) % sizes.size]
        }
        if (!supportsOrientation) return span
        val spans = spans(columns)
        if (this == RINGER && span == AxQsSpan.TileDefault) {
            return AxQsSpan(2.coerceAtMost(spans.max.columns), 1)
        }
        return if (span.columns == 1) {
            val rows = if (span.rows < spans.max.rows) span.rows + 1 else minOrientationLength
            AxQsSpan(1, rows)
        } else {
            val width =
                if (span.columns < spans.max.columns) span.columns + 1 else minOrientationLength
            AxQsSpan(width, 1)
        }
    }

    fun resizeSpan(startSpan: AxQsSpan, columnDelta: Int, rowDelta: Int, columns: Int): AxQsSpan {
        if (isResizableContent) {
            return AxQsSpan(
                (startSpan.columns + columnDelta).coerceIn(1, spans(columns).max.columns),
                (startSpan.rows + rowDelta).coerceIn(1, maxSpan.rows),
            )
        }
        if (!supportsOrientation) {
            return AxQsSpan(
                columns =
                    (startSpan.columns + columnDelta).coerceIn(
                        AxQsSpan.MIN_COLUMNS,
                        AxQsSpan.MAX_COLUMNS,
                    ),
                rows = (startSpan.rows + rowDelta).coerceIn(AxQsSpan.MIN_ROWS, AxQsSpan.MAX_ROWS),
            )
        }
        val spans = spans(columns)
        val vertical =
            if (this == RINGER && startSpan == AxQsSpan.TileDefault) rowDelta > columnDelta
            else startSpan.columns == 1
        return if (vertical) {
            AxQsSpan(1, (startSpan.rows + rowDelta).coerceIn(minOrientationLength, spans.max.rows))
        } else {
            AxQsSpan(
                (startSpan.columns + columnDelta).coerceIn(
                    minOrientationLength,
                    spans.max.columns,
                ),
                1,
            )
        }
    }

    fun rotateSpan(span: AxQsSpan, columns: Int): AxQsSpan {
        if (isResizableContent) return coerceSpan(AxQsSpan(span.rows, span.columns), columns)
        if (!supportsOrientation) return span
        return if (span.columns == 1) {
            AxQsSpan(span.rows.coerceIn(minOrientationLength, columns), 1)
        } else {
            AxQsSpan(1, span.columns.coerceIn(minOrientationLength, AxQsSpan.MAX_ROWS))
        }
    }

    val isSlider: Boolean
        get() = this == BRIGHTNESS || this == VOLUME || this == FLASHLIGHT

    val supportsOrientation: Boolean
        get() = isSlider || this == RINGER || isResizableContent

    val isResizableContent: Boolean
        get() = this == PHOTO || isAppWidget

    val isAppWidget: Boolean
        get() = this == APP_WIDGET || this == APP_WIDGET_2 || this == APP_WIDGET_3

    fun contentSizes(columns: Int): List<AxQsSpan> =
        if (this == PHOTO) photoSizes(columns)
        else
            (1..maxSpan.rows).flatMap { rows ->
                (1..spans(columns).max.columns).map { width -> AxQsSpan(width, rows) }
            }

    fun photoSizes(columns: Int): List<AxQsSpan> =
        listOf(AxQsSpan(1, 1), AxQsSpan(2, 1), AxQsSpan(2, 2), AxQsSpan(1, 2)).filter {
            it.columns <= columns.coerceAtLeast(1)
        }

    private val minOrientationLength: Int
        get() = if (this == RINGER) 1 else 2

    /**
     * Whether this control should appear in the QS Edit grid.
     *
     * Slider controls (BRIGHTNESS*, VOLUME*) and AUTO_BRIGHTNESS are already
     * configurable through Mistify → Panel Customisations → Sliders & Appearance.
     * Showing them here too creates duplicate configuration, so they are hidden
     * from the edit grid while remaining fully functional in the live QS panel.
     */
    val showInEditGrid: Boolean
        get() = true

    val canUseTileGrid: Boolean
        get() =
            defaultSpan == AxQsSpan.TileDefault &&
                minSpan == AxQsSpan.TileDefault &&
                maxSpan == AxQsSpan.TileDefault
}

data class AxQsControlSpans(val default: AxQsSpan, val min: AxQsSpan, val max: AxQsSpan)

data class AxQsGridPosition(val column: Int, val row: Int) {
    init {
        require(column >= 0)
        require(row >= 0)
    }

    override fun toString(): String = "$column:$row"

    companion object {
        fun parse(value: String): AxQsGridPosition? {
            val parts = value.split(':', limit = 2)
            if (parts.size != 2) return null
            val column = parts[0].toIntOrNull() ?: return null
            val row = parts[1].toIntOrNull() ?: return null
            if (column < 0 || row < 0) return null
            return AxQsGridPosition(column, row)
        }
    }
}

enum class AxQsGridSection {
    CONTROLS,
    TILES,
}

enum class AxQsGridLayout(
    val layout: AxQsLayout,
    val section: AxQsGridSection,
) {
    PORTRAIT_QQS_CONTROLS(AxQsLayout.QQS, AxQsGridSection.CONTROLS),
    PORTRAIT_QQS_TILES(AxQsLayout.QQS, AxQsGridSection.TILES),
    PORTRAIT_QS_CONTROLS(AxQsLayout.QS, AxQsGridSection.CONTROLS),
    PORTRAIT_QS_TILES(AxQsLayout.QS, AxQsGridSection.TILES),
    SPLIT_SHADE_CONTROLS(AxQsLayout.SPLIT_SHADE, AxQsGridSection.CONTROLS),
    SPLIT_SHADE_TILES(AxQsLayout.SPLIT_SHADE, AxQsGridSection.TILES);

    val isSplitShade: Boolean
        get() = layout == AxQsLayout.SPLIT_SHADE

    val supportsTileLabels: Boolean
        get() = section == AxQsGridSection.TILES && layout != AxQsLayout.QQS

    val showTileLabelsByDefault: Boolean
        get() = supportsTileLabels

    fun columnRange(defaultColumns: Int): IntRange {
        return (defaultColumns - 1).coerceAtLeast(MIN_COLUMNS)..(defaultColumns + 1)
    }

    companion object {
        fun from(layout: AxQsLayout, section: AxQsGridSection): AxQsGridLayout {
            return entries.first { it.layout == layout && it.section == section }
        }

        private const val MIN_COLUMNS = 2
    }
}

enum class AxQsLayout {
    QQS,
    QS,
    SPLIT_SHADE,
}

enum class AxQsPanelMode(val settingValue: Int) {
    TOGETHER(0),
    SEPARATE(1);

    companion object {
        fun fromSetting(value: Int): AxQsPanelMode =
            entries.firstOrNull { it.settingValue == value } ?: TOGETHER
    }
}

enum class AxQsTileStyle(val settingValue: Int) {
    PILL(0),
    ROUNDED_SQUARE(1),
    DYNAMIC(2),
    DYNAMIC_INVERTED(3);

    fun usesPillShape(isActive: Boolean): Boolean =
        when (this) {
            PILL -> true
            ROUNDED_SQUARE -> false
            DYNAMIC -> isActive
            DYNAMIC_INVERTED -> !isActive
        }

    companion object {
        fun fromSetting(value: Int): AxQsTileStyle =
            entries.firstOrNull { it.settingValue == value } ?: PILL
    }
}

object AxQsLayoutPadding {
    const val PORTRAIT_SIDE_FRACTION = 0.0637f
    const val LANDSCAPE_SIDE_FRACTION = 0.07f
    const val LANDSCAPE_SPLIT_GRID_SPACING_DP = 28f
}
