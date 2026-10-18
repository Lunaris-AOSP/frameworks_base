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

import com.android.systemui.qs.pipeline.shared.TileSpec
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object AxQsTileStacks {
    val Span = AxQsSpan(2, 2)

    fun newId(): String = "stack:${UUID.randomUUID()}"

    private fun isStackId(id: String): Boolean =
        id.startsWith("stack:") &&
            id.length > 6 &&
            id.none { it.isWhitespace() || it == ',' || it == '=' }

    private fun normalizeSpecs(specs: List<String>): List<String> =
        specs
            .map(String::trim)
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("control:") &&
                    !it.startsWith("stack:") &&
                    ',' !in it &&
                    '=' !in it &&
                    TileSpec.create(it) != TileSpec.Invalid
            }
            .distinct()

    fun decode(value: String?): Map<String, List<String>> {
        if (value.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(value)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    if (!isStackId(id)) continue
                    val pages = json.optJSONArray(id) ?: continue
                    put(
                        id,
                        normalizeSpecs(
                            (0 until pages.length()).mapNotNull { pages.opt(it) as? String }
                        ),
                    )
                }
            }
        } catch (_: JSONException) {
            emptyMap()
        }
    }

    fun encode(stacks: Map<String, List<String>>): String =
        JSONObject()
            .apply {
                stacks.toSortedMap().forEach { (id, specs) ->
                    if (isStackId(id)) put(id, JSONArray(normalizeSpecs(specs)))
                }
            }
            .toString()

    fun active(
        stacks: Map<String, List<String>>,
        controlOrder: List<String>,
    ): Map<String, List<String>> {
        val usedSpecs = mutableSetOf<String>()
        return buildMap {
            controlOrder.distinct().forEach { id ->
                stacks[id]?.let { specs -> put(id, specs.filter(usedSpecs::add)) }
            }
        }
    }
}
