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

package com.android.systemui.volume.dialog.ui.view

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.android.axion.blur.AxBlurBackgroundRenderer

/**
 * A drop-in replacement for the plain background [View] in the Redesigned volume dialog.
 * Applies real-time AxBlur backdrop blur over the rounded-rect panel, falling back to the
 * original drawable when blur is unavailable or disabled.
 */
class VolumeDialogBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val blurRenderer = AxBlurBackgroundRenderer(this)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blurRenderer.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        blurRenderer.onDetachedFromWindow()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        blurRenderer.onVisibilityAggregated(isVisible)
    }

    override fun onDraw(canvas: Canvas) {
        // Try to draw blur; if blur is unavailable, fall through to the normal background drawable
        if (!blurRenderer.drawBackground(canvas, background)) {
            super.onDraw(canvas)
        }
    }
}
