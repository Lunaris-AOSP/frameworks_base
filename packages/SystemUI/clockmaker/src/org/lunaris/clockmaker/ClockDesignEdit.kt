/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import org.json.JSONObject

enum class ClockDesignEditMode { LOCKSCREEN, AOD }

data class ClockDesignEdit(val before: ClockDesign, val after: ClockDesign, val mode: ClockDesignEditMode) {
    fun retainAod(current: ClockDesign): ClockDesignEdit {
        if (mode != ClockDesignEditMode.LOCKSCREEN || current.id != before.id) return this
        val beforeIds = before.elements.map { it.id }.toSet()
        val afterIds = after.elements.map { it.id }.toSet()
        if (beforeIds == afterIds) return this
        val live = current.elements.associateBy { it.id }
        fun retain(snapshot: ClockDesign) = snapshot.copy(elements = snapshot.elements.map { element ->
            val latest = live[element.id] ?: return@map element
            val structural = element.id !in beforeIds || element.id !in afterIds
            element.copy(aod = latest.aod, aodLayoutConfigured = latest.aodLayoutConfigured,
                font = if (structural) latest.font else element.font,
                weight = if (structural) latest.weight else element.weight,
                italic = if (structural) latest.italic else element.italic)
        })
        return copy(before = retain(before), after = retain(after))
    }

    fun undo(current: ClockDesign): ClockDesign = apply(current, before, after)

    fun redo(current: ClockDesign): ClockDesign = apply(current, after, before)

    private fun apply(current: ClockDesign, target: ClockDesign, source: ClockDesign): ClockDesign {
        if (current.id != target.id) return current
        val targetElements = target.elements.associateBy { it.id }
        val sourceElements = source.elements.associateBy { it.id }
        val currentElements = current.elements.associateBy { it.id }
        val structural = mode == ClockDesignEditMode.LOCKSCREEN &&
            target.elements.map { it.id } != source.elements.map { it.id }
        val elements = (if (structural) target.elements else current.elements).map { element ->
            val to = targetElements[element.id] ?: return@map element
            val from = sourceElements[element.id] ?: return@map element
            val live = currentElements[element.id] ?: return@map element
            if (mode == ClockDesignEditMode.AOD) {
                val fonts = merge(live.toJson(), to.toJson(), from.toJson(), FONT_KEYS)
                val result = ClockElement.fromJson(fonts)
                val toStyle = to.aodStyle.toJson()
                val fromStyle = from.aodStyle.toJson()
                if (changedKeys(toStyle, fromStyle).isEmpty()) live.copy(
                    font = result.font, weight = result.weight, italic = result.italic)
                else live.copy(font = result.font, weight = result.weight, italic = result.italic)
                    .configureAod(ClockElementAodStyle.fromJson(
                        merge(live.aodStyle.toJson(), toStyle, fromStyle), live.aodStyle))
            } else {
                val json = merge(live.toJson(), to.toJson(), from.toJson(),
                    changedKeys(to.toJson(), from.toJson()) - AOD_KEYS)
                ClockElement.fromJson(json)
            }
        }
        return current.copy(
            name = if (mode == ClockDesignEditMode.LOCKSCREEN && target.name != source.name)
                target.name else current.name,
            elements = elements,
        )
    }

    private fun changedKeys(target: JSONObject, source: JSONObject): Set<String> =
        (target.keys().asSequence().toSet() + source.keys().asSequence().toSet()).filterTo(mutableSetOf()) {
            target.opt(it)?.toString() != source.opt(it)?.toString()
        }

    private fun merge(current: JSONObject, target: JSONObject, source: JSONObject,
        allowed: Set<String> = changedKeys(target, source)): JSONObject {
        (changedKeys(target, source) intersect allowed).forEach {
            current.put(it, target.opt(it) ?: JSONObject.NULL)
        }
        return current
    }

    private companion object {
        val FONT_KEYS = setOf("font", "weight", "italic")
        val AOD_KEYS = setOf("aod", "aodLayoutConfigured")
    }
}
