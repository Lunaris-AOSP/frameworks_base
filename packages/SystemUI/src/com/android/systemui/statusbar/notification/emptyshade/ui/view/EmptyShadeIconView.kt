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

package com.android.systemui.statusbar.notification.emptyshade.ui.view

import android.annotation.ColorInt
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.animation.LaunchableView
import com.android.systemui.animation.LaunchableViewDelegate
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView
import com.android.systemui.statusbar.notification.stack.ExpandableViewState

/** View representing the content in the notification shade when there are no notifications. */
class EmptyShadeIconView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    StackScrollerDecorView(context, attrs), LaunchableView {

    private lateinit var textView: TextView
    private lateinit var iconView: ImageView

    private val launchableViewDelegate =
        LaunchableViewDelegate(this) { visibility: Int? -> super.setVisibility(visibility!!) }

    override fun onFinishInflate() {
        super.onFinishInflate()
        textView = findViewById(R.id.text)
        iconView = findViewById(R.id.icon)
    }

    override fun setVisibility(visibility: Int) {
        launchableViewDelegate.setVisibility(visibility)
    }

    override fun setShouldBlockVisibilityChanges(block: Boolean) {
        launchableViewDelegate.setShouldBlockVisibilityChanges(block)
    }

    override fun findContentView(): View {
        return findViewById(R.id.content)
    }

    override fun findSecondaryView(): View? {
        return null
    }

    fun setText(text: String) {
        if (textView.text != text) {
            textView.text = text
        }
    }

    fun setIcon(icon: Icon?) {
        val drawable =
            when (icon) {
                is Icon.Loaded -> icon.drawable.mutate()
                is Icon.Resource -> context.getDrawable(icon.resId)
                null -> null
            }
        iconView.setImageDrawable(drawable)
        iconView.visibility = if (icon == null) View.GONE else View.VISIBLE
    }

    /** Update view color. */
    fun setContentColor(@ColorInt color: Int) {
        textView.setTextColor(color)
        iconView.setColorFilter(color)
    }

    public override fun createExpandableViewState(): ExpandableViewState {
        return EmptyShadeViewState()
    }

    inner class EmptyShadeViewState : ExpandableViewState() {
        override fun applyToView(view: View) {
            super.applyToView(view)
            if (view is EmptyShadeIconView) {
                view.setContentVisibleAnimated(view.isVisible)
            }
        }
    }
}
