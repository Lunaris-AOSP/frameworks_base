/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.surfaceeffects.shaderutil

object GlowShaderLibrary {
    const val SHADER_LIB =
        """
        float curvedGlowBand(float band, float curve, float curveMix) {
            return mix(band, pow(band, curve), curveMix);
        }
        """
}
