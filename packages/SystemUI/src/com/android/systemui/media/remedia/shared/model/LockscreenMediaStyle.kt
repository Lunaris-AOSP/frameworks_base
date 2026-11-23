/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.media.remedia.shared.model

enum class LockscreenMediaStyle(val settingValue: Int) {
    DEFAULT(0),
    ALBUM_ART(1),
    ONE_UI(2),
    COS(3);

    companion object {
        const val SETTING = "lockscreen_media_player_style"

        fun fromSetting(value: Int): LockscreenMediaStyle =
            entries.firstOrNull { it.settingValue == value } ?: DEFAULT
    }
}
