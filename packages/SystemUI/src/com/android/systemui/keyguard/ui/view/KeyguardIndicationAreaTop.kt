/*
 * Copyright (C) 2025 crDroid Android Project
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
 *
 */

package com.android.systemui.keyguard.ui.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.FaceUnlockImageView

class KeyguardIndicationAreaTop(
    context: Context,
    attrs: AttributeSet?,
) :
    FrameLayout(
        context,
        attrs,
    ) {

    val lockIconView = ImageView(context, attrs).apply {
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }

    val faceIconView = FaceUnlockImageView(context, attrs).apply {
        id = R.id.face_unlock_icon
        scaleType = ImageView.ScaleType.FIT_CENTER
        alpha = 0.8f
        visibility = View.GONE
    }

    init {
        setId(R.id.keyguard_indication_area_top)
        clipChildren = false
        clipToPadding = false
        val faceIconSize = R.dimen.lockscreen_top_icon_size.dp()
        val iconMarginTop = R.dimen.face_unlock_icon_margin_top.dp()
        for (icon in listOf(lockIconView, faceIconView)) {
            val iconSize =
                if (icon === lockIconView) R.dimen.lockscreen_top_lock_icon_size.dp()
                else faceIconSize
            addView(
                icon,
                LayoutParams(iconSize, iconSize).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = iconMarginTop + (faceIconSize - iconSize) / 2
                },
            )
        }
    }

    private fun Int.dp(): Int {
        return context.resources.getDimensionPixelSize(this)
    }
}
