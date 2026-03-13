/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.ims.ui

import android.content.Context
import android.widget.ImageView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.icons.shared.model.BindableIcon
import com.android.systemui.statusbar.pipeline.icons.shared.model.ModernStatusBarViewCreator
import com.android.systemui.statusbar.pipeline.ims.ui.binder.ImsStatusBarIconBinder
import com.android.systemui.statusbar.pipeline.ims.ui.viewmodel.VoNrStatusBarIconViewModel
import com.android.systemui.statusbar.pipeline.shared.ui.view.SingleBindableStatusBarIconView
import javax.inject.Inject

@SysUISingleton
class VoNrBindableIcon
@Inject
constructor(
    context: Context,
    viewModel: VoNrStatusBarIconViewModel,
) : BindableIcon {
    override val slot: String =
        context.getString(com.android.internal.R.string.status_bar_vonr)

    override val initializer = ModernStatusBarViewCreator { context ->
        SingleBindableStatusBarIconView.createView(context).also { view ->
            view.requireViewById<ImageView>(R.id.icon_view).apply {
                val verticalPadding =
                    context.resources.getDimensionPixelSize(R.dimen.status_bar_bindable_icon_padding)
                adjustViewBounds = true
                setPadding(0, verticalPadding, 0, verticalPadding)
            }
            view.initView(slot) { ImsStatusBarIconBinder.bind(view, viewModel) }
        }
    }

    override val shouldBindIcon: Boolean = true
}
