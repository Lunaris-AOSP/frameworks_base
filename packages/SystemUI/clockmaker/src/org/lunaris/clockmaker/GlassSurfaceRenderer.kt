/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver

class GlassSurfaceRenderer(private val host: View) : ViewTreeObserver.OnPreDrawListener {
    private data class Entry(val material: ClockGlass, val style: ClockGlassStyle, val tint: Int,
        val shader: RuntimeShader)

    private val entries = mutableMapOf<String, Entry>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val transform = Matrix()
    private val previous = Matrix()
    private val spaceToScreen = Matrix()
    private val screenToSpace = Matrix()
    private val values = FloatArray(9)
    private val ready = Runnable {
        prepareMaterial()
        if (enabled) host.invalidate()
    }
    private var observing: ViewTreeObserver? = null
    private var listening = false
    private var preview = false
    private var previewMaterial: ClockGlass? = null
    private var previewSpace: View? = null
    private var previewOffset = 0f
    private var preparedStyle: ClockGlassStyle? = null
    private var blurRadii = intArrayOf()
    private var failed = false

    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            if (value) failed = false
            observe()
            if (value) prepareMaterial()
            host.invalidate()
        }

    var userId = host.context.userId
        set(value) {
            if (field == value) return
            field = value
            clear()
            failed = false
            observe()
            prepareMaterial()
            host.invalidate()
        }

    fun setPreview(material: ClockGlass?, space: View, offsetY: Float = 0f) {
        if (preview && previewMaterial === material && previewSpace === space && previewOffset == offsetY) return
        preview = true
        previewMaterial = material
        previewSpace = space
        previewOffset = offsetY
        clear()
        failed = false
        observe()
        prepareMaterial()
        host.invalidate()
    }

    fun prepare(style: ClockGlassStyle?) {
        preparedStyle = style?.normalized()
        blurRadii = preparedStyle?.let { intArrayOf(it.blurRadius) } ?: intArrayOf()
        if (preparedStyle == null && !enabled) clear()
        observe()
        prepareMaterial()
    }

    private fun material(): ClockGlass? = if (preview) previewMaterial
        else ClockGlassWallpaper.get(host.context, userId, blurRadii)

    private fun prepareMaterial() {
        val requested = preparedStyle ?: return
        if (!preview && !host.isAttachedToWindow) return
        material()?.isBlurReady(requested, ready)
    }

    fun onAttachedToWindow() {
        observe()
        prepareMaterial()
    }

    fun onDetachedFromWindow() {
        observing?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observing = null
        if (listening) ClockGlassWallpaper.unlisten(ready)
        listening = false
        failed = false
    }

    fun onVisibilityAggregated(visible: Boolean) {
        observe(visible)
        if (visible) prepareMaterial()
    }

    private fun clear() {
        entries.clear()
        paint.shader = null
    }

    private fun observe(visible: Boolean = host.isShown) {
        val attached = (enabled || preparedStyle != null) && !failed && host.isAttachedToWindow
        val active = attached && enabled && visible && host.windowVisibility == View.VISIBLE
        if (observing?.isAlive == false) observing = null
        if (active && observing == null) {
            observing = host.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        } else if (!active) {
            observing?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observing = null
        }
        val watch = attached && !preview
        if (watch && !listening) ClockGlassWallpaper.listen(host.context, ready)
        if (!watch && listening) ClockGlassWallpaper.unlisten(ready)
        listening = watch
    }

    private fun map(material: ClockGlass): Boolean {
        transform.reset()
        host.transformMatrixToGlobal(transform)
        val space = previewSpace
        if (space != null) {
            if (space.width <= 0) return false
            spaceToScreen.reset()
            space.transformMatrixToGlobal(spaceToScreen)
            if (!spaceToScreen.invert(screenToSpace)) return false
            transform.postConcat(screenToSpace)
            transform.postTranslate(0f, previewOffset)
            val scale = material.clear.width.toFloat() / space.width
            transform.postScale(scale, scale)
            return true
        }
        val width = host.resources.displayMetrics.widthPixels
        val height = host.resources.displayMetrics.heightPixels
        if (width <= 0 || height <= 0) return false
        transform.postScale(material.clear.width.toFloat() / width, material.clear.height.toFloat() / height)
        return true
    }

    override fun onPreDraw(): Boolean {
        val material = material()
        preparedStyle?.let { material?.isBlurReady(it, ready) }
        if (material != null && map(material) && transform != previous) {
            previous.set(transform)
            host.invalidate()
        }
        return true
    }

    fun draw(canvas: Canvas, id: String, box: RectF, radius: Float, style: ClockGlassStyle, tint: Int): Boolean {
        if (!enabled || failed || !canvas.isHardwareAccelerated || box.isEmpty) return false
        val material = material() ?: return false
        try {
            val s = style.normalized()
            val cached = entries[id]
            val entry = if (cached?.material === material && cached.style == s && cached.tint == tint) cached else {
                val shader = material.surfaceShader(s, tint, ready)
                if (shader != null) Entry(material, s, tint, shader).also { entries[id] = it }
                else cached?.takeIf { it.style == s && it.tint == tint } ?: return false
            }
            if (!map(entry.material)) return false
            transform.getValues(values)
            entry.shader.apply {
                setFloatUniform("mapX", values[0], values[1], values[2])
                setFloatUniform("mapY", values[3], values[4], values[5])
                setFloatUniform("mapW", values[6], values[7], values[8])
                setFloatUniform("bounds", box.left, box.top, box.right, box.bottom)
                setFloatUniform("radius", radius.coerceIn(0f, minOf(box.width(), box.height()) * 0.5f))
            }
            paint.shader = entry.shader
            paint.alpha = 255
            val scale = kotlin.math.hypot(values[0], values[3]).coerceAtLeast(0.001f)
            val inset = if (s.shadowStrength > 0) 18f / scale else 1f
            canvas.drawRect(box.left - inset, box.top - inset, box.right + inset, box.bottom + inset, paint)
            return true
        } catch (error: Exception) {
            failed = true
            clear()
            observe()
            Log.w("LiquidGlass", "Cannot draw glass surface", error)
            return false
        }
    }
}
