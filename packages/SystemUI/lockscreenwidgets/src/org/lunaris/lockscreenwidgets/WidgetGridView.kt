/*
 * Copyright (C) 2026 Lunaris-AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lunaris.lockscreenwidgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.FrameLayout

class WidgetGridView(context: Context) : ViewGroup(context) {
    var entries: List<WidgetEntry> = emptyList()
        private set
    var editing = false
        set(value) { field = value; setWillNotDraw(!value); requestLayout(); invalidate() }
    var selectedId = -1
        set(value) { field = value; invalidate() }
    var onSelect: (Int) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    fun clearWidgets() {
        removeAllViews()
        entries = emptyList()
    }

    fun setWidgets(items: List<WidgetEntry>, create: (WidgetEntry) -> View) {
        if (entries.map { it.id } == items.map { it.id }) {
            entries = items
            requestLayout()
            return
        }
        val previous = (0 until childCount).associate { getChildAt(it).tag as Int to getChildAt(it) }
        removeAllViews()
        entries = items
        items.forEach { entry ->
            val child = previous[entry.id] ?: create(entry)
            child.tag = entry.id
            addView(child)
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val sizes = WidgetSizes(context, width)
        for (i in entries.indices) {
            val entry = entries[i]
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(sizes.pixels(entry.width), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(sizes.pixels(entry.height), MeasureSpec.EXACTLY))
        }
        val rows = if (editing) 4 else entries.maxOfOrNull { it.y + it.height } ?: 0
        setMeasuredDimension(width, if (rows == 0) 0 else sizes.pixels(rows))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val sizes = WidgetSizes(context, width)
        for (i in entries.indices) {
            val e = entries[i]
            val child = getChildAt(i)
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL)
                width - sizes.offset(e.x) - child.measuredWidth else sizes.offset(e.x)
            val y = sizes.offset(e.y)
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editing && selectedId < 0) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = Color.GRAY
        val sizes = WidgetSizes(context, width)
        if (editing) {
            for (y in 0..3) for (x in 0..3) {
                val left = sizes.offset(x).toFloat()
                val top = sizes.offset(y).toFloat()
                rect.set(left, top, left + sizes.pixels(1), top + sizes.pixels(1))
                canvas.drawRoundRect(rect, 12 * density, 12 * density, paint)
            }
        }
        val index = entries.indexOfFirst { it.id == selectedId }
        if (index >= 0) {
            val child = getChildAt(index)
            paint.color = context.getColor(android.R.color.system_accent1_400)
            paint.strokeWidth = 3 * density
            rect.set(child.left + density, child.top + density,
                child.right - density, child.bottom - density)
            canvas.drawRoundRect(rect, 12 * density, 12 * density, paint)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent) = editing
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editing) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            for (i in entries.indices) {
                val child = getChildAt(i)
                if (event.x >= child.left && event.x < child.right &&
                    event.y >= child.top && event.y < child.bottom) {
                    selectedId = entries[i].id
                    onSelect(selectedId)
                    performClick()
                    break
                }
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

class WidgetTouchFrame(context: Context, private val onEdit: () -> Unit) : FrameLayout(context) {
    private var downX = 0f
    private var downY = 0f
    private var touchActive = false
    private var longPressed = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val edit = Runnable {
        if (touchActive) performLongClick()
    }

    override fun performLongClick(): Boolean {
        if (touchActive) {
            if (longPressed) return true
            longPressed = true
            removeCallbacks(edit)
            val cancel = MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onEdit()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = isLongClickable

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (isLongClickable) info.addAction(
            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK,
                context.getString(R.string.app_widgets_edit),
            )
        )
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true
                longPressed = false
                downX = event.x
                downY = event.y
                var ancestor = parent
                while (ancestor != null && ancestor !is android.widget.ScrollView) ancestor = ancestor.parent
                ancestor?.parent?.requestDisallowInterceptTouchEvent(true)
                if (hasScrollableChild(this)) parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(edit)
                if (isLongClickable) postDelayed(edit, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.x - downX) > slop ||
                kotlin.math.abs(event.y - downY) > slop) removeCallbacks(edit)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(edit)
        }
        val handled = if (longPressed) true else super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            touchActive = false
            longPressed = false
        }
        return handled || isLongClickable
    }

    private fun hasScrollableChild(view: View): Boolean {
        if (view.canScrollVertically(-1) || view.canScrollVertically(1) ||
            view.canScrollHorizontally(-1) || view.canScrollHorizontally(1)) return true
        return view is ViewGroup && (0 until view.childCount).any { hasScrollableChild(view.getChildAt(it)) }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(edit)
        touchActive = false
        longPressed = false
        super.onDetachedFromWindow()
    }
}

