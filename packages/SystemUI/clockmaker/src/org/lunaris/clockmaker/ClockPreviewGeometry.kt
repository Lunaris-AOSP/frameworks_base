/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lunaris.clockmaker

import android.net.Uri
import android.os.Bundle

data class ClockPreviewGeometry(
    val displayId: Int,
    val userId: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val clockLeft: Int,
    val clockTop: Int,
    val clockWidth: Int,
    val statusBarTop: Float,
    val density: Float,
    val infoTopOffset: Int = 12 + (12f * density).toInt(),
    val appWidgetBottom: Int = screenHeight - 2 * (96f * density).toInt(),
    val appWidgetMaxHeight: Int = (screenHeight * 0.45f).toInt(),
    val contentLeft: Int = 0,
    val contentTop: Int = 0,
    val contentWidth: Int = screenWidth,
    val contentHeight: Int = screenHeight,
    val appWidgetCenterX: Float = contentLeft + contentWidth / 2f,
    val appWidgetContentOffset: Int = 0,
    val appWidgetTrailingOffset: Int = 0,
    val wallpaperScale: Float = 1f,
    val wallpaperPivotX: Float = screenWidth / 2f,
    val wallpaperPivotY: Float = screenHeight / 2f,
    val wallpaperScrimColor: Int = 0,
    val wallpaperDimAmount: Float = 0f,
) {
    val aspect: Float get() = screenWidth.toFloat() / screenHeight

    fun top(marginTop: Int): Int = clockTop + topInset(statusBarTop, marginTop, density)

    fun left(marginStart: Int): Int = clockLeft + (marginStart * density).toInt()

    fun toBundle() = Bundle().apply {
        putInt("display_id", displayId)
        putInt("user_id", userId)
        putInt("screen_width", screenWidth)
        putInt("screen_height", screenHeight)
        putInt("clock_left", clockLeft)
        putInt("clock_top", clockTop)
        putInt("clock_width", clockWidth)
        putFloat("status_bar_top", statusBarTop)
        putFloat("density", density)
        putInt("info_top_offset", infoTopOffset)
        putInt("app_widget_bottom", appWidgetBottom)
        putInt("app_widget_max_height", appWidgetMaxHeight)
        putInt("content_left", contentLeft)
        putInt("content_top", contentTop)
        putInt("content_width", contentWidth)
        putInt("content_height", contentHeight)
        putFloat("app_widget_center_x", appWidgetCenterX)
        putInt("app_widget_content_offset", appWidgetContentOffset)
        putInt("app_widget_trailing_offset", appWidgetTrailingOffset)
        putFloat("wallpaper_scale", wallpaperScale)
        putFloat("wallpaper_pivot_x", wallpaperPivotX)
        putFloat("wallpaper_pivot_y", wallpaperPivotY)
        putInt("wallpaper_scrim_color", wallpaperScrimColor)
        putFloat("wallpaper_dim_amount", wallpaperDimAmount)
    }

    companion object {
        val URI: Uri = Uri.parse("content://com.android.systemui.clockpreview")
        const val METHOD = "geometry"

        fun topInset(statusBarTop: Float, marginTop: Int, density: Float): Int =
            (statusBarTop + marginTop * density).toInt() - statusBarTop.toInt()

        fun fromBundle(bundle: Bundle): ClockPreviewGeometry? {
            val width = bundle.getInt("screen_width")
            val height = bundle.getInt("screen_height")
            val clockWidth = bundle.getInt("clock_width")
            val density = bundle.getFloat("density")
            val top = bundle.getFloat("status_bar_top")
            val contentWidth = bundle.getInt("content_width", width)
            val contentHeight = bundle.getInt("content_height", height)
            val contentLeft = bundle.getInt("content_left")
            val center = bundle.getFloat("app_widget_center_x", contentLeft + contentWidth / 2f)
            val wallpaperScale = bundle.getFloat("wallpaper_scale", 1f)
            val wallpaperPivotX = bundle.getFloat("wallpaper_pivot_x", width / 2f)
            val wallpaperPivotY = bundle.getFloat("wallpaper_pivot_y", height / 2f)
            val wallpaperDimAmount = bundle.getFloat("wallpaper_dim_amount", 0f)
            if (width <= 0 || height <= 0 || clockWidth !in 1..width ||
                contentWidth !in 1..width || contentHeight !in 1..height ||
                !density.isFinite() || density <= 0f || !top.isFinite() || !center.isFinite() ||
                !wallpaperScale.isFinite() || wallpaperScale <= 0f ||
                !wallpaperPivotX.isFinite() || !wallpaperPivotY.isFinite() ||
                !wallpaperDimAmount.isFinite() || wallpaperDimAmount !in 0f..1f) return null
            return ClockPreviewGeometry(
                bundle.getInt("display_id"), bundle.getInt("user_id"), width, height,
                bundle.getInt("clock_left"), bundle.getInt("clock_top"), clockWidth, top, density,
                bundle.getInt("info_top_offset", 12 + (12f * density).toInt()),
                bundle.getInt("app_widget_bottom", height - 2 * (96f * density).toInt()),
                bundle.getInt("app_widget_max_height", (height * 0.45f).toInt()),
                contentLeft, bundle.getInt("content_top"), contentWidth, contentHeight, center,
                bundle.getInt("app_widget_content_offset"), bundle.getInt("app_widget_trailing_offset"),
                wallpaperScale, wallpaperPivotX, wallpaperPivotY,
                bundle.getInt("wallpaper_scrim_color"), wallpaperDimAmount,
            )
        }
    }
}
