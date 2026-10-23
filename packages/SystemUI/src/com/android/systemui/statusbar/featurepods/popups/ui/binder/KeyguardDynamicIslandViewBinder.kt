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

package com.android.systemui.statusbar.featurepods.popups.ui.binder

import android.widget.RelativeLayout
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.android.compose.theme.PlatformTheme
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.res.R
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandEnabled
import com.android.systemui.statusbar.featurepods.popups.ui.view.DynamicIslandIconFader
import com.android.systemui.statusbar.featurepods.popups.ui.compose.StatusBarDynamicIslandContainer
import com.android.systemui.statusbar.featurepods.popups.ui.viewmodel.StatusBarPopupChipsViewModelStore
import com.android.systemui.statusbar.phone.KeyguardStatusBarView
import javax.inject.Inject
import kotlinx.coroutines.flow.collect

class KeyguardDynamicIslandViewBinder
@Inject
constructor(
    private val viewModelStore: StatusBarPopupChipsViewModelStore,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val mediaHierarchyManager: MediaHierarchyManager,
) {
    fun bind(view: KeyguardStatusBarView) {
        val iconFader = DynamicIslandIconFader(view)
        val composeView = ComposeView(view.context).apply {
            layoutParams =
                RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                ).apply { addRule(RelativeLayout.CENTER_IN_PARENT) }
            setPadding(0, resources.getDimensionPixelSize(R.dimen.status_bar_padding_top), 0, 0)
            setViewCompositionStrategy(ViewCompositionStrategy.Default)
            setContent {
                val onLockscreen by remember {
                    keyguardTransitionInteractor.isFinishedIn(KeyguardState.LOCKSCREEN)
                }.collectAsState(initial = false)
                val isDozing by keyguardInteractor.isDozing.collectAsState(initial = false)

                if (onLockscreen && !isDozing) {
                    val viewModel = remember {
                        viewModelStore.forDisplay(view.context.displayId)
                    }
                    DisposableEffect(viewModel) {
                        onDispose {
                            viewModel.lockscreenPopupChips
                                .firstOrNull { it.isPopupShown }?.hidePopup?.invoke()
                            mediaHierarchyManager.isMediaControlPopupShowing = false
                        }
                    }
                    PlatformTheme {
                        StatusBarDynamicIslandContainer(
                            chips = viewModel.lockscreenPopupChips,
                            onIslandBoundsChanged = iconFader::update,
                            onMediaControlPopupVisibilityChanged = {
                                mediaHierarchyManager.isMediaControlPopupShowing = it
                            },
                        )
                    }
                }
            }
        }
        view.addView(composeView)
        iconFader.attach(composeView)
        view.repeatWhenAttached {
            observeDynamicIslandEnabled(view.context).collect { iconFader.setEnabled(it) }
        }
    }
}
