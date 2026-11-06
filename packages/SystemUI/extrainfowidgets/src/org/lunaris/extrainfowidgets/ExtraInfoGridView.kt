/* SPDX-License-Identifier: GPL-3.0-only */
package org.lunaris.extrainfowidgets

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

class ExtraInfoGridView(context: Context) : ViewGroup(context) {
    private val catalog = ExtraInfoCatalog.load(context)
    var blurSource: View? = null
        set(value) {
            field = value
            children().forEach { it.setBlurSource(value) }
        }

    var selection = ExtraInfoLayout()
        set(value) {
            if (field != value) {
                field = value
                rebuild()
            }
        }

    var data = InfoData()
        set(value) {
            field = value
            children().forEach { it.data = value }
        }

    var appearance = InfoAppearance()
        set(value) {
            field = value
            children().forEach { it.appearance = value }
        }

    var onAction: ((String) -> Unit)? = null
        set(value) {
            field = value
            children().forEach { it.onAction = value }
        }

    var previewScale = 1f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    init {
        clipChildren = false
        clipToPadding = false
        rebuild()
    }

    private fun children() = (0 until childCount).map { getChildAt(it) as InfoWidgetView }

    private fun activeRows(): List<List<InfoWidget>> {
        val rows =
            selection.rows.map { row ->
                row.mapNotNull { id -> catalog.firstOrNull { it.id == id } }
            }
        return rows.take(rows.indexOfLast { it.isNotEmpty() } + 1)
    }

    private fun rebuild() {
        removeAllViews()
        activeRows().flatten().forEach { widget ->
            addView(
                InfoWidgetView(context, widget).also {
                    it.data = data
                    it.appearance = appearance
                    it.onAction = onAction
                    it.setBlurSource(blurSource)
                }
            )
        }
        requestLayout()
    }

    private fun cell(width: Int): Float {
        val padding = 32f * resources.displayMetrics.density * previewScale
        return minOf(
            64f * resources.displayMetrics.density * previewScale,
            (width - padding).coerceAtLeast(0f) /
                (selection.columns + (selection.columns - 1) * GAP),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cell = cell(width)
        val rows = activeRows()
        val height =
            if (rows.isEmpty()) 0
            else (cell * rows.size + cell * GAP * (rows.size - 1)).roundToInt()
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec),
        )
        children().forEach { child ->
            val span = child.widget.span(selection.columns)
            child.measure(
                MeasureSpec.makeMeasureSpec(
                    (cell * (span + GAP * (span - 1))).roundToInt(),
                    MeasureSpec.EXACTLY,
                ),
                MeasureSpec.makeMeasureSpec(cell.roundToInt(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cell = cell(right - left)
        var childIndex = 0
        activeRows().forEachIndexed { rowIndex, widgets ->
            val units = widgets.sumOf { it.span(selection.columns) }
            var x = ((right - left) - cell * (units + GAP * (units - 1))) / 2f
            val y = (cell * rowIndex * (1 + GAP)).roundToInt()
            widgets.forEach {
                val child = getChildAt(childIndex++)
                val actualX =
                    if (layoutDirection == LAYOUT_DIRECTION_RTL)
                        right - left - x - child.measuredWidth
                    else x
                child.layout(
                    actualX.roundToInt(),
                    y,
                    actualX.roundToInt() + child.measuredWidth,
                    y + child.measuredHeight,
                )
                x += child.measuredWidth + cell * GAP
            }
        }
    }

    companion object {
        private const val GAP = 0.0315f / 0.171f
    }
}
