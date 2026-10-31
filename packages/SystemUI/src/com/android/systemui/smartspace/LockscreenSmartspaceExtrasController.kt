/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.smartspace

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.smartspace.SmartspaceTarget
import android.app.smartspace.SmartspaceTargetEvent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.media.NotificationMediaManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.UserTracker
import com.android.systemui.smartspace.SmartspaceExtrasCard.Type
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.util.settings.SecureSettings
import com.google.android.systemui.smartspace.BcSmartspaceView
import com.google.android.systemui.smartspace.PagerDots
import java.time.LocalDateTime
import java.util.Date
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@SysUISingleton
class LockscreenSmartspaceExtrasController
@Inject
constructor(
    @Application private val context: Context,
    private val secureSettings: SecureSettings,
    private val userTracker: UserTracker,
    private val keyguardInteractor: KeyguardInteractor,
    private val transitionInteractor: KeyguardTransitionInteractor,
    private val mediaManager: NotificationMediaManager,
    private val notifications: CommonNotifCollection,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val flashlightController: FlashlightController,
    private val batteryController: BatteryController,
    private val nextAlarmController: NextAlarmController,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val activityStarter: ActivityStarter,
    private val falsingManager: FalsingManager,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Main private val mainExecutor: Executor,
    @Background private val backgroundExecutor: Executor,
    @Main private val handler: Handler,
) {
    private val views = mutableSetOf<ExtrasView>()
    private var visibilityJob: Job? = null
    private var lockscreenVisible = false
    private var dozing = false
    private var switchingUser = false
    private var replacingSmartspace = false
    private var active = false
    private var activeUserId = UserHandle.USER_NULL
    private var observingFlashlight = false
    private var observingStatus = false
    private var observingNotifications = false
    private var bluetooth: SmartspaceExtrasBluetoothController? = null
    private var bluetoothCards = emptyList<SmartspaceExtrasCard>()
    private var mediaListener: NotificationMediaManager.MediaListener? = null
    private var mediaMetadata: MediaMetadata? = null
    private var mediaState = PlaybackState.STATE_NONE
    private var mediaKey: String? = null
    private var mediaArtwork: Icon? = null
    private var messageSlot: LocalDateTime? = null
    private var message = ""
    private var targetSource: SmartspaceExtrasTargetSource? = null
    private var nextAlarm: AlarmManager.AlarmClockInfo? = null
    private var batteryLevel = -1
    private var charging = false
    private var calendarSource: SmartspaceExtrasCalendarSource? = null
    private val publishRunnable = Runnable { publish() }
    private val grayscaleFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    private val collectionListener =
        object : NotifCollectionListener {
            override fun onEntryAdded(entry: NotificationEntry) = requestPublish()

            override fun onEntryUpdated(entry: NotificationEntry) = requestPublish()

            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) = requestPublish()

            override fun onRankingApplied() = requestPublish()

            override fun onNotificationChannelModified(
                pkgName: String,
                user: UserHandle,
                channel: NotificationChannel,
                modificationType: Int,
            ) = requestPublish()
        }

    private val provisioningListener =
        object : DeviceProvisionedController.DeviceProvisionedListener {
            override fun onDeviceProvisionedChanged() = updateSources()

            override fun onUserSetupChanged() = updateSources()
        }

    private val flashlightCallback =
        object : FlashlightController.FlashlightListener {
            override fun onFlashlightChanged(enabled: Boolean) = mainExecutor.execute { publish() }

            override fun onFlashlightAvailabilityChanged(available: Boolean) =
                mainExecutor.execute { publish() }

            override fun onFlashlightError() = mainExecutor.execute { publish() }
        }

    private val batteryCallback =
        object : BatteryController.BatteryStateChangeCallback {
            override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
                batteryLevel = level
                this@LockscreenSmartspaceExtrasController.charging = charging
                publish()
            }
        }

    private val alarmCallback =
        NextAlarmController.NextAlarmChangeCallback {
            nextAlarm =
                if (isActiveForCurrentUser()) {
                    try {
                        userTracker.userContext
                            .getSystemService(AlarmManager::class.java)
                            ?.nextAlarmClock
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "Unable to read the current user's alarm", e)
                        null
                    }
                } else null
            publish()
        }

    private val settingsObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = updateSources()
        }

    private val notificationStateListener =
        NotificationLockscreenUserManager.NotificationStateChangedListener { updateSources() }

    private val userCallback =
        object : UserTracker.Callback {
            override fun onBeforeUserSwitching(newUser: Int) {
                switchingUser = true
                updateSources()
            }

            override fun onUserChanging(newUser: Int, userContext: Context) {
                switchingUser = true
                updateSources()
            }

            override fun onUserChanged(newUser: Int, userContext: Context) {
                switchingUser = false
                messageSlot = null
                updateSources()
            }
        }

    private val timeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_TIME_TICK) messageSlot = null
                calendarSource?.refresh()
                publish()
            }
        }

    fun attachToSmartspace(view: BcSmartspaceView) {
        view.setExtrasView(buildView(view.context, view::setExtrasEnabled))
    }

    fun buildView(viewContext: Context, onReplacementChanged: (Boolean) -> Unit = {}): View {
        val holder =
            ExtrasView(
                LayoutInflater.from(viewContext)
                    .inflate(R.layout.lockscreen_smartspace_extras, null)
                    .apply { id = R.id.lockscreen_smartspace_extras },
                onReplacementChanged,
            )
        holder.root.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    views.add(holder)
                    if (views.size == 1) startObserving()
                    publish()
                }

                override fun onViewDetachedFromWindow(view: View) {
                    holder.clear()
                    views.remove(holder)
                    if (views.isEmpty()) stopObserving()
                }
            }
        )
        return holder.root
    }

    private fun startObserving() {
        userTracker.addCallback(userCallback, mainExecutor)
        deviceProvisionedController.addCallback(provisioningListener)
        lockscreenUserManager.addNotificationStateChangedListener(notificationStateListener)
        SETTING_KEYS.forEach { key ->
            context.contentResolver.registerContentObserver(
                secureSettings.getUriFor(key),
                false,
                settingsObserver,
                UserHandle.USER_ALL,
            )
        }
        visibilityJob =
            applicationScope.launch(mainDispatcher) {
                val lockscreenOrAod =
                    if (SceneContainerFlag.isEnabled) {
                        transitionInteractor.isCurrentlyIn(
                            Scenes.Lockscreen,
                            KeyguardState.LOCKSCREEN,
                        )
                    } else {
                        transitionInteractor.currentKeyguardState.map {
                            it == KeyguardState.LOCKSCREEN || it == KeyguardState.AOD
                        }
                    }
                launch {
                    keyguardInteractor.dozeTimeTick.collect {
                        if (isActiveForCurrentUser() && dozing) {
                            calendarSource?.refresh()
                            publish()
                        }
                    }
                }
                combine(
                        combine(
                            lockscreenOrAod,
                            keyguardInteractor.isDozing,
                            keyguardInteractor.isAodAvailable,
                        ) { locked, isDozing, aodAvailable ->
                            (locked && (!isDozing || aodAvailable)) to isDozing
                        },
                        keyguardInteractor.isKeyguardOccluded,
                        keyguardInteractor.primaryBouncerShowing,
                        keyguardInteractor.alternateBouncerShowing,
                    ) { state, occluded, primaryBouncer, alternateBouncer ->
                        (state.first && !occluded && !primaryBouncer && !alternateBouncer) to
                            state.second
                    }
                    .distinctUntilChanged()
                    .collect {
                        lockscreenVisible = it.first
                        dozing = it.second
                        updateSources()
                    }
            }
    }

    private fun stopObserving() {
        visibilityJob?.cancel()
        visibilityJob = null
        lockscreenVisible = false
        dozing = false
        switchingUser = false
        updateSources()
        userTracker.removeCallback(userCallback)
        deviceProvisionedController.removeCallback(provisioningListener)
        lockscreenUserManager.removeNotificationStateChangedListener(notificationStateListener)
        context.contentResolver.unregisterContentObserver(settingsObserver)
        handler.removeCallbacks(publishRunnable)
    }

    private fun enabled(key: String, default: Int = 1): Boolean =
        secureSettings.getIntForUser(key, default, userTracker.userId) == 1

    private fun updateSources() {
        replacingSmartspace =
            views.isNotEmpty() &&
                lockscreenVisible &&
                !switchingUser &&
                !userTracker.isUserSwitching &&
                deviceProvisionedController.isDeviceProvisioned &&
                deviceProvisionedController.isCurrentUserSetup &&
                secureSettings.getIntForUser(
                    Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE,
                    0,
                    userTracker.userId,
                ) == 0 &&
                enabled(ENABLED, 0)
        val shouldBeActive =
            replacingSmartspace &&
                listOf(
                        BLUETOOTH,
                        MUSIC,
                        MESSAGES,
                        WEATHER,
                        FLASHLIGHT,
                        DEFAULT_EVENTS,
                        NAVIGATION,
                        CALENDAR,
                    )
                    .any { enabled(it) }
        if (shouldBeActive != active) {
            active = shouldBeActive
            activeUserId = if (active) userTracker.userId else UserHandle.USER_NULL
            if (active) {
                broadcastDispatcher.registerReceiver(
                    timeReceiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_TIME_TICK)
                        addAction(Intent.ACTION_TIME_CHANGED)
                        addAction(Intent.ACTION_TIMEZONE_CHANGED)
                        addAction(Intent.ACTION_LOCALE_CHANGED)
                    },
                    mainExecutor,
                    UserHandle.ALL,
                )
            } else {
                broadcastDispatcher.unregisterReceiver(timeReceiver)
            }
        }

        val wantFlashlight = active && enabled(FLASHLIGHT)
        if (wantFlashlight != observingFlashlight) {
            observingFlashlight = wantFlashlight
            if (wantFlashlight) flashlightController.addCallback(flashlightCallback)
            else flashlightController.removeCallback(flashlightCallback)
        }
        val wantStatus = active && enabled(DEFAULT_EVENTS)
        if (wantStatus != observingStatus) {
            observingStatus = wantStatus
            if (wantStatus) {
                batteryController.addCallback(batteryCallback)
                nextAlarmController.addCallback(alarmCallback)
            } else {
                batteryController.removeCallback(batteryCallback)
                nextAlarmController.removeCallback(alarmCallback)
                nextAlarm = null
                batteryLevel = -1
            }
        }
        val wantNotifications = active && (enabled(NAVIGATION) || enabled(MUSIC))
        if (wantNotifications != observingNotifications) {
            observingNotifications = wantNotifications
            if (wantNotifications) notifications.addCollectionListener(collectionListener)
            else notifications.removeCollectionListener(collectionListener)
        }

        val wantTargets =
            active &&
                listOf(WEATHER, DEFAULT_EVENTS, BLUETOOTH, MUSIC, NAVIGATION, CALENDAR).any {
                    enabled(it)
                }
        if (wantTargets && targetSource == null) {
            targetSource =
                SmartspaceExtrasTargetSource(
                        userTracker.userContext,
                        mainExecutor,
                        handler,
                        ::publish,
                    )
                    .also { it.start() }
        } else if (!wantTargets) {
            targetSource?.stop()
            targetSource = null
        }

        val wantCalendar = active && enabled(CALENDAR) && allowPersonalContent()
        if (wantCalendar && calendarSource == null) {
            calendarSource =
                SmartspaceExtrasCalendarSource(
                        userTracker.userContext,
                        backgroundExecutor,
                        handler,
                        ::publish,
                    )
                    .also { it.start() }
        } else if (!wantCalendar) {
            calendarSource?.stop()
            calendarSource = null
        }

        val wantBluetooth = active && enabled(BLUETOOTH)
        if (wantBluetooth && bluetooth == null) {
            bluetooth =
                SmartspaceExtrasBluetoothController(context, mainExecutor, backgroundExecutor) {
                        cards ->
                        bluetoothCards = cards
                        publish()
                    }
                    .also { it.start() }
        } else if (!wantBluetooth) {
            bluetooth?.stop()
            bluetooth = null
            bluetoothCards = emptyList()
        }

        val wantMusic = active && enabled(MUSIC)
        if (wantMusic && mediaListener == null) {
            val listener =
                object : NotificationMediaManager.MediaListener {
                    override fun onPrimaryMetadataOrStateChanged(
                        metadata: MediaMetadata?,
                        state: Int,
                    ) {
                        if (mediaListener !== this) return
                        if (mediaMetadata !== metadata) {
                            val art =
                                metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                            mediaArtwork = art?.let(Icon::createWithBitmap)
                        }
                        mediaMetadata = metadata
                        mediaState = state
                        mediaKey = mediaManager.mediaNotificationKey
                        publish()
                    }
                }
            mediaListener = listener
            mediaManager.addCallback(listener)
        } else if (!wantMusic) {
            mediaListener?.let(mediaManager::removeCallback)
            mediaListener = null
            mediaMetadata = null
            mediaState = PlaybackState.STATE_NONE
            mediaKey = null
            mediaArtwork = null
        }
        publish()
    }

    private fun mediaCard(): SmartspaceExtrasCard? {
        if (mediaListener == null || !NotificationMediaManager.isPlayingState(mediaState))
            return null
        val key = mediaKey?.takeIf { it == mediaManager.mediaNotificationKey } ?: return null
        val entry = notifications.getEntry(key) ?: return null
        if (!canShowNotification(entry)) return null
        val metadata = mediaMetadata ?: return null
        val title =
            metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
                ?: metadata.getText(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.music_controls_no_title)
        val artist = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        return SmartspaceExtrasCard(
            "media_$key",
            title,
            artist,
            mediaArtwork,
            R.drawable.ic_music_note,
            entry.sbn.notification.contentIntent,
            null,
            Type.MUSIC,
        )
    }

    private fun messageCard(): SmartspaceExtrasCard {
        val now = LocalDateTime.now()
        val slot = now.withMinute(now.minute / 30 * 30).withSecond(0).withNano(0)
        val (greeting, pool) =
            when (SmartspaceExtrasPolicy.greetingPeriod(now.hour)) {
                SmartspaceExtrasPolicy.MORNING ->
                    R.string.smartspace_extras_morning to R.array.smartspace_extras_messages_morning
                SmartspaceExtrasPolicy.AFTERNOON ->
                    R.string.smartspace_extras_afternoon to
                        R.array.smartspace_extras_messages_afternoon
                SmartspaceExtrasPolicy.EVENING ->
                    R.string.smartspace_extras_evening to R.array.smartspace_extras_messages_evening
                else -> R.string.smartspace_extras_night to R.array.smartspace_extras_messages_night
            }
        if (messageSlot != slot) {
            val allMessages = context.resources.getStringArray(pool).toList()
            val messages = allMessages.filter { it != message }.ifEmpty { allMessages }
            message = messages[Random.nextInt(messages.size)]
            messageSlot = slot
        }
        return SmartspaceExtrasCard(
            MESSAGE_ID,
            context.getString(greeting),
            message,
            null,
            0,
            null,
            null,
        )
    }

    private fun isActiveForCurrentUser(): Boolean =
        active &&
            !switchingUser &&
            !userTracker.isUserSwitching &&
            activeUserId == userTracker.userId

    private fun requestPublish() {
        handler.removeCallbacks(publishRunnable)
        handler.post(publishRunnable)
    }

    private fun publish() {
        val cards =
            if (isActiveForCurrentUser())
                buildList {
                    if (enabled(NAVIGATION)) addAll(navigationCards())
                    if (enabled(FLASHLIGHT) && flashlightController.isEnabled) {
                        add(
                            SmartspaceExtrasCard(
                                TORCH_ID,
                                context.getString(R.string.smartspace_extras_torch_on),
                                context.getString(R.string.smartspace_extras_torch_off_action),
                                null,
                                R.drawable.ic_dynamic_island_flashlight,
                                null,
                                null,
                                Type.TORCH,
                            )
                        )
                    }
                    addAll(bluetoothCards)
                    mediaCard()?.let(::add)
                    addAll(
                        targetSource?.cards(
                            userTracker.userHandle,
                            ::allowTarget,
                            ::allowEmbeddedFeature,
                        ) ?: emptyList()
                    )
                    if (enabled(CALENDAR) && allowPersonalContent()) {
                        addAll(calendarSource?.cards() ?: emptyList())
                    }
                    if (enabled(DEFAULT_EVENTS)) {
                        upcomingAlarm()?.let { alarm ->
                            add(
                                SmartspaceExtrasCard(
                                    "alarm",
                                    context.getString(R.string.smartspace_extras_next_alarm),
                                    DateFormat.getTimeFormat(userTracker.userContext)
                                        .format(Date(alarm.triggerTime)),
                                    null,
                                    R.drawable.ic_alarm,
                                    alarm.showIntent,
                                    Intent(AlarmClock.ACTION_SHOW_ALARMS),
                                )
                            )
                        }
                        if (batteryLevel in 0..100 && (charging || batteryLevel <= 20)) {
                            add(
                                SmartspaceExtrasCard(
                                    "battery",
                                    context.getString(
                                        if (charging) R.string.smartspace_extras_charging
                                        else R.string.smartspace_extras_low_battery
                                    ),
                                    context.getString(
                                        R.string.smartspace_extras_bluetooth_battery,
                                        batteryLevel,
                                    ),
                                    null,
                                    R.drawable.ic_statusbar_battery,
                                    null,
                                    Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                                )
                            )
                        }
                    }
                    if (enabled(MESSAGES)) add(messageCard())
                }
            else emptyList()
        val sortedCards = cards.sortedBy { it.type.priority }
        views.forEach { it.bind(sortedCards) }
    }

    private fun allowTarget(target: SmartspaceTarget): Boolean {
        val screens = target.baseAction?.extras?.getInt("SCREEN_EXTRA", 3) ?: 3
        if (screens and (if (dozing) 2 else 1) == 0) return false
        val weather =
            target.featureType in
                setOf(
                    SmartspaceTarget.FEATURE_WEATHER,
                    SmartspaceTarget.FEATURE_WEATHER_ALERT,
                    SmartspaceTarget.FEATURE_SEVERE_WEATHER_ALERT,
                )
        if (
            !weather &&
                (!enabled(Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS) ||
                    !lockscreenUserManager.shouldShowLockscreenNotifications() ||
                    !lockscreenUserManager.userAllowsNotificationsInPublic(userTracker.userId))
        )
            return false
        if (target.isSensitive && !allowPersonalContent()) {
            return false
        }
        return when (target.featureType) {
            SmartspaceTarget.FEATURE_WEATHER,
            SmartspaceTarget.FEATURE_WEATHER_ALERT,
            SmartspaceTarget.FEATURE_SEVERE_WEATHER_ALERT -> enabled(WEATHER)
            SmartspaceTarget.FEATURE_FLASHLIGHT -> false
            SmartspaceTarget.FEATURE_PAIRED_DEVICE_STATE ->
                enabled(BLUETOOTH) && bluetoothCards.isEmpty()
            SmartspaceTarget.FEATURE_MEDIA,
            SmartspaceTarget.FEATURE_MEDIA_RESUME,
            SmartspaceTarget.FEATURE_MEDIA_HEADS_UP ->
                enabled(MUSIC) &&
                    allowPersonalContent() &&
                    mediaManager.mediaNotificationKey == null
            SmartspaceTarget.FEATURE_ALARM,
            SmartspaceTarget.FEATURE_UPCOMING_ALARM ->
                enabled(DEFAULT_EVENTS) && upcomingAlarm() == null
            SmartspaceTarget.FEATURE_CALENDAR ->
                enabled(CALENDAR) &&
                    allowPersonalContent() &&
                    calendarSource?.cards().isNullOrEmpty()
            SmartspaceTarget.FEATURE_COMMUTE_TIME,
            SmartspaceTarget.FEATURE_ETA_MONITORING,
            SmartspaceTarget.FEATURE_DRIVING_MODE,
            SmartspaceTarget.FEATURE_TIME_TO_LEAVE ->
                enabled(NAVIGATION) && allowPersonalContent() && navigationEntries().isEmpty()
            else -> enabled(DEFAULT_EVENTS)
        }
    }

    private fun allowEmbeddedFeature(feature: Int): Boolean =
        when (feature) {
            SmartspaceTarget.FEATURE_UNDEFINED -> true
            SmartspaceTarget.FEATURE_STEP_DATE,
            SmartspaceTarget.FEATURE_WEATHER -> false
            SmartspaceTarget.FEATURE_WEATHER_ALERT,
            SmartspaceTarget.FEATURE_SEVERE_WEATHER_ALERT -> enabled(WEATHER)
            SmartspaceTarget.FEATURE_FLASHLIGHT -> enabled(FLASHLIGHT)
            SmartspaceTarget.FEATURE_PAIRED_DEVICE_STATE -> enabled(BLUETOOTH)
            SmartspaceTarget.FEATURE_MEDIA,
            SmartspaceTarget.FEATURE_MEDIA_RESUME,
            SmartspaceTarget.FEATURE_MEDIA_HEADS_UP -> enabled(MUSIC) && allowPersonalContent()
            SmartspaceTarget.FEATURE_CALENDAR -> enabled(CALENDAR) && allowPersonalContent()
            SmartspaceTarget.FEATURE_COMMUTE_TIME,
            SmartspaceTarget.FEATURE_ETA_MONITORING,
            SmartspaceTarget.FEATURE_DRIVING_MODE,
            SmartspaceTarget.FEATURE_TIME_TO_LEAVE -> enabled(NAVIGATION) && allowPersonalContent()
            else -> enabled(DEFAULT_EVENTS)
        }

    private fun allowPersonalContent(): Boolean =
        enabled(Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS) &&
            enabled(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0) &&
            lockscreenUserManager.shouldShowLockscreenNotifications() &&
            lockscreenUserManager.userAllowsNotificationsInPublic(userTracker.userId) &&
            lockscreenUserManager.userAllowsPrivateNotificationsInPublic(userTracker.userId)

    private fun canShowNotification(entry: NotificationEntry): Boolean =
        !entry.isCanceled &&
            (entry.sbn.userId == userTracker.userId || entry.sbn.userId == UserHandle.USER_ALL) &&
            enabled(Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS) &&
            lockscreenUserManager.shouldShowLockscreenNotifications() &&
            lockscreenUserManager.userAllowsNotificationsInPublic(userTracker.userId) &&
            lockscreenUserManager.getRedactionType(entry) ==
                NotificationLockscreenUserManager.REDACTION_TYPE_NONE &&
            entry.sbn.notification.visibility != Notification.VISIBILITY_SECRET &&
            (entry.sbn.notification.visibility == Notification.VISIBILITY_PUBLIC ||
                allowPersonalContent())

    private fun navigationEntries(): List<NotificationEntry> =
        notifications.allNotifs.filter {
            !it.isCanceled &&
                !it.sbn.notification.isGroupSummary &&
                (it.sbn.userId == userTracker.userId || it.sbn.userId == UserHandle.USER_ALL) &&
                it.sbn.notification.category == Notification.CATEGORY_NAVIGATION &&
                it.sbn.isOngoing
        }

    private fun navigationCards(): List<SmartspaceExtrasCard> =
        navigationEntries()
            .filter(::canShowNotification)
            .sortedByDescending { it.sbn.postTime }
            .mapNotNull { entry ->
                val notification = entry.sbn.notification
                val extras = notification.extras
                val title =
                    extras.getCharSequence(Notification.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                val details =
                    listOfNotNull(
                            extras.getCharSequence(Notification.EXTRA_TEXT),
                            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
                        )
                        .filter { it.isNotBlank() && it.toString() != title.toString() }
                        .map { it.toString() }
                        .distinct()
                        .joinToString(" · ")
                SmartspaceExtrasCard(
                    "navigation_${entry.key}",
                    title,
                    details,
                    notification.getLargeIcon(),
                    R.drawable.ic_location,
                    notification.contentIntent,
                    null,
                    Type.NAVIGATION,
                )
            }

    private fun upcomingAlarm(): AlarmManager.AlarmClockInfo? {
        val now = System.currentTimeMillis()
        return nextAlarm?.takeIf {
            it.triggerTime > now &&
                it.triggerTime - now <= 12 * 60 * 60 * 1000L &&
                (it.showIntent == null || it.showIntent.creatorUserHandle == userTracker.userHandle)
        }
    }

    private inner class ExtrasView(
        val root: View,
        private val onReplacementChanged: (Boolean) -> Unit,
    ) {
        private val pager = root.requireViewById<ViewPager>(R.id.smartspace_extras_pager)
        private val indicator = root.requireViewById<PagerDots>(R.id.smartspace_extras_indicator)
        private var cards = emptyList<SmartspaceExtrasCard>()
        private var selectedId: String? = null
        private var shownId: String? = null
        private var updatingCards = false
        private var boundDozing = false
        private val adapter =
            object : PagerAdapter() {
                override fun getCount(): Int = cards.size

                override fun isViewFromObject(view: View, item: Any): Boolean =
                    view === (item as CardView).root

                override fun instantiateItem(container: ViewGroup, position: Int): Any {
                    val holder =
                        CardView(
                            LayoutInflater.from(container.context)
                                .inflate(
                                    R.layout.lockscreen_smartspace_extras_card,
                                    container,
                                    false,
                                )
                        )
                    holder.bind(cards[position])
                    container.addView(holder.root)
                    return holder
                }

                override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
                    val holder = item as CardView
                    holder.clear()
                    container.removeView(holder.root)
                }

                override fun getItemPosition(item: Any): Int {
                    val holder = item as CardView
                    val position = cards.indexOfFirst { it.id == holder.cardId }
                    if (position < 0) return POSITION_NONE
                    holder.bind(cards[position])
                    return position
                }

                override fun getPageTitle(position: Int): CharSequence = cards[position].title
            }

        init {
            indicator.primaryColor = Color.WHITE
            indicator.paint.color = Color.WHITE
            pager.adapter = adapter
            pager.addOnPageChangeListener(
                object : ViewPager.SimpleOnPageChangeListener() {
                    override fun onPageSelected(position: Int) {
                        if (updatingCards) return
                        selectedId = cards.getOrNull(position)?.id
                        updateSelection()
                    }

                    override fun onPageScrolled(position: Int, offset: Float, offsetPixels: Int) {
                        indicator.currentPositionIndex = position
                        indicator.currentPositionOffset = offset
                        indicator.invalidate()
                    }
                }
            )
        }

        fun bind(newCards: List<SmartspaceExtrasCard>) {
            val appearanceChanged = boundDozing != dozing
            boundDozing = dozing
            pager.isEnabled = !dozing
            pager.importantForAccessibility =
                if (dozing) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            indicator.alpha = if (dozing) 0f else 1f
            if (cards == newCards && !appearanceChanged) {
                onReplacementChanged(replacingSmartspace)
                return
            }
            val previousFirst = cards.firstOrNull()
            val first = newCards.firstOrNull()
            if (
                first?.id != previousFirst?.id ||
                    first?.type?.priority != previousFirst?.type?.priority ||
                    newCards.none { it.id == selectedId }
            ) {
                selectedId = first?.id
            }
            cards = newCards
            val position = cards.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
            updatingCards = true
            adapter.notifyDataSetChanged()
            pager.setCurrentItem(position, false)
            updatingCards = false
            indicator.setNumPages(cards.size, false)
            indicator.currentPositionIndex = position
            indicator.currentPositionOffset = 0f
            indicator.invalidate()
            root.visibility = if (cards.isEmpty()) View.GONE else View.VISIBLE
            onReplacementChanged(replacingSmartspace)
            pager.requestLayout()
            updateSelection()
        }

        fun clear() {
            bind(emptyList())
            onReplacementChanged(false)
        }

        private fun updateSelection() {
            if (shownId != selectedId) {
                targetSource?.notifyEvent(shownId, SmartspaceTargetEvent.EVENT_TARGET_HIDDEN)
                targetSource?.notifyEvent(selectedId, SmartspaceTargetEvent.EVENT_TARGET_SHOWN)
                shownId = selectedId
            }
            indicator.updateCurrentPageIndex(pager.currentItem)
        }
    }

    private inner class CardView(val root: View) {
        private val title = root.requireViewById<TextView>(R.id.smartspace_extras_title)
        private val subtitle = root.requireViewById<TextView>(R.id.smartspace_extras_subtitle)
        private val icon = root.requireViewById<ImageView>(R.id.smartspace_extras_icon)
        private val text = root.requireViewById<View>(R.id.smartspace_extras_text)
        private var boundCard: SmartspaceExtrasCard? = null
        private var iconGeneration = 0
        val cardId: String?
            get() = boundCard?.id

        init {
            icon.outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(
                            0,
                            0,
                            view.width,
                            view.height,
                            view.resources.getDimension(R.dimen.smartspace_extras_album_art_radius),
                        )
                    }
                }
            text.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateIconSize() }
            root.setOnClickListener {
                if (
                    dozing ||
                        !isActiveForCurrentUser() ||
                        falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)
                ) {
                    return@setOnClickListener
                }
                boundCard?.let { card ->
                    targetSource?.notifyEvent(
                        card.id,
                        SmartspaceTargetEvent.EVENT_TARGET_INTERACTION,
                    )
                    if (card.id == TORCH_ID) {
                        flashlightController.setFlashlight(false)
                        return@setOnClickListener
                    }
                    card.pendingIntent?.let(activityStarter::startPendingIntentDismissingKeyguard)
                        ?: card.intent?.let {
                            activityStarter.postStartActivityDismissingKeyguard(it, 0)
                        }
                }
            }
        }

        fun clear() {
            boundCard = null
            iconGeneration++
            icon.setImageDrawable(null)
            title.text = ""
            subtitle.text = ""
            root.contentDescription = null
            root.isClickable = false
            root.isFocusable = false
        }

        fun bind(card: SmartspaceExtrasCard) {
            updateAppearance(card)
            if (boundCard == card) return
            boundCard = card
            title.text = card.title
            subtitle.text = card.subtitle
            subtitle.maxLines = if (card.id == MESSAGE_ID || card.isMusic) 1 else 2
            subtitle.visibility = if (card.subtitle.isBlank()) View.GONE else View.VISIBLE
            root.contentDescription =
                listOf(card.title, card.subtitle).filter { it.isNotBlank() }.joinToString(", ")
            icon.scaleType =
                if (card.isMusic) ImageView.ScaleType.CENTER_CROP
                else ImageView.ScaleType.FIT_CENTER
            icon.clipToOutline = card.isMusic
            updateIconSize()
            icon.visibility =
                if (card.icon == null && card.fallbackIcon == 0) View.GONE else View.VISIBLE
            icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            icon.setImageResource(card.fallbackIcon)
            val generation = ++iconGeneration
            card.icon?.loadDrawableAsync(
                root.context,
                { drawable ->
                    if (
                        generation == iconGeneration && root.isAttachedToWindow && drawable != null
                    ) {
                        icon.imageTintList = null
                        icon.setImageDrawable(drawable)
                    }
                },
                handler,
            )
        }

        private fun updateAppearance(card: SmartspaceExtrasCard) {
            icon.colorFilter = if (dozing && card.isMusic) grayscaleFilter else null
            root.isClickable =
                !dozing &&
                    (card.id == TORCH_ID || card.pendingIntent != null || card.intent != null)
            root.isFocusable = root.isClickable
        }

        private fun updateIconSize() {
            val card = boundCard ?: return
            val size =
                if (card.isMusic) {
                    maxOf(
                        root.resources.getDimensionPixelSize(
                            R.dimen.smartspace_extras_album_art_size
                        ),
                        text.height,
                    )
                } else root.resources.getDimensionPixelSize(R.dimen.smartspace_extras_icon_size)
            val params = icon.layoutParams
            if (params.width != size || params.height != size) {
                params.width = size
                params.height = size
                icon.layoutParams = params
            }
        }
    }

    companion object {
        const val ENABLED = "lockscreen_smartspace_extras_enabled"
        const val BLUETOOTH = "lockscreen_smartspace_extras_bluetooth"
        const val MUSIC = "lockscreen_smartspace_extras_music"
        const val MESSAGES = "lockscreen_smartspace_extras_messages"
        const val WEATHER = "lockscreen_smartspace_extras_weather"
        const val FLASHLIGHT = "lockscreen_smartspace_extras_flashlight"
        const val DEFAULT_EVENTS = "lockscreen_smartspace_extras_default_events"
        const val NAVIGATION = "lockscreen_smartspace_extras_navigation"
        const val CALENDAR = "lockscreen_smartspace_extras_calendar"
        private const val MESSAGE_ID = "message"
        private const val TORCH_ID = "torch"
        private const val TAG = "LockscreenSmartspaceExtras"
        private val SETTING_KEYS =
            listOf(
                ENABLED,
                BLUETOOTH,
                MUSIC,
                MESSAGES,
                WEATHER,
                FLASHLIGHT,
                DEFAULT_EVENTS,
                NAVIGATION,
                CALENDAR,
                Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_STYLE,
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
            )
    }
}
