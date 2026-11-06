/* SPDX-License-Identifier: GPL-3.0-only */
package org.lunaris.extrainfowidgets

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class InfoWidget(val id: String, val design: JSONObject) {
    val name: String = design.getString("name")
    val category: String = design.getString("category")

    fun span(columns: Int): Int =
        if (category == "inline") columns else design.optInt("span", 1).coerceIn(1, columns)
}

object ExtraInfoCatalog {
    private var cached: List<InfoWidget>? = null

    @Synchronized
    fun load(context: Context): List<InfoWidget> =
        cached
            ?: context.assets
                .list("extra_info_widgets")
                .orEmpty()
                .filter { it.endsWith(".json") }
                .sorted()
                .map { file ->
                    val json =
                        context.assets.open("extra_info_widgets/$file").bufferedReader().use {
                            JSONObject(it.readText())
                        }
                    InfoWidget(file.removeSuffix(".json"), json)
                }
                .also { cached = it }
}

data class ExtraInfoLayout(val columns: Int = 4, val rows: List<List<String>> = DEFAULT_ROWS) {
    fun encode(): String =
        JSONObject()
            .put("columns", columns)
            .put("rows", JSONArray().apply { rows.forEach { put(JSONArray(it)) } })
            .toString()

    fun used(row: Int, catalog: List<InfoWidget>): Int =
        rows[row].sumOf { id -> catalog.firstOrNull { it.id == id }?.span(columns) ?: 0 }

    fun withColumns(value: Int, catalog: List<InfoWidget>): ExtraInfoLayout? {
        val next = copy(columns = value.coerceIn(4, 5))
        return next.takeIf { (0..1).all { row -> next.used(row, catalog) <= next.columns } }
    }

    fun add(row: Int, widget: InfoWidget, catalog: List<InfoWidget>): ExtraInfoLayout? {
        if (
            row !in 0..1 ||
                rows.flatten().contains(widget.id) ||
                used(row, catalog) + widget.span(columns) > columns
        )
            return null
        return copy(rows = rows.mapIndexed { i, ids -> if (i == row) ids + widget.id else ids })
    }

    fun remove(row: Int, id: String) =
        copy(rows = rows.mapIndexed { i, ids -> if (i == row) ids.filterNot { it == id } else ids })

    fun move(row: Int, index: Int, delta: Int): ExtraInfoLayout {
        val ids = rows[row].toMutableList()
        val to = index + delta
        if (index !in ids.indices || to !in ids.indices) return this
        ids.add(to, ids.removeAt(index))
        return copy(rows = rows.mapIndexed { i, old -> if (i == row) ids else old })
    }

    companion object {
        const val ENABLED = "lockscreen_extra_info_widgets_enabled"
        const val LAYOUT = "lockscreen_extra_info_widgets_layout"
        const val BLUR = "lockscreen_extra_info_widgets_blur"
        val DEFAULT_ROWS = listOf(listOf("01_battery", "35_alarm", "25_battery_bar"), emptyList())

        fun parse(raw: String?, catalog: List<InfoWidget>): ExtraInfoLayout {
            val json =
                raw?.takeIf { it.length <= 8192 }
                    ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return ExtraInfoLayout()
            val columns = json.optInt("columns", 4).coerceIn(4, 5)
            val seen = mutableSetOf<String>()
            val source = json.optJSONArray("rows") ?: return ExtraInfoLayout()
            val rows =
                List(2) { row ->
                    var used = 0
                    val entries = source.optJSONArray(row) ?: JSONArray()
                    buildList {
                        for (i in 0 until minOf(entries.length(), 10)) {
                            val id = entries.optString(i)
                            val widget = catalog.firstOrNull { it.id == id } ?: continue
                            val span = widget.span(columns)
                            if (id in seen || used + span > columns) continue
                            add(id)
                            seen.add(id)
                            used += span
                        }
                    }
                }
            return ExtraInfoLayout(columns, rows)
        }
    }
}
