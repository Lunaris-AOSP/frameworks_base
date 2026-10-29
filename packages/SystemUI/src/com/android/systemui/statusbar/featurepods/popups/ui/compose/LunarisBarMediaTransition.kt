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

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.TransitionAnimator
import com.android.systemui.statusbar.featurepods.popups.data.repository.LunarisBarTransitionState
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import java.util.UUID
import kotlin.math.roundToInt
import android.graphics.Rect as AndroidRect

/** Measures the destination even while the player is foregrounded and its chip is hidden. */
@Composable
internal fun LunarisBarMediaTarget(
    chip: PopupChipModel.Shown,
    cutoutSpec: DynamicIslandCutoutSpec,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val media = (chip.popupContent as PopupContentModel.Media).model
    val owner = remember { Any() }
    val configuration = LocalConfiguration.current
    var bounds by
        remember(configuration, cutoutSpec, media.packageName, media.sourceUserId) {
            mutableStateOf<AndroidRect?>(null)
        }
    val clearBounds by rememberUpdatedState<() -> Unit>({ bounds = null })
    DisposableEffect(view, owner) {
        val listener =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    clearBounds()
                    view.requestLayout()
                }

                override fun onViewDetachedFromWindow(view: View) {
                    clearBounds()
                    LunarisBarTransitionState.clear(owner)
                }
            }
        view.addOnAttachStateChangeListener(listener)
        onDispose {
            view.removeOnAttachStateChangeListener(listener)
            LunarisBarTransitionState.clear(owner)
        }
    }
    SideEffect {
        val display = view.display
        val destination = bounds
        val packageName = media.packageName
        val userId = media.sourceUserId
        if (
            view.isAttachedToWindow && display != null && destination != null &&
                packageName != null && userId != null
        ) {
            LunarisBarTransitionState.update(
                owner,
                packageName,
                userId,
                display.displayId,
                display.rotation,
                destination,
            )
        } else {
            LunarisBarTransitionState.clear(owner)
        }
    }
    Layout(
        content = { LunarisBarChip(chip, pageCount, cutoutSpec, onTap = {}, measuring = true) },
        modifier =
            modifier.onGloballyPositioned { coordinates ->
                if (!view.isAttachedToWindow || !coordinates.isAttached) {
                    bounds = null
                    return@onGloballyPositioned
                }
                val screen = IntArray(2)
                val window = IntArray(2)
                view.getLocationOnScreen(screen)
                view.getLocationInWindow(window)
                val rect = coordinates.boundsInWindow()
                bounds =
                    AndroidRect(
                        rect.left.roundToInt() + screen[0] - window[0],
                        rect.top.roundToInt() + screen[1] - window[1],
                        rect.right.roundToInt() + screen[0] - window[0],
                        rect.bottom.roundToInt() + screen[1] - window[1],
                    )
            },
    ) { measurables, constraints ->
        val chipSize = measurables.single().measure(constraints)
        layout(chipSize.width, chipSize.height) {}
    }
}

/** An ephemeral launch origin; no return transition is registered for icon or Recents launches. */
internal fun lunarisBarLaunchController(
    view: View,
    bounds: Rect?,
    reducedMotion: Boolean,
): ActivityTransitionAnimator.Controller? {
    if (
        reducedMotion || !ValueAnimator.areAnimatorsEnabled() || !view.isShown ||
            bounds == null || bounds.isEmpty
    ) {
        return null
    }
    val root = view.rootView as? ViewGroup ?: return null
    return object : ActivityTransitionAnimator.Controller {
        override var transitionContainer = root
        override val isLaunching = true
        override val transitionCookie =
            if (ActivityTransitionAnimator.shellMigrationEnabled()) {
                ActivityTransitionAnimator.TransitionCookie("LunarisBar_${UUID.randomUUID()}")
            } else null

        override fun createAnimatorState() =
            TransitionAnimator.State(
                top = bounds.top.roundToInt(),
                bottom = bounds.bottom.roundToInt(),
                left = bounds.left.roundToInt(),
                right = bounds.right.roundToInt(),
                topCornerRadius = bounds.height / 2f,
                bottomCornerRadius = bounds.height / 2f,
            )
    }
}
