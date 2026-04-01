/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.featurepods.flashlight.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.res.R
import com.android.systemui.statusbar.featurepods.flashlight.shared.model.FlashlightPopupModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.ChipIcon
import com.android.systemui.statusbar.featurepods.popups.ui.model.ColorsModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupContentModel
import com.android.systemui.statusbar.featurepods.popups.ui.viewmodel.StatusBarPopupChipViewModel
import com.android.systemui.statusbar.policy.FlashlightController
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

/** ViewModel backing the flashlight page inside the dynamic island. */
class FlashlightPopupChipViewModel
@AssistedInject
constructor(
    @Application private val context: Context,
    private val flashlightController: FlashlightController,
) : StatusBarPopupChipViewModel, ExclusiveActivatable() {
    private val hydrator = Hydrator("FlashlightPopupChipViewModel.hydrator")

    override val chip: PopupChipModel by
        hydrator.hydratedStateOf(
            traceName = "chip",
            initialValue = PopupChipModel.Hidden(PopupChipId.Flashlight),
            source =
                callbackFlow {
                        val callback =
                            object : FlashlightController.FlashlightListener {
                                override fun onFlashlightChanged(enabled: Boolean) {
                                    trySend(readFlashlightState())
                                }

                                override fun onFlashlightError() {
                                    trySend(readFlashlightState())
                                }

                                override fun onFlashlightAvailabilityChanged(available: Boolean) {
                                    trySend(readFlashlightState())
                                }
                            }

                        flashlightController.addCallback(callback)
                        trySend(readFlashlightState())
                        awaitClose { flashlightController.removeCallback(callback) }
                    }
                    .map(::toPopupChipModel),
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    private fun toPopupChipModel(state: FlashlightState): PopupChipModel {
        if (!state.hasFlashlight || !state.isAvailable || !state.isEnabled) {
            return PopupChipModel.Hidden(PopupChipId.Flashlight)
        }

        val model =
            FlashlightPopupModel(
                levelPercent = null,
                turnOff = { flashlightController.setFlashlight(false) },
            )

        val contentDescription =
            ContentDescription.Resource(R.string.quick_settings_flashlight_label)

        return PopupChipModel.Shown(
            chipId = PopupChipId.Flashlight,
            icons =
                listOf(
                    ChipIcon(
                        icon =
                            Icon.Resource(
                                resId = R.drawable.ic_dynamic_island_flashlight,
                                contentDescription = contentDescription,
                            ),
                    )
                ),
            chipText = context.getString(R.string.dynamic_island_flashlight_short),
            colors = ColorsModel.DynamicIsland,
            contentDescription = contentDescription.loadContentDescription(context),
            popupContent = PopupContentModel.Flashlight(model),
        )
    }

    private fun readFlashlightState(): FlashlightState {
        return FlashlightState(
            hasFlashlight = flashlightController.hasFlashlight(),
            isAvailable = flashlightController.isAvailable(),
            isEnabled = flashlightController.isEnabled(),
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): FlashlightPopupChipViewModel
    }
}

private data class FlashlightState(
    val hasFlashlight: Boolean,
    val isAvailable: Boolean,
    val isEnabled: Boolean,
)
