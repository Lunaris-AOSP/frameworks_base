/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import com.android.systemui.res.R
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class HyperClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var mode = MODE_ART
    private var family = "a"
    private var minuteFamily: String? = null
    private var em = 0f
    private var showColon = true
    private var digitColor = Color.WHITE
    private var colonColor = DEFAULT_COLON_COLOR
    private var attrColonColor = DEFAULT_COLON_COLOR
    private var colonFollowsDigits = false
    private var colonFamily: String? = null
    private var colonScale = 1f
    private var colonDx = 0f
    private var colonDy = 0f
    private var colonOnTop = false
    private var minuteAlpha = 1f
    private var digitGap = ROW_DIGIT_GAP
    private var pairGap = ROW_PAIR_GAP
    private var artGapX = ART_GAP_X
    private var artDropY = ART_DROP_Y
    private var artRowDx = ART_ROW_DX
    private var artRowDy = ART_ROW_DY
    private var stackDy = STACK_DY
    private var duoDy = DUO_DY
    private var previewTime: String? = null

    private var gradientShader: Shader? = null
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }

    private val drawables = HashMap<String, Drawable?>()
    private val placements = ArrayList<Placement>(5)
    private val calendar = Calendar.getInstance()

    private var hour = -1
    private var minute = -1
    private var contentWidth = 0f
    private var contentHeight = 0f
    private var originX = 0f
    private var originY = 0f

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshTime()
        }
    }

    private class Placement(
        val name: String,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val inkLeft: Float,
        val inkTop: Float,
        val inkRight: Float,
        val inkBottom: Float,
        val isColon: Boolean,
        val alpha: Float,
    )

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.HyperClockView)
        try {
            mode = a.getInt(R.styleable.HyperClockView_hyperMode, MODE_ART)
            a.getString(R.styleable.HyperClockView_hyperFamily)?.let { family = it }
            minuteFamily = a.getString(R.styleable.HyperClockView_hyperMinuteFamily)
            val emPx = a.getDimension(R.styleable.HyperClockView_hyperEmSize, -1f)
            em = if (emPx > 0f) emPx else DEFAULT_EM_DP * resources.displayMetrics.density
            showColon = a.getBoolean(R.styleable.HyperClockView_hyperShowColon, mode == MODE_ART)
            digitColor = a.getColor(R.styleable.HyperClockView_hyperDigitColor, Color.WHITE)
            colonColor = a.getColor(R.styleable.HyperClockView_hyperColonColor, DEFAULT_COLON_COLOR)
            attrColonColor = colonColor
            colonFollowsDigits =
                a.getBoolean(R.styleable.HyperClockView_hyperColonFollowsDigits, false)
            colonFamily = a.getString(R.styleable.HyperClockView_hyperColonFamily)
            colonScale = a.getFloat(R.styleable.HyperClockView_hyperColonScale, 1f)
                .coerceIn(0.1f, 4f)
            colonDx = a.getFloat(R.styleable.HyperClockView_hyperColonDx, 0f)
            colonDy = a.getFloat(R.styleable.HyperClockView_hyperColonDy, 0f)
            colonOnTop = a.getBoolean(R.styleable.HyperClockView_hyperColonOnTop, false)
            minuteAlpha = a.getFloat(R.styleable.HyperClockView_hyperMinuteAlpha, 1f)
                .coerceIn(0f, 1f)
            digitGap = a.getFloat(R.styleable.HyperClockView_hyperDigitGap, ROW_DIGIT_GAP)
            pairGap = a.getFloat(R.styleable.HyperClockView_hyperPairGap, ROW_PAIR_GAP)
            artGapX = a.getFloat(R.styleable.HyperClockView_hyperArtGapX, ART_GAP_X)
            artDropY = a.getFloat(R.styleable.HyperClockView_hyperArtDropY, ART_DROP_Y)
            artRowDx = a.getFloat(R.styleable.HyperClockView_hyperArtRowDx, ART_ROW_DX)
            artRowDy = a.getFloat(R.styleable.HyperClockView_hyperArtRowDy, ART_ROW_DY)
            stackDy = a.getFloat(R.styleable.HyperClockView_hyperStackDy, STACK_DY)
            duoDy = a.getFloat(R.styleable.HyperClockView_hyperDuoDy, DUO_DY)
            previewTime = a.getString(R.styleable.HyperClockView_hyperPreviewTime)
        } finally {
            a.recycle()
        }
        readTime()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(
            timeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_LOCALE_CHANGED)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        refreshTime()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { context.unregisterReceiver(timeReceiver) }
    }

    fun refreshTime() {
        val oldHour = hour
        val oldMinute = minute
        readTime()
        if (hour == oldHour && minute == oldMinute) return
        layoutGlyphs()
        requestLayout()
        invalidate()
    }

    fun setDigitColor(color: Int) {
        if (digitColor == color) return
        digitColor = color
        invalidate()
    }

    fun setColonColor(color: Int) {
        if (colonColor == color) return
        colonColor = color
        invalidate()
    }

    /** Back to the colon colour the layout asked for, after a doze override. */
    fun restoreColonColor() {
        setColonColor(attrColonColor)
    }

    fun setColonFollowsDigits(follow: Boolean) {
        if (colonFollowsDigits == follow) return
        colonFollowsDigits = follow
        invalidate()
    }

    /** Shader in this view's coordinates, or null to go back to a flat colour. */
    fun setGradientShader(shader: Shader?) {
        if (gradientShader === shader) return
        gradientShader = shader
        invalidate()
    }

    fun setEmSizePx(px: Float) {
        if (px <= 0f || px == em) return
        em = px
        layoutGlyphs()
        requestLayout()
        invalidate()
    }

    private fun readTime() {
        val preview = previewTime
        if (preview != null) {
            val parts = preview.split(":")
            hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 16
            minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 25
            return
        }
        calendar.timeInMillis = System.currentTimeMillis()
        val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
        hour = if (DateFormat.is24HourFormat(context)) {
            hour24
        } else {
            if (hour24 % 12 == 0) 12 else hour24 % 12
        }
        minute = calendar.get(Calendar.MINUTE)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (placements.isEmpty()) layoutGlyphs()
        val w = ceil(contentWidth).toInt() + paddingLeft + paddingRight
        val h = ceil(contentHeight).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSizeAndState(w, widthMeasureSpec, 0),
            resolveSizeAndState(h, heightMeasureSpec, 0),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (placements.isEmpty()) layoutGlyphs()
        val dx = paddingLeft - originX
        val dy = paddingTop - originY

        val ownColour = !colonFollowsDigits
        if (ownColour && !colonOnTop) drawPass(canvas, dx, dy, true, colonColor)

        val shader = gradientShader
        val layer = if (shader != null) {
            canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        } else {
            -1
        }
        if (!ownColour && !colonOnTop) drawPass(canvas, dx, dy, true, digitColor)
        drawPass(canvas, dx, dy, false, digitColor)
        if (!ownColour && colonOnTop) drawPass(canvas, dx, dy, true, digitColor)
        if (shader != null) {
            gradientPaint.shader = shader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)
            canvas.restoreToCount(layer)
        }
        if (ownColour && colonOnTop) drawPass(canvas, dx, dy, true, colonColor)
    }

    private fun drawPass(canvas: Canvas, dx: Float, dy: Float, colon: Boolean, color: Int) {
        canvas.save()
        canvas.translate(dx, dy)
        for (i in placements.indices) {
            val p = placements[i]
            if (p.isColon == colon) draw(canvas, p, color)
        }
        canvas.restore()
    }

    private fun draw(canvas: Canvas, p: Placement, color: Int) {
        val d = drawable(p.name) ?: return
        d.setBounds(
            p.x.roundToInt(),
            p.y.roundToInt(),
            (p.x + p.w).roundToInt(),
            (p.y + p.h).roundToInt(),
        )
        d.setTint(color)
        d.alpha = (p.alpha * 255f).roundToInt().coerceIn(0, 255)
        d.draw(canvas)
    }

    private fun drawable(name: String): Drawable? {
        if (drawables.containsKey(name)) return drawables[name]
        val id = HyperGlyphDrawables.id(name)
        val d = if (id == 0) null else context.getDrawable(id)?.mutate()
        drawables[name] = d
        return d
    }

    private fun layoutGlyphs() {
        placements.clear()
        when (mode) {
            MODE_DUO -> layoutDuo()
            MODE_ROW -> layoutRow()
            MODE_STACK -> layoutStack()
            else -> layoutArt()
        }
        measureContent()
    }

    private fun tens(value: Int): Int = (value / 10) % 10
    private fun units(value: Int): Int = value % 10

    private fun place(
        fam: String,
        index: Int,
        x: Float,
        y: Float,
        alpha: Float,
        scale: Float = 1f,
    ): Placement? {
        val m = HyperGlyphMetrics.table(fam) ?: return null
        if (!HyperGlyphMetrics.has(m, index)) return null
        val glyph = if (index == HyperGlyphMetrics.COLON) "colon" else index.toString()
        val e = em * scale
        val inkX = x + HyperGlyphMetrics.inkX(m, index) * e
        val inkY = y + HyperGlyphMetrics.inkY(m, index) * e
        val p = Placement(
            name = "font_" + fam + "_" + glyph,
            x = x,
            y = y,
            w = HyperGlyphMetrics.vw(m, index) * e,
            h = HyperGlyphMetrics.vh(m, index) * e,
            inkLeft = inkX,
            inkTop = inkY,
            inkRight = inkX + HyperGlyphMetrics.inkW(m, index) * e,
            inkBottom = inkY + HyperGlyphMetrics.inkH(m, index) * e,
            isColon = index == HyperGlyphMetrics.COLON,
            alpha = alpha,
        )
        placements.add(p)
        return p
    }

    /** Ink inset of a glyph from its box left edge, at the same [scale] as [place]. */
    private fun inkInset(fam: String, index: Int, scale: Float = 1f): Float {
        val m = HyperGlyphMetrics.table(fam) ?: return 0f
        return HyperGlyphMetrics.inkX(m, index) * em * scale
    }

    private fun placeColon(cx: Float, cy: Float) {
        if (!showColon) return
        val fam = colonFamily ?: family
        val m = HyperGlyphMetrics.table(fam) ?: return
        val i = HyperGlyphMetrics.COLON
        if (!HyperGlyphMetrics.has(m, i)) return
        val e = em * colonScale
        val midX = HyperGlyphMetrics.inkX(m, i) + HyperGlyphMetrics.inkW(m, i) / 2f
        val midY = HyperGlyphMetrics.inkY(m, i) + HyperGlyphMetrics.inkH(m, i) / 2f
        place(fam, i, cx + colonDx * em - midX * e, cy + colonDy * em - midY * e, 1f, colonScale)
    }

    private fun layoutArt() {
        val fam = family
        val h1 = tens(hour)
        val h2 = units(hour)
        val m1 = tens(minute)
        val m2 = units(minute)

        val first = place(fam, h1, 0f, 0f, 1f) ?: return
        val x2 = first.inkRight + artGapX * em - inkInset(fam, h2)
        val y2 = first.y + artDropY * em
        place(fam, h2, x2, y2, 1f)

        val x3 = first.inkLeft + artRowDx * em - inkInset(fam, m1)
        val y3 = first.y + artRowDy * em
        val third = place(fam, m1, x3, y3, minuteAlpha) ?: return
        val x4 = third.inkRight + artGapX * em - inkInset(fam, m2)
        val y4 = third.y + artDropY * em
        val fourth = place(fam, m2, x4, y4, minuteAlpha) ?: return

        placeColon(
            (first.inkRight + fourth.inkLeft) / 2f + COLON_NUDGE_X * em,
            (first.y + em + fourth.y) / 2f,
        )
    }

    private fun layoutRow() {
        val minFam = minuteFamily ?: family
        val colonFam = colonFamily ?: family
        val colonAvailable = HyperGlyphMetrics.table(colonFam)
            ?.let { HyperGlyphMetrics.has(it, HyperGlyphMetrics.COLON) } == true
        val withColon = showColon && colonAvailable

        val fams = arrayOfNulls<String>(5)
        val indices = IntArray(5)
        val alphas = FloatArray(5)
        var n = 0
        fams[n] = family; indices[n] = tens(hour); alphas[n] = 1f; n++
        fams[n] = family; indices[n] = units(hour); alphas[n] = 1f; n++
        if (withColon) {
            fams[n] = colonFam; indices[n] = HyperGlyphMetrics.COLON; alphas[n] = 1f; n++
        }
        fams[n] = minFam; indices[n] = tens(minute); alphas[n] = minuteAlpha; n++
        fams[n] = minFam; indices[n] = units(minute); alphas[n] = minuteAlpha; n++

        var pen = 0f
        var prevWasColon = false
        for (i in 0 until n) {
            val fam = fams[i]!!
            val index = indices[i]
            val isColon = index == HyperGlyphMetrics.COLON
            val scale = if (isColon) colonScale else 1f
            val gap = when {
                i == 0 -> 0f
                isColon || prevWasColon -> pairGap
                else -> digitGap
            }
            val x = pen + gap * em - inkInset(fam, index, scale) +
                (if (isColon) colonDx * em else 0f)
            val p = place(fam, index, x, if (isColon) colonRowY(fam, scale) else 0f, alphas[i],
                scale)
            pen = p?.inkRight ?: pen
            prevWasColon = isColon
        }
    }

    private fun colonRowY(fam: String, scale: Float): Float {
        val m = HyperGlyphMetrics.table(fam) ?: return 0f
        val i = HyperGlyphMetrics.COLON
        val mid = HyperGlyphMetrics.inkY(m, i) + HyperGlyphMetrics.inkH(m, i) / 2f
        return mid * em * (1f - scale) + colonDy * em
    }

    private fun layoutStack() {
        val minFam = minuteFamily ?: family
        for (row in 0..1) {
            val fam = if (row == 0) family else minFam
            val value = if (row == 0) hour else minute
            val alpha = if (row == 0) 1f else minuteAlpha
            val y = row * stackDy * em
            var pen = 0f
            for (i in 0..1) {
                val index = if (i == 0) tens(value) else units(value)
                val x = (if (i == 0) 0f else pen + digitGap * em) - inkInset(fam, index)
                val p = place(fam, index, x, y, alpha)
                pen = p?.inkRight ?: pen
            }
        }
    }

    private fun layoutDuo() {
        val hourIndex = (if (hour > 12) hour - 12 else hour).coerceIn(0, 12)
        val dy = duoDy * em
        val bottom = duoLayer(
            String.format(Locale.US, "font_j_r_bottom_%02d", minute),
            dy,
            HyperGlyphMetrics.DUO_BOT_X,
            HyperGlyphMetrics.DUO_BOT_Y,
            HyperGlyphMetrics.DUO_BOT_W,
            HyperGlyphMetrics.DUO_BOT_H,
            minuteAlpha,
        )
        placements.add(bottom)
        val top = duoLayer(
            String.format(Locale.US, "font_j_r_top_%02d", hourIndex),
            0f,
            HyperGlyphMetrics.DUO_TOP_X,
            HyperGlyphMetrics.DUO_TOP_Y,
            HyperGlyphMetrics.DUO_TOP_W,
            HyperGlyphMetrics.DUO_TOP_H,
            1f,
        )
        placements.add(top)
        placeColon(
            (max(top.inkLeft, bottom.inkLeft) + min(top.inkRight, bottom.inkRight)) / 2f,
            (bottom.inkTop + top.inkBottom) / 2f,
        )
    }

    private fun duoLayer(
        name: String,
        y: Float,
        inkX: Float,
        inkY: Float,
        inkW: Float,
        inkH: Float,
        alpha: Float,
    ): Placement {
        val s = em / HyperGlyphMetrics.DUO_VH
        val left = inkX * s
        val top = y + inkY * s
        return Placement(
            name = name,
            x = 0f,
            y = y,
            w = HyperGlyphMetrics.DUO_VW * s,
            h = em,
            inkLeft = left,
            inkTop = top,
            inkRight = left + inkW * s,
            inkBottom = top + inkH * s,
            isColon = false,
            alpha = alpha,
        )
    }

    private fun measureContent() {
        if (placements.isEmpty()) {
            contentWidth = 0f
            contentHeight = 0f
            originX = 0f
            originY = 0f
            return
        }
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in placements.indices) {
            val p = placements[i]
            if (p.inkLeft < left) left = p.inkLeft
            if (p.inkTop < top) top = p.inkTop
            if (p.inkRight > right) right = p.inkRight
            if (p.inkBottom > bottom) bottom = p.inkBottom
        }
        originX = left
        originY = top
        contentWidth = right - left
        contentHeight = bottom - top
    }

    companion object {
        const val MODE_ART = 0
        const val MODE_ROW = 1
        const val MODE_STACK = 2
        const val MODE_DUO = 3

        private const val DEFAULT_EM_DP = 104f
        private val DEFAULT_COLON_COLOR = 0xFFFA2A2A.toInt()

        private const val ART_GAP_X = 0.05f
        private const val ART_DROP_Y = 0.58f
        private const val ART_ROW_DX = -0.36f
        private const val ART_ROW_DY = 1.04f
        private const val COLON_NUDGE_X = 0.02f

        private const val ROW_DIGIT_GAP = 0.02f
        private const val ROW_PAIR_GAP = 0.10f
        private const val STACK_DY = 0.80f
        private const val DUO_DY = 0.50f
    }
}
