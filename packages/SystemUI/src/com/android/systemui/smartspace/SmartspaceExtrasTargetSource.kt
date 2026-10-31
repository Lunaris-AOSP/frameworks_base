/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.smartspace

import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSession
import android.app.smartspace.SmartspaceTarget
import android.app.smartspace.SmartspaceTargetEvent
import android.app.smartspace.uitemplatedata.BaseTemplateData
import android.app.smartspace.uitemplatedata.CarouselTemplateData
import android.app.smartspace.uitemplatedata.TapAction
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Parcelable
import android.os.UserHandle
import android.util.Log
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.res.R
import com.android.systemui.smartspace.SmartspaceExtrasCard.Type
import java.util.concurrent.Executor

internal class SmartspaceExtrasTargetSource(
    private val context: Context,
    private val executor: Executor,
    private val handler: Handler,
    private val onChanged: () -> Unit,
) {
    private var session: SmartspaceSession? = null
    private var targets = emptyList<SmartspaceTarget>()
    private val actionIds = mutableMapOf<String, String>()
    private val refresh = Runnable { onChanged() }
    private val listener =
        SmartspaceSession.OnTargetsAvailableListener {
            if (session != null) {
                targets = it.toList()
                actionIds.clear()
                onChanged()
            }
        }

    fun start() {
        if (session != null) return
        try {
            session =
                context
                    .getSystemService(SmartspaceManager::class.java)
                    ?.createSmartspaceSession(
                        SmartspaceConfig.Builder(
                                context,
                                BcSmartspaceDataPlugin.UI_SURFACE_LOCK_SCREEN_AOD,
                            )
                            .setSmartspaceTargetCount(20)
                            .build()
                    )
            session?.addOnTargetsAvailableListener(executor, listener)
            session?.notifySmartspaceEvent(
                SmartspaceTargetEvent.Builder(SmartspaceTargetEvent.EVENT_UI_SURFACE_SHOWN).build()
            )
            session?.requestSmartspaceUpdate()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to start Smartspace extras session", e)
            stop()
        }
    }

    fun stop() {
        handler.removeCallbacks(refresh)
        val oldSession = session
        session = null
        targets = emptyList()
        actionIds.clear()
        try {
            oldSession?.removeOnTargetsAvailableListener(listener)
            oldSession?.notifySmartspaceEvent(
                SmartspaceTargetEvent.Builder(SmartspaceTargetEvent.EVENT_UI_SURFACE_HIDDEN).build()
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to detach Smartspace extras session", e)
        } finally {
            try {
                oldSession?.close()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unable to close Smartspace extras session", e)
            }
        }
    }

    fun cards(
        user: UserHandle,
        allowed: (SmartspaceTarget) -> Boolean,
        allowedFeature: (Int) -> Boolean,
    ): List<SmartspaceExtrasCard> {
        val now = System.currentTimeMillis()
        val relevantTargets =
            targets.filter {
                it.userHandle == user &&
                    it.featureType != SmartspaceTarget.FEATURE_STEP_DATE &&
                    allowed(it)
            }
        handler.removeCallbacks(refresh)
        relevantTargets
            .flatMap { listOf(it.creationTimeMillis, it.expiryTimeMillis) }
            .filter { it > now }
            .minOrNull()
            ?.let { handler.postDelayed(refresh, it - now) }
        return relevantTargets
            .filter {
                it.creationTimeMillis <= now &&
                    (it.expiryTimeMillis <= 0 || it.expiryTimeMillis > now)
            }
            .mapNotNull { target ->
                try {
                    toCard(target, user, allowedFeature)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Unable to read Smartspace extra", e)
                    null
                }
            }
            .distinctBy { it.id }
    }

    fun notifyEvent(id: String?, type: Int) {
        if (id == null) return
        val target = targets.find { "target_${it.smartspaceTargetId}" == id } ?: return
        try {
            val event = SmartspaceTargetEvent.Builder(type).setSmartspaceTarget(target)
            actionIds[id]?.let { event.setSmartspaceActionId(it) }
            session?.notifySmartspaceEvent(event.build())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to report Smartspace extras event", e)
        }
    }

    private fun toCard(
        target: SmartspaceTarget,
        user: UserHandle,
        allowedFeature: (Int) -> Boolean,
    ): SmartspaceExtrasCard? {
        val header = target.headerAction
        val base = target.baseAction
        val template = target.templateData
        val forecast =
            if (target.featureType == SmartspaceTarget.FEATURE_WEATHER) {
                weatherForecast(target) ?: return null
            } else null
        val type =
            when (target.featureType) {
                SmartspaceTarget.FEATURE_WEATHER,
                SmartspaceTarget.FEATURE_WEATHER_ALERT,
                SmartspaceTarget.FEATURE_SEVERE_WEATHER_ALERT -> Type.WEATHER
                SmartspaceTarget.FEATURE_COMMUTE_TIME,
                SmartspaceTarget.FEATURE_ETA_MONITORING,
                SmartspaceTarget.FEATURE_DRIVING_MODE,
                SmartspaceTarget.FEATURE_TIME_TO_LEAVE -> Type.NAVIGATION
                SmartspaceTarget.FEATURE_PAIRED_DEVICE_STATE -> Type.BLUETOOTH
                SmartspaceTarget.FEATURE_MEDIA,
                SmartspaceTarget.FEATURE_MEDIA_RESUME,
                SmartspaceTarget.FEATURE_MEDIA_HEADS_UP -> Type.MEDIA_SUGGESTION
                else -> Type.OTHER
            }
        fun allowedItem(item: BaseTemplateData.SubItemInfo?): BaseTemplateData.SubItemInfo? =
            item?.takeIf {
                target.featureType == SmartspaceTarget.FEATURE_PAIRED_DEVICE_STATE ||
                    it.loggingInfo?.featureType?.let(allowedFeature) != false
            }
        val primary = allowedItem(template?.primaryItem)
        val secondary = allowedItem(template?.subtitleItem)
        val supplemental =
            listOfNotNull(
                allowedItem(template?.subtitleSupplementalItem),
                allowedItem(template?.supplementalLineItem),
                allowedItem(template?.supplementalAlarmItem),
            )
        val items = listOfNotNull(primary, secondary) + supplemental
        val title =
            forecast?.let { context.getString(R.string.smartspace_extras_weather_forecast) }
                ?: primary?.text?.text?.takeIf { it.isNotBlank() }
                ?: header?.title?.takeIf { it.isNotBlank() }
                ?: base?.title?.takeIf { it.isNotBlank() }
                ?: secondary?.text?.text?.takeIf { it.isNotBlank() }
                ?: header?.subtitle?.takeIf { it.isNotBlank() }
                ?: base?.subtitle?.takeIf { it.isNotBlank() }
                ?: return null
        val subtitle =
            secondary?.text?.text?.takeIf { it.isNotBlank() }
                ?: header?.subtitle?.takeIf { it.isNotBlank() }
                ?: base?.subtitle?.takeIf { it.isNotBlank() }
        val details = supplemental.mapNotNull { it.text?.text }
        val condition =
            if (type == Type.WEATHER) {
                base?.extras?.getString("description")
                    ?: header?.extras?.getString("description")
                    ?: primary?.icon?.contentDescription
                    ?: header?.contentDescription
            } else null
        val lines =
            (forecast?.let { listOf(it.text) } ?: (listOfNotNull(subtitle, condition) + details))
                .filter { it.isNotBlank() && it.toString() != title.toString() }
                .map { it.toString() }
                .distinct()
        val action =
            listOfNotNull(header, base).firstOrNull {
                it.pendingIntent != null || it.intent != null
            }
        val tap =
            (listOfNotNull(forecast?.tap) + items.mapNotNull { it.tapAction }).firstOrNull {
                (it.userHandle == null || it.userHandle == user) &&
                    (it.pendingIntent != null || it.intent != null)
            }
        val icon =
            if (forecast != null) forecast.icon
            else items.firstNotNullOfOrNull { it.icon?.icon } ?: header?.icon ?: base?.icon
        val id = "target_${target.smartspaceTargetId}"
        val actionId = if (tap != null) tap.id?.toString() else action?.id
        if (actionId != null) actionIds[id] = actionId else actionIds.remove(id)
        return SmartspaceExtrasCard(
            id,
            title,
            lines.joinToString(" · "),
            icon,
            when (type) {
                Type.WEATHER -> R.drawable.ic_weather
                Type.NAVIGATION -> R.drawable.ic_location
                Type.BLUETOOTH -> R.drawable.ic_bluetooth_connected
                Type.MEDIA_SUGGESTION -> R.drawable.ic_music_note
                else -> R.drawable.ic_info_outline
            },
            if (tap != null) tap.pendingIntent else action?.pendingIntent,
            if (tap != null) tap.intent else action?.intent,
            type,
        )
    }

    private fun weatherForecast(target: SmartspaceTarget): WeatherForecast? {
        val carousel = target.templateData as? CarouselTemplateData
        if (carousel != null) {
            val items =
                carousel.carouselItems.filter {
                    !it.upperText?.text.isNullOrBlank() && !it.lowerText?.text.isNullOrBlank()
                }
            if (items.isNotEmpty()) {
                val lines =
                    items.take(4).map { item ->
                        listOfNotNull(
                                item.lowerText?.text,
                                item.image?.contentDescription,
                                item.upperText?.text,
                            )
                            .filter { it.isNotBlank() }
                            .map { it.toString() }
                            .distinct()
                            .joinToString(" ")
                    }
                return WeatherForecast(
                    lines.joinToString(" · "),
                    items.first().image?.icon,
                    carousel.carouselAction ?: items.first().tapAction,
                )
            }
        }
        for (action in listOfNotNull(target.baseAction, target.headerAction)) {
            val extras = action.extras ?: continue
            val temperatures = extras.getStringArray("temperatureValues") ?: continue
            val timestamps = extras.getStringArray("timestamps") ?: continue
            val lines =
                timestamps.zip(temperatures).mapNotNull { (time, temperature) ->
                    if (time.isNullOrBlank() || temperature.isNullOrBlank()) null
                    else "$time $temperature"
                }
            if (lines.isEmpty()) continue
            val description = extras.getString("description")?.takeIf { it.isNotBlank() }
            val bitmap =
                extras.getParcelableArray("weatherIcons", Parcelable::class.java)?.firstOrNull()
                    as? Bitmap
            return WeatherForecast(
                (listOfNotNull(description) + lines.take(4)).joinToString(" · "),
                bitmap?.takeUnless { it.isRecycled }?.let(Icon::createWithBitmap),
                null,
            )
        }
        return null
    }

    private data class WeatherForecast(val text: String, val icon: Icon?, val tap: TapAction?)

    private companion object {
        const val TAG = "SmartspaceExtrasTargets"
    }
}
