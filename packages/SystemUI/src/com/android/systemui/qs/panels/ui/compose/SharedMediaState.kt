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
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

internal data class SharedMediaState(
    val title: String? = null,
    val artist: String? = null,
    val albumArt: Bitmap? = null,
    val isPlaying: Boolean = false,
    val controller: MediaController? = null,
    val packageName: String? = null,
)

@Composable
internal fun rememberMediaState(): SharedMediaState {
    val context = LocalContext.current
    val sessionManager = remember {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }

    var mediaState by remember { mutableStateOf(SharedMediaState()) }

    DisposableEffect(Unit) {
        var controllerCallback: MediaController.Callback? = null
        var currentController: MediaController? = null

        val logic = object {

            fun attachCallback(ctrl: MediaController?) {
                if (currentController == ctrl) return
                controllerCallback?.let { cb -> currentController?.unregisterCallback(cb) }
                currentController = ctrl
                if (ctrl == null) return
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(m: MediaMetadata?) { refreshActiveSessions() }
                    override fun onPlaybackStateChanged(s: PlaybackState?) { refreshActiveSessions() }
                    override fun onSessionDestroyed() { refreshActiveSessions() }
                }
                ctrl.registerCallback(cb)
                controllerCallback = cb
            }

            fun refreshActiveSessions() {
                val sessions = try {
                    sessionManager?.getActiveSessions(null) ?: emptyList()
                } catch (_: Exception) { emptyList() }

                val active = sessions.firstOrNull { c ->
                    c.playbackState?.state == PlaybackState.STATE_PLAYING ||
                        c.playbackState?.state == PlaybackState.STATE_PAUSED ||
                        c.playbackState?.state == PlaybackState.STATE_FAST_FORWARDING ||
                        c.playbackState?.state == PlaybackState.STATE_REWINDING ||
                        c.playbackState?.state == PlaybackState.STATE_BUFFERING
                } ?: sessions.firstOrNull()

                attachCallback(active)

                if (active == null) {
                    mediaState = SharedMediaState()
                    return
                }
                val meta = active.metadata
                mediaState = SharedMediaState(
                    title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
                    artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    albumArt = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART),
                    isPlaying = active.playbackState?.state == PlaybackState.STATE_PLAYING,
                    controller = active,
                    packageName = active.packageName,
                )
            }
        }

        logic.refreshActiveSessions()

        val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener {
            logic.refreshActiveSessions()
        }
        try { sessionManager?.addOnActiveSessionsChangedListener(sessionListener, null) }
        catch (_: Exception) {}

        onDispose {
            try { sessionManager?.removeOnActiveSessionsChangedListener(sessionListener) }
            catch (_: Exception) {}
            controllerCallback?.let { cb -> currentController?.unregisterCallback(cb) }
        }
    }

    return mediaState
}
