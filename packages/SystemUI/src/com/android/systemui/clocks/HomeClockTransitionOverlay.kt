/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import com.android.systemui.res.R

internal data class HomeClockPicture(val bitmap: Bitmap, val content: RectF)

internal data class HomeClockViewport(
    val uid: Int,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
)

internal class HomeClockTransitionOverlay(
    context: Context,
    display: Display,
    private val picture: HomeClockPicture,
    initialBounds: RectF,
    viewport: HomeClockViewport,
    private val sourceBounds: () -> RectF?,
    private val onPrepare: (HomeClockTransitionOverlay, RectF, Runnable) -> Boolean,
    private val onFrame: () -> Unit,
    private val onReady: (HomeClockTransitionOverlay, RectF) -> Unit,
) : FrameLayout(ContextThemeWrapper(
    context.createWindowContext(display, WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG, null),
    context.applicationInfo.theme,
)) {
    val displayId = display.displayId
    val clock: ClockStyle = (LayoutInflater.from(this.context)
        .inflate(R.layout.keyguard_clock_style, this, false) as ClockStyle).apply {
        isHomeScreenClock = true
        alpha = 0f
    }
    val bounds = RectF(initialBounds)
    var pictureAlpha = 1f
    var clockPadding = 0f
    var ready = false
        private set
    private val manager = requireNotNull(this.context.getSystemService(WindowManager::class.java))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blendPaint = Paint().apply { blendMode = BlendMode.PLUS }
    private val imageMatrix = Matrix()
    private val localBounds = RectF()
    private val blendBounds = RectF()
    private val position = IntArray(2)
    private var readyScheduled = false
    private var closed = false
    private var added = false
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (!closed) {
            if (!readyScheduled) {
                sourceBounds()?.let(bounds::set)
                val start = RectF(bounds)
                readyScheduled = true
                val committed = Runnable {
                    if (!closed) {
                        ready = true
                        onReady(this, start)
                    }
                }
                if (!onPrepare(this, start, committed)) {
                    if (isHardwareAccelerated) viewTreeObserver.registerFrameCommitCallback(committed)
                    else post(committed)
                }
            }
            onFrame()
        }
        true
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val holder = FrameLayout(this.context).apply {
            clipChildren = false
            clipToPadding = false
        }
        holder.addView(clock, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = (resources.getDimensionPixelSize(R.dimen.status_bar_height) * 1.25f)
                .toInt() - viewport.y
        })
        addView(holder, LayoutParams(viewport.width, viewport.height).apply {
            leftMargin = viewport.x
            topMargin = viewport.y
        })
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    fun show() {
        manager.addView(this, WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "HomeClockTransition"
            accessibilityTitle = " "
            gravity = Gravity.TOP or Gravity.LEFT
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY or
                WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
            windowAnimations = 0
        })
        added = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (closed || pictureAlpha <= 0f) return
        getLocationOnScreen(position)
        localBounds.set(bounds)
        localBounds.offset(-position[0].toFloat(), -position[1].toFloat())
        imageMatrix.setRectToRect(picture.content, localBounds, Matrix.ScaleToFit.FILL)
        blendBounds.set(0f, 0f, picture.bitmap.width.toFloat(), picture.bitmap.height.toFloat())
        imageMatrix.mapRect(blendBounds)
        localBounds.inset(-clockPadding, -clockPadding)
        blendBounds.union(localBounds)
        paint.alpha = (pictureAlpha.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawBitmap(picture.bitmap, imageMatrix, paint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (pictureAlpha > 0f && pictureAlpha < 1f) {
            val layer = canvas.saveLayer(blendBounds, blendPaint)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(layer)
        } else {
            super.dispatchDraw(canvas)
        }
    }

    fun hideOnDraw(view: View, committed: Runnable): Boolean {
        if (!view.isHardwareAccelerated) return false
        val root = view.rootSurfaceControl ?: return false
        val surface = viewRootImpl?.surfaceControl?.takeIf { it.isValid } ?: return false
        return runCatching {
            SurfaceControl.Transaction().use {
                it.hide(surface).addTransactionCommittedListener(context.mainExecutor) {
                    committed.run()
                }
                root.applyTransactionOnDraw(it)
            }
        }.getOrDefault(false)
    }

    fun close() {
        if (closed) return
        closed = true
        viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        if (added) runCatching { manager.removeViewImmediate(this) }
        added = false
    }
}
