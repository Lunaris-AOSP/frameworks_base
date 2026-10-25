/*
 * Copyright (C) 2026 The PenguinOS Project
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

package com.android.systemui.media.remedia.motionart

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay

private const val LUNARIS_MOTION_ART_FADE_MS = 400

@Composable
fun LunarisMotionArtVideo(
    file: File,
    modifier: Modifier = Modifier,
    crop: Boolean = false,
    isPlaying: Boolean = true,
    onRendering: () -> Unit = {},
) {
    key(file) {
        var rendering by remember { mutableStateOf(false) }
        val lifecycleOwner = LocalLifecycleOwner.current
        var started by
            remember(lifecycleOwner) {
                mutableStateOf(
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                )
            }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, _ ->
                started = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val alpha by
            animateFloatAsState(
                targetValue = if (rendering) 1f else 0f,
                animationSpec =
                    tween(durationMillis = LUNARIS_MOTION_ART_FADE_MS, easing = LinearEasing),
                label = "LunarisMotionArt",
            )
        AndroidView(
            factory = { context ->
                LunarisMotionArtTextureView(context).apply {
                    isOpaque = false
                    loopingPlayer =
                        LunarisMotionArtPlayer(this, file, crop) { hasFrame ->
                            rendering = hasFrame
                            if (hasFrame) onRendering()
                        }
                    surfaceTextureListener = loopingPlayer
                }
            },
            update = { it.loopingPlayer?.setPlaying(isPlaying && started) },
            onRelease = {
                it.loopingPlayer?.release()
                it.surfaceTextureListener = null
                it.loopingPlayer = null
            },
            modifier = modifier.graphicsLayer { this.alpha = alpha },
        )
    }
}

@Composable
fun FadingLunarisMotionArtVideo(
    file: File?,
    modifier: Modifier = Modifier,
    crop: Boolean = false,
    isPlaying: Boolean = true,
    onRendering: () -> Unit = {},
) {
    var shown by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(file) {
        if (file != null) {
            shown = file
        } else if (shown != null) {
            delay(LUNARIS_MOTION_ART_FADE_MS.toLong())
            shown = null
        }
    }
    val alpha by
        animateFloatAsState(
            targetValue = if (file != null) 1f else 0f,
            animationSpec =
                tween(durationMillis = LUNARIS_MOTION_ART_FADE_MS, easing = LinearEasing),
            label = "LunarisMotionArtLeave",
        )
    shown?.let { current ->
        LunarisMotionArtVideo(
            file = current,
            modifier = modifier.graphicsLayer { this.alpha = alpha },
            crop = crop,
            isPlaying = isPlaying,
            onRendering = onRendering,
        )
    }
}

private class LunarisMotionArtTextureView(context: Context) : TextureView(context) {
    var loopingPlayer: LunarisMotionArtPlayer? = null

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        loopingPlayer?.setVisible(isVisible)
    }
}

private class LunarisMotionArtPlayer(
    private val view: TextureView,
    private val file: File,
    private val crop: Boolean,
    private val onRendering: (Boolean) -> Unit,
) : TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var playWhenReady = false
    private var visible = true

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        release()
        val output = Surface(texture)
        surface = output
        try {
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            mediaPlayer.apply {
                setSurface(output)
                isLooping = true
                setVolume(0f, 0f)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onRendering(true)
                    false
                }
                setOnErrorListener { _, _, _ ->
                    this@LunarisMotionArtPlayer.release()
                    true
                }
                setOnPreparedListener {
                    prepared = true
                    updatePlayback()
                }
                if (crop) {
                    setOnVideoSizeChangedListener { _, width, height -> cropTo(width, height) }
                }
                setDataSource(file.path)
                prepareAsync()
            }
        } catch (e: IOException) {
            Log.w("LunarisMotionArt", "Cannot open motion art", e)
            release()
        } catch (e: RuntimeException) {
            Log.w("LunarisMotionArt", "Cannot prepare motion art", e)
            release()
        }
    }

    fun setPlaying(playing: Boolean) {
        playWhenReady = playing
        updatePlayback()
    }

    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        updatePlayback()
    }

    private fun updatePlayback() {
        val current = player ?: return
        if (!prepared) return
        try {
            val shouldPlay = playWhenReady && visible && view.isShown
            if (shouldPlay && !current.isPlaying) current.start()
            else if (!shouldPlay && current.isPlaying) current.pause()
        } catch (e: IllegalStateException) {
            Log.w("LunarisMotionArt", "Cannot update motion art playback", e)
            release()
        }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        val player = player ?: return
        if (crop) cropTo(player.videoWidth, player.videoHeight)
    }

    private fun cropTo(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0 || view.width <= 0 || view.height <= 0) return
        val viewWidth = view.width.toFloat()
        val viewHeight = view.height.toFloat()
        val scale = maxOf(viewWidth / videoWidth, viewHeight / videoHeight)
        view.setTransform(
            Matrix().apply {
                setScale(
                    videoWidth * scale / viewWidth,
                    videoHeight * scale / viewHeight,
                    viewWidth / 2f,
                    viewHeight / 2f,
                )
            }
        )
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}

    fun release() {
        prepared = false
        onRendering(false)
        player?.release()
        player = null
        surface?.release()
        surface = null
    }
}
