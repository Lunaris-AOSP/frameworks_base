/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.util.SizeF
import kotlin.math.ceil

internal data class QsWidgetSize(
    val width: Int,
    val height: Int,
    val scale: Float,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

internal fun qsWidgetSize(
    width: Int,
    height: Int,
    minWidth: Int,
    minHeight: Int,
    maxDimension: Int,
): QsWidgetSize {
    require(width > 0 && height > 0 && maxDimension > 0)
    val limit = maxOf(width, height, maxDimension)
    val scale =
        maxOf(
            minOf(
                1f,
                width.toFloat() / minWidth.coerceAtLeast(1),
                height.toFloat() / minHeight.coerceAtLeast(1),
            ),
            maxOf(width, height).toFloat() / limit,
        )
    return QsWidgetSize(
        ceil(width / scale).toInt().coerceAtMost(limit),
        ceil(height / scale).toInt().coerceAtMost(limit),
        scale,
    )
}

internal fun qsWidgetSize(
    context: Context,
    info: AppWidgetProviderInfo,
    width: Int,
    height: Int,
    padding: Rect,
): QsWidgetSize {
    val limit = (1600f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    val minWidth =
        if (info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0) {
            info.minResizeWidth.takeIf { it in 1..info.minWidth } ?: info.minWidth
        } else info.minWidth
    val minHeight =
        if (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0) {
            info.minResizeHeight.takeIf { it in 1..info.minHeight } ?: info.minHeight
        } else info.minHeight
    val size =
        qsWidgetSize(
            width,
            height,
            minWidth.coerceIn(0, limit) + padding.left + padding.right,
            minHeight.coerceIn(0, limit) + padding.top + padding.bottom,
            limit,
        )
    val boundedWidth =
        if (
            info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0 &&
                info.maxResizeWidth > 0 &&
                info.maxResizeWidth >= info.minWidth
        ) {
            minOf(
                size.width,
                info.maxResizeWidth.coerceAtMost(limit) + padding.left + padding.right,
            )
        } else size.width
    val boundedHeight =
        if (
            info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0 &&
                info.maxResizeHeight > 0 &&
                info.maxResizeHeight >= info.minHeight
        ) {
            minOf(
                size.height,
                info.maxResizeHeight.coerceAtMost(limit) + padding.top + padding.bottom,
            )
        } else size.height
    return size.copy(
        width = boundedWidth,
        height = boundedHeight,
        offsetX = ((width - boundedWidth * size.scale) / 2).coerceAtLeast(0f),
        offsetY = ((height - boundedHeight * size.scale) / 2).coerceAtLeast(0f),
    )
}

internal fun qsWidgetOptions(width: Float, height: Float): Bundle {
    require(width.isFinite() && height.isFinite())
    val size = SizeF(width.coerceIn(1f, 1600f), height.coerceIn(1f, 1600f))
    return Bundle().apply {
        putInt(
            AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
        )
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size.width.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, size.width.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, size.height.toInt())
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, size.height.toInt())
        putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(size))
    }
}
