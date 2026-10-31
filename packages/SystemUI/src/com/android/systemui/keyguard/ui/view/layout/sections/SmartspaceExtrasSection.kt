/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.res.R
import com.android.systemui.smartspace.LockscreenSmartspaceExtrasController
import com.google.android.systemui.smartspace.BcSmartspaceView
import javax.inject.Inject

@SysUISingleton
class SmartspaceExtrasSection
@Inject
constructor(private val controller: LockscreenSmartspaceExtrasController) : KeyguardSection() {
    private var smartspaceHost: BcSmartspaceView? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        val host = constraintLayout.findViewById<View>(R.id.bc_smartspace_view) as? BcSmartspaceView
        smartspaceHost = host
        host?.let(controller::attachToSmartspace)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {}

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (smartspaceHost != null) {
            constraintSet.setVisibilityMode(
                R.id.bc_smartspace_view,
                ConstraintSet.VISIBILITY_MODE_IGNORE,
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        smartspaceHost?.setExtrasView(null)
        smartspaceHost = null
    }
}
