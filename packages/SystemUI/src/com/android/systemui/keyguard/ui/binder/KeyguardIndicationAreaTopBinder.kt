/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.binder

import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.keyguard.data.repository.LockscreenLockIconSettings
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView.AccessibilityHintType
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView.IconType
import com.android.systemui.keyguard.ui.view.KeyguardIndicationAreaTop
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.res.R
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.tuner.TunerService
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.combine

class KeyguardIndicationAreaTopBinder
@Inject
constructor(
    private val viewModel: Lazy<DeviceEntryIconViewModel>,
    private val tunerService: TunerService,
    private val falsingManager: FalsingManager,
    private val indicationController: KeyguardIndicationController,
) {
    fun bind(view: KeyguardIndicationAreaTop): DisposableHandle {
        return view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                indicationController.setIndicationAreaTop(view)
                val icon = view.lockIconView
                val model = viewModel.get()
                var showLockIcon = false
                fun updateVisibility() {
                    icon.isVisible = showLockIcon && !view.faceIconView.isVisible
                }
                view.faceIconView.visibilityListener = { updateVisibility() }
                icon.setOnLongClickListener {
                    if (falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)) {
                        false
                    } else {
                        launch { model.onUserInteraction() }
                        true
                    }
                }
                icon.isLongClickable = false
                launch {
                    model.isLongPressEnabled.collect { icon.isLongClickable = it }
                }
                launch {
                    model.accessibilityDelegateHint.collect { hint ->
                        if (hint == AccessibilityHintType.NONE) {
                            icon.setOnClickListener(null)
                            icon.isClickable = false
                        } else {
                            icon.setOnClickListener { launch { model.onUserInteraction() } }
                        }
                    }
                }
                try {
                    combine(
                            LockscreenLockIconSettings.observe(tunerService),
                            model.iconType,
                            model.deviceEntryViewAlpha,
                            model.keyguardInteractor.isDozing,
                        ) { settings, type, alpha, dozing ->
                            Triple(
                                type,
                                alpha,
                                settings.onTop && !settings.hidden && !dozing &&
                                    type != IconType.NONE && alpha > 0f,
                            )
                        }
                        .collect { (type, alpha, visible) ->
                            val unlocked = type == IconType.UNLOCK
                            icon.setImageResource(
                                if (unlocked) R.drawable.ic_unlocked else R.drawable.ic_lock
                            )
                            icon.contentDescription =
                                icon.resources.getString(
                                    if (unlocked) R.string.accessibility_unlock_button
                                    else R.string.accessibility_lock_icon
                                )
                            icon.alpha = alpha * 0.8f
                            showLockIcon = visible
                            updateVisibility()
                        }
                } finally {
                    indicationController.clearIndicationAreaTop(view)
                    view.faceIconView.visibilityListener = null
                    icon.setOnClickListener(null)
                    icon.setOnLongClickListener(null)
                    icon.isClickable = false
                    icon.isLongClickable = false
                    icon.visibility = View.GONE
                }
            }
        }
    }
}
