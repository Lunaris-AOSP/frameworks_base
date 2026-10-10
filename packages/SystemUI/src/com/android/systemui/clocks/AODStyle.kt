/*
 * Copyright (C) 2023-2024 the risingOS Android Project
 * Copyright (C) 2024-2026 Lunaris AOSP 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.clocks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import com.android.settingslib.drawable.CircleFramedDrawable
import com.android.systemui.Dependency
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.tuner.TunerService
import java.io.File
import kotlin.math.roundToInt

class AODStyle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RelativeLayout(context, attrs), TunerService.Tunable {

    private val tunerService: TunerService = Dependency.get(TunerService::class.java)
    private val statusBarStateController: StatusBarStateController =
        Dependency.get(StatusBarStateController::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val imageHandler = Handler(Dependency.get(Dependency.BG_LOOPER))
    private val random = java.util.Random()

    private var aodImageView: ImageView? = null
    private var imagePath: String? = null
    private var loadingPath: String? = null
    private var aodImageEnabled = false
    private var imageLoaded = false
    private var isDozing = false
    private var loadGeneration = 0

    private val burnInProtectionRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !isDozing || !aodImageEnabled || !imageLoaded) return
            aodImageView?.apply {
                translationX = (random.nextInt(BURN_IN_PROTECTION_MAX_SHIFT * 2 + 1)
                    - BURN_IN_PROTECTION_MAX_SHIFT).toFloat()
                translationY = (random.nextInt(BURN_IN_PROTECTION_MAX_SHIFT * 2 + 1)
                    - BURN_IN_PROTECTION_MAX_SHIFT).toFloat()
            }
            handler.postDelayed(this, BURN_IN_PROTECTION_INTERVAL)
        }
    }

    private val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onDozingChanged(dozing: Boolean) {
            isDozing = dozing
            loadAodImage()
            updateAodImageView()
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadAodImage()
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        aodImageView = findViewById(R.id.custom_aod_image_view)
        visibility = View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isDozing = statusBarStateController.isDozing
        statusBarStateController.addCallback(statusBarStateListener)
        context.registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED),
            Context.RECEIVER_NOT_EXPORTED)
        tunerService.addTunable(this, CUSTOM_AOD_IMAGE_ENABLED_KEY, CUSTOM_AOD_IMAGE_URI_KEY)
        loadAodImage()
        updateAodImageView()
    }

    override fun onDetachedFromWindow() {
        statusBarStateController.removeCallback(statusBarStateListener)
        tunerService.removeTunable(this)
        context.unregisterReceiver(unlockReceiver)
        loadGeneration++
        imageHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        loadingPath = null
        imageLoaded = false
        aodImageView?.apply {
            animate().cancel()
            setImageDrawable(null)
            visibility = View.GONE
        }
        visibility = View.GONE
        super.onDetachedFromWindow()
    }

    override fun onTuningChanged(key: String?, newValue: String?) {
        when (key) {
            CUSTOM_AOD_IMAGE_URI_KEY -> {
                imagePath = newValue?.takeIf { it.isNotBlank() }
                loadGeneration++
                loadingPath = null
                imageLoaded = false
                aodImageView?.setImageDrawable(null)
            }
            CUSTOM_AOD_IMAGE_ENABLED_KEY -> {
                aodImageEnabled = TunerService.parseIntegerSwitch(newValue, false)
            }
            else -> return
        }
        loadAodImage()
        updateAodImageView()
    }

    private fun startBurnInProtection() {
        handler.removeCallbacks(burnInProtectionRunnable)
        handler.postDelayed(burnInProtectionRunnable, BURN_IN_PROTECTION_INTERVAL)
    }

    private fun stopBurnInProtection() {
        handler.removeCallbacks(burnInProtectionRunnable)
        aodImageView?.apply {
            translationX = 0f
            translationY = 0f
        }
    }

    private fun updateAodImageView() {
        val imageView = aodImageView ?: return
        val show = isAttachedToWindow && aodImageEnabled && isDozing && imageLoaded
        if (!show) {
            imageView.animate().cancel()
            imageView.visibility = View.GONE
            visibility = View.GONE
            stopBurnInProtection()
            return
        }
        val wasVisible = visibility == View.VISIBLE && imageView.visibility == View.VISIBLE
        visibility = View.VISIBLE
        imageView.visibility = View.VISIBLE
        if (!wasVisible) {
            imageView.animate().cancel()
            imageView.scaleX = 0f
            imageView.scaleY = 0f
            imageView.animate().scaleX(1f).scaleY(1f).setDuration(500).start()
            startBurnInProtection()
        }
    }

    private fun loadAodImage() {
        val path = imagePath ?: return
        if (!isAttachedToWindow || !aodImageEnabled || imageLoaded || loadingPath == path) return
        loadingPath = path
        val generation = ++loadGeneration
        val targetSize = resources.getDimensionPixelSize(R.dimen.custom_aod_image_size)
        imageHandler.post {
            val drawable = try {
                decodeAvatar(path, targetSize)
            } catch (e: Exception) {
                Log.w("AODStyle", "Could not load AOD avatar", e)
                null
            }
            handler.post result@{
                if (generation != loadGeneration || !isAttachedToWindow) return@result
                loadingPath = null
                imageLoaded = drawable != null
                aodImageView?.setImageDrawable(drawable)
                updateAodImageView()
            }
        }
    }

    private fun decodeAvatar(path: String, targetSize: Int): Drawable {
        val uri = Uri.parse(path)
        val source = if (uri.scheme == null) ImageDecoder.createSource(File(path))
            else ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = minOf(1024f / maxOf(info.size.width, info.size.height), 1f)
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            val side = minOf(bitmap.width, bitmap.height)
            val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2,
                (bitmap.height - side) / 2, side, side)
            try {
                return CircleFramedDrawable(square, targetSize)
            } finally {
                if (square !== bitmap) square.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        private const val CUSTOM_AOD_IMAGE_URI_KEY = "system:custom_aod_image_uri"
        private const val CUSTOM_AOD_IMAGE_ENABLED_KEY = "system:custom_aod_image_enabled"
        private const val BURN_IN_PROTECTION_INTERVAL = 10_000L
        private const val BURN_IN_PROTECTION_MAX_SHIFT = 4
    }
}
