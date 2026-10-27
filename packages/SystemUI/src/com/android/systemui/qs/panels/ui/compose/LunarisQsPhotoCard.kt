/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.ui.compose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.systemui.qs.panels.data.repository.LunarisQsPhotoStorage
import com.android.systemui.qs.panels.shared.model.LunarisQsPhotoFilter
import com.android.systemui.qs.panels.shared.model.LunarisQsPhotoFrame
import com.android.systemui.res.R
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun LunarisQsPhotoCard(
    photo: LunarisQsPhotoFrame = LunarisQsPhotoFrame(),
    playing: Boolean = false,
    modifier: Modifier = Modifier,
    onPositionChanged: ((Float, Float) -> Unit)? = null,
    visible: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var inViewport by remember { mutableStateOf(false) }
    var started by
        remember(lifecycle) {
            mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val shape = RoundedCornerShape(photo.cornerDp.dp)
    val description = stringResource(R.string.lunaris_qs_photo_title)
    Box(
        modifier
            .onGloballyPositioned { inViewport = !it.boundsInWindow().isEmpty }
            .semantics { contentDescription = description }
            .clip(shape)
            .background(AxTileDefaults.backgroundColor())
            .lunarisQsCosTileBorder(shape)
            .padding(photo.borderDp.dp)
    ) {
        key(photo.userId, photo.fileName) {
            var unlockVersion by remember { mutableIntStateOf(0) }
            DisposableEffect(context, photo.userId, photo.fileName) {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            unlockVersion++
                        }
                    }
                val registered = photo.userId >= 0 && photo.fileName != null
                if (registered) {
                    context.registerReceiverAsUser(
                        receiver,
                        UserHandle.of(photo.userId),
                        IntentFilter(Intent.ACTION_USER_UNLOCKED),
                        null,
                        null,
                        Context.RECEIVER_NOT_EXPORTED,
                    )
                }
                onDispose { if (registered) context.unregisterReceiver(receiver) }
            }
            val active = started && visible && inViewport
            val preview by
                produceState<Drawable?>(null, active, unlockVersion) {
                    if (active && value == null) value = LunarisQsPhotoStorage.load(context, photo)
                    else if (!active) {
                        delay(5_000)
                        value = null
                    }
                }
            val animation by
                produceState<Drawable?>(null, active, playing, unlockVersion) {
                    if (
                        active &&
                            playing &&
                            value == null &&
                            photo.fileName?.endsWith(".anim") == true
                    ) {
                        delay(150)
                        value = LunarisQsPhotoStorage.load(context, photo, animate = true)
                    } else if (!active) {
                        delay(5_000)
                        value = null
                    }
                }
            val drawable = animation ?: preview
            val currentPhoto by rememberUpdatedState(photo)
            val updatePosition by rememberUpdatedState(onPositionChanged)
            val innerShape =
                RoundedCornerShape((photo.cornerDp - photo.borderDp).coerceAtLeast(0).dp)
            val positionModifier =
                if (onPositionChanged == null) Modifier
                else {
                    Modifier.pointerInput(drawable) {
                        detectDragGestures { change, drag ->
                            val image = drawable ?: return@detectDragGestures
                            if (image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0)
                                return@detectDragGestures
                            change.consume()
                            val config = currentPhoto
                            val scale =
                                maxOf(
                                    size.width.toFloat() / image.intrinsicWidth,
                                    size.height.toFloat() / image.intrinsicHeight,
                                ) * config.zoom
                            val overflowX = image.intrinsicWidth * scale - size.width
                            val overflowY = image.intrinsicHeight * scale - size.height
                            updatePosition?.invoke(
                                if (overflowX > 0)
                                    (config.positionX - drag.x / overflowX).coerceIn(0f, 1f)
                                else config.positionX,
                                if (overflowY > 0)
                                    (config.positionY - drag.y / overflowY).coerceIn(0f, 1f)
                                else config.positionY,
                            )
                        }
                    }
                }
            Box(
                Modifier.fillMaxSize()
                    .clip(innerShape)
                    .then(positionModifier),
                contentAlignment = Alignment.Center,
            ) {
                if (drawable == null) {
                    Icon(
                        Icons.Outlined.Photo,
                        contentDescription =
                            stringResource(
                                if (photo.fileName == null) R.string.lunaris_qs_photo_choose
                                else R.string.lunaris_qs_photo_unavailable
                            ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AndroidView(
                        factory = { PhotoView(it) },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { it.release() },
                        update = { it.bind(drawable, photo, playing && active) },
                    )
                }
            }
        }
    }
}

private class PhotoView(context: Context) : View(context) {
    private var image: Drawable? = null
    private var photo = LunarisQsPhotoFrame()
    private var playing = false
    private var aggregatedVisible = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(drawable: Drawable?, config: LunarisQsPhotoFrame, animate: Boolean) {
        val changed = image !== drawable
        val configurationChanged = photo != config
        if (changed) {
            release()
            image = drawable
            drawable?.callback = this
        }
        if (changed || photo.filter != config.filter || photo.grayscale != config.grayscale) {
            drawable?.colorFilter = photoColorFilter(config)
        }
        photo = config
        playing = animate
        updatePlayback()
        if (changed || configurationChanged) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val drawable = image ?: return
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return
        val scale =
            maxOf(
                width.toFloat() / drawable.intrinsicWidth,
                height.toFloat() / drawable.intrinsicHeight,
            ) * photo.zoom
        val save = canvas.save()
        canvas.translate(
            (width - drawable.intrinsicWidth * scale) * photo.positionX,
            (height - drawable.intrinsicHeight * scale) * photo.positionY,
        )
        canvas.scale(scale, scale)
        drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
        drawable.draw(canvas)
        canvas.restoreToCount(save)
        if (photo.dim > 0f) canvas.drawColor(Color.argb((photo.dim * 255).roundToInt(), 0, 0, 0))
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === image || super.verifyDrawable(who)

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        updatePlayback()
    }

    override fun onDetachedFromWindow() {
        aggregatedVisible = false
        (image as? AnimatedImageDrawable)?.stop()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        updatePlayback()
    }

    private fun updatePlayback() {
        (image as? AnimatedImageDrawable)?.let {
            if (playing && aggregatedVisible && isAttachedToWindow && hasWindowFocus()) {
                if (!it.isRunning) it.start()
            } else if (it.isRunning) it.stop()
        }
    }

    fun release() {
        image?.let {
            (it as? AnimatedImageDrawable)?.stop()
            unscheduleDrawable(it)
            it.callback = null
        }
        image = null
    }
}

private fun photoColorFilter(photo: LunarisQsPhotoFrame): ColorMatrixColorFilter {
    val matrix = ColorMatrix()
    when (photo.filter) {
        LunarisQsPhotoFilter.ORIGINAL -> Unit
        LunarisQsPhotoFilter.GRAYSCALE -> matrix.setSaturation(0f)
        LunarisQsPhotoFilter.SEPIA ->
            matrix.set(
                floatArrayOf(
                    .393f,
                    .769f,
                    .189f,
                    0f,
                    0f,
                    .349f,
                    .686f,
                    .168f,
                    0f,
                    0f,
                    .272f,
                    .534f,
                    .131f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                )
            )
        LunarisQsPhotoFilter.WARM -> matrix.setScale(1.1f, 1f, .85f, 1f)
        LunarisQsPhotoFilter.COOL -> matrix.setScale(.85f, 1f, 1.1f, 1f)
    }
    matrix.postConcat(ColorMatrix().apply { setSaturation(1f - photo.grayscale) })
    return ColorMatrixColorFilter(matrix)
}
