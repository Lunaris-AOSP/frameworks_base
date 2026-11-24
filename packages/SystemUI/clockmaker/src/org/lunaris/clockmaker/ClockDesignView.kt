/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.android.axion.quicklook.QuickLookAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object ClockFonts {
    val uri: Uri = Uri.parse("content://org.lunaris.lockstyles.clockfonts/fonts")
    val families = listOf("sans-serif", "sans-serif-condensed", "sans-serif-light", "serif", "monospace")
    fun bundled(context: Context): List<Pair<String, String>> = runCatching {
        bundledResources(context).map { (name, _) ->
            "bundled:$name" to name.replace('_', ' ').replace('-', ' ')
        }.sortedBy { it.second.lowercase(Locale.ROOT) }
    }.getOrDefault(emptyList())

    private fun bundledResources(context: Context): List<Pair<String, Int>> {
        val res = context.resources
        // This library is linked into both SystemUI and Lockstyles. The generated R id from
        // the font library is not guaranteed to be valid in the consuming application's
        // resource table, so resolve the array through the active package first.
        val arrayIds = sequenceOf(
            res.getIdentifier("clock_maker_fonts", "array", context.packageName),
            res.getIdentifier("clock_maker_fonts", "array", "org.lunaris.clockmaker.fonts"),
            org.lunaris.clockmaker.fonts.R.array.clock_maker_fonts,
        ).filter { it != 0 }.distinct()
        for (arrayId in arrayIds) {
            val fonts = runCatching { res.obtainTypedArray(arrayId) }.getOrNull() ?: continue
            return try {
                (0 until fonts.length()).mapNotNull { index ->
                    fonts.getResourceId(index, 0).takeIf { it != 0 }?.let {
                        res.getResourceEntryName(it) to it
                    }
                }
            } finally {
                fonts.recycle()
            }
        }
        return emptyList()
    }

    fun openBundled(context: Context, family: String): java.io.InputStream {
        require(family.startsWith("bundled:"))
        val id = bundledResources(context).first { it.first == family.removePrefix("bundled:") }.second
        return context.resources.openRawResource(id)
    }

    internal val worker = Executors.newSingleThreadExecutor()
    private val cache = ConcurrentHashMap<String, Typeface>()
    private val generation = AtomicInteger()
    fun clear() { generation.incrementAndGet(); cache.clear() }
    fun cached(user: Int, font: String): Typeface? = if (font.startsWith("import:") || font.startsWith("bundled:")) {
        cache["$user/$font"]
    } else Typeface.create(font.takeIf { it in families } ?: "sans-serif", Typeface.NORMAL)

    fun load(context: Context, user: Int, font: String): Typeface {
        cached(user, font)?.let { return it }
        val version = generation.get()
        val face = runCatching {
            if (font.startsWith("bundled:")) {
                val id = bundledResources(context).first { it.first == font.removePrefix("bundled:") }.second
                context.resources.getFont(id)
            } else {
                val id = font.removePrefix("import:")
                require(id.matches(Regex("[a-f0-9-]{36}\\.(ttf|otf)")))
                val target = ContentProvider.maybeAddUserId(Uri.withAppendedPath(uri, id), user)
                context.contentResolver.openFileDescriptor(target, "r")!!.use {
                    Typeface.Builder(it.fileDescriptor).build()
                }
            }
        }.getOrDefault(Typeface.DEFAULT)
        if (cache.size >= 128) cache.clear()
        if (generation.get() == version) cache["$user/$font"] = face
        return face
    }
}

