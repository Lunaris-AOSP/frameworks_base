/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.RenderNode
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.LayoutInflater
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.window.InputTransferToken
import android.window.SurfaceSyncGroup
import com.android.systemui.Dependency
import com.android.systemui.res.R
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.InteractionBoost
import org.lunaris.clockmaker.ClockViewTransform
import org.lunaris.clockmaker.DynamicClockSettings
import java.lang.ref.WeakReference
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil

class HomeClockProvider : ContentProvider() {
    private val settingsObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            resetUnlock()
            sessions.toList().filterNot { isEnabled(it.context, it.uid) }.forEach { it.close() }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    resetUnlock()
                    sessions.toList().forEach { it.setScreenInteractive(false) }
                }
                Intent.ACTION_SCREEN_ON -> sessions.toList().forEach { it.setScreenInteractive(true) }
                else -> {
                    transition?.takeUnless { isEnabled(it.context, it.uid) }?.let { resetUnlock() }
                    if (intent.action == Intent.ACTION_USER_SWITCHED) viewports.clear()
                    sessions.toList().filterNot { isEnabled(it.context, it.uid) }
                        .forEach { it.close() }
                }
            }
        }
    }

    override fun onCreate(): Boolean {
        val context = context ?: return false
        providerContext = context
        for (key in arrayOf(ENABLED_SETTING, ClockStyle.CLOCK_STYLE_KEY, DynamicClockSettings.HOME_SMALL,
            DynamicClockSettings.ENABLED, DynamicClockSettings.LAYOUTS, ClockStyle.HOME_CLOCK_SIZE_KEY,
            ClockStyle.HOME_CLOCK_MARGIN_TOP_KEY, ClockStyle.HOME_CLOCK_MARGIN_START_KEY,
            Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE, Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK)) {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(key), false, settingsObserver, UserHandle.USER_ALL,
            )
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_PREFERRED_ACTIVITY_CHANGED)
            addAction(Intent.ACTION_USER_SWITCHED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }, Context.RECEIVER_NOT_EXPORTED)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        context.enforceCallingPermission(Manifest.permission.STATUS_BAR_SERVICE, "Home clock")
        val uid = Binder.getCallingUid()
        val request = extras?.let(::Bundle) ?: return null
        val lifetime = request.getBinder("lifecycle") ?: return null
        val task = FutureTask<Bundle?> {
            when (method) {
                "render" -> render(context, uid, lifetime, request)
                "attached" -> {
                    val session = sessions.firstOrNull {
                        it.uid == uid && it.lifetime == lifetime &&
                            it.renderer == request.getBinder("renderer")
                    }
                    Bundle().apply { putBoolean("attached", session?.markAttached() == true) }
                }
                "unlock" -> {
                    sessions.firstOrNull {
                        it.uid == uid && it.lifetime == lifetime &&
                            it.renderer == request.getBinder("renderer")
                    }?.setUnlockTiming(request)
                    null
                }
                "visibility" -> {
                    sessions.firstOrNull {
                        it.uid == uid && it.lifetime == lifetime &&
                            it.renderer == request.getBinder("renderer")
                    }?.setContentVisible(request.getBoolean("visible", true))
                    null
                }
                "release" -> {
                    val releasing = sessions.toList().filter {
                        it.uid == uid && it.lifetime == lifetime
                    }
                    if (request.getBoolean("cancel_unlock") && releasing.isNotEmpty() &&
                        transition?.uid == uid) resetUnlock()
                    releasing.forEach { it.close() }
                    null
                }
                else -> null
            }
        }
        if (Looper.myLooper() == main.looper) task.run() else main.post(task)
        return try {
            task.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            main.removeCallbacks(task)
            main.post {
                sessions.toList().filter { it.uid == uid && it.lifetime == lifetime }
                    .forEach { it.close() }
            }
            Log.w(TAG, "Cannot host the home clock", e)
            null
        }
    }

    private fun render(context: Context, uid: Int, lifetime: IBinder, request: Bundle): Bundle? {
        if (!isEnabled(context, uid)) return null
        val displayId = request.getInt("display_id")
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
            ?: return null
        val size = Point().also(display::getRealSize)
        val width = request.getInt("width")
        val height = request.getInt("height")
        if (width !in 1..size.x || height !in 1..size.y) return null
        val hostToken = request.getBinder("host_token") ?: return null
        sessions.toList().filter { it.uid == uid && it.displayId == displayId }
            .forEach { it.close() }
        val userContext = context.createContextAsUser(
            UserHandle.of(UserHandle.getUserId(uid)), Context.CONTEXT_INCLUDE_CODE,
        )
        val viewContext = ContextThemeWrapper(
            userContext.createDisplayContext(display), context.applicationInfo.theme,
        )
        val host = SurfaceControlViewHost(
            viewContext, display, InputTransferToken(hostToken), TAG,
        )
        var session: Session? = null
        return try {
            val root = FrameLayout(viewContext).apply {
                clipChildren = false
                clipToPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val clock = (LayoutInflater.from(viewContext)
                .inflate(R.layout.keyguard_clock_style, root, false) as ClockStyle).apply {
                isHomeScreenClock = true
            }
            val screenX = request.getInt("screen_x")
            val screenY = request.getInt("screen_y")
            val viewport = HomeClockViewport(uid, displayId, width, height, screenX, screenY)
            viewports[displayId] = viewport
            root.addView(clock, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (viewContext.resources.getDimensionPixelSize(R.dimen.status_bar_height)
                    * 1.25f).toInt() - screenY
            })
            val created = Session(viewContext, uid, lifetime, viewport, host, root, clock)
            session = created
            lifetime.linkToDeath(created, 0)
            sessions.add(created)
            created.setScreenInteractive(
                viewContext.getSystemService(PowerManager::class.java)?.isInteractive != false,
            )
            host.setView(root, WindowManager.LayoutParams(
                width, height, WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ))
            created.surfacePackage = host.surfacePackage
            created.setContentVisible(request.getBoolean("visible", true))
            created.setUnlockTiming(request)
            Bundle().apply {
                putParcelable("surface_package", created.surfacePackage)
                putBinder("renderer", created.renderer)
            }
        } catch (e: Exception) {
            session?.close() ?: host.release()
            Log.w(TAG, "Cannot render the home clock", e)
            null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = 0

    private class ClockSnapshot(
        val view: WeakReference<ClockStyle>,
        val bounds: RectF,
        val layoutBounds: RectF?,
        val morph: ClockStyle.HomeClockMorph?,
        val global: Matrix,
        val animationMatrix: Matrix?,
        val alpha: Float,
        val screenX: Int,
        val screenY: Int,
    ) {
        fun capturePicture(): HomeClockPicture? = runCatching {
            val clock = view.get() ?: return@runCatching null
            val size = Point().also { clock.display?.getRealSize(it) }
            if (size.x <= 0 || size.y <= 0) return@runCatching null
            val padded = RectF(bounds).apply {
                inset(-clock.transitionPadding, -clock.transitionPadding)
            }
            val scale = minOf(1f, size.x / padded.width(), size.y / padded.height())
            val width = ceil(padded.width() * scale).toInt().coerceAtLeast(1)
            val height = ceil(padded.height() * scale).toInt().coerceAtLeast(1)
            val node = RenderNode("HomeClockPicture").apply { setPosition(0, 0, width, height) }
            val canvas = node.beginRecording(width, height)
            try {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(),
                    (alpha.coerceIn(0f, 1f) * 255).toInt())
                canvas.scale(scale, scale)
                canvas.translate(-padded.left, -padded.top)
                canvas.concat(global)
                clock.draw(canvas)
                canvas.restore()
            } finally {
                node.endRecording()
            }
            try {
                val content = RectF(bounds).apply {
                    offset(-padded.left, -padded.top)
                    left *= scale
                    top *= scale
                    right *= scale
                    bottom *= scale
                }
                HomeClockPicture(HardwareRenderer.createHardwareBitmap(node, width, height), content)
            } finally {
                node.discardDisplayList()
            }
        }.onFailure { Log.w(TAG, "Cannot retain the lock clock", it) }.getOrNull()

        companion object {
            fun capture(clock: ClockStyle, screenX: Int = 0, screenY: Int = 0,
                alpha: Float = clock.alpha): ClockSnapshot? {
                val bounds = clock.clockBounds() ?: return null
                bounds.offset(screenX.toFloat(), screenY.toFloat())
                val layoutBounds = clock.clockLayoutBounds()?.apply {
                    offset(screenX.toFloat(), screenY.toFloat())
                }
                val global = Matrix()
                if (!ClockViewTransform.toGlobal(clock, global)) return null
                global.apply {
                    postTranslate(screenX.toFloat(), screenY.toFloat())
                }
                val animation = clock.animationMatrix?.let(::Matrix)
                return ClockSnapshot(WeakReference(clock), bounds, layoutBounds, clock.captureHomeClockMorph(),
                    global, animation, alpha,
                    screenX, screenY)
            }
        }
    }

    private class ClockTransform(val snapshot: ClockSnapshot) {
        val contentBounds = RectF()
        private var changed = false
        private val inverseLocal = Matrix()
        private val parent = Matrix()
        private val inverseParent = Matrix()
        private val transform = Matrix()
        private val appliedMatrix = Matrix()

        fun apply(bounds: RectF, alpha: Float, useLayoutBounds: Boolean = false): Boolean {
            val view = snapshot.view.get()?.takeIf { it.isAttachedToWindow } ?: return false
            if (!view.matrix.invert(inverseLocal)) return false
            if (!ClockViewTransform.toGlobal(view, parent, view)) return false
            parent.postTranslate(snapshot.screenX.toFloat(), snapshot.screenY.toFloat())
            parent.preConcat(inverseLocal)
            if (!parent.invert(inverseParent)) return false
            val content = if (useLayoutBounds) view.clockLayoutBounds(snapshot.global, contentBounds)
                else view.clockBounds(snapshot.global, contentBounds)
            if (content == null) return false
            transform.setRectToRect(contentBounds, bounds, Matrix.ScaleToFit.FILL)
            transform.preConcat(snapshot.global)
            transform.preConcat(inverseLocal)
            transform.postConcat(inverseParent)
            if (!changed || appliedMatrix != transform) {
                view.animationMatrix = transform
                appliedMatrix.set(transform)
            }
            view.alpha = snapshot.alpha * alpha
            changed = true
            return true
        }

        fun restore() {
            if (!changed) return
            snapshot.view.get()?.let {
                it.animationMatrix = snapshot.animationMatrix
                it.alpha = snapshot.alpha
            }
            changed = false
        }
    }

    private data class UnlockTiming(
        val unlocked: Boolean,
        val startTime: Long,
        val endTime: Long,
        val fromLauncher: Boolean,
    )

    private class UnlockTransition(
        val context: Context,
        val uid: Int,
        val clocks: MutableMap<Int, ClockTransition>,
    ) {
        val preparedTime = SystemClock.uptimeMillis()
        var amount = 0f
        var started = false
        var manual = false
        var keyguardGone = false
        var timing: UnlockTiming? = null
    }

    private class ClockTransition(val source: ClockSnapshot, val picture: HomeClockPicture) {
        val from = RectF(source.bounds)
        val bounds = RectF(from)
        private val layoutFrom = RectF(source.layoutBounds ?: from)
        val layoutBounds = RectF(layoutFrom)
        var progress = 0f
            private set
        var overlay: HomeClockTransitionOverlay? = null
        var home: Session? = null
        var pendingHome: Session? = null
        private var destination: RectF? = null
        private var layoutDestination: RectF? = null
        val hasDestination: Boolean
            get() = destination != null
        private var destinationFromLauncher = false
        private val anchor = RectF(from)
        private val layoutAnchor = RectF(layoutBounds)
        private var anchorProgress = 0f
        private var originCaptured = false
        private var overlayTarget: ClockTransform? = null
        private var overlayMorphs = false
        private var bridgePicture = picture
        private var bridgeIsHome = false
        private val pictures = mutableListOf(picture)
        private val sourceTransform = ClockTransform(source)
        private var sourceHidden = false
        private var closed = false
        private var sourceTree: ViewTreeObserver? = null
        private val sourcePreDraw = ViewTreeObserver.OnPreDrawListener {
            if (!closed) {
                if (sourceHidden) {
                    source.view.get()?.let { it.alpha = 0f }
                } else {
                    transition?.let {
                        updateBounds(it.amount)
                        updateSource(it)
                    }
                }
            }
            true
        }

        init {
            sourceTree = source.view.get()?.viewTreeObserver?.takeIf { it.isAlive }?.also {
                it.addOnPreDrawListener(sourcePreDraw)
            }
        }

        fun updateSource(request: UnlockTransition) {
            source.view.get()?.setGlassTransitionAmount(unlockBlurAmount(request))
            if (!closed && !sourceHidden && request.started) sourceTransform.apply(bounds, 1f)
        }

        fun setDestination(snapshot: ClockSnapshot, request: UnlockTransition, fromLauncher: Boolean) {
            if (destinationFromLauncher && !fromLauncher) return
            destinationFromLauncher = fromLauncher
            val value = snapshot.bounds
            val previous = destination
            if (previous != null && abs(previous.left - value.left) < 0.5f &&
                abs(previous.top - value.top) < 0.5f &&
                abs(previous.right - value.right) < 0.5f &&
                abs(previous.bottom - value.bottom) < 0.5f && layoutDestination == snapshot.layoutBounds) return
            if (previous != null && request.started) {
                updateBounds(request.amount)
                anchor.set(bounds)
                layoutAnchor.set(layoutBounds)
                anchorProgress = motion.getInterpolation(request.amount)
            }
            destination = RectF(value)
            layoutDestination = snapshot.layoutBounds?.let(::RectF)
            updateBounds(request.amount)
        }

        fun updateBounds(amount: Float) {
            if (pendingHome != null) return
            val to = destination ?: return
            progress = motion.getInterpolation(amount)
            val returningToOrigin = progress < anchorProgress
            val fraction = if (returningToOrigin) progress / anchorProgress
                else if (anchorProgress >= 1f) 1f else
                ((progress - anchorProgress) / (1f - anchorProgress)).coerceIn(0f, 1f)
            interpolateBounds(bounds, if (returningToOrigin) from else anchor,
                if (returningToOrigin) anchor else to, fraction)
            layoutDestination?.let { target ->
                interpolateBounds(layoutBounds,
                    if (returningToOrigin) layoutFrom else layoutAnchor,
                    if (returningToOrigin) layoutAnchor else target, fraction)
            }
        }

        private fun interpolateBounds(result: RectF, start: RectF, end: RectF, fraction: Float) {
            result.set(
                start.left + (end.left - start.left) * fraction,
                start.top + (end.top - start.top) * fraction,
                start.right + (end.right - start.right) * fraction,
                start.bottom + (end.bottom - start.bottom) * fraction,
            )
        }

        fun updateOverlay(request: UnlockTransition) {
            if (closed || transition !== request) return
            val layer = overlay ?: return
            layer.alpha = getShadeAlpha(layer.displayId)
            layer.clock.setGlassTransitionAmount(unlockBlurAmount(request))
            if (overlayTarget == null) {
                ClockSnapshot.capture(layer.clock, alpha = 1f)?.let {
                    overlayTarget = ClockTransform(it)
                    setDestination(it, request, false)
                    overlayMorphs = source.layoutBounds != null && it.layoutBounds != null &&
                        source.morph?.let(layer.clock::prepareHomeClockTransition) == true
                }
            }
            if (overlayMorphs) layer.clock.setHomeClockTransition(progress)
            val appearance = if (overlayTarget == null) 0f else if (overlayMorphs || bridgeIsHome) 1f else
                (request.amount / APPEARANCE_HANDOFF).coerceIn(0f, 1f)
            val previousAlpha = layer.pictureAlpha
            val changedBounds = layer.bounds != bounds
            layer.bounds.set(bounds)
            val target = overlayTarget
            val alpha = if (overlayMorphs) source.alpha + (1f - source.alpha) * progress else appearance
            if (target != null && target.apply(if (overlayMorphs) layoutBounds else bounds, alpha, overlayMorphs)) {
                layer.pictureAlpha = 1f - appearance
                layer.clockPadding = layer.clock.transitionPadding * maxOf(
                    bounds.width() / target.contentBounds.width(),
                    bounds.height() / target.contentBounds.height(),
                )
            } else {
                layer.clock.alpha = 0f
                layer.pictureAlpha = 1f
            }
            if (changedBounds || previousAlpha != layer.pictureAlpha) layer.invalidate()
        }

        fun showOverlay(request: UnlockTransition, displayId: Int): Boolean {
            val display = request.context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(displayId) ?: return false
            val size = Point().also(display::getRealSize)
            val viewport = viewports[displayId]?.takeIf { it.uid == request.uid &&
                it.width <= size.x && it.height <= size.y } ?: HomeClockViewport(
                request.uid, displayId, size.x, size.y, 0, 0,
            )
            return try {
                val layer = HomeClockTransitionOverlay(
                    request.context, display, bridgePicture, bounds, viewport,
                    sourceBounds = {
                        if (!originCaptured && !request.started) source.view.get()?.clockBounds()
                        else RectF(bounds)
                    },
                    onPrepare = { view, start, committed ->
                        if (transition !== request || overlay !== view) false else {
                            if (!originCaptured) {
                                originCaptured = true
                                if (!request.started) {
                                    from.set(start)
                                    source.view.get()?.clockLayoutBounds()?.let {
                                        layoutFrom.set(it)
                                        layoutBounds.set(it)
                                    }
                                }
                                anchor.set(start)
                                layoutAnchor.set(layoutBounds)
                                anchorProgress = motion.getInterpolation(request.amount)
                                bounds.set(start)
                            }
                            updateOverlay(request)
                            syncUnlock(request)
                            synchronizeSource(view, committed)
                        }
                    },
                    onFrame = {
                        updateOverlay(request)
                        if (!request.started) syncUnlock(request)
                    },
                    onReady = { view, _ ->
                        if (transition === request && overlay === view) {
                            hideSource()
                            if (request.manual && request.clocks.values.all {
                                it.hasDestination
                            }) request.started = true
                            startPlayback(request)
                            syncUnlock(request)
                        }
                    },
                )
                overlay = layer
                layer.show()
                true
            } catch (e: Exception) {
                closeOverlay()
                Log.w(TAG, "Cannot carry the clock across unlock", e)
                false
            }
        }

        private fun synchronizeSource(layer: HomeClockTransitionOverlay, committed: Runnable): Boolean {
            val view = source.view.get()?.takeIf { it.isShown && it.isHardwareAccelerated }
                ?: return false
            if (sourceHidden || !layer.isHardwareAccelerated) return false
            val sync = SurfaceSyncGroup("HomeClockEnter")
            return try {
                if (!sync.add(layer.rootSurfaceControl, null) ||
                    !sync.add(view.rootSurfaceControl) { hideSource() }) false else {
                    sync.addSyncCompleteCallback({ main.post(it) }, committed)
                    true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot synchronize the lock clock handoff", e)
                false
            } finally {
                sync.markSyncReady()
            }
        }

        private fun hideSource() {
            if (sourceHidden) return
            val view = source.view.get() ?: return
            sourceTransform.restore()
            sourceHidden = true
            view.alpha = 0f
            view.invalidate()
        }

        fun homeClosed(session: Session, request: UnlockTransition) {
            if (home !== session && pendingHome !== session) return
            session.capturePicture()?.let {
                bridgePicture = it
                bridgeIsHome = true
                pictures.add(it)
            }
            home = null
            pendingHome = null
            closeOverlay()
            if (transition === request && isEnabled(request.context, request.uid) &&
                isHomeUnderneath(request.context)) showOverlay(request, session.displayId)
        }

        fun closeOverlay() {
            overlay?.clock?.resetHomeClockTransition()
            overlay?.close()
            overlay = null
            overlayTarget?.restore()
            overlayTarget = null
            overlayMorphs = false
        }

        fun close(animateSourceBlur: Boolean = false, restoreSourceOnDraw: Boolean = false) {
            if (closed) return
            closed = true
            sourceTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(sourcePreDraw)
            sourceTree = null
            sourceTransform.restore()
            val view = source.view.get()
            if (sourceHidden) view?.alpha = source.alpha
            view?.restoreGlassBlur(animateSourceBlur)
            sourceHidden = false
            pendingHome = null
            home = null
            val layer = overlay
            val target = overlayTarget
            overlay = null
            overlayTarget = null
            overlayMorphs = false
            var released = false
            lateinit var release: Runnable
            release = Runnable {
                if (!released) {
                    released = true
                    main.removeCallbacks(release)
                    layer?.close()
                    target?.restore()
                    pictures.forEach { it.bitmap.recycle() }
                    pictures.clear()
                }
            }
            if (restoreSourceOnDraw && layer?.ready == true && view?.isShown == true &&
                layer.hideOnDraw(view, release)) {
                main.postDelayed(release, 250L)
                view.invalidate()
            } else release.run()
        }
    }

    private class Session(
        val context: Context,
        val uid: Int,
        val lifetime: IBinder,
        val viewport: HomeClockViewport,
        private val host: SurfaceControlViewHost,
        private val root: FrameLayout,
        private val clock: ClockStyle,
    ) : IBinder.DeathRecipient {
        val displayId = viewport.displayId
        private val shadeAlpha: Float get() = getShadeAlpha(displayId)
        val renderer = Binder()
        var surfacePackage: SurfaceControlViewHost.SurfacePackage? = null
        private val clockAlpha = clock.alpha
        private var request: UnlockTransition? = null
        private var target: ClockTransform? = null
        private var morphs = false
        private var attached = false
        private var closed = false
        private var interactive = true
        private var contentVisible = true
        private val shadeTransaction = SurfaceControl.Transaction()
        private var appliedShadeAlpha = Float.NaN
        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            syncTransition()
            true
        }

        init {
            root.viewTreeObserver.addOnPreDrawListener(preDraw)
            syncTransition()
        }

        fun markAttached(): Boolean {
            if (closed) return false
            if (!isEnabled(context, uid)) {
                close()
                return false
            }
            attached = true
            syncTransition()
            root.invalidate()
            return true
        }

        fun setUnlockTiming(bundle: Bundle) {
            val current = transition?.takeIf { it.uid == uid } ?: return
            val duration = bundle.getLong("duration")
            val startTime = bundle.getLong("start_time")
            val now = SystemClock.uptimeMillis()
            if (duration !in 1L..50000L || startTime < current.preparedTime - 32 ||
                startTime > now + 20000) return
            setTiming(current, UnlockTiming(
                bundle.getBoolean("unlocked"), startTime, startTime + duration, true,
            ))
        }

        fun setScreenInteractive(interactive: Boolean) {
            this.interactive = interactive
            if (!interactive) resetTransition()
            updateVisibility()
        }

        fun setContentVisible(visible: Boolean) {
            contentVisible = visible
            updateVisibility()
            if (!visible) clock.restoreGlassBlur(animate = false)
        }

        private fun updateVisibility() {
            surfacePackage?.surfaceControl?.takeIf { it.isValid }?.let {
                if (appliedShadeAlpha != shadeAlpha) {
                    shadeTransaction.setAlpha(it, shadeAlpha).apply()
                    appliedShadeAlpha = shadeAlpha
                }
            }
            root.visibility = if (interactive && contentVisible && shadeAlpha > 0f)
                View.VISIBLE else View.INVISIBLE
        }

        fun refreshVisibility() {
            updateVisibility()
            if (shadeAlpha <= 0f) clock.restoreGlassBlur(animate = false)
        }

        fun syncTransition() {
            if (closed) return
            if (request !== transition) {
                resetTransition()
                request = transition
            }
            val current = request ?: return
            clock.setGlassTransitionAmount(unlockBlurAmount(current))
            val movement = current.clocks[displayId] ?: return
            if (target == null) {
                clock.alpha = 0f
                ClockSnapshot.capture(clock, viewport.x, viewport.y, clockAlpha)?.let {
                    target = ClockTransform(it)
                    movement.setDestination(it, current, true)
                    morphs = movement.source.layoutBounds != null && it.layoutBounds != null &&
                        movement.source.morph?.let(clock::prepareHomeClockTransition) == true
                }
            }
            if (morphs) clock.setHomeClockTransition(movement.progress)
            val visible = movement.home === this || movement.pendingHome === this
            val alpha = if (visible) {
                if (morphs) movement.source.alpha + (1f - movement.source.alpha) * movement.progress else 1f
            } else 0f
            target?.apply(if (morphs) movement.layoutBounds else movement.bounds, alpha, morphs)
            if (attached && interactive && contentVisible && shadeAlpha > 0f &&
                current.started && current.keyguardGone &&
                current.timing?.unlocked != false &&
                current.amount >= APPEARANCE_HANDOFF && movement.home == null &&
                movement.pendingHome == null && movement.overlay?.ready == true &&
                target != null && isHomeUnderneath(context)) {
                handoff(current, movement)
            }
        }

        private fun handoff(current: UnlockTransition, movement: ClockTransition) {
            val layer = movement.overlay ?: return
            movement.pendingHome = this
            movement.updateOverlay(current)
            val alpha = if (morphs) movement.source.alpha + (1f - movement.source.alpha) * movement.progress else 1f
            if (target?.apply(if (morphs) movement.layoutBounds else movement.bounds, alpha, morphs) != true) {
                movement.pendingHome = null
                return
            }
            val committed = Runnable {
                if (!closed && transition === current && movement.pendingHome === this) {
                    movement.pendingHome = null
                    movement.home = this
                    movement.closeOverlay()
                    syncUnlock(current)
                    root.invalidate()
                }
            }
            if (!layer.hideOnDraw(root, committed)) {
                if (root.isHardwareAccelerated) root.viewTreeObserver.registerFrameCommitCallback(committed)
                else root.post(committed)
            }
            root.invalidate()
        }

        fun capturePicture(): HomeClockPicture? =
            ClockSnapshot.capture(clock, viewport.x, viewport.y, clockAlpha)?.capturePicture()

        fun resetTransition(animateBlur: Boolean = false) {
            clock.resetHomeClockTransition()
            morphs = false
            target?.restore()
            target = null
            request = null
            clock.alpha = clockAlpha
            clock.restoreGlassBlur(animateBlur)
        }

        override fun binderDied() {
            main.post { close() }
        }

        fun close() {
            if (closed) return
            closed = true
            request?.let { it.clocks[displayId]?.homeClosed(this, it) }
            resetTransition()
            root.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
            sessions.remove(this)
            runCatching { lifetime.unlinkToDeath(this, 0) }
            surfacePackage?.release()
            surfacePackage = null
            shadeTransaction.close()
            host.release()
        }
    }

    companion object {
        const val ENABLED_SETTING = "lock_screen_home_clock_enabled"
        private const val TAG = "HomeClockProvider"
        private const val LAUNCHER_PACKAGE = "com.android.launcher3"
        private const val APPEARANCE_HANDOFF = 0.3f
        private const val UNLOCK_DURATION = 633L
        private const val GLASS_UNLOCK_BOOST_DURATION_MS = 1000
        private val main = Handler(Looper.getMainLooper())
        private val sessions = mutableSetOf<Session>()
        private val viewports = mutableMapOf<Int, HomeClockViewport>()
        private val motion = PathInterpolator(0.2f, 0f, 0f, 1f)
        private var providerContext: Context? = null
        private var keyguardStateController: KeyguardStateController? = null
        private var transition: UnlockTransition? = null
        private var shadeAlpha = 1f
        private var shadeDisplayId = Display.DEFAULT_DISPLAY
        private var animator: ValueAnimator? = null
        private var observingTasks = false
        private val timeout = Runnable { resetUnlock() }
        private val taskCallback: TaskStackChangeListener = object : TaskStackChangeListener {
            override fun onTaskStackChanged(): Unit = onMain {
                transition?.let {
                    if (!isHomeUnderneath(it.context)) resetUnlock()
                }
            }
        }
        private val keyguardCallback: KeyguardStateController.Callback = object : KeyguardStateController.Callback {
            override fun onKeyguardGoingAwayChanged() {
                if (keyguardStateController?.isKeyguardGoingAway == true) startUnlockAnimation()
            }

            override fun onKeyguardShowingChanged() {
                val controller = keyguardStateController ?: return
                if (controller.isShowing) {
                    if (controller.isOccluded || transition?.keyguardGone == true) resetUnlock()
                } else if (!controller.isKeyguardFadingAway) {
                    finishUnlock(false)
                }
            }

            override fun onKeyguardFadingAwayChanged() {
                val controller = keyguardStateController ?: return
                if (controller.isKeyguardFadingAway) {
                    if (transition?.timing == null) startUnlockAnimation()
                } else if (!controller.isShowing) {
                    finishUnlock(false)
                }
            }
        }

        fun observeKeyguard(): Unit = onMain {
            if (keyguardStateController == null) {
                runCatching { Dependency.get(KeyguardStateController::class.java) }.getOrNull()?.let {
                    keyguardStateController = it
                    it.addCallback(keyguardCallback)
                }
            }
        }

        private fun isEnabled(context: Context, uid: Int): Boolean = runCatching {
            val user = UserHandle.getUserId(uid)
            if (user != ActivityManager.getCurrentUser()) return@runCatching false
            val userContext = context.createContextAsUser(UserHandle.of(user), 0)
            if (userContext.packageManager.getPackagesForUid(uid)?.contains(LAUNCHER_PACKAGE) != true) {
                return@runCatching false
            }
            val home = userContext.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo
            val clockEnabled = Settings.Secure.getIntForUser(
                userContext.contentResolver, ClockStyle.CLOCK_STYLE_KEY, 0, user,
            ) > 0 || Settings.Secure.getIntForUser(
                userContext.contentResolver, DynamicClockSettings.ENABLED, 0, user,
            ) != 0
            home?.packageName == LAUNCHER_PACKAGE && Settings.Secure.getIntForUser(
                userContext.contentResolver, ENABLED_SETTING, 0, user,
            ) != 0 && clockEnabled
        }.getOrDefault(false)

        private fun isHomeUnderneath(context: Context): Boolean = runCatching {
            val top = ActivityManagerWrapper.getInstance().runningTask?.topActivity
                ?: return@runCatching false
            val home = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo ?: return@runCatching false
            top.packageName == LAUNCHER_PACKAGE && home.packageName == LAUNCHER_PACKAGE &&
                top.className == (home.targetActivity ?: home.name)
        }.getOrDefault(false)

        private fun onMain(action: () -> Unit) {
            if (Looper.myLooper() == main.looper) action() else main.post { action() }
        }

        private fun stopAnimator() {
            val animation = animator
            animator = null
            animation?.cancel()
        }

        private fun resetUnlock() {
            stopAnimator()
            main.removeCallbacks(timeout)
            val current = transition
            transition = null
            if (observingTasks) {
                observingTasks = false
                TaskStackChangeListeners.getInstance().unregisterTaskStackListener(taskCallback)
            }
            val restoreBlur = current?.let { it.keyguardGone && it.amount >= 1f } == true
            sessions.toList().forEach { it.resetTransition(restoreBlur) }
            val restoreSourceBlur = current?.let { it.started && !it.keyguardGone } == true
            current?.let { request ->
                request.clocks.values.forEach { it.close(restoreSourceBlur, !request.keyguardGone) }
            }
        }

        private fun syncUnlock(request: UnlockTransition) {
            if (transition !== request) return
            request.clocks.values.forEach {
                it.updateBounds(request.amount)
                it.updateSource(request)
                it.updateOverlay(request)
            }
            sessions.toList().forEach { it.syncTransition() }
            if (!request.started && request.clocks.values.all { it.hasDestination }) {
                if (request.manual) request.started = true
                else if (animator == null) startPlayback(request)
            }
            if (request.started && request.amount >= 1f && request.keyguardGone &&
                request.clocks.values.all { it.home != null && it.pendingHome == null }) resetUnlock()
        }

        private fun unlockBlurAmount(request: UnlockTransition): Float {
            val progress = (request.amount / APPEARANCE_HANDOFF).coerceIn(0f, 1f)
            return 1f - progress * progress * (3f - 2f * progress)
        }

        private fun getShadeAlpha(displayId: Int): Float =
            if (displayId == shadeDisplayId) shadeAlpha else 1f

        fun setShadeExpansion(displayId: Int, value: Float): Unit = onMain {
            if (!value.isFinite()) return@onMain
            val progress = value.coerceIn(0f, 1f)
            val alpha = 1f - progress * progress * (3f - 2f * progress)
            if (shadeDisplayId == displayId && shadeAlpha == alpha) return@onMain
            shadeDisplayId = displayId
            shadeAlpha = alpha
            sessions.forEach { it.refreshVisibility() }
            transition?.clocks?.values?.forEach {
                it.overlay?.let { layer -> layer.alpha = getShadeAlpha(layer.displayId) }
            }
        }

        private fun setTiming(request: UnlockTransition, timing: UnlockTiming) {
            if (transition !== request) return
            val previous = request.timing
            if (previous?.unlocked == timing.unlocked && !timing.fromLauncher) return
            val now = SystemClock.uptimeMillis()
            val startTime = if (timing.unlocked) {
                previous?.takeIf { it.unlocked }?.startTime ?: minOf(timing.startTime, now)
            } else timing.startTime
            val schedule = timing.copy(startTime = startTime)
            if (previous == schedule) return
            request.timing = schedule
            request.manual = false
            stopAnimator()
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, (schedule.endTime - now)
                .coerceAtLeast(0) + 2000)
            startPlayback(request)
        }

        private fun startPlayback(request: UnlockTransition) {
            val timing = request.timing ?: return
            if (transition !== request || animator != null ||
                request.clocks.values.any { !it.hasDestination || it.overlay?.ready != true && it.home == null }) return
            val to = if (timing.unlocked) 1f else 0f
            if (request.started && request.amount == to) {
                syncUnlock(request)
                return
            }
            if (!isEnabled(request.context, request.uid) || !isHomeUnderneath(request.context) ||
                !ValueAnimator.areAnimatorsEnabled()) {
                resetUnlock()
                return
            }
            val scale = ValueAnimator.getDurationScale().coerceAtLeast(0.01f)
            val now = SystemClock.uptimeMillis()
            val initial = !request.started
            val delay = if (timing.unlocked) 0L else (timing.startTime - now).coerceAtLeast(0)
            val startTime = if (initial) timing.startTime else maxOf(now, timing.startTime)
            val remaining = (timing.endTime - startTime).coerceAtLeast(80)
            val animation = ValueAnimator.ofFloat(request.amount, to)
            animator = animation
            animation.duration = (remaining / scale).toLong().coerceAtLeast(1)
            animation.startDelay = (delay / scale).toLong()
            animation.interpolator = LinearInterpolator()
            animation.addUpdateListener {
                if (animator === animation && transition === request) {
                    request.started = true
                    request.amount = (it.animatedValue as Float).coerceIn(0f, 1f)
                    syncUnlock(request)
                }
            }
            animation.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    if (animator === animation && transition === request) {
                        request.started = true
                        syncUnlock(request)
                    }
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation && transition === request) {
                        animator = null
                        request.amount = to
                        if (to == 0f) resetUnlock() else syncUnlock(request)
                    }
                }
            })
            animation.start()
            if (initial && delay == 0L && now > timing.startTime) {
                animation.currentPlayTime = ((now - timing.startTime) / scale).toLong()
                    .coerceAtMost(animation.duration)
            }
        }

        fun prepareForUnlock(): Unit = onMain {
            if (transition != null || !ValueAnimator.areAnimatorsEnabled()) return@onMain
            observeKeyguard()
            val clocks = ClockStyle.unlockClocks()
            if (clocks.isEmpty()) return@onMain
            val context = providerContext ?: clocks.firstOrNull()?.context ?: return@onMain
            val userContext = context.createContextAsUser(
                UserHandle.of(ActivityManager.getCurrentUser()), Context.CONTEXT_INCLUDE_CODE,
            )
            val uid = runCatching { userContext.packageManager.getPackageUid(LAUNCHER_PACKAGE, 0) }
                .getOrNull() ?: return@onMain
            if (!isEnabled(userContext, uid) || !isHomeUnderneath(userContext)) return@onMain
            if (clocks.any { it.hasGlassEffects }) {
                InteractionBoost.request(GLASS_UNLOCK_BOOST_DURATION_MS)
            }
            val pictures = clocks.mapNotNull { clock ->
                val displayId = clock.display?.displayId ?: return@mapNotNull null
                val source = ClockSnapshot.capture(clock) ?: return@mapNotNull null
                val picture = source.capturePicture() ?: return@mapNotNull null
                displayId to ClockTransition(source, picture)
            }.toMap().toMutableMap()
            if (pictures.isEmpty()) return@onMain
            val request = UnlockTransition(userContext, uid, pictures)
            transition = request
            pictures.keys.toList().forEach { displayId ->
                if (pictures[displayId]?.showOverlay(request, displayId) != true) {
                    pictures.remove(displayId)?.close()
                }
            }
            if (pictures.isEmpty()) {
                resetUnlock()
            } else {
                observingTasks = true
                TaskStackChangeListeners.getInstance().registerTaskStackListener(taskCallback)
                sessions.toList().forEach { it.syncTransition() }
                main.postDelayed(timeout, 2000)
            }
        }

        fun startUnlockAnimation(): Unit = playUnlockAnimation(true, UNLOCK_DURATION, 0)

        fun playUnlockAnimation(unlocked: Boolean, duration: Long, startDelay: Long): Unit = onMain {
            if (!ValueAnimator.areAnimatorsEnabled() || duration <= 0) {
                resetUnlock()
                return@onMain
            }
            val now = SystemClock.uptimeMillis()
            if (transition == null) prepareForUnlock()
            val request = transition ?: return@onMain
            val scale = ValueAnimator.getDurationScale()
            val to = if (unlocked) 1f else 0f
            val startTime = if (unlocked) now else
                now + (startDelay.coerceIn(0, 2000) * scale).toLong()
            val remaining = (duration.coerceAtMost(5000) * abs(to - request.amount) * scale)
                .toLong().coerceAtLeast(1)
            setTiming(request, UnlockTiming(unlocked, startTime, startTime + remaining, false))
        }

        fun setUnlockAmount(amount: Float, forceIfAnimating: Boolean): Unit = onMain {
            if (!amount.isFinite()) return@onMain
            if (!ValueAnimator.areAnimatorsEnabled()) {
                resetUnlock()
                return@onMain
            }
            val request = transition ?: return@onMain
            if (amount >= 1f && request.timing?.unlocked == true) return@onMain
            if (animator != null && !forceIfAnimating) return@onMain
            if (amount >= 1f && !request.manual) return@onMain
            stopAnimator()
            request.timing = null
            request.manual = true
            request.started = request.clocks.values.all { it.hasDestination }
            request.amount = amount.coerceIn(0f, 1f)
            syncUnlock(request)
            main.removeCallbacks(timeout)
            main.postDelayed(timeout, 2000)
        }

        fun finishUnlock(showKeyguard: Boolean): Unit = onMain {
            if (showKeyguard) {
                resetUnlock()
            } else {
                val request = transition ?: return@onMain
                request.keyguardGone = true
                if (request.timing == null && !request.manual) {
                    playUnlockAnimation(true, UNLOCK_DURATION, 0)
                }
                syncUnlock(request)
            }
        }
    }
}
