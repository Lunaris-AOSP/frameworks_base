/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.graphics.Matrix
import android.graphics.Shader
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView

class ClockGlassController(private val host: View) :
    View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private class Entry(val view: TextView, val intensity: Int, val lift: Int) {
        val originalShader = view.paint.shader
        var background: ClockGlass? = null
        var shader: Shader? = null
        val matrix = Matrix()

        fun restore() {
            if (shader != null && view.paint.shader === shader) {
                view.paint.shader = originalShader
                view.invalidate()
            }
        }
    }

    private val entries = mutableListOf<Entry>()
    private val textToScreen = Matrix()
    private val bitmapToText = Matrix()
    private val spaceToScreen = Matrix()
    private var observing: ViewTreeObserver? = null
    private var listening = false
    private var preview = false
    private var previewBackground: ClockGlass? = null
    private var coordinateRoot: View? = null
    private val changed = Runnable { update() }
    var userId = host.context.userId
        set(value) {
            if (field == value) return
            field = value
            update()
        }

    init {
        host.addOnAttachStateChangeListener(this)
    }

    fun clear() {
        beginUpdate()
        observe()
    }

    fun beginUpdate() {
        entries.forEach { it.restore() }
        entries.clear()
    }

    fun add(view: TextView, intensity: Int, lift: Int) {
        entries.add(Entry(view, intensity.coerceIn(0, 100), lift.coerceIn(0, 100)))
    }

    fun commit() {
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
    }

    override fun onPreDraw(): Boolean {
        update()
        return true
    }

    fun update() {
        if (entries.isEmpty()) return
        if (!preview && (!host.isShown || host.windowVisibility != View.VISIBLE)) return
        if (entries.none { it.view.visibility == View.VISIBLE && it.view.alpha > 0f }) return
        val background =
            if (preview) previewBackground
            else {
                if (!host.isAttachedToWindow) return
                ClockGlassWallpaper.get(host.context, userId)
            }
        if (background == null) {
            entries.forEach { it.restore() }
            return
        }
        val space = coordinateRoot
        val metrics = host.resources.displayMetrics
        val width = space?.width ?: metrics.widthPixels
        val height = space?.height ?: metrics.heightPixels
        if (width <= 0 || height <= 0) return
        spaceToScreen.reset()
        space?.transformMatrixToGlobal(spaceToScreen)
        spaceToScreen.preScale(
            width.toFloat() / background.clear.width,
            height.toFloat() / background.clear.height,
        )
        for (entry in entries) {
            val view = entry.view
            if (view.width <= 0 || view.height <= 0 || view.visibility != View.VISIBLE) continue
            textToScreen.reset()
            view.transformMatrixToGlobal(textToScreen)
            textToScreen.preTranslate(
                (view.compoundPaddingLeft - view.scrollX).toFloat(),
                (view.totalPaddingTop - view.scrollY).toFloat(),
            )
            if (!textToScreen.invert(bitmapToText)) continue
            bitmapToText.preConcat(spaceToScreen)
            val replaced = entry.background !== background
            if (replaced) {
                entry.background = background
                entry.shader = background.shader(entry.intensity, entry.lift)
            }
            val shader = entry.shader ?: continue
            if (replaced || entry.matrix != bitmapToText || view.paint.shader !== shader) {
                entry.matrix.set(bitmapToText)
                shader.setLocalMatrix(bitmapToText)
                view.paint.shader = shader
                view.invalidate()
            }
        }
    }
}
