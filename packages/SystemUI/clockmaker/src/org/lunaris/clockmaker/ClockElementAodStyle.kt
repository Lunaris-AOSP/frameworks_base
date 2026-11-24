/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.graphics.Color
import org.json.JSONObject

data class ClockElementAodStyle(
    val x: Float = 0.5f,
    val y: Float = 0.2f,
    val size: Int = 84,
    val stretchX: Int = 100,
    val stretchY: Int = 100,
    val stretchFromTop: Boolean = true,
    val letterSpacing: Int = 0,
    val rotation: Int = 0,
    val opacity: Int = 100,
    val visible: Boolean = true,
    val outline: Boolean = false,
    val outlineWidth: Int = 2,
    val animateOutline: Boolean = true,
    val textStroke: Boolean = false,
    val textStrokeWidth: Int = 2,
    val textStrokeColor: Int = Color.BLACK,
    val imageWidth: Int = 120,
    val imageHeight: Int = 120,
    val imageAspectRatio: Float = imageWidth.toFloat() / imageHeight,
    val imageAspectLocked: Boolean = true,
    val imageFit: ClockImageFit = ClockImageFit.CROP,
    val imageShape: String = "none",
    val imageBorderWidth: Int = 0,
    val imageBorderColor: Int = Color.WHITE,
) {
    fun applyTo(element: ClockElement): ClockElement = element.copy(
        x = x, y = y, size = size, letterSpacing = letterSpacing, rotation = rotation,
        stretchX = stretchX, stretchY = stretchY, stretchFromTop = stretchFromTop,
        opacity = opacity, visible = visible, color = Color.WHITE,
        gradient = false, shadow = false, glass = false,
        lockscreenOutline = outline, lockscreenOutlineWidth = outlineWidth,
        textStroke = textStroke, textStrokeWidth = textStrokeWidth, textStrokeColor = textStrokeColor,
        imageWidth = imageWidth, imageHeight = imageHeight, imageAspectRatio = imageAspectRatio,
        imageAspectLocked = imageAspectLocked, imageFit = imageFit, imageShape = imageShape,
        imageBorderWidth = imageBorderWidth, imageBorderColor = imageBorderColor,
    )

    fun toJson() = JSONObject()
        .put("x", x.toDouble()).put("y", y.toDouble()).put("size", size)
        .put("stretchX", stretchX).put("stretchY", stretchY).put("stretchFromTop", stretchFromTop)
        .put("letterSpacing", letterSpacing).put("rotation", rotation)
        .put("opacity", opacity).put("visible", visible)
        .put("outline", outline).put("outlineWidth", outlineWidth).put("animateOutline", animateOutline)
        .put("textStroke", textStroke).put("textStrokeWidth", textStrokeWidth).put("textStrokeColor", textStrokeColor)
        .put("imageWidth", imageWidth).put("imageHeight", imageHeight)
        .put("imageAspectRatio", imageAspectRatio.toDouble()).put("imageAspectLocked", imageAspectLocked)
        .put("imageFit", imageFit.name).put("imageShape", imageShape)
        .put("imageBorderWidth", imageBorderWidth).put("imageBorderColor", imageBorderColor)

    companion object {
        fun fromElement(element: ClockElement) = ClockElementAodStyle(
            x = element.x, y = element.y, size = element.size,
            stretchX = element.stretchX, stretchY = element.stretchY, stretchFromTop = element.stretchFromTop,
            letterSpacing = element.letterSpacing, rotation = element.rotation,
            opacity = element.opacity, visible = element.visible,
            outline = element.lockscreenOutline, outlineWidth = element.lockscreenOutlineWidth,
            textStroke = element.textStroke, textStrokeWidth = element.textStrokeWidth,
            textStrokeColor = element.textStrokeColor,
            imageWidth = element.imageWidth, imageHeight = element.imageHeight,
            imageAspectRatio = element.imageAspectRatio, imageAspectLocked = element.imageAspectLocked,
            imageFit = element.imageFit, imageShape = element.imageShape,
            imageBorderWidth = element.imageBorderWidth, imageBorderColor = element.imageBorderColor,
        )

        fun fromJson(json: JSONObject?, fallback: ClockElementAodStyle): ClockElementAodStyle {
            val j = json ?: return fallback
            fun number(key: String, default: Float, min: Float, max: Float): Float =
                j.optDouble(key, default.toDouble()).toFloat().let {
                    if (it.isFinite()) it.coerceIn(min, max) else default
                }
            val width = j.optInt("imageWidth", fallback.imageWidth).coerceIn(16, 360)
            val height = j.optInt("imageHeight", fallback.imageHeight).coerceIn(16, 720)
            return fallback.copy(
                x = number("x", fallback.x, ClockElement.MIN_POSITION, ClockElement.MAX_POSITION),
                y = number("y", fallback.y, ClockElement.MIN_POSITION, ClockElement.MAX_POSITION),
                size = j.optInt("size", fallback.size).coerceIn(ClockElement.MIN_TEXT_SIZE, ClockElement.MAX_TEXT_SIZE),
                stretchX = j.optInt("stretchX", fallback.stretchX)
                    .coerceIn(ClockElement.MIN_TEXT_STRETCH, ClockElement.MAX_TEXT_STRETCH),
                stretchY = j.optInt("stretchY", fallback.stretchY)
                    .coerceIn(ClockElement.MIN_TEXT_STRETCH, ClockElement.MAX_TEXT_STRETCH),
                stretchFromTop = j.optBoolean("stretchFromTop", fallback.stretchFromTop),
                letterSpacing = j.optInt("letterSpacing", fallback.letterSpacing).coerceIn(-10, 50),
                rotation = j.optInt("rotation", fallback.rotation).coerceIn(-180, 180),
                opacity = j.optInt("opacity", fallback.opacity).coerceIn(0, 100),
                visible = j.optBoolean("visible", fallback.visible),
                outline = j.optBoolean("outline", fallback.outline),
                outlineWidth = j.optInt("outlineWidth", fallback.outlineWidth).coerceIn(1, 6),
                animateOutline = j.optBoolean("animateOutline", fallback.animateOutline),
                textStroke = j.optBoolean("textStroke", fallback.textStroke),
                textStrokeWidth = j.optInt("textStrokeWidth", fallback.textStrokeWidth).coerceIn(1, 12),
                textStrokeColor = j.optInt("textStrokeColor", fallback.textStrokeColor),
                imageWidth = width, imageHeight = height,
                imageAspectRatio = number("imageAspectRatio",
                    if (j.has("imageWidth") || j.has("imageHeight")) width.toFloat() / height
                    else fallback.imageAspectRatio, 16f / 720, 360f / 16),
                imageAspectLocked = j.optBoolean("imageAspectLocked", fallback.imageAspectLocked),
                imageFit = runCatching { ClockImageFit.valueOf(j.optString("imageFit")) }
                    .getOrDefault(fallback.imageFit),
                imageShape = j.optString("imageShape", fallback.imageShape).take(40),
                imageBorderWidth = j.optInt("imageBorderWidth", fallback.imageBorderWidth).coerceIn(0, 24),
                imageBorderColor = j.optInt("imageBorderColor", fallback.imageBorderColor),
            )
        }
    }
}
