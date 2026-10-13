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

package com.android.systemui.screencapture.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.view.Display
import android.view.SurfaceControl
import android.view.ViewRootImpl
import android.view.Window
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.WindowOnBackInvokedDispatcher
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.android.compose.theme.PlatformTheme
import com.android.systemui.compose.ComposeInitializer
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.screencapture.common.ScreenCaptureUiComponent
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureType
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureUiParameters
import com.android.systemui.screencapture.common.shared.model.ScreenCaptureUiState
import com.android.systemui.screencapture.ui.viewmodel.ScreenCaptureUiViewModel
import com.android.systemui.screenshot.ScreenshotWindow
import com.android.systemui.settings.UserContextProvider
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation

private val scaleTransformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 0f)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@SuppressLint("NonInjectedService")
class ScreenCaptureUi
@AssistedInject
constructor(
    @Assisted private val display: Display,
    @Assisted private val type: ScreenCaptureType,
    private val viewModelFactory: ScreenCaptureUiViewModel.Factory,
    @Application context: Context,
    userContextProvider: UserContextProvider,
    private val componentBuilders:
        Map<
            @JvmSuppressWildcards
            ScreenCaptureType,
            @JvmSuppressWildcards
            ScreenCaptureUiComponent.Builder,
        >,
    private val defaultBuilder: Lazy<ScreenCaptureUiComponent.Builder>,
) :
    ScreenshotWindow(
        display = display,
        context = userContextProvider.createCurrentUserContext(context),
        shouldConsumeInsets = false,
    ) {

    private val composeContext =
        object : ContextWrapper(getContext()) {
            override fun getApplicationContext(): Context = context
        }

    private var composeRoot: ComposeView? = null
    var onRemoved: (() -> Unit)? = null

    private val surfaceCallback =
        object : ViewRootImpl.SurfaceChangedCallback {
            override fun surfaceCreated(transaction: SurfaceControl.Transaction) {
                excludeRecordingToolbar(transaction)
            }

            override fun surfaceReplaced(transaction: SurfaceControl.Transaction) {
                excludeRecordingToolbar(transaction)
            }

            override fun surfaceDestroyed() {}
        }

    private fun excludeRecordingToolbar(transaction: SurfaceControl.Transaction) {
        val surface = window.decorView.viewRootImpl?.surfaceControl ?: return
        if (surface.isValid) transaction.setSkipScreenshot(surface, true)
    }

    init {
        with(window) {
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
        }
    }

    override fun onAttach() {
        require(composeRoot == null) { "The ui is already attached" }
        setFocusable(true)
        if (type == ScreenCaptureType.RECORD) {
            window.decorView.viewRootImpl?.addSurfaceChangedCallback(surfaceCallback)
            SurfaceControl.Transaction().use {
                excludeRecordingToolbar(it)
                it.apply()
            }
        }

        composeRoot =
            ComposeView(composeContext).also { composeView ->
                ComposeInitializer.onAttachedToWindow(composeView)
                setContentView(composeView)
                composeView.setContent {
                    val viewModel =
                        rememberViewModel("ScreenCaptureUi#viewModel") {
                            viewModelFactory.create(type)
                        }
                    var parametersState: ScreenCaptureUiParameters? by remember {
                        mutableStateOf(null)
                    }
                    LaunchedEffect(viewModel.state) {
                        (viewModel.state as? ScreenCaptureUiState.Visible)?.parameters?.let {
                            parametersState = it
                        }
                    }
                    // Wait until parameters are passed down to Compose
                    val parameters = parametersState ?: return@setContent

                    LaunchedEffect(viewModel) { window.observeBack { viewModel.dismiss() } }

                    // Focus the view on initial render so it can receive key events.
                    LaunchedEffect(Unit) { window.decorView.requestFocus() }

                    PlatformTheme {
                        val visibleState = remember { MutableTransitionState(false) }
                        visibleState.targetState = viewModel.state is ScreenCaptureUiState.Visible
                        if (!visibleState.targetState && visibleState.isIdle) {
                            SideEffect { removeWindow() }
                        }
                        val toolbarOffset = with(LocalDensity.current) { 80.dp.roundToPx() }
                        AnimatedVisibility(
                            visibleState = visibleState,
                            enter =
                                if (type == ScreenCaptureType.RECORD) {
                                    fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
                                        slideInVertically(
                                            MaterialTheme.motionScheme.fastSpatialSpec()
                                        ) {
                                            -toolbarOffset
                                        }
                                } else {
                                    scaleIn(transformOrigin = scaleTransformOrigin) +
                                        slideInVertically()
                                },
                            exit =
                                if (type == ScreenCaptureType.RECORD) {
                                    fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                                        slideOutVertically(
                                            MaterialTheme.motionScheme.fastSpatialSpec()
                                        ) {
                                            -toolbarOffset
                                        }
                                } else {
                                    scaleOut(transformOrigin = scaleTransformOrigin) +
                                        slideOutVertically()
                                },
                        ) {
                            val builder: ScreenCaptureUiComponent.Builder =
                                componentBuilders[parameters.screenCaptureType]
                                    ?: defaultBuilder.get()
                            val coroutineScope = rememberCoroutineScope()
                            val component =
                                remember(parameters, coroutineScope) {
                                    builder
                                        .setScope(coroutineScope)
                                        .setDisplay(display)
                                        .setWindow(window)
                                        .build()
                                }

                            Box(
                                modifier =
                                    (if (type == ScreenCaptureType.RECORD) Modifier
                                        else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
                                        .focusable()
                                        .onKeyEvent { event -> handleKeyEvent(event, viewModel) }
                            ) {
                                component.screenCaptureContent.Content()
                            }
                        }
                    }
                }
            }
    }

    override fun onDetach() {
        window.decorView.viewRootImpl?.removeSurfaceChangedCallback(surfaceCallback)
        val root = composeRoot ?: return
        ComposeInitializer.onDetachedFromWindow(root)
        composeRoot = null
        onRemoved?.invoke()
        onRemoved = null
    }

    private fun handleKeyEvent(event: KeyEvent, viewModel: ScreenCaptureUiViewModel): Boolean {
        if (event.type != KeyEventType.KeyUp) {
            return false
        }

        val noModifierKeys =
            !event.isShiftPressed &&
                !event.isCtrlPressed &&
                !event.isAltPressed &&
                !event.isMetaPressed

        return when {
            (event.key == Key.Escape && noModifierKeys) -> {
                viewModel.dismiss()
                true
            }
            else -> false
        }
    }

    @AssistedFactory
    interface Factory {

        fun create(display: Display, type: ScreenCaptureType): ScreenCaptureUi
    }
}

private suspend fun Window.observeBack(onBack: OnBackInvokedCallback) {
    if (!WindowOnBackInvokedDispatcher.isOnBackInvokedCallbackEnabled(context)) {
        return
    }
    onBackInvokedDispatcher.registerSystemOnBackInvokedCallback(onBack)
    try {
        awaitCancellation()
    } finally {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(onBack)
    }
}
