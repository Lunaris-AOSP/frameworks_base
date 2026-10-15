package com.android.packageinstaller.v2.ui.compose

import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallerBottomSheet(
    cancelable: Boolean,
    actionsEnabled: Boolean,
    dismissRequested: Boolean,
    onDismissRequest: () -> Unit,
    onDismissed: () -> Unit,
    content: @Composable () -> Unit,
) {
    val canDismiss = cancelable && actionsEnabled && !dismissRequested
    val currentCanDismiss by rememberUpdatedState(canDismiss)
    val currentDismissRequested by rememberUpdatedState(dismissRequested)
    val currentOnDismissed by rememberUpdatedState(onDismissed)
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            value != SheetValue.Hidden || currentCanDismiss || currentDismissRequested
        },
    )

    ModalBottomSheet(
        onDismissRequest = {
            if (currentDismissRequested) {
                // A back animation can be cancelled by our programmatic hide. Only finish
                // once the sheet has actually settled off screen.
                if (!sheetState.isVisible && !sheetState.isAnimationRunning) {
                    currentOnDismissed()
                }
            } else if (currentCanDismiss) {
                onDismissRequest()
            } else {
                // A new, non-cancelable install stage may have arrived during a user swipe.
                scope.launch {
                    if (!currentDismissRequested) sheetState.show()
                }
            }
        },
        modifier = Modifier.padding(top = 24.dp),
        sheetState = sheetState,
        sheetMaxWidth = 640.dp,
        sheetGesturesEnabled = canDismiss,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
        contentWindowInsets = { WindowInsets.safeDrawing },
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = canDismiss,
        ),
    ) {
        SecureInstallerSheetWindow()
        Box(Modifier.weight(1f, fill = false)) {
            content()
        }
    }

    LaunchedEffect(sheetState, dismissRequested) {
        if (dismissRequested) {
            // ModalBottomSheet starts showing after its first measurement. Wait until that
            // animation has started so a fast result cannot hide and then reopen the sheet.
            snapshotFlow {
                sheetState.targetValue != SheetValue.Hidden || sheetState.isVisible
            }.first { it }
            sheetState.hide()
            if (!sheetState.isVisible) currentOnDismissed()
        }
    }
}

@Composable
private fun SecureInstallerSheetWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val originalCallback = window?.callback
        if (window != null && originalCallback != null) {
            window.addSystemFlags(
                WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS
            )
            val callback = object : Window.Callback by originalCallback {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    val obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED or
                        MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
                    return event.flags and obscured == 0 &&
                        originalCallback.dispatchTouchEvent(event)
                }
            }
            window.callback = callback
            onDispose {
                if (window.callback === callback) window.callback = originalCallback
            }
        } else {
            onDispose {}
        }
    }
}
