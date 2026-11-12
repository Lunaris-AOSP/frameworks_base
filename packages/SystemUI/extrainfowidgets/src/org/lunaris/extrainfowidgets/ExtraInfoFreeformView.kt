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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import org.lunaris.clockmaker.ClockGlass
import kotlin.math.abs
import kotlin.math.roundToInt

class ExtraInfoFreeformView(context: Context) : ViewGroup(context) {
    private val catalog = ExtraInfoCatalog.load(context)
    private val outline =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }
    private var currentLayout = ExtraInfoFreeformLayout()
    var selection: ExtraInfoFreeformLayout
        get() = currentLayout
        set(value) {
            val bounded =
                value.copy(
                    widgets =
                        value.widgets
                            .distinctBy { it.id }
                            .filter { position -> catalog.any { it.id == position.id } }
                            .take(ExtraInfoFreeformLayout.MAX_WIDGETS)
                            .map {
                                it.copy(
                                    x = coordinate(it.x, 0.5f),
                                    y = coordinate(it.y, 0.35f),
                                    scale = ExtraInfoFreeformLayout.clampScale(it.scale),
                                )
                            }
                )
            if (dragId != null) {
                if (bounded == start) return
                clearDrag()
            }
            applySelection(bounded)
        }

    var contentVisible = true
        set(value) {
            field = value
            updateVisibility()
        }

    var suppressed = false
        set(value) {
            field = value
            updateVisibility()
        }

    var burnInOffsetX = 0f
        set(value) {
            field = if (value.isFinite()) value else 0f
            applyBurnInOffsets()
        }

    var burnInOffsetY = 0f
        set(value) {
            field = if (value.isFinite()) value else 0f
            applyBurnInOffsets()
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

    private var glassPreview: ClockGlass? = null
    private var glassSpace: View? = null
    private var glassOffset = 0f

    fun setGlassPreview(material: ClockGlass?, space: View, offsetY: Float = 0f) {
        glassPreview = material
        glassSpace = space
        glassOffset = offsetY
        children().forEach { it.setGlassPreview(material, space, offsetY) }
    }

    var blurSource: View? = null
        set(value) {
            field = value
            children().forEach { it.setBlurSource(value) }
        }

    var onAction: ((String) -> Unit)? = null
        set(value) {
            field = value
            updateActions()
        }

    var previewScale = 1f
        set(value) {
            val scale = if (value.isFinite()) value.coerceAtLeast(0f) else 1f
            if (field != scale) {
                finishDrag(cancel = true)
                field = scale
                requestLayout()
            }
        }

    var editable = false
        set(value) {
            if (field == value) return
            if (!value) finishDrag(cancel = true)
            field = value
            updateActions()
            invalidate()
        }

    var selectedId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var onSelected: ((String) -> Unit)? = null
    var onEdit: ((ExtraInfoFreeformLayout) -> Unit)? = null
    var onVisibilityChanged: (() -> Unit)? = null
    private var dragId: String? = null
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var start = selection
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        clipChildren = true
        clipToPadding = true
    }

    private fun coordinate(value: Float, fallback: Float) =
        if (value.isFinite()) value.coerceIn(0f, 1f) else fallback

    private fun applySelection(value: ExtraInfoFreeformLayout) {
        if (currentLayout == value) return
        val rebuild = currentLayout.widgets.map { it.id } != value.widgets.map { it.id }
        currentLayout = value
        if (rebuild) rebuild() else requestLayout()
        invalidate()
    }

    private fun updateVisibility() {
        val visible = contentVisible && !suppressed
        if (!visible) finishDrag(cancel = true)
        visibility = if (visible) VISIBLE else GONE
    }

    private fun children() = (0 until childCount).map { getChildAt(it) as InfoWidgetView }

    private fun updateActions() {
        children().forEach { it.onAction = if (editable) null else onAction }
    }

    private fun rebuild() {
        removeAllViews()
        selection.widgets.forEach { position ->
            val design = catalog.first { it.id == position.id }
            addView(
                InfoWidgetView(context, design).also {
                    it.data = data
                    it.appearance = appearance
                    it.onAction = if (editable) null else onAction
                    it.setBlurSource(blurSource)
                    glassSpace?.let { space -> it.setGlassPreview(glassPreview, space, glassOffset) }
                }
            )
        }
        requestLayout()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) finishDrag(cancel = true)
        onVisibilityChanged?.invoke()
    }

    override fun onDetachedFromWindow() {
        finishDrag(cancel = true)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) finishDrag(cancel = true)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val density = resources.displayMetrics.density * previewScale
        val cell =
            minOf(
                64f * density,
                (width - 32f * density).coerceAtLeast(0f) / (4 + 3 * GAP),
                height.toFloat(),
            )
        children().forEachIndexed { index, child ->
            val span = child.widget.span(4)
            val aspect = span + GAP * (span - 1)
            val scaledCell =
                minOf(cell * selection.widgets[index].scale, width / aspect, height.toFloat())
            child.measure(
                MeasureSpec.makeMeasureSpec(
                    (scaledCell * aspect).roundToInt(),
                    MeasureSpec.EXACTLY,
                ),
                MeasureSpec.makeMeasureSpec(scaledCell.roundToInt(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        selection.widgets.forEachIndexed { index, position ->
            val child = getChildAt(index)
            val x =
                ((right - left - child.measuredWidth).coerceAtLeast(0) *
                        position.x.coerceIn(0f, 1f))
                    .roundToInt()
            val y =
                ((bottom - top - child.measuredHeight).coerceAtLeast(0) *
                        position.y.coerceIn(0f, 1f))
                    .roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
        applyBurnInOffsets()
    }

    private fun applyBurnInOffsets() {
        children().forEach { child ->
            child.translationX =
                burnInOffsetX.coerceIn(
                    -child.left.toFloat(),
                    (width - child.right).coerceAtLeast(-child.left).toFloat(),
                )
            child.translationY =
                burnInOffsetY.coerceIn(
                    -child.top.toFloat(),
                    (height - child.bottom).coerceAtLeast(-child.top).toFloat(),
                )
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editable) return
        val index = selection.widgets.indexOfFirst { it.id == selectedId }
        val child = getChildAt(index) ?: return
        val inset = outline.strokeWidth / 2f
        canvas.drawRoundRect(
            child.left + inset,
            child.top + inset,
            child.right - inset,
            child.bottom - inset,
            12f,
            12f,
            outline,
        )
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!editable || event.actionMasked != MotionEvent.ACTION_DOWN) return false
        finishDrag(cancel = true)
        val child =
            children().asReversed().firstOrNull {
                event.x >= it.left && event.x < it.right && event.y >= it.top && event.y < it.bottom
            } ?: return false
        dragId = child.widget.id
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val id = dragId ?: return false
        if (!editable) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start = selection
                pointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                dragging = false
                selectedId = id
                parent?.requestDisallowInterceptTouchEvent(true)
                onSelected?.invoke(id)
            }
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP -> {
                val pointer = event.findPointerIndex(pointerId)
                if (pointer < 0) {
                    finishDrag(cancel = true)
                    return true
                }
                val dx = event.getX(pointer) - downX
                val dy = event.getY(pointer) - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                if (dragging) {
                    val position = start.widgets.firstOrNull { it.id == id }
                    val child = children().firstOrNull { it.widget.id == id }
                    if (position != null && child != null) {
                        applySelection(
                            start.move(
                                id,
                                position.x + dx / (width - child.measuredWidth).coerceAtLeast(1),
                                position.y + dy / (height - child.measuredHeight).coerceAtLeast(1),
                            )
                        )
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (!dragging) performClick()
                    finishDrag(cancel = false)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) finishDrag(cancel = true)
            }
            MotionEvent.ACTION_CANCEL -> finishDrag(cancel = true)
        }
        return true
    }

    private fun finishDrag(cancel: Boolean) {
        if (dragId == null) return
        val result = selection
        val previous = start
        val changed = dragging && result != previous
        clearDrag()
        if (cancel) applySelection(previous) else if (changed) onEdit?.invoke(result)
    }

    private fun clearDrag() {
        dragId = null
        pointerId = -1
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val GAP = 0.0315f / 0.171f
    }
}
