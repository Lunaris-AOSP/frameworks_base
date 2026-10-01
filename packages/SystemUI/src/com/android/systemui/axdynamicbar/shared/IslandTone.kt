package com.android.systemui.axdynamicbar.shared

import android.media.AudioManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.systemui.islandevents.model.IslandEvent
import com.android.systemui.islandevents.model.RecordingState

/**
 * Material color pairs for the frosted surfaces. Legacy media and keyguard pills keep their
 * palette.
 */
internal enum class IslandTone {
    ACCENT,
    POSITIVE,
    ATTENTION,
    ALERT,
}

/** Saturated role: icon/text tints and translucent washes (`tint.copy(alpha = …)`). */
internal val IslandTone.tint: Color
    @Composable
    get() =
        when (this) {
            IslandTone.ACCENT -> MaterialTheme.colorScheme.primary
            IslandTone.POSITIVE -> MaterialTheme.colorScheme.secondary
            IslandTone.ATTENTION -> MaterialTheme.colorScheme.tertiary
            IslandTone.ALERT -> MaterialTheme.colorScheme.error
        }

/** Content legible on the saturated [tint] itself, for glyphs drawn inside a filled shape. */
internal val IslandTone.onTint: Color
    @Composable
    get() =
        when (this) {
            IslandTone.ACCENT -> MaterialTheme.colorScheme.onPrimary
            IslandTone.POSITIVE -> MaterialTheme.colorScheme.onSecondary
            IslandTone.ATTENTION -> MaterialTheme.colorScheme.onTertiary
            IslandTone.ALERT -> MaterialTheme.colorScheme.onError
        }

/** Filled container role: for chips that paint the tone as their background. */
internal val IslandTone.container: Color
    @Composable
    get() =
        when (this) {
            IslandTone.ACCENT -> MaterialTheme.colorScheme.primaryContainer
            IslandTone.POSITIVE -> MaterialTheme.colorScheme.secondaryContainer
            IslandTone.ATTENTION -> MaterialTheme.colorScheme.tertiaryContainer
            IslandTone.ALERT -> MaterialTheme.colorScheme.errorContainer
        }

/** Content legible on [container]. Never pair it with [tint] — that is what [onTint] is for. */
internal val IslandTone.onContainer: Color
    @Composable
    get() =
        when (this) {
            IslandTone.ACCENT -> MaterialTheme.colorScheme.onPrimaryContainer
            IslandTone.POSITIVE -> MaterialTheme.colorScheme.onSecondaryContainer
            IslandTone.ATTENTION -> MaterialTheme.colorScheme.onTertiaryContainer
            IslandTone.ALERT -> MaterialTheme.colorScheme.onErrorContainer
        }

internal fun toneFor(event: IslandEvent): IslandTone =
    when (event) {
        is IslandEvent.AudioRecording ->
            when (event.state) {
                RecordingState.RECORDING -> IslandTone.ALERT
                RecordingState.PAUSED -> IslandTone.ATTENTION
                RecordingState.SAVED -> IslandTone.POSITIVE
            }
        is IslandEvent.Call,
        is IslandEvent.Charging,
        is IslandEvent.BiometricUnlock -> IslandTone.POSITIVE
        is IslandEvent.Sports ->
            when (event.status) {
                IslandEvent.GameStatus.LIVE,
                IslandEvent.GameStatus.HALFTIME -> IslandTone.POSITIVE
                else -> IslandTone.ACCENT
            }
        is IslandEvent.Alarm -> IslandTone.ATTENTION
        is IslandEvent.RingerMode ->
            when (event.mode) {
                AudioManager.RINGER_MODE_SILENT,
                AudioManager.RINGER_MODE_VIBRATE -> IslandTone.ATTENTION
                else -> IslandTone.ACCENT
            }
        is IslandEvent.Vpn -> if (event.isValidated) IslandTone.POSITIVE else IslandTone.ATTENTION
        is IslandEvent.KeyguardIndication ->
            when (event.indicationType) {
                IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC,
                IslandEvent.KeyguardIndication.IndicationType.PERSISTENT_UNLOCK ->
                    IslandTone.POSITIVE
                IslandEvent.KeyguardIndication.IndicationType.ALIGNMENT -> IslandTone.ATTENTION
                else -> IslandTone.ACCENT
            }
        is IslandEvent.AospChip ->
            when (event.active.key) {
                "ScreenRecord",
                "ShareToApp",
                "CastToOtherDevice" -> IslandTone.ALERT
                else ->
                    if (event.active.key.startsWith("callChip-")) IslandTone.POSITIVE
                    else IslandTone.ACCENT
            }
        else -> IslandTone.ACCENT
    }

@Composable internal fun islandAccentColorFor(event: IslandEvent): Color = toneFor(event).tint

@Composable internal fun islandChipColorFor(event: IslandEvent): Color = toneFor(event).container

@Composable
internal fun islandChipContentColorFor(event: IslandEvent): Color = toneFor(event).onContainer

/** Reuses event glyphs and labels while resolving new surface accents from the Material scheme. */
internal data class IslandEventStyle(
    val tone: IslandTone,
    val icon: ImageVector?,
    val labelRes: Int,
)

internal val IslandEventStyle.accent: Color
    @Composable get() = tone.tint

internal fun islandEventStyleFor(event: IslandEvent): IslandEventStyle {
    val style = eventStyleFor(event)
    return IslandEventStyle(toneFor(event), style.icon, style.labelRes)
}
