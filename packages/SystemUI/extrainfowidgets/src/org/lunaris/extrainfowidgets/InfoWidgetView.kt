/* SPDX-License-Identifier: GPL-3.0-only */
package org.lunaris.extrainfowidgets

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.android.axion.blur.AxBlurBackgroundRenderer
import com.android.axion.blur.AxBlurColors
import com.android.internal.util.lunaris.FontController
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONObject

data class InfoAppearance(
    val colorMode: String = "default",
    val customColor: Int = Color.WHITE,
    val opacity: Int = 100,
    val blur: Boolean = false,
    val dozing: Boolean = false,
)

class InfoWidgetView(context: Context, val widget: InfoWidget) : View(context) {
    var data: InfoData = InfoData()
        set(value) {
            if (field != value) {
                if (field.weather?.icon !== value.weather?.icon) {
                    weatherIcon =
                        value.weather?.icon?.let {
                            (it.constantState?.newDrawable(resources) ?: it).mutate()
                        }
                }
                field = value
                refresh()
            }
        }

    var appearance = InfoAppearance()
        set(value) {
            if (field == value) return
            field = value
            val opacity = value.opacity.coerceIn(0, if (value.dozing) 70 else 100)
            alpha = opacity / 100f
            blurs.values.forEach { it.setEnabled(value.blur && !value.dozing) }
            if (value.dozing) {
                animate().cancel()
                scaleX = 1f
                scaleY = 1f
                isPressed = false
                tap = null
            }
            updateClickability()
            refresh()
        }

    var onAction: ((String) -> Unit)? = null
        set(value) {
            field = value
            updateClickability()
        }

