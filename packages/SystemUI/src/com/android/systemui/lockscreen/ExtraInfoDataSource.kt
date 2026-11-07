/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.lockscreen

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.usage.NetworkStatsManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerExecutor
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.view.KeyEvent
import com.android.internal.util.lunaris.OmniJawsClient
import com.android.systemui.media.NotificationMediaManager
import java.time.LocalDate
import java.util.Calendar
import org.lunaris.extrainfowidgets.InfoData
import org.lunaris.extrainfowidgets.InfoWeather
import org.lunaris.extrainfowidgets.InfoWidget

internal class ExtraInfoDataSource(
    private val context: Context,
    private val userId: Int,
    widgets: List<InfoWidget>,
    private val onData: (InfoData) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("ExtraInfoWidgets")
    private lateinit var worker: Handler
    @Volatile private var running = false
    @Volatile private var awake = false
    private var stepsAvailable = false
    private var current = InfoData()
    private var observedMedia: MediaController? = null
    private val mediaSessions = context.getSystemService(MediaSessionManager::class.java)

    private data class ArtworkKey(
        val mediaId: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val uris: List<Uri>,
    )

    private var artworkKey: ArtworkKey? = null
    private var artwork: Bitmap? = null
    private val mediaCallback =
        object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                if (!running || !awake) return
                clearArtwork()
                current = withMetadata(current, metadata)
                publish()
                refresh()
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (!running || !awake) return
                val isPlaying =
                    state?.let { NotificationMediaManager.isPlayingState(it.state) } ?: false
                current = withMetadata(current, observedMedia?.metadata).copy(playing = isPlaying)
                publish()
                refresh()
            }

            override fun onSessionDestroyed() {
                observedMedia?.unregisterCallback(this)
                observedMedia = null
                clearArtwork()
                current = withMetadata(current, null).copy(playing = false)
                publish()
                refresh()
            }
        }
    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateMediaController(controllers.orEmpty())
        }
    private var slowUpdate = 0L
    private val types =
        widgets
            .flatMap { widget ->
                widget.design.getJSONArray("layers").let { layers ->
                    (0 until layers.length()).map { layers.getJSONObject(it).getString("type") }
                }
            }
            .toSet()
    private val needsMedia =
        types.any { it.startsWith("MEDIA_") } ||
            widgets.any { widget ->
                val layers = widget.design.getJSONArray("layers")
                (0 until layers.length()).any {
                    layers.getJSONObject(it).optString("tile").startsWith("MEDIA")
                }
            }
    private val needsWeather = types.any { it.startsWith("WEATHER_") }
    private var weatherObserved = false
    private val weatherClient by lazy { OmniJawsClient() }
    private val weatherUpdate = Runnable {
        if (running) {
            current = withWeather(current)
            publish()
        }
    }
    private val weatherObserver =
        object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                refreshWeather()
            }
        }
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val prefs by lazy {
        context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences("extra_info_steps_$userId", Context.MODE_PRIVATE)
    }
    private val stepsListener =
        object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

            override fun onSensorChanged(event: SensorEvent) {
                if (!running) return
                val counter = event.values.firstOrNull()?.toInt() ?: return
                val today = LocalDate.now().toString()
                val last = prefs.getInt("counter", counter)
                val sameDay = prefs.getString("day", null) == today
                val previous = if (sameDay) prefs.getInt("steps", 0) else 0
                val steps =
                    previous +
                        if (sameDay) (if (counter >= last) counter - last else counter) else 0
                prefs
                    .edit()
                    .putString("day", today)
                    .putInt("counter", counter)
                    .putInt("steps", steps)
                    .apply()
                current = current.copy(steps = steps)
                publish()
            }
        }
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!running) return
                if (intent.action == Intent.ACTION_USER_UNLOCKED) {
                    observeWeather()
                    refreshWeather()
                }
                if (
                    intent.action == OmniJawsClient.WEATHER_UPDATE ||
                        intent.action == OmniJawsClient.WEATHER_ERROR
                ) {
                    refreshWeather()
                    return
                }
                worker.post {
                    if (intent.action == Intent.ACTION_USER_UNLOCKED) clearArtwork()
                    if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                        current = withBattery(current, intent)
                        publish()
                    } else {
                        if (types.any { it.startsWith("ALARM_") }) {
                            current = withAlarm(current)
                            publish()
                        }
                        slowUpdate = 0
                        refresh()
                    }
                }
            }
        }
    private val update =
        object : Runnable {
            override fun run() {
                if (!running || !awake) return
                current = runCatching { read() }.getOrDefault(current)
                publish()
                worker.postDelayed(this, 5000)
            }
        }

    fun start() {
        if (running) return
        running = true
        thread.start()
        worker = Handler(thread.looper)
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_USER_UNLOCKED)
                addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
                if (needsWeather) {
                    addAction(OmniJawsClient.WEATHER_UPDATE)
                    addAction(OmniJawsClient.WEATHER_ERROR)
                }
            }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        if (needsWeather) {
            observeWeather()
            refreshWeather()
        }
        if ("STEPS" in types) {
            worker.post {
                if (!running) return@post
                stepsAvailable =
                    runCatching {
                            sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                                sensorManager.registerListener(
                                    stepsListener,
                                    it,
                                    SensorManager.SENSOR_DELAY_NORMAL,
                                    60_000_000,
                                    worker,
                                )
                            } ?: false
                        }
                        .getOrDefault(false)
                current =
                    current.copy(
                        steps =
                            if (
                                stepsAvailable &&
                                    prefs.getString("day", null) == LocalDate.now().toString()
                            )
                                prefs.getInt("steps", 0)
                            else null
                    )
            }
        }
        if (needsMedia) {
            runCatching {
                mediaSessions?.addOnActiveSessionsChangedListener(
                    null,
                    UserHandle.of(userId),
                    HandlerExecutor(worker),
                    sessionsListener,
                )
            }
        }
        refresh()
    }

    fun setAwake(value: Boolean) {
        val waking = value && !awake
        awake = value
        if (running) {
            if (waking) {
                observeWeather()
                refreshWeather()
            }
            worker.post {
                worker.removeCallbacks(update)
                if (waking) clearArtwork()
                if (running && awake) worker.post(update)
            }
        }
    }

    fun refresh() {
        if (!running || !awake) return
        worker.post {
            worker.removeCallbacks(update)
            if (running && awake) worker.post(update)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        context.unregisterReceiver(receiver)
        if (needsWeather) {
            runCatching { context.contentResolver.unregisterContentObserver(weatherObserver) }
            weatherObserved = false
        }
        if (needsMedia) {
            runCatching { mediaSessions?.removeOnActiveSessionsChangedListener(sessionsListener) }
        }
        worker.removeCallbacksAndMessages(null)
        worker.post {
            runCatching { sensorManager?.unregisterListener(stepsListener) }
            runCatching { observedMedia?.unregisterCallback(mediaCallback) }
            observedMedia = null
            clearArtwork()
            thread.quitSafely()
        }
    }

    private fun publish() {
        val snapshot = current
        main.post { if (running) onData(snapshot) }
    }

    private fun observeWeather() {
        if (!running || !needsWeather || weatherObserved) return
        weatherObserved =
            runCatching {
                    context.contentResolver.registerContentObserver(
                        OmniJawsClient.WEATHER_URI,
                        true,
                        weatherObserver,
                    )
                    context.contentResolver.registerContentObserver(
                        OmniJawsClient.SETTINGS_URI,
                        true,
                        weatherObserver,
                    )
                    true
                }
                .getOrElse {
                    runCatching {
                        context.contentResolver.unregisterContentObserver(weatherObserver)
                    }
                    false
                }
    }

    private fun refreshWeather() {
        if (!running || !needsWeather) return
        worker.removeCallbacks(weatherUpdate)
        worker.post(weatherUpdate)
    }

    private fun withWeather(base: InfoData): InfoData =
        runCatching {
                weatherClient.queryWeather(context)
                val weather = weatherClient.weatherInfo
                base.copy(
                    weather =
                        weather?.let {
                            InfoWeather(
                                temperature =
                                    it.temp
                                        ?.takeIf { value -> value.isNotBlank() && value != "-" }
                                        ?.let { value -> "$value${it.tempUnits.orEmpty()}" },
                                condition = it.condition,
                                conditionCode = it.conditionCode,
                                icon =
                                    weatherClient.getWeatherConditionImage(
                                        context,
                                        it.conditionCode,
                                    ),
                            )
                        }
                )
            }
            .getOrElse { base.copy(weather = null) }

    private fun withBattery(base: InfoData, intent: Intent): InfoData {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return base.copy(
            battery = level.takeIf { it >= 0 }?.let { (it * 100 / scale).coerceIn(0, 100) },
            charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
        )
    }

    private fun withAlarm(base: InfoData): InfoData {
        val alarm =
            runCatching {
                    context.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
                }
                .getOrNull()
        return base.copy(
            alarm = alarm,
            alarmText = alarm?.let { DateFormat.getTimeFormat(context).format(it) },
        )
    }

    private fun extractTitle(metadata: MediaMetadata?): String? =
        metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: metadata?.description?.title?.toString()?.takeIf { it.isNotBlank() }

    private fun extractArtist(metadata: MediaMetadata?): String? =
        metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf {
                it.isNotBlank()
            }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.takeIf {
                it.isNotBlank()
            }
            ?: metadata?.description?.subtitle?.toString()?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_AUTHOR)?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_COMPOSER)?.takeIf { it.isNotBlank() }

    private fun extractArt(metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) {
            clearArtwork()
            return null
        }
        val direct =
            sequenceOf(
                    metadata.getBitmap(MediaMetadata.METADATA_KEY_ART),
                    metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
                    metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                    metadata.description?.iconBitmap,
                )
                .filterNotNull()
                .firstOrNull { !it.isRecycled }
        if (direct != null) {
            clearArtwork()
            return direct
        }

        val uris =
            listOfNotNull(
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                    metadata.getString(MediaMetadata.METADATA_KEY_ART_URI),
                    metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
                    metadata.description?.iconUri?.toString(),
                )
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
                .distinct()
        val key =
            ArtworkKey(
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                extractTitle(metadata),
                extractArtist(metadata),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                uris,
            )
        if (key != artworkKey || artwork == null || artwork?.isRecycled == true) {
            artworkKey = key
            artwork = uris.firstNotNullOfOrNull(::loadBitmapFromUri)
        }
        return artwork
    }

    private fun clearArtwork() {
        artworkKey = null
        artwork = null
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? =
        runCatching {
                if (
                    uri.scheme in
                        listOf(
                            ContentResolver.SCHEME_CONTENT,
                            ContentResolver.SCHEME_ANDROID_RESOURCE,
                            ContentResolver.SCHEME_FILE,
                        )
                ) {
                    ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(context.contentResolver, uri)
                    ) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val maxDim = 512
                        val width = info.size.width
                        val height = info.size.height
                        if (width > maxDim || height > maxDim) {
                            val scale = maxDim.toFloat() / maxOf(width, height)
                            decoder.setTargetSize(
                                (width * scale).toInt().coerceAtLeast(1),
                                (height * scale).toInt().coerceAtLeast(1),
                            )
                        }
                    }
                } else null
            }
            .getOrNull()

    private fun withMetadata(base: InfoData, metadata: MediaMetadata?): InfoData =
        base.copy(
            mediaTitle = extractTitle(metadata),
            mediaArtist = extractArtist(metadata),
            art = extractArt(metadata),
        )

    private fun read(): InfoData {
        var next = current
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        battery?.let { next = withBattery(next, it) }
        if ("STEPS" in types) {
            if (!stepsAvailable) next = next.copy(steps = null)
            else if (prefs.getString("day", null) != LocalDate.now().toString())
                next = next.copy(steps = 0)
        }
        if (types.any { it.startsWith("ALARM_") }) next = withAlarm(next)
        if (needsMedia) {
            val controller = mediaController()
            val isPlaying =
                controller?.playbackState?.let { NotificationMediaManager.isPlayingState(it.state) }
                    ?: false
            next = withMetadata(next, controller?.metadata).copy(playing = isPlaying)
        }
        val now = System.currentTimeMillis()
        if (now - slowUpdate >= 60_000 || now < slowUpdate) {
            slowUpdate = now
            if ("DATA_USAGE" in types)
                next =
                    next.copy(
                        usage =
                            runCatching {
                                    val start =
                                        Calendar.getInstance()
                                            .apply {
                                                set(Calendar.DAY_OF_MONTH, 1)
                                                set(Calendar.HOUR_OF_DAY, 0)
                                                set(Calendar.MINUTE, 0)
                                                set(Calendar.SECOND, 0)
                                                set(Calendar.MILLISECOND, 0)
                                            }
                                            .timeInMillis
                                    context
                                        .getSystemService(NetworkStatsManager::class.java)
                                        .querySummaryForDevice(
                                            ConnectivityManager.TYPE_MOBILE,
                                            null,
                                            start,
                                            now,
                                        )
                                        .let { it.rxBytes + it.txBytes }
                                }
                                .getOrNull()
                    )
            if (types.any { it.startsWith("EVENT_") }) {
                val event =
                    if (context.getSystemService(UserManager::class.java).isUserUnlocked)
                        nextEvent(now)
                    else null
                next = next.copy(eventTitle = event?.first, eventTime = event?.second)
            }
        }
        return next
    }

    private fun nextEvent(now: Long): Pair<String, String>? =
        runCatching {
                val uri =
                    CalendarContract.Instances.CONTENT_URI.buildUpon()
                        .apply {
                            ContentUris.appendId(this, now)
                            ContentUris.appendId(this, now + 7 * 86_400_000)
                        }
                        .build()
                context.contentResolver
                    .query(
                        uri,
                        arrayOf(
                            CalendarContract.Instances.TITLE,
                            CalendarContract.Instances.BEGIN,
                            CalendarContract.Instances.ALL_DAY,
                        ),
                        "${CalendarContract.Instances.VISIBLE}=1 AND ${CalendarContract.Instances.END}>? AND " +
                            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS}!=?",
                        arrayOf(
                            now.toString(),
                            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED.toString(),
                        ),
                        "${CalendarContract.Instances.BEGIN} ASC",
                    )
                    ?.use { cursor ->
                        if (!cursor.moveToFirst()) null
                        else {
                            val start = cursor.getLong(1)
                            val date =
                                DateFormat.getMediumDateFormat(context)
                                    .apply {
                                        if (cursor.getInt(2) == 1)
                                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                                    }
                                    .format(start)
                            val time =
                                if (cursor.getInt(2) == 1) date
                                else "$date ${DateFormat.getTimeFormat(context).format(start)}"
                            cursor.getString(0) to time
                        }
                    }
            }
            .getOrNull()

    private fun updateMediaController(controllers: List<MediaController>? = null) {
        if (!running || !awake) return
        val controller = mediaController(controllers)
        val isPlaying =
            controller?.playbackState?.let { NotificationMediaManager.isPlayingState(it.state) }
                ?: false
        current = withMetadata(current, controller?.metadata).copy(playing = isPlaying)
        publish()
    }

    private fun mediaController(providedSessions: List<MediaController>? = null): MediaController? {
        val selected =
            runCatching {
                    val sessions =
                        providedSessions
                            ?: mediaSessions
                                ?.getActiveSessionsForUser(null, UserHandle.of(userId))
                                .orEmpty()

                    sessions.firstOrNull {
                        val s = it.playbackState?.state ?: 0
                        NotificationMediaManager.isPlayingState(s)
                    }
                        ?: sessions.firstOrNull {
                            it.playbackState?.state == PlaybackState.STATE_PAUSED
                        }
                        ?: sessions.firstOrNull()
                }
                .getOrNull()

        if (selected?.sessionToken != observedMedia?.sessionToken) {
            observedMedia?.unregisterCallback(mediaCallback)
            observedMedia = selected
            clearArtwork()
            selected?.registerCallback(mediaCallback, worker)
        }
        return observedMedia
    }

    fun mediaAction(action: String) {
        if (!running || !awake) return
        worker.post {
            runCatching {
                if (!running || !awake || ActivityManager.getCurrentUser() != userId) {
                    return@runCatching
                }
                val controller = mediaController()
                val state = controller?.playbackState
                val isPlaying =
                    state?.let { NotificationMediaManager.isPlayingState(it.state) } ?: false
                val keyCode =
                    when (action) {
                        "MEDIA_PREVIOUS" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        "MEDIA_NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
                        "MEDIA" ->
                            when {
                                state == null -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                                isPlaying -> KeyEvent.KEYCODE_MEDIA_PAUSE
                                else -> KeyEvent.KEYCODE_MEDIA_PLAY
                            }
                        else -> return@runCatching
                    }
                val supportedAction =
                    when (keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        KeyEvent.KEYCODE_MEDIA_NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
                        KeyEvent.KEYCODE_MEDIA_PAUSE ->
                            PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
                        else -> PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE
                    }
                val handled =
                    controller != null &&
                        ((state?.actions ?: 0L) and supportedAction) != 0L &&
                        runCatching {
                                when (keyCode) {
                                    KeyEvent.KEYCODE_MEDIA_PREVIOUS ->
                                        controller.transportControls.skipToPrevious()
                                    KeyEvent.KEYCODE_MEDIA_NEXT ->
                                        controller.transportControls.skipToNext()
                                    KeyEvent.KEYCODE_MEDIA_PAUSE ->
                                        controller.transportControls.pause()
                                    else -> controller.transportControls.play()
                                }
                            }
                            .isSuccess
                if (!handled) {
                    dispatchMediaKey(controller, keyCode)
                }
            }
            refresh()
        }
    }

    private fun dispatchMediaKey(controller: MediaController?, keyCode: Int) {
        runCatching {
            if (!running || !awake || ActivityManager.getCurrentUser() != userId) return
            val sessions = mediaSessions ?: return
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
            if (
                controller != null &&
                    sessions.dispatchMediaKeyEventToSessionAsSystemService(
                        down,
                        controller.sessionToken,
                    )
            ) {
                sessions.dispatchMediaKeyEventToSessionAsSystemService(up, controller.sessionToken)
            } else {
                sessions.dispatchMediaKeyEventAsSystemService(down)
                sessions.dispatchMediaKeyEventAsSystemService(up)
            }
        }
    }
}
