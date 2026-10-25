/*
 * Copyright 2025-2026 AxionOS
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

package com.android.systemui.shade

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.qs.panels.data.repository.AxQsSettingsRepository
import com.android.systemui.qs.panels.shared.model.AxQsPanelMode
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class AxQsShadePolicy
@Inject
constructor(
    repository: AxQsSettingsRepository,
    @Application applicationScope: CoroutineScope,
    private val shadeController: Lazy<ShadeController>,
    shadeAnimationInteractor: ShadeAnimationInteractor,
    private val shadeModeInteractor: ShadeModeInteractor,
    private val notificationStackScrollLayoutController:
        Lazy<NotificationStackScrollLayoutController>,
) {
    private val panelMode = repository.panelMode
    private val quickPanelOnLeft = repository.quickPanelOnLeft
    val panelSwipeEnabled =
        combine(repository.panelSwipeEnabled, panelMode) { enabled, mode ->
            enabled && mode == AxQsPanelMode.SEPARATE
        }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    init {
        shadeAnimationInteractor.isAnyCloseAnimationRunning
            .onEach { collapsing ->
                notificationStackScrollLayoutController
                    .get()
                    .setSeparateShadeCollapsing(collapsing && isSeparateShade())
            }
            .launchIn(applicationScope)
    }

    fun isSeparateShade(): Boolean =
        com.android.systemui.qs.flags.MistQsMode.isEnabled &&
        panelMode.value == AxQsPanelMode.SEPARATE && !shadeModeInteractor.isSplitShade

    fun isSeparateQuickPanelGesture(x: Float, width: Float): Boolean {
        val midpoint = width / 2f
        return if (quickPanelOnLeft.value) x < midpoint else x >= midpoint
    }

    fun isPanelSwipeEnabled(): Boolean = isSeparateShade() && panelSwipeEnabled.value

    fun collapseSeparateShade(): Boolean {
        if (!isSeparateShade()) return false
        shadeController.get().animateCollapseShadeForced()
        return true
    }
}
