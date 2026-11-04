/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.TextPaint
import android.widget.TextView
import kotlin.math.roundToInt

internal class ClockOutlineTextView(context: Context) : TextView(context) {
    private val savedPaint = TextPaint()
    private var element: ClockElement? = null
    private var scale = 0f
    private var dozeProgress = 0f

    fun setTextEffects(element: ClockElement, scale: Float, progress: Float) {
        if (this.element == element && this.scale == scale && dozeProgress == progress) return
        this.element = element
        this.scale = scale
        dozeProgress = progress
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val textLayout = layout
        val style = element
        if (style == null || textLayout == null) {
            super.onDraw(canvas)
            return
        }
        val normalOutline = if (style.lockscreenOutline) 1f - dozeProgress else 0f
        val aodOutline = if (style.aodOutline) dozeProgress else 0f
        val fill = when {
            style.lockscreenOutline && style.aodOutline -> 0f
            style.lockscreenOutline -> dozeProgress
            style.aodOutline -> 1f - dozeProgress
            else -> 1f
        }
        val strokeWidth = if (style.textStroke && fill > 0f && Color.alpha(style.textStrokeColor) > 0) {
            style.textStrokeWidth.coerceIn(1, 12) * scale
        } else 0f
        if (normalOutline <= 0f && aodOutline <= 0f && strokeWidth <= 0f) {
            super.onDraw(canvas)
            return
        }
        val normalWidth = style.lockscreenOutlineWidth.coerceIn(1, 6) * scale
        val aodWidth = style.aodOutlineWidth.coerceIn(1, 6) * scale
        val outlineWidth = when {
            style.lockscreenOutline && style.aodOutline -> normalWidth + (aodWidth - normalWidth) * dozeProgress
            style.lockscreenOutline -> normalWidth
            else -> aodWidth
        }

        savedPaint.set(paint)
        val save = canvas.save()
        try {
            canvas.translate(compoundPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            val inset = maxOf(outlineWidth / 2f, strokeWidth)
            canvas.clipRect(-inset - maxOf(0f, shadowRadius - shadowDx),
                -inset - maxOf(0f, shadowRadius - shadowDy),
                textLayout.width + inset + maxOf(0f, shadowRadius + shadowDx),
                height - totalPaddingTop - totalPaddingBottom + inset + maxOf(0f, shadowRadius + shadowDy))
            if (strokeWidth > 0f) drawText(canvas, textLayout, fill, Paint.Style.STROKE,
                strokeWidth * 2f, style.textStrokeColor, textShader = false)
            drawText(canvas, textLayout, fill, Paint.Style.FILL, shadow = strokeWidth <= 0f)
            val normalAlpha = if (normalOutline > 0f && aodOutline < 1f) normalOutline / (1f - aodOutline) else 0f
            drawText(canvas, textLayout, normalAlpha, Paint.Style.STROKE, outlineWidth, shadowAlpha = normalOutline)
            drawText(canvas, textLayout, aodOutline, Paint.Style.STROKE, outlineWidth,
                Color.WHITE, textShader = false, shadow = false)
        } finally {
            paint.set(savedPaint)
            canvas.restoreToCount(save)
        }
    }

    private fun drawText(canvas: Canvas, textLayout: Layout, alpha: Float, style: Paint.Style,
        width: Float = 0f, color: Int = currentTextColor, textShader: Boolean = true, shadow: Boolean = true,
        shadowAlpha: Float = alpha) {
        if (alpha <= 0f) return
        paint.set(savedPaint)
        paint.style = style
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = width
        paint.color = color
        paint.alpha = (Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255)
        if (!textShader) paint.shader = null
        if (!shadow || shadowAlpha <= 0f) paint.clearShadowLayer()
        else if (shadowRadius > 0f && shadowAlpha < 1f) {
            val baseAlpha = if (Color.alpha(shadowColor) == 255) Color.alpha(color) else Color.alpha(shadowColor)
            val fadedAlpha = (baseAlpha * shadowAlpha).roundToInt().coerceIn(0, 255)
            if (fadedAlpha == 0) paint.clearShadowLayer()
            else paint.setShadowLayer(shadowRadius, shadowDx, shadowDy,
                (shadowColor and 0xFFFFFF) or (fadedAlpha shl 24))
        }
        textLayout.draw(canvas)
    }
}
