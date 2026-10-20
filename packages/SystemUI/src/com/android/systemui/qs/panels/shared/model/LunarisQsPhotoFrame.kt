/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.shared.model

import androidx.compose.runtime.Immutable
import org.json.JSONObject

enum class LunarisQsPhotoFilter {
    ORIGINAL,
    GRAYSCALE,
    SEPIA,
    WARM,
    COOL,
}

@Immutable
data class LunarisQsPhotoFrame(
    val userId: Int = -1,
    val fileName: String? = null,
    val positionX: Float = .5f,
    val positionY: Float = .5f,
    val zoom: Float = 1f,
    val borderDp: Int = 0,
    val cornerDp: Int = 24,
    val filter: LunarisQsPhotoFilter = LunarisQsPhotoFilter.ORIGINAL,
    val grayscale: Float = 0f,
    val dim: Float = 0f,
) {
    fun toJson(): String =
        JSONObject()
            .apply {
                put("file", fileName)
                put("x", positionX)
                put("y", positionY)
                put("zoom", zoom)
                put("border", borderDp)
                put("corner", cornerDp)
                put("filter", filter.name)
                put("grayscale", grayscale)
                put("dim", dim)
            }
            .toString()

    companion object {
        private val filePattern = Regex("(?:qqs|qs|split_shade)_[a-f0-9-]{36}\\.(?:webp|anim)")

        fun isValidFileName(value: String): Boolean = filePattern.matches(value)

        fun settingKey(layout: AxQsLayout): String =
            when (layout) {
                AxQsLayout.QQS -> "lunaris_qqs_photo_frame"
                AxQsLayout.QS -> "lunaris_qs_photo_frame"
                AxQsLayout.SPLIT_SHADE -> "lunaris_qs_split_shade_photo_frame"
            }

        fun fromJson(value: String?, userId: Int): LunarisQsPhotoFrame {
            val defaults = LunarisQsPhotoFrame(userId = userId)
            if (value.isNullOrBlank()) return defaults
            return try {
                val json = JSONObject(value)
                fun fraction(key: String, default: Float, min: Float = 0f, max: Float = 1f): Float {
                    val number = json.optDouble(key, default.toDouble()).toFloat()
                    return if (number.isFinite()) number.coerceIn(min, max) else default
                }
                defaults.copy(
                    fileName = json.optString("file").takeIf(::isValidFileName),
                    positionX = fraction("x", .5f),
                    positionY = fraction("y", .5f),
                    zoom = fraction("zoom", 1f, 1f, 3f),
                    borderDp = json.optInt("border", 0).coerceIn(0, 15),
                    cornerDp = json.optInt("corner", 24).coerceIn(0, 48),
                    filter =
                        LunarisQsPhotoFilter.entries.firstOrNull {
                            it.name == json.optString("filter")
                        } ?: LunarisQsPhotoFilter.ORIGINAL,
                    grayscale = fraction("grayscale", 0f),
                    dim = fraction("dim", 0f),
                )
            } catch (_: org.json.JSONException) {
                defaults
            }
        }
    }
}
