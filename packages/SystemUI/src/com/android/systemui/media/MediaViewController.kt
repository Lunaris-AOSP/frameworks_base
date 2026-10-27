/*
 * Copyright (C) 2025 The AxionAOSP Project
 * Copyright (C) 2025 Lunaris Project
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
package com.android.systemui.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.VisibleForTesting
import com.android.internal.graphics.ColorUtils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.media.MediaScrimState.STATE_SCRIM_HIDDEN
import com.android.systemui.media.MediaScrimState.STATE_SCRIM_VISIBLE
import com.android.systemui.media.remedia.motionart.LunarisMotionArt
import com.android.systemui.util.ScrimUtils

import java.io.File
import kotlinx.coroutines.*
import kotlin.math.*
import javax.inject.Inject

enum class MediaScrimState {
    STATE_SCRIM_VISIBLE,
    STATE_SCRIM_HIDDEN
}

@SysUISingleton
class MediaViewController @VisibleForTesting internal constructor(
    private val context: Context,
    private val backgroundDispatcher: CoroutineDispatcher,
    private val mediaSessionManager: MediaSessionManager,
    private val scrimUtils: ScrimUtils
) : MediaSessionManager.MediaDataListener, ScrimUtils.ScrimEventListener {

    @Inject
    constructor(
        context: Context,
        @Background backgroundDispatcher: CoroutineDispatcher
    ) : this(context, backgroundDispatcher, MediaSessionManager.get(), ScrimUtils.get(context))

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var scrimState = STATE_SCRIM_HIDDEN

    private var listening = false
    private var featureEnabled = false
    private var aodEnabled = false
    private var artworkDrawable: Drawable? = null
    private var isMediaPlaying = false
    private var bouncerShowing = false
    private var keyguardGoingAway = false
    private var keyguardFadingAway = false
    private var screenInteractive = context.getSystemService(PowerManager::class.java).isInteractive

    private var mediaFilter = 0
    private var mediaFadeLevel = 40
    private var mediaBlurLevel = 90
    private var pixelSize = 20
    private var aodDimLevel = 35

    private var expandedMusicOpen = false

    private val EXPANDED_MUSIC_BLUR_LEVEL = 120
    private val EXPANDED_MUSIC_FADE_LEVEL = 50
    private val EXPANDED_MUSIC_FILTER = 1

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            updateSettings()
        }
    }

    private val mediaBackground = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val framedAlbum = FramedMediaArtView(context).apply { visibility = View.GONE }

    private val mediaScrim = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = minOf(
                View.MeasureSpec.getSize(widthMeasureSpec) * 0.86f,
                View.MeasureSpec.getSize(heightMeasureSpec) * 0.44f,
                480f * resources.displayMetrics.density
            ).roundToInt().coerceAtLeast(1)
            framedAlbum.layoutParams.width = size
            framedAlbum.layoutParams.height = size
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            coroutineScope.launch {
                if (isAlbumArtVisible && shouldShowMediaArt()) updateMediaArt()
                updateMediaState()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            coroutineScope.launch { updateFramedAlbum() }
        }

        override fun onDetachedFromWindow() {
            stopMotionArt()
            super.onDetachedFromWindow()
        }
    }.apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(mediaBackground, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        addView(framedAlbum, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        visibility = View.GONE
    }

    private var mediaArtJob: Job? = null
    private var motionArtJob: Job? = null
    private var motionArtKey: Pair<String, String>? = null
    private var motionArtFile: File? = null
    private var motionArtResolved = false
    private var trackTitle = ""
    private var trackArtist = ""
    private var isAlbumArtVisible = false
        set(visible) {
            if (field == visible) return
            field = visible
            scrimUtils.setMediaArtVisible(visible)
        }
    private var dismissingKeyguard = false
    private var artworkRequestId = 0L
    
    private var showDelayJob: Job? = null
    private var hideDelayJob: Job? = null
    
    private val MEDIA_SHOW_DELAY_MS = 200L
    private val MEDIA_HIDE_DELAY_MS = 100L

    private val sharedTypedValue = TypedValue()
    private val grayscaleMatrix = ColorMatrix().apply { setSaturation(0f) }

    init {
        INSTANCE = this

        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.LS_MEDIA_ART_ENABLED),
            false,
            settingsObserver,
            UserHandle.USER_ALL
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.LS_MEDIA_ART_AOD_ENABLED),
            false,
            settingsObserver,
            UserHandle.USER_ALL
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.LS_MEDIA_ART_FILTER),
            false,
            settingsObserver,
            UserHandle.USER_ALL
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.LS_MEDIA_ART_FADE_LEVEL),
            false,
            settingsObserver,
            UserHandle.USER_ALL
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.LS_MEDIA_ART_BLUR_LEVEL),
            false,
            settingsObserver,
            UserHandle.USER_ALL
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.LS_MEDIA_ART_PIXEL_SIZE),
            false,
            settingsObserver,
            UserHandle.USER_ALL
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.LS_MEDIA_ART_AOD_DIM_LEVEL),
            false,
            settingsObserver,
            UserHandle.USER_ALL
        )

        // Keep user/keyguard state fresh even while the media-art setting is disabled.
        scrimUtils.addListener(this)
        updateSettings()
    }

    private fun updateSettings() {
        featureEnabled = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.LS_MEDIA_ART_ENABLED,
            0,
            UserHandle.USER_CURRENT
        ) == 1

        aodEnabled = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.LS_MEDIA_ART_AOD_ENABLED,
            0,
            UserHandle.USER_CURRENT
        ) == 1

        mediaFilter = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.LS_MEDIA_ART_FILTER,
            1,
            UserHandle.USER_CURRENT
        )

        mediaFadeLevel = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.LS_MEDIA_ART_FADE_LEVEL,
            40,
            UserHandle.USER_CURRENT
        ).coerceIn(0, 100)

        mediaBlurLevel = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.LS_MEDIA_ART_BLUR_LEVEL,
            90,
            UserHandle.USER_CURRENT
        ).coerceIn(0, 200)

        pixelSize = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.LS_MEDIA_ART_PIXEL_SIZE,
            20,
            UserHandle.USER_CURRENT
        ).coerceIn(5, 50)

        aodDimLevel = Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.LS_MEDIA_ART_AOD_DIM_LEVEL,
            35,
            UserHandle.USER_CURRENT
        ).coerceIn(0, 100)

        if (!featureEnabled && !expandedMusicOpen) {
            cleanupResources(false)
        }

        refreshListening()
        coroutineScope.launch {
            if (isAlbumArtVisible && shouldShowMediaArt()) updateMediaArt()
            updateMediaState()
        }
    }

    private fun refreshListening() {
        val shouldListen = featureEnabled || expandedMusicOpen
        if (shouldListen && !listening) {
            mediaSessionManager.addListener(this)
            listening = true
        } else if (!shouldListen && listening) {
            mediaSessionManager.removeListener(this)
            listening = false
        }
    }

    private fun isDozing(): Boolean = scrimUtils.isDozing()
    private fun isPulsing(): Boolean = scrimUtils.isPulsing()

    private val effectiveMediaFilter: Int
        get() = if (expandedMusicOpen) EXPANDED_MUSIC_FILTER else mediaFilter

    private val effectiveMediaBlurLevel: Int
        get() = if (expandedMusicOpen) EXPANDED_MUSIC_BLUR_LEVEL else mediaBlurLevel

    private val effectiveMediaFadeLevel: Int
        get() = if (expandedMusicOpen) EXPANDED_MUSIC_FADE_LEVEL else mediaFadeLevel

    private fun setupMediaFilter() {
        val ambient = (isDozing() || isPulsing()) && aodEnabled
        val grayscaleEffect = if (ambient) RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(grayscaleMatrix)
        ) else null
        framedAlbum.setRenderEffect(grayscaleEffect)

        if (effectiveMediaFilter == FILTER_FRAMED_ALBUM) {
            val blurEffect = if (effectiveMediaBlurLevel > 0) RenderEffect.createBlurEffect(
                effectiveMediaBlurLevel.toFloat(),
                effectiveMediaBlurLevel.toFloat(),
                Shader.TileMode.MIRROR
            ) else null
            mediaBackground.setRenderEffect(
                if (blurEffect != null && grayscaleEffect != null) {
                    RenderEffect.createChainEffect(grayscaleEffect, blurEffect)
                } else blurEffect ?: grayscaleEffect
            )
            mediaBackground.colorFilter = null
            return
        }

        if (ambient) {
            mediaBackground.setRenderEffect(grayscaleEffect)
            mediaBackground.colorFilter = null
            return
        }

        val effect = when (effectiveMediaFilter) {
            1 -> if (effectiveMediaBlurLevel > 0) RenderEffect.createBlurEffect(
                effectiveMediaBlurLevel.toFloat(),
                effectiveMediaBlurLevel.toFloat(),
                Shader.TileMode.MIRROR
            ) else null
            2 -> {
                val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(grayMatrix))
            }
            3 -> {
                val sepiaMatrix = ColorMatrix().apply { setSaturation(0f) }
                val sepia = floatArrayOf(
                    1f, 0f, 0f, 0f, 30f,
                    0f, 1f, 0f, 0f, 20f,
                    0f, 0f, 1f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )
                sepiaMatrix.postConcat(ColorMatrix(sepia))
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(sepiaMatrix))
            }
            4 -> null
            5 -> {
                val invertMatrix = floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix(invertMatrix)))
            }
            6 -> {
                val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
                val grayscaleEffect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(grayMatrix))
                if (effectiveMediaBlurLevel > 0) {
                    val blurEffect = RenderEffect.createBlurEffect(
                        effectiveMediaBlurLevel.toFloat(),
                        effectiveMediaBlurLevel.toFloat(),
                        Shader.TileMode.MIRROR
                    )
                    RenderEffect.createChainEffect(blurEffect, grayscaleEffect)
                } else grayscaleEffect
            }
            7 -> null
            else -> null
        }

        mediaBackground.setRenderEffect(effect)
        if (effectiveMediaFilter == 4) {
            context.theme.resolveAttribute(android.R.attr.colorAccent, sharedTypedValue, true)
            val accent = sharedTypedValue.data
            mediaBackground.colorFilter = PorterDuffColorFilter(
                Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent)),
                PorterDuff.Mode.SRC_ATOP
            )
        } else {
            mediaBackground.colorFilter = null
        }
    }

    private fun shouldShowMediaArt(): Boolean {
        if (!featureEnabled && !expandedMusicOpen) return false
        
        val dozing = isDozing()
        val pulsing = isPulsing()

        if ((dozing || pulsing) && !aodEnabled && !expandedMusicOpen) return false
        
        if (artworkDrawable == null) return false
        val isPortrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val isKeyguard = scrimUtils.isKeyguardShowing()
        val isCollapsed = scrimUtils.isPanelFullyCollapsed()
        if (!isPortrait || !isKeyguard || !isCollapsed) return false
        if (!isMediaPlaying) return false
        if (bouncerShowing || keyguardGoingAway || keyguardFadingAway) return false
        return true
    }

    private fun cancelScrimAnim() {
        mediaScrim.animate().setListener(null).cancel()
    }

    private fun showMediaArt() {
        if (isAlbumArtVisible && scrimState == STATE_SCRIM_VISIBLE) {
            updateAodAlpha()
            return
        }
        if (mediaArtJob?.isActive != true) updateMediaArt()
    }

    private fun getTargetAlpha(): Float {
        return if ((isDozing() || isPulsing()) && aodEnabled) {
            aodDimLevel / 100f
        } else {
            1f
        }
    }

    private fun updateAodAlpha() {
        if (scrimState != STATE_SCRIM_VISIBLE) return
        
        val targetAlpha = getTargetAlpha()
        
        dismissingKeyguard = false
        cancelScrimAnim()
        setupMediaFilter()
        updateFramedAlbum()
        mediaScrim.animate()
            .alpha(targetAlpha)
            .setDuration(300)
            .setListener(null)
            .start()
    }

    private fun updateMediaArt() {
        cancelArtworkUpdate()
        if (!shouldShowMediaArt()) return
        val source = artworkDrawable ?: return
        val requestId = artworkRequestId
        val filter = effectiveMediaFilter
        val blockSize = pixelSize
        val fadeLevel = if ((isDozing() || isPulsing()) && aodEnabled && !expandedMusicOpen) {
            min(effectiveMediaFadeLevel + 15, 100)
        } else effectiveMediaFadeLevel

        mediaArtJob = coroutineScope.launch {
            delay(40)
            val drawable = try {
                withContext(backgroundDispatcher) {
                    processArtwork(source, filter, blockSize, fadeLevel)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "processArtwork failed", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Not enough memory for lockscreen artwork", e)
                null
            }
            // A lock/unlock, settings change or newer track can invalidate an in-flight render.
            if (requestId != artworkRequestId || source !== artworkDrawable || !shouldShowMediaArt()) {
                return@launch
            }
            if (drawable == null) {
                cleanupResources(false)
                return@launch
            }

            cancelScrimAnim()
            setupMediaFilter()
            if (!isAlbumArtVisible) mediaScrim.alpha = 0f
            mediaBackground.setImageDrawable(drawable)
            mediaScrim.visibility = View.VISIBLE
            scrimState = STATE_SCRIM_VISIBLE
            // Depth wallpaper and notification blur must only react to successfully applied art.
            isAlbumArtVisible = true
            updateMediaElevation()
            updateAodAlpha()
        }
    }

    private fun cancelArtworkUpdate() {
        artworkRequestId++
        mediaArtJob?.cancel()
        mediaArtJob = null
    }

    private fun updateFramedAlbum() {
        if (effectiveMediaFilter != FILTER_FRAMED_ALBUM || !isAlbumArtVisible ||
            !shouldShowMediaArt() || dismissingKeyguard) {
            framedAlbum.visibility = View.GONE
            framedAlbum.clear()
            resetMotionArt()
            return
        }
        artworkDrawable?.let(framedAlbum::setArtwork)
        framedAlbum.visibility = View.VISIBLE

        if (!mediaScrim.isAttachedToWindow || !screenInteractive || isDozing() || isPulsing()) {
            stopMotionArt()
            return
        }
        if (trackTitle.isBlank() || trackArtist.isBlank() ||
            trackTitle == "Unknown" || trackArtist == "Unknown") {
            resetMotionArt()
            return
        }

        val key = trackArtist to trackTitle
        if (key != motionArtKey) {
            resetMotionArt()
            motionArtKey = key
        }
        if (motionArtResolved) {
            framedAlbum.setMotionArt(motionArtFile)
            return
        }
        if (motionArtJob?.isActive == true) return
        motionArtJob = coroutineScope.launch {
            val file = try {
                LunarisMotionArt.find(context, key.first, key.second)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot load lockscreen motion art", e)
                null
            }
            ensureActive()
            if (motionArtKey != key || effectiveMediaFilter != FILTER_FRAMED_ALBUM ||
                !mediaScrim.isAttachedToWindow || !isAlbumArtVisible || dismissingKeyguard ||
                !shouldShowMediaArt() || !screenInteractive || isDozing() || isPulsing()) return@launch
            motionArtFile = file
            motionArtResolved = true
            framedAlbum.setMotionArt(file)
        }
    }

    private fun stopMotionArt() {
        motionArtJob?.cancel()
        motionArtJob = null
        framedAlbum.clearMotionArt()
    }

    private fun resetMotionArt() {
        stopMotionArt()
        motionArtKey = null
        motionArtFile = null
        motionArtResolved = false
    }

    private fun processArtwork(
        drawable: Drawable,
        filter: Int,
        blockSize: Int,
        fadeLevel: Int
    ): LayerDrawable {
        val bitmap = drawableToBitmap(drawable)
        val resizedBitmap = getResizedBitmap(bitmap)
        if (bitmap !== resizedBitmap && !bitmap.isRecycled) bitmap.recycle()

        val processedBitmap = if (filter == 7) {
            applyPixelation(resizedBitmap, blockSize)
        } else {
            resizedBitmap
        }

        val bitmapDrawable = BitmapDrawable(context.resources, processedBitmap).apply {
            alpha = 255
        }

        val fadeColor = ColorUtils.blendARGB(
            Color.TRANSPARENT,
            Color.BLACK,
            fadeLevel / 100f
        )

        val overlay = ColorDrawable(fadeColor)

        val layers = arrayOf<Drawable>(bitmapDrawable, overlay)
        return LayerDrawable(layers).apply {
            setBounds(0, 0, processedBitmap.width, processedBitmap.height)
            setLayerInset(1, 0, 0, 0, 0)
        }
    }

    private fun applyPixelation(source: Bitmap, pixelSize: Int): Bitmap {
        val width = source.width
        val height = source.height
        val config = source.config ?: Bitmap.Config.ARGB_8888
        val pixelated = Bitmap.createBitmap(width, height, config)
        val canvas = Canvas(pixelated)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = false
        }

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val blockWidth = min(pixelSize, width - x)
                val blockHeight = min(pixelSize, height - y)
                
                val sampleX = x + blockWidth / 2
                val sampleY = y + blockHeight / 2
                val color = source.getPixel(
                    min(sampleX, width - 1),
                    min(sampleY, height - 1)
                )
                
                paint.color = color
                canvas.drawRect(
                    x.toFloat(),
                    y.toFloat(),
                    (x + blockWidth).toFloat(),
                    (y + blockHeight).toFloat(),
                    paint
                )
                
                x += pixelSize
            }
            y += pixelSize
        }

        if (source != pixelated && !source.isRecycled) {
            source.recycle()
        }

        return pixelated
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            // Never resize/recycle a bitmap owned by the media panel or another listener.
            return requireNotNull(drawable.bitmap?.copy(Bitmap.Config.ARGB_8888, false))
        }

        val source = drawable.constantState?.newDrawable(context.resources)?.mutate() ?: drawable

        val w = max(1, source.intrinsicWidth)
        val h = max(1, source.intrinsicHeight)

        val cfg = if (source.opacity == PixelFormat.OPAQUE) {
            Bitmap.Config.RGB_565
        } else {
            Bitmap.Config.ARGB_8888
        }
        val bitmap = Bitmap.createBitmap(w, h, cfg)
        val canvas = Canvas(bitmap)
        val oldBounds = Rect(source.bounds)
        try {
            source.setBounds(0, 0, canvas.width, canvas.height)
            source.draw(canvas)
        } finally {
            source.setBounds(oldBounds)
        }
        return bitmap
    }

    private fun getResizedBitmap(bitmap: Bitmap): Bitmap {
        val displayBounds = context.getSystemService(WindowManager::class.java)
            .currentWindowMetrics.bounds

        val ratioW = displayBounds.width() / bitmap.width.toFloat()
        val ratioH = displayBounds.height() / bitmap.height.toFloat()
        val ratio = max(ratioH, ratioW)

        val desiredHeight = (ratio * bitmap.height).roundToInt().coerceAtLeast(1)
        val desiredWidth = (ratio * bitmap.width).roundToInt().coerceAtLeast(1)

        if (abs(desiredWidth - bitmap.width) <= 1 && abs(desiredHeight - bitmap.height) <= 1) {
            val xPixelShift = max((bitmap.width - displayBounds.width()) / 2, 0)
            val yPixelShift = max((bitmap.height - displayBounds.height()) / 2, 0)
            val cropWidth = min(displayBounds.width(), bitmap.width - xPixelShift)
            val cropHeight = min(displayBounds.height(), bitmap.height - yPixelShift)
            return Bitmap.createBitmap(bitmap, xPixelShift, yPixelShift, cropWidth, cropHeight)
        }

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, desiredWidth, desiredHeight, true)

        val xPixelShift = max((desiredWidth - displayBounds.width()) / 2, 0)
        val yPixelShift = max((desiredHeight - displayBounds.height()) / 2, 0)
        val cropWidth = min(displayBounds.width(), scaledBitmap.width - xPixelShift)
        val cropHeight = min(displayBounds.height(), scaledBitmap.height - yPixelShift)

        val cropped = Bitmap.createBitmap(
            scaledBitmap,
            xPixelShift,
            yPixelShift,
            cropWidth,
            cropHeight
        )
        if (scaledBitmap != cropped && !scaledBitmap.isRecycled) {
            scaledBitmap.recycle()
        }
        return cropped
    }

    private fun clearResources() {
        framedAlbum.clear()
        framedAlbum.visibility = View.GONE
        mediaBackground.setImageDrawable(null)
        mediaBackground.setRenderEffect(null)
        mediaBackground.colorFilter = null
        mediaScrim.visibility = View.GONE
        scrimState = STATE_SCRIM_HIDDEN
        isAlbumArtVisible = false
        dismissingKeyguard = false
    }

    fun cleanupResources(animate: Boolean) {
        showDelayJob?.cancel()
        hideDelayJob?.cancel()
        cancelArtworkUpdate()
        resetMotionArt()
        cancelScrimAnim()
        
        if (!animate) {
            dismissingKeyguard = false
            clearResources()
            return
        }
        
        dismissingKeyguard = true
        mediaScrim.animate()
            .alpha(0f)
            .setDuration(100)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    clearResources()
                }
            })
            .start()
    }

    override fun onAlbumArtChanged(drawable: Drawable?) {
        coroutineScope.launch {
            if (artworkDrawable === drawable) return@launch
            artworkDrawable = drawable
            cancelArtworkUpdate()
            if (scrimState == STATE_SCRIM_VISIBLE && shouldShowMediaArt()) {
                updateMediaArt()
            }
            updateMediaState()
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        coroutineScope.launch {
            isMediaPlaying = state == PlaybackState.STATE_PLAYING
            // Pause/buffering does not necessarily trigger a new artwork callback on resume.
            // Only the artwork producer clears the cached source when a session/artwork is lost.
            updateMediaState()
        }
    }

    override fun onMetadataChanged(track: String, artist: String) {
        coroutineScope.launch {
            if (trackTitle == track && trackArtist == artist) return@launch
            trackTitle = track
            trackArtist = artist
            resetMotionArt()
            updateFramedAlbum()
        }
    }

    override fun onUserChanged() {
        coroutineScope.launch {
            cleanupResources(false)
            updateSettings()
        }
    }

    override fun onPrimaryBouncerShowingChanged(showing: Boolean) {
        bouncerShowing = showing
        if (showing) {
            showDelayJob?.cancel()
            hideDelayJob?.cancel()
            cleanupResources(true)
        }
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        keyguardGoingAway = goingAway
        if (goingAway) {
            showDelayJob?.cancel()
            hideDelayJob?.cancel()
            cleanupResources(true)
        }
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        keyguardFadingAway = fadingAway
        if (fadingAway) {
            showDelayJob?.cancel()
            hideDelayJob?.cancel()
            cleanupResources(true)
        }
    }

    override fun onDozingChanged(dozing: Boolean) {
        coroutineScope.launch {
            if (isAlbumArtVisible && shouldShowMediaArt()) updateMediaArt()
            updateMediaElevation()
            updateMediaState()
        }
    }

    override fun setPulsing(pulsing: Boolean) {
        coroutineScope.launch {
            if (isAlbumArtVisible && shouldShowMediaArt()) updateMediaArt()
            updateMediaElevation()
            updateMediaState()
        }
    }

    private fun updateMediaElevation() {
        val ambient = isDozing() || isPulsing()
        mediaScrim.translationZ = if (ambient) 1f else 0f
    }

    override fun onExpandedFractionChanged(expandedFraction: Float) {
        coroutineScope.launch {
            updateMediaState()
        }
    }

    override fun onBarStateChanged(state: Int) {
        coroutineScope.launch {
            updateMediaState()
        }
    }

    override fun onQsVisibilityChanged(visible: Boolean) {
        coroutineScope.launch {
            updateMediaState()
        }
    }

    override fun onKeyguardShowingChanged(showing: Boolean) {
        if (showing) {
            dismissingKeyguard = false
        } else {
            showDelayJob?.cancel()
            hideDelayJob?.cancel()
            cleanupResources(false)
        }
        coroutineScope.launch {
            updateMediaState()
        }
    }

    override fun onScreenTurnedOff() {
        screenInteractive = false
        showDelayJob?.cancel()
        hideDelayJob?.cancel()
        dismissingKeyguard = false
        cleanupResources(false)
    }

    override fun onStartedWakingUp() {
        screenInteractive = true
        dismissingKeyguard = false
        coroutineScope.launch {
            updateMediaState()
        }
    }

    private fun updateMediaState() {
        val shouldShow = shouldShowMediaArt()
        
        showDelayJob?.cancel()
        hideDelayJob?.cancel()
        if (!shouldShow) {
            cancelArtworkUpdate()
            resetMotionArt()
        }
        
        if (shouldShow && scrimState == STATE_SCRIM_HIDDEN) {
            showDelayJob = coroutineScope.launch {
                delay(MEDIA_SHOW_DELAY_MS)
                if (shouldShowMediaArt() && scrimState == STATE_SCRIM_HIDDEN) {
                    showMediaArt()
                }
            }
        } else if (!shouldShow && scrimState == STATE_SCRIM_VISIBLE) {
            hideDelayJob = coroutineScope.launch {
                delay(MEDIA_HIDE_DELAY_MS)
                if (!shouldShowMediaArt() && scrimState == STATE_SCRIM_VISIBLE) {
                    cleanupResources(true)
                }
            }
        } else if (shouldShow && scrimState == STATE_SCRIM_VISIBLE) {
            updateAodAlpha()
        }
    }

    fun getMediaArtScrim() = mediaScrim

    fun albumArtVisible() = isAlbumArtVisible

    fun setSubjectAlpha(alpha: Float) {
        mediaScrim.post {
            mediaScrim.alpha = alpha.coerceIn(0f, 1f)
        }
    }

    fun setExpandedMusicOpen(open: Boolean) {
        if (expandedMusicOpen == open) return
        expandedMusicOpen = open
        coroutineScope.launch {
            refreshListening()
            if (scrimState == STATE_SCRIM_VISIBLE) {
                updateMediaArt()
                setupMediaFilter()
            }
            updateMediaState()
        }
    }

    fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(settingsObserver)
        scrimUtils.removeListener(this)
        if (listening) {
            mediaSessionManager.removeListener(this)
            listening = false
        }
        cleanupResources(false)
        artworkDrawable = null
        expandedMusicOpen = false
        coroutineScope.cancel()
        if (INSTANCE === this) INSTANCE = null
    }

    companion object {
        private const val TAG = "MediaViewController"
        private const val FILTER_FRAMED_ALBUM = 8

        @Volatile
        private var INSTANCE: MediaViewController? = null

        @JvmStatic
        fun get(context: Context): MediaViewController {
            return INSTANCE ?: throw IllegalStateException(
                "MediaViewController not initialized"
            )
        }
        @JvmStatic
        fun getOrNull(): MediaViewController? = INSTANCE
    }
}
