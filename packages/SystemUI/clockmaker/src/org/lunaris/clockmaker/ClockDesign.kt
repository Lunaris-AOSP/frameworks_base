/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

enum class ClockElementKind { TIME, DATE, WEATHER, TEXT, IMAGE, QUICKLOOK }

enum class ClockImageFit { CROP, FIT }

data class ClockElement(
    val id: String = UUID.randomUUID().toString(),
    val kind: ClockElementKind = ClockElementKind.TIME,
    val text: String = "",
    val format: String = "",
    val x: Float = 0.5f,
    val y: Float = 0.2f,
    val size: Int = 84,
    val font: String = "sans-serif",
    val weight: Int = 400,
    val italic: Boolean = false,
    val letterSpacing: Int = 0,
    val rotation: Int = 0,
    val color: Int? = null,
    val opacity: Int = 100,
    val visible: Boolean = true,
    val gradient: Boolean = false,
    val gradientStart: Int = 0xFF00E5FF.toInt(),
    val gradientEnd: Int = 0xFFFF2DAA.toInt(),
    val shadow: Boolean = false,
    val shadowColor: Int = Color.BLACK,
    val shadowSoftness: Int = 8,
    val shadowOpacity: Int = 35,
    val image: String = "",
    val imageWidth: Int = 120,
    val imageHeight: Int = 120,
    val imageAspectRatio: Float = imageWidth.toFloat() / imageHeight,
    val imageAspectLocked: Boolean = true,
    val imageFit: ClockImageFit = ClockImageFit.CROP,
    val imageShape: String = "none",
    val imageBorderWidth: Int = 0,
    val imageBorderColor: Int = Color.WHITE,
    val quickLookDateFallback: Boolean = true,
) {
    fun resizeImage(width: Int? = null, height: Int? = null): ClockElement {
        if (!imageAspectLocked) return copy(imageWidth = (width ?: imageWidth).coerceIn(16, 360),
            imageHeight = (height ?: imageHeight).coerceIn(16, 720))
        val ratio = imageAspectRatio.coerceIn(16f / 720, 360f / 16)
        val targetWidth = (width?.toFloat() ?: ((height ?: imageHeight) * ratio))
            .coerceIn(maxOf(16f, 16f * ratio), minOf(360f, 720f * ratio))
        return copy(imageWidth = targetWidth.roundToInt().coerceIn(16, 360),
            imageHeight = (targetWidth / ratio).roundToInt().coerceIn(16, 720))
    }

    fun toJson() = JSONObject()
        .put("id", id).put("kind", kind.name).put("text", text).put("format", format)
        .put("x", x.toDouble()).put("y", y.toDouble()).put("size", size).put("font", font)
        .put("weight", weight).put("italic", italic).put("letterSpacing", letterSpacing)
        .put("rotation", rotation).put("color", color ?: JSONObject.NULL).put("opacity", opacity)
        .put("visible", visible).put("gradient", gradient).put("gradientStart", gradientStart)
        .put("gradientEnd", gradientEnd).put("shadow", shadow).put("shadowColor", shadowColor)
        .put("shadowSoftness", shadowSoftness).put("shadowOpacity", shadowOpacity)
        .put("image", image).put("imageWidth", imageWidth).put("imageHeight", imageHeight)
        .put("imageAspectRatio", imageAspectRatio.toDouble())
        .put("imageAspectLocked", imageAspectLocked).put("imageFit", imageFit.name)
        .put("imageShape", imageShape)
        .put("imageBorderWidth", imageBorderWidth).put("imageBorderColor", imageBorderColor)
        .put("quickLookDateFallback", quickLookDateFallback)

    companion object {
        const val MIN_POSITION = -4f
        const val MAX_POSITION = 4f

        fun create(kind: ClockElementKind) = ClockElement(kind = kind,
            size = if (kind == ClockElementKind.TIME) 84 else 22,
            y = when (kind) {
                ClockElementKind.TIME -> 0.2f
                ClockElementKind.QUICKLOOK -> 0.65f
                else -> 0.45f
            },
            text = if (kind == ClockElementKind.TEXT) "Text" else "")

        fun validFormat(value: String): Boolean = value.length <= 80 &&
            runCatching { SimpleDateFormat(value, Locale.getDefault()) }.isSuccess

        fun fromJson(j: JSONObject): ClockElement {
            val kind = runCatching { ClockElementKind.valueOf(j.optString("kind")) }
                .getOrDefault(ClockElementKind.TEXT)
            val d = create(kind)
            fun number(key: String, default: Float, min: Float, max: Float): Float =
                j.optDouble(key, default.toDouble()).toFloat().let {
                    if (it.isFinite()) it.coerceIn(min, max) else default
                }
            return d.copy(
                id = j.optString("id", d.id).take(80), text = j.optString("text").take(240),
                format = j.optString("format").takeIf(::validFormat) ?: "",
                x = number("x", d.x, MIN_POSITION, MAX_POSITION),
                y = number("y", d.y, MIN_POSITION, MAX_POSITION),
                size = j.optInt("size", d.size).coerceIn(8, 200),
                font = j.optString("font", d.font).take(100),
                weight = j.optInt("weight", 400).coerceIn(100, 900),
                italic = j.optBoolean("italic"), letterSpacing = j.optInt("letterSpacing").coerceIn(-10, 50),
                rotation = j.optInt("rotation").coerceIn(-180, 180),
                color = if (j.isNull("color")) null else j.optInt("color", Color.WHITE),
                opacity = j.optInt("opacity", 100).coerceIn(0, 100), visible = j.optBoolean("visible", true),
                gradient = j.optBoolean("gradient"), gradientStart = j.optInt("gradientStart", d.gradientStart),
                gradientEnd = j.optInt("gradientEnd", d.gradientEnd), shadow = j.optBoolean("shadow"),
                shadowColor = j.optInt("shadowColor", d.shadowColor),
                shadowSoftness = j.optInt("shadowSoftness", 8).coerceIn(0, 30),
                shadowOpacity = j.optInt("shadowOpacity", 35).coerceIn(0, 100),
                image = j.optString("image").takeIf(ClockImages::validId) ?: "",
                imageWidth = j.optInt("imageWidth", 120).coerceIn(16, 360),
                imageHeight = j.optInt("imageHeight", 120).coerceIn(16, 720),
                imageAspectRatio = number("imageAspectRatio",
                    j.optInt("imageWidth", 120).coerceIn(16, 360).toFloat() /
                        j.optInt("imageHeight", 120).coerceIn(16, 720), 16f / 720, 360f / 16),
                imageAspectLocked = j.optBoolean("imageAspectLocked", true),
                imageFit = runCatching { ClockImageFit.valueOf(j.optString("imageFit")) }
                    .getOrDefault(ClockImageFit.CROP),
                imageShape = j.optString("imageShape", "none").take(40),
                imageBorderWidth = j.optInt("imageBorderWidth", 0).coerceIn(0, 24),
                imageBorderColor = j.optInt("imageBorderColor", Color.WHITE),
                quickLookDateFallback = j.optBoolean("quickLookDateFallback", true),
            )
        }
    }
}

