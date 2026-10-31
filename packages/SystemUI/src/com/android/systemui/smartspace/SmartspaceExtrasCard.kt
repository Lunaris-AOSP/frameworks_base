/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.smartspace

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon

internal data class SmartspaceExtrasCard(
    val id: String,
    val title: CharSequence,
    val subtitle: CharSequence,
    val icon: Icon?,
    val fallbackIcon: Int,
    val pendingIntent: PendingIntent?,
    val intent: Intent?,
    val type: Type = Type.OTHER,
) {
    val isMusic: Boolean
        get() = type == Type.MUSIC || type == Type.MEDIA_SUGGESTION

    enum class Type(val priority: Int) {
        MUSIC(0),
        TORCH(1),
        NAVIGATION(2),
        BLUETOOTH(3),
        OTHER(4),
        MEDIA_SUGGESTION(4),
        WEATHER(5),
    }
}
