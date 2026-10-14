/*
 * Copyright (C) 2025 AxionOS Project
 * Copyright (C) 2026 Lunaris-AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.lockscreen

import android.content.pm.LauncherApps
import com.android.systemui.communal.domain.interactor.WidgetTrampolineInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.ScrimUtils
import org.lunaris.lockscreenwidgets.viewKey
import org.lunaris.lockscreenwidgets.WidgetClient
import org.lunaris.lockscreenwidgets.WidgetContract
import org.lunaris.lockscreenwidgets.WidgetEditorView
import org.lunaris.lockscreenwidgets.WidgetEntry
import org.lunaris.lockscreenwidgets.WidgetGridView
import org.lunaris.lockscreenwidgets.WidgetTouchFrame
import org.lunaris.lockscreenwidgets.R as WidgetR

class KeyguardWidgetHostController(
    private val context: Context,
    private val container: WidgetViewport,
    private val activityStarter: ActivityStarter,
    private val falsingManager: FalsingManager,
    private val userTracker: UserTracker,
    private val keyguardStateController: KeyguardStateController,
    private val trampolineInteractor: WidgetTrampolineInteractor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trampolineJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scrim = ScrimUtils.get()
    private var attached = false
    private var switchingUser = false
    private var editing = false
    private var awake = context.getSystemService(PowerManager::class.java).isInteractive
    private var bouncer = false
    private var qsVisible = false
    private var grid = WidgetGridView(context)
    private var editor: WidgetEditorView? = null
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var client: WidgetClient? = null
    private var snapshot = Bundle()
    private var providerKeys = emptyMap<Int, String>()
    private val views = mutableMapOf<Int, AppWidgetHostView>()
    private val remoteViews = mutableMapOf<Int, RemoteViews?>()
    private val interaction = RemoteViews.InteractionHandler { view, intent, response ->
        if (!canShow() || falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) false
        else {
            trampolineJob?.cancel()
            val options = response.getLaunchOptions(view)
            if (intent.isActivity) {
                activityStarter.startPendingIntentMaybeDismissingKeyguard(intent, false, null, null,
                    options.first, options.second.toBundle(), null)
                true
            } else {
                trampolineJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    trampolineInteractor.waitForActivityStartAndDismissKeyguard(onLockscreen = true)
                }
                RemoteViews.startPendingIntent(view, intent, options)
            }
        }
    }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { updateVisibility(); client?.refresh() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updateVisibility() }
    }
    private val userCallback = object : UserTracker.Callback {
        override fun onBeforeUserSwitching(newUser: Int) {
            switchingUser = true
            disconnect()
            container.visibility = View.GONE
        }
        override fun onUserChanged(newUser: Int, userContext: Context) {
            disconnect()
            switchingUser = false
            snapshot = Bundle()
            remoteViews.clear()
            editing = false
            rebuild()
            updateVisibility()
        }
    }
    private val keyguardCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardShowingChanged() { updateVisibility() }
    }
    private val scrimListener = object : ScrimUtils.ScrimEventListener {
        override fun onKeyguardShowingChanged(showing: Boolean) { updateVisibility() }
        override fun onDozingChanged(dozing: Boolean) { updateVisibility() }
        override fun onStartedWakingUp() { awake = true; updateVisibility() }
        override fun onScreenTurnedOff() { awake = false; editing = false; rebuild(); updateVisibility() }
        override fun onKeyguardGoingAwayChanged(goingAway: Boolean) { updateVisibility() }
        override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) { updateVisibility() }
        override fun onPrimaryBouncerShowingChanged(showing: Boolean) { bouncer = showing; updateVisibility() }
        override fun onQsVisibilityChanged(visible: Boolean) { qsVisible = visible; updateVisibility() }
    }

    fun attach() {
        if (attached) return
        attached = true
        container.addView(body)
        container.onConfigurationChanged = { rebuild(); render(); client?.refresh() }
        for (key in listOf(WidgetContract.CONFIG, WidgetContract.ENABLED)) {
            context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false,
                observer, UserHandle.USER_ALL)
        }
        context.registerReceiverAsUser(receiver, UserHandle.ALL, IntentFilter(Intent.ACTION_USER_UNLOCKED),
            null, handler, Context.RECEIVER_NOT_EXPORTED)
        scrim.addListener(scrimListener)
        keyguardStateController.addCallback(keyguardCallback)
        userTracker.addCallback(userCallback, context.mainExecutor)
        rebuild()
        updateVisibility()
    }

    private fun canShow() = attached && !switchingUser && keyguardStateController.isShowing &&
        !keyguardStateController.isOccluded && !keyguardStateController.isKeyguardGoingAway &&
        !scrim.isDozing() && awake &&
        !bouncer && !qsVisible && context.getSystemService(UserManager::class.java)
            .isUserUnlocked(UserHandle.of(userTracker.userId))

    private fun updateVisibility() {
        val show = canShow() && Settings.Secure.getIntForUser(context.contentResolver,
            WidgetContract.ENABLED, 0, userTracker.userId) != 0
        container.visibility = if (show) View.VISIBLE else View.GONE
        if (show && client == null) {
            val userContext = context.createContextAsUser(UserHandle.of(userTracker.userId), 0)
            client = WidgetClient(userContext, onSnapshot = {
                snapshot = it
                render()
            }, onViews = { id, remote ->
                remoteViews[id] = remote
                views[id]?.updateAppWidget(remote)
            }, onDataChanged = { id, viewId -> views[id]?.onViewDataChanged(viewId) }, onError = { error ->
                if (editing) android.widget.Toast.makeText(context, error.messageResId,
                    android.widget.Toast.LENGTH_SHORT).show()
            }).also { it.start() }
        } else if (!show) disconnect()
    }

    private fun disconnect() {
        trampolineJob?.cancel()
        client?.close()
        client = null
    }

    private fun rebuild() {
        views.clear()
        body.removeAllViews()
        if (editing) {
            body.addView(Button(context).apply {
                setText(WidgetR.string.app_widgets_done)
                setOnClickListener { editing = false; rebuild(); render() }
            })
            editor = WidgetEditorView(context).apply {
                onAdd = { configure(-1) }
                onConfigure = ::configure
                onRemove = { id -> client?.change("remove", id.toString()) }
                onChange = { entry -> client?.change("change", extras = entry.bundle()) }
            }
            grid = editor!!.grid
            body.addView(editor)
        } else {
            editor = null
            grid = WidgetGridView(context)
            body.addView(grid)
        }
    }

    private fun configure(id: Int) {
        activityStarter.startActivity(Intent().setClassName("com.android.systemui", WidgetContract.ACTIVITY)
            .putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), true)
    }

    private fun render() {
        if (!attached) return
        val entries = WidgetEntry.decode(snapshot.getString("config"))
        if (entries.isEmpty() && !editing) {
            body.removeAllViews()
            body.addView(Button(context).apply {
                setText(WidgetR.string.app_widgets_add)
                setOnClickListener { configure(-1) }
            })
            return
        }
        if (!editing && grid.parent == null) { body.removeAllViews(); body.addView(grid) }
        val infos = entries.mapNotNull { entry ->
            snapshot.getParcelable(entry.id.toString(), AppWidgetProviderInfo::class.java)?.let { entry.id to it }
        }.toMap()
        val keys = infos.mapValues { it.value.viewKey() }
        if (keys != providerKeys) { grid.clearWidgets(); views.clear() }
        providerKeys = keys
        views.keys.retainAll(entries.map { it.id }.toSet())
        remoteViews.keys.retainAll(entries.map { it.id }.toSet())
        val create: (WidgetEntry) -> View = { entry ->
            val info = infos[entry.id]
            if (info == null) {
                TextView(context).apply {
                    setText(WidgetR.string.app_widgets_unavailable)
                    setOnLongClickListener { beginEditing(); true }
                }
            } else {
                val hostView = object : AppWidgetHostView(context, interaction) {
                    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                        super.onSizeChanged(w, h, oldw, oldh)
                        if (w > 0 && h > 0) client?.change("size", extras = Bundle().apply {
                            putInt("id", entry.id); putInt("width", w); putInt("height", h)
                        })
                    }
                    override fun onDefaultViewClicked(view: View) {
                        val launcher = context.getSystemService(LauncherApps::class.java)
                        val activity = launcher.getActivityList(info.provider.packageName, info.profile)
                            .firstOrNull() ?: return
                        val intent = launcher.getMainActivityLaunchIntent(activity.componentName, null,
                            info.profile) ?: return
                        interaction.onInteraction(view, intent, RemoteViews.RemoteResponse.fromPendingIntent(intent))
                    }
                }.apply {
                    setAppWidget(entry.id, info)
                    updateAppWidget(remoteViews[entry.id])
                }
                views[entry.id] = hostView
                WidgetTouchFrame(context, ::beginEditing).apply {
                    isLongClickable = true
                    contentDescription = info.loadLabel(context.packageManager)
                    addView(hostView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT))
                    setOnLongClickListener { beginEditing(); true }
                }
            }
        }
        editor?.sizingWidth = snapshot.getInt("gridWidth")
        editor?.update(entries, infos, create) ?: grid.setWidgets(entries, create)
    }

    private fun beginEditing() {
        if (!canShow() || falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)) return
        editing = true
        rebuild()
        render()
    }

    fun detach() {
        if (!attached) return
        attached = false
        disconnect()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        context.contentResolver.unregisterContentObserver(observer)
        context.unregisterReceiver(receiver)
        scrim.removeListener(scrimListener)
        keyguardStateController.removeCallback(keyguardCallback)
        userTracker.removeCallback(userCallback)
        container.onConfigurationChanged = null
        container.removeAllViews()
        views.clear()
        remoteViews.clear()
    }
}

class WidgetViewport(context: Context) : ScrollView(context) {
    var onConfigurationChanged: (() -> Unit)? = null
    var maximumHeight = 0
        set(value) { if (field != value) { field = value; requestLayout() } }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maximumHeight.coerceAtLeast(0), MeasureSpec.AT_MOST))
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            (canScrollVertically(-1) || canScrollVertically(1))) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
            parent?.requestDisallowInterceptTouchEvent(false)
        return handled
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onConfigurationChanged?.invoke()
    }
}
