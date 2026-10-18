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

package com.android.systemui.animation

import android.content.ComponentName

interface TransitionSource : Expandable {
    var isSourceVisible: Boolean
    val isAvailable: Boolean
}

class DynamicExpandable : Expandable {
    private val sources = linkedSetOf<TransitionSource>()
    private var isSourceVisible = true

    fun addSource(source: TransitionSource) {
        if (sources.add(source)) source.isSourceVisible = isSourceVisible
    }

    fun removeSource(source: TransitionSource) {
        sources.remove(source)
    }

    private fun updateSourceVisibility(visible: Boolean) {
        isSourceVisible = visible
        sources.forEach { it.isSourceVisible = visible }
    }

    private fun resolveSource() = sources.lastOrNull { it.isAvailable }

    override fun dialogTransitionController(cuj: DialogCuj?): DialogTransitionAnimator.Controller? {
        val source = resolveSource() ?: return null
        val controller = source.dialogTransitionController(cuj) ?: return null
        return object : DialogTransitionAnimator.Controller by controller {
            override val sourceIdentity: Any = this@DynamicExpandable

            override fun createTransitionController(): TransitionAnimator.Controller {
                val delegate = controller.createTransitionController()
                return object : TransitionAnimator.Controller by delegate {
                    override fun onTransitionAnimationEnd(isExpandingFullyAbove: Boolean) {
                        delegate.onTransitionAnimationEnd(isExpandingFullyAbove)
                        updateSourceVisibility(false)
                    }
                }
            }

            override fun createExitController(): TransitionAnimator.Controller {
                val delegate = controller.createExitController()
                return object : TransitionAnimator.Controller by delegate {
                    override fun onTransitionAnimationEnd(isExpandingFullyAbove: Boolean) {
                        delegate.onTransitionAnimationEnd(isExpandingFullyAbove)
                        updateSourceVisibility(true)
                    }
                }
            }

            override fun shouldAnimateExit() = source.isAvailable && controller.shouldAnimateExit()

            override fun onExitAnimationCancelled() {
                controller.onExitAnimationCancelled()
                updateSourceVisibility(true)
            }
        }
    }

    override fun activityTransitionController(
        launchCujType: Int?,
        cookie: ActivityTransitionAnimator.TransitionCookie?,
        component: ComponentName?,
        returnCujType: Int?,
        isEphemeral: Boolean,
    ): ActivityTransitionAnimator.Controller? {
        return resolveSource()
            ?.activityTransitionController(
                launchCujType,
                cookie,
                component,
                returnCujType,
                isEphemeral,
            )
    }
}
