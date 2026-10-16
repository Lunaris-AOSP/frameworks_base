/*
 * Copyright (C) 2026 Lunaris-AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lunaris.lockscreenwidgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetHostView
import android.util.SizeF
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

object WidgetContract {
    const val HOST_ID = 1027
    const val CONFIG = "lockscreen_app_widgets"
    const val ENABLED = "lockscreen_app_widgets_enabled"
    const val AUTHORITY = "com.android.systemui.lockscreenwidgets"
    const val PERMISSION = "android.permission.CUSTOMIZE_SYSTEM_UI"
    const val ACTIVITY = "com.android.systemui.lockscreen.KeyguardWidgetConfigActivity"
    val URI: Uri = Uri.parse("content://$AUTHORITY")
}

data class WidgetEntry(val id: Int, val x: Int, val y: Int, val width: Int, val height: Int) {
    fun overlaps(other: WidgetEntry) = x < other.x + other.width && x + width > other.x &&
        y < other.y + other.height && y + height > other.y
    fun fits(entries: List<WidgetEntry>) = id > 0 && width in 1..4 && height in 1..4 &&
        x >= 0 && y >= 0 && x + width <= 4 && y + height <= 4 &&
        entries.none { it.id != id && overlaps(it) }
    fun bundle() = Bundle().apply {
        putInt("id", id)
        putInt("x", x)
        putInt("y", y)
        putInt("width", width)
        putInt("height", height)
    }

    companion object {
        fun from(bundle: Bundle) = WidgetEntry(bundle.getInt("id"), bundle.getInt("x"),
            bundle.getInt("y"), bundle.getInt("width"), bundle.getInt("height"))
        fun decode(value: String?): List<WidgetEntry> {
            if (value.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(value)
                buildList<WidgetEntry> {
                    for (i in 0 until array.length().coerceAtMost(16)) {
                        val obj = array.optJSONObject(i) ?: continue
                        val entry = WidgetEntry(obj.optInt("id"), obj.optInt("x"), obj.optInt("y"),
                            obj.optInt("width"), obj.optInt("height"))
                        if (none { it.id == entry.id } && entry.fits(this)) add(entry)
                    }
                }
            }.getOrDefault(emptyList())
        }
        fun encode(entries: List<WidgetEntry>) = JSONArray().apply {
            entries.forEach { e -> put(JSONObject().apply {
                put("id", e.id); put("x", e.x); put("y", e.y)
                put("width", e.width); put("height", e.height)
            }) }
        }.toString()
        fun place(id: Int, width: Int, height: Int, entries: List<WidgetEntry>): WidgetEntry? {
            for (y in 0..4 - height) for (x in 0..4 - width) {
                val entry = WidgetEntry(id, x, y, width, height)
                if (entry.fits(entries)) return entry
            }
            return null
        }
    }
}

class WidgetSizes(context: Context, private val availableWidth: Int) {
    private val density = context.resources.displayMetrics.density
    private val gap = 8 * density
    private val cell = ((availableWidth - gap * 3) / 4).coerceAtLeast(1f)
    private val context = context
    fun pixels(span: Int) = (cell * span + gap * (span - 1)).toInt()
    fun options(info: AppWidgetProviderInfo, spanX: Int, spanY: Int): Bundle {
        val padding = AppWidgetHostView.getDefaultPaddingForWidget(context, info.provider, null)
        val width = ((pixels(spanX) - padding.left - padding.right) / density).coerceAtLeast(1f)
        val height = ((pixels(spanY) - padding.top - padding.bottom) / density).coerceAtLeast(1f)
        return Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width.toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width.toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height.toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height.toInt())
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(width, height)))
        }
    }
    fun offset(cellIndex: Int) = ((cell + gap) * cellIndex).toInt()
    fun allowed(info: AppWidgetProviderInfo): List<Pair<Int, Int>> {
        val padding = AppWidgetHostView.getDefaultPaddingForWidget(context, info.provider, null)
        fun span(pixels: Int, pad: Int) = ceil((pixels + pad + gap) / (cell + gap)).toInt().coerceAtLeast(1)
        val defaultX = if (info.targetCellWidth > 0) info.targetCellWidth else
            span(info.minWidth, padding.left + padding.right)
        val defaultY = if (info.targetCellHeight > 0) info.targetCellHeight else
            span(info.minHeight, padding.top + padding.bottom)
        val resizeX = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val resizeY = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        val minX = if (resizeX) minOf(defaultX,
            span(minOf(info.minWidth, info.minResizeWidth), padding.left + padding.right)) else defaultX
        val minY = if (resizeY) minOf(defaultY,
            span(minOf(info.minHeight, info.minResizeHeight), padding.top + padding.bottom)) else defaultY
        val maxX = if (resizeX) 4 else defaultX
        val maxY = if (resizeY) 4 else defaultY
        return buildList {
            for (w in minX..maxX.coerceAtMost(4)) for (h in minY..maxY.coerceAtMost(4)) {
                val contentWidth = pixels(w) - padding.left - padding.right
                val contentHeight = pixels(h) - padding.top - padding.bottom
                if ((!resizeX || info.maxResizeWidth <= 0 || info.maxResizeWidth < info.minWidth ||
                        contentWidth <= info.maxResizeWidth) &&
                    (!resizeY || info.maxResizeHeight <= 0 || info.maxResizeHeight < info.minHeight ||
                        contentHeight <= info.maxResizeHeight)) add(w to h)
            }
        }
    }
}

fun AppWidgetProviderInfo.viewKey(): String =
    "${provider.flattenToString()}:$initialLayout:${providerInfo?.applicationInfo?.longVersionCode}"