data class ClockDesign(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Custom clock",
    val elements: List<ClockElement> = listOf(ClockElement.create(ClockElementKind.TIME),
        ClockElement.create(ClockElementKind.DATE)),
) {
    fun toJson() = JSONObject().put("version", 1).put("id", id).put("name", name)
        .put("elements", JSONArray().also { a -> elements.forEach { a.put(it.toJson()) } })

    companion object {
        const val STYLE = 103
        const val SETTING = "lock_screen_custom_clock_design"
        const val MAX_ELEMENTS = 16
        const val MAX_JSON_LENGTH = 32768

        fun parse(value: String?): ClockDesign? = value?.takeIf { it.length <= MAX_JSON_LENGTH }?.let {
            runCatching { fromJson(JSONObject(it)) }.getOrNull()
        }

        fun fromJson(j: JSONObject): ClockDesign {
            require(j.optInt("version", 1) == 1)
            val array = j.getJSONArray("elements")
            require(array.length() <= MAX_ELEMENTS)
            val elements = List(array.length()) { ClockElement.fromJson(array.getJSONObject(it)) }
            require(elements.map { it.id }.distinct().size == elements.size)
            return ClockDesign(j.optString("id", UUID.randomUUID().toString()).take(80),
                j.optString("name", "Custom clock").take(60), elements)
        }
    }
}

data class ClockDesignAppearance(
    val color: Int = Color.WHITE,
    val gradient: Boolean = false,
    val gradientStart: Int = 0xFF00E5FF.toInt(),
    val gradientEnd: Int = 0xFFFF2DAA.toInt(),
    val gradientAnchor: Int = 50,
    val gradientRadius: Int = 100,
    val weather: Boolean = true,
    val dozing: Boolean = false,
)
