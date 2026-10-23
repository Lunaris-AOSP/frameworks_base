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
import kotlin.math.ceil
import kotlin.math.roundToInt

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
    var animationsEnabled = true
        set(value) {
            if (field == value) return
            field = value
            updateImages()
        }
    var userId: Int = context.userId
        set(value) {
            if (field == value) return
            field = value
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
    private var screenInteractive = context.getSystemService(PowerManager::class.java).isInteractive
    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE ||
                (!screenInteractive && !appearance.dozing)) return
            refreshTime()
            val interval = if (!appearance.dozing && screenInteractive && animationsEnabled && design.elements.any {
                it.visible && it.kind == ClockElementKind.TIME && it.format.contains('s')
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
        glass.clear()
        val rebuild = childCount != value.elements.size ||
            design.elements.map { it.id to it.kind } != value.elements.map { it.id to it.kind }
        design = value
        if (rebuild) {
            removeAllViews()
            value.elements.forEach { element ->
                val child = if (element.kind == ClockElementKind.IMAGE) ClockImageView(context) else
                    (if (element.kind == ClockElementKind.QUICKLOOK) ClockQuickLookView(context) else TextView(context)).apply {
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
        refreshTime()
        scheduleTick()
    }

    fun setAppearance(value: ClockDesignAppearance) {
        if (appearance == value) return
        appearance = value
        applyElements()
        updateQuickLook()
        refreshTime()
        scheduleTick()
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
            (screenInteractive || appearance.dozing) && design.elements.any {
                it.kind == ClockElementKind.QUICKLOOK && it.visible && it.opacity > 0
            }, userId)
    }

    private fun updateImages() {
        design.elements.forEachIndexed { index, element ->
            (getChildAt(index) as? ClockImageView)?.bind(element, userId,
                animationsEnabled && screenInteractive && !appearance.dozing)
        }
    }

    private fun applyElements() {
        glass.clear()
        design.elements.forEachIndexed { index, element ->
            val view = getChildAt(index)
            view.visibility = if (element.visible &&
                (element.kind != ClockElementKind.WEATHER || appearance.weather)) View.VISIBLE else View.GONE
            view.alpha = element.opacity / 100f
            view.rotation = element.rotation.toFloat()
            if (view is ClockImageView) {
                view.bind(element, userId, animationsEnabled && screenInteractive && !appearance.dozing)
                return@forEachIndexed
            }
            view as TextView
            view.letterSpacing = element.letterSpacing / 100f
            view.setTextColor(if (appearance.dozing) Color.WHITE else element.color ?: appearance.color)
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
        requestLayout()
        invalidate()
    }

    fun refreshTime() {
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
                view.bind(ClockQuickLookContent.create(quickLook.targets, date,
                    element.quickLookDateFallback, appearance.weather, allowSensitive), appearance.dozing,
                    onQuickLookClick.takeIf { !appearance.dozing && onSelect == null })
                return@forEachIndexed
            }
            val text = when (element.kind) {
                ClockElementKind.IMAGE, ClockElementKind.QUICKLOOK -> ""
                ClockElementKind.TEXT -> element.text
                ClockElementKind.WEATHER -> weather
                ClockElementKind.TIME, ClockElementKind.DATE -> {
                    val pattern = element.format.ifEmpty {
                        if (element.kind == ClockElementKind.TIME) {
                            if (DateFormat.is24HourFormat(context, userId)) "HH:mm" else "h:mm"
                        } else DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
                    }
                    runCatching { SimpleDateFormat(pattern, locale).format(now) }.getOrDefault("")
                }
            }
            if (view.text.toString() != text) view.text = text
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
            if (child is TextView) {
                child.setTextSize(TypedValue.COMPLEX_UNIT_PX, element.size * w / 360f)
                child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            } else {
                child.measure(MeasureSpec.makeMeasureSpec((element.imageWidth * w / 360f).roundToInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec((element.imageHeight * w / 360f).roundToInt(), MeasureSpec.EXACTLY))
            }
            if (child.visibility != View.GONE) {
                bottom = maxOf(bottom, element.y * w + child.measuredHeight / 2f + w * 0.04f)
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
        design.elements.forEachIndexed { index, element ->
            val child = getChildAt(index)
            val x = (element.x * width - child.measuredWidth / 2f).roundToInt()
            val y = (element.y * width - child.measuredHeight / 2f).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            if (child !is TextView) {
                child.setRenderEffect(if (element.shadow && !appearance.dozing) ClockEffects.shadow(
                    element.shadowColor, element.shadowOpacity, element.shadowSoftness, width / 360f) else null)
                return@forEachIndexed
            }
            val inheritGradient = element.color == null && appearance.gradient
            child.paint.shader = if (!appearance.dozing && !element.glass && (element.gradient || inheritGradient)) {
                val start = if (element.gradient) element.gradientStart else appearance.gradientStart
                val end = if (element.gradient) element.gradientEnd else appearance.gradientEnd
                val center = if (element.gradient) child.height / 2f else height * appearance.gradientAnchor / 100f - y
                val radius = if (element.gradient) child.height / 2f else height * appearance.gradientRadius / 200f
                LinearGradient(0f, center - radius, 0f, center + radius.coerceAtLeast(1f), start, end, Shader.TileMode.CLAMP)
            } else null
            val alpha = (Color.alpha(element.shadowColor) * element.shadowOpacity / 100f).roundToInt()
            if (element.shadow && !appearance.dozing && alpha > 0) {
                child.setShadowLayer((element.shadowSoftness * width / 360f).coerceAtLeast(0.01f),
                    0f, width / 180f, (element.shadowColor and 0xFFFFFF) or (alpha shl 24))
            } else child.setShadowLayer(0f, 0f, 0f, 0)
            if (element.glass && !appearance.dozing) {
                glass.add(child, element.blurIntensity, element.blurLift)
            }
        }
        glass.commit()
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
                grabX = event.x - design.elements[hit].x * width
                grabY = event.y - design.elements[hit].y * width
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
        screenInteractive = context.getSystemService(PowerManager::class.java).isInteractive
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
