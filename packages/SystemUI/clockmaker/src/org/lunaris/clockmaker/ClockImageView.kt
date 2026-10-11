/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import java.util.concurrent.Future

internal class ClockImageView(context: Context) : ImageView(context) {
    private val main = Handler(Looper.getMainLooper())
    private var imageId = ""
    private var imageUser = context.userId
    private var generation = 0
    private var pending: Future<*>? = null
    private var shape = "none"
    private var clip = Path()
    private var aggregatedVisible = false
    private var playbackAllowed = false
    private var borderWidth = 0
    private var designWidth = 120
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(element: ClockElement, user: Int, animate: Boolean) {
        playbackAllowed = animate && element.visible && element.opacity > 0
        scaleType = if (element.imageFit == ClockImageFit.CROP) ScaleType.CENTER_CROP else ScaleType.FIT_CENTER
        borderPaint.color = element.imageBorderColor
        if (shape != element.imageShape || borderWidth != element.imageBorderWidth || designWidth != element.imageWidth) {
            shape = element.imageShape
            borderWidth = element.imageBorderWidth
            designWidth = element.imageWidth
            updateClip()
        }
        if (imageId != element.image || imageUser != user) {
            release()
            imageId = element.image
            imageUser = user
            if (isAttachedToWindow) load()
        }
        updatePlayback()
        invalidate()
    }

    private fun load() {
        if (!ClockImages.validId(imageId) || drawable != null || pending != null) return
        val version = ++generation
        val id = imageId
        val user = imageUser
        pending = ClockImages.worker.submit {
            val loaded = runCatching { ClockImages.load(context, user, id) }.getOrNull()
            main.post {
                if (version != generation || !isAttachedToWindow) {
                    (loaded as? AnimatedImageDrawable)?.stop()
                    return@post
                }
                pending = null
                if (loaded is AnimatedImageDrawable) loaded.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                setImageDrawable(loaded)
                updatePlayback()
            }
        }
    }

    private fun updatePlayback() {
        (drawable as? AnimatedImageDrawable)?.let {
            if (playbackAllowed && aggregatedVisible && isAttachedToWindow && windowVisibility == VISIBLE) {
                if (!it.isRunning) it.start()
            } else if (it.isRunning) it.stop()
        }
    }

    private fun release() {
        generation++
        pending?.cancel(true)
        pending = null
        val animation = drawable as? AnimatedImageDrawable
        animation?.stop()
        setImageDrawable(null)
    }

    private fun updateClip() {
        borderPaint.strokeWidth = (borderWidth * width / designWidth.toFloat())
            .coerceAtMost(minOf(width, height) / 2f)
        val inset = borderPaint.strokeWidth / 2f
        clip = ClockImageShapes.path(shape, (width - inset * 2).coerceAtLeast(0f),
            (height - inset * 2).coerceAtLeast(0f)).apply { offset(inset, inset) }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateClip()
    }

    override fun onDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(clip)
        super.onDraw(canvas)
        canvas.restoreToCount(save)
        if (borderPaint.strokeWidth > 0f) canvas.drawPath(clip, borderPaint)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        updatePlayback()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updatePlayback()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        load()
        updatePlayback()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }
}
