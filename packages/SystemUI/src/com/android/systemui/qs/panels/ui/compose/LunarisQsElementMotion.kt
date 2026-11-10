/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.LunarisQsMotion

internal class LunarisQsElementMotion(
    val expansion: () -> Float,
    val swipeProgress: () -> Float,
    val swipeFromQs: () -> Boolean,
    val panelWidth: () -> Float,
    val controlRows: Int,
    val tileRows: Int,
)

internal val LocalLunarisQsElementMotion = staticCompositionLocalOf<LunarisQsElementMotion?> { null }

@Composable
internal fun qsPanelSwipeOverflow(): Dp {
    val motion = LocalLunarisQsElementMotion.current
    val swiping by remember(motion) { derivedStateOf { (motion?.swipeProgress?.invoke() ?: -1f) >= 0f } }
    return if (swiping && motion != null) {
        with(LocalDensity.current) { (motion.panelWidth() * 0.25f).toDp() }
    } else 0.dp
}

internal fun Modifier.lunarisQsElementMotion(
    motion: LunarisQsElementMotion?,
    row: Int,
): Modifier {
    if (motion == null) return this
    val depth = ((row + 1f) / (motion.controlRows + motion.tileRows + 1f)).coerceIn(0f, 1f)
    return graphicsLayer {
        val expansion = motion.expansion()
        alpha = LunarisQsMotion.entranceAlpha(expansion, depth)
        translationY =
            (LunarisQsMotion.entranceTranslation(expansion, depth) -
                LunarisQsMotion.entranceTranslation(expansion, 0f)).dp.toPx()
        val swipe = motion.swipeProgress()
        translationX =
            if (swipe >= 0f) {
                LunarisQsMotion.panelRowOffset(
                    swipe, motion.swipeFromQs(), depth, motion.panelWidth()
                )
            } else 0f
    }
}
