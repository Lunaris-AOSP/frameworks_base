/* Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.statusbar.featurepods.popups.ui.viewmodel

internal data class OngoingActivityContent(
    val chipText: String?,
    val title: String,
    val text: String,
    val prominentText: String?,
)

internal fun ongoingActivityContent(
    shortText: String,
    title: String,
    text: String,
    appName: String,
    progress: Float?,
    indeterminate: Boolean,
    navigation: Boolean,
): OngoingActivityContent {
    val short = shortText.trim().takeIf { it.isNotEmpty() }
    fun withoutNavigationPrefix(value: String): String {
        val trimmed = value.trim()
        return if (
            navigation &&
                short != null &&
                trimmed.startsWith(short) &&
                (trimmed.length == short.length ||
                    trimmed[short.length].isWhitespace() ||
                    trimmed[short.length] in "·•:–—-")
        ) {
            trimmed.removePrefix(short).trimStart { it.isWhitespace() || it in "·•:–—-" }
        } else trimmed
    }
    val heading = withoutNavigationPrefix(title)
    val body = withoutNavigationPrefix(text)
    val popupTitle = heading.ifBlank { body.ifBlank { appName } }
    return OngoingActivityContent(
        chipText =
            short
                ?: when {
                    !navigation && indeterminate -> null
                    !navigation && progress != null ->
                        "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
                    else -> popupTitle
                },
        title = popupTitle,
        text = body.takeUnless { it == popupTitle }.orEmpty(),
        prominentText = short?.takeIf { navigation },
    )
}
