/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import org.json.JSONObject

/** Percent controls use 100 = 1; optical strengths and widths use 1000 = 1. */
data class ClockGlassStyle(
    val blurRadius: Int = 16,
    val glyphBlur: Int = 8,
    val tintOpacity: Int = 40,
    val refraction: Int = 85,
    val refractionWidth: Int = 40,
    val chromaticAberration: Int = 15,
    val contrast: Int = 140,
    val rimStrength: Int = 180,
    val rimRotation: Int = 170,
    val softRimStrength: Int = 0,
    val softRimWidth: Int = 100,
    val softRimTint: Int = 50,
    val shadowStrength: Int = 20,
    val blackBorder: Boolean = false,
    val blackBorderStrength: Int = 50,
    val separateRefraction: Boolean = false,
    val clockRefraction: Int = 60,
    val clockRefractionWidth: Int = 60,
    val clockRimStrength: Int = 180,
    val rimWidth: Int = 100,
) {
    fun normalized() = copy(
        blurRadius = blurRadius.coerceIn(0, 120), glyphBlur = glyphBlur.coerceIn(0, 1200), tintOpacity = tintOpacity.coerceIn(0, 100),
        refraction = refraction.coerceIn(0, 400), refractionWidth = refractionWidth.coerceIn(10, 200),
        chromaticAberration = chromaticAberration.coerceIn(0, 50), contrast = contrast.coerceIn(100, 200),
        rimStrength = rimStrength.coerceIn(0, 500), rimRotation = rimRotation.coerceIn(0, 360),
        softRimStrength = softRimStrength.coerceIn(0, 300), softRimWidth = softRimWidth.coerceIn(25, 300),
        softRimTint = softRimTint.coerceIn(0, 100), shadowStrength = shadowStrength.coerceIn(0, 200),
        blackBorderStrength = blackBorderStrength.coerceIn(0, 100),
        clockRefraction = clockRefraction.coerceIn(0, 200),
        clockRefractionWidth = clockRefractionWidth.coerceIn(10, 200),
        clockRimStrength = clockRimStrength.coerceIn(0, 400), rimWidth = rimWidth.coerceIn(-100, 300),
    )

    fun toJson(): JSONObject {
        val s = normalized()
        return JSONObject()
            .put("blurRadius", s.blurRadius).put("glyphBlur", s.glyphBlur).put("tintOpacity", s.tintOpacity)
            .put("refraction", s.refraction).put("refractionWidth", s.refractionWidth)
            .put("chromaticAberration", s.chromaticAberration).put("contrast", s.contrast)
            .put("rimStrength", s.rimStrength).put("rimRotation", s.rimRotation)
            .put("softRimStrength", s.softRimStrength).put("softRimWidth", s.softRimWidth)
            .put("softRimTint", s.softRimTint).put("shadowStrength", s.shadowStrength)
            .put("blackBorder", s.blackBorder).put("blackBorderStrength", s.blackBorderStrength)
            .put("separateRefraction", s.separateRefraction).put("clockRefraction", s.clockRefraction)
            .put("clockRefractionWidth", s.clockRefractionWidth).put("clockRimStrength", s.clockRimStrength)
            .put("rimWidth", s.rimWidth)
    }

    companion object {
        const val SETTING = "lock_screen_custom_clock_glass_style"
        const val DATE_SETTING = "lock_screen_custom_clock_date_glass_style"
        const val SEPARATE_DATE_SETTING = "lock_screen_custom_clock_separate_date_glass"

        val DATE_DEFAULT = ClockGlassStyle(glyphBlur = 0, tintOpacity = 50, refraction = 60, refractionWidth = 60,
            rimStrength = 100, clockRimStrength = 100, rimWidth = 50, shadowStrength = 0)

        fun parse(value: String?, fallback: ClockGlassStyle = ClockGlassStyle()): ClockGlassStyle =
            value?.takeIf { it.length <= 4096 }?.let {
                runCatching { fromJson(JSONObject(it), fallback) }.getOrNull()
            } ?: fallback.normalized()

        fun fromJson(j: JSONObject?, d: ClockGlassStyle = ClockGlassStyle()): ClockGlassStyle {
            if (j == null) return d.normalized()
            return ClockGlassStyle(
                blurRadius = j.optInt("blurRadius", d.blurRadius), glyphBlur = j.optInt("glyphBlur", d.glyphBlur), tintOpacity = j.optInt("tintOpacity", d.tintOpacity),
                refraction = j.optInt("refraction", d.refraction), refractionWidth = j.optInt("refractionWidth", d.refractionWidth),
                chromaticAberration = j.optInt("chromaticAberration", d.chromaticAberration), contrast = j.optInt("contrast", d.contrast),
                rimStrength = j.optInt("rimStrength", d.rimStrength), rimRotation = j.optInt("rimRotation", d.rimRotation),
                softRimStrength = j.optInt("softRimStrength", d.softRimStrength), softRimWidth = j.optInt("softRimWidth", d.softRimWidth),
                softRimTint = j.optInt("softRimTint", d.softRimTint), shadowStrength = j.optInt("shadowStrength", d.shadowStrength),
                blackBorder = j.optBoolean("blackBorder", d.blackBorder), blackBorderStrength = j.optInt("blackBorderStrength", d.blackBorderStrength),
                separateRefraction = j.optBoolean("separateRefraction", d.separateRefraction), clockRefraction = j.optInt("clockRefraction", d.clockRefraction),
                clockRefractionWidth = j.optInt("clockRefractionWidth", d.clockRefractionWidth), clockRimStrength = j.optInt("clockRimStrength", d.clockRimStrength),
                rimWidth = j.optInt("rimWidth", d.rimWidth),
            ).normalized()
        }

        /** Preserve the old intensity and white lift in existing exported designs. */
        fun legacy(intensity: Int, lift: Int) = ClockGlassStyle(
            blurRadius = intensity.coerceIn(0, 100) * 120 / 100,
            tintOpacity = lift.coerceIn(0, 100),
        )
    }
}
