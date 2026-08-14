/*
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

package com.android.systemui.qs.panels.ui.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.Color as AndroidColor
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.media.MediaSessionManager as newMediaSessionManager

private fun boostSaturation(color: Int, amount: Float = 0.20f): Int {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color, hsv)
    hsv[1] = (hsv[1] + amount).coerceAtMost(1.0f)
    return AndroidColor.HSVToColor(hsv)
}

internal data class SharedMediaState(
    val title: String? = null,
    val artist: String? = null,
    val albumArt: Bitmap? = null,
    val isPlaying: Boolean = false,
    val controller: MediaController? = null,
    val packageName: String? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val positionAnchorMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val albumArtTint: Color? = null,
)

// Playback states considered "active" for session priority ordering.
private val ACTIVE_PLAYBACK_STATES = setOf(
    PlaybackState.STATE_PLAYING,
    PlaybackState.STATE_PAUSED,
    PlaybackState.STATE_FAST_FORWARDING,
    PlaybackState.STATE_REWINDING,
    PlaybackState.STATE_BUFFERING,
)

@Composable
internal fun rememberMediaState(): SharedMediaState {
    val context = LocalContext.current
    val sessionManager = remember {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }

    var mediaState by remember { mutableStateOf(SharedMediaState()) }

    DisposableEffect(Unit) {
        val newManager = newMediaSessionManager.get()
        val colorListener = object : newMediaSessionManager.MediaDataListener {
            override fun onMediaColorsChanged(color: Int) {
                val boosted = boostSaturation(color)
                Handler(Looper.getMainLooper()).post {
                    mediaState = mediaState.copy(albumArtTint = Color(boosted))
                }
            }
        }
        newManager.addListener(colorListener)
        onDispose { newManager.removeListener(colorListener) }
    }

    DisposableEffect(Unit) {
        var controllerCallback: MediaController.Callback? = null
        var currentController: MediaController? = null

        fun refreshActiveSessions() {
            val sessions: List<MediaController> = try {
                sessionManager?.getActiveSessions(null) ?: emptyList()
            } catch (_: SecurityException) {
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            // Prefer a session that is actively playing/buffering/etc; fall back to first available.
            val active = sessions.firstOrNull { it.playbackState?.state in ACTIVE_PLAYBACK_STATES }
                ?: sessions.firstOrNull()

            // Re-attach the metadata/state callback only when the active controller changes.
            if (currentController !== active) {
                controllerCallback?.let { cb -> currentController?.unregisterCallback(cb) }
                currentController = active

                if (active != null) {
                    val cb = object : MediaController.Callback() {
                        override fun onMetadataChanged(m: MediaMetadata?) = refreshActiveSessions()
                        override fun onPlaybackStateChanged(s: PlaybackState?) = refreshActiveSessions()
                        override fun onSessionDestroyed() = refreshActiveSessions()
                    }
                    active.registerCallback(cb)
                    controllerCallback = cb
                } else {
                    controllerCallback = null
                }
            }

            if (active == null) {
                mediaState = SharedMediaState()
                return
            }

            val meta = active.metadata
            val playbackState = active.playbackState?.state
            val stopped = playbackState == PlaybackState.STATE_STOPPED ||
                playbackState == PlaybackState.STATE_NONE
            mediaState = SharedMediaState(
                title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                albumArt = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                isPlaying = playbackState == PlaybackState.STATE_PLAYING,
                controller = active,
                packageName = active.packageName,
                durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L,
                positionMs = active.playbackState?.position?.coerceAtLeast(0L) ?: 0L,
                positionAnchorMs = SystemClock.elapsedRealtime(),
                playbackSpeed = active.playbackState?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                albumArtTint = if (stopped) null else mediaState.albumArtTint,
            )
        }

        refreshActiveSessions()

        val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener {
            refreshActiveSessions()
        }
        try {
            sessionManager?.addOnActiveSessionsChangedListener(sessionListener, null)
        } catch (_: Exception) {}

        onDispose {
            try {
                sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
            } catch (_: Exception) {}
            controllerCallback?.let { cb -> currentController?.unregisterCallback(cb) }
        }
    }

    return mediaState
}
