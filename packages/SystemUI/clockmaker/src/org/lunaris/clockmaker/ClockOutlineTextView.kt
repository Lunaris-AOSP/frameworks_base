/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.widget.TextView
import kotlin.math.roundToInt

internal class ClockOutlineTextView(context: Context) : TextView(context) {
    private val savedPaint = TextPaint()
    private var outlineProgress = 0f
    private var outlineWidth = 0f

    fun setAodOutline(progress: Float, width: Float) {
        if (outlineProgress == progress && outlineWidth == width) return
        outlineProgress = progress
        outlineWidth = width
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val textLayout = layout
        val progress = outlineProgress
        if (progress <= 0f || textLayout == null) {
            super.onDraw(canvas)
            return
        }

        savedPaint.set(paint)
        val save = canvas.save()
        try {
            canvas.translate(compoundPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            val inset = outlineWidth / 2f
            canvas.clipRect(-maxOf(inset, shadowRadius - shadowDx),
                -maxOf(inset, shadowRadius - shadowDy),
                textLayout.width + maxOf(inset, shadowRadius + shadowDx),
                height - totalPaddingTop - totalPaddingBottom + maxOf(inset, shadowRadius + shadowDy))
            if (progress < 1f) {
                paint.style = Paint.Style.FILL
                paint.color = currentTextColor
                paint.alpha = (Color.alpha(currentTextColor) * (1f - progress)).roundToInt()
                textLayout.draw(canvas)
            }
            paint.shader = null
            paint.clearShadowLayer()
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = outlineWidth
            paint.color = Color.WHITE
            paint.alpha = (255 * progress).roundToInt()
            textLayout.draw(canvas)
        } finally {
            paint.set(savedPaint)
            canvas.restoreToCount(save)
        }
    }
}
