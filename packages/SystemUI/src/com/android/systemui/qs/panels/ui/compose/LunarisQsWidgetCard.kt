/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.ui.compose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.UserHandle
import android.widget.RemoteViews
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.systemui.qs.panels.shared.model.AxQsControl
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.AxQsSpan
import com.android.systemui.qs.panels.shared.model.LunarisQsWidget
import com.android.systemui.qs.panels.shared.model.LunarisQsWidgetKey
import com.android.systemui.qs.panels.widgets.LunarisQsWidgetHostView
import com.android.systemui.res.R
import kotlinx.coroutines.delay

@Composable
fun LunarisQsWidgetCard(
    widget: LunarisQsWidget = LunarisQsWidget(),
    layout: AxQsLayout = AxQsLayout.QS,
    control: AxQsControl = AxQsControl.APP_WIDGET,
    playing: Boolean = false,
    visible: Boolean = false,
    interactionHandler: RemoteViews.InteractionHandler =
        RemoteViews.InteractionHandler { _, _, _ -> true },
    onChoose: ((Float, Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by
        remember(lifecycle) {
            mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
    var screenOn by remember {
        mutableStateOf(context.getSystemService(PowerManager::class.java).isInteractive)
    }
    var inViewport by remember { mutableStateOf(false) }
    var unlockVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    screenOn = context.getSystemService(PowerManager::class.java).isInteractive
                    if (intent.action == Intent.ACTION_USER_UNLOCKED) unlockVersion++
                }
            }
        lifecycle.addObserver(observer)
        context.registerReceiverAsUser(
            receiver,
            UserHandle.ALL,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_UNLOCKED)
            },
            null,
            null,
            Context.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    val shown = visible && started && screenOn && inViewport
    val active = shown && playing
    val backgroundColor = AxTileDefaults.backgroundColor()
    val onLightBackground =
        backgroundColor.compositeOver(MaterialTheme.colorScheme.surface).luminance() > .5f
    val shape = axQsControlShape(control, AxQsSpan(2, 2))
    BoxWithConstraints(
        modifier
            .onGloballyPositioned { inViewport = !it.boundsInWindow().isEmpty }
            .clip(shape)
            .background(backgroundColor)
            .lunarisQsCosTileBorder(shape),
        contentAlignment = Alignment.Center,
    ) {
        val width = maxWidth.value
        val height = maxHeight.value
        key(widget, layout, control, unlockVersion, onLightBackground) {
            var keepHost by remember { mutableStateOf(false) }
            var unavailable by remember { mutableStateOf(false) }
            LaunchedEffect(active, shown) {
                if (active) keepHost = true
                else if (!shown) {
                    delay(5_000)
                    keepHost = false
                }
            }
            if (widget.isConfigured && keepHost && !unavailable) {
                AndroidView(
                    factory = {
                        LunarisQsWidgetHostView(
                            it,
                            widget,
                            LunarisQsWidgetKey(layout, control),
                            interactionHandler,
                            onLightBackground,
                        ) {
                            unavailable = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onReset = null,
                    onRelease = { it.dispose() },
                    update = { it.updateActive(active) },
                )
            } else {
                Column(
                    Modifier.fillMaxSize()
                        .then(
                            if (onChoose != null)
                                Modifier.clickable(
                                    enabled = active,
                                    role = Role.Button,
                                    onClickLabel = stringResource(R.string.lunaris_qs_widget_choose),
                                ) {
                                    onChoose(width, height)
                                }
                            else Modifier
                        )
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Icon(
                        Icons.Outlined.Widgets,
                        contentDescription = stringResource(R.string.lunaris_qs_widget_title),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    if (height >= 80f) {
                        Text(
                            stringResource(
                                if (unavailable) R.string.lunaris_qs_widget_unavailable
                                else if (widget.isConfigured) R.string.lunaris_qs_widget_title
                                else R.string.lunaris_qs_widget_choose
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
