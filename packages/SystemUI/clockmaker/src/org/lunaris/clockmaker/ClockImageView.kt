/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.airbnb.lottie.LottieDrawable
import java.util.concurrent.Future

internal class ClockImageView(context: Context) : ImageView(context) {
    private val main = Handler(Looper.getMainLooper())
    private var imageId = ""
    private var imageUser = context.userId
    private var generation = 0
    private var pending: Future<*>? = null
    private var shape = "none"
    private var aodShape = "none"
    private var aodProgress = 0f
    private var fit = ClockImageFit.CROP
    private var aodFit = ClockImageFit.CROP
    private val imageTransform = Matrix()
    private var clip = Path()
    private var aggregatedVisible = false
    private var playbackAllowed = false
    private var borderWidth = 0f
    private var designWidth = 120f
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ScaleType.MATRIX
    }

    fun bind(element: ClockElement, user: Int, animate: Boolean, progress: Float = 0f) {
        val p = ClockElementAnimation.progress(progress)
        val frame = ClockElementAnimation.frame(element, p)
        playbackAllowed = animate && frame.alpha > 0f
        fit = element.imageFit
        aodFit = element.aodStyle.imageFit
        borderPaint.color = frame.imageBorderColor
        if (shape != element.imageShape || aodShape != element.aodStyle.imageShape || aodProgress != p ||
            borderWidth != frame.imageBorderWidth || designWidth != frame.imageWidth) {
            shape = element.imageShape
            aodShape = element.aodStyle.imageShape
            aodProgress = p
            borderWidth = frame.imageBorderWidth
            designWidth = frame.imageWidth
            updateClip()
        }
        updateImageTransform()
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
                    stopAnimation(loaded)
                    return@post
                }
                pending = null
                if (loaded is AnimatedImageDrawable) loaded.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                setImageDrawable(loaded)
                updateImageTransform()
                updatePlayback()
            }
        }
    }

    private fun updatePlayback() {
        val play = playbackAllowed && aggregatedVisible && isAttachedToWindow && windowVisibility == VISIBLE
        when (val animation = drawable) {
            is AnimatedImageDrawable -> if (play) {
                if (!animation.isRunning) animation.start()
            } else if (animation.isRunning) animation.stop()
            is LottieDrawable -> if (play) {
                if (!animation.isAnimating) animation.resumeAnimation()
            } else animation.pauseAnimation()
        }
    }

    private fun stopAnimation(image: Drawable?) {
        when (image) {
            is AnimatedImageDrawable -> image.stop()
            is LottieDrawable -> image.cancelAnimation()
        }
    }

    private fun release() {
        generation++
        pending?.cancel(true)
        pending = null
        stopAnimation(drawable)
        setImageDrawable(null)
    }

    private fun updateClip() {
        borderPaint.strokeWidth = (borderWidth * width / designWidth)
            .coerceAtMost(minOf(width, height) / 2f)
        val inset = borderPaint.strokeWidth / 2f
        clip = ClockImageShapes.path(shape, aodShape, aodProgress, (width - inset * 2).coerceAtLeast(0f),
            (height - inset * 2).coerceAtLeast(0f)).apply { offset(inset, inset) }
        invalidate()
    }

    private fun updateImageTransform() {
        val image = drawable ?: return
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        val dw = image.intrinsicWidth.toFloat()
        val dh = image.intrinsicHeight.toFloat()
        if (w <= 0f || h <= 0f || dw <= 0f || dh <= 0f) return
        fun scale(mode: ClockImageFit) = if (mode == ClockImageFit.CROP) maxOf(w / dw, h / dh)
            else minOf(w / dw, h / dh)
        val scale = ClockElementAnimation.lerp(scale(fit), scale(aodFit), aodProgress)
        imageTransform.setScale(scale, scale)
        imageTransform.postTranslate((w - dw * scale) / 2f, (h - dh * scale) / 2f)
        imageMatrix = imageTransform
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateClip()
        updateImageTransform()
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
