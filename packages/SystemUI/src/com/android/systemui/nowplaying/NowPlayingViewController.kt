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
package com.android.systemui.nowplaying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.palette.graphics.Palette
import com.android.settingslib.Utils
import com.android.systemui.axdynamicbar.ui.AxDynamicBarKeyguardExpansion
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.MediaSessionManager as SystemUIMediaSessionManager
import com.android.systemui.res.R
import com.android.systemui.util.ScrimUtils
import com.android.systemui.statusbar.phone.LyricsFetcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@SysUISingleton
class NowPlayingViewController
@Inject
constructor(
    private val context: Context,
    private val keyguardExpansion: AxDynamicBarKeyguardExpansion,
) : ScrimUtils.ScrimEventListener {

    private val nowPlayingView = NowPlayingView(context)
        .apply { id = R.id.now_playing_view }
    private val settingsRepo = NowPlayingSettingsRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var currentSettings = settingsRepo.currentSettings()
    private var settingsJob: Job? = null

    private val mediaSessionManager = context.getSystemService(MediaSessionManager::class.java)!!
    private var activeController: MediaController? = null
    private var bouncerShowingOrKeyguardDismissing = false
    private var currentTrackTitle: String = ""
    private var currentArtist: String = ""
    private var currentPackageName: String = ""
    private var isPlaying: Boolean = false
    private var isScreenOff: Boolean = false

    private var isKeyguardShowing: Boolean = false
    private var isDozing: Boolean = false

    private var showDelayJob: Job? = null
    private var hideDelayJob: Job? = null

    private var controllerResyncJob: Job? = null

    private var lyricsCallbackRegistered = false

    private val lyricsFetcher: LyricsFetcher by lazy { LyricsFetcher.getInstance(context) }

    private val lyricsCallback = object : LyricsFetcher.Callback {
        override fun onSyncedLineChanged(prevLine: String?, currentLine: String?, nextLine: String?) {
            nowPlayingView.prevLyric = prevLine ?: ""
            nowPlayingView.currentLyric = currentLine ?: ""
            nowPlayingView.nextLyric = nextLine ?: ""
        }

        override fun onPlainLyricsAvailable(plainLyrics: String) {
            nowPlayingView.prevLyric = ""
            nowPlayingView.currentLyric = plainLyrics
            nowPlayingView.nextLyric = ""
        }

        override fun onLyricsCleared() {
            nowPlayingView.prevLyric = ""
            nowPlayingView.currentLyric = ""
            nowPlayingView.nextLyric = ""
        }
    }

    private companion object {
        private const val TAG = "NowPlayingViewController"
        private const val SHOW_DELAY_MS = 300L
        private const val HIDE_DELAY_MS = 150L
        private const val CONTROLLER_RESYNC_INTERVAL_MS = 3000L

        @Volatile
        private var INSTANCE: NowPlayingViewController? = null

        @JvmStatic
        fun get(context: Context): NowPlayingViewController {
            return INSTANCE ?: throw IllegalStateException(
                "NowPlayingViewController not initialized"
            )
        }
    }

    private var currentAlbumArtColor: Int? = null
    private var sharedMediaColor: Int? = null
    private var albumArtColorJob: Job? = null

    private val mediaColorListener = object : SystemUIMediaSessionManager.MediaDataListener {
        override fun onMediaColorsChanged(color: Int) {
            scope.launch {
                sharedMediaColor = color
                updateAlbumArtColor()
            }
        }
    }

    private val expandedOverlay: NowPlayingExpandedOverlay by lazy {
        NowPlayingExpandedOverlay(
            context = context,
            windowManager = context.getSystemService(WindowManager::class.java)!!,
            mediaSessionManager = mediaSessionManager,
        )
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updatePlaybackState(state)
        }

        override fun onSessionDestroyed() {
            refreshActiveController()
        }
    }

    private fun refreshActiveController() {
        try {
            updateActiveController(mediaSessionManager.getActiveSessions(null))
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing active controller", e)
        }
    }

    private fun startControllerResync() {
        if (controllerResyncJob?.isActive == true) return
        controllerResyncJob = scope.launch {
            while (true) {
                delay(CONTROLLER_RESYNC_INTERVAL_MS)
                refreshActiveController()
            }
        }
    }

    private fun stopControllerResync() {
        controllerResyncJob?.cancel()
        controllerResyncJob = null
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveController(controllers)
    }

    init {
        INSTANCE = this

        try {
            ScrimUtils.get()?.addListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding ScrimUtils listener", e)
        }
        
        scope.launch {
            keyguardExpansion.isExpanded.collect { expanded ->
                if (expanded) {
                    nowPlayingView.suppressed = true
                    forceHide()
                }
            }
        }
        scope.launch {
            keyguardExpansion.collapseSettled.collect {
                if (!keyguardExpansion.isExpanded.value) {
                    nowPlayingView.suppressed = false
                    updateState()
                }
            }
        }

        observeSettings()
        
        startMediaMonitoring()
        SystemUIMediaSessionManager.get().addListener(mediaColorListener)

        nowPlayingView.isClickable = true
        nowPlayingView.isFocusable = true
        nowPlayingView.setOnClickListener {
            if (NowPlayingOverlayState.isOverlayOpen.value) {
                expandedOverlay.hide()
            } else if (currentSettings.tapToExpand) {
                expandedOverlay.show()
            }
        }
    }

    fun getNowPlayingView(): FrameLayout = nowPlayingView

    private fun observeSettings() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            settingsRepo.settingsFlow
                .catch { e -> 
                    Log.e(TAG, "Error observing settings", e)
                }
                .collect { settings ->
                    currentSettings = settings
                    updateViewWithSettings(settings)
                }
        }
    }

    private fun updateViewWithSettings(settings: NowPlayingSettings) {
        val textColor = resolveTextColor(settings.colorMode)
        
        if (!settings.tapToExpand && NowPlayingOverlayState.isOverlayOpen.value) {
            expandedOverlay.hide()
        }

        setLyricsFetcherEnabled(settings.useLyricsMode)

        if (settings.isEnabled) {
            startControllerResync()
        } else {
            stopControllerResync()
        }

        nowPlayingView.apply {
            this.textColor = textColor
            iconStyle = settings.iconStyle
            iconSizeDp = settings.iconSize
            useCompactStyle = settings.useCompactStyle
            verticalPosition = settings.verticalPosition
            lyricsMode = settings.useLyricsMode
            updateTextSize(settings.trackTextSize, settings.artistTextSize)
            NowPlayingOverlayState.update {
                copy(useWaveformSeekBar = settings.useWaveformSeekBar)
            }
        }
        
        updateState()
    }

    private fun setLyricsFetcherEnabled(enabled: Boolean) {
        if (enabled && !lyricsCallbackRegistered) {
            lyricsCallbackRegistered = true
            lyricsFetcher.addCallback(lyricsCallback)
        } else if (!enabled && lyricsCallbackRegistered) {
            lyricsCallbackRegistered = false
            lyricsFetcher.removeCallback(lyricsCallback)
            nowPlayingView.prevLyric = ""
            nowPlayingView.currentLyric = ""
            nowPlayingView.nextLyric = ""
        }
    }

    private fun startMediaMonitoring() {
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                null
            )
            
            val controllers = mediaSessionManager.getActiveSessions(null)
            updateActiveController(controllers)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting media monitoring", e)
        }
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        val newController = controllers?.firstOrNull()
        val isSameController = activeController != null && newController != null
                && activeController?.packageName == newController.packageName
                && activeController?.sessionToken == newController.sessionToken
        val bothNull = activeController == null && newController == null

        if (isSameController || bothNull) {
            return
        }

        activeController?.unregisterCallback(mediaCallback)

        activeController = newController
        activeController?.registerCallback(mediaCallback)
        
        currentPackageName = activeController?.packageName ?: ""
        nowPlayingView.appPackageName = currentPackageName
        
        updateMetadata(activeController?.metadata)
        updatePlaybackState(activeController?.playbackState)
    }

    private fun updateMetadata(metadata: MediaMetadata?) {
        currentTrackTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        currentArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        
        nowPlayingView.trackTitle = currentTrackTitle
        nowPlayingView.artistName = currentArtist

        val albumArt: Bitmap? = run {
            val meta = metadata ?: return@run null
            meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }

        albumArtColorJob?.cancel()
        albumArtColorJob = null
        currentAlbumArtColor = null
        // The shared pipeline also resolves artwork from notifications and URIs, which may not
        // be present as a bitmap in this session's metadata.
        if (activeController == null) sharedMediaColor = null
        updateAlbumArtColor()
        if (albumArt != null) {
            albumArtColorJob = scope.launch {
                val extracted = withContext(Dispatchers.Default) { extractAlbumArtColor(albumArt) }
                currentAlbumArtColor = extracted
                updateAlbumArtColor()
            }
        }

        NowPlayingOverlayState.update {
            copy(
                track = currentTrackTitle,
                artist = currentArtist,
                packageName = currentPackageName,
                albumArt = albumArt,
                useWaveformSeekBar = currentSettings.useWaveformSeekBar,
            )
        }
        
        updateState()
    }

    private fun resolveTextColor(colorMode: Int): Int =
        when (colorMode) {
            NowPlayingSettingsRepository.COLOR_MODE_ACCENT ->
                Utils.getColorAccentDefaultColor(context)
            NowPlayingSettingsRepository.COLOR_MODE_ALBUM ->
                (currentAlbumArtColor ?: sharedMediaColor)?.let { boostSaturation(it) }
                    ?: 0xFFFFFFFF.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

    private fun updateAlbumArtColor() {
        val color = currentAlbumArtColor ?: sharedMediaColor
        NowPlayingOverlayState.update { copy(albumArtColor = color) }
        nowPlayingView.textColor = resolveTextColor(currentSettings.colorMode)
    }

    private fun extractAlbumArtColor(bitmap: Bitmap): Int? {
        if (bitmap.isRecycled) return null
        val palette = Palette.from(bitmap).generate()
        val swatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.dominantSwatch
            ?: return null
        return swatch.rgb
    }

    private fun boostSaturation(color: Int, amount: Float = 0.20f): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] + amount).coerceAtMost(1.0f)
        return Color.HSVToColor(hsv)
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val duration = activeController?.metadata
            ?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val pos = state?.position?.coerceAtLeast(0L) ?: 0L
        val progress = if (duration > 0L) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f
        NowPlayingOverlayState.update {
            copy(
                isPlaying = this@NowPlayingViewController.isPlaying,
                duration = duration,
                position = pos,
                progress = progress,
                playbackSpeed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
                positionUpdateTime = state?.lastPositionUpdateTime ?: 0L,
                packageName = currentPackageName,
            )
        }
        updateState()
    }

    private fun shouldShowNowPlaying(panelCollapsed: Boolean): Boolean =
        !nowPlayingView.suppressed
            && currentSettings.isEnabled
            && isPlaying
            && currentTrackTitle.isNotEmpty()
            && !bouncerShowingOrKeyguardDismissing
            && panelCollapsed
            && !isScreenOff
            && ((isKeyguardShowing && !isDozing && currentSettings.showOnLockscreen)
                || (isDozing && currentSettings.showOnAod))

    private fun updateState() {
        if (nowPlayingView.suppressed) {
            cancelDebounceJobs()
            return
        }
        if (!currentSettings.isEnabled) {
            cancelDebounceJobs()
            nowPlayingView.hide()
            return
        }

        val isPanelCollapsed = try {
            ScrimUtils.get()?.isPanelFullyCollapsed() ?: true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking panel state", e)
            true
        }

        val shouldShow = shouldShowNowPlaying(isPanelCollapsed)

        if (shouldShow) {
            hideDelayJob?.cancel()
            hideDelayJob = null
            if (nowPlayingView.visible) return
            showDelayJob?.cancel()
            showDelayJob = scope.launch {
                delay(SHOW_DELAY_MS)
                val stillCollapsed = try {
                    ScrimUtils.get()?.isPanelFullyCollapsed() ?: true
                } catch (e: Exception) { true }
                if (shouldShowNowPlaying(stillCollapsed)) {
                    nowPlayingView.show()
                }
                showDelayJob = null
            }
        } else {
            showDelayJob?.cancel()
            showDelayJob = null
            if (!nowPlayingView.visible) return
            hideDelayJob?.cancel()
            hideDelayJob = scope.launch {
                delay(HIDE_DELAY_MS)
                val stillCollapsed = try {
                    ScrimUtils.get()?.isPanelFullyCollapsed() ?: true
                } catch (e: Exception) { true }
                if (!shouldShowNowPlaying(stillCollapsed)) {
                    nowPlayingView.hide()
                }
                hideDelayJob = null
            }
        }
    }

    private fun forceHide() {
        cancelDebounceJobs()
        nowPlayingView.hide()
        expandedOverlay.hide()
    }

    private fun cancelDebounceJobs() {
        showDelayJob?.cancel()
        showDelayJob = null
        hideDelayJob?.cancel()
        hideDelayJob = null
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        isKeyguardShowing = showing
        if (!showing) nowPlayingView.suppressed = false
        updateState()
    }

    override fun onPrimaryBouncerShowingChanged(showing: Boolean) {
        bouncerShowingOrKeyguardDismissing = showing
        if (showing) {
            forceHide()
        } else {
            updateState()
        }
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = goingAway
        if (goingAway) {
            forceHide()
        } else {
            updateState()
        }
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        bouncerShowingOrKeyguardDismissing = fadingAway
        if (fadingAway) {
            forceHide()
        } else {
            updateState()
        }
    }

    override fun onDozingChanged(dozing: Boolean) {
        isDozing = dozing
        updateState()
    }

    override fun setPulsing(pulsing: Boolean) {
        if (pulsing && currentSettings.showOnAod) {
            updateState()
        }
    }

    override fun onExpandedFractionChanged(expandedFraction: Float) {
        updateState()
    }

    override fun onQsVisibilityChanged(visible: Boolean) {
        updateState()
    }

    override fun onBarStateChanged(state: Int) {
        updateState()
    }

    override fun onScreenTurnedOff() {
        isScreenOff = true
        forceHide()
    }

    override fun onStartedWakingUp() {
        isScreenOff = false
        refreshActiveController()
        updateState()
    }

    fun cleanup() {
        SystemUIMediaSessionManager.get().removeListener(mediaColorListener)
        activeController?.unregisterCallback(mediaCallback)
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            ScrimUtils.get()?.removeListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
        cancelDebounceJobs()
        expandedOverlay.hide()
        settingsJob?.cancel()
        scope.cancel()
    }
}

