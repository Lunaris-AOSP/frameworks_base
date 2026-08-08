/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.bouncer.ui

import android.content.Context
import com.android.internal.graphics.ColorUtils
import com.android.systemui.Flags
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.ColorId.NUM_PAD_BACKGROUND
import com.android.systemui.common.shared.colors.SurfaceEffectColors
import com.android.systemui.res.R.color
import com.android.systemui.shade.ui.ShadeColors

object BouncerColors {
    @JvmStatic
    fun Context.surfaceColor(blurSupported: Boolean): Int {
        return if (blurSupported) {
            ShadeColors.shadePanel(
                context = this,
                blurSupported = blurSupported,
                withScrim = true,
                allowCustomColor = false,
            )
        } else {
            ColorUtils.blendARGB(
                getColor(color.nt_scrim_behind_1),
                getColor(color.nt_scrim_behind_2),
                0.2f
            )
        }
    }

    @JvmStatic
    fun Context.pinDigitBg(): Int {
        return if (Flags.bouncerUiRevamp() && Flags.bouncerUiRevamp2()) {
            return SurfaceEffectColors.surfaceEffect1(context = this)
        } else {
            getColor(NUM_PAD_BACKGROUND)
        }
    }

    @JvmStatic
    fun Context.pinActionBg(): Int {
        return if (Flags.bouncerUiRevamp() && Flags.bouncerUiRevamp2()) {
            return getColor(color.pin_bouncer_action_button_bg)
        } else {
            getColor(NUM_PAD_BACKGROUND)
        }
    }
}
