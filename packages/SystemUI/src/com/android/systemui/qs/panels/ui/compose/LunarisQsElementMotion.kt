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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.ui.graphics.ContainerState
import com.android.compose.ui.graphics.container
import com.android.compose.ui.graphics.drawInContainer
import com.android.systemui.qs.LunarisQsMotion

internal class LunarisQsElementMotion(
    val expansion: () -> Float,
    val swipeProgress: () -> Float,
    val swipeFromQs: () -> Boolean,
    val panelWidth: () -> Float,
    val controlRows: Int,
    val tileRows: Int,
    val miuiAnimation: MiuiQsPanelMotion? = null,
) {
    fun panelRowTranslation(row: Int): Float {
        val swipe = swipeProgress()
        if (swipe < 0f) return 0f
        val depth = ((row + 1f) / (controlRows + tileRows + 1f)).coerceIn(0f, 1f)
        return LunarisQsMotion.panelRowOffset(swipe, swipeFromQs(), depth, panelWidth())
    }
}

internal val LocalLunarisQsElementMotion = staticCompositionLocalOf<LunarisQsElementMotion?> { null }

@Composable
internal fun Modifier.qsTouchBounds(): Modifier {
    val containerState = remember { ContainerState() }
    return container(containerState).clipToBounds().drawInContainer(containerState)
}

@Composable
internal fun qsPanelSwipeOverflow(): Dp {
    val motion = LocalLunarisQsElementMotion.current
    val swiping by remember(motion) { derivedStateOf { (motion?.swipeProgress?.invoke() ?: -1f) >= 0f } }
    return if (swiping && motion != null) {
        with(LocalDensity.current) { (motion.panelWidth() * 0.25f).toDp() }
    } else 0.dp
}

@Composable
internal fun qsMiuiEntranceOverflow(): Dp {
    val motion = LocalLunarisQsElementMotion.current?.miuiAnimation
    val running by remember(motion) { derivedStateOf { motion?.animationsRunning == true } }
    return if (running && motion != null) {
        val viewport = motion.viewportSize()
        val bounds = motion.bounds
        with(LocalDensity.current) {
            (maxOf(viewport.width, viewport.height, bounds.width, bounds.height) * 0.5f).toDp()
        }
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
