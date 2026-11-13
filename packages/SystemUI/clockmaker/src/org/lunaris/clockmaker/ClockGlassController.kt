/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BlurMaskFilter
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.drawable.Drawable
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Rect
import android.text.Layout
import android.text.TextPaint
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import android.widget.TextClock
import kotlin.math.ceil
import kotlin.math.roundToInt

class ClockGlassController(private val host: View) :
    View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private class Entry(val view: TextView, val style: ClockGlassStyle, val date: Boolean,
        private val ready: Runnable) {
        val originalShader = view.paint.shader
        private val originalShadow = floatArrayOf(view.shadowRadius, view.shadowDx, view.shadowDy)
        private val originalShadowColor = view.shadowColor
        var background: ClockGlass? = null
        var shader: RuntimeShader? = null
        private var outlineShader: RuntimeShader? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val maskPaint = TextPaint()
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var explicitShadow: Bitmap? = null
        private val shadowOffsets = IntArray(2)
        private var mask: Bitmap? = null
        private var inset = 0
        private val viewport = Rect()
        private var maskLeft = 0f
        private var maskTop = 0f
        private var layout: Layout? = null
        private var text: String? = null
        private var tint = 0
        private var failed = false
        private var applied = false
        private var dirty = true
        private val extentPoints = FloatArray(4)
        val matrix = Matrix()
        private val drawable = object : Drawable() {
            override fun draw(canvas: Canvas) {
                val save = canvas.save()
                canvas.translate(view.compoundPaddingLeft.toFloat(), view.totalPaddingTop.toFloat())
                drawGlass(canvas, 1f)
                canvas.restoreToCount(save)
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: ColorFilter?) {}
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }

        fun restore() {
            if (!applied) return
            if (view is ClockOutlineTextView) view.glassFill = null else view.overlay.remove(drawable)
            view.paint.shader = originalShader
            view.setShadowLayer(originalShadow[0], originalShadow[1], originalShadow[2], originalShadowColor)
            applied = false
            view.invalidate()
        }

        fun matches(view: TextView, style: ClockGlassStyle, date: Boolean, enabled: Boolean): Boolean {
            if (this.view !== view || this.style != style || this.date != date) return false
            if (!enabled) return true
            return originalShader === view.paint.shader && originalShadowColor == view.shadowColor &&
                originalShadow[0] == view.shadowRadius && originalShadow[1] == view.shadowDx &&
                originalShadow[2] == view.shadowDy
        }

        private fun releaseMaterial() {
            paint.shader = null
            shader = null
            outlineShader = null
            mask = null
            explicitShadow = null
        }

        fun release() {
            restore()
            releaseMaterial()
            background = null
            layout = null
            text = null
            failed = false
        }

        private fun fail(error: Throwable) {
            restore()
            releaseMaterial()
            failed = true
            Log.w("ClockGlass", "Cannot create glyph lens", error)
        }

        private fun drawGlass(canvas: Canvas, alpha: Float) {
            val mask = mask ?: return
            val shader = shader ?: return
            val opacity = (Color.alpha(tint) * alpha).roundToInt().coerceIn(0, 255)
            if (opacity <= 0) return
            explicitShadow?.let {
                shadowPaint.color = originalShadowColor
                shadowPaint.alpha = (Color.alpha(originalShadowColor) * opacity / 255f).roundToInt()
                canvas.drawBitmap(it, shadowOffsets[0] + maskLeft + originalShadow[1],
                    shadowOffsets[1] + maskTop + originalShadow[2], shadowPaint)
            }
            paint.shader = shader
            paint.alpha = opacity
            canvas.drawRect(maskLeft, maskTop, maskLeft + mask.width, maskTop + mask.height, paint)
        }

        fun prepare(requestedBackground: ClockGlass): Boolean {
            val background = if (requestedBackground.isBlurReady(style, ready)) requestedBackground
                else this.background ?: return false
            val currentLayout = view.layout ?: return false
            val currentText = view.text.toString()
            val width = (view.width - view.compoundPaddingLeft - view.compoundPaddingRight)
                .coerceIn(1, currentLayout.width.coerceAtLeast(1))
            val height = (view.height - view.totalPaddingTop - view.totalPaddingBottom)
                .coerceIn(1, currentLayout.height.coerceAtLeast(1))
            val left = view.scrollX
            val top = view.scrollY
            val maskChanged = layout !== currentLayout || text != currentText ||
                !maskPaint.equalsForTextMeasurement(view.paint) ||
                viewport.left != left || viewport.top != top ||
                viewport.width() != width || viewport.height() != height
            val changed = maskChanged || this.background !== background || tint != view.currentTextColor
            if (!changed && (failed || shader != null)) return !failed && shader != null
            restore()
            paint.shader = null
            shader = null
            outlineShader = null
            failed = false
            layout = currentLayout
            text = currentText
            this.background = background
            tint = view.currentTextColor
            viewport.set(left, top, left + width, top + height)
            try {
                if (maskChanged || mask == null) {
                    releaseMaterial()
                    maskPaint.set(view.paint)
                    inset = ceil(maxOf(style.glyphBlur / 10f * 3f, originalShadow[0] * 3f,
                        kotlin.math.abs(originalShadow[1]), kotlin.math.abs(originalShadow[2]), 16f)).toInt()
                    val bitmapWidth = width.toLong() + inset.toLong() * 2
                    val bitmapHeight = height.toLong() + inset.toLong() * 2
                    require(bitmapWidth in 1..MAX_MASK_DIMENSION && bitmapHeight in 1..MAX_MASK_DIMENSION &&
                        bitmapWidth * bitmapHeight <= MAX_MASK_PIXELS) {
                        "Glyph mask is too large: ${bitmapWidth}x${bitmapHeight}"
                    }
                    maskLeft = left.toFloat() - inset
                    maskTop = top.toFloat() - inset
                    val bitmap = Bitmap.createBitmap(bitmapWidth.toInt(), bitmapHeight.toInt(), Bitmap.Config.ARGB_8888)
                    val saved = TextPaint(view.paint)
                    try {
                        view.paint.shader = null
                        view.paint.color = Color.WHITE
                        view.paint.alpha = 255
                        view.paint.style = Paint.Style.FILL
                        view.paint.clearShadowLayer()
                        view.paint.maskFilter = if (style.glyphBlur > 0)
                            BlurMaskFilter(style.glyphBlur / 10f, BlurMaskFilter.Blur.NORMAL) else null
                        val canvas = Canvas(bitmap)
                        canvas.translate(-maskLeft, -maskTop)
                        currentLayout.draw(canvas)
                    } finally {
                        view.paint.set(saved)
                    }
                    mask = bitmap
                    explicitShadow = if (originalShadow[0] > 0f) bitmap.extractAlpha(Paint().apply {
                        maskFilter = BlurMaskFilter(originalShadow[0], BlurMaskFilter.Blur.NORMAL)
                    }, shadowOffsets) else null
                }
                val bitmap = mask ?: return false
                val material = background.shader(bitmap, style, tint, date, maskLeft, maskTop, ready) ?: return false
                shader = material
                dirty = true
                outlineShader = if (view is ClockOutlineTextView) {
                    background.shader(bitmap, style, tint, date, maskLeft, maskTop, ready)?.apply {
                        setFloatUniform("outline", 1f)
                    }
                } else null
            } catch (error: OutOfMemoryError) {
                fail(error)
            } catch (error: Exception) {
                fail(error)
            }
            return !failed && shader != null
        }

        fun apply(values: FloatArray, transform: Matrix, width: Int, height: Int) {
            val shaders = listOfNotNull(shader, outlineShader)
            shaders.forEach {
                it.setFloatUniform("mapX", values[0], values[1], values[2])
                it.setFloatUniform("mapY", values[3], values[4], values[5])
                it.setFloatUniform("mapW", values[6], values[7], values[8])
                extentPoints[0] = viewport.exactCenterX()
                extentPoints[1] = viewport.top.toFloat()
                extentPoints[2] = extentPoints[0]
                extentPoints[3] = viewport.bottom.toFloat()
                transform.mapPoints(extentPoints)
                val extent = kotlin.math.hypot(extentPoints[2] - extentPoints[0], extentPoints[3] - extentPoints[1]) /
                    (background?.clear?.height ?: height)
                val t = ((extent - 0.015f) / 0.085f).coerceIn(0f, 1f)
                it.setFloatUniform("dateFade", t * t * (3f - 2f * t))
                val scaleX = (background?.clear?.width ?: width).toFloat() / width
                val scaleY = (background?.clear?.height ?: height).toFloat() / height
                val determinant = values[0] * values[4] - values[1] * values[3]
                val inv = if (kotlin.math.abs(determinant) > 0.0001f) 1f / determinant else 1f
                it.setFloatUniform("shadowOffset", values[1] * 3f * scaleY * inv,
                    -values[0] * 3f * scaleY * inv)
                it.setFloatUniform("shadowRadius", kotlin.math.abs(values[4] * 10f * scaleX * inv),
                    kotlin.math.abs(values[0] * 10f * scaleY * inv))
            }
            matrix.set(transform)
            if (view is ClockOutlineTextView) {
                view.paint.shader = outlineShader
                view.glassFill = ::drawGlass
            } else {
                view.paint.shader = hidden
                view.setShadowLayer(0f, 0f, 0f, 0)
                if (!applied) view.overlay.add(drawable)
                drawable.setBounds(0, 0, view.width, view.height)
            }
            applied = true
            dirty = false
            view.invalidate()
        }

        fun needsApply(transform: Matrix): Boolean = dirty || !applied || matrix != transform ||
            view.paint.shader !== if (view is ClockOutlineTextView) outlineShader else hidden

        companion object {
            private const val MAX_MASK_DIMENSION = 4096L
            private const val MAX_MASK_PIXELS = 4L * 1024 * 1024
            private val hidden: Shader = LinearGradient(0f, 0f, 0f, 1f,
                Color.TRANSPARENT, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
    }

    private val entries = mutableListOf<Entry>()
    private val previousEntries = mutableListOf<Entry>()
    private var blurRadii = intArrayOf()
    private val textToScreen = Matrix()
    private val bitmapToText = Matrix()
    private val textToBitmap = Matrix()
    private val spaceToScreen = Matrix()
    private val values = FloatArray(9)
    private var observing: ViewTreeObserver? = null
    private var listening = false
    private var preview = false
    private var previewBackground: ClockGlass? = null
    private var coordinateRoot: View? = null
    private val changed = Runnable { update() }
    var enabled = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) entries.forEach { it.restore() }
            host.invalidate()
        }
    var userId = host.context.userId
        set(value) {
            if (field == value) return
            entries.forEach { it.release() }
            previousEntries.forEach { it.release() }
            field = value
            update()
        }

    init { host.addOnAttachStateChangeListener(this) }

    fun clear() {
        entries.forEach { it.release() }
        previousEntries.forEach { it.release() }
        entries.clear()
        previousEntries.clear()
        blurRadii = intArrayOf()
        observe()
    }

    fun beginUpdate() {
        previousEntries.forEach { it.release() }
        previousEntries.clear()
        entries.forEach { it.restore() }
        previousEntries.addAll(entries)
        entries.clear()
    }

    fun add(view: TextView, style: ClockGlassStyle, date: Boolean = isDate(view)) {
        val normalized = style.normalized()
        val cached = previousEntries.firstOrNull { it.matches(view, normalized, date, enabled) }
        if (cached != null) previousEntries.remove(cached)
        entries.add(cached ?: Entry(view, normalized, date, changed))
    }

    fun commit() {
        previousEntries.forEach { it.release() }
        previousEntries.clear()
        blurRadii = entries.map { it.style.blurRadius }.distinct().toIntArray()
        observe()
        update()
    }

    fun setPreview(background: ClockGlass?, space: View) {
        preview = true
        previewBackground = background
        coordinateRoot = space
        observe()
        update()
    }

    private fun observe() {
        val active = host.isAttachedToWindow && entries.isNotEmpty()
        if (observing?.isAlive == false) observing = null
        if (active && observing == null) {
            observing = host.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        } else if (!active) {
            observing?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observing = null
        }
        val watchWallpaper = active && !preview
        if (watchWallpaper && !listening) ClockGlassWallpaper.listen(host.context, changed)
        if (!watchWallpaper && listening) ClockGlassWallpaper.unlisten(changed)
        listening = watchWallpaper
    }

    override fun onViewAttachedToWindow(view: View) {
        observe()
        update()
    }

    override fun onViewDetachedFromWindow(view: View) {
        observing?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observing = null
        if (listening) ClockGlassWallpaper.unlisten(changed)
        listening = false
        entries.forEach { it.restore() }
    }

    override fun onPreDraw(): Boolean {
        update()
        return true
    }

    fun update() {
        if (entries.isEmpty()) return
        val background = if (preview) previewBackground else {
            if (!host.isAttachedToWindow) return
            ClockGlassWallpaper.get(host.context, userId, blurRadii)
        }
        if (background == null) {
            entries.forEach { it.restore() }
            return
        }
        entries.forEach { background.isBlurReady(it.style, changed) }
        if (!enabled) return
        if (!preview && (!host.isShown || host.windowVisibility != View.VISIBLE)) return
        if (entries.none { it.view.visibility == View.VISIBLE && it.view.alpha > 0f }) return
        val space = coordinateRoot
        val metrics = host.resources.displayMetrics
        val width = space?.width ?: metrics.widthPixels
        val height = space?.height ?: metrics.heightPixels
        if (width <= 0 || height <= 0) return
        for (entry in entries) {
            val view = entry.view
            if (view.width <= 0 || view.height <= 0 || view.visibility != View.VISIBLE) continue
            if (!entry.prepare(background)) continue
            val material = entry.background ?: continue
            spaceToScreen.reset()
            space?.transformMatrixToGlobal(spaceToScreen)
            spaceToScreen.preScale(width.toFloat() / material.clear.width, height.toFloat() / material.clear.height)
            textToScreen.reset()
            view.transformMatrixToGlobal(textToScreen)
            textToScreen.preTranslate((view.compoundPaddingLeft - view.scrollX).toFloat(),
                (view.totalPaddingTop - view.scrollY).toFloat())
            if (!textToScreen.invert(bitmapToText)) continue
            bitmapToText.preConcat(spaceToScreen)
            if (!bitmapToText.invert(textToBitmap)) continue
            if (entry.shader == null) continue
            if (entry.needsApply(textToBitmap)) {
                textToBitmap.getValues(values)
                entry.apply(values, textToBitmap, width, height)
            }
        }
    }

    companion object {
        fun isDate(view: TextView): Boolean {
            val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
            if (name.contains("date", true) || name.contains("day", true) || name.contains("month", true)) return true
            if (view !is TextClock) return false
            val format = (view.format24Hour ?: view.format12Hour)?.toString() ?: return false
            return format.replace(Regex("'[^']*'"), "").any { it in "dDEMLyY" }
        }
    }
}
