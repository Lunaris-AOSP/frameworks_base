package com.android.systemui.statusbar.featurepods.popups.ui.compose

internal enum class LunarisBarAlignment {
    CENTER,
    LEFT,
    RIGHT;

    companion object {
        fun fromSetting(value: Int): LunarisBarAlignment =
            when (value) {
                1 -> LEFT
                2 -> RIGHT
                else -> CENTER
            }
    }
}

internal fun lunarisBarCutoutMatches(
    alignment: LunarisBarAlignment,
    centerX: Float,
    windowWidth: Float,
): Boolean {
    if (windowWidth <= 0f) return false
    val fraction = centerX / windowWidth
    return when (alignment) {
        LunarisBarAlignment.LEFT -> fraction < 0.35f
        LunarisBarAlignment.RIGHT -> fraction > 0.65f
        LunarisBarAlignment.CENTER -> fraction in 0.35f..0.65f
    }
}

internal fun lunarisBarChipLeft(
    alignment: LunarisBarAlignment,
    windowWidth: Float,
    chipWidth: Float,
    cameraCenterX: Float?,
    cameraGap: Float,
    padding: Float,
    offsetX: Float,
    margin: Float,
): Float {
    val left =
        when (alignment) {
            LunarisBarAlignment.LEFT ->
                cameraCenterX?.let { it - cameraGap / 2f - padding } ?: margin
            LunarisBarAlignment.RIGHT ->
                (cameraCenterX?.let { it + cameraGap / 2f + padding } ?: (windowWidth - margin)) -
                    chipWidth
            LunarisBarAlignment.CENTER -> (cameraCenterX ?: (windowWidth / 2f)) - chipWidth / 2f
        }
    return (left + offsetX).coerceIn(
        margin,
        (windowWidth - margin - chipWidth).coerceAtLeast(margin),
    )
}
