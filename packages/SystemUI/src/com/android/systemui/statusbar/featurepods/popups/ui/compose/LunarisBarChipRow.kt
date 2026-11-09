/* Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.statusbar.featurepods.popups.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

internal const val LunarisBarCameraGap = "lunaris_camera_gap"

@Composable
internal fun LunarisBarChipRow(
    centerCamera: Boolean,
    spacing: Dp,
    companion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!centerCamera) {
        Row(
            modifier = modifier,
            horizontalArrangement =
                if (companion) Arrangement.Center else Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
        return
    }
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapIndex = measurables.indexOfFirst { it.layoutId == LunarisBarCameraGap }
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val gap = measurables[gapIndex].measure(childConstraints)
        val sideChildCount = maxOf(gapIndex, measurables.size - gapIndex - 1, 1)
        val space =
            spacing.roundToPx().coerceIn(0, (constraints.maxWidth - gap.width) / 2 / sideChildCount)
        val sideLimit = ((constraints.maxWidth - gap.width) / 2 - space).coerceAtLeast(0)
        fun measureSide(children: List<Measurable>, fromEnd: Boolean = false): List<Placeable> {
            var remaining =
                (sideLimit - space * (children.size - 1).coerceAtLeast(0)).coerceAtLeast(0)
            val ordered = if (fromEnd) children.asReversed() else children
            val measured =
                ordered.map { child ->
                    child.measure(childConstraints.copy(maxWidth = remaining)).also {
                        remaining = (remaining - it.width).coerceAtLeast(0)
                    }
                }
            return if (fromEnd) measured.asReversed() else measured
        }
        val start = measureSide(measurables.take(gapIndex))
        val end = measureSide(measurables.drop(gapIndex + 1), fromEnd = true)
        fun List<Placeable>.rowWidth() = sumOf { it.width } + space * (size - 1).coerceAtLeast(0)
        val sideWidth = maxOf(start.rowWidth(), end.rowWidth())
        val width = constraints.constrainWidth(2 * (sideWidth + space) + gap.width)
        val height = constraints.constrainHeight((start + gap + end).maxOf { it.height })
        layout(width, height) {
            fun placeSide(children: List<Placeable>, left: Int) {
                var x = left
                children.forEach {
                    it.placeRelative(x, (height - it.height) / 2)
                    x += it.width + space
                }
            }
            placeSide(start, 0)
            gap.placeRelative((width - gap.width) / 2, (height - gap.height) / 2)
            placeSide(end, width - end.rowWidth())
        }
    }
}
