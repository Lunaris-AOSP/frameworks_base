/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.controls.ui.controller

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.Dumpable
import com.android.systemui.classifier.Classifier
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.initOnBackPressedDispatcherOwner
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.media.remedia.ui.compose.LockscreenMedia
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.media.remedia.ui.viewmodel.MediaFalsingSystem
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.panels.ui.viewmodel.AxMediaViewModel
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.MediaContainerView
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.SplitShadeStateController
import com.android.systemui.util.asIndenting
import com.android.systemui.util.boundsOnScreen
import com.android.systemui.util.println
import com.android.systemui.util.withIncreasedIndent
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Controls the media notifications on the lock screen, handles its visibility and placement -
 * switches media player positioning between split pane container vs single pane container
 */
@SysUISingleton
class KeyguardMediaController
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val bypassController: KeyguardBypassController,
    private val statusBarStateController: SysuiStatusBarStateController,
    @ShadeDisplayAware private val context: Context,
    @ShadeDisplayAware private val configurationController: ConfigurationController,
    private val splitShadeStateController: SplitShadeStateController,
    private val logger: KeyguardMediaControllerLogger,
    dumpManager: DumpManager,
    private val mediaViewModelFactory: MediaViewModel.Factory,
    private val mediaCarouselInteractor: MediaCarouselInteractor,
    private val falsingSystem: MediaFalsingSystem,
    private val lockscreenMediaViewModel: AxMediaViewModel,
    private val mediaDataManager: MediaDataManager,
) : Dumpable {
    private var lastUsedStatusBarState = -1

    private val composeView =
        ComposeView(context).apply {
            repeatWhenAttached {
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.CREATED) {
                        initOnBackPressedDispatcherOwner(this@repeatWhenAttached.lifecycle)
                        setComposeContent(this@apply)
                    }
                }
            }
        }

    private var isMediaVisibleOnLockscreen = false
        set(value) {
            field = value
            onMediaHostVisibilityChanged(value)
        }

    private fun setUpListenersAndCallbacks() {
        if (SceneContainerFlag.isEnabled) return

        applicationScope.launch {
            combine(
                    mediaCarouselInteractor.allowMediaOnLockscreen,
                    mediaCarouselInteractor.hasActiveMedia,
                    mediaCarouselInteractor.isOnLockscreen,
                ) { allowMediaOnLockscreen, activeMedia, isOnLockscreen ->
                    if (allowMediaOnLockscreen && isOnLockscreen) {
                        activeMedia
                    } else {
                        false
                    }
                }
                .collect { isMediaVisibleOnLockscreen = it }
        }
        statusBarStateController.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onStateChanged(newState: Int) {
                    refreshMediaPosition(reason = "StatusBarState.onStateChanged")
                }

                override fun onDozingChanged(isDozing: Boolean) {
                    refreshMediaPosition(reason = "StatusBarState.onDozingChanged")
                }
            }
        )
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration?) {
                    updateResources()
                }
            }
        )
        bypassController.registerOnBypassStateChangedListener(
            object : KeyguardBypassController.OnBypassStateChangedListener {
                override fun onBypassStateChanged(isEnabled: Boolean) {
                    refreshMediaPosition(reason = "bypass state changed")
                }
            }
        )
        updateResources()
    }

    @VisibleForTesting
    internal fun listenForLockscreenMediaAllowedChanges(scope: CoroutineScope): Job {
        return scope.launch {
            mediaCarouselInteractor.allowMediaOnLockscreen.collect {
                refreshMediaPosition(reason = "lockscreen media allowed changed")
            }
        }
    }

    private fun setComposeContent(composeView: ComposeView) {
        composeView.setContent {
            LockscreenMedia(
                viewModelFactory = mediaViewModelFactory,
                lockscreenViewModel = lockscreenMediaViewModel,
                behavior =
                    MediaUiBehavior(
                        carouselVisibility = MediaCarouselVisibility.WhenAnyCardIsActive,
                        isCarouselScrollFalseTouch = {
                            falsingSystem.isFalseTouch(Classifier.MEDIA_CAROUSEL_SWIPE)
                        },
                    ),
                onDismissed = { mediaDataManager.onSwipeToDismiss() },
                visible = visible,
                onVisibilityChanged = { isVisible ->
                    isComposeMediaVisible = isVisible
                    if (!isVisible && !visible) hideMediaPlayer()
                },
            )
        }
    }

    private fun updateResources() {
        useSplitShade = splitShadeStateController.shouldUseSplitNotificationShade(context.resources)
    }

    @VisibleForTesting
    var useSplitShade = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            reattachHostView()
            refreshMediaPosition(reason = "useSplitShade changed")
        }

    /** Is the media player visible? */
    var visible by mutableStateOf(false)
        private set

    var visibilityChangedListener: ((Boolean) -> Unit)? = null

    var notificationPreviewVisible = false
        set(value) {
            if (field == value) return
            field = value
            refreshMediaPosition(reason = "notification preview visibility changed")
        }

    /** single pane media container placed at the top of the notifications list */
    var singlePaneContainer: MediaContainerView? = null
        private set

    private var splitShadeContainer: ViewGroup? = null
    private var isComposeMediaVisible = false

    init {
        dumpManager.registerDumpable(this)
        setUpListenersAndCallbacks()
    }

    /**
     * Attaches media container in single pane mode, situated at the top of the notifications list
     */
    fun attachSinglePaneContainer(mediaView: MediaContainerView?) {
        SceneContainerFlag.assertInLegacyMode()
        singlePaneContainer = mediaView
        reattachHostView()
        onMediaHostVisibilityChanged(isMediaVisibleOnLockscreen)

        singlePaneContainer?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Called whenever the media hosts visibility changes */
    @VisibleForTesting
    fun onMediaHostVisibilityChanged(visible: Boolean) {
        refreshMediaPosition(reason = "onMediaHostVisibilityChanged")

        if (visible) {
            if (useSplitShade) {
                return
            }
            composeView.layoutParams?.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                width = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    /** Attaches media container in split shade mode, situated to the left of notifications */
    fun attachSplitShadeContainer(container: ViewGroup) {
        SceneContainerFlag.assertInLegacyMode()
        splitShadeContainer = container
        reattachHostView()
        refreshMediaPosition(reason = "attachSplitShadeContainer")
    }

    private fun reattachHostView() {
        val inactiveContainer: ViewGroup?
        val activeContainer: ViewGroup?
        if (useSplitShade) {
            activeContainer = splitShadeContainer
            inactiveContainer = singlePaneContainer
        } else {
            inactiveContainer = splitShadeContainer
            activeContainer = singlePaneContainer
        }
        if (inactiveContainer?.childCount == 1) {
            inactiveContainer.removeAllViews()
        }
        if (activeContainer?.childCount == 0) {
            composeView.parent?.let { (it as? ViewGroup)?.removeView(composeView) }
            activeContainer.addView(composeView)
            composeView.layoutParams.apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                width = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    fun isWithinMediaViewBounds(x: Int, y: Int): Boolean {
        if (!visible) return false
        return composeView.boundsOnScreen.contains(x, y)
    }

    fun refreshMediaPosition(reason: String) {
        SceneContainerFlag.assertInLegacyMode()
        val currentState = statusBarStateController.state

        val keyguardOrUserSwitcher = (currentState == StatusBarState.KEYGUARD)
        val isMediaHostVisible = isMediaVisibleOnLockscreen
        val isBypassNotEnabled = !bypassController.bypassEnabled
        val useSplitShade = useSplitShade
        val shouldBeVisibleForSplitShade = shouldBeVisibleForSplitShade()
        visible =
            isMediaHostVisible &&
                isBypassNotEnabled &&
                keyguardOrUserSwitcher &&
                shouldBeVisibleForSplitShade &&
                !notificationPreviewVisible
        logger.logRefreshMediaPosition(
            reason = reason,
            visible = visible,
            useSplitShade = useSplitShade,
            currentState = currentState,
            keyguardOrUserSwitcher = keyguardOrUserSwitcher,
            mediaHostVisible = isMediaHostVisible,
            bypassNotEnabled = isBypassNotEnabled,
            shouldBeVisibleForSplitShade = shouldBeVisibleForSplitShade,
        )
        val currActiveContainer = activeContainer

        logger.logActiveMediaContainer("before refreshMediaPosition", currActiveContainer)
        if (visible) {
            showMediaPlayer()
        } else if (!isComposeMediaVisible || !composeView.isAttachedToWindow) {
            hideMediaPlayer()
        }
        logger.logActiveMediaContainer("after refreshMediaPosition", currActiveContainer)

        lastUsedStatusBarState = currentState
    }

    private fun shouldBeVisibleForSplitShade(): Boolean {
        if (!useSplitShade) {
            return true
        }
        // We have to explicitly hide media for split shade when on AOD, as it is a child view of
        // keyguard status view, and nothing hides keyguard status view on AOD.
        // When using the double-line clock, it is not an issue, as media gets implicitly hidden
        // by the clock. This is not the case for single-line clock though.
        // For single shade, we don't need to do it, because media is a child of NSSL, which already
        // gets hidden on AOD.
        return !statusBarStateController.isDozing
    }

    private fun showMediaPlayer() {
        if (useSplitShade) {
            setVisibility(splitShadeContainer, View.VISIBLE)
            setVisibility(singlePaneContainer, View.GONE)
        } else {
            setVisibility(singlePaneContainer, View.VISIBLE)
            setVisibility(splitShadeContainer, View.GONE)
        }
    }

    private fun hideMediaPlayer() {
        // always hide splitShadeContainer as it's initially visible and may influence layout
        setVisibility(splitShadeContainer, View.GONE)
        setVisibility(singlePaneContainer, View.GONE)
    }

    private fun setVisibility(view: ViewGroup?, newVisibility: Int) {
        val currentMediaContainer = view ?: return

        val isVisible = newVisibility == View.VISIBLE

        if (currentMediaContainer is MediaContainerView) {
            val previousVisibility = currentMediaContainer.visibility

            currentMediaContainer.setKeyguardVisibility(isVisible)
            if (previousVisibility != newVisibility) {
                visibilityChangedListener?.invoke(isVisible)
            }
        } else {
            currentMediaContainer.visibility = newVisibility
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.asIndenting().run {
            println("KeyguardMediaController")
            withIncreasedIndent {
                println("Self", this@KeyguardMediaController)
                println("visible", visible)
                println("notificationPreviewVisible", notificationPreviewVisible)
                println("useSplitShade", useSplitShade)
                println("bypassController.bypassEnabled", bypassController.bypassEnabled)
                println("singlePaneContainer", singlePaneContainer)
                println("splitShadeContainer", splitShadeContainer)
                if (lastUsedStatusBarState != -1) {
                    println(
                        "lastUsedStatusBarState",
                        StatusBarState.toString(lastUsedStatusBarState),
                    )
                }
                println(
                    "statusBarStateController.state",
                    StatusBarState.toString(statusBarStateController.state),
                )
            }
        }
    }

    // This field is only used to log current active container.
    private val activeContainer: ViewGroup?
        get() = if (useSplitShade) splitShadeContainer else singlePaneContainer
}
