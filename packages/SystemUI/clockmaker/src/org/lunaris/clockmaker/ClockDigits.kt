/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.style.CharacterStyle
import android.text.style.ReplacementSpan
import kotlin.math.ceil

enum class ClockDigitAlignment {
    FONT, CENTER, EDGES;

    companion object {
        fun parse(value: String?): ClockDigitAlignment = entries.firstOrNull { it.name == value } ?: FONT
    }
}

object ClockDigits {
    const val SETTING = "lock_screen_custom_clock_digit_alignment"

    fun sourceText(text: CharSequence): CharSequence =
        (text as? Spanned)?.getSpans(0, text.length, SourceText::class.java)?.firstOrNull()?.text ?: text

    fun align(text: CharSequence, alignment: ClockDigitAlignment,
        part: ClockTimePart = ClockTimePart.FULL): CharSequence {
        val source = sourceText(text)
        if (alignment == ClockDigitAlignment.FONT) return source
        val original = if (source is Spanned) SpannedString(source) else source.toString()
        val result = SpannableStringBuilder(original)
        result.getSpans(0, result.length, DigitSpan::class.java).forEach(result::removeSpan)
        val metrics = mutableMapOf<Int, DigitMetrics>()
        val ranges = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (start < original.length) {
            val codePoint = Character.codePointAt(original, start)
            val end = start + Character.charCount(codePoint)
            if (Character.isDigit(codePoint) &&
                result.getSpans(start, end, ReplacementSpan::class.java).isEmpty()) {
                val digit = Character.digit(codePoint, 10)
                val zero = codePoint - digit
                val digits = mutableListOf(digit)
                val limit = result.nextSpanTransition(start, original.length, CharacterStyle::class.java)
                var runEnd = end
                while (runEnd < limit) {
                    val next = Character.codePointAt(original, runEnd)
                    if (!Character.isDigit(next) || next - Character.digit(next, 10) != zero) break
                    val nextEnd = runEnd + Character.charCount(next)
                    if (result.getSpans(runEnd, nextEnd, ReplacementSpan::class.java).isNotEmpty()) break
                    digits.add(Character.digit(next, 10))
                    runEnd = nextEnd
                }
                result.setSpan(DigitSpan(metrics.getOrPut(zero) { DigitMetrics(zero) },
                    digits.toIntArray(), original.subSequence(start, runEnd), alignment, part),
                    start, runEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ranges += start to runEnd
                start = runEnd
                continue
            }
            start = end
        }
        if (ranges.isEmpty()) return source
        ranges.asReversed().forEach { (left, right) ->
            result.insert(right, "\u200e")
            result.insert(left, "\u200e")
        }
        result.setSpan(SourceText(original), 0, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return result
    }

    private class SourceText(val text: CharSequence)

    private class DigitMetrics(zero: Int) {
        val digits = Array(10) { String(Character.toChars(zero + it)) }
        val bounds = Array(10) { Rect() }
        val fontMetrics = Paint.FontMetricsInt()
        var width = 0
            private set
        var inkWidth = 0
            private set
        var tracking = 0f
            private set
        private val cachedPaint = Paint()
        private val measuringPaint = Paint()
        private val glyphMetrics = Paint.FontMetricsInt()
        private var measured = false

        fun update(paint: Paint) {
            if (measured && cachedPaint.equalsForTextMeasurement(paint)) return
            cachedPaint.set(paint)
            measuringPaint.set(paint)
            measuringPaint.style = Paint.Style.FILL
            measuringPaint.letterSpacing = 0f
            measuringPaint.clearShadowLayer()
            measuringPaint.getFontMetricsInt(fontMetrics)
            var widest = 0f
            inkWidth = 0
            digits.forEachIndexed { index, digit ->
                measuringPaint.getTextBounds(digit, 0, digit.length, bounds[index])
                inkWidth = maxOf(inkWidth, bounds[index].width())
                widest = maxOf(widest, measuringPaint.measureText(digit), bounds[index].width().toFloat())
                measuringPaint.getFontMetricsInt(digit, 0, digit.length, 0, digit.length, false, glyphMetrics)
                fontMetrics.top = minOf(fontMetrics.top, glyphMetrics.top)
                fontMetrics.ascent = minOf(fontMetrics.ascent, glyphMetrics.ascent)
                fontMetrics.descent = maxOf(fontMetrics.descent, glyphMetrics.descent)
                fontMetrics.bottom = maxOf(fontMetrics.bottom, glyphMetrics.bottom)
                fontMetrics.leading = maxOf(fontMetrics.leading, glyphMetrics.leading)
            }
            width = ceil(widest).toInt().coerceAtLeast(1)
            tracking = (paint.letterSpacing * paint.textSize * paint.textScaleX).coerceAtLeast(1f - width)
            measured = true
        }
    }

    private class DigitSpan(private val metrics: DigitMetrics, private val digits: IntArray,
        description: CharSequence, private val alignment: ClockDigitAlignment,
        part: ClockTimePart) : ReplacementSpan() {
        private val leading = part == ClockTimePart.HOUR_TENS || part == ClockTimePart.MINUTE_TENS ||
            part == ClockTimePart.SECOND_TENS
        private val trailing = part == ClockTimePart.HOUR_ONES || part == ClockTimePart.MINUTE_ONES ||
            part == ClockTimePart.SECOND_ONES

        init {
            contentDescription = description
        }

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int,
            fm: Paint.FontMetricsInt?): Int {
            metrics.update(paint)
            fm?.let {
                it.top = metrics.fontMetrics.top
                it.ascent = metrics.fontMetrics.ascent
                it.descent = metrics.fontMetrics.descent
                it.bottom = metrics.fontMetrics.bottom
                it.leading = metrics.fontMetrics.leading
            }
            return ceil(metrics.width * digits.size + metrics.tracking * (digits.size - 1)).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
            top: Int, y: Int, bottom: Int, paint: Paint) {
            metrics.update(paint)
            val spacing = paint.letterSpacing
            paint.letterSpacing = 0f
            try {
                digits.forEachIndexed { index, digit ->
                    val bounds = metrics.bounds[digit]
                    val inset = (metrics.width - metrics.inkWidth) / 2f
                    val offset = when {
                        alignment == ClockDigitAlignment.EDGES && index == 0 && (digits.size > 1 || leading) ->
                            inset - bounds.left
                        alignment == ClockDigitAlignment.EDGES && index == digits.lastIndex && (digits.size > 1 || trailing) ->
                            metrics.width - inset - bounds.right
                        else -> (metrics.width - bounds.width()) / 2f - bounds.left
                    }
                    canvas.drawText(metrics.digits[digit],
                        x + index * (metrics.width + metrics.tracking) + offset, y.toFloat(), paint)
                }
            } finally {
                paint.letterSpacing = spacing
            }
        }
    }
}
