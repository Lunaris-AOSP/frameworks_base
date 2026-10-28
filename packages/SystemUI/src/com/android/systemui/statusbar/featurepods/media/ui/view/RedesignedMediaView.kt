/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.featurepods.media.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import com.android.systemui.statusbar.featurepods.media.ui.view.MediaArtworkEffects.Companion.alpha
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

internal class RedesignedMediaView(context: Context) : ViewGroup(context) {
    data class Action(val icon: Drawable?, val label: CharSequence?, val run: Runnable?)

    data class State(
        val title: CharSequence?,
        val artist: CharSequence?,
        val artwork: Drawable?,
        val appIcon: Drawable?,
        val trackKey: String,
        val playing: Boolean,
        val position: Long,
        val duration: Long,
        val playbackSpeed: Float,
        val canSeek: Boolean,
        val seekTo: ((Long) -> Unit)?,
        val previous: Action?,
        val playPause: Action?,
        val next: Action?,
        val custom: List<Action>,
    )

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val effects = MediaArtworkEffects(resources)
    private val title = label(17f, 600, Color.WHITE)
    private val artist = label(14.5f, 400, 0x8cffffff.toInt())
    private val elapsed = label(12f, 500, 0x99ffffff.toInt())
    private val total = label(12f, 500, 0x99ffffff.toInt())
    private val buttons = List(5) { index -> MediaButton(index).also { addView(it) } }
    private val centers = floatArrayOf(.08f, .30f, .50f, .70f, .92f)
    private var state: State? = null
    private var seekBar: SeekBar? = null
    private var seekBarStyle: Any? = null
    private var updateSeekBar: ((SeekBar, Boolean, Boolean) -> Unit)? = null
    private var scrubbing = false
    private var cancelScrub = false
    private var seekGesture = false
    private var basePosition = 0L
    private var baseTime = 0L
    private var statePositionTime = 0L
    private var shownPosition = 0L
    private var awaitingSeekUntil = 0L
    private var revealMillis = Float.POSITIVE_INFINITY
    private var aggregatedVisible = false
    private var headerHeight = 0f
    private var seekCenterY = 0f
    private var controlsTop = 0f
    private var appGlyph: Drawable? = null
    private var lastAppIcon: Drawable? = null
    var seekDescription: ((String, String) -> String)? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        layoutDirection = LAYOUT_DIRECTION_LTR
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        elapsed.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        total.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun label(size: Float, weight: Int, color: Int) =
        TextView(context).apply {
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, weight, false)
            setTextColor(color)
            setSingleLine()
            includeFontPadding = false
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            addView(this)
        }

    fun setSeekBar(
        style: Any,
        factory: () -> SeekBar,
        update: (SeekBar, Boolean, Boolean) -> Unit,
    ) {
        updateSeekBar = update
        if (style == seekBarStyle) return
        if (scrubbing || seekGesture) restorePosition()
        cancelScrub = scrubbing || seekGesture
        scrubbing = false
        seekGesture = false
        parent?.requestDisallowInterceptTouchEvent(false)
        seekBar?.let { removeView(it) }
        seekBarStyle = style
        seekBar =
            factory().apply {
                background = null
                layoutDirection = LAYOUT_DIRECTION_LTR
                setPadding(0, 0, 0, 0)
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            cancelScrub = false
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_CANCEL -> cancelScrub = true
                    }
                    false
                }
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            bar: SeekBar,
                            progress: Int,
                            fromUser: Boolean,
                        ) {
                            if (!fromUser || (cancelScrub && seekGesture)) return
                            val duration = state?.duration?.coerceAtLeast(0L) ?: 0L
                            shownPosition =
                                (progress.toDouble() / bar.max.coerceAtLeast(1) * duration).toLong()
                            elapsed.text = formatTime(shownPosition)
                            if (!scrubbing) commitSeek()
                            invalidate()
                        }

                        override fun onStartTrackingTouch(bar: SeekBar) {
                            scrubbing = true
                            awaitingSeekUntil = 0L
                            updateSeekBar?.invoke(bar, state?.playing == true, true)
                        }

                        override fun onStopTrackingTouch(bar: SeekBar) {
                            scrubbing = false
                            if (!cancelScrub) commitSeek() else restorePosition()
                            parent?.requestDisallowInterceptTouchEvent(false)
                            updateSeekBar?.invoke(bar, state?.playing == true, false)
                            invalidate()
                        }
                    }
                )
                addView(this)
            }
        requestLayout()
    }

    private fun commitSeek() {
        val current = state ?: return
        if (!current.canSeek || current.seekTo == null || current.duration <= 0) return
        basePosition = shownPosition.coerceIn(0L, current.duration)
        baseTime = SystemClock.elapsedRealtime()
        awaitingSeekUntil = baseTime + 1500L
        current.seekTo.invoke(basePosition)
    }

    private fun restorePosition() {
        basePosition = state?.position?.coerceAtLeast(0L) ?: 0L
        baseTime = statePositionTime
        awaitingSeekUntil = 0L
    }

    fun bind(value: State, reveal: Float) {
        val previous = state
        val now = SystemClock.elapsedRealtime()
        val trackChanged = previous?.trackKey != value.trackKey
        if (trackChanged || !value.canSeek) {
            cancelScrub = scrubbing || seekGesture
            scrubbing = false
            awaitingSeekUntil = 0L
        }
        val playbackChanged =
            trackChanged ||
                previous?.position != value.position ||
                previous.duration != value.duration ||
                previous.playing != value.playing ||
                previous.playbackSpeed != value.playbackSpeed
        if (playbackChanged) statePositionTime = now
        if (
            playbackChanged &&
                !scrubbing &&
                (now >= awaitingSeekUntil ||
                    kotlin.math.abs(value.position - basePosition) < 1000L ||
                    trackChanged)
        ) {
            basePosition = value.position.coerceAtLeast(0L)
            baseTime = now
            awaitingSeekUntil = 0L
        }
        state = value
        revealMillis = reveal
        if (title.text != value.title) title.text = value.title
        if (artist.text != value.artist) artist.text = value.artist
        val totalText = if (value.duration > 0) formatTime(value.duration) else "--:--"
        if (total.text != totalText) total.text = totalText
        effects.bind(value.artwork, value.trackKey, value.playing, SystemClock.uptimeMillis())
        if (lastAppIcon !== value.appIcon) {
            lastAppIcon = value.appIcon
            appGlyph =
                (value.appIcon as? AdaptiveIconDrawable)?.monochrome?.let {
                    (it.constantState?.newDrawable(resources) ?: it).mutate().apply {
                        setTint(Color.WHITE)
                    }
                }
        }
        buttons[0].bind(value.custom.getOrNull(0))
        buttons[1].bind(value.previous)
        buttons[2].bind(value.playPause)
        buttons[3].bind(value.next)
        buttons[4].bind(value.custom.getOrNull(1))
        seekBar?.let { bar ->
            bar.isEnabled = value.canSeek && value.duration > 0 && value.seekTo != null
            bar.max = value.duration.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            updateSeekBar?.invoke(bar, value.playing, scrubbing)
        }
        updateRows()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(dp(420f), widthMeasureSpec)
        val textWidth = (width - dp(148f)).coerceAtLeast(1)
        title.measure(
            MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        artist.measure(
            MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        headerHeight =
            maxOf(
                56f * density,
                title.measuredHeight +
                    if (artist.text.isNullOrEmpty()) 0f else artist.measuredHeight + 3f * density,
            )
        seekCenterY = 18f * density + headerHeight + 23f * density
        controlsTop = seekCenterY + 17f * density
        setMeasuredDimension(
            width,
            resolveSize((controlsTop + 66f * density).roundToInt(), heightMeasureSpec),
        )
        val timeWidth =
            maxOf(
                    elapsed.paint.measureText("00:00"),
                    total.paint.measureText(total.text.toString()),
                )
                .roundToInt() + dp(1f)
        measureExactly(elapsed, timeWidth, dp(36f))
        measureExactly(total, timeWidth, dp(36f))
        seekBar?.let {
            measureExactly(it, (width - dp(56f) - timeWidth * 2).coerceAtLeast(1), dp(36f))
        }
        buttons.forEach { measureExactly(it, dp(52f), dp(52f)) }
    }

    private fun measureExactly(view: View, width: Int, height: Int) {
        view.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pad = dp(18f)
        val textHeight =
            title.measuredHeight +
                if (artist.text.isNullOrEmpty()) 0 else artist.measuredHeight + dp(3f)
        val textTop = pad + ((headerHeight - textHeight) / 2).roundToInt()
        place(title, dp(88f), textTop)
        place(artist, dp(88f), textTop + title.measuredHeight + dp(3f))
        place(elapsed, pad, (seekCenterY - 18f * density).roundToInt())
        place(total, width - pad - total.measuredWidth, elapsed.top)
        seekBar?.let { place(it, elapsed.right + dp(10f), elapsed.top) }
        buttons.forEachIndexed { index, button ->
            place(
                button,
                (pad + (width - pad * 2) * centers[index] - button.measuredWidth / 2f).roundToInt(),
                controlsTop.roundToInt(),
            )
        }
    }

    private fun place(view: View, left: Int, top: Int) =
        view.layout(left, top, left + view.measuredWidth, top + view.measuredHeight)

    override fun shouldDelayChildPressedState() = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val bar = seekBar
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            seekGesture =
                bar != null &&
                    bar.isEnabled &&
                    event.x >= bar.left - dp(12f) &&
                    event.x <= bar.right + dp(12f) &&
                    event.y >= bar.top + bar.translationY &&
                    event.y <= bar.bottom + bar.translationY
        }
        if (seekGesture && bar != null) {
            val translated = MotionEvent.obtain(event)
            translated.offsetLocation(-bar.left.toFloat(), -bar.top - bar.translationY)
            val handled = bar.dispatchTouchEvent(translated)
            translated.recycle()
            if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                seekGesture = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return handled
        }
        return super.dispatchTouchEvent(event)
    }

    private fun rowAlpha(row: Int) = ((revealMillis - row * 30f) / 200f).coerceIn(0f, 1f)

    private fun updateRows() {
        fun apply(view: View, row: Int) {
            view.alpha = rowAlpha(row)
            view.translationY = (1 - view.alpha) * 8f * density
        }
        apply(title, 0)
        apply(artist, 0)
        apply(elapsed, 1)
        apply(total, 1)
        seekBar?.let { apply(it, 1) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        effects.drawBackground(canvas, width.toFloat(), height.toFloat(), now)
        effects.drawArtwork(canvas, rowAlpha(0), now)
        drawAppIcon(canvas)
        val current = state
        if (awaitingSeekUntil != 0L && SystemClock.elapsedRealtime() >= awaitingSeekUntil) {
            restorePosition()
        }
        if (current != null && !scrubbing) {
            shownPosition =
                (basePosition +
                        if (current.playing)
                            ((SystemClock.elapsedRealtime() - baseTime) * current.playbackSpeed)
                                .toLong()
                        else 0L)
                    .coerceIn(0L, current.duration.coerceAtLeast(0L))
            val text = formatTime(shownPosition)
            if (elapsed.text != text) elapsed.text = text
            seekBar?.let { bar ->
                val description = seekDescription?.invoke(text, total.text.toString())
                if (bar.contentDescription != description) bar.contentDescription = description
                val progress =
                    if (current.duration > 0)
                        (shownPosition.toDouble() / current.duration * bar.max).toInt()
                    else 0
                if (bar.progress != progress) bar.progress = progress
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val save = canvas.save()
        canvas.translate(0f, (1 - rowAlpha(2)) * 8f * density)
        buttons.forEach { if (it.visibility == VISIBLE) it.drawControl(canvas, now) }
        canvas.restoreToCount(save)
        if (isAttachedToWindow && aggregatedVisible) {
            if (ValueAnimator.areAnimatorsEnabled()) postInvalidateOnAnimation()
            else if (state?.playing == true || awaitingSeekUntil != 0L) postInvalidateDelayed(250L)
        }
    }

    private fun drawAppIcon(canvas: Canvas) {
        val drawable = appGlyph ?: state?.appIcon ?: return
        val left = width - dp(50f)
        val top = dp(18f) + (1 - rowAlpha(0)) * 8f * density
        rect.set(left.toFloat(), top, left + 32f * density, top + 32f * density)
        val save = canvas.save()
        path.reset()
        path.addRoundRect(rect, 9f * density, 9f * density, Path.Direction.CW)
        canvas.clipPath(path)
        if (appGlyph != null) {
            paint.color = alpha(0x2effffff, rowAlpha(0))
            canvas.drawRect(rect, paint)
            rect.inset(-4f * density, -4f * density)
        }
        drawable.setBounds(
            rect.left.roundToInt(),
            rect.top.roundToInt(),
            rect.right.roundToInt(),
            rect.bottom.roundToInt(),
        )
        val oldAlpha = drawable.alpha
        drawable.alpha = (rowAlpha(0) * 255).roundToInt()
        drawable.draw(canvas)
        drawable.alpha = oldAlpha
        canvas.restoreToCount(save)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        effects.resetFrameClock()
        if (isVisible) invalidate()
    }

    override fun onDetachedFromWindow() {
        aggregatedVisible = false
        scrubbing = false
        seekGesture = false
        effects.resetFrameClock()
        super.onDetachedFromWindow()
    }

    private inner class MediaButton(private val index: Int) : View(context) {
        private var action: Action? = null
        private var downAt = -1000L
        private var releaseAt = -1000L
        private var releasedPress = 0f

        init {
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                runCatching { action?.run?.run() }
            }
        }

        fun bind(value: Action?) {
            action = value
            contentDescription = value?.label
            isEnabled = value?.run != null
            visibility = if ((index == 0 || index == 4) && value == null) GONE else VISIBLE
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = Button::class.java.name
        }

        override fun setPressed(pressed: Boolean) {
            if (pressed != isPressed) {
                val now = SystemClock.uptimeMillis()
                if (pressed) downAt = now
                else {
                    releasedPress = press(now)
                    releaseAt = now
                }
                this@RedesignedMediaView.invalidate()
            }
            super.setPressed(pressed)
        }

        private fun press(now: Long): Float {
            if (!ValueAnimator.areAnimatorsEnabled()) return 0f
            if (isPressed) return 1 - (1 - ((now - downAt) / 110f).coerceIn(0f, 1f)).pow(3)
            val u = ((now - releaseAt) / 380f).coerceIn(0f, 1f)
            return releasedPress * cos(u * Math.PI.toFloat() * 1.6f) * (1 - u).pow(2)
        }

        fun drawControl(canvas: Canvas, now: Long) {
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            val side = (if (index == 2) 52f else 43.68f) * density
            val opacity = rowAlpha(2) * if (isEnabled) 1f else .35f
            val press = press(now)
            if (press > 0f) {
                paint.color = alpha(Color.WHITE, (if (index == 2) .22f else .16f) * opacity * press)
                canvas.drawCircle(
                    cx,
                    cy,
                    side / 2 +
                        (if (index == 2) 5f else if (index == 1 || index == 3) 10f else 0f) *
                            density,
                    paint,
                )
            }
            val save = canvas.save()
            val scale = 1 - .14f * press
            canvas.scale(scale, scale, cx, cy)
            if (index == 2) {
                paint.color = alpha(0x24ffffff, opacity)
                canvas.drawCircle(cx, cy, side / 2, paint)
            }
            paint.color = alpha(Color.WHITE, opacity)
            when (index) {
                1,
                3 -> drawSkip(canvas, cx, cy, side * .5f, index == 3)
                2 -> drawPlayPause(canvas, cx, cy, side * .4f)
                else -> {
                    val icon = action?.icon
                    if (icon != null) {
                        val half = side * .3f
                        icon.setBounds(
                            (cx - half).roundToInt(),
                            (cy - half).roundToInt(),
                            (cx + half).roundToInt(),
                            (cy + half).roundToInt(),
                        )
                        val oldAlpha = icon.alpha
                        icon.alpha = (opacity * 255).roundToInt()
                        icon.draw(canvas)
                        icon.alpha = oldAlpha
                    } else {
                        paint.textSize = 12f * resources.displayMetrics.scaledDensity
                        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        val label = action?.label?.toString().orEmpty()
                        val fit = paint.breakText(label, true, side, null)
                        val text = label.take(fit)
                        canvas.drawText(
                            text,
                            cx - paint.measureText(text) / 2,
                            cy - (paint.ascent() + paint.descent()) / 2,
                            paint,
                        )
                    }
                }
            }
            canvas.restoreToCount(save)
            val t = (now - downAt) / 750f
            if (t !in 0f..<1f || !ValueAnimator.areAnimatorsEnabled()) return
            val e = 1 - (1 - t).pow(3)
            val ring = (.95f + 1.15f * e) * side / 2
            val band = (9 + 11 * e) * density
            val outer = ring + band
            paint.shader =
                RadialGradient(
                    cx,
                    cy,
                    outer,
                    intArrayOf(
                        0x00ffffff,
                        0x00ffffff,
                        alpha(Color.WHITE, .4f * opacity * (1 - t).pow(2)),
                        0x00ffffff,
                    ),
                    floatArrayOf(0f, ((ring - band) / outer).coerceIn(0f, 1f), ring / outer, 1f),
                    Shader.TileMode.CLAMP,
                )
            paint.alpha = 255
            canvas.drawCircle(cx, cy, outer, paint)
            paint.shader = null
        }
    }

    private fun drawPlayPause(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        if (state?.playing == true) {
            val bar = size * .22f
            val gap = size * .09f
            canvas.drawRoundRect(
                cx - gap - bar,
                cy - size * .4f,
                cx - gap,
                cy + size * .4f,
                bar * .35f,
                bar * .35f,
                paint,
            )
            canvas.drawRoundRect(
                cx + gap,
                cy - size * .4f,
                cx + gap + bar,
                cy + size * .4f,
                bar * .35f,
                bar * .35f,
                paint,
            )
        } else {
            val left = cx - .38f * .78f * size
            path.reset()
            path.moveTo(left, cy - size * .41f)
            path.lineTo(left + .86f * .78f * size, cy)
            path.lineTo(left, cy + size * .41f)
            path.close()
            drawRoundedPath(canvas, size)
        }
    }

    private fun drawSkip(canvas: Canvas, cx: Float, cy: Float, size: Float, next: Boolean) {
        val direction = if (next) 1f else -1f
        val tip = cx + .26f * size * direction
        val base = cx - .26f * size * direction
        val halfHeight = .36f * size
        path.reset()
        path.moveTo(base, cy - halfHeight)
        path.lineTo(tip, cy)
        path.lineTo(base, cy + halfHeight)
        path.close()
        drawRoundedPath(canvas, size)
        val bar = .11f * size
        val x = tip + direction * bar * .9f
        canvas.drawRoundRect(
            minOf(x, x + direction * bar),
            cy - halfHeight,
            maxOf(x, x + direction * bar),
            cy + halfHeight,
            bar / 2,
            bar / 2,
            paint,
        )
    }

    private fun drawRoundedPath(canvas: Canvas, size: Float) {
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = .06f * size
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
    }

    private fun formatTime(position: Long) =
        DateUtils.formatElapsedTime(position.coerceAtLeast(0L) / 1000L)

    private fun dp(value: Float) = (value * density).roundToInt()
}
