/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

object ClockDesignTransition {
    fun canMorphLayouts(source: ClockDesign, target: ClockDesign): Boolean =
        source.elements.isNotEmpty() && source.elements.size == target.elements.size &&
            source.elements.none { it.kind == ClockElementKind.IMAGE || it.kind == ClockElementKind.QUICKLOOK } &&
            source.elements.zip(target.elements).all { (from, to) ->
                from == to.copy(x = from.x, y = from.y, size = from.size,
                    stretchX = from.stretchX, stretchY = from.stretchY, stretchFromTop = from.stretchFromTop,
                    letterSpacing = from.letterSpacing, rotation = from.rotation,
                    opacity = from.opacity, visible = from.visible,
                    imageWidth = from.imageWidth, imageHeight = from.imageHeight,
                    aod = from.aod, aodLayoutConfigured = from.aodLayoutConfigured)
            }

    fun fromAod(source: ClockDesign, target: ClockDesign): ClockDesign =
        fromAod(source, target, mergeElements = true)

    fun fromAod(source: ClockDesign, target: ClockDesign, sourceAlignment: ClockDigitAlignment,
        targetAlignment: ClockDigitAlignment): ClockDesign {
        fun configured(design: ClockDesign, alignment: ClockDigitAlignment) =
            design.copy(elements = design.elements.map {
                if (it.kind == ClockElementKind.TIME) it.copy(digitAlignmentOverride = alignment) else it
            })
        return fromAod(configured(source, sourceAlignment), configured(target, targetAlignment),
            mergeElements = true)
    }

    private fun fromAod(source: ClockDesign, target: ClockDesign, mergeElements: Boolean): ClockDesign {
        val remaining = source.elements.toMutableList()
        val ids = target.elements.mapTo(mutableSetOf()) { it.id }
        val retainedIds = source.elements.filter { candidate ->
            target.elements.any { it.id == candidate.id && it.kind == candidate.kind }
        }.mapTo(mutableSetOf()) { it.id }
        val aodElements = mutableMapOf<String, ClockElement>()

        fun outgoingId(element: ClockElement): String {
            var id = "aod/${element.id}"
            while (!ids.add(id)) id += "/aod"
            return id
        }

        val incoming = target.elements.map { element ->
            val match = remaining.firstOrNull { it.id == element.id && it.kind == element.kind }
                ?: remaining.firstOrNull { it.id !in retainedIds && sameContent(it, element) }
            if (match == null) {
                element.configureAod(ClockElementAodStyle.fromElement(element).copy(visible = false))
            } else {
                remaining.remove(match)
                if (mergeElements && sameContent(match, element) && match.font == element.font &&
                    match.weight == element.weight && match.italic == element.italic &&
                    match.digitAlignmentOverride == element.digitAlignmentOverride) {
                    element.configureAod(match.aodStyle).also { aodElements[match.id] = it }
                } else {
                    aodElements[match.id] = match.copy(
                        id = outgoingId(match), x = element.x, y = element.y, size = element.size,
                        stretchX = element.stretchX, stretchY = element.stretchY,
                        stretchFromTop = element.stretchFromTop,
                        letterSpacing = element.letterSpacing, rotation = element.rotation,
                        imageWidth = element.imageWidth, imageHeight = element.imageHeight,
                        visible = false,
                    ).configureAod(match.aodStyle)
                    element.configureAod(match.aodStyle.copy(visible = false))
                }
            }
        }

        remaining.forEach { element ->
            aodElements[element.id] = element.aodStyle.applyTo(element).copy(
                id = outgoingId(element), visible = false,
            ).configureAod(element.aodStyle)
        }
        val aodOrder = source.elements.map { aodElements.getValue(it.id) }
        val aodIds = aodOrder.mapTo(mutableSetOf()) { it.id }
        val incomingIds = incoming.mapTo(mutableSetOf()) { it.id }
        if (aodOrder.filter { it.id in incomingIds }.map { it.id } !=
            incoming.filter { it.id in aodIds }.map { it.id }) {
            return fromAod(source, target, mergeElements = false)
        }
        val elements = mutableListOf<ClockElement>()
        var aodIndex = 0
        incoming.forEach { element ->
            if (element.id in aodIds) {
                while (aodOrder[aodIndex].id != element.id) elements += aodOrder[aodIndex++]
                aodIndex++
            }
            elements += element
        }
        elements += aodOrder.drop(aodIndex)
        return target.copy(elements = elements)
    }

    private fun sameContent(first: ClockElement, second: ClockElement): Boolean =
        first.kind == second.kind && when (first.kind) {
            ClockElementKind.TIME -> first.timePart == second.timePart &&
                first.hourFormat == second.hourFormat && first.format == second.format
            ClockElementKind.TEXT -> first.text == second.text
            ClockElementKind.DATE -> first.format == second.format
            ClockElementKind.IMAGE -> first.image == second.image
            ClockElementKind.QUICKLOOK -> first.format == second.format &&
                first.quickLookDateFallback == second.quickLookDateFallback
            ClockElementKind.WEATHER -> true
        }
}
