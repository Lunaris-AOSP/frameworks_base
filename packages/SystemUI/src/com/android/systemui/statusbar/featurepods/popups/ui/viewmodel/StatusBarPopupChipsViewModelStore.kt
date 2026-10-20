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

package com.android.systemui.statusbar.featurepods.popups.ui.viewmodel

import androidx.compose.runtime.snapshotFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.featurepods.popups.data.repository.DynamicIslandStateRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class StatusBarPopupChipsViewModelStore
@Inject
constructor(
    private val factory: StatusBarPopupChipsViewModel.Factory,
    @Application private val applicationScope: CoroutineScope,
    private val dynamicIslandStateRepository: DynamicIslandStateRepository,
) {
    private val viewModels = mutableMapOf<Int, StatusBarPopupChipsViewModel>()

    @Synchronized
    fun forDisplay(displayId: Int): StatusBarPopupChipsViewModel {
        return viewModels.getOrPut(displayId) {
            factory.create().also { model ->
                applicationScope.launch {
                    try {
                        coroutineScope {
                            launch { model.activate() }
                            snapshotFlow { model.dynamicIslandState }.collect {
                                dynamicIslandStateRepository.setState(displayId, it)
                            }
                        }
                    } finally {
                        dynamicIslandStateRepository.removeDisplay(displayId)
                    }
                }
            }
        }
    }
}
