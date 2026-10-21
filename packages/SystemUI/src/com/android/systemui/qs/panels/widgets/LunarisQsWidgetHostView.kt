/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.widgets

import android.animation.Animator
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterViewAnimator
import android.widget.Advanceable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import com.android.systemui.qs.panels.shared.model.LunarisQsWidget
import com.android.systemui.qs.panels.shared.model.LunarisQsWidgetKey

internal class LunarisQsWidgetHostView(
    context: Context,
    private val widget: LunarisQsWidget,
    private val key: LunarisQsWidgetKey,
    interactionHandler: RemoteViews.InteractionHandler,
    onLightBackground: Boolean,
    private val onUnavailable: () -> Unit,
) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val host =
        object :
            AppWidgetHostView(
                context.createContextAsUser(UserHandle.of(widget.userId), 0),
                interactionHandler,
            ) {
            override fun isVisibilityTrackingPermitted() = false

            override fun onDefaultViewClicked(view: View) = unavailable()

            override fun getErrorView(): View {
                main.post { unavailable() }
                return super.getErrorView()
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                try {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Cannot measure widget", e)
                    setMeasuredDimension(
                        MeasureSpec.getSize(widthMeasureSpec),
                        MeasureSpec.getSize(heightMeasureSpec),
                    )
                    main.post { unavailable() }
                }
            }

            override fun dispatchWindowVisibilityChanged(visibility: Int) {
                super.dispatchWindowVisibilityChanged(
                    if (active && this.visibility == VISIBLE) visibility else INVISIBLE
                )
            }
        }
    private val frozen = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private var snapshot: Bitmap? = null
    private var service: ILunarisQsWidgetHost? = null
    private var bound = false
    private var disposed = false
    private var requestedActive = false
    private var active = false
    private var touchInProgress = false
    private var provider: AppWidgetProviderInfo? = null
    private var pendingViews: RemoteViews? = null
    private var hasUpdate = false
    private val pendingData = mutableSetOf<Int>()
    private var lastSize: Pair<Float, Float>? = null
    private var lastHostWidth = 0
    private var lastHostHeight = 0
    private val pausedDrawables = mutableSetOf<Animatable>()
    private val pausedAnimators = mutableSetOf<Animator>()
    private var advanceScheduled = false
    private val advance =
        object : Runnable {
            override fun run() {
                advanceScheduled = false
                if (!active || disposed) return
                try {
                    advanceable()?.advance()
                    scheduleAdvance()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Cannot advance widget", e)
                    unavailable()
                }
            }
        }

    private val listener =
        object : ILunarisQsWidgetListener.Stub() {
            override fun onUpdate(info: AppWidgetProviderInfo?, views: RemoteViews?) {
                main.post {
                    if (disposed) return@post
                    provider = info
                    pendingViews = views
                    hasUpdate = true
                    if (active) applyPending()
                }
            }

            override fun onDataChanged(viewId: Int) {
                main.post {
                    if (disposed) return@post
                    pendingData.add(viewId)
                    if (active) applyPending()
                }
            }

            override fun onRemoved() {
                main.post { if (!disposed) unavailable() }
            }
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (disposed) return
                service = ILunarisQsWidgetHost.Stub.asInterface(binder)
                lastSize = null
                callService {
                    it.connect(key.layout.name, key.control.id, widget.appWidgetId, listener)
                    it.setActive(listener, active)
                }
                if (active) resumeHost()
                requestLayout()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                freeze()
            }

            override fun onBindingDied(name: ComponentName) {
                freeze()
                unbind()
                if (active && !disposed) bind()
            }

            override fun onNullBinding(name: ComponentName) = unavailable()
        }

    init {
        clipChildren = true
        clipToPadding = true
        host.setOnLightBackground(onLightBackground)
        host.visibility = INVISIBLE
        addView(
            host,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
                Gravity.TOP or Gravity.LEFT,
            ),
        )
        addView(frozen, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun updateActive(value: Boolean) {
        requestedActive = value
        updateHostActivity()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateHostActivity()
    }

    override fun onDetachedFromWindow() {
        setHostActive(false)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        setHostActive(requestedActive && isAttachedToWindow && visibility == VISIBLE)
    }

    private fun updateHostActivity() {
        setHostActive(requestedActive && isAttachedToWindow && windowVisibility == VISIBLE)
    }

    private fun setHostActive(value: Boolean) {
        if (disposed || active == value) return
        active = value
        if (value) {
            bind()
            if (!disposed) resumeHost()
        } else {
            freeze()
        }
        callService { it.setActive(listener, value) }
    }

    private fun resumeHost() {
        host.visibility = VISIBLE
        host.dispatchWindowVisibilityChanged(VISIBLE)
        pausedDrawables.forEach { it.start() }
        pausedDrawables.clear()
        pausedAnimators.forEach { it.resume() }
        pausedAnimators.clear()
        frozen.visibility = GONE
        applyPending()
        scheduleAdvance()
        requestLayout()
    }

    private fun advanceable(): Advanceable? =
        provider
            ?.autoAdvanceViewId
            ?.takeIf { it != View.NO_ID }
            ?.let { host.findViewById<View>(it) as? Advanceable }

    private fun scheduleAdvance() {
        if (!active || disposed || advanceScheduled) return
        val target = advanceable() ?: return
        target.fyiWillBeAdvancedByHostKThx()
        advanceScheduled = true
        main.postDelayed(advance, 20_000)
    }

    private fun bind() {
        if (bound || disposed) return
        try {
            bound =
                context.bindServiceAsUser(
                    Intent(context, LunarisQsWidgetService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                    UserHandle.of(widget.userId),
                )
            if (!bound) unavailable()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot bind widget host", e)
            unavailable()
        }
    }

    private fun applyPending() {
        if (!active || disposed) return
        try {
            if (hasUpdate) {
                val info = provider ?: return
                pausedDrawables.clear()
                host.setAppWidget(widget.appWidgetId, info)
                host.updateAppWidget(pendingViews)
                pendingViews = null
                hasUpdate = false
                scheduleAdvance()
                requestLayout()
            }
            pendingData.forEach(host::onViewDataChanged)
            pendingData.clear()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot display widget", e)
            unavailable()
        } catch (_: OutOfMemoryError) {
            unavailable()
        }
    }

    private fun freeze() {
        main.removeCallbacks(advance)
        advanceScheduled = false
        cancelTouch()
        if (host.visibility != VISIBLE) return
        if (width > 0 && height > 0 && width.toLong() * height <= MAX_SNAPSHOT_PIXELS) {
            try {
                val bitmap =
                    snapshot?.takeIf { it.width == width && it.height == height }
                        ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                val canvas = Canvas(bitmap)
                canvas.translate(host.translationX, host.translationY)
                canvas.scale(host.scaleX, host.scaleY)
                host.draw(canvas)
                frozen.setImageBitmap(bitmap)
                snapshot = bitmap
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cannot freeze widget", e)
            } catch (_: OutOfMemoryError) {
                frozen.setImageDrawable(null)
                snapshot = null
            }
        }
        pauseDrawables(host)
        host.visibility = INVISIBLE
        host.dispatchWindowVisibilityChanged(INVISIBLE)
        frozen.visibility = VISIBLE
    }

    private fun pauseDrawables(view: View) {
        if (view is AbsListView) view.smoothScrollBy(0, 0)
        if (view is AdapterViewAnimator) {
            listOfNotNull(view.inAnimation, view.outAnimation).forEach {
                if (it.isStarted && !it.isPaused) {
                    it.pause()
                    pausedAnimators.add(it)
                }
            }
        }
        val drawables = buildList {
            add(view.background)
            add(view.foreground)
            if (view is ImageView) add(view.drawable)
            if (view is TextView) addAll(view.compoundDrawables.toList())
        }
        drawables.filterIsInstance<Animatable>().forEach {
            if (it.isRunning) {
                pausedDrawables.add(it)
                it.stop()
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) pauseDrawables(view.getChildAt(index))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!active && lastHostWidth > 0 && lastHostHeight > 0) {
            host.measure(
                MeasureSpec.makeMeasureSpec(lastHostWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(lastHostHeight, MeasureSpec.EXACTLY),
            )
            return
        }
        val info = provider ?: return
        if (measuredWidth <= 0 || measuredHeight <= 0) return
        val nativeSize =
            qsWidgetSize(
                context,
                info,
                measuredWidth,
                measuredHeight,
                Rect(host.paddingLeft, host.paddingTop, host.paddingRight, host.paddingBottom),
            )
        val width = nativeSize.width
        val height = nativeSize.height
        lastHostWidth = width
        lastHostHeight = height
        host.pivotX = 0f
        host.pivotY = 0f
        host.scaleX = nativeSize.scale
        host.scaleY = nativeSize.scale
        host.translationX = nativeSize.offsetX
        host.translationY = nativeSize.offsetY
        host.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        val density = resources.displayMetrics.density
        val size =
            (width - host.paddingLeft - host.paddingRight).coerceAtLeast(1) / density to
                (height - host.paddingTop - host.paddingBottom).coerceAtLeast(1) / density
        if (active && size != lastSize && service != null) {
            lastSize = size
            callService { it.resize(listener, size.first, size.second) }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!active && event.actionMasked != MotionEvent.ACTION_CANCEL) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchInProgress = true
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> touchInProgress = false
        }
        return super.dispatchTouchEvent(event)
    }

    private fun cancelTouch() {
        if (!touchInProgress) return
        touchInProgress = false
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try {
            super.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun callService(action: (ILunarisQsWidgetHost) -> Unit) {
        val hostService = service ?: return
        try {
            action(hostService)
        } catch (e: android.os.RemoteException) {
            Log.w(TAG, "Widget host disconnected", e)
        }
    }

    private fun unavailable() {
        if (disposed) return
        dispose()
        onUnavailable()
    }

    private fun unbind() {
        callService { it.disconnect(listener) }
        service = null
        if (bound) context.unbindService(connection)
        bound = false
    }

    fun dispose() {
        if (disposed) return
        requestedActive = false
        active = false
        cancelTouch()
        pauseDrawables(host)
        host.visibility = INVISIBLE
        host.dispatchWindowVisibilityChanged(INVISIBLE)
        callService { it.setActive(listener, false) }
        disposed = true
        unbind()
        main.removeCallbacksAndMessages(null)
        pausedDrawables.clear()
        pausedAnimators.forEach { it.cancel() }
        pausedAnimators.clear()
        pendingData.clear()
        pendingViews = null
        frozen.setImageDrawable(null)
        snapshot = null
        removeAllViews()
    }

    companion object {
        private const val TAG = "LunarisQsWidgetView"
        private const val MAX_SNAPSHOT_PIXELS = 4_000_000
    }
}
