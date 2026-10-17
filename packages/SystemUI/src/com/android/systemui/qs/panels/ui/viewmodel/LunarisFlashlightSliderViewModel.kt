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

package com.android.systemui.qs.panels.ui.viewmodel

import android.app.ActivityManager
import androidx.compose.runtime.staticCompositionLocalOf
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

val LocalLunarisFlashlightSliderViewModel =
    staticCompositionLocalOf<LunarisFlashlightSliderViewModel> {
        error("LunarisFlashlightSliderViewModel not provided")
    }

data class LunarisFlashlightSliderState(
    val available: Boolean,
    val enabled: Boolean,
    val supportsStrength: Boolean,
    val level: Int,
    val maxLevel: Int,
)

@SysUISingleton
class LunarisFlashlightSliderViewModel
@Inject
constructor(
    private val flashlightController: FlashlightController,
    private val falsingManager: FalsingManager,
    @Application applicationScope: CoroutineScope,
) {
    val hasFlashlight: Boolean
        get() = flashlightController.hasFlashlight()

    val state =
        conflatedCallbackFlow {
                val callback =
                    object : FlashlightController.FlashlightListener {
                        override fun onFlashlightChanged(enabled: Boolean) {
                            trySend(readState().copy(enabled = enabled))
                        }

                        override fun onFlashlightError() {
                            trySend(readState().copy(enabled = false))
                        }

                        override fun onFlashlightAvailabilityChanged(available: Boolean) {
                            trySend(readState().copy(available = hasFlashlight && available))
                        }

                        override fun onFlashlightStrengthChanged(level: Int) {
                            trySend(readState())
                        }
                    }
                flashlightController.addCallback(callback)
                awaitClose { flashlightController.removeCallback(callback) }
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(), readState())

    fun toggle(): Boolean {
        if (!canInteract() || falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) return false
        flashlightController.setFlashlight(!flashlightController.isEnabled)
        return true
    }

    fun startAdjusting(): Boolean =
        canInteract() &&
            flashlightController.isStrengthControlSupported &&
            !falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)

    fun setLevel(value: Float): Boolean {
        if (
            !value.isFinite() || !canInteract() || !flashlightController.isStrengthControlSupported
        ) {
            return false
        }
        val level = value.roundToInt().coerceIn(1, flashlightController.maxLevel.coerceAtLeast(1))
        if (level == flashlightController.currentLevel) return false
        flashlightController.setFlashlightStrengthLevel(level)
        return true
    }

    private fun canInteract(): Boolean =
        hasFlashlight && flashlightController.isAvailable && !ActivityManager.isUserAMonkey()

    private fun readState(): LunarisFlashlightSliderState {
        val maxLevel = flashlightController.maxLevel.coerceAtLeast(1)
        return LunarisFlashlightSliderState(
            available = hasFlashlight && flashlightController.isAvailable,
            enabled = flashlightController.isEnabled,
            supportsStrength = flashlightController.isStrengthControlSupported && maxLevel > 1,
            level = flashlightController.currentLevel.coerceIn(1, maxLevel),
            maxLevel = maxLevel,
        )
    }
}
