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

package com.android.systemui.media

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.android.systemui.media.remedia.motionart.LunarisMotionArtPlayer
import com.android.systemui.media.remedia.motionart.LunarisMotionArtTextureView
import java.io.File
import kotlin.math.roundToInt

internal class FramedMediaArtView(context: Context) : FrameLayout(context) {
    private val artwork = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private var source: Drawable? = null
    private var motionView: LunarisMotionArtTextureView? = null
    private var motionFile: File? = null

    init {
        val density = resources.displayMetrics.density
        val radius = 22f * density
        background =
            GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = radius
            }
        foreground =
            GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = radius
                setStroke(density.roundToInt().coerceAtLeast(1), Color.argb(210, 255, 255, 255))
            }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        elevation = 24f * density
        outlineAmbientShadowColor = Color.argb(100, 0, 0, 0)
        outlineSpotShadowColor = Color.argb(180, 0, 0, 0)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(artwork, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setArtwork(drawable: Drawable) {
        if (source === drawable) return
        source = drawable
        artwork.setImageDrawable(
            drawable.constantState?.newDrawable(resources)?.mutate() ?: drawable
        )
    }

    fun setMotionArt(file: File?) {
        if (motionFile == file) return
        clearMotionArt()
        if (file == null) return
        motionFile = file
        val video =
            LunarisMotionArtTextureView(context).apply {
                isOpaque = false
                alpha = 0f
            }
        motionView = video
        video.loopingPlayer =
            LunarisMotionArtPlayer(video, file, crop = true) { rendering ->
                video.animate().cancel()
                if (rendering) {
                    video.animate().alpha(1f).setDuration(400).start()
                } else {
                    video.alpha = 0f
                }
            }
        video.surfaceTextureListener = video.loopingPlayer
        video.loopingPlayer?.setPlaying(true)
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun clearMotionArt() {
        motionView?.let { video ->
            video.animate().cancel()
            video.loopingPlayer?.release()
            video.surfaceTextureListener = null
            video.loopingPlayer = null
            removeView(video)
        }
        motionView = null
        motionFile = null
    }

    fun clear() {
        clearMotionArt()
        artwork.setImageDrawable(null)
        source = null
        setRenderEffect(null)
    }

    override fun onDetachedFromWindow() {
        clearMotionArt()
        super.onDetachedFromWindow()
    }
}
