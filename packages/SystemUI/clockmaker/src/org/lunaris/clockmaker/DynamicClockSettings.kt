/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lunaris.clockmaker

import org.json.JSONObject
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object DynamicClockSettings {
    const val ENABLED = "lock_screen_dynamic_clock_enabled"
    const val LAYOUTS = "lock_screen_dynamic_clock_layouts"
    const val HOME_SMALL = "lock_screen_home_clock_use_small"
    const val SMALL = "small"
    const val LARGE = "large"
    const val ACTION = "org.lunaris.lockstyles.action.DYNAMIC_CLOCK"
    const val EXTRA_SIZE = "clock_size"
    const val TRANSITION_DURATION = 400L
    private const val MAX_SETTING_LENGTH = 32768
    private const val MAX_LAYOUT_BYTES = ClockDesign.MAX_JSON_LENGTH * 8

    fun encode(layouts: JSONObject): String {
        val json = layouts.toString()
        if (json.length <= MAX_SETTING_LENGTH) return json
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val value = "gz:" + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        require(value.length <= MAX_SETTING_LENGTH)
        return value
    }

    fun decode(value: String?): JSONObject? = runCatching {
        if (value.isNullOrBlank()) return@runCatching null
        require(value.length <= MAX_SETTING_LENGTH)
        val json = if (value.startsWith("gz:")) {
            val bytes = Base64.decode(value.substring(3), Base64.NO_WRAP)
            GZIPInputStream(bytes.inputStream()).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_LAYOUT_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } else value
        JSONObject(json)
    }.getOrNull()
}

data class ClockLayoutProfile(
    val values: Map<String, String?>,
    val small: Boolean,
    val preset: Int? = null,
    val rounded: Boolean = false,
    val color: Int? = null,
    val font: String? = null,
    val stockAxes: Map<String, Float>? = null,
    val inheritStockSettings: Boolean = false,
) {
    val style: Int
        get() = values[STYLE]?.toIntOrNull()?.coerceIn(0, ClockDesign.STYLE) ?: 0

    companion object {
        const val STYLE = "lock_screen_custom_clock_style"
        private const val SCALE = "lock_screen_custom_clock_size_scale"
        private const val TOP = "lock_screen_custom_clock_margin_top"
        private const val COLOR_MODE = "lock_screen_custom_clock_color_mode"
        private const val COLOR = "lock_screen_custom_clock_custom_color"

        private val integerFields = mapOf(
            "scale" to SCALE,
            "opacity" to "lock_screen_custom_clock_opacity",
            "shadowColor" to "lock_screen_custom_clock_shadow_color",
            "shadowSoftness" to "lock_screen_custom_clock_shadow_softness",
            "shadowOpacity" to "lock_screen_custom_clock_shadow_opacity",
            "marginTop" to TOP,
            "marginStart" to "lock_screen_custom_clock_margin_start",
            "rotation" to "lock_screen_custom_clock_rotation",
            "tiltX" to "lock_screen_custom_clock_tilt_x",
            "tiltY" to "lock_screen_custom_clock_tilt_y",
            "parallaxIntensity" to ClockParallaxSettings.INTENSITY,
            "glassIntensity" to ClockGlass.INTENSITY_SETTING,
            "glassLift" to ClockGlass.LIFT_SETTING,
            "gradientStart" to "lock_screen_custom_clock_gradient_color_start",
            "gradientEnd" to "lock_screen_custom_clock_gradient_color_end",
            "gradientAnchorY" to "lock_screen_custom_clock_gradient_anchor_y",
            "gradientRadius" to "lock_screen_custom_clock_gradient_radius",
        )
        private val booleanFields = mapOf(
            "shadowEnabled" to "lock_screen_custom_clock_shadow_enabled",
            "hyperBlur" to "lock_screen_custom_clock_hyper_blur",
            "textGlass" to ClockGlass.ENABLED_SETTING,
            "separateDateGlass" to ClockGlassStyle.SEPARATE_DATE_SETTING,
            "gradient" to "lock_screen_custom_clock_gradient_enabled",
            "albumArtColour" to "lock_screen_custom_clock_album_art_color",
            "wobbleOnCharge" to "lock_screen_custom_clock_wobble_on_charge",
            "weather" to "custom_clock_weather",
            "parallaxEnabled" to ClockParallaxSettings.ENABLED,
        )

        fun parse(value: String?, small: Boolean): ClockLayoutProfile? = runCatching {
            if (value.isNullOrBlank()) return@runCatching null
            val json = JSONObject(value)
            val style = json.getInt("face")
            require(style in 0..ClockDesign.STYLE)
            val tuning = json.optJSONObject("tuning") ?: JSONObject()
            val design = json.optJSONObject("design")
            if (style == ClockDesign.STYLE) require(ClockDesign.parse(design?.toString()) != null)
            val color = if (json.isNull("color")) null else json.optInt("color")
            val values = buildMap<String, String?> {
                put(STYLE, style.toString())
                put(ClockDesign.SETTING, design?.toString())
                integerFields.forEach { (field, key) ->
                    put(key, if (tuning.isNull(field)) null else tuning.getInt(field).toString())
                }
                booleanFields.forEach { (field, key) ->
                    put(key, if (tuning.isNull(field)) null else if (tuning.getBoolean(field)) "1" else "0")
                }
                put(ClockGlassStyle.SETTING, tuning.optJSONObject("glassStyle")?.toString())
                put(ClockGlassStyle.DATE_SETTING, tuning.optJSONObject("dateGlassStyle")?.toString())
                put(ClockDigits.SETTING, ClockDigitAlignment.parse(tuning.optString("digitAlignment")).name)
                put(COLOR, color?.toString())
                put(COLOR_MODE, when {
                    tuning.optBoolean("gradient") -> "custom"
                    tuning.optBoolean("accent") -> "accent"
                    color != null -> "custom"
                    else -> "default"
                })
            }
            ClockLayoutProfile(values, small,
                preset = if (json.isNull("preset")) null else json.optInt("preset").coerceIn(0, 6),
                rounded = json.optBoolean("rounded"), color = color,
                font = json.optString("font").takeIf { it.isNotBlank() && it != "null" },
                stockAxes = json.optJSONObject("stock_axes")?.let { axes ->
                    axes.keys().asSequence().take(32).mapNotNull { key ->
                        axes.optDouble(key).toFloat().takeIf { it.isFinite() }?.let { key to it }
                    }.toMap()
                })
        }.getOrNull()

        fun fallback(values: Map<String, String?>, small: Boolean): ClockLayoutProfile {
            val keys = integerFields.values + booleanFields.values + listOf(STYLE, COLOR_MODE,
                COLOR, ClockDesign.SETTING, ClockGlassStyle.SETTING, ClockGlassStyle.DATE_SETTING,
                ClockDigits.SETTING)
            val layoutValues = values.filterKeys { it in keys }.toMutableMap()
            val stock = (values[STYLE]?.toIntOrNull() ?: 0) == 0
            if (stock) {
                layoutValues.keys.toList().forEach { layoutValues[it] = null }
                layoutValues[STYLE] = "0"
                layoutValues[SCALE] = "100"
                layoutValues[TOP] = "15"
            }
            if (small) {
                layoutValues[SCALE] = if (stock) "100"
                    else ((values[SCALE]?.toIntOrNull() ?: 100) * 0.6f).toInt().coerceIn(50, 150).toString()
                layoutValues[TOP] = "0"
            }
            return ClockLayoutProfile(layoutValues, small, inheritStockSettings = stock)
        }
    }
}
