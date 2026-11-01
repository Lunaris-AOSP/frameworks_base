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

package com.android.systemui.edgeaurora

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationStats
import android.util.Log
import android.view.ViewConfiguration
import com.android.internal.statusbar.IStatusBarService
import com.android.internal.util.lunaris.EdgeAuroraConfig
import com.android.internal.util.lunaris.EdgeAuroraConfig.Profile
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.doze.DozeHost
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.featurepods.popups.data.repository.observeLunarisBarForegroundApp
import com.android.systemui.statusbar.notification.collection.NotifCollection
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptSuppressor
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController
import com.axion.applocker.AxAppLockerHelper
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class AuroraPayload(
    val id: String,
    val profile: Profile,
    val entries: List<NotificationEntry>,
)

@SysUISingleton
class EdgeAuroraController
@Inject
constructor(
    private val context: Context,
    private val pipeline: NotifPipeline,
    private val collection: NotifCollection,
    private val visibility: NotificationVisibilityProvider,
    private val lockscreen: NotificationLockscreenUserManager,
    private val keyguardVisibility: KeyguardNotificationVisibilityProvider,
    private val protection: SensitiveNotificationProtectionController,
    private val appLocker: AxAppLockerHelper,
    private val keyguard: KeyguardStateController,
    private val statusBar: StatusBarStateController,
    private val wakefulness: WakefulnessLifecycle,
    private val dozeHost: DozeHost,
    private val userTracker: UserTracker,
    private val activityStarter: ActivityStarter,
    private val interruption: VisualInterruptionDecisionProvider,
    private val provisioned: DeviceProvisionedController,
    private val configuration: ConfigurationController,
    private val statusBarService: IStatusBarService,
    private val edgeAuroraRepository: EdgeAuroraRepository,
    @Main private val handler: Handler,
    @Main private val executor: Executor,
    @Application private val scope: CoroutineScope,
) : CoreStartable {
    private val resolver = context.contentResolver
    private val power = context.getSystemService(PowerManager::class.java)!!
    private val audio = context.getSystemService(AudioManager::class.java)!!
    private val users = context.getSystemService(UserManager::class.java)!!
    private val sensors = context.getSystemService(SensorManager::class.java)!!
    private val pocketWakeLock =
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SystemUI:EdgeAuroraPocket").apply {
            setReferenceCounted(false)
        }
    private var config = EdgeAuroraConfig(resolver, userTracker.userId)
    private var windowConfiguration = Configuration(context.resources.configuration)
    private val window = EdgeAuroraWindow(context, handler, this)
    private val hiddenUntil = mutableMapOf<String, Long>()
    private val contents = mutableMapOf<String, String>()
    private var foreground: String? = null
    private var switchingUser = false
    private var bootCompleted = false
    private var darkSession = false
    private var sleepRequested = false
    private var checkingPocket = false
    private var pocketGeneration = 0
    private var proximityListener: SensorEventListener? = null
    private var previewId: String? = null
    private var previewToken: String? = null
    private val refresh = Runnable { render() }
    private val endPreview = Runnable {
        previewId = null
        render()
    }
    private val darkTimeout = Runnable { finishDarkSession() }

    override fun start() {
        previewToken =
            Settings.System.getStringForUser(resolver, EdgeAuroraConfig.PREVIEW, userTracker.userId)
        val observer =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    config = EdgeAuroraConfig(resolver, userTracker.userId)
                    if (uri == Settings.System.getUriFor(EdgeAuroraConfig.PREVIEW)) readPreview()
                    if (!config.enabled || !config.wake) finishDarkSession()
                    window.closePanel()
                    window.cancelGestures()
                    scheduleRefresh()
                }
            }
        EdgeAuroraConfig.KEYS.forEach {
            resolver.registerContentObserver(
                Settings.System.getUriFor(it),
                false,
                observer,
                UserHandle.USER_ALL,
            )
        }
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(KEY_GAMING_MODE_ACTIVE),
            false,
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    render()
                }
            },
            UserHandle.USER_ALL,
        )
        pipeline.addCollectionListener(
            object : NotifCollectionListener {
                override fun onEntryAdded(entry: NotificationEntry) = changed(entry)

                override fun onEntryUpdated(entry: NotificationEntry) = changed(entry)

                override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                    hiddenUntil.remove(entry.key)
                    contents.remove(entry.key)
                    scheduleRefresh()
                }

                override fun onRankingApplied() = scheduleRefresh()
            }
        )
        userTracker.addCallback(
            object : UserTracker.Callback {
                override fun onUserChanging(newUser: Int, userContext: Context) {
                    switchingUser = true
                    reset()
                }

                override fun onUserChanged(newUser: Int, userContext: Context) {
                    reset()
                    config = EdgeAuroraConfig(resolver, newUser)
                    previewToken =
                        Settings.System.getStringForUser(
                            resolver,
                            EdgeAuroraConfig.PREVIEW,
                            newUser,
                        )
                    switchingUser = false
                    scheduleRefresh()
                }

                override fun onProfilesChanged(profiles: List<android.content.pm.UserInfo>) {
                    window.hide()
                    scheduleRefresh()
                }
            },
            executor,
        )
        keyguard.addCallback(
            object : KeyguardStateController.Callback {
                override fun onKeyguardShowingChanged() {
                    window.hide()
                    if (!keyguard.isShowing) finishDarkSession()
                    scheduleRefresh()
                }

                override fun onKeyguardGoingAwayChanged() {
                    if (keyguard.isKeyguardGoingAway) {
                        window.hide()
                        finishDarkSession()
                    }
                    scheduleRefresh()
                }

                override fun onPrimaryBouncerShowingChanged() {
                    if (keyguard.isPrimaryBouncerShowing) {
                        finishDarkSession()
                        window.hide()
                    }
                    scheduleRefresh()
                }
            }
        )
        statusBar.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onExpandedChanged(isExpanded: Boolean) {
                    if (isExpanded && !keyguard.isShowing) {
                        window.hide()
                        finishDarkSession()
                    }
                    scheduleRefresh()
                }

                override fun onDozingChanged(isDozing: Boolean) = scheduleRefresh()

                override fun onDreamingChanged(isDreaming: Boolean) = scheduleRefresh()
            }
        )
        wakefulness.addObserver(
            object : WakefulnessLifecycle.Observer {
                override fun onStartedGoingToSleep() {
                    sleepRequested = true
                    finishDarkSession()
                    window.hide()
                }

                override fun onStartedWakingUp() {
                    sleepRequested = false
                    finishDarkSession()
                    scheduleRefresh()
                }

                override fun onFinishedGoingToSleep() {
                    sleepRequested = false
                    scheduleRefresh()
                }

                override fun onFinishedWakingUp() = scheduleRefresh()
            }
        )
        configuration.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onThemeChanged() = scheduleRefresh()

                override fun onConfigChanged(newConfig: Configuration) {
                    val changes = windowConfiguration.diff(newConfig)
                    windowConfiguration.setTo(newConfig)
                    if (
                        changes and
                            (ActivityInfo.CONFIG_DENSITY or
                                ActivityInfo.CONFIG_FONT_SCALE or
                                ActivityInfo.CONFIG_UI_MODE or
                                ActivityInfo.CONFIG_LOCALE or
                                ActivityInfo.CONFIG_LAYOUT_DIRECTION) != 0
                    )
                        window.hide()
                    scheduleRefresh()
                }
            }
        )
        lockscreen.addNotificationStateChangedListener {
            window.closePanel()
            scheduleRefresh()
        }
        keyguardVisibility.addOnStateChangedListener {
            window.closePanel()
            scheduleRefresh()
        }
        protection.registerSensitiveStateListener(
            Runnable {
                window.closePanel()
                scheduleRefresh()
            }
        )
        appLocker.addRefreshListener(
            Runnable {
                window.closePanel()
                scheduleRefresh()
            }
        )
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            finishDarkSession()
                            window.hide()
                        }
                        Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                            finishDarkSession()
                            window.closePanel(true)
                            window.cancelGestures()
                            scheduleRefresh()
                        }
                        else -> scheduleRefresh()
                    }
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            },
            null,
            handler,
            Context.RECEIVER_NOT_EXPORTED,
        )
        audio.addOnModeChangedListener(executor) {
            if (it != AudioManager.MODE_NORMAL) {
                finishDarkSession()
                window.hide()
            }
            scheduleRefresh()
        }
        scope.launch {
            observeLunarisBarForegroundApp().collect {
                val packageName = it.packageName
                handler.post {
                    if (foreground != packageName) {
                        if (darkSession && !keyguard.isShowing) finishDarkSession()
                        window.closePanel(true)
                    }
                    foreground = packageName
                    scheduleRefresh()
                }
            }
        }
        interruption.addLegacySuppressor(
            object : NotificationInterruptSuppressor {
                override fun getName() = "Edge Aurora"

                override fun suppressAwakeHeadsUp(entry: NotificationEntry) =
                    shouldSuppressHeadsUp(entry)
            }
        )
        scheduleRefresh()
    }

    override fun onBootCompleted() {
        bootCompleted = true
    }

    private fun changed(entry: NotificationEntry) {
        val key = entry.key
        val n = entry.sbn.notification
        val fingerprint = fingerprint(n)
        val previous = contents.put(key, fingerprint)
        val fresh =
            bootCompleted &&
                previous != fingerprint &&
                (previous == null || n.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0) &&
                System.currentTimeMillis() - entry.sbn.postTime in 0..20000
        val user = userTracker.userId
        handler.post {
            if (user != userTracker.userId || switchingUser) return@post
            val current = pipeline.getEntry(key)
            if (
                fresh &&
                    current != null &&
                    eligible(current) &&
                    available() &&
                    !quiet() &&
                    config.profileFor(current.sbn.packageName) != null
            ) {
                if (!power.isInteractive && config.wake) maybeWake(key)
                render(if (config.autoPeek) key else null)
            } else render()
        }
    }

    internal fun fingerprint(n: Notification): String =
        listOf(
                Notification.EXTRA_TITLE,
                Notification.EXTRA_TEXT,
                Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_CONVERSATION_TITLE,
            )
            .joinToString("\u0000") { n.extras.getCharSequence(it)?.toString().orEmpty() } +
            n.extras
                .getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.joinToString("\u0000")
                .orEmpty() +
            runCatching {
                    Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                            n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                        )
                        .joinToString("\u0000") {
                            "${it.timestamp}:${it.senderPerson?.name}:${it.text}:${it.dataUri}"
                        }
                }
                .getOrDefault("")

    private fun available() =
        config.enabled &&
            !isGaming() &&
            !sleepRequested &&
            wakefulness.wakefulness != WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP &&
            !switchingUser &&
            provisioned.isDeviceProvisioned &&
            provisioned.isCurrentUserSetup &&
            !keyguard.isKeyguardGoingAway &&
            !keyguard.isPrimaryBouncerShowing &&
            !keyguard.isOccluded &&
            audio.mode == AudioManager.MODE_NORMAL &&
            (!statusBar.isDreaming || statusBar.isDozing)

    private fun quiet() = foreground in config.quietApps

    private fun isGaming() =
        Settings.Secure.getIntForUser(resolver, KEY_GAMING_MODE_ACTIVE, 0, userTracker.userId) == 1

    private fun shouldSuppressHeadsUp(entry: NotificationEntry): Boolean =
        config.onlyEdge &&
            available() &&
            power.isInteractive &&
            !keyguard.isShowing &&
            !quiet() &&
            !statusBar.isExpanded &&
            !statusBar.isDozing &&
            config.profileFor(entry.sbn.packageName) != null &&
            eligible(entry, includeGroupSummary = true)

    internal fun eligible(entry: NotificationEntry, includeGroupSummary: Boolean = false): Boolean {
        val sbn = entry.sbn
        val user = if (sbn.userId == UserHandle.USER_ALL) userTracker.userId else sbn.userId
        if (
            !lockscreen.isCurrentProfile(user) ||
                !lockscreen.isProfileAvailable(user) ||
                users.isQuietModeEnabled(UserHandle.of(user)) ||
                !users.isUserUnlocked(UserHandle.of(user))
        )
            return false
        if (
            entry.dismissState != NotificationEntry.DismissState.NOT_DISMISSED ||
                entry.isLastMessageFromReply
        )
            return false
        val n = sbn.notification
        if (
            sbn.isOngoing ||
                !sbn.isClearable ||
                (n.isGroupSummary && !includeGroupSummary) ||
                n.fullScreenIntent != null ||
                n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 ||
                n.category in EXCLUDED
        )
            return false
        val ranking = entry.ranking
        if (
            ranking.importance < NotificationManager.IMPORTANCE_LOW ||
                ranking.isSuspended ||
                !ranking.matchesInterruptionFilter() ||
                ranking.suppressedVisualEffects and
                    (NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS or
                        NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK) != 0
        )
            return false
        if (keyguardVisibility.shouldHideNotification(entry)) return false
        if ((hiddenUntil[entry.key] ?: Long.MIN_VALUE) >= sbn.postTime) return false
        return true
    }

    internal fun redacted(entry: NotificationEntry): Boolean =
        protection.shouldProtectNotification(entry) ||
            appLocker.needsAuth(entry.sbn.packageName, entry.sbn.userId) ||
            lockscreen.needsSeparateWorkChallenge(entry.sbn.userId) &&
                lockscreen.isLockscreenPublicMode(entry.sbn.userId) ||
            (keyguard.isShowing || lockscreen.isLockscreenPublicMode(entry.sbn.userId)) &&
                lockscreen.getRedactionType(entry) !=
                    NotificationLockscreenUserManager.REDACTION_TYPE_NONE

    private fun scheduleRefresh() {
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    internal fun onWindowContentsChanged(payloads: List<AuroraPayload>) {
        val entries =
            if (config.enabled && config.onlyEdge && window.isShowing) {
                payloads.flatMap { it.entries }
            } else {
                emptyList()
            }
        val keys = entries.mapTo(mutableSetOf()) { it.key }
        if (entries.isNotEmpty()) {
            val groupKeys = entries.mapTo(mutableSetOf()) { it.sbn.groupKey }
            pipeline.allNotifs
                .asSequence()
                .filter {
                    it.sbn.notification.isGroupSummary &&
                        it.sbn.groupKey in groupKeys &&
                        eligible(it, includeGroupSummary = true)
                }
                .mapTo(keys) { it.key }
        }
        edgeAuroraRepository.suppressedStatusBarNotificationKeys.value = keys
    }

    private fun render(autoPeekKey: String? = null) {
        if (isGaming()) {
            previewId = null
            handler.removeCallbacks(endPreview)
            finishDarkSession()
            window.hide()
            return
        }
        val preview = previewId?.let { id -> config.profiles.find { it.id == id } }
        if (
            preview != null &&
                !sleepRequested &&
                !switchingUser &&
                power.isInteractive &&
                !keyguard.isShowing &&
                wakefulness.wakefulness != WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP
        ) {
            window.show(listOf(AuroraPayload("preview", preview, emptyList())), config, false, null)
            return
        }
        if (
            !available() ||
                quiet() ||
                (!power.isInteractive || statusBar.isDozing) && !darkSession ||
                statusBar.isExpanded && !keyguard.isShowing ||
                keyguard.isShowing && !config.lockScreen && !darkSession
        ) {
            window.hide()
            if (darkSession) finishDarkSession()
            return
        }
        val groups =
            pipeline.allNotifs
                .filter { eligible(it) }
                .mapNotNull { entry ->
                    config.profileFor(entry.sbn.packageName)?.let { it to entry }
                }
                .groupBy { (profile, entry) -> "${entry.sbn.userId}:${profile.id}" }
        val payloads =
            groups.map { (id, items) ->
                AuroraPayload(
                    id,
                    items.first().first,
                    items.map { it.second }.sortedByDescending { it.sbn.postTime },
                )
            }
        if (payloads.isEmpty()) {
            window.hide()
            finishDarkSession()
        } else {
            val resolved = EdgeAuroraConfig.resolveOverlaps(payloads.map { it.profile })
            window.show(
                payloads.mapIndexed { i, payload -> payload.copy(profile = resolved[i]) },
                config,
                darkSession,
                autoPeekKey?.takeUnless { keyguard.isShowing },
            )
        }
    }

    internal fun touched() {
        pocketGeneration++
        checkingPocket = false
        stopProximity()
        handler.removeCallbacks(darkTimeout)
        if (darkSession) {
            darkSession = false
            dozeHost.stopAuroraPulse()
            power.wakeUp(
                SystemClock.uptimeMillis(),
                PowerManager.WAKE_REASON_GESTURE,
                "SystemUI:EdgeAuroraTouch",
            )
            window.setDark(false, config)
        }
    }

    internal fun longPressTimeout(): Long =
        Settings.Secure.getIntForUser(
                resolver,
                Settings.Secure.LONG_PRESS_TIMEOUT,
                ViewConfiguration.getLongPressTimeout(),
                userTracker.userId,
            )
            .coerceAtLeast(1)
            .toLong()

    internal fun open(key: String) {
        val entry = pipeline.getEntry(key)?.takeIf { eligible(it) } ?: return
        val user = userTracker.userId
        val postTime = entry.sbn.postTime
        val intent = entry.sbn.notification.contentIntent
        touched()
        window.closePanel(true)
        if (intent == null) {
            val appContext = entry.sbn.getPackageContext(context)
            val launch =
                appContext.packageManager.getLaunchIntentForPackage(entry.sbn.packageName) ?: return
            activityStarter.startActivityDismissingKeyguard(
                launch,
                false,
                true,
                false,
                null,
                Intent.FLAG_ACTIVITY_NEW_TASK,
                null,
                entry.sbn.user,
            )
            return
        }
        activityStarter.startPendingIntentDismissingKeyguard(
            intent,
            Runnable {
                handler.post {
                    if (user != userTracker.userId || switchingUser) return@post
                    val current = pipeline.getEntry(key) ?: return@post
                    if (current.sbn.postTime != postTime || !eligible(current)) return@post
                    hiddenUntil[key] = current.sbn.postTime
                    if (current.sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
                        dismiss(key)
                    scheduleRefresh()
                }
            },
        )
    }

    internal fun dismiss(key: String) {
        val entry = pipeline.getEntry(key) ?: return
        val user =
            if (entry.sbn.userId == UserHandle.USER_ALL) userTracker.userId else entry.sbn.userId
        if (!entry.sbn.isClearable || !lockscreen.isCurrentProfile(user)) return
        collection.dismissNotification(
            entry,
            DismissedByUserStats(
                NotificationStats.DISMISSAL_OTHER,
                NotificationStats.DISMISS_SENTIMENT_NEUTRAL,
                visibility.obtain(entry, true),
            ),
        )
        scheduleRefresh()
    }

    internal fun unlockToRead(key: String) {
        val entry = pipeline.getEntry(key)?.takeIf { eligible(it) } ?: return
        if (
            protection.shouldProtectNotification(entry) ||
                appLocker.needsAuth(entry.sbn.packageName, entry.sbn.userId) ||
                lockscreen.needsSeparateWorkChallenge(entry.sbn.userId) &&
                    lockscreen.isLockscreenPublicMode(entry.sbn.userId)
        ) {
            open(key)
            return
        }
        val user = userTracker.userId
        touched()
        window.hide()
        activityStarter.executeRunnableDismissingKeyguard(
            Runnable {
                handler.post {
                    if (user == userTracker.userId && !keyguard.isShowing) {
                        render()
                        window.read(key)
                    }
                }
            },
            Runnable { scheduleRefresh() },
            true,
            true,
            false,
        )
    }

    internal fun canReply(entry: NotificationEntry): Boolean =
        !keyguard.isShowing && !redacted(entry) && eligible(entry)

    internal fun reply(key: String, text: String): Boolean {
        val entry = pipeline.getEntry(key) ?: return false
        if (!canReply(entry) || text.isBlank()) return false
        val action = replyAction(entry.sbn.notification) ?: return false
        val inputs = action.remoteInputs ?: return false
        val results = Bundle()
        inputs
            .filter { it.allowFreeFormInput }
            .forEach { results.putCharSequence(it.resultKey, text) }
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(inputs, fillIn, results)
        RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return try {
            action.actionIntent.send(context, 0, fillIn)
            entry.remoteInputText = text
            entry.setHasSentReply()
            runCatching { statusBarService.onNotificationDirectReplied(key) }
            hiddenUntil[key] = entry.sbn.postTime
            touched()
            window.closePanel()
            scheduleRefresh()
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "Reply action expired")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Reply action unavailable", e)
            false
        }
    }

    internal fun move(id: String, edge: EdgeAuroraConfig.Edge, position: Float) {
        if (keyguard.isShowing || switchingUser) return
        val profiles =
            EdgeAuroraConfig.readProfiles(
                Settings.System.getStringForUser(
                    resolver,
                    EdgeAuroraConfig.PROFILES,
                    userTracker.userId,
                )
            )
        profiles
            .find { it.id == id }
            ?.apply {
                this.edge = edge
                this.position = position
                clamp()
            } ?: return
        Settings.System.putStringForUser(
            resolver,
            EdgeAuroraConfig.PROFILES,
            EdgeAuroraConfig.encodeProfiles(profiles),
            userTracker.userId,
        )
    }

    private fun readPreview() {
        val token =
            Settings.System.getStringForUser(resolver, EdgeAuroraConfig.PREVIEW, userTracker.userId)
        if (token == previewToken) return
        previewToken = token
        if (isGaming()) return
        val parts = token?.split('|', limit = 2) ?: return
        if (
            parts.size != 2 ||
                System.currentTimeMillis() - (parts[0].toLongOrNull() ?: 0) !in 0..5000
        )
            return
        previewId = parts[1]
        handler.removeCallbacks(endPreview)
        handler.postDelayed(endPreview, 6000)
    }

    private fun maybeWake(key: String) {
        if (checkingPocket || darkSession || !config.wake || keyguard.isKeyguardGoingAway) return
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val generation = ++pocketGeneration
        checkingPocket = true
        pocketWakeLock.acquire(1000L)
        var near: Boolean? = if (sensor == null) false else null
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (generation != pocketGeneration) return
                    near = event.values[0] < minOf(event.sensor.maximumRange, 5f)
                    if (near == true && darkSession) finishDarkSession()
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
        if (sensor != null) {
            proximityListener = listener
            if (
                !sensors.registerListener(
                    listener,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    handler,
                )
            ) {
                checkingPocket = false
                stopProximity()
                return
            }
        }
        handler.postDelayed(
            {
                if (generation != pocketGeneration) return@postDelayed
                checkingPocket = false
                val entry = pipeline.getEntry(key)
                if (
                    near != false ||
                        power.isInteractive ||
                        !available() ||
                        !config.wake ||
                        quiet() ||
                        entry == null ||
                        !eligible(entry) ||
                        config.profileFor(entry.sbn.packageName) == null
                ) {
                    stopProximity()
                    return@postDelayed
                }
                darkSession = true
                render()
                if (!window.isShowing) {
                    darkSession = false
                    stopProximity()
                    return@postDelayed
                }
                handler.removeCallbacks(darkTimeout)
                handler.postDelayed(darkTimeout, 2000L)
                val accepted =
                    dozeHost.requestAuroraPulse(
                        config.brightness / 100f,
                        object : DozeHost.PulseCallback {
                            override fun onPulseStarted() {
                                if (!darkSession || !available() || !config.wake || quiet()) {
                                    finishDarkSession()
                                    return
                                }
                                handler.removeCallbacks(darkTimeout)
                                handler.postDelayed(darkTimeout, config.timeout * 1000L)
                                scheduleRefresh()
                            }

                            override fun onPulseFinished() {
                                finishDarkSession()
                            }
                        },
                    )
                if (!accepted) {
                    finishDarkSession()
                    return@postDelayed
                }
                if (pocketWakeLock.isHeld) pocketWakeLock.release()
            },
            if (sensor == null) 0 else 250,
        )
    }

    private fun finishDarkSession() {
        pocketGeneration++
        checkingPocket = false
        stopProximity()
        handler.removeCallbacks(darkTimeout)
        if (!darkSession) return
        darkSession = false
        dozeHost.stopAuroraPulse()
        window.hide()
    }

    private fun stopProximity() {
        proximityListener?.let { sensors.unregisterListener(it) }
        proximityListener = null
        if (pocketWakeLock.isHeld) pocketWakeLock.release()
    }

    private fun reset() {
        finishDarkSession()
        previewId = null
        hiddenUntil.clear()
        contents.clear()
        handler.removeCallbacks(endPreview)
        window.hide()
        window.forgetBubbles()
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("enabled=${config.enabled}, profiles=${config.profiles.size}, dark=$darkSession")
        pw.println("quiet=${quiet()}, window=${window.isShowing}, switchingUser=$switchingUser")
        pw.println("gaming=${isGaming()}")
        pw.println(
            "ambient=${dozeHost.auroraPulseBrightness}, brightness=${config.brightness}, " +
                "timeout=${config.timeout}, clock=${config.clock}, checkingPocket=$checkingPocket"
        )
    }

    companion object {
        private const val TAG = "EdgeAurora"
        private const val KEY_GAMING_MODE_ACTIVE = "ax_gaming_mode_active"
        private val EXCLUDED =
            setOf(
                Notification.CATEGORY_CALL,
                Notification.CATEGORY_ALARM,
                Notification.CATEGORY_SERVICE,
                Notification.CATEGORY_TRANSPORT,
                Notification.CATEGORY_PROGRESS,
                Notification.CATEGORY_SYSTEM,
                "navigation",
                "location_sharing",
                "workout",
                "stopwatch",
            )

        internal fun replyAction(notification: Notification): Notification.Action? {
            val actions =
                (notification.actions?.toList().orEmpty() +
                        Notification.WearableExtender(notification).actions)
                    .filter {
                        it.actionIntent != null &&
                            it.remoteInputs?.any { input -> input.allowFreeFormInput } == true
                    }
            return actions.firstOrNull {
                it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
            } ?: actions.firstOrNull()
        }
    }
}
