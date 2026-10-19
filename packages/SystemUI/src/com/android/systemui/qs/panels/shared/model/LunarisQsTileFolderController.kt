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

package com.android.systemui.qs.panels.shared.model

import com.android.systemui.qs.pipeline.shared.TileSpec
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class LunarisQsTileFolder(
    val tileSpecs: List<String>,
    val tileStyle: AxQsTileStyle? = null,
    val tileSpans: Map<String, AxQsSpan> = emptyMap(),
)

object LunarisQsTileFolderController {
    const val MAX_FOLDERS = 2
    const val MAX_TILES = 8
    const val ICON_PREVIEW_TILE_COUNT = 4
    const val BIG_PREVIEW_SMALL_TILE_COUNT = 3
    const val PILE_PREVIEW_SMALL_TILE_COUNT = 4
    val Span = AxQsSpan(2, 2)
    val MinSpan = AxQsSpan.TileDefault

    fun maxSpan(columns: Int): AxQsSpan = AxQsSpan(columns.coerceIn(2, 4), 2)

    fun coerceSpan(span: AxQsSpan, columns: Int): AxQsSpan =
        span.coerceIn(if (span.rows == 1) MinSpan else Span, maxSpan(columns))

    fun resizeOptions(columns: Int): List<AxQsSpan> =
        listOf(MinSpan, Span) +
            (2..maxSpan(columns).columns).map { AxQsSpan(it, 1) } +
            (3..maxSpan(columns).columns).map { AxQsSpan(it, 2) }

    fun directTileCount(span: AxQsSpan, tileCount: Int): Int {
        if (span == MinSpan) return 0
        val capacity = if (span.rows == 1) 3 else 4
        return if (tileCount > capacity) capacity - 1 else tileCount
    }

    fun previewTileCount(span: AxQsSpan): Int =
        if (span == MinSpan) ICON_PREVIEW_TILE_COUNT
        else
            directTileCount(span, MAX_TILES) +
                if (span.rows == 1) PILE_PREVIEW_SMALL_TILE_COUNT
                else BIG_PREVIEW_SMALL_TILE_COUNT

    fun previewSpecs(specs: List<String>, span: AxQsSpan): List<String> =
        specs.take(previewTileCount(span))

    fun newId(): String = "folder:${UUID.randomUUID()}"

    fun isFolderId(id: String): Boolean =
        id.startsWith("folder:") &&
            id.length > 7 &&
            id.none { it.isWhitespace() || it == ',' || it == '=' }

    fun normalizeSpecs(specs: List<String>): List<String> =
        specs
            .map(String::trim)
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("control:") &&
                    !it.startsWith("stack:") &&
                    !it.startsWith("folder:") &&
                    ',' !in it &&
                    '=' !in it &&
                    TileSpec.create(it) != TileSpec.Invalid
            }
            .distinct()
            .take(MAX_TILES)

    fun active(
        folders: Map<String, LunarisQsTileFolder>,
        controlOrder: List<String>,
        reservedSpecs: Set<String> = emptySet(),
    ): Map<String, LunarisQsTileFolder> {
        val usedSpecs = reservedSpecs.toMutableSet()
        return buildMap {
            controlOrder
                .distinct()
                .filter(::isFolderId)
                .filter(folders::containsKey)
                .take(MAX_FOLDERS)
                .forEach { id ->
                    val folder = folders.getValue(id)
                    val specs = normalizeSpecs(folder.tileSpecs).filter(usedSpecs::add)
                    put(
                        id,
                        folder.copy(
                            tileSpecs = specs,
                            tileSpans =
                                folder.tileSpans.filterKeys { it in specs }.mapValues {
                                    it.value.coerceForControlTile(4)
                                },
                        ),
                    )
                }
        }
    }

    fun decode(value: String?): Map<String, LunarisQsTileFolder> {
        if (value.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(value)
            val folders = buildMap {
                val keys = json.keys()
                while (keys.hasNext() && size < MAX_FOLDERS) {
                    val id = keys.next()
                    if (!isFolderId(id)) continue
                    val entry = json.optJSONObject(id) ?: continue
                    val specs = entry.optJSONArray("tiles") ?: continue
                    val spans = entry.optJSONObject("spans")
                    put(
                        id,
                        LunarisQsTileFolder(
                            normalizeSpecs(
                                (0 until specs.length()).mapNotNull { specs.opt(it) as? String }
                            ),
                            AxQsTileStyle.entries.firstOrNull {
                                it.settingValue == entry.optInt("shape", -1)
                            },
                            buildMap {
                                if (spans != null) {
                                    val spanKeys = spans.keys()
                                    while (spanKeys.hasNext()) {
                                        val spec = spanKeys.next()
                                        AxQsSpan.parse(spans.optString(spec))?.let {
                                            put(spec, it)
                                        }
                                    }
                                }
                            },
                        ),
                    )
                }
            }
            active(folders, folders.keys.toList())
        } catch (_: JSONException) {
            emptyMap()
        }
    }

    fun encode(folders: Map<String, LunarisQsTileFolder>): String =
        JSONObject()
            .apply {
                active(folders, folders.keys.toList()).forEach { (id, folder) ->
                    put(
                        id,
                        JSONObject().apply {
                            put("tiles", JSONArray(folder.tileSpecs))
                            folder.tileStyle?.let { put("shape", it.settingValue) }
                            if (folder.tileSpans.isNotEmpty()) {
                                put(
                                    "spans",
                                    JSONObject().apply {
                                        folder.tileSpans.forEach { (spec, span) ->
                                            put(spec, span.toString())
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
            .toString()
}
