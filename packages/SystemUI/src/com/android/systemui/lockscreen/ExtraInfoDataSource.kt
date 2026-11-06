/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.lockscreen

import android.app.AlarmManager
import android.app.usage.NetworkStatsManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.provider.CalendarContract
import android.text.format.DateFormat
import java.time.LocalDate
import java.util.Calendar
import org.lunaris.extrainfowidgets.InfoData
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
    private val mediaCallback =
        object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                current = withMetadata(current, metadata)
                publish()
                refresh()
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                current = current.copy(playing = state?.state == PlaybackState.STATE_PLAYING)
                publish()
                refresh()
            }

            override fun onSessionDestroyed() {
                current = withMetadata(current, null).copy(playing = false)
                publish()
                refresh()
            }
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
                worker.post {
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
            }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
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
        refresh()
    }

    fun setAwake(value: Boolean) {
        awake = value
        if (running) {
            worker.post {
                worker.removeCallbacks(update)
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
        worker.removeCallbacksAndMessages(null)
        worker.post {
            runCatching { sensorManager?.unregisterListener(stepsListener) }
            runCatching { observedMedia?.unregisterCallback(mediaCallback) }
            observedMedia = null
            thread.quitSafely()
        }
    }

    private fun publish() {
        val snapshot = current
        main.post { if (running) onData(snapshot) }
    }

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

    private fun withMetadata(base: InfoData, metadata: MediaMetadata?): InfoData =
        base.copy(
            mediaTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            mediaArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            art =
                metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
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
            next =
                withMetadata(next, controller?.metadata)
                    .copy(playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING)
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

    private fun mediaController(): MediaController? {
        val selected =
            runCatching {
                    val sessions =
                        context
                            .getSystemService(MediaSessionManager::class.java)
                            .getActiveSessionsForUser(null, UserHandle.of(userId))
                    sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                        ?: sessions.firstOrNull()
                }
                .getOrNull()
        if (selected?.sessionToken != observedMedia?.sessionToken) {
            observedMedia?.unregisterCallback(mediaCallback)
            observedMedia = selected
            selected?.registerCallback(mediaCallback, worker)
        }
        return observedMedia
    }

    fun mediaAction(action: String) {
        if (!running) return
        worker.post {
            runCatching {
                val controller = mediaController() ?: return@runCatching
                when (action) {
                    "MEDIA_PREVIOUS" -> controller.transportControls.skipToPrevious()
                    "MEDIA_NEXT" -> controller.transportControls.skipToNext()
                    "MEDIA" ->
                        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING)
                            controller.transportControls.pause()
                        else controller.transportControls.play()
                }
            }
            refresh()
        }
    }
}
