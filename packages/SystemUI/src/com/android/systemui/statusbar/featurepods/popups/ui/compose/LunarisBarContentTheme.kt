/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import android.app.WallpaperColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LunarisBarContentTheme(
    model: PopupContentModel,
    chipId: PopupChipId? = null,
    content: @Composable () -> Unit,
) {
    val state = LocalLunarisBarState.current
    var settings = state.settings
    val inherited = LocalLunarisBarColors.current
    val card = (model as? PopupContentModel.Utility)?.card
    val media = (model as? PopupContentModel.Media)?.model
    val suffix = if (inherited.dark) "night" else "day"
    val feature = model.lunarisBarFeature(chipId)?.let { "feature_$it" }
    if (media != null) {
        for (key in
            listOf("media_palette", "media_mini_left_palette", "media_mini_right_palette")) {
            settings =
                settings.with(
                    key,
                    settings.integer(
                        "${key}_$suffix",
                        settings.integer(key, if (key == "media_palette") 4 else 0, 0, 20),
                        0,
                        20,
                    ),
                )
        }
        for (key in
            listOf(
                "media_background_color",
                "media_foreground_color",
                "media_accent_color",
                "media_mini_left_background",
                "media_mini_left_foreground",
                "media_mini_right_background",
                "media_mini_right_foreground",
            )) {
            val fallback =
                if (key.endsWith("background") || key == "media_background_color") inherited.surface
                else inherited.content
            settings =
                settings.with(
                    key,
                    settings.integer(
                        "${key}_$suffix",
                        settings.integer(key, fallback.toArgb(), Int.MIN_VALUE, Int.MAX_VALUE),
                        Int.MIN_VALUE,
                        Int.MAX_VALUE,
                    ),
                )
        }
    }
    if (feature != null) {
        for (key in
            listOf(
                "mini_shape",
                "right_shape",
                "card_icon_shape",
                "card_button_shape",
                "left_content",
                "right_content",
                "left_palette",
                "right_palette",
                "progress_palette",
                "track_palette",
            )) {
            val fallback =
                when (key) {
                    "mini_shape",
                    "right_shape" -> settings.integer("mini_shape", 2, 0, 31)
                    "card_icon_shape" -> settings.integer(key, 2, 0, 31)
                    "card_button_shape" -> settings.integer(key, 20, 0, 31)
                    "left_palette",
                    "right_palette" -> -1
                    "progress_palette" -> 5
                    "track_palette" -> 2
                    else -> 0
                }
            val value =
                if (key in listOf("left_palette", "right_palette"))
                    settings.integer(
                        "${feature}_${key}_$suffix",
                        settings.integer("${feature}_$key", 0, 0, 21),
                        0,
                        21,
                    ) - 1
                else if (
                    key in
                        listOf("mini_shape", "right_shape", "card_icon_shape", "card_button_shape")
                )
                    settings.integer("${feature}_$key", 0, 0, 32).let {
                        if (it == 0) fallback else it - 1
                    }
                else if (key.endsWith("palette"))
                    settings.integer(
                        "${feature}_${key}_$suffix",
                        settings.integer("${feature}_$key", fallback, 0, 20),
                        0,
                        20,
                    )
                else settings.integer("${feature}_$key", fallback, 0, 31)
            settings = settings.with(key, value)
        }
        for (part in listOf("left", "right", "progress", "track")) {
            for (role in listOf("background", "foreground")) {
                val key = "${part}_$role"
                val fallback =
                    if (role == "background") inherited.miniSurface else inherited.miniContent
                settings =
                    settings.with(
                        key,
                        settings.integer(
                            "${feature}_${key}_$suffix",
                            fallback.toArgb(),
                            Int.MIN_VALUE,
                            Int.MAX_VALUE,
                        ),
                    )
            }
        }
        settings =
            settings.with(
                "haptics",
                settings.isHapticsEnabled && settings.enabled("${feature}_haptics", true),
            )
    }
    val artwork = (media?.artworkIcon as? Icon.Loaded)?.drawable
    val artworkColor by
        produceState<Color?>(null, artwork) {
            value =
                artwork?.let {
                    withContext(Dispatchers.Default) {
                        runCatching {
                                val copy =
                                    it.constantState?.newDrawable()?.mutate()
                                        ?: return@runCatching null
                                Color(WallpaperColors.fromDrawable(copy).primaryColor.toArgb())
                            }
                            .getOrNull()
                    }
                }
        }
    val custom =
        if (card != null) {
            Color(
                card.integer(
                    "background_$suffix",
                    card.integer(
                        "background",
                        inherited.surface.toArgb(),
                        Int.MIN_VALUE,
                        Int.MAX_VALUE,
                    ),
                    Int.MIN_VALUE,
                    Int.MAX_VALUE,
                )
            ) to
                Color(
                    card.integer(
                        "foreground_$suffix",
                        card.integer(
                            "foreground",
                            inherited.content.toArgb(),
                            Int.MIN_VALUE,
                            Int.MAX_VALUE,
                        ),
                        Int.MIN_VALUE,
                        Int.MAX_VALUE,
                    )
                )
        } else {
            Color(
                settings.integer(
                    "media_background_color",
                    inherited.surface.toArgb(),
                    Int.MIN_VALUE,
                    Int.MAX_VALUE,
                )
            ) to
                Color(
                    settings.integer(
                        "media_foreground_color",
                        inherited.content.toArgb(),
                        Int.MIN_VALUE,
                        Int.MAX_VALUE,
                    )
                )
        }
    val palette =
        when {
            card?.enabled("custom_colors_$suffix", card.enabled("custom_colors", false)) == true ->
                6
            card != null ->
                card.integer("palette_$suffix", card.integer("palette", 0, 0, 21), 0, 21) - 1
            media != null -> settings.integer("media_palette", 4, 0, 20)
            feature != null ->
                settings.integer(
                    "${feature}_palette_$suffix",
                    settings.integer("${feature}_palette", 0, 0, 21),
                    0,
                    21,
                ) - 1
            else -> -1
        }
    val featureCustom =
        if (feature != null) {
            Color(
                settings.integer(
                    "${feature}_background_$suffix",
                    settings.integer(
                        "${feature}_background",
                        inherited.surface.toArgb(),
                        Int.MIN_VALUE,
                        Int.MAX_VALUE,
                    ),
                    Int.MIN_VALUE,
                    Int.MAX_VALUE,
                )
            ) to
                Color(
                    settings.integer(
                        "${feature}_foreground_$suffix",
                        settings.integer(
                            "${feature}_foreground",
                            inherited.content.toArgb(),
                            Int.MIN_VALUE,
                            Int.MAX_VALUE,
                        ),
                        Int.MIN_VALUE,
                        Int.MAX_VALUE,
                    )
                )
        } else custom
    val selected = rememberLunarisBarPalette(palette.coerceAtLeast(0), featureCustom)
    val cover =
        artworkColor?.let {
            val surface =
                lerp(
                    it,
                    if (inherited.dark) Color.Black else Color.White,
                    if (inherited.dark) 0.68f else 0.82f,
                )
            surface to if (surface.luminance() > 0.45f) Color(0xFF191919) else Color(0xFFF5F5F5)
        }
    val expanded =
        if (media != null && settings.enabled("media_artwork_colors", false) && cover != null) cover
        else if (palette >= 0) selected
        else inherited.surface to inherited.content
    val mini =
        if (media != null && settings.enabled("media_mini_artwork_colors", false) && cover != null)
            cover
        else if ((card != null || feature != null) && palette >= 0) selected
        else inherited.miniSurface to inherited.miniContent
    val colors =
        inherited.copy(
            surface = expanded.first.copy(alpha = inherited.surface.alpha),
            content = expanded.second,
            miniSurface = mini.first.copy(alpha = inherited.miniSurface.alpha),
            miniContent = mini.second,
            accent =
                if (
                    cover != null &&
                        media != null &&
                        settings.enabled("media_artwork_colors", false)
                )
                    lerp(artworkColor!!, if (inherited.dark) Color.White else Color.Black, 0.35f)
                else if (media != null && settings.integer("media_palette", 4, 0, 20) == 6)
                    Color(
                        settings.integer(
                            "media_accent_color",
                            inherited.accent.toArgb(),
                            Int.MIN_VALUE,
                            Int.MAX_VALUE,
                        )
                    )
                else if (palette >= 0) selected.second else inherited.accent,
            outline = expanded.second.copy(alpha = if (inherited.dark) 0.22f else 0.14f),
        )
    if (card != null) {
        settings =
            settings
                .with(
                    "card_icon_shape",
                    card.integer("icon_shape", settings.integer("card_icon_shape", 2, 0, 31), 0, 31),
                )
                .with(
                    "card_button_shape",
                    card.integer(
                        "toggle_shape",
                        settings.integer("card_button_shape", 20, 0, 31),
                        0,
                        31,
                    ),
                )
                .with(
                    "mini_shape",
                    card.integer("icon_shape", settings.integer("mini_shape", 2, 0, 31), 0, 31),
                )
    }
    if (card != null)
        settings =
            settings.with(
                "haptics",
                settings.isHapticsEnabled && card.enabled("card_haptics", true),
            )
    if (media != null)
        settings =
            settings.with(
                "haptics",
                settings.isHapticsEnabled && settings.enabled("media_haptics", true),
            )
    val scheme =
        MaterialTheme.colorScheme.copy(
            surface = colors.surface,
            surfaceContainerHigh = colors.surface,
            onSurface = colors.content,
            onSurfaceVariant = colors.content.copy(alpha = 0.72f),
            primary = colors.accent,
            onPrimary = if (colors.accent.luminance() > 0.45f) Color.Black else Color.White,
            secondaryContainer = lerp(colors.surface, colors.accent, 0.15f),
            onSecondaryContainer = colors.content,
        )
    CompositionLocalProvider(
        LocalLunarisBarState provides state.copy(settings = settings),
        LocalLunarisBarColors provides colors,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

internal fun PopupContentModel.lunarisBarFeature(chipId: PopupChipId? = null): String? =
    when (this) {
        is PopupContentModel.SystemEvent -> kind.name.lowercase()
        is PopupContentModel.Alarm -> "alarm"
        is PopupContentModel.Stopwatch -> "stopwatch"
        is PopupContentModel.ScreenRecord -> "screenrecord"
        is PopupContentModel.Flashlight -> "flashlight"
        is PopupContentModel.LiveScore -> "livescore"
        PopupContentModel.None ->
            if (chipId == PopupChipId.ShareScreenPrivacyIndicator) "screenshare" else "privacy"
        else -> null
    }

@Composable
internal fun lunarisBarPartColors(part: String, fallback: Pair<Color, Color>): Pair<Color, Color> {
    val settings = LocalLunarisBarState.current.settings
    return Color(
        settings.integer(
            "${part}_background",
            fallback.first.toArgb(),
            Int.MIN_VALUE,
            Int.MAX_VALUE,
        )
    ) to
        Color(
            settings.integer(
                "${part}_foreground",
                fallback.second.toArgb(),
                Int.MIN_VALUE,
                Int.MAX_VALUE,
            )
        )
}