    private val blurs = mutableMapOf<String, AxBlurBackgroundRenderer>()
    private var blurSource: View? = null
    private val paint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = Rect()
    private val path = Path()
    private val layers =
        widget.design.getJSONArray("layers").let { array ->
            (0 until array.length()).associate { i ->
                array.getJSONObject(i).let { it.getString("id") to it }
            }
        }
    private val actions =
        layers.values.mapNotNull { it.optString("tile").takeIf(String::isNotEmpty) }.distinct()
    private val hits = mutableMapOf<String, RectF>()
    private var frames: Frame? = null
    private var downX = 0f
    private var downY = 0f
    private var tap: String? = null
    private val fonts = mutableMapOf<String, Typeface>()
    private val rippleMask = GradientDrawable().apply { setColor(Color.WHITE) }
    private val ripple = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, rippleMask)
    private val mediaPlaceholder = JSONObject().put("type", "MEDIA_ICON").put("weight", 500)
    private var weatherIcon: Drawable? = null
    private val weatherSampleIcon by lazy {
        context.getDrawable(R.drawable.extra_info_weather_sunny)!!.mutate()
    }
    private val weatherUnavailableIcon by lazy {
        context.getDrawable(R.drawable.extra_info_weather_unavailable)!!.mutate()
    }
    private val weatherDozeFilter by lazy {
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        refresh()
    }

    private fun updateClickability() {
        isClickable = onAction != null && actions.isNotEmpty() && !appearance.dozing
        isFocusable = isClickable
        foreground = if (isClickable) ripple else null
    }

    fun setBlurSource(source: View?) {
        blurSource = source
        blurs.values.forEach { configureSource(it) }
    }

    private fun configureSource(blur: AxBlurBackgroundRenderer) {
        blur.setSourceView(blurSource)
        blur.setPreferSourceBlur(blurSource != null)
        blur.setRequireSourceBlur(blurSource != null)
        if (blurSource != null) blur.refreshSourceBlur()
    }

    private fun backgroundBlur(id: String): AxBlurBackgroundRenderer =
        blurs.getOrPut(id) {
            AxBlurBackgroundRenderer(this, enabled = appearance.blur && !appearance.dozing).also {
                configureSource(it)
                if (isAttachedToWindow) it.onAttachedToWindow()
            }
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blurs.values.forEach {
            configureSource(it)
            it.onAttachedToWindow()
        }
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        isPressed = false
        tap = null
        blurs.values.forEach { it.onDetachedFromWindow() }
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        fonts.clear()
        refresh()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rippleMask.cornerRadius =
            min(w, h) * (layers["bg"]?.optDouble("radius", 0.5)?.toFloat() ?: 0.5f)
    }

    override fun onVisibilityAggregated(visible: Boolean) {
        super.onVisibilityAggregated(visible)
        blurs.values.forEach { it.onVisibilityAggregated(visible) }
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        blurs.values.any { it.verifyDrawable(who) } || super.verifyDrawable(who)

    private fun refresh() {
        frames = null
        contentDescription =
            (listOf(widget.name) +
                    layers.values
                        .filter {
                            it.optString("type") !in
                                setOf("TEXT", "CARD", "GAUGE", "TILE", "MEDIA_ART") &&
                                (!it.optString("type").endsWith("_ICON") ||
                                    it.optString("type") == "WEATHER_ICON")
                        }
                        .map { text(it) })
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(", ")
        invalidate()
    }

    private fun text(layer: JSONObject): String {
        val result =
            if (layer.optString("type") == "TILE") data.glyph(layer.optString("tile"))
            else data.text(context, layer.optString("type"), layer.optString("format"))
        return if (layer.optBoolean("caps")) result.uppercase(Locale.getDefault()) else result
    }

    private fun prepare(layer: JSONObject, size: Float) {
        val symbol =
            layer.optString("type").endsWith("_ICON") ||
                layer.optString("type") == "TILE" ||
                layer.optString("font").startsWith("res:symbols")
        val weight =
            if (appearance.dozing) layer.optInt("sleepWeight", 250) else layer.optInt("weight", 500)
        val fill = if (layer.has("tile") && data.tile(layer.optString("tile")) == 0) 0 else 1
        val key = "$symbol/$weight/$fill"
        paint.typeface =
            fonts.getOrPut(key) {
                val customTypeface =
                    if (symbol) null
                    else
                        FontController.getOverrideTypeface(
                            if (weight >= 500) "sans-serif-medium" else "sans-serif"
                        )
                val resource =
                    if (symbol) R.font.extra_info_symbols else R.font.extra_info_google_sans_flex
                val base = customTypeface ?: resources.getFont(resource)
                TextPaint().apply {
                    typeface = Typeface.create(base, weight, false)
                    fontVariationSettings =
                        when {
                            symbol -> "'FILL' $fill, 'wght' $weight"
                            customTypeface == null -> "'opsz' 144, 'wght' $weight"
                            else -> null
                        }
                }.typeface
            }
        paint.textSize = size
        paint.letterSpacing = layer.optDouble("tracking", 0.0).toFloat()
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.WHITE
    }

    private data class Frame(
        val node: JSONObject,
        val width: Float,
        val height: Float,
        val children: List<Frame> = emptyList(),
        val text: String = "",
        val cap: Float = height,
    )

    private fun measure(node: JSONObject): Frame {
        if (node.has("layer")) {
            val layer = layers.getValue(node.getString("layer"))
            val size = node.getDouble("size").toFloat()
            if (layer.optString("type") in setOf("CARD", "GAUGE", "MEDIA_ART", "WEATHER_ICON")) {
                return Frame(node, size, size / layer.optDouble("aspect", 1.0).toFloat())
            }
            prepare(layer, 1000f)
            var value = text(layer)
            if (layer.has("maxEms"))
                value =
                    TextUtils.ellipsize(
                            value,
                            paint,
                            layer.getDouble("maxEms").toFloat() * 1000f,
                            TextUtils.TruncateAt.END,
                        )
                        .toString()
            paint.getTextBounds("0", 0, 1, rect)
            val icon =
                layer.optString("type").endsWith("_ICON") ||
                    layer.optString("type") == "TILE" ||
                    layer.optString("font").startsWith("res:symbols")
            val cap = size * if (icon) 1f else rect.height() / 1000f
            return Frame(
                node,
                paint.measureText(value) / 1000f * size,
                cap + size * 0.24f,
                text = value,
                cap = cap,
            )
        }
        val kind = listOf("stack", "row", "column").first { node.has(it) }
        val group = node.getJSONObject(kind)
        val array = group.getJSONArray("children")
        val children = (0 until array.length()).map { measure(array.getJSONObject(it)) }
        val gap = group.optDouble("gap", 0.0).toFloat() * (children.size - 1).coerceAtLeast(0)
        val width =
            when (kind) {
                "row" -> children.sumOf { it.width.toDouble() }.toFloat() + gap
                "stack" ->
                    group
                        .optDouble("width", children.maxOfOrNull { it.width }?.toDouble() ?: 0.0)
                        .toFloat()
                else -> children.maxOfOrNull { it.width } ?: 0f
            }
        val height =
            when (kind) {
                "column" -> children.sumOf { it.height.toDouble() }.toFloat() + gap
                "stack" ->
                    group
                        .optDouble("height", children.maxOfOrNull { it.height }?.toDouble() ?: 0.0)
                        .toFloat()
                else -> children.maxOfOrNull { it.height } ?: 0f
            }
        val descent =
            when (kind) {
                "column" -> children.lastOrNull()?.let { it.height - it.cap } ?: 0f
                "row" -> children.maxOfOrNull { it.height - it.cap } ?: 0f
                else -> 0f
            }
        return Frame(node, width, height, children, cap = height - descent)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = frames ?: measure(widget.design.getJSONObject("large")).also { frames = it }
        val unit =
            min(width / frame.width.coerceAtLeast(0.001f), height / maxOf(0.171f, frame.height))
        hits.clear()
        drawFrame(
            canvas,
            frame,
            (width - frame.width * unit) / 2f,
            (height - frame.height * unit) / 2f,
            unit,
        )
    }

    private fun offset(align: String, outer: Float, inner: Float) =
        when (align) {
            "END" -> outer - inner
            "CENTER" -> (outer - inner) / 2f
            else -> 0f
        }

    private fun drawFrame(canvas: Canvas, frame: Frame, x: Float, y: Float, unit: Float) {
        val node = frame.node
        if (node.has("layer")) {
            drawLayer(canvas, frame, x, y, unit)
            return
        }
        val kind = listOf("stack", "row", "column").first { node.has(it) }
        val group = node.getJSONObject(kind)
        var cursor = 0f
        frame.children.forEach { child ->
            val align =
                if (kind == "stack") child.node.optString("anchor", "START")
                else group.optString("align", "START")
            val left =
                when (kind) {
                    "row" -> cursor
                    else -> offset(align, frame.width, child.width)
                } + child.node.optDouble("x", 0.0).toFloat()
            val top =
                when (kind) {
                    "column" -> cursor
                    "stack" -> (frame.height - child.cap) / 2f
                    else -> offset(align, frame.height, child.height)
                }
            drawFrame(canvas, child, x + left * unit, y + top * unit, unit)
            cursor +=
                (if (kind == "row") child.width else child.height) +
                    group.optDouble("gap", 0.0).toFloat()
        }
    }

    private fun color(value: String): Int {
        val original =
            if (value == "accent1") context.getColor(android.R.color.system_accent1_100)
            else runCatching { Color.parseColor(value) }.getOrDefault(Color.WHITE)
        if (appearance.dozing) return (original and -0x1000000) or 0xFFFFFF
        val foreground = value == "accent1" || (original and 0xFFFFFF) == 0xFFFFFF
        if (!foreground) return original
        val tint =
            when (appearance.colorMode) {
                "accent" -> context.getColor(android.R.color.system_accent1_100)
                "custom" -> appearance.customColor
                else -> return original
            }
        return (original and -0x1000000) or (tint and 0xFFFFFF)
    }

    private fun drawLayer(canvas: Canvas, frame: Frame, x: Float, y: Float, unit: Float) {
        val id = frame.node.getString("layer")
        val layer = layers.getValue(id)
        if (appearance.dozing && layer.optString("sleepStyle") == "HIDE") return
        val type = layer.getString("type")
        val box = RectF(x, y, x + frame.width * unit, y + frame.cap * unit)
        var ink = color(layer.optString("color", "#FFFFFF"))
        val tile = layer.optString("tile")
        if (tile.isNotEmpty()) {
            hits[tile]?.union(box) ?: run { hits[tile] = RectF(box) }
            if (!appearance.dozing && data.tile(tile) != 0 && layer.has("activeColor"))
                ink = color(layer.getString("activeColor"))
        }
        paint.color = ink
        paint.style = Paint.Style.FILL
        paint.shader = null
        val radius = min(box.width(), box.height()) * layer.optDouble("radius", 0.5).toFloat()
        when (type) {
            "CARD" -> {
                val outlined = layer.optBoolean("outline") || appearance.dozing
                if (appearance.blur && !appearance.dozing && !outlined) {
                    val overlay =
                        if (tile.isNotEmpty() && data.tile(tile) != 0)
                            (ink and 0xFFFFFF) or (0x73 shl 24)
                        else AxBlurColors.surfaceLightTint(context)
                    if (
                        backgroundBlur(id)
                            .draw(
                                canvas,
                                id,
                                box.left.roundToInt(),
                                box.top.roundToInt(),
                                box.right.roundToInt(),
                                box.bottom.roundToInt(),
                                radius,
                                overlay,
                                255,
                            )
                    )
                        return
                    paint.color = (overlay and 0xFFFFFF) or 0xCC000000.toInt()
                }
                if (outlined) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = layer.optDouble("outlineWidth", 1.5).toFloat() * unit / 400f
                    box.inset(paint.strokeWidth / 2f, paint.strokeWidth / 2f)
                }
                canvas.drawRoundRect(box, radius, radius, paint)
            }
            "GAUGE" -> {
                val value =
                    if (layer.optString("format") == "steps") (data.steps ?: 0) / 10000f
                    else (data.battery ?: 0) / 100f
                val progress = value.coerceIn(0f, 1f)
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                val bar = layer.optDouble("aspect", 1.0) > 4
                paint.strokeWidth =
                    if (bar) box.height()
                    else box.width() * layer.optDouble("strokeWidth", 0.1).toFloat()
                if (appearance.dozing) paint.strokeWidth *= 0.5f
                box.inset(paint.strokeWidth / 2f, paint.strokeWidth / 2f)
                paint.color = (ink and 0xFFFFFF) or ((Color.alpha(ink) * 0.28f).toInt() shl 24)
                if (bar) canvas.drawLine(box.left, box.centerY(), box.right, box.centerY(), paint)
                else canvas.drawArc(box, 135f, 270f, false, paint)
                paint.color = ink
                if (progress > 0f) {
                    if (bar)
                        canvas.drawLine(
                            box.left,
                            box.centerY(),
                            box.left + box.width() * progress,
                            box.centerY(),
                            paint,
                        )
                    else canvas.drawArc(box, 135f, 270f * progress, false, paint)
                }
            }
            "WEATHER_ICON" -> {
                val icon =
                    weatherIcon?.takeUnless { it is ColorDrawable && Color.alpha(it.color) == 0 }
                        ?: if (data.weather?.conditionCode == 32) weatherSampleIcon
                        else weatherUnavailableIcon
                val iconWidth = icon.intrinsicWidth.takeIf { it > 0 } ?: 1
                val iconHeight = icon.intrinsicHeight.takeIf { it > 0 } ?: 1
                val scale = min(box.width() / iconWidth, box.height() / iconHeight)
                val halfWidth = iconWidth * scale / 2f
                val halfHeight = iconHeight * scale / 2f
                icon.setBounds(
                    (box.centerX() - halfWidth).roundToInt(),
                    (box.centerY() - halfHeight).roundToInt(),
                    (box.centerX() + halfWidth).roundToInt(),
                    (box.centerY() + halfHeight).roundToInt(),
                )
                icon.colorFilter = if (appearance.dozing) weatherDozeFilter else null
                icon.draw(canvas)
            }
            "MEDIA_ART" -> {
                canvas.save()
                path.reset()
                path.addRoundRect(box, radius, radius, Path.Direction.CW)
                canvas.clipPath(path)
                val art = data.art
                if (art != null && !art.isRecycled) {
                    val side = min(art.width, art.height)
                    rect.set(
                        (art.width - side) / 2,
                        (art.height - side) / 2,
                        (art.width + side) / 2,
                        (art.height + side) / 2,
                    )
                    canvas.drawBitmap(art, rect, box, paint)
                } else {
                    paint.color = 0x33FFFFFF
                    canvas.drawRect(box, paint)
                    prepare(mediaPlaceholder, box.height() * 0.45f)
                    paint.color = ink
                    val icon = "\uE405"
                    val metrics = paint.fontMetrics
                    canvas.drawText(
                        icon,
                        box.centerX() - paint.measureText(icon) / 2f,
                        box.centerY() - (metrics.ascent + metrics.descent) / 2f,
                        paint,
                    )
                }
                canvas.restore()
            }
            else -> {
                prepare(layer, frame.node.getDouble("size").toFloat() * unit)
                paint.color = ink
                canvas.drawText(frame.text, x, y + frame.cap * unit, paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isClickable || appearance.dozing) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                tap =
                    if (actions.size == 1) actions.first()
                    else
                        hits.entries
                            .filter {
                                RectF(it.value)
                                    .apply {
                                        inset(
                                            -8 * resources.displayMetrics.density,
                                            -8 * resources.displayMetrics.density,
                                        )
                                    }
                                    .contains(event.x, event.y)
                            }
                            .minByOrNull {
                                val dx = it.value.centerX() - event.x
                                val dy = it.value.centerY() - event.y
                                dx * dx + dy * dy
                            }
                            ?.key
                if (tap != null) {
                    drawableHotspotChanged(event.x, event.y)
                    press(true)
                }
                return tap != null
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    tap = null
                    press(false)
                }
            }
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) tap = null
                if (tap != null) performClick()
                tap = null
                press(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                tap = null
                press(false)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!appearance.dozing && onAction != null) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            (tap ?: actions.firstOrNull { it == "MEDIA" } ?: actions.firstOrNull())?.let {
                onAction?.invoke(it)
            }
        }
        return true
    }

    private fun press(down: Boolean) {
        isPressed = down
        animate().cancel()
        animate()
            .scaleX(if (down) 0.94f else 1f)
            .scaleY(if (down) 0.94f else 1f)
            .setDuration(if (down) 90 else 220)
            .setInterpolator(if (down) DecelerateInterpolator() else OvershootInterpolator(1.2f))
            .start()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (onAction == null || appearance.dozing) return
        actions.forEachIndexed { index, action ->
            val title =
                when (action) {
                    "MEDIA_PREVIOUS" -> context.getString(R.string.extra_info_previous)
                    "MEDIA_NEXT" -> context.getString(R.string.extra_info_next)
                    "MEDIA" -> context.getString(R.string.extra_info_play_pause)
                    else -> widget.name
                }
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(0x01000000 + index, title))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (onAction != null && !appearance.dozing) {
            val target =
                if (action == AccessibilityNodeInfo.ACTION_CLICK)
                    actions.firstOrNull { it == "MEDIA" } ?: actions.firstOrNull()
                else actions.getOrNull(action - 0x01000000)
            if (target != null) {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onAction?.invoke(target)
                return true
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