class ClockDesignView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ViewGroup(context, attrs) {
    var design = ClockDesign()
        private set
    var appearance = ClockDesignAppearance()
        private set
    private var aodProgress = 0f
    private var layoutTarget: ClockDesign? = null
    private var layoutFraction = 0f
    var retainAnimationBounds = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }
    private val renderProgress: Float
        get() = if (appearance.aodAnimation) aodProgress else if (appearance.dozing) 1f else 0f
    var animationsEnabled = true
        set(value) {
            if (field == value) return
            field = value
            updateImages()
            scheduleTick()
        }
    var userId: Int = context.userId
        set(value) {
            if (field == value) return
            field = value
            glass.userId = value
            weather = ""
            lastWeatherRequest = 0L
            if (registered) {
                context.contentResolver.unregisterContentObserver(observer)
                registerObservers()
            }
            applyElements()
            updateQuickLook()
            refreshTime()
        }
    var selectedId: String? = null
        set(value) { field = value; invalidate() }
    var selectionColor: Int = Color.CYAN
    val transformPivotY: Float get() = width / 2f
    var onSelect: ((String) -> Unit)? = null
    var onMove: ((String, Float, Float) -> Unit)? = null
    var onDragFinished: (() -> Unit)? = null
    var onQuickLookClick: ((QuickLookAction) -> Unit)? = null
        set(value) { field = value; refreshTime() }
    private val main = Handler(Looper.getMainLooper())
    private val glass = ClockGlassController(this)
    val hasGlassElements: Boolean
        get() = glass.hasGlassElements
    var glassBlurAmount = 1f
        set(value) {
            if (!value.isFinite()) return
            val amount = value.coerceIn(0f, 1f)
            if (field == amount) return
            field = amount
            glass.blurAmount = minOf(1f - renderProgress, amount)
        }
    var wallpaperFlags: Int
        get() = glass.wallpaperFlags
        set(value) { glass.wallpaperFlags = value }
    private val quickLook = ClockQuickLook(context) { refreshTime() }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pendingFonts = mutableSetOf<String>()
    private var weather = ""
    private var weatherPending = false
    private var lastWeatherRequest = 0L
    private var touchId: String? = null
    private var grabX = 0f
    private var grabY = 0f
    private var registered = false
    private var aggregatedVisible = false
    private var screenInteractive = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE ||
                (!screenInteractive && !appearance.dozing)) return
            refreshTime()
            val interval = if (!appearance.dozing && renderProgress == 0f && screenInteractive && animationsEnabled && design.elements.any {
                it.visible && it.needsSecondTicks
            }) 1000L else 60000L
            main.postDelayed(this, interval - System.currentTimeMillis() % interval)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == Intent.ACTION_SCREEN_ON) {
                screenInteractive = intent.action == Intent.ACTION_SCREEN_ON
                updateImages()
                updateQuickLook()
            }
            refreshTime()
            scheduleTick()
        }
    }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (uri?.authority?.endsWith("clockfonts") == true) {
                ClockFonts.clear()
                applyElements()
            } else {
                refreshTime()
                queryWeather()
            }
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        setDesign(design)
    }

    fun setDesign(value: ClockDesign) {
        layoutTarget = null
        layoutFraction = 0f
        val rebuild = childCount != value.elements.size ||
            design.elements.map { it.id to it.kind } != value.elements.map { it.id to it.kind }
        design = value
        if (rebuild) {
            glass.clear()
            removeAllViews()
            value.elements.forEach { element ->
                val child = if (element.kind == ClockElementKind.IMAGE) ClockImageView(context) else
                    (if (element.kind == ClockElementKind.QUICKLOOK) ClockQuickLookView(context) else ClockOutlineTextView(context)).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    maxLines = 4
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                child.tag = element.id
                addView(child)
            }
        }
        applyElements()
        updateQuickLook()
        refreshTime(forceAlignment = true)
        scheduleTick()
    }

    fun setLayoutTransition(target: ClockDesign?, fraction: Float) {
        if (!fraction.isFinite()) return
        val next = if (target === layoutTarget) target
            else target?.takeIf { ClockDesignTransition.canMorphLayouts(design, it) }
        val progress = ClockElementAnimation.progress(fraction)
        if (layoutTarget == next && layoutFraction == progress) return
        layoutTarget = next
        layoutFraction = progress
        applyElements(updateFonts = false)
        updateQuickLook()
    }

    private fun elementFrame(element: ClockElement, index: Int): ClockElementFrame {
        val target = layoutTarget?.elements?.getOrNull(index)
        return if (target != null && renderProgress == 0f)
            ClockElementAnimation.layoutFrame(element, target, layoutFraction, appearance.color)
        else ClockElementAnimation.frame(element, renderProgress, appearance.color)
    }

    fun setAppearance(value: ClockDesignAppearance) {
        if (appearance == value) return
        val alignmentChanged = appearance.digitAlignment != value.digitAlignment
        if (alignmentChanged) glass.clear()
        appearance = value
        applyElements()
        updateQuickLook()
        refreshTime(forceAlignment = alignmentChanged)
        scheduleTick()
    }

    fun setAodProgress(value: Float) {
        if (!value.isFinite()) return
        val progress = value.coerceIn(0f, 1f)
        if (aodProgress == progress) return
        val waking = renderProgress > 0f
        aodProgress = progress
        applyElements(updateFonts = false)
        updateQuickLook()
        if (waking && renderProgress == 0f) {
            refreshTime()
            scheduleTick()
        }
    }

    private fun updateTextEffects() {
        design.elements.forEachIndexed { index, element ->
            val view = getChildAt(index) as? ClockOutlineTextView ?: return@forEachIndexed
            val progress = when {
                !element.aodStyle.animateOutline -> if (appearance.dozing) 1f else 0f
                else -> renderProgress
            }
            view.setTextEffects(element, width / 360f, renderProgress, progress)
        }
    }

    fun setGlassPreview(background: ClockGlass?, coordinateRoot: View) {
        glass.setPreview(background, coordinateRoot)
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE &&
            (screenInteractive || appearance.dozing)) main.post(tick)
    }

    private fun updateQuickLook() {
        quickLook.update(isAttachedToWindow && aggregatedVisible && isShown && windowVisibility == VISIBLE &&
            (screenInteractive || appearance.dozing || renderProgress > 0f) && design.elements.any {
                it.kind == ClockElementKind.QUICKLOOK &&
                    ClockElementAnimation.frame(it, renderProgress).alpha > 0f
            }, userId)
    }

    private fun updateImages() {
        design.elements.forEachIndexed { index, element ->
            (getChildAt(index) as? ClockImageView)?.bind(element, userId,
                animationsEnabled && screenInteractive && !appearance.dozing && renderProgress == 0f,
                renderProgress)
        }
    }

    private fun applyElements(updateFonts: Boolean = true) {
        val progress = renderProgress
        glass.enabled = progress < 1f
        glass.aodProgress = progress
        glass.blurAmount = minOf(1f - progress, glassBlurAmount)
        design.elements.forEachIndexed { index, element ->
            val view = getChildAt(index)
            val frame = elementFrame(element, index)
            view.visibility = if (frame.visibility > 0f &&
                (element.kind != ClockElementKind.WEATHER || appearance.weather)) View.VISIBLE else View.INVISIBLE
            view.alpha = frame.alpha
            view.rotation = frame.rotation
            if (view is ClockImageView) {
                view.bind(element, userId, animationsEnabled && screenInteractive &&
                    !appearance.dozing && progress == 0f, progress)
                return@forEachIndexed
            }
            view as TextView
            view.scaleX = frame.stretchX
            view.scaleY = frame.stretchY
            view.letterSpacing = frame.letterSpacing
            view.setTextColor(frame.color)
            (view as? ClockQuickLookView)?.setAodProgress(progress)
            if (!updateFonts) return@forEachIndexed
            val face = ClockFonts.cached(userId, element.font)
            view.typeface = Typeface.create(face ?: Typeface.DEFAULT, element.weight, element.italic)
            val fontKey = "$userId/${element.font}"
            if (face == null && pendingFonts.add(fontKey)) {
                val fontUser = userId
                ClockFonts.worker.execute {
                    ClockFonts.load(context, fontUser, element.font)
                    main.post {
                        pendingFonts.remove(fontKey)
                        if (fontUser == userId) applyElements()
                    }
                }
            }
        }
        updateTextEffects()
        requestLayout()
        invalidate()
    }

    fun refreshTime() = refreshTime(forceAlignment = false)

    private fun refreshTime(forceAlignment: Boolean) {
        val now = Date()
        val locale = resources.configuration.locales[0]
        design.elements.forEachIndexed { index, element ->
            val view = getChildAt(index) as? TextView ?: return@forEachIndexed
            if (view is ClockQuickLookView) {
                val pattern = element.format.ifEmpty { DateFormat.getBestDateTimePattern(locale, "EEEMMMd") }
                val date = runCatching { SimpleDateFormat(pattern, locale).format(now) }.getOrDefault("")
                val allowSensitive = Settings.Secure.getIntForUser(context.contentResolver,
                    Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0, userId) != 0 &&
                    Settings.Secure.getIntForUser(context.contentResolver,
                        Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 1, userId) != 0
                val dozing = appearance.dozing || renderProgress > 0f
                view.bind(ClockQuickLookContent.create(quickLook.targets, date,
                    element.quickLookDateFallback, appearance.weather, allowSensitive), dozing,
                    onQuickLookClick.takeIf { !dozing && onSelect == null })
                return@forEachIndexed
            }
            val text = when (element.kind) {
                ClockElementKind.IMAGE, ClockElementKind.QUICKLOOK -> ""
                ClockElementKind.TEXT -> element.text
                ClockElementKind.WEATHER -> weather
                ClockElementKind.TIME -> ClockTime.format(element.timePart, element.hourFormat,
                    element.format, now, locale, DateFormat.is24HourFormat(context, userId))
                ClockElementKind.DATE -> {
                    val pattern = element.format.ifEmpty { DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd") }
                    runCatching { SimpleDateFormat(pattern, locale).format(now) }.getOrDefault("")
                }
            }
            if (ClockDigits.sourceText(view.text).toString() != text ||
                (forceAlignment && element.kind == ClockElementKind.TIME)) {
                view.text = if (element.kind == ClockElementKind.TIME) {
                    ClockDigits.align(text, element.digitAlignmentOverride ?: appearance.digitAlignment,
                        element.timePart)
                } else text
            }
        }
        if (System.currentTimeMillis() - lastWeatherRequest > 15 * 60000L) queryWeather()
    }

    private fun queryWeather() {
        if (weatherPending || !appearance.weather || design.elements.none { it.kind == ClockElementKind.WEATHER }) return
        weatherPending = true
        lastWeatherRequest = System.currentTimeMillis()
        val user = userId
        ClockFonts.worker.execute {
            val value = runCatching {
                val uri = ContentProvider.maybeAddUserId(WEATHER_URI, user)
                context.contentResolver.query(uri, arrayOf("temperature", "condition"), null, null, null)?.use {
                    if (it.moveToFirst()) "${it.getString(0)}° · ${it.getString(1)}" else ""
                } ?: ""
            }.getOrDefault("")
            main.post {
                weatherPending = false
                if (user == userId && weather != value) {
                    weather = value
                    refreshTime()
                }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        var bottom = 1f
        design.elements.forEachIndexed { index, element ->
            val child = getChildAt(index)
            val frame = elementFrame(element, index)
            if (child is TextView) {
                child.setTextSize(TypedValue.COMPLEX_UNIT_PX, frame.size * w / 360f)
                child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            } else {
                child.measure(MeasureSpec.makeMeasureSpec((frame.imageWidth * w / 360f).roundToInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec((frame.imageHeight * w / 360f).roundToInt(), MeasureSpec.EXACTLY))
            }
            if (element.kind != ClockElementKind.WEATHER || appearance.weather) {
                val aod = element.aodStyle
                val target = layoutTarget?.elements?.getOrNull(index)
                val stretched = child is TextView && (element.stretchX != 100 || element.stretchY != 100 ||
                    aod.stretchX != 100 || aod.stretchY != 100 ||
                    target != null && (target.stretchX != 100 || target.stretchY != 100))
                val offset = if (stretched) frame.textOffsetY(child.measuredHeight) else 0f
                val halfHeight = if (stretched) {
                    val radians = Math.toRadians(frame.rotation.toDouble())
                    (abs(sin(radians)) * child.measuredWidth * frame.stretchX +
                        abs(cos(radians)) * child.measuredHeight * frame.stretchY).toFloat() / 2f
                } else child.measuredHeight / 2f
                val extent = (frame.y * w + offset + halfHeight + w * 0.04f).coerceAtLeast(0f)
                val visibility = if (retainAnimationBounds && frame.visibility > 0f) 1f else frame.visibility
                bottom = maxOf(bottom, extent * visibility)
            }
        }
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else ceil(bottom).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        glass.beginUpdate()
        glass.userId = userId
        val progress = renderProgress
        design.elements.forEachIndexed { index, element ->
            val child = getChildAt(index)
            val frame = elementFrame(element, index)
            val targetX = frame.x * width - child.measuredWidth / 2f +
                (if (child is TextView) frame.textOffsetX(child.measuredHeight) else 0f)
            val targetY = frame.y * width - child.measuredHeight / 2f +
                (if (child is TextView) frame.textOffsetY(child.measuredHeight) else 0f)
            val x = targetX.roundToInt()
            val y = targetY.roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            child.translationX = targetX - x
            child.translationY = targetY - y
            if (child !is TextView) {
                child.setRenderEffect(if (element.shadow && progress < 1f) ClockEffects.shadow(
                    element.shadowColor, (element.shadowOpacity * (1f - progress)).roundToInt(),
                    element.shadowSoftness, width / 360f) else null)
                return@forEachIndexed
            }
            val inheritGradient = element.color == null && appearance.gradient
            child.paint.shader = if (progress < 1f && !element.glass && (element.gradient || inheritGradient)) {
                val start = ClockElementAnimation.color(
                    if (element.gradient) element.gradientStart else appearance.gradientStart, Color.WHITE, progress)
                val end = ClockElementAnimation.color(
                    if (element.gradient) element.gradientEnd else appearance.gradientEnd, Color.WHITE, progress)
                val center = if (element.gradient) child.height / 2f else height * appearance.gradientAnchor / 100f - y
                val radius = if (element.gradient) child.height / 2f else height * appearance.gradientRadius / 200f
                LinearGradient(0f, center - radius, 0f, center + radius.coerceAtLeast(1f), start, end, Shader.TileMode.CLAMP)
            } else null
            val alpha = (Color.alpha(element.shadowColor) * element.shadowOpacity / 100f * (1f - progress)).roundToInt()
            if (element.shadow && alpha > 0) {
                child.setShadowLayer((element.shadowSoftness * width / 360f).coerceAtLeast(0.01f),
                    0f, width / 180f, (element.shadowColor and 0xFFFFFF) or (alpha shl 24))
            } else child.setShadowLayer(0f, 0f, 0f, 0)
            if (element.glass) {
                glass.add(child, element.glassStyle, element.kind == ClockElementKind.DATE)
            }
        }
        glass.commit()
        updateTextEffects()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (onSelect == null) return
        val child = (0 until childCount).map { getChildAt(it) }
            .firstOrNull { it.tag == selectedId && it.visibility == View.VISIBLE } ?: return
        selectionPaint.color = selectionColor
        selectionPaint.strokeWidth = maxOf(1f, width / 200f)
        canvas.save()
        canvas.translate(child.left.toFloat(), child.top.toFloat())
        canvas.concat(child.matrix)
        canvas.drawRect(0f, 0f, child.width.toFloat(), child.height.toFloat(), selectionPaint)
        canvas.restore()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = onSelect != null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onSelect == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = (childCount - 1 downTo 0).firstOrNull { i ->
                    val child = getChildAt(i)
                    val point = floatArrayOf(event.x - child.left, event.y - child.top)
                    val inverse = Matrix()
                    child.matrix.invert(inverse)
                    inverse.mapPoints(point)
                    child.visibility == View.VISIBLE && point[0] in 0f..child.width.toFloat() &&
                        point[1] in 0f..child.height.toFloat()
                } ?: return false
                touchId = design.elements[hit].id
                selectedId = touchId
                onSelect?.invoke(touchId!!)
                val frame = elementFrame(design.elements[hit], hit)
                grabX = event.x - frame.x * width
                grabY = event.y - frame.y * width
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val element = design.elements.firstOrNull { it.id == touchId } ?: return false
                onMove?.invoke(element.id,
                    ((event.x - grabX) / width).coerceIn(ClockElement.MIN_POSITION, ClockElement.MAX_POSITION),
                    ((event.y - grabY) / width).coerceIn(ClockElement.MIN_POSITION, ClockElement.MAX_POSITION))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchId = null
                parent?.requestDisallowInterceptTouchEvent(false)
                onDragFinished?.invoke()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (registered) return
        screenInteractive = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        updateImages()
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, Context.RECEIVER_NOT_EXPORTED)
        registerObservers()
        registered = true
        updateQuickLook()
        scheduleTick()
        queryWeather()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        updateQuickLook()
        if (isVisible) scheduleTick() else main.removeCallbacks(tick)
    }

    private fun registerObservers() {
        runCatching { context.contentResolver.registerContentObserver(
            ContentProvider.maybeAddUserId(ClockFonts.uri, userId), true, observer) }
        runCatching { context.contentResolver.registerContentObserver(
            ContentProvider.maybeAddUserId(WEATHER_URI, userId), true, observer) }
        runCatching { context.contentResolver.registerContentObserver(
            ContentProvider.maybeAddUserId(Settings.System.getUriFor(Settings.System.TIME_12_24), userId),
            false, observer) }
        for (key in listOf(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
            Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS)) {
            context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer, userId)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        main.removeCallbacks(tick)
        quickLook.update(false, userId)
        if (registered) {
            context.unregisterReceiver(receiver)
            context.contentResolver.unregisterContentObserver(observer)
            registered = false
        }
    }

    companion object {
        private val WEATHER_URI = Uri.parse("content://org.omnirom.omnijaws.provider/weather")
    }
}
