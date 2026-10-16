/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.qs.composefragment.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.SceneTransitionLayoutState
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.systemui.qs.composefragment.SceneKeys
import com.android.systemui.qs.shared.ui.QuickSettings.Elements
import com.android.systemui.shade.ui.composable.ShadeHeader

fun TransitionBuilder.axFromQuickQuickSettingsToQuickSettings() {
    fade(SceneKeys.QuickQuickSettings.rootElementKey)
    fade(SceneKeys.QuickSettings.rootElementKey)
    disableAxQsSharedElements()
    sharedElement(ShadeHeader.Elements.Clock, enabled = false)
}

fun TransitionBuilder.toAxEditMode() {
    fade(SceneKeys.EditMode.rootElementKey)
    scaleDraw(
        SceneKeys.EditMode.rootElementKey,
        scaleX = 0.85f,
        scaleY = 0.85f,
    )
    fade(SceneKeys.QuickSettings.rootElementKey)
    scaleDraw(
        SceneKeys.QuickSettings.rootElementKey,
        scaleX = 0.85f,
        scaleY = 0.85f,
    )
    fade(SceneKeys.QuickQuickSettings.rootElementKey)
    scaleDraw(
        SceneKeys.QuickQuickSettings.rootElementKey,
        scaleX = 0.85f,
        scaleY = 0.85f,
    )
    disableAxQsSharedElements()
}

private val VolumeSlider = ElementKey("VolumeSlider")

private fun TransitionBuilder.disableAxQsSharedElements() {
    sharedElement(Elements.TileElementMatcher, enabled = false)
    sharedElement(Elements.BrightnessSlider, enabled = false)
    sharedElement(VolumeSlider, enabled = false)
}

fun SceneTransitionLayoutState.shouldComposeLiveAxQs(): Boolean {
    return when (val state = transitionState) {
        is TransitionState.Idle -> state.currentScene.isAxQsScene()
        is TransitionState.Transition -> {
            state.fromContent.isAxQsScene() || state.toContent.isAxQsScene()
        }
    }
}

fun SceneTransitionLayoutState.editModeTransitionProgress(): Float {
    return when (val state = transitionState) {
        is TransitionState.Idle -> {
            if (state.currentScene == SceneKeys.EditMode) {
                1f
            } else {
                0f
            }
        }
        is TransitionState.Transition -> {
            val toEdit = state.toContent == SceneKeys.EditMode
            val fromEdit = state.fromContent == SceneKeys.EditMode
            when {
                toEdit -> state.progress.coerceIn(0f, 1f)
                fromEdit -> (1f - state.progress).coerceIn(0f, 1f)
                else -> 0f
            }
        }
    }
}

internal fun Modifier.axQsEntrance(progress: () -> Float): Modifier {
    return graphicsLayer {
        val entrance = progress().coerceIn(0f, 1f)
        alpha =
            ((entrance - AX_QS_ENTRANCE_ALPHA_START) / (1f - AX_QS_ENTRANCE_ALPHA_START))
                .coerceIn(0f, 1f)
        translationY =
            if (entrance == 0f) {
                AX_QS_ENTRANCE_HIDDEN_TRANSLATION_PX
            } else {
                -(1f - entrance) * AX_QS_ENTRANCE_TRANSLATION_PX
            }
    }
}

internal fun Modifier.axQuickSettingsSceneMotion(progress: () -> Float): Modifier {
    return graphicsLayer {
        val expansion = progress().coerceIn(0f, 1f)
        alpha =
            ((expansion - AX_QS_SCENE_FADE_START) / (1f - AX_QS_SCENE_FADE_START))
                .coerceIn(0f, 1f)
        translationY =
            if (expansion == 0f) {
                AX_QS_SCENE_HIDDEN_TRANSLATION_PX
            } else {
                -(1f - expansion) * AX_QS_SCENE_TRANSLATION_PX
            }
    }
}

private fun ContentKey.isAxQsScene(): Boolean {
    return this == SceneKeys.QuickSettings || this == SceneKeys.QuickQuickSettings
}

private const val AX_QS_ENTRANCE_ALPHA_START = 0.89f
private const val AX_QS_ENTRANCE_TRANSLATION_PX = 300f
private const val AX_QS_ENTRANCE_HIDDEN_TRANSLATION_PX = -5000f
private const val AX_QS_SCENE_FADE_START = 0.50f
private const val AX_QS_SCENE_TRANSLATION_PX = 300f
private const val AX_QS_SCENE_HIDDEN_TRANSLATION_PX = -5000f
