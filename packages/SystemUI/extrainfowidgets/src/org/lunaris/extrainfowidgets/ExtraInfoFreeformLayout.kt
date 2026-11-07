/*
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

package org.lunaris.extrainfowidgets

import org.json.JSONArray
import org.json.JSONObject

data class InfoWidgetPosition(
    val id: String,
    val x: Float,
    val y: Float,
    val scale: Float = ExtraInfoFreeformLayout.DEFAULT_SCALE,
)

data class ExtraInfoFreeformLayout(val widgets: List<InfoWidgetPosition> = emptyList()) {
    fun encode(): String =
        JSONObject()
            .put(
                "widgets",
                JSONArray().apply {
                    widgets.take(MAX_WIDGETS).forEach {
                        put(
                            JSONObject()
                                .put("id", it.id)
                                .put("x", coordinate(it.x, 0.5f))
                                .put("y", coordinate(it.y, 0.5f))
                                .put("scale", clampScale(it.scale))
                        )
                    }
                },
            )
            .toString()

    fun add(widget: InfoWidget): ExtraInfoFreeformLayout? {
        if (widgets.size >= MAX_WIDGETS || widgets.any { it.id == widget.id }) return null
        return copy(widgets = widgets + InfoWidgetPosition(widget.id, 0.5f, 0.35f))
    }

    fun remove(id: String) = copy(widgets = widgets.filterNot { it.id == id })

    fun move(id: String, x: Float, y: Float) =
        copy(
            widgets =
                widgets.map {
                    if (it.id == id) it.copy(x = coordinate(x, it.x), y = coordinate(y, it.y))
                    else it
                }
        )

    fun resize(id: String, scale: Float) =
        copy(widgets = widgets.map { if (it.id == id) it.copy(scale = clampScale(scale)) else it })

    fun bringToFront(id: String): ExtraInfoFreeformLayout {
        val widget = widgets.firstOrNull { it.id == id } ?: return this
        return copy(widgets = widgets.filterNot { it.id == id } + widget)
    }

    companion object {
        const val MODE = "lockscreen_extra_info_widgets_independent"
        const val LAYOUT = "lockscreen_extra_info_widgets_freeform_layout"
        const val MAX_WIDGETS = 5
        const val DEFAULT_SCALE = 1f
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2f

        fun clampScale(value: Float) =
            if (value.isFinite()) value.coerceIn(MIN_SCALE, MAX_SCALE) else DEFAULT_SCALE

        private fun coordinate(value: Float, fallback: Float) =
            if (value.isFinite()) value.coerceIn(0f, 1f) else fallback

        fun fromRows(layout: ExtraInfoLayout, catalog: List<InfoWidget>): ExtraInfoFreeformLayout {
            val positions = mutableListOf<InfoWidgetPosition>()
            layout.rows.forEachIndexed { row, ids ->
                val widgets = ids.mapNotNull { id -> catalog.firstOrNull { it.id == id } }
                widgets.forEachIndexed { index, widget ->
                    positions +=
                        InfoWidgetPosition(
                            widget.id,
                            (index + 1f) / (widgets.size + 1f),
                            0.35f + row * 0.12f,
                        )
                }
            }
            return ExtraInfoFreeformLayout(positions.distinctBy { it.id }.take(MAX_WIDGETS))
        }

        fun parse(
            raw: String?,
            catalog: List<InfoWidget>,
            fallback: ExtraInfoLayout = ExtraInfoLayout(),
        ): ExtraInfoFreeformLayout {
            val json =
                raw?.takeIf { it.length <= 8192 }
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val source = json?.optJSONArray("widgets") ?: return fromRows(fallback, catalog)
            val seen = mutableSetOf<String>()
            val positions = buildList {
                for (i in 0 until minOf(source.length(), 64)) {
                    val entry = source.optJSONObject(i) ?: continue
                    val id = entry.optString("id")
                    if (catalog.none { it.id == id } || !seen.add(id)) continue
                    add(
                        InfoWidgetPosition(
                            id,
                            coordinate(entry.optDouble("x", 0.5).toFloat(), 0.5f),
                            coordinate(entry.optDouble("y", 0.35).toFloat(), 0.35f),
                            clampScale(entry.optDouble("scale", DEFAULT_SCALE.toDouble()).toFloat()),
                        )
                    )
                    if (size == MAX_WIDGETS) break
                }
            }
            return ExtraInfoFreeformLayout(positions)
        }
    }
}
