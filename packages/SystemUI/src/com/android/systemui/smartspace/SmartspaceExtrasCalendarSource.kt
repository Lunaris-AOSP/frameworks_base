/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.smartspace

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.CancellationSignal
import android.os.Handler
import android.provider.CalendarContract
import android.text.format.DateUtils
import android.util.Log
import com.android.systemui.res.R
import java.util.concurrent.Executor

internal class SmartspaceExtrasCalendarSource(
    private val context: Context,
    private val executor: Executor,
    private val handler: Handler,
    private val onChanged: () -> Unit,
) {
    private data class Event(val id: Long, val title: String, val start: Long, val end: Long)

    private var active = false
    private var generation = 0
    private var cancellation: CancellationSignal? = null
    private var events = emptyList<Event>()
    private val query = Runnable { queryEvents() }
    private val observer =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = refresh()
        }

    fun start() {
        if (
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        active = true
        context.contentResolver.registerContentObserver(
            CalendarContract.CONTENT_URI,
            true,
            observer,
        )
        refresh()
    }

    fun stop() {
        if (!active) return
        active = false
        generation++
        cancellation?.cancel()
        cancellation = null
        events = emptyList()
        handler.removeCallbacks(query)
        context.contentResolver.unregisterContentObserver(observer)
    }

    fun refresh() {
        if (!active) return
        generation++
        cancellation?.cancel()
        handler.removeCallbacks(query)
        handler.postDelayed(query, 150)
    }

    fun cards(): List<SmartspaceExtrasCard> {
        val now = System.currentTimeMillis()
        return events
            .filter { it.end > now && it.start <= now + DateUtils.HOUR_IN_MILLIS }
            .map {
                SmartspaceExtrasCard(
                    "calendar_${it.id}_${it.start}",
                    it.title,
                    if (it.start <= now) context.getString(R.string.smartspace_extras_event_now)
                    else
                        DateUtils.getRelativeTimeSpanString(
                            it.start,
                            now,
                            DateUtils.MINUTE_IN_MILLIS,
                        ),
                    null,
                    com.android.internal.R.drawable.ic_zen_mode_type_schedule_calendar,
                    null,
                    Intent(
                            Intent.ACTION_VIEW,
                            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, it.id),
                        )
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it.start)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it.end),
                )
            }
    }

    private fun queryEvents() {
        val revision = generation
        val signal = CancellationSignal()
        cancellation = signal
        executor.execute {
            val result = mutableListOf<Event>()
            val now = System.currentTimeMillis()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(uri, now)
            ContentUris.appendId(uri, now + DateUtils.HOUR_IN_MILLIS)
            try {
                context.contentResolver
                    .query(
                        uri.build(),
                        arrayOf(
                            CalendarContract.Instances.EVENT_ID,
                            CalendarContract.Instances.TITLE,
                            CalendarContract.Instances.BEGIN,
                            CalendarContract.Instances.END,
                        ),
                        "${CalendarContract.Instances.VISIBLE}=1 AND ${CalendarContract.Instances.ALL_DAY}=0 AND " +
                            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS}!=${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED} AND " +
                            "${CalendarContract.Instances.STATUS}!=${CalendarContract.Events.STATUS_CANCELED}",
                        null,
                        "${CalendarContract.Instances.BEGIN} ASC",
                        signal,
                    )
                    ?.use { cursor ->
                        while (cursor.moveToNext() && result.size < 3) {
                            if (cursor.getLong(3) <= now) continue
                            result.add(
                                Event(
                                    cursor.getLong(0),
                                    cursor.getString(1)?.takeIf { it.isNotBlank() }
                                        ?: context.getString(
                                            R.string.smartspace_extras_event_untitled
                                        ),
                                    cursor.getLong(2),
                                    cursor.getLong(3),
                                )
                            )
                        }
                    }
            } catch (e: RuntimeException) {
                if (!signal.isCanceled) Log.w(TAG, "Unable to read calendar extras", e)
            }
            handler.post {
                if (active && generation == revision && !signal.isCanceled) {
                    events = result
                    cancellation = null
                    onChanged()
                }
            }
        }
    }

    private companion object {
        const val TAG = "SmartspaceExtrasCalendar"
    }
}
