/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Color.colorToHSV
import android.graphics.Color.HSVToColor
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.os.Vibrator
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.IWallpaperZoomListener
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowManagerGlobal
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.graphics.ColorUtils
import com.android.systemui.Dependency
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.plugins.keyguard.ui.clocks.ClockAxisStyle
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockSettings
import com.android.systemui.plugins.keyguard.ui.clocks.TimeFormatKind
import com.android.systemui.shared.clocks.DEFAULT_CLOCK_ID
import com.android.systemui.shared.clocks.DefaultClockProvider
import com.android.systemui.customization.clocks.R as ClocksR
import com.android.systemui.scrim.ScrimView
import com.android.systemui.shared.R as SharedR
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.tuner.TunerService
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.lunaris.clockmaker.ClockDesign
import org.lunaris.clockmaker.ClockDesignAppearance
import org.lunaris.clockmaker.ClockDesignTransition
import org.lunaris.clockmaker.ClockDesignView
import org.lunaris.clockmaker.ClockDigitAlignment
import org.lunaris.clockmaker.ClockDigits
import org.lunaris.clockmaker.ClockElementAnimation
import org.lunaris.clockmaker.ClockElementKind
import org.lunaris.clockmaker.ClockEffects
import org.lunaris.clockmaker.ClockFonts
import org.lunaris.clockmaker.ClockGlass
import org.lunaris.clockmaker.ClockGlassController
import org.lunaris.clockmaker.ClockGlassStyle
import org.lunaris.clockmaker.ClockPreviewGeometry
import org.lunaris.clockmaker.ClockParallaxSettings
import org.lunaris.clockmaker.ClockViewTransform
import org.lunaris.clockmaker.ClockLayoutProfile
import org.lunaris.clockmaker.DynamicClockSettings
import org.lunaris.extrainfowidgets.ExtraInfoGridView
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class ClockStyle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RelativeLayout(context, attrs), TunerService.Tunable {

    var isHomeScreenClock = false

    private var isDynamicFace = false
    private var layoutProfile: ClockLayoutProfile? = null
    private var applyingLayoutProfile = false
    internal data class HomeClockMorph(val design: ClockDesign, val values: Map<String, String?>, val rotation: Float,
        val tiltX: Float, val tiltY: Float, val cameraDistance: Float)
    private var homeMorph: HomeClockMorph? = null
    private var homeProfileUserId = -1
    private var layoutAodSource: ClockStyle? = null
    private var layoutAodProgress = 0f
    private var layoutAodView: ClockDesignView? = null
    private var layoutAodSourceDesign: ClockDesign? = null
    private var layoutAodTargetDesign: ClockDesign? = null
    private var layoutAodOptions = -1
    private var layoutMorphTarget: ClockStyle? = null
    private var layoutMorphFraction = 0f
    private data class ClockLayoutGeometry(val width: Int, val x: Float, val y: Float)
    private data class ClockAodGeometry(
        val layout: ClockLayoutGeometry,
        val scale: Float,
        val cameraDistance: Float,
    )
    private var layoutAodGeometry: ClockAodGeometry? = null
    private var layoutClockGeometry: ClockLayoutGeometry? = null
    private var stockClockController: ClockController? = null
    private var dynamicClockController: DynamicClockController? = null
    private var parallaxController: ClockParallaxController? = null
    private var parallaxHost: ClockStyle? = null
    internal var onDynamicClockLayoutChanged: (() -> Unit)? = null
    private val tuningValues = mutableMapOf<String, String?>()
    private var profileGeneration = 0
    private var layoutClockInteractive = true
    private var parallaxSwitchingAllowed = true
    private var parallaxStateManaged = false
    private val updateDynamicLayout = Runnable {
        if (isHomeScreenClock) {
            val small = TunerService.parseInteger(tuningValues[DynamicClockSettings.HOME_SMALL], 0) != 0 &&
                TunerService.parseInteger(tuningValues[DynamicClockSettings.ENABLED], 0) != 0
            val profile = if (small) {
                val layouts = DynamicClockSettings.decode(tuningValues[DynamicClockSettings.LAYOUTS])
                ClockLayoutProfile.parse(layouts?.optJSONObject(DynamicClockSettings.SMALL)?.toString(), true)
                    ?: ClockLayoutProfile.fallback(tuningValues, true)
            } else ClockLayoutProfile.fallback(tuningValues, false).copy(values = tuningValues.filterKeys {
                it in CLOCK_TUNING_KEYS
            })
            val stock = !small && profile.style == 0
            val homeValues = profile.values + tuningValues.filterKeys {
                it == HOME_CLOCK_SIZE_KEY || it == HOME_CLOCK_MARGIN_TOP_KEY || it == HOME_CLOCK_MARGIN_START_KEY
            } + if (stock) tuningValues.filterKeys {
                it == Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE ||
                    it == Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK
            } else emptyMap()
            val homeProfile = profile.copy(values = homeValues,
                small = if (stock) TunerService.parseInteger(
                    tuningValues[Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK], 1) == 0 else profile.small)
            val user = ActivityManager.getCurrentUser()
            if (homeProfile != layoutProfile || homeProfileUserId != user) {
                homeProfileUserId = user
                resetHomeClockTransition()
                configureLayout(homeProfile)
            }
        } else if (!isDynamicFace) {
            val controller = dynamicClockController ?: DynamicClockController(this).also {
                dynamicClockController = it
            }
            val layouts = DynamicClockSettings.decode(tuningValues[DynamicClockSettings.LAYOUTS])
                ?: org.json.JSONObject()
            controller.configure(
                TunerService.parseInteger(tuningValues[DynamicClockSettings.ENABLED], 0) != 0,
                ClockLayoutProfile.parse(layouts.optJSONObject(DynamicClockSettings.SMALL)?.toString(), true)
                    ?: ClockLayoutProfile.fallback(tuningValues, true),
                ClockLayoutProfile.parse(layouts.optJSONObject(DynamicClockSettings.LARGE)?.toString(), false)
                    ?: ClockLayoutProfile.fallback(tuningValues, false),
            )
        }
    }

    private val tunerService: TunerService = Dependency.get(TunerService::class.java)
    private val statusBarStateController: SysuiStatusBarStateController =
        Dependency.get(StatusBarStateController::class.java) as SysuiStatusBarStateController
    private val keyguardStateController: KeyguardStateController =
        Dependency.get(KeyguardStateController::class.java)
    private val wakefulnessLifecycle = Dependency.get(WakefulnessLifecycle::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val layoutFontObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            ClockFonts.clear()
            profileGeneration++
            applyLayoutFont()
        }
    }
    private val random = java.util.Random()

    private var currentClockView: View? = null
    private val clockBoundsMatrix = Matrix()
    private var clockStub: ViewStub? = null
    private var clockContainer: ClockStyleFrameLayout? = null

    private val textClocks = ArrayList<TextClock>()
    private val styledTextViews = ArrayList<TextView>()
    private val hyperClocks = ArrayList<HyperClockView>()

    private var clockStyle = DEFAULT_STYLE
    private var clockDesign = ClockDesign()
    private var makerWeather = true
    private var digitAlignment = ClockDigitAlignment.FONT
    private var colorMode = COLOR_MODE_DEFAULT
    private var customColor = DEFAULT_CUSTOM_COLOR
    private var clockOpacity = DEFAULT_OPACITY
    private var shadowEnabled = false
    private var shadowColor = DEFAULT_SHADOW_COLOR
    private var shadowSoftness = DEFAULT_SHADOW_SOFTNESS
    private var shadowOpacity = DEFAULT_SHADOW_OPACITY
    private var clockFrameMarginTop = DEFAULT_MARGIN_TOP
    private var clockSizeScale = DEFAULT_CLOCK_SIZE
    private var clockFrameMarginStart = DEFAULT_MARGIN_START
    private var homeClockSizeScale: Int? = null
    private var homeClockMarginTop: Int? = null
    private var homeClockMarginStart: Int? = null
    private var clockTiltX = DEFAULT_TILT
    private var clockTiltY = DEFAULT_TILT
    private var parallaxEnabled = false
    private var parallaxIntensityPercent = ClockParallaxSettings.DEFAULT_INTENSITY
    private var parallaxRotationX = 0f
    private var parallaxRotationY = 0f
    private var clockRotation = DEFAULT_ROTATION

    private var gradientEnabled = false
    private var hyperBlurEnabled = false
    private var textGlassEnabled = false
    private var textGlassIntensity = ClockGlass.DEFAULT_INTENSITY
    private var textGlassLift = ClockGlass.DEFAULT_LIFT
    private var textGlassStyleValue: String? = null
    private var legacyGlassIntensity = false
    private var legacyGlassLift = false
    private var dateGlassStyle = ClockGlassStyle.DATE_DEFAULT
    private var separateDateGlass = true
    private val textGlass = ClockGlassController(this)
    private var glassTransitionAmount = 1f
    private var glassAnimator: ValueAnimator? = null
    private var gradientColorStart = DEFAULT_GRADIENT_COLOR_START
    private var gradientColorEnd = DEFAULT_GRADIENT_COLOR_END
    private var gradientAnchorY = DEFAULT_GRADIENT_ANCHOR_Y
    private var gradientRadius = DEFAULT_GRADIENT_RADIUS

    private var gradientAngleDeg = 90

    private var aodAnimEnabled = true
    private var albumArtColorEnabled = false
    private var currentAlbumColor: Int? = null

    private var lastUpdateTimeMillis = 0L
    private var isDozing = false
    private var screenInteractive = false
    private var parallaxDozeAmount = 0f
    private var callbacksRegistered = false

    private var currentShiftX = 0
    private var currentShiftY = 0

    private var pendingLayoutListener: View.OnLayoutChangeListener? = null
    private var clockSizeListener: View.OnLayoutChangeListener? = null
    private val previewGeometryListener = ViewTreeObserver.OnGlobalLayoutListener {
        updatePreviewGeometry()
    }
    private var previewGeometryKey: Pair<Int, Int>? = null
    private var previewScrimView: ScrimView? = null
    private val previewAppearanceListener = ViewTreeObserver.OnPreDrawListener {
        updatePreviewAppearance()
        true
    }
    private var previewWallpaperZoomListener: IWallpaperZoomListener? = null
    private var previewWallpaperDisplayId = 0
    private var previewWallpaperScale = 1f
    private var previewWallpaperPivotX = 0f
    private var previewWallpaperPivotY = 0f
    private var previewHasWallpaperZoom = false

    private var clockWobbleOnChargeEnabled = true
    private var wobbleAnimator: ObjectAnimator? = null
    private var wobbleGlowAnimator: android.animation.ValueAnimator? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = powerManager?.isInteractive == true
                    updateClockParallax()
                    forceTimeUpdate()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    updateClockParallax()
                }
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_LOCALE_CHANGED -> forceTimeUpdate()
                Intent.ACTION_TIME_TICK,
                DOZE_PULSE_ACTION -> onTimeChanged()
                Intent.ACTION_POWER_CONNECTED -> {
                    if (clockWobbleOnChargeEnabled && !isDozing && clockContainer?.visibility == View.VISIBLE &&
                        (!isDynamicFace || layoutClockInteractive) &&
                        (currentClockView !is ClockDesignView || clockAodProgress() == 0f)) {
                        startWobbleAnimation()
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> cancelWobbleAnimation()
            }
        }
    }

    private val aodTickRunnable = object : Runnable {
        override fun run() {
            if (!isDozing || clockStyle == 0) return
            forceTimeUpdate()
            val now = System.currentTimeMillis()
            val nextMinute = ((now / AOD_UPDATE_INTERVAL_MILLIS) + 1) * AOD_UPDATE_INTERVAL_MILLIS
            handler.postDelayed(this, nextMinute - now)
        }
    }

    private val burnInProtectionRunnable = object : Runnable {
        override fun run() {
            if (!isDozing) return
            currentShiftX = random.nextInt(BURN_IN_PROTECTION_MAX_SHIFT * 2) - BURN_IN_PROTECTION_MAX_SHIFT
            currentShiftY = random.nextInt(BURN_IN_PROTECTION_MAX_SHIFT * 2) - BURN_IN_PROTECTION_MAX_SHIFT
            currentClockView?.apply {
                val progress = if (this is ClockDesignView) clockAodProgress() else 1f
                translationX = currentShiftX * progress
                translationY = currentShiftY * progress
            }
            invalidate()
            handler.postDelayed(this, BURN_IN_PROTECTION_INTERVAL)
        }
    }

    private val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) {
            dynamicClockController?.onHostVisibilityChanged()
            updateClockParallax()
            if (newState == StatusBarState.KEYGUARD) handler.post { updatePreviewGeometry() }
        }

        override fun onDozingChanged(dozing: Boolean) {
            if (isHomeScreenClock) return
            if (isDozing == dozing) return
            applyDozing(dozing)
        }

        override fun onDozeAmountChanged(linear: Float, eased: Float) {
            if (isHomeScreenClock || isDynamicFace) return
            parallaxDozeAmount = maxOf(linear, eased).coerceIn(0f, 1f)
            updateClockParallax()
            dynamicClockController?.onDozeAmountChanged(eased)
            updateGlassAmount()
            if (currentClockView !is ClockDesignView) return
            (currentClockView as ClockDesignView).setAodProgress(eased)
            applyClockAlpha()
            applyClockShadow()
            applyClockScale()
        }
    }

    private val parallaxKeyguardCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardShowingChanged() = updateClockParallax()
        override fun onPrimaryBouncerShowingChanged() = updateClockParallax()
        override fun onKeyguardFadingAwayChanged() = updateClockParallax()
        override fun onKeyguardGoingAwayChanged() = updateClockParallax()
    }

    private val parallaxWakefulnessObserver = object : WakefulnessLifecycle.Observer {
        override fun onStartedGoingToSleep() {
            screenInteractive = false
            updateClockParallax()
        }

        override fun onFinishedWakingUp() {
            screenInteractive = powerManager?.isInteractive == true
            updateClockParallax()
        }
    }

    private val mediaDataListener = object : MediaSessionManager.MediaDataListener {
        override fun onPlaybackStateChanged(state: Int) {
            handler.post {
                if (state != PlaybackState.STATE_PLAYING) {
                    currentAlbumColor = null
                    if (albumArtColorEnabled) applyClockColors()
                }
            }
        }

        override fun onMediaColorsChanged(color: Int) {
            handler.post {
                currentAlbumColor = color
                if (albumArtColorEnabled) applyClockColors()
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        clockStub = findViewById(R.id.clock_view_stub)
        clockContainer = findViewById(R.id.clock_frame)
        updateClockView()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = if (isDynamicFace && resources.getBoolean(R.bool.config_use_split_notification_shade))
            MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(widthMeasureSpec) / 2).coerceAtLeast(1),
                MeasureSpec.getMode(widthMeasureSpec)) else widthMeasureSpec
        val height = if (isDynamicFace || dynamicClockController?.enabled == true)
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) else heightMeasureSpec
        super.onMeasure(width, height)
        val frame = clockContainer
        val top = (frame?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        dynamicClockController?.measuredHeight(top + (frame?.measuredHeight ?: 0))?.let {
            setMeasuredDimension(measuredWidthAndState,
                resolveSizeAndState(it, heightMeasureSpec, measuredState shl MEASURED_HEIGHT_STATE_SHIFT))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (isDynamicFace && layoutAodSource != null && currentLayoutGeometry() != layoutClockGeometry) {
            applyClockScale()
        }
    }

    internal val baseClockStyle: Int get() = clockStyle
    internal val isClockDozing: Boolean get() = isDozing
    internal val isClockSwitchingVisible: Boolean
        get() = isShown && windowVisibility == View.VISIBLE && statusBarStateController.state == StatusBarState.KEYGUARD
    internal val isDynamicClockTransitionRunning: Boolean
        get() = dynamicClockController?.isAnimating == true
    internal val isDynamicClockOnLockscreen: Boolean
        get() = dynamicClockController?.isOnLockscreen == true
    internal val isDynamicClockEnabled: Boolean
        get() = dynamicClockController?.enabled == true
    internal val isDynamicClockLayoutActive: Boolean
        get() = dynamicClockController?.isLayoutActive == true
    internal val layoutClockCenterY: Float
        get() = (clockContainer?.top ?: 0) + (currentClockView?.height ?: 0) / 2f
    internal val layoutClockHeight: Int
        get() = (measuredHeight + if (layoutAodSource != null && clockAodProgress() > 0f)
            currentClockView?.translationY ?: 0f else 0f).roundToInt()
            .coerceAtLeast(0)
    internal val canRenderParallax: Boolean
        get() = parallaxEnabled && !isHomeScreenClock && parallaxIntensityPercent > 0 &&
            clockOpacity > 0 && currentClockView != null
    internal val parallaxIntensity: Float
        get() = if (parallaxEnabled) parallaxIntensityPercent / 100f else 0f
    internal val isClockParallaxSuspended: Boolean
        get() = !isAttachedToWindow || !screenInteractive || isDozing || parallaxDozeAmount > 0f
    internal val canSenseClockParallax: Boolean
        get() = !isHomeScreenClock && !isDynamicFace && !isClockParallaxSuspended &&
            parallaxSwitchingAllowed && isClockSwitchingVisible && alpha > 0f &&
            (parallaxStateManaged || keyguardStateController.isShowing &&
                !keyguardStateController.isOccluded && !keyguardStateController.isPrimaryBouncerShowing &&
                !keyguardStateController.isKeyguardGoingAway && !keyguardStateController.isKeyguardFadingAway)

    internal fun configureClockParallax(small: ClockStyle?, large: ClockStyle?,
        fraction: Float, transitioning: Boolean) {
        parallaxController?.configure(small, large, fraction, transitioning)
    }

    private fun updateClockParallax() {
        if (isDynamicFace) parallaxHost?.updateClockParallax()
        else parallaxController?.updateState()
    }

    internal fun setParallaxRotation(rotationX: Float, rotationY: Float) {
        if (parallaxRotationX == rotationX && parallaxRotationY == rotationY) return
        parallaxRotationX = rotationX
        parallaxRotationY = rotationY
        if (parallaxEnabled && !isHomeScreenClock && isShown) {
            currentClockView?.rotationX = rotationX
            currentClockView?.rotationY = rotationY
        }
    }

    fun setNotificationPresence(present: Boolean) {
        dynamicClockController?.setNotificationPresence(present)
    }

    fun setClockSwitchingAllowed(allowed: Boolean) {
        parallaxStateManaged = true
        parallaxSwitchingAllowed = allowed
        dynamicClockController?.setSwitchingAllowed(allowed)
        updateClockParallax()
    }

    internal fun applyNativeClockConstraints(constraints: ConstraintSet, clock: ClockController?) {
        dynamicClockController?.applyNativeClockConstraints(constraints, clock)
    }

    internal fun createLayoutClock(profile: ClockLayoutProfile): ClockStyle {
        val clock = LayoutInflater.from(context).inflate(R.layout.keyguard_clock_style, this, false) as ClockStyle
        clock.id = View.generateViewId()
        clock.isDynamicFace = true
        clock.parallaxHost = this
        clock.layoutProfile = profile
        clock.visibility = View.INVISIBLE
        clock.clipChildren = false
        clock.clipToPadding = false
        clock.textGlass.wallpaperFlags = WallpaperManager.FLAG_LOCK
        clock.configureLayout(profile)
        clock.setGlassTransitionAmount(glassTransitionAmount)
        addView(clock, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return clock
    }

    internal fun canMorphLayoutWith(other: ClockStyle): Boolean {
        if (currentClockView !is ClockDesignView || other.currentClockView !is ClockDesignView) return false
        val own = layoutProfile ?: return false
        val target = other.layoutProfile ?: return false
        return own.values.filterKeys { it != ClockDesign.SETTING && it != CLOCK_SIZE_KEY } ==
            target.values.filterKeys { it != ClockDesign.SETTING && it != CLOCK_SIZE_KEY } &&
            ClockDesignTransition.canMorphLayouts(clockDesign, other.clockDesign)
    }

    internal fun setLayoutClockTransition(target: ClockStyle?, fraction: Float): Boolean {
        val view = currentClockView as? ClockDesignView ?: return false
        if (target == null) {
            if (layoutMorphTarget != null) {
                layoutMorphTarget = null
                layoutMorphFraction = 0f
                view.setLayoutTransition(null, 0f)
                applyClockScale()
            }
            return false
        }
        if (!isDynamicFace || target.currentClockView !is ClockDesignView) return false
        if (layoutMorphTarget == null) {
            resetLayoutClockAodTransition(view)
        }
        layoutMorphTarget = target
        layoutMorphFraction = ClockElementAnimation.progress(fraction)
        view.setLayoutTransition(target.clockDesign, fraction)
        applyClockScale()
        return true
    }

    internal fun captureHomeClockMorph(): HomeClockMorph? {
        if (isDynamicClockTransitionRunning || isDozing || clockAodProgress() > 0f) return null
        val renderer = dynamicClockController?.activeClock ?: this
        val sourceView = renderer.currentClockView as? ClockDesignView ?: return null
        if (sourceView.width <= 0) return null
        return HomeClockMorph(renderer.clockDesign, (renderer.layoutProfile?.values ?: renderer.tuningValues).toMap(),
            sourceView.rotation, sourceView.rotationX, sourceView.rotationY, sourceView.cameraDistance / sourceView.width)
    }

    internal fun prepareHomeClockTransition(source: HomeClockMorph): Boolean {
        if (!isHomeScreenClock || currentClockView !is ClockDesignView ||
            !ClockDesignTransition.canMorphLayouts(source.design, clockDesign) ||
            source.design.elements.zip(clockDesign.elements).none { (from, to) ->
                from.stretchX != to.stretchX || from.stretchY != to.stretchY ||
                    from.stretchFromTop != to.stretchFromTop
            }) return false
        val placementKeys = setOf(CLOCK_DESIGN_KEY, CLOCK_SIZE_KEY, CLOCK_FRAME_MARGIN_TOP_KEY,
            CLOCK_FRAME_MARGIN_START_KEY, HOME_CLOCK_SIZE_KEY, HOME_CLOCK_MARGIN_TOP_KEY,
            HOME_CLOCK_MARGIN_START_KEY, CLOCK_ROTATION_KEY, CLOCK_TILT_X_KEY, CLOCK_TILT_Y_KEY)
        val to = layoutProfile?.values ?: tuningValues
        if (CLOCK_TUNING_KEYS.any { it !in placementKeys && source.values[it] != to[it] }) return false
        homeMorph = source
        return true
    }

    internal fun setHomeClockTransition(fraction: Float) {
        val morph = homeMorph ?: return
        val view = currentClockView as? ClockDesignView ?: return
        val progress = 1f - ClockElementAnimation.progress(fraction)
        view.setLayoutTransition(morph.design, progress)
        if (isLayoutRequested && width > 0) {
            measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            layout(left, top, left + measuredWidth, top + measuredHeight)
        }
        applyClockTransform()
        view.rotation = clockRotation + ((morph.rotation - clockRotation + 540f) % 360f - 180f) * progress
        view.rotationX = ClockElementAnimation.lerp(clockTiltX.toFloat(), morph.tiltX, progress)
        view.rotationY = ClockElementAnimation.lerp(clockTiltY.toFloat(), morph.tiltY, progress)
        view.cameraDistance = ClockElementAnimation.lerp(view.cameraDistance,
            morph.cameraDistance * view.width, progress)
    }

    internal fun resetHomeClockTransition() {
        if (homeMorph == null) return
        homeMorph = null
        (currentClockView as? ClockDesignView)?.setLayoutTransition(null, 0f)
        applyClockTransform()
    }

    private fun resetLayoutClockAodTransition(view: ClockDesignView) {
        layoutAodSource = null
        layoutAodProgress = 0f
        layoutAodView = null
        layoutAodGeometry = null
        layoutClockGeometry = null
        isDozing = false
        view.retainAnimationBounds = false
        view.setDesign(clockDesign)
        updateClockAppearance()
    }

    internal fun setLayoutClockAodProgress(source: ClockStyle, amount: Float): Boolean {
        val view = currentClockView as? ClockDesignView ?: return false
        val sourceView = source.currentClockView as? ClockDesignView
        if (!isDynamicFace || sourceView == null) {
            if (layoutAodSource != null) {
                resetLayoutClockAodTransition(view)
            }
            return false
        }
        val options = (if (makerWeather) 1 else 0) or
            (if (source.makerWeather) 2 else 0) or (if (source.aodAnimEnabled) 4 else 0) or
            (source.digitAlignment.ordinal shl 3) or (digitAlignment.ordinal shl 5)
        val changed = layoutAodSource !== source || layoutAodView !== view ||
            layoutAodSourceDesign != source.clockDesign || layoutAodTargetDesign != clockDesign ||
            layoutAodOptions != options
        val dozingChanged = isDozing != source.isDozing
        val progressChanged = layoutAodProgress != amount
        val sourceLayout = source.currentLayoutGeometry()
        var sourceGeometryChanged = false
        if (sourceLayout != null &&
            (changed || layoutAodGeometry == null || source.clockAodProgress() == 1f || amount == 1f)) {
            val geometry = ClockAodGeometry(
                sourceLayout.copy(x = sourceLayout.x + source.currentShiftX,
                    y = sourceLayout.y + source.currentShiftY),
                ClockViewTransform.renderScale(source.getScaleFactor(), true, source.aodAnimEnabled),
                sourceView.cameraDistance,
            )
            sourceGeometryChanged = layoutAodGeometry != geometry
            layoutAodGeometry = geometry
        }
        val geometryChanged = currentLayoutGeometry() != layoutClockGeometry
        layoutAodSource = source
        layoutAodProgress = amount
        isDozing = source.isDozing
        if (changed) {
            layoutAodView = view
            layoutAodSourceDesign = source.clockDesign
            layoutAodTargetDesign = clockDesign
            layoutAodOptions = options
            view.retainAnimationBounds = true
            val aod = source.clockDesign.copy(elements = source.clockDesign.elements.map {
                if (it.kind == ClockElementKind.WEATHER && !source.makerWeather)
                    it.configureAod(it.aodStyle.copy(visible = false)) else it
            })
            val target = clockDesign.copy(elements = clockDesign.elements.map {
                if (it.kind == ClockElementKind.WEATHER && !makerWeather) it.copy(visible = false) else it
            })
            view.setDesign(ClockDesignTransition.fromAod(aod, target, source.digitAlignment, digitAlignment))
        }
        if (changed || dozingChanged) applyClockColors()
        if (changed || dozingChanged || progressChanged || sourceGeometryChanged || geometryChanged || amount > 0f) {
            applyClockAlpha()
            applyClockShadow()
            applyClockScale()
        }
        return layoutAodGeometry != null
    }

    private fun currentLayoutGeometry(): ClockLayoutGeometry? {
        val view = currentClockView ?: return null
        val frame = clockContainer ?: return null
        if (!view.isLaidOut || !frame.isLaidOut || view.width <= 0) return null
        return ClockLayoutGeometry(
            view.width,
            (if (isDynamicFace) left else 0) + frame.left + frame.translationX + view.left + view.width / 2f,
            (if (isDynamicFace) top else 0) + frame.top + frame.translationY + view.top +
                ((view as? ClockDesignView)?.transformPivotY ?: view.height / 2f),
        )
    }

    private fun configureLayout(profile: ClockLayoutProfile) {
        layoutProfile = profile
        profileGeneration++
        applyingLayoutProfile = true
        try {
            CLOCK_TUNING_KEYS.filter { it != CLOCK_STYLE_KEY }.forEach {
                onTuningChanged(it, profile.values[it])
            }
            onTuningChanged(CLOCK_STYLE_KEY, profile.style.toString())
        } finally {
            applyingLayoutProfile = false
        }
    }

    internal fun setBaselineClockAlpha(value: Float) {
        clockContainer?.apply {
            alpha = value
            visibility = if (value > 0f) View.VISIBLE else View.INVISIBLE
            importantForAccessibility = if (value > 0f) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        (currentClockView as? ClockDesignView)?.animationsEnabled =
            value > 0f && !isDozing && clockAodProgress() == 0f && clockOpacity > 0
    }

    internal fun setLayoutClockInteractive(interactive: Boolean) {
        if (layoutClockInteractive == interactive) return
        layoutClockInteractive = interactive
        importantForAccessibility = if (interactive) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isEnabled = interactive
        (currentClockView as? ClockDesignView)?.animationsEnabled = interactive && clockOpacity > 0
        if (!interactive && (wobbleAnimator != null || wobbleGlowAnimator != null)) cancelWobbleAnimation()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        if (isDynamicFace && !layoutClockInteractive) true else super.onInterceptTouchEvent(event)

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        dynamicClockController?.onHostVisibilityChanged()
        updateClockParallax()
        if (visibility == View.VISIBLE && parallaxEnabled) applyClockTransform()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        dynamicClockController?.onHostVisibilityChanged()
        updateClockParallax()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isDynamicFace || isHomeScreenClock) {
            profileGeneration++
            layoutAodGeometry = null
            layoutClockGeometry = null
            updateClockView()
        } else if (dynamicClockController?.enabled == true) {
            dynamicClockController?.onHostVisibilityChanged()
            onDynamicClockLayoutChanged?.invoke()
        }
    }

    internal fun updateClockVisibility() {
        visibility = if (clockStyle != 0 || isDynamicFace || isHomeScreenClock && layoutProfile != null ||
            isDynamicClockLayoutActive)
            View.VISIBLE else View.GONE
    }

    private fun updatePreviewGeometry() {
        if (!canUpdatePreview()) return
        val renderer = dynamicClockController?.activeClock ?: this
        val view = renderer.currentClockView ?: return
        val frame = renderer.clockContainer ?: return
        val params = frame.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (view.width <= 0 || height <= 0) return
        val matrix = Matrix()
        if (!ClockViewTransform.toGlobal(this, matrix, excludeAnimationOf = this,
                excludeTransformOf = parent as? View)) return
        val origin = floatArrayOf((frame.left + view.left).toFloat(),
            (frame.top - params.topMargin + view.top).toFloat())
        if (renderer !== this) {
            val faceMatrix = Matrix()
            if (!ClockViewTransform.toAncestor(renderer, this, faceMatrix,
                    excludeTransformOf = renderer)) return
            faceMatrix.mapPoints(origin)
        }
        matrix.mapPoints(origin)
        val bounds = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
            ?: return
        if (bounds.width() <= 0 || bounds.height() <= 0 || view.width > bounds.width()) return
        val displayId = display?.displayId ?: return
        val user = ActivityManager.getCurrentUser()
        val density = resources.displayMetrics.density
        val infoGridInset = (12f * density).toInt()
        var infoTopOffset = 12 + infoGridInset
        var appWidgetBottom = bounds.height() - 2 * (96f * density).toInt()
        var appWidgetMaxHeight = (bounds.height() * 0.45f).toInt()
        var contentLeft = 0
        var contentTop = 0
        var contentWidth = bounds.width()
        var contentHeight = bounds.height()
        var appWidgetCenterX = bounds.width() / 2f
        var appWidgetContentOffset = 0
        var appWidgetTrailingOffset = 0
        (parent as? ViewGroup)?.let { root ->
            val info = root.findViewById<View>(R.id.keyguard_info_widgets)?.takeIf {
                it.parent === root && it.visibility == View.VISIBLE && it.height > 0
            }
            fun contentBottom(ids: List<Int>): Int = ids.mapNotNull { root.findViewById<View>(it) }
                .filter { it.parent === root && it.visibility == View.VISIBLE }
                .maxOfOrNull { it.bottom }?.coerceAtLeast(bottom) ?: bottom
            val weatherIds = listOf(R.id.keyguard_weather, R.id.default_weather_image,
                R.id.default_weather_text)
            val infoGrid = (info as? ViewGroup)?.let { container ->
                (0 until container.childCount).map { container.getChildAt(it) }
                    .firstOrNull { it is ExtraInfoGridView && it.visibility == View.VISIBLE }
            }
            infoTopOffset = (info?.top ?: (contentBottom(weatherIds) + 12)) - bottom +
                (infoGrid?.top ?: infoGridInset)
            val auxiliaryBottom = contentBottom(weatherIds + listOf(R.id.keyguard_slice_view,
                SharedR.id.bc_smartspace_view, SharedR.id.date_smartspace_view,
                SharedR.id.date_smartspace_view_large))
            appWidgetContentOffset = auxiliaryBottom - bottom
            root.findViewById<View>(R.id.keyguard_app_widget_anchor)?.let {
                appWidgetTrailingOffset = (it.bottom - maxOf(auxiliaryBottom, info?.bottom ?: bottom))
                    .coerceAtLeast(0)
            }
            val rootOrigin = floatArrayOf(0f, 0f)
            if (ClockViewTransform.toGlobal(root, matrix, excludeTransformOf = root)) {
                matrix.mapPoints(rootOrigin)
                contentLeft = rootOrigin[0].roundToInt()
                contentTop = rootOrigin[1].roundToInt()
                contentWidth = root.width.coerceAtLeast(1)
                contentHeight = root.height.coerceAtLeast(1)
                val viewport = root.findViewById<View>(R.id.keyguard_app_widgets)
                    ?.takeIf { it.width > 0 }
                appWidgetCenterX = rootOrigin[0] + if (viewport != null)
                    viewport.left + viewport.width / 2f else root.width / 2f
                val reserved = (96f * density).toInt()
                val limit = listOf(R.id.device_entry_icon_view, R.id.keyguard_indication_area,
                    R.id.start_button, R.id.end_button, R.id.ambient_indication_container)
                    .mapNotNull { root.findViewById<View>(it) }
                    .filter { it.visibility != View.GONE && it.top > 0 }
                    .minOfOrNull { it.top } ?: (root.height - reserved)
                appWidgetBottom = rootOrigin[1].roundToInt() + limit - reserved
                appWidgetMaxHeight = (root.height * 0.45f).toInt()
            }
        }
        val key = displayId to user
        previewGeometryKey = key
        previewGeometries[key] = ClockPreviewGeometry(
            displayId, user, bounds.width(), bounds.height(), origin[0].roundToInt(),
            origin[1].roundToInt(), view.width,
            resources.getDimensionPixelSize(R.dimen.status_bar_height) * 1.25f,
            density, infoTopOffset, appWidgetBottom, appWidgetMaxHeight,
            contentLeft, contentTop, contentWidth, contentHeight, appWidgetCenterX,
            appWidgetContentOffset, appWidgetTrailingOffset,
            previewWallpaperScale,
            if (previewHasWallpaperZoom) previewWallpaperPivotX else bounds.exactCenterX(),
            if (previewHasWallpaperZoom) previewWallpaperPivotY else bounds.exactCenterY(),
            previewGeometries[key]?.wallpaperScrimColor ?: 0,
        )
        updatePreviewAppearance()
    }

    private fun canUpdatePreview() = isAttachedToWindow && isShown && !isHomeScreenClock &&
        windowVisibility == View.VISIBLE && !isDozing &&
        (clockStyle != 0 || dynamicClockController?.enabled == true) &&
        !isDynamicClockTransitionRunning &&
        statusBarStateController.state == StatusBarState.KEYGUARD &&
        !keyguardStateController.isOccluded && !keyguardStateController.isPrimaryBouncerShowing &&
        !keyguardStateController.isKeyguardGoingAway

    private fun updatePreviewAppearance() {
        if (!canUpdatePreview()) return
        val key = previewGeometryKey ?: return
        val geometry = previewGeometries[key] ?: return
        val scrim = previewScrimView ?: rootView.findViewById<ScrimView>(R.id.scrim_behind)
            ?.also { previewScrimView = it } ?: return
        val tint = scrim.tint
        val color = if (scrim.shouldBlendWithMainColor()) {
            ColorUtils.blendARGB(scrim.colors.mainColor, tint, Color.alpha(tint) / 255f)
        } else tint
        val scrimColor = (color and 0xFFFFFF) or
            ((scrim.viewAlpha.coerceIn(0f, 1f) * 255f).toInt() shl 24)
        if (geometry.wallpaperScrimColor != scrimColor) {
            previewGeometries[key] = geometry.copy(wallpaperScrimColor = scrimColor)
        }
    }

    private fun updatePreviewWallpaperZoom() {
        if (keyguardStateController.isKeyguardGoingAway) return
        val key = previewWallpaperDisplayId to ActivityManager.getCurrentUser()
        val geometry = previewGeometries[key] ?: return
        val bounds = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
            ?: return
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val updated = geometry.copy(wallpaperScale = previewWallpaperScale,
            wallpaperPivotX = previewWallpaperPivotX * geometry.screenWidth / bounds.width(),
            wallpaperPivotY = previewWallpaperPivotY * geometry.screenHeight / bounds.height())
        if (geometry == updated) return
        previewGeometries[key] = updated
        context.contentResolver.notifyChange(ClockPreviewGeometry.URI, null, 0, key.second)
    }

    private fun observePreviewWallpaperZoom() {
        if (previewWallpaperZoomListener != null) return
        val displayId = display?.displayId ?: return
        val windowManager = runCatching {
            WindowManagerGlobal.getWindowManagerService()
        }.getOrNull() ?: return
        previewWallpaperDisplayId = displayId
        val listener = object : IWallpaperZoomListener.Stub() {
            override fun onWallpaperZoomChanged(scale: Float, pivotX: Float, pivotY: Float) {
                if (!scale.isFinite() || scale <= 0f || !pivotX.isFinite() || !pivotY.isFinite()) return
                handler.post {
                    if (previewWallpaperZoomListener !== this) return@post
                    previewWallpaperScale = scale
                    previewWallpaperPivotX = pivotX
                    previewWallpaperPivotY = pivotY
                    previewHasWallpaperZoom = true
                    updatePreviewWallpaperZoom()
                    updatePreviewGeometry()
                }
            }
        }
        previewWallpaperZoomListener = listener
        runCatching {
            windowManager.registerWallpaperZoomListener(listener, displayId)
        }.onFailure { previewWallpaperZoomListener = null }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (callbacksRegistered) return
        if (!isHomeScreenClock && !isDynamicFace) {
            lockscreenClocks.add(this)
            viewTreeObserver.addOnGlobalLayoutListener(previewGeometryListener)
            viewTreeObserver.addOnPreDrawListener(previewAppearanceListener)
            observePreviewWallpaperZoom()
            HomeClockProvider.observeKeyguard()
        }
        isDozing = !isHomeScreenClock && !isDynamicFace && statusBarStateController.isDozing
        screenInteractive = powerManager?.isInteractive == true
        parallaxDozeAmount = if (isHomeScreenClock || isDynamicFace) 0f
            else maxOf(statusBarStateController.dozeAmount, statusBarStateController.interpolatedDozeAmount)
                .coerceIn(0f, 1f)
        if (!isHomeScreenClock && !isDynamicFace) {
            parallaxController = ClockParallaxController(this)
        }
        textGlass.wallpaperFlags = if (isHomeScreenClock) WallpaperManager.FLAG_SYSTEM
            else WallpaperManager.FLAG_LOCK

        if (!isDynamicFace) {
            if (!isHomeScreenClock) {
                val controller = dynamicClockController ?: DynamicClockController(this).also {
                    dynamicClockController = it
                }
                controller.attach(statusBarStateController.interpolatedDozeAmount)
            }
            val keys = CLOCK_TUNING_KEYS + arrayOf(DynamicClockSettings.ENABLED, DynamicClockSettings.LAYOUTS) +
                if (isHomeScreenClock) arrayOf(DynamicClockSettings.HOME_SMALL,
                    Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE, Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK)
                else emptyArray()
            tunerService.addTunable(this, *keys)
            handler.removeCallbacks(updateDynamicLayout)
            updateDynamicLayout.run()
            statusBarStateController.addCallback(statusBarStateListener)
            if (!isHomeScreenClock) {
                keyguardStateController.addCallback(parallaxKeyguardCallback)
                wakefulnessLifecycle.addObserver(parallaxWakefulnessObserver)
            }
        }
        if (isDynamicFace || isHomeScreenClock) {
            context.contentResolver.registerContentObserver(ClockFonts.uri, true, layoutFontObserver, UserHandle.USER_ALL)
            applyLayoutFont()
        }
        if (albumArtColorEnabled) {
            MediaSessionManager.get().addListener(mediaDataListener)
        }

        applyClockAlpha()
        applyClockColors()
        applyClockShadow()
        if (isDozing) {
            startBurnInProtection()
            startAodTick()
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(DOZE_PULSE_ACTION)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        callbacksRegistered = true
        updateClockParallax()
    }

    override fun onDetachedFromWindow() {
        resetHomeClockTransition()
        setGlassTransitionAmount(1f)
        super.onDetachedFromWindow()
        parallaxController?.destroy()
        parallaxController = null
        setParallaxRotation(0f, 0f)
        lockscreenClocks.remove(this)
        viewTreeObserver.removeOnGlobalLayoutListener(previewGeometryListener)
        viewTreeObserver.removeOnPreDrawListener(previewAppearanceListener)
        previewWallpaperZoomListener?.let { listener ->
            previewWallpaperZoomListener = null
            runCatching {
                WindowManagerGlobal.getWindowManagerService()
                    ?.unregisterWallpaperZoomListener(listener, previewWallpaperDisplayId)
            }
        }
        previewScrimView = null
        previewGeometryKey = null
        previewHasWallpaperZoom = false
        dynamicClockController?.detach()
        handler.removeCallbacks(updateDynamicLayout)
        profileGeneration++
        if (!callbacksRegistered) return

        if (isDynamicFace || isHomeScreenClock) {
            context.contentResolver.unregisterContentObserver(layoutFontObserver)
        }

        statusBarStateController.removeCallback(statusBarStateListener)
        if (!isHomeScreenClock && !isDynamicFace) {
            keyguardStateController.removeCallback(parallaxKeyguardCallback)
            wakefulnessLifecycle.removeObserver(parallaxWakefulnessObserver)
        }
        tunerService.removeTunable(this)
        runCatching { MediaSessionManager.get().removeListener(mediaDataListener) }
        handler.removeCallbacks(burnInProtectionRunnable)
        handler.removeCallbacks(aodTickRunnable)
        currentClockView?.animate()?.cancel()
        cancelWobbleAnimation()
        removePendingLayoutListener()
        runCatching { context.unregisterReceiver(screenReceiver) }
        callbacksRegistered = false
    }

    override fun onTuningChanged(key: String?, newValue: String?) {
        if (key != null && !applyingLayoutProfile) tuningValues[key] = newValue
        if (isHomeScreenClock && !applyingLayoutProfile) {
            handler.removeCallbacks(updateDynamicLayout)
            handler.post(updateDynamicLayout)
            return
        }
        when (key) {
            CLOCK_DESIGN_KEY -> {
                clockDesign = ClockDesign.parse(newValue) ?: ClockDesign()
                (currentClockView as? ClockDesignView)?.let {
                    it.userId = ActivityManager.getCurrentUser()
                    it.setDesign(clockDesign)
                    applyClockColors()
                }
            }
            CLOCK_MAKER_WEATHER_KEY -> {
                makerWeather = TunerService.parseInteger(newValue, 1) != 0
                applyClockColors()
            }
            ClockDigits.SETTING -> {
                digitAlignment = ClockDigitAlignment.parse(newValue)
                textClocks.filterIsInstance<AlignedTextClock>().forEach { it.digitAlignment = digitAlignment }
                textGlass.clear()
                applyClockColors()
            }
            CLOCK_STYLE_KEY -> {
                clockStyle = TunerService.parseInteger(newValue, DEFAULT_STYLE)
                if (clockStyle != 0 && !isDynamicFace && !isHomeScreenClock) {
                    Settings.Secure.putIntForUser(
                        context.contentResolver,
                        Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                        0,
                        UserHandle.USER_CURRENT,
                    )
                }
                updateClockView()
            }
            CLOCK_COLOR_MODE_KEY -> {
                colorMode = newValue ?: COLOR_MODE_DEFAULT
                applyClockColors()
            }
            CLOCK_CUSTOM_COLOR_KEY -> {
                customColor = TunerService.parseInteger(newValue, DEFAULT_CUSTOM_COLOR)
                if (colorMode == COLOR_MODE_CUSTOM) applyClockColors()
            }
            CLOCK_TEXT_OPACITY_KEY -> {
                clockOpacity = TunerService.parseInteger(newValue, DEFAULT_OPACITY)
                    .coerceIn(0, 100)
                applyClockAlpha()
                updateClockParallax()
            }
            CLOCK_SHADOW_ENABLED_KEY -> {
                shadowEnabled = TunerService.parseInteger(newValue, 0) != 0
                applyClockShadow()
            }
            CLOCK_SHADOW_COLOR_KEY -> {
                shadowColor = TunerService.parseInteger(newValue, DEFAULT_SHADOW_COLOR)
                applyClockShadow()
            }
            CLOCK_SHADOW_SOFTNESS_KEY -> {
                shadowSoftness = TunerService.parseInteger(newValue, DEFAULT_SHADOW_SOFTNESS)
                    .coerceIn(0, 30)
                applyClockShadow()
            }
            CLOCK_SHADOW_OPACITY_KEY -> {
                shadowOpacity = TunerService.parseInteger(newValue, DEFAULT_SHADOW_OPACITY)
                    .coerceIn(0, 100)
                applyClockShadow()
            }
            CLOCK_FRAME_MARGIN_TOP_KEY -> {
                clockFrameMarginTop = TunerService.parseInteger(newValue, DEFAULT_MARGIN_TOP)
                    .coerceIn(0, 200)
                updateClockFrameMargin()
            }
            CLOCK_SIZE_KEY -> {
                clockSizeScale = TunerService.parseInteger(newValue, DEFAULT_CLOCK_SIZE)
                    .coerceIn(MIN_CLOCK_SIZE, MAX_CLOCK_SIZE)
                applyClockScale()
            }
            CLOCK_AOD_ANIM_KEY -> {
                aodAnimEnabled = TunerService.parseInteger(newValue, 1) != 0
                if (currentClockView is ClockDesignView) updateClockAppearance()
            }
            CLOCK_ALBUM_ART_COLOR_KEY -> {
                val wasEnabled = albumArtColorEnabled
                albumArtColorEnabled = TunerService.parseInteger(newValue, 0) != 0
                when {
                    albumArtColorEnabled && !wasEnabled ->
                        MediaSessionManager.get().addListener(mediaDataListener)
                    !albumArtColorEnabled && wasEnabled -> {
                        runCatching { MediaSessionManager.get().removeListener(mediaDataListener) }
                        currentAlbumColor = null
                    }
                }
                applyClockColors()
            }
            CLOCK_WOBBLE_ON_CHARGE_KEY -> {
                clockWobbleOnChargeEnabled = TunerService.parseInteger(newValue, 1) != 0
                if (!clockWobbleOnChargeEnabled) cancelWobbleAnimation()
            }
            CLOCK_FRAME_MARGIN_START_KEY -> {
                clockFrameMarginStart = TunerService.parseInteger(newValue, DEFAULT_MARGIN_START)
                    .coerceIn(-200, 200)
                updateClockFrameMargin()
            }
            HOME_CLOCK_SIZE_KEY -> {
                homeClockSizeScale = newValue?.toIntOrNull()?.coerceIn(25, MAX_CLOCK_SIZE)
                if (isHomeScreenClock) applyClockScale()
            }
            HOME_CLOCK_MARGIN_TOP_KEY -> {
                homeClockMarginTop = newValue?.toIntOrNull()?.coerceIn(0, 2000)
                if (isHomeScreenClock) updateClockFrameMargin()
            }
            HOME_CLOCK_MARGIN_START_KEY -> {
                homeClockMarginStart = newValue?.toIntOrNull()?.coerceIn(-2000, 2000)
                if (isHomeScreenClock) updateClockFrameMargin()
            }
            CLOCK_GRADIENT_ENABLED_KEY -> {
                gradientEnabled = TunerService.parseInteger(newValue, 0) != 0
                applyClockColors()
            }
            ClockGlass.ENABLED_SETTING -> {
                textGlassEnabled = TunerService.parseInteger(newValue, 0) != 0
                applyClockColors()
            }
            ClockGlass.INTENSITY_SETTING -> {
                legacyGlassIntensity = newValue != null
                textGlassIntensity = TunerService.parseInteger(newValue, ClockGlass.DEFAULT_INTENSITY).coerceIn(0, 100)
                applyClockColors()
            }
            ClockGlass.LIFT_SETTING -> {
                legacyGlassLift = newValue != null
                textGlassLift = TunerService.parseInteger(newValue, ClockGlass.DEFAULT_LIFT).coerceIn(0, 100)
                applyClockColors()
            }
            ClockGlassStyle.SETTING -> {
                textGlassStyleValue = newValue
                applyClockColors()
            }
            ClockGlassStyle.DATE_SETTING -> {
                dateGlassStyle = ClockGlassStyle.parse(newValue, ClockGlassStyle.DATE_DEFAULT)
                applyClockColors()
            }
            ClockGlassStyle.SEPARATE_DATE_SETTING -> {
                separateDateGlass = TunerService.parseInteger(newValue, 1) != 0
                applyClockColors()
            }
            CLOCK_HYPER_BLUR_KEY -> {
                hyperBlurEnabled = TunerService.parseInteger(newValue, 0) != 0
                applyClockColors()
            }
            CLOCK_GRADIENT_COLOR_START_KEY -> {
                gradientColorStart = TunerService.parseInteger(newValue, DEFAULT_GRADIENT_COLOR_START)
                if (gradientEnabled) applyClockColors()
            }
            CLOCK_GRADIENT_COLOR_END_KEY -> {
                gradientColorEnd = TunerService.parseInteger(newValue, DEFAULT_GRADIENT_COLOR_END)
                if (gradientEnabled) applyClockColors()
            }
            CLOCK_GRADIENT_ANCHOR_Y_KEY -> {
                gradientAnchorY = TunerService.parseInteger(newValue, DEFAULT_GRADIENT_ANCHOR_Y)
                    .coerceIn(0, 100)
                if (gradientEnabled) applyClockColors()
            }
            CLOCK_GRADIENT_RADIUS_KEY -> {
                gradientRadius = TunerService.parseInteger(newValue, DEFAULT_GRADIENT_RADIUS)
                    .coerceIn(MIN_GRADIENT_RADIUS, MAX_GRADIENT_RADIUS)
                if (gradientEnabled) applyClockColors()
            }
            CLOCK_TILT_X_KEY -> {
                clockTiltX = TunerService.parseInteger(newValue, DEFAULT_TILT)
                    .coerceIn(MIN_TILT, MAX_TILT)
                applyClockTransform()
            }
            CLOCK_TILT_Y_KEY -> {
                clockTiltY = TunerService.parseInteger(newValue, DEFAULT_TILT)
                    .coerceIn(MIN_TILT, MAX_TILT)
                applyClockTransform()
            }
            ClockParallaxSettings.ENABLED -> {
                parallaxEnabled = TunerService.parseInteger(newValue, 0) != 0
                updateClockParallax()
                applyClockTransform()
            }
            ClockParallaxSettings.INTENSITY -> {
                parallaxIntensityPercent = TunerService.parseInteger(newValue,
                    ClockParallaxSettings.DEFAULT_INTENSITY).coerceIn(0, 100)
                updateClockParallax()
            }
            CLOCK_ROTATION_KEY -> {
                clockRotation = TunerService.parseInteger(newValue, DEFAULT_ROTATION)
                    .coerceIn(MIN_ROTATION, MAX_ROTATION)
                applyClockTransform()
            }
        }
        if (!isHomeScreenClock && !isDynamicFace && !applyingLayoutProfile) {
            handler.removeCallbacks(updateDynamicLayout)
            handler.post(updateDynamicLayout)
        }
    }

    private fun applyDozing(dozing: Boolean) {
        isDozing = dozing
        updateClockParallax()
        dynamicClockController?.onDozingChanged()
        if (currentClockView is ClockDesignView) cancelWobbleAnimation()
        animateAodTransition(dozing)
        applyClockAlpha()
        applyClockColors()
        applyClockShadow()
        if (currentClockView is ClockDesignView) applyClockScale()
        if (dozing) {
            startBurnInProtection()
            startAodTick()
        } else {
            stopBurnInProtection()
            stopAodTick()
            forceTimeUpdate()
        }
    }

    private fun startAodTick() {
        if (clockStyle == 0) return
        handler.removeCallbacks(aodTickRunnable)
        val now = System.currentTimeMillis()
        val nextMinute = ((now / AOD_UPDATE_INTERVAL_MILLIS) + 1) * AOD_UPDATE_INTERVAL_MILLIS
        handler.postDelayed(aodTickRunnable, nextMinute - now)
    }

    private fun stopAodTick() {
        handler.removeCallbacks(aodTickRunnable)
    }

    private fun startBurnInProtection() {
        if (clockStyle == 0) return
        handler.removeCallbacks(burnInProtectionRunnable)
        handler.postDelayed(burnInProtectionRunnable, BURN_IN_PROTECTION_INTERVAL)
    }

    private fun stopBurnInProtection() {
        if (clockStyle == 0) return
        handler.removeCallbacks(burnInProtectionRunnable)
        currentClockView?.apply {
            if (this !is ClockDesignView) {
                translationX = 0f
                translationY = 0f
            }
        }
    }

    private fun animateAodTransition(toAod: Boolean) {
        val view = currentClockView ?: return
        view.animate().cancel()
        if (view is ClockDesignView) return
        if (!aodAnimEnabled) return
        if (toAod) {
            view.animate()
                .scaleX(AOD_SCALE_DOWN)
                .scaleY(AOD_SCALE_DOWN)
                .setDuration(AOD_ANIM_OUT_MS)
                .setInterpolator(AccelerateInterpolator(1.5f))
                .start()
        } else {
            view.scaleX = AOD_SCALE_DOWN
            view.scaleY = AOD_SCALE_DOWN
            val targetScale = getScaleFactor()
            view.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(AOD_ANIM_IN_MS)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    fun onTimeChanged() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateTimeMillis >= UPDATE_INTERVAL_MILLIS) {
            forceTimeUpdate()
        }
    }

    internal fun clockBounds(rootTransform: Matrix? = null, bounds: RectF = RectF()): RectF? {
        val controller = dynamicClockController?.takeIf { it.enabled }
            ?: return baseClockBounds(rootTransform, bounds)
        val combined = RectF()
        val faceBounds = RectF()
        var found = false
        fun include(value: RectF?) {
            if (value == null) return
            if (found) combined.union(value) else combined.set(value)
            found = true
        }
        controller.visibleClocks.forEach { renderer ->
            if (rootTransform == null) include(renderer.clockBounds(bounds = faceBounds)) else {
                val matrix = Matrix()
                if (ClockViewTransform.toAncestor(renderer, this, matrix)) {
                    matrix.postConcat(rootTransform)
                    include(renderer.clockBounds(matrix, faceBounds))
                }
            }
        }
        if (controller.isBaselineVisible) include(baseClockBounds(rootTransform, faceBounds))
        return if (found) bounds.apply { set(combined) } else null
    }

    internal fun clockLayoutBounds(rootTransform: Matrix? = null, bounds: RectF = RectF()): RectF? {
        val renderer = dynamicClockController?.activeClock ?: this
        val view = renderer.currentClockView as? ClockDesignView ?: return null
        if (!view.isAttachedToWindow || !view.isLaidOut || view.width <= 0) return null
        val matrix = clockBoundsMatrix
        if (rootTransform == null) {
            if (!ClockViewTransform.toGlobal(view, matrix)) return null
        } else {
            if (!ClockViewTransform.toAncestor(view, this, matrix)) return null
            matrix.postConcat(rootTransform)
        }
        bounds.set(0f, 0f, view.width.toFloat(), view.width.toFloat())
        matrix.mapRect(bounds)
        return bounds.takeUnless { it.isEmpty }
    }

    private fun baseClockBounds(rootTransform: Matrix?, bounds: RectF): RectF? {
        val view = currentClockView ?: return null
        if (!view.isAttachedToWindow || !view.isLaidOut || view.visibility != View.VISIBLE) return null
        val matrix = clockBoundsMatrix
        if (rootTransform == null) {
            if (!ClockViewTransform.toGlobal(view, matrix)) return null
        } else {
            if (!ClockViewTransform.toAncestor(view, this, matrix)) return null
            matrix.postConcat(rootTransform)
        }
        bounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        matrix.mapRect(bounds)
        return bounds.takeUnless { it.isEmpty }
    }

    internal val transitionPadding: Float
        get() {
            val controller = dynamicClockController
            val baseline = if (controller?.isBaselineVisible != false)
                (if (shadowEnabled) shadowSoftness * 2f + 4f else 8f) * resources.displayMetrics.density else 0f
            return maxOf(baseline, controller?.visibleClocks?.maxOfOrNull { it.transitionPadding } ?: 0f)
        }

    internal val hasGlassEffects: Boolean
        get() {
            val controller = dynamicClockController
            val baseline = controller?.isBaselineVisible != false && (textGlass.hasGlassElements ||
                (hyperBlurEnabled && !gradientEnabled && hyperClocks.isNotEmpty()) ||
                (currentClockView as? ClockDesignView)?.hasGlassElements == true)
            return baseline || controller?.visibleClocks?.any { it.hasGlassEffects } == true
        }

    internal fun setGlassTransitionAmount(value: Float) {
        if (!value.isFinite()) return
        val animation = glassAnimator
        glassAnimator = null
        animation?.cancel()
        val amount = value.coerceIn(0f, 1f)
        if (glassTransitionAmount != amount) {
            glassTransitionAmount = amount
            updateGlassAmount()
        }
        dynamicClockController?.layoutClocks?.forEach { it.setGlassTransitionAmount(amount) }
    }

    internal fun restoreGlassBlur(animate: Boolean) {
        if (!animate || !hasGlassEffects || !screenInteractive || !isShown ||
            windowVisibility != View.VISIBLE || alpha <= 0f ||
            !ValueAnimator.areAnimatorsEnabled() || glassTransitionAmount >= 1f) {
            setGlassTransitionAmount(1f)
            return
        }
        val from = glassTransitionAmount
        setGlassTransitionAmount(from)
        val animation = ValueAnimator.ofFloat(from, 1f)
        glassAnimator = animation
        animation.duration = 180L
        animation.interpolator = DecelerateInterpolator()
        animation.addUpdateListener {
            if (glassAnimator === animation) {
                glassTransitionAmount = it.animatedValue as Float
                updateGlassAmount()
                dynamicClockController?.layoutClocks?.forEach { clock ->
                    clock.setGlassTransitionAmount(glassTransitionAmount)
                }
            }
        }
        animation.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) {
                if (glassAnimator === animation) {
                    glassAnimator = null
                    setGlassTransitionAmount(1f)
                }
            }
        })
        animation.start()
    }

    private fun updateGlassAmount() {
        val doze = when {
            isHomeScreenClock -> 0f
            isDynamicFace -> layoutAodProgress
            else -> statusBarStateController.interpolatedDozeAmount.coerceIn(0f, 1f)
        }
        textGlass.blurAmount = (1f - doze) * glassTransitionAmount
        (currentClockView as? ClockDesignView)?.glassBlurAmount = (1f - doze) * glassTransitionAmount
    }

    private fun forceTimeUpdate() {
        if (currentClockView == null) return
        stockClockController?.let {
            it.events.onTimeZoneChanged(android.icu.util.TimeZone.getDefault())
            it.events.onLocaleChanged(java.util.Locale.getDefault())
            it.events.onTimeFormatChanged(TimeFormatKind.getFromContext(context, ActivityManager.getCurrentUser()))
            it.smallClock.events.onTimeTick()
            it.largeClock.events.onTimeTick()
        }
        (currentClockView as? ClockDesignView)?.refreshTime()
        for (i in styledTextViews.indices) {
            val tv = styledTextViews[i]
            if (tv is TextClock) continue
            (tv.getTag(R.id.original_typeface) as? Typeface)?.let { tv.typeface = it }
        }
        for (i in textClocks.indices) {
            val tc = textClocks[i]
            (tc.getTag(R.id.original_typeface) as? Typeface)?.let { tc.typeface = it }
            tc.refreshTime()
        }
        for (i in hyperClocks.indices) {
            hyperClocks[i].refreshTime()
        }
        lastUpdateTimeMillis = System.currentTimeMillis()
    }

    private fun updateClockView() {
        textGlass.clear()
        removePendingLayoutListener()
        currentClockView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        currentClockView = null
        layoutAodView = null
        stockClockController = null
        textClocks.clear()
        styledTextViews.clear()
        hyperClocks.clear()

        if (clockStyle == 0 && (isDynamicFace || isHomeScreenClock)) {
            val profile = layoutProfile ?: return
            val baseSettings = runCatching {
                ClockSettings.fromJson(org.json.JSONObject(Settings.Secure.getStringForUser(
                    context.contentResolver, Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                    UserHandle.USER_CURRENT) ?: "{}"))
            }.getOrDefault(ClockSettings(DEFAULT_CLOCK_ID))
            val axes = profile.preset?.let { preset ->
                val (weight, width) = STOCK_PRESETS[preset.coerceIn(0, STOCK_PRESETS.lastIndex)]
                ClockAxisStyle(mapOf("wght" to weight, "wdth" to width,
                    "ROND" to if (profile.rounded) 100f else 0f, "slnt" to 0f))
            } ?: if (profile.inheritStockSettings) baseSettings.axes else ClockAxisStyle(
                profile.stockAxes ?: mapOf("wght" to 500f, "wdth" to 100f)).apply {
                put("ROND", if (profile.rounded) 100f else 0f)
            }
            val seedColor = if (profile.inheritStockSettings) baseSettings.seedColor else profile.color
            val controller = DefaultClockProvider(LayoutInflater.from(context), resources,
                com.android.systemui.shared.Flags.clockReactiveVariants(),
                context.getSystemService(Vibrator::class.java))
                .createClock(context, ClockSettings(DEFAULT_CLOCK_ID, seedColor, axes))
            stockClockController = controller
            controller.initialize(true, 0f, 0f)
            val face = if (profile.small) controller.smallClock else controller.largeClock
            face.events.onFontSettingChanged(resources.getDimension(
                if (profile.small) ClocksR.dimen.small_clock_text_size else ClocksR.dimen.large_clock_text_size))
            val view = face.view.apply {
                id = View.generateViewId()
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            currentClockView = view
            clockStub?.let { clockContainer?.removeView(it) }
            clockStub = null
            clockContainer?.addView(view)
            cacheClockViews(view)
            applyLayoutFont()
            updateClockAppearance()
            updateClockFrameMargin()
            attachClockSizeListener(view)
        } else if (clockStyle in 1 until CLOCK_LAYOUTS.size) {
            val stub = clockStub
            if (stub != null) {
                stub.layoutResource = CLOCK_LAYOUTS[clockStyle]
                val inflated = stub.inflate()
                currentClockView = inflated
                clockContainer = inflated.parent as? ClockStyleFrameLayout
                clockStub = null
            } else {
                clockContainer?.let { container ->
                    val inflated = LayoutInflater.from(context)
                        .inflate(CLOCK_LAYOUTS[clockStyle], container, false)
                    currentClockView = inflated
                    container.addView(inflated)
                }
            }

            currentClockView?.let { view ->
                // Match the detached preview and the first ViewStub inflation. Root layout
                // margins/gravity must not change the clock's origin when switching styles.
                view.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                (view as? ClockDesignView)?.let {
                    it.userId = ActivityManager.getCurrentUser()
                    it.wallpaperFlags = textGlass.wallpaperFlags
                    it.setDesign(clockDesign)
                    it.onQuickLookClick = if (isHomeScreenClock) null else { action ->
                        val starter = Dependency.get(ActivityStarter::class.java)
                        val pendingIntent = action.pendingIntent
                        val intent = action.intent
                        if (pendingIntent != null) starter.startPendingIntentDismissingKeyguard(pendingIntent)
                        else if (intent != null) starter.startActivity(intent, true)
                    }
                }
                cacheClockViews(view)
                applyLayoutFont()
                disableClippingOnParents(view)

                view.findViewById<ImageView?>(R.id.user_profile_icon)?.setImageDrawable(
                    UserProfileUtils.getUserProfileIcon(context)
                )
                view.findViewById<TextView?>(R.id.user_name)?.text =
                    UserProfileUtils.getUsername(context)
                view.findViewById<TextView?>(R.id.device_name)?.text =
                    UserProfileUtils.getDeviceName()

                updateClockAppearance()
                updateClockFrameMargin()
                attachClockSizeListener(view)
            }
        }

        forceTimeUpdate()
        updateClockVisibility()
        updateClockParallax()
        if (isDozing && clockStyle != 0) startAodTick()
    }

    private fun applyLayoutFont() {
        val font = layoutProfile?.font ?: return
        if (currentClockView is ClockDesignView) return
        val generation = profileGeneration
        val user = ActivityManager.getCurrentUser()
        clockFontExecutor.execute {
            val face = ClockFonts.load(context, user, font)
            handler.post {
                if (profileGeneration != generation || layoutProfile?.font != font) return@post
                styledTextViews.forEach { view ->
                    view.typeface = face
                    view.setTag(R.id.original_typeface, face)
                }
                requestLayout()
            }
        }
    }

    private fun cacheClockViews(root: View) {
        if (root is ClockDesignView) return
        when (root) {
            is TextClock -> {
                (root as? AlignedTextClock)?.digitAlignment = digitAlignment
                root.setTag(R.id.original_typeface, root.typeface)
                root.setTag(R.id.original_text_color, root.currentTextColor)
                root.setTag(R.id.original_alpha, root.alpha)
                textClocks.add(root)
                styledTextViews.add(root)
            }
            is TextView -> {
                root.setTag(R.id.original_typeface, root.typeface)
                root.setTag(R.id.original_text_color, root.currentTextColor)
                root.setTag(R.id.original_alpha, root.alpha)
                styledTextViews.add(root)
            }
            is HyperClockView -> {
                root.wallpaperFlags = textGlass.wallpaperFlags
                root.setTag(R.id.original_alpha, root.alpha)
                hyperClocks.add(root)
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                cacheClockViews(root.getChildAt(i))
            }
        }
    }

    private fun updateClockFrameMargin() {
        val clockFrame = findViewById<View?>(R.id.clock_frame) ?: return
        val params = clockFrame.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val density = resources.displayMetrics.density
        val keyguardTop = resources.getDimensionPixelSize(R.dimen.status_bar_height) * 1.25f
        val marginTop = if (isHomeScreenClock) homeClockMarginTop ?: clockFrameMarginTop
            else clockFrameMarginTop
        // Lockstyles rounds the full screen offset once; the section already supplies
        // keyguardTop.toInt(), so keep its fractional pixel when adding the user offset.
        params.topMargin = ClockPreviewGeometry.topInset(keyguardTop, marginTop, density)
        val side = resources.getDimensionPixelSize(R.dimen.below_clock_padding_start)
        params.marginStart = side
        params.marginEnd = side
        // Move a fixed-width clock, just as CustomClocks.frame does in Lockstyles.
        // Changing a margin instead remeasures/recentres it and also changes its size.
        val marginStart = if (isHomeScreenClock && homeClockMarginStart != null) {
            homeClockMarginStart!!
        } else if (isNoHorizontalMarginClock(clockStyle)) 0 else clockFrameMarginStart
        clockFrame.translationX = (marginStart * density).toInt().toFloat()
        clockFrame.layoutParams = params
    }

    private fun updateClockAppearance() {
        if (currentClockView == null) return
        applyClockAlpha()
        applyClockColors()
        applyClockShadow()
        applyClockScaleAfterLayout(currentClockView!!)
    }

    private fun applyClockShadow() {
        val view = currentClockView ?: return
        val progress = if (view is ClockDesignView) clockAodProgress() else if (isDozing) 1f else 0f
        view.setRenderEffect(if (shadowEnabled && progress < 1f) ClockEffects.shadow(shadowColor,
            (shadowOpacity * (1f - progress)).roundToInt(), shadowSoftness, resources.displayMetrics.density) else null)
    }

    private fun applyClockAlpha() {
        val view = currentClockView ?: return
        if (view is ClockDesignView) {
            val progress = clockAodProgress()
            view.alpha = layoutAodSource?.let { source ->
                ClockElementAnimation.lerp(ClockViewTransform.alpha(clockOpacity),
                    ClockViewTransform.alpha(source.clockOpacity, true), progress)
            } ?: ClockViewTransform.alpha(clockOpacity, progress)
            view.animationsEnabled = view.alpha > 0f && !isDozing && progress == 0f &&
                clockContainer?.visibility != View.INVISIBLE && (!isDynamicFace || layoutClockInteractive)
        } else view.alpha = ClockViewTransform.alpha(clockOpacity, isDozing)
    }

    private fun resolveClockColor(): Int {
        if (albumArtColorEnabled) {
            currentAlbumColor?.let {
                return boostSaturation(it)
            }
        }
        return when (colorMode) {
            COLOR_MODE_ACCENT -> context.getColor(
                resources.getIdentifier("system_accent1_100", "color", "android")
            )
            COLOR_MODE_CUSTOM -> customColor
            else -> context.getColor(android.R.color.white)
        }
    }

    private fun boostSaturation(color: Int, amount: Float = 0.20f): Int {
        val hsv = FloatArray(3)
        colorToHSV(color, hsv)
        hsv[1] = (hsv[1] + amount).coerceAtMost(1.0f)
        return HSVToColor(hsv)
    }

    private fun getOffsetWithinAncestor(view: View, ancestor: View): Pair<Int, Int> {
        var offsetX = 0
        var offsetY = 0
        var current: View = view
        while (current !== ancestor) {
            offsetX += current.left
            offsetY += current.top
            val parent = current.parent as? View ?: break
            current = parent
        }
        return offsetX to offsetY
    }

    private fun buildGradientShader(
        width: Int,
        height: Int,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ): Shader {
        val rad = Math.toRadians(gradientAngleDeg.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        val cx = width / 2f
        val cy = height * (gradientAnchorY / 100f)
        val halfW = width / 2f
        val halfH = height / 2f
        val baseLen = abs(dx * halfW) + abs(dy * halfH)
        val len = baseLen * (gradientRadius / 100f)
        val x0 = cx - dx * len - offsetX
        val y0 = cy - dy * len - offsetY
        val x1 = cx + dx * len - offsetX
        val y1 = cy + dy * len - offsetY
        return LinearGradient(
            x0, y0, x1, y1,
            gradientColorStart, gradientColorEnd,
            Shader.TileMode.CLAMP,
        )
    }

    private fun applyGradientToView(view: TextView) {
        val container = currentClockView ?: return
        if (view.width <= 0 || view.height <= 0) return
        if (container.width <= 0 || container.height <= 0) return
        val (offsetX, offsetY) = getOffsetWithinAncestor(view, container)
        view.paint.shader = buildGradientShader(container.width, container.height, offsetX, offsetY)
        view.invalidate()
    }

    private fun clearGradientFromView(view: TextView) {
        if (view.paint.shader != null) {
            view.paint.shader = null
            view.invalidate()
        }
    }

    private fun attachClockSizeListener(view: View) {
        clockSizeListener?.let { currentClockView?.removeOnLayoutChangeListener(it) }
        val listener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, oR, ob ->
            if (r - l != oR - ol || b - t != ob - ot) {
                applyClockScale()
                if (gradientEnabled) applyClockColors()
            }
        }
        clockSizeListener = listener
        view.addOnLayoutChangeListener(listener)
    }

    private fun applyClockColors() {
        updateGlassAmount()
        stockClockController?.let { controller ->
            val color = if (colorMode == COLOR_MODE_DEFAULT && !albumArtColorEnabled) {
                if (layoutProfile?.inheritStockSettings == true) controller.smallClock.theme.seedColor
                else layoutProfile?.color
            } else resolveClockColor()
            val face = if (layoutProfile?.small == true) controller.smallClock else controller.largeClock
            face.events.onThemeChanged(face.theme.copy(isDarkTheme = true, seedColor = color))
            return
        }
        textGlass.enabled = !isDozing
        textGlass.beginUpdate()
        textGlass.userId = ActivityManager.getCurrentUser()
        try {
            applyClockColorsToViews()
        } finally {
            textGlass.commit()
        }
    }

    private fun applyClockColorsToViews() {
        (currentClockView as? ClockDesignView)?.let {
            it.setAppearance(ClockDesignAppearance(resolveClockColor(), gradientEnabled,
                gradientColorStart, gradientColorEnd, gradientAnchorY, gradientRadius,
                makerWeather || layoutAodSource != null, isDozing,
                aodAnimation = layoutAodSource?.aodAnimEnabled ?: aodAnimEnabled,
                digitAlignment = digitAlignment))
            it.setAodProgress(clockAodProgress())
            return
        }
        if (isNoColorClock(clockStyle)) return
        applyHyperClockColors()
        if (styledTextViews.isEmpty()) return
        val whiteColor = context.getColor(android.R.color.white)
        val useGlass = textGlassEnabled && clockStyle !in 93..102
        val glassStyle = ClockGlassStyle.parse(textGlassStyleValue,
            if (legacyGlassIntensity || legacyGlassLift) ClockGlassStyle.legacy(textGlassIntensity, textGlassLift) else ClockGlassStyle())
        val useGradient = gradientEnabled && !isDozing && !useGlass
        for (i in textClocks.indices) {
            val tc = textClocks[i]
            (tc.getTag(R.id.original_typeface) as? Typeface)?.let { tc.typeface = it }
            val originalColor = tc.getTag(R.id.original_text_color) as? Int ?: tc.currentTextColor
            val isWhiteOriginal = (originalColor and 0x00FFFFFF) == (whiteColor and 0x00FFFFFF)
            when {
                isDozing -> {
                    clearGradientFromView(tc)
                    tc.setTextColor(whiteColor)
                }
                useGlass -> {
                    clearGradientFromView(tc)
                    tc.setTextColor(if (isWhiteOriginal) resolveClockColor() else originalColor)
                }
                !isWhiteOriginal -> {
                    clearGradientFromView(tc)
                    tc.setTextColor(originalColor)
                }
                useGradient -> {
                    tc.setTextColor(Color.WHITE)
                    applyGradientToView(tc)
                }
                else -> {
                    clearGradientFromView(tc)
                    tc.setTextColor(resolveClockColor())
                }
            }
            if (useGlass) textGlass.add(tc,
                if (separateDateGlass && ClockGlassController.isDate(tc)) dateGlassStyle else glassStyle)
        }

        for (i in styledTextViews.indices) {
            val tv = styledTextViews[i]
            if (tv is TextClock) continue
            val originalColor = tv.getTag(R.id.original_text_color) as? Int ?: tv.currentTextColor
            val isWhiteOriginal = (originalColor and 0x00FFFFFF) == (whiteColor and 0x00FFFFFF)
            when {
                isDozing -> {
                    clearGradientFromView(tv)
                    tv.setTextColor(whiteColor)
                }
                useGlass -> {
                    clearGradientFromView(tv)
                    tv.setTextColor(if (isWhiteOriginal) resolveClockColor() else originalColor)
                }
                !isWhiteOriginal -> {
                    clearGradientFromView(tv)
                    tv.setTextColor(originalColor)
                }
                useGradient -> {
                    tv.setTextColor(Color.WHITE)
                    applyGradientToView(tv)
                }
                else -> {
                    clearGradientFromView(tv)
                    tv.setTextColor(resolveClockColor())
                }
            }
            if (useGlass) textGlass.add(tv,
                if (separateDateGlass && ClockGlassController.isDate(tv)) dateGlassStyle else glassStyle)
        }
    }

    private fun applyHyperClockColors() {
        if (hyperClocks.isEmpty()) return
        val whiteColor = context.getColor(android.R.color.white)
        val useGradient = gradientEnabled && !isDozing
        val clockColor = resolveClockColor()
        val colorPicked = (clockColor and 0x00FFFFFF) != (whiteColor and 0x00FFFFFF)
        val container = currentClockView
        for (i in hyperClocks.indices) {
            val hv = hyperClocks[i]
            when {
                isDozing -> {
                    hv.setBlurEnabled(false)
                    hv.setGradientShader(null)
                    hv.setColonFollowsDigits(false)
                    hv.setDigitColor(whiteColor)
                    hv.setColonColor(whiteColor)
                }
                useGradient -> {
                    hv.setBlurEnabled(false)
                    hv.restoreColonColor()
                    hv.setColonFollowsDigits(true)
                    hv.setDigitColor(whiteColor)
                    if (container != null && hv.width > 0 && hv.height > 0 &&
                        container.width > 0 && container.height > 0
                    ) {
                        val (offsetX, offsetY) = getOffsetWithinAncestor(hv, container)
                        hv.setGradientShader(
                            buildGradientShader(
                                container.width,
                                container.height,
                                offsetX,
                                offsetY,
                            )
                        )
                    }
                }
                else -> {
                    hv.setGradientShader(null)
                    hv.restoreColonColor()
                    hv.setColonFollowsDigits(colorPicked)
                    hv.setDigitColor(clockColor)
                    hv.setBlurEnabled(hyperBlurEnabled)
                }
            }
        }
    }

    private fun getScaleFactor(): Float =
        (if (isHomeScreenClock) homeClockSizeScale ?: clockSizeScale else clockSizeScale) / 100f

    private fun clockAodProgress(): Float = when {
        isHomeScreenClock -> 0f
        isDynamicFace -> if (layoutAodSource?.aodAnimEnabled == false)
            (if (isDozing) 1f else 0f) else layoutAodProgress
        !aodAnimEnabled -> if (isDozing) 1f else 0f
        else -> statusBarStateController.interpolatedDozeAmount.coerceIn(0f, 1f)
    }

    private fun applyClockScale() {
        val view = currentClockView ?: return
        updateGlassAmount()
        val layoutScale = layoutMorphTarget?.let {
            ClockElementAnimation.lerp(getScaleFactor(), it.getScaleFactor(), layoutMorphFraction)
        } ?: getScaleFactor()
        val scale = if (view is ClockDesignView) {
            val progress = clockAodProgress()
            view.setAodProgress(progress)
            if (!isHomeScreenClock && !isDynamicFace &&
                (progress > 0f || currentShiftX != 0 || currentShiftY != 0)) {
                view.translationX = currentShiftX * progress
                view.translationY = currentShiftY * progress
                if (progress == 0f) {
                    currentShiftX = 0
                    currentShiftY = 0
                }
            }
            layoutAodGeometry?.takeIf { view.width > 0 }?.let { geometry ->
                val aodScale = geometry.scale * geometry.layout.width / view.width
                ClockElementAnimation.lerp(getScaleFactor(), aodScale, progress)
            } ?: ClockViewTransform.renderScale(layoutScale, progress, aodAnimEnabled)
        } else getScaleFactor()
        view.scaleX = scale
        view.scaleY = scale
        view.pivotX = view.width / 2f
        view.pivotY = (view as? ClockDesignView)?.transformPivotY ?: (view.height / 2f)
        clockContainer?.clockScale = scale
        disableClippingOnParents(view)
        applyClockTransform()
        layoutAodGeometry?.let { geometry ->
            val target = currentLayoutGeometry() ?: return@let
            val progress = clockAodProgress()
            view.translationX = (geometry.layout.x - target.x) * progress
            view.translationY = (geometry.layout.y - target.y) * progress
            layoutClockGeometry = target
        }
    }

    private fun applyClockTransform() {
        val view = currentClockView ?: return
        val overrideTilt = parallaxEnabled && !isHomeScreenClock
        ClockViewTransform.applyClockTransform(view, clockRotation,
            if (overrideTilt) parallaxRotationX else clockTiltX.toFloat(),
            if (overrideTilt) parallaxRotationY else clockTiltY.toFloat())
        layoutAodSource?.let { source ->
            val progress = clockAodProgress()
            val sourceRotation = source.clockRotation.toFloat()
            view.rotation = if (progress == 1f) sourceRotation else clockRotation +
                ((sourceRotation - clockRotation + 540f) % 360f - 180f) * progress
            view.rotationX = ClockElementAnimation.lerp(view.rotationX,
                if (source.parallaxEnabled) source.parallaxRotationX else source.clockTiltX.toFloat(), progress)
            view.rotationY = ClockElementAnimation.lerp(view.rotationY,
                if (source.parallaxEnabled) source.parallaxRotationY else source.clockTiltY.toFloat(), progress)
            layoutAodGeometry?.takeIf { view.width > 0 }?.let { geometry ->
                view.cameraDistance = ClockElementAnimation.lerp(view.cameraDistance,
                    geometry.cameraDistance * view.width / geometry.layout.width, progress)
            }
        }
        disableClippingOnParents(view)
    }

    private fun applyClockScaleAfterLayout(view: View) {
        if (view.width > 0) {
            applyClockScale()
            return
        }
        removePendingLayoutListener()
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int,
                ol: Int, ot: Int, oR: Int, ob: Int,
            ) {
                v.removeOnLayoutChangeListener(this)
                if (pendingLayoutListener === this) pendingLayoutListener = null
                applyClockScale()
                if (gradientEnabled) applyClockColors()
            }
        }
        pendingLayoutListener = listener
        view.addOnLayoutChangeListener(listener)
    }

    private fun removePendingLayoutListener() {
        pendingLayoutListener?.let { currentClockView?.removeOnLayoutChangeListener(it) }
        pendingLayoutListener = null
    }

    private fun disableClippingOnParents(view: View) {
        clipChildren = false
        clipToPadding = false
        var current: View? = view
        while (current != null && current !== this) {
            (current as? ViewGroup)?.let {
                it.clipChildren = false
                it.clipToPadding = false
            }
            val parent = current.parent
            if (parent !is View) break
            current = parent
        }
    }

    private fun isNoColorClock(style: Int): Boolean = NO_COLOR_CLOCKS.contains(style)
    private fun isNoHorizontalMarginClock(style: Int): Boolean = NO_HORIZONTAL_MARGIN_CLOCKS.contains(style)

    private fun startWobbleAnimation() {
        val view = currentClockView ?: return
        cancelWobbleAnimation()

        val base = getScaleFactor()
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val riseAmp = WOBBLE_RISE_DP * density * base
        val ty0 = Keyframe.ofFloat(0.00f,  0f)
        val ty1 = Keyframe.ofFloat(0.18f, -riseAmp)
        val ty2 = Keyframe.ofFloat(0.36f, -riseAmp * 0.44f)
        val ty3 = Keyframe.ofFloat(0.52f, -riseAmp * 0.21f)
        val ty4 = Keyframe.ofFloat(0.66f, -riseAmp * 0.09f)
        val ty5 = Keyframe.ofFloat(0.78f, -riseAmp * 0.04f)
        val ty6 = Keyframe.ofFloat(1.00f,  0f)   
        val pvhY = PropertyValuesHolder.ofKeyframe("translationY", ty0, ty1, ty2, ty3, ty4, ty5, ty6)

        val sx0 = Keyframe.ofFloat(0.00f, base * 1.000f)
        val sx1 = Keyframe.ofFloat(0.18f, base * 0.940f)
        val sx2 = Keyframe.ofFloat(0.36f, base * 1.018f)
        val sx3 = Keyframe.ofFloat(0.52f, base * 0.968f)
        val sx4 = Keyframe.ofFloat(0.66f, base * 1.008f)
        val sx5 = Keyframe.ofFloat(1.00f, base * 1.000f)
        val pvhSX = PropertyValuesHolder.ofKeyframe("scaleX", sx0, sx1, sx2, sx3, sx4, sx5)

        val sy0 = Keyframe.ofFloat(0.00f, base * 1.000f)
        val sy1 = Keyframe.ofFloat(0.18f, base * 1.038f)
        val sy2 = Keyframe.ofFloat(0.36f, base * 0.984f)
        val sy3 = Keyframe.ofFloat(0.52f, base * 1.018f)
        val sy4 = Keyframe.ofFloat(0.66f, base * 0.994f)
        val sy5 = Keyframe.ofFloat(1.00f, base * 1.000f)
        val pvhSY = PropertyValuesHolder.ofKeyframe("scaleY", sy0, sy1, sy2, sy3, sy4, sy5)

        wobbleAnimator = ObjectAnimator.ofPropertyValuesHolder(view, pvhY, pvhSX, pvhSY).apply {
            duration = WOBBLE_DURATION_MS
            interpolator = null
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.translationY = 0f
                    view.scaleX = getScaleFactor()
                    view.scaleY = getScaleFactor()
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                    clearWobbleGlow()
                    wobbleAnimator = null
                }
                override fun onAnimationCancel(animation: Animator) {
                    view.translationY = 0f
                    view.scaleX = getScaleFactor()
                    view.scaleY = getScaleFactor()
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                    clearWobbleGlow()
                    wobbleAnimator = null
                }
            })
            start()
        }

        val baseGlowColor = resolveClockColor()
        val baseHsv = FloatArray(3)
        Color.colorToHSV(baseGlowColor, baseHsv)
        val warmS = 1.0f
        val warmV = 1.0f

        val userAlphaNorm = (clockOpacity.coerceIn(0, 100) / 100f)

        wobbleGlowAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = WOBBLE_DURATION_MS
            interpolator = null
            addUpdateListener { anim ->
                val animFrac = anim.animatedFraction
                val currentTY = view.translationY
                val fraction = (-currentTY / riseAmp).coerceIn(0f, 1f)
                val flashAlpha: Float = when {
                    animFrac < 0.06f -> userAlphaNorm + (1f - userAlphaNorm) * (animFrac / 0.06f)
                    animFrac < 0.12f -> 1f - (1f - userAlphaNorm) * ((animFrac - 0.06f) / 0.06f)
                    else -> userAlphaNorm
                }

                val lerpedHsv = floatArrayOf(
                    baseHsv[0],
                    baseHsv[1] + (warmS - baseHsv[1]) * fraction,
                    baseHsv[2] + (warmV - baseHsv[2]) * fraction,
                )

                val shiftedBaseColor = Color.HSVToColor(lerpedHsv)
                val radius = WOBBLE_GLOW_MAX_RADIUS * fraction
                val dy = WOBBLE_GLOW_MAX_DY * (1f - fraction)
                val alpha = (WOBBLE_GLOW_MAX_ALPHA * fraction).toInt().coerceIn(0, 255)
                val currentGlowColor = Color.argb(
                    alpha,
                    Color.red(shiftedBaseColor),
                    Color.green(shiftedBaseColor),
                    Color.blue(shiftedBaseColor),
                )

                for (i in textClocks.indices) {
                    val tc = textClocks[i]
                    val origAlpha = tc.getTag(R.id.original_alpha) as? Float ?: 1f
                    tc.alpha = flashAlpha * origAlpha
                    tc.setShadowLayer(radius, 0f, dy, currentGlowColor)
                }
                for (i in styledTextViews.indices) {
                    val tv = styledTextViews[i]
                    if (tv !is android.widget.TextClock) {
                        val origAlpha = tv.getTag(R.id.original_alpha) as? Float ?: 1f
                        tv.alpha = flashAlpha * origAlpha
                        tv.setShadowLayer(radius, 0f, dy, currentGlowColor)
                    }
                }
                for (i in hyperClocks.indices) {
                    val hv = hyperClocks[i]
                    val origAlpha = hv.getTag(R.id.original_alpha) as? Float ?: 1f
                    hv.alpha = flashAlpha * origAlpha
                }
            }
            start()
        }
    }

    private fun cancelWobbleAnimation() {
        wobbleAnimator?.cancel()
        wobbleAnimator = null
        wobbleGlowAnimator?.cancel()
        wobbleGlowAnimator = null
        clearWobbleGlow()
    }

    private fun clearWobbleGlow() {
        for (i in textClocks.indices) {
            val tc = textClocks[i]
            val origAlpha = tc.getTag(R.id.original_alpha) as? Float ?: 1f
            tc.alpha = origAlpha
            tc.setShadowLayer(0f, 0f, 0f, 0)
        }
        for (i in styledTextViews.indices) {
            val tv = styledTextViews[i]
            if (tv !is android.widget.TextClock) {
                val origAlpha = tv.getTag(R.id.original_alpha) as? Float ?: 1f
                tv.alpha = origAlpha
                tv.setShadowLayer(0f, 0f, 0f, 0)
            }
        }
        for (i in hyperClocks.indices) {
            val hv = hyperClocks[i]
            val origAlpha = hv.getTag(R.id.original_alpha) as? Float ?: 1f
            hv.alpha = origAlpha
        }
    }

    companion object {
        private val lockscreenClocks = Collections.newSetFromMap(WeakHashMap<ClockStyle, Boolean>())
        private val previewGeometries = ConcurrentHashMap<Pair<Int, Int>, ClockPreviewGeometry>()

        internal fun previewGeometry(displayId: Int, userId: Int): ClockPreviewGeometry? =
            previewGeometries[displayId to userId]

        internal fun lockscreenClock(displayId: Int): ClockStyle? =
            lockscreenClocks.firstOrNull {
                it.id == R.id.clock_ls && it.isShown && it.alpha > 0f &&
                    it.display?.displayId == displayId && it.clockBounds() != null
            } ?: lockscreenClocks.firstOrNull {
                it.isShown && it.alpha > 0f && it.display?.displayId == displayId &&
                    it.clockBounds() != null
            }

        internal fun unlockClocks(): List<ClockStyle> = lockscreenClocks
            .mapNotNull { it.display?.displayId }.distinct().mapNotNull(::lockscreenClock)

        private val CLOCK_LAYOUTS = intArrayOf(
            0,
            R.layout.keyguard_clock_oos,            // 1
            R.layout.keyguard_clock_oos2,           // 2
            R.layout.keyguard_clock_center,         // 3
            R.layout.keyguard_clock_simple,         // 4
            R.layout.keyguard_clock_miui,           // 5
            R.layout.keyguard_clock_ide,            // 6
            R.layout.keyguard_clock_moto,           // 7
            R.layout.keyguard_clock_stylish,        // 8
            R.layout.keyguard_clock_stylish2,       // 9
            R.layout.keyguard_clock_stylish3,       // 10
            R.layout.keyguard_clock_stylish4,       // 11
            R.layout.keyguard_clock_stylish5,       // 12
            R.layout.keyguard_clock_stylish6,       // 13
            R.layout.keyguard_clock_stylish7,       // 14
            R.layout.keyguard_clock_stylish8,       // 15
            R.layout.keyguard_clock_stylish9,       // 16
            R.layout.keyguard_clock_stylish10,      // 17
            R.layout.keyguard_clock_word,           // 18
            R.layout.keyguard_clock_life,           // 19
            R.layout.keyguard_clock_a9,             // 20
            R.layout.keyguard_clock_nos1,           // 21
            R.layout.keyguard_clock_nos2,           // 22
            R.layout.keyguard_clock_num,            // 23
            R.layout.keyguard_clock_accent,         // 24
            R.layout.keyguard_clock_analog,         // 25
            R.layout.keyguard_clock_block,          // 26
            R.layout.keyguard_clock_bubble,         // 27
            R.layout.keyguard_clock_label,          // 28
            R.layout.keyguard_clock_ios,            // 29
            R.layout.keyguard_clock_taden,          // 30
            R.layout.keyguard_clock_mont,           // 31
            R.layout.keyguard_clock_encode,         // 32
            R.layout.keyguard_clock_nos3,           // 33
            R.layout.keyguard_anci_clock_outline,   // 34
            R.layout.keyguard_anci_clock_ovalium,   // 35
            R.layout.keyguard_anci_clock_rectangle, // 36
            R.layout.keyguard_anci_clock_wallet,    // 37
            R.layout.keyguard_anci_clockdate_clavicula, // 38
            R.layout.keyguard_anci_clockdate_kln,   // 39
            R.layout.keyguard_anci_clockdate_miring, // 40
            R.layout.keyguard_anci_clockdate_scapula, // 41
            R.layout.keyguard_anci_clockdate_sternum, // 42
            R.layout.keyguard_sparkCircle,          // 43
            R.layout.keyguard_sparkList,            // 44
            R.layout.keyguard_clock_miui2,          // 45
            R.layout.keyguard_clock_ios2,           // 46
            R.layout.keyguard_clock_ios3,           // 47
            R.layout.keyguard_clock_ios4,           // 48
            R.layout.keyguard_clock_ios5,           // 49
            R.layout.keyguard_clock_ios6,           // 50
            R.layout.keyguard_clock_ios7,           // 51
            R.layout.keyguard_clock_ios8,           // 52
            R.layout.keyguard_clock_ios9,           // 53
            R.layout.keyguard_clock_ios10,          // 54
            R.layout.keyguard_clock_ios11,          // 55
            R.layout.keyguard_clock_ios12,          // 56
            R.layout.keyguard_clock_ios13,          // 57
            R.layout.keyguard_clock_ios14,          // 58
            R.layout.keyguard_clock_ios15,          // 59
            R.layout.keyguard_clock_ios16,          // 60
            R.layout.keyguard_clock_ios17,          // 61
            R.layout.keyguard_clock_ios18,          // 62
            R.layout.keyguard_clock_ios19,          // 63
            R.layout.keyguard_clock_big1,           // 64
            R.layout.keyguard_clock_big2,           // 65
            R.layout.keyguard_clock_big3,           // 66
            R.layout.keyguard_clock_big4,           // 67
            R.layout.keyguard_clock_sweet,          // 68
            R.layout.keyguard_clock_pixel,          // 69
            R.layout.keyguard_clock_samurai,        // 70
            R.layout.keyguard_clock_gateway,        // 71
            R.layout.keyguard_clock_tall,           // 72
            R.layout.keyguard_clock_gobold,         // 73
            R.layout.keyguard_clock_gobold2,        // 74
            R.layout.keyguard_clock_delirium,       // 75
            R.layout.keyguard_clock_deliriumdual,   // 76
            R.layout.keyguard_clock_skewrom,        // 77
            R.layout.keyguard_clock_skewrom2,       // 78
            R.layout.keyguard_clock_taller,         // 79
            R.layout.keyguard_clock_taller2,        // 80
            R.layout.keyguard_clock_taller3,        // 81
            R.layout.keyguard_clock_modak,          // 82
            R.layout.keyguard_clock_galada,         // 83
            R.layout.keyguard_clock_cos1,           // 84
            R.layout.keyguard_clock_cos2,           // 85
            R.layout.keyguard_clock_neu,            // 86
            R.layout.keyguard_clock_neu2,           // 87
            R.layout.keyguard_clock_neu3,           // 88
            R.layout.keyguard_clock_badeen,         // 89
            R.layout.keyguard_clock_space_age,      // 90
            R.layout.keyguard_clock_polyline,       // 91
            R.layout.keyguard_clock_oppo,           // 92
            R.layout.keyguard_clock_hyperos_art2,    // 93
            R.layout.keyguard_clock_hyperos_art4,    // 94
            R.layout.keyguard_clock_hyperos_duo,     // 95
            R.layout.keyguard_clock_hyperos_tall,    // 96
            R.layout.keyguard_clock_hyperos_tall2,   // 97
            R.layout.keyguard_clock_hyperos_tall3,   // 98
            R.layout.keyguard_clock_hyperos_stack,   // 99
            R.layout.keyguard_clock_graffiti_art,    // 100
            R.layout.keyguard_clock_graffiti_stack,  // 101
            R.layout.keyguard_clock_graffiti_compact, // 102
            R.layout.keyguard_clock_maker,
        )

        private val NO_COLOR_CLOCKS = hashSetOf(1, 2, 25, 26)
        private val NO_HORIZONTAL_MARGIN_CLOCKS = hashSetOf(45, 73, 74)

        @JvmField val CLOCK_STYLE_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE
        @JvmField val CLOCK_DESIGN_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_DESIGN
        private const val CLOCK_MAKER_WEATHER_KEY = "custom_clock_weather"
        @JvmField val CLOCK_COLOR_MODE_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_COLOR_MODE
        @JvmField val CLOCK_CUSTOM_COLOR_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_CUSTOM_COLOR
        @JvmField val CLOCK_TEXT_OPACITY_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_OPACITY
        @JvmField val CLOCK_SHADOW_ENABLED_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_SHADOW_ENABLED
        @JvmField val CLOCK_SHADOW_COLOR_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_SHADOW_COLOR
        @JvmField val CLOCK_SHADOW_SOFTNESS_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_SHADOW_SOFTNESS
        @JvmField val CLOCK_SHADOW_OPACITY_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_SHADOW_OPACITY
        @JvmField val CLOCK_FRAME_MARGIN_TOP_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_MARGIN_TOP
        @JvmField val CLOCK_SIZE_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_SIZE
        @JvmField val CLOCK_AOD_ANIM_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_AOD_ANIM
        @JvmField val CLOCK_ALBUM_ART_COLOR_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_ALBUM_ART_COLOR
        @JvmField val CLOCK_WOBBLE_ON_CHARGE_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_WOBBLE_ON_CHARGE
        @JvmField val CLOCK_FRAME_MARGIN_START_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_MARGIN_START
        const val HOME_CLOCK_SIZE_KEY = "lock_screen_home_clock_size"
        const val HOME_CLOCK_MARGIN_TOP_KEY = "lock_screen_home_clock_margin_top"
        const val HOME_CLOCK_MARGIN_START_KEY = "lock_screen_home_clock_margin_start"
        @JvmField val CLOCK_GRADIENT_ENABLED_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_GRADIENT_ENABLED
        @JvmField val CLOCK_GRADIENT_COLOR_START_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_GRADIENT_COLOR_START
        @JvmField val CLOCK_GRADIENT_COLOR_END_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_GRADIENT_COLOR_END
        @JvmField val CLOCK_GRADIENT_ANCHOR_Y_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_GRADIENT_ANCHOR_Y
        @JvmField val CLOCK_GRADIENT_RADIUS_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_GRADIENT_RADIUS
        @JvmField val CLOCK_TILT_X_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_TILT_X
        @JvmField val CLOCK_TILT_Y_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_TILT_Y
        @JvmField val CLOCK_ROTATION_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_ROTATION
        @JvmField val CLOCK_HYPER_BLUR_KEY: String = Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_HYPER_BLUR

        private val CLOCK_TUNING_KEYS = arrayOf(
            CLOCK_STYLE_KEY, CLOCK_DESIGN_KEY, CLOCK_MAKER_WEATHER_KEY, ClockDigits.SETTING,
            CLOCK_COLOR_MODE_KEY, CLOCK_CUSTOM_COLOR_KEY, CLOCK_TEXT_OPACITY_KEY,
            CLOCK_SHADOW_ENABLED_KEY, CLOCK_SHADOW_COLOR_KEY, CLOCK_SHADOW_SOFTNESS_KEY,
            CLOCK_SHADOW_OPACITY_KEY, CLOCK_FRAME_MARGIN_TOP_KEY, CLOCK_SIZE_KEY,
            CLOCK_AOD_ANIM_KEY, CLOCK_ALBUM_ART_COLOR_KEY, CLOCK_WOBBLE_ON_CHARGE_KEY,
            CLOCK_FRAME_MARGIN_START_KEY, HOME_CLOCK_SIZE_KEY, HOME_CLOCK_MARGIN_TOP_KEY,
            HOME_CLOCK_MARGIN_START_KEY, CLOCK_GRADIENT_ENABLED_KEY, CLOCK_GRADIENT_COLOR_START_KEY,
            CLOCK_GRADIENT_COLOR_END_KEY, CLOCK_GRADIENT_ANCHOR_Y_KEY, CLOCK_GRADIENT_RADIUS_KEY,
            CLOCK_TILT_X_KEY, CLOCK_TILT_Y_KEY, CLOCK_ROTATION_KEY, CLOCK_HYPER_BLUR_KEY,
            ClockParallaxSettings.ENABLED, ClockParallaxSettings.INTENSITY,
            ClockGlass.ENABLED_SETTING, ClockGlass.INTENSITY_SETTING, ClockGlass.LIFT_SETTING,
            ClockGlassStyle.SETTING, ClockGlassStyle.DATE_SETTING, ClockGlassStyle.SEPARATE_DATE_SETTING,
        )
        private val STOCK_PRESETS = listOf(800f to 30f, 700f to 55f, 600f to 80f,
            500f to 100f, 400f to 108f, 300f to 116f, 200f to 120f)
        private val clockFontExecutor = Executors.newSingleThreadExecutor()

        const val COLOR_MODE_DEFAULT = "default"
        const val COLOR_MODE_ACCENT = "accent"
        const val COLOR_MODE_CUSTOM = "custom"

        private const val DOZE_PULSE_ACTION = "com.android.systemui.doze.pulse"

        private const val DEFAULT_STYLE = 0
        private const val DEFAULT_OPACITY = 100
        private const val DEFAULT_SHADOW_COLOR = Color.BLACK
        private const val DEFAULT_SHADOW_SOFTNESS = 8
        private const val DEFAULT_SHADOW_OPACITY = 35
        private const val DEFAULT_MARGIN_TOP = 15
        private const val DEFAULT_CUSTOM_COLOR = Color.WHITE
        private const val DEFAULT_CLOCK_SIZE = 100
        private const val MIN_CLOCK_SIZE = 50
        private const val MAX_CLOCK_SIZE = 150
        private const val DEFAULT_MARGIN_START = 0

        private const val DEFAULT_TILT = 0
        private const val MIN_TILT = -75
        private const val MAX_TILT = 75

        private const val DEFAULT_ROTATION = 0
        private const val MIN_ROTATION = -180
        private const val MAX_ROTATION = 180

        private val DEFAULT_GRADIENT_COLOR_START = 0xFF00E5FF.toInt()
        private val DEFAULT_GRADIENT_COLOR_END = 0xFFFF2DAA.toInt()
        private const val DEFAULT_GRADIENT_ANCHOR_Y = 50
        private const val DEFAULT_GRADIENT_RADIUS = 100
        private const val MIN_GRADIENT_RADIUS = 25
        private const val MAX_GRADIENT_RADIUS = 200

        private const val AOD_UPDATE_INTERVAL_MILLIS = 60_000L
        private const val UPDATE_INTERVAL_MILLIS = 15_000L

        private const val BURN_IN_PROTECTION_INTERVAL = 10_000L
        private const val BURN_IN_PROTECTION_MAX_SHIFT = 4

        private const val AOD_SCALE_DOWN = ClockViewTransform.AOD_SCALE_DOWN
        private const val AOD_ANIM_OUT_MS = ClockViewTransform.AOD_ANIM_OUT_MS
        private const val AOD_ANIM_IN_MS = ClockViewTransform.AOD_ANIM_IN_MS

        private const val WOBBLE_RISE_DP = 26f
        private const val WOBBLE_DURATION_MS = 1150L
        private const val WOBBLE_GLOW_MAX_RADIUS = 28f
        private const val WOBBLE_GLOW_MAX_DY = 6f
        private const val WOBBLE_GLOW_MAX_ALPHA = 160
    }
}
