/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlin.math.max


internal object HyperBackdrop {

    private const val TAG = "HyperBackdrop"

    const val SAMPLE = 8

    private const val MAX_BLUR = 250f

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Runnable>(2)

    private var source: Bitmap? = null
    private var blurred: Bitmap? = null
    private var blurredRadius = -1f
    private var tried = false
    private var listening = false

    private val wallpaperChanged = WallpaperManager.OnColorsChangedListener { _, _ ->
        invalidate()
        for (i in listeners.indices) listeners[i].run()
    }

    fun bitmap(context: Context, radiusPx: Float): Bitmap? {
        val src = source(context) ?: return null
        val r = (radiusPx / SAMPLE).coerceIn(1f, MAX_BLUR)
        blurred?.let { if (blurredRadius == r) return it }
        val out = runCatching { blur(src, r) }
            .onFailure { Log.w(TAG, "cannot blur the wallpaper", it) }
            .getOrNull() ?: return null
        blurred = out
        blurredRadius = r
        return out
    }

    fun addListener(context: Context, listener: Runnable) {
        listen(context)
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    fun invalidate() {
        source = null
        blurred = null
        blurredRadius = -1f
        tried = false
    }

    private fun listen(context: Context) {
        if (listening) return
        val wm = context.getSystemService(WallpaperManager::class.java) ?: return
        runCatching { wm.addOnColorsChangedListener(wallpaperChanged, handler) }
            .onSuccess { listening = true }
            .onFailure { Log.w(TAG, "cannot watch the wallpaper", it) }
    }

    private fun source(context: Context): Bitmap? {
        val metrics = context.resources.displayMetrics
        val tw = max(1, metrics.widthPixels / SAMPLE)
        val th = max(1, metrics.heightPixels / SAMPLE)
        source?.let { if (it.width == tw && it.height == th) return it else invalidate() }
        if (tried) return null
        tried = true
        val bm = runCatching { decode(context, tw, th) }
            .onFailure { Log.w(TAG, "cannot read the wallpaper", it) }
            .getOrNull()
        if (bm == null) Log.i(TAG, "no wallpaper bitmap; hyper clocks fall back to a tint")
        source = bm
        return bm
    }

    private fun open(wm: WallpaperManager): ParcelFileDescriptor? =
        wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)
            ?: wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)

    private fun decode(context: Context, tw: Int, th: Int): Bitmap? {
        val wm = context.getSystemService(WallpaperManager::class.java) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(wm)?.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            if (wm.wallpaperInfo != null) Log.i(TAG, "live wallpaper: nothing to read")
            return null
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, tw, th)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = open(wm)?.use {
            BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, opts)
        } ?: return null
        return cropToDisplay(raw, tw, th)
    }

    private fun sampleSize(w: Int, h: Int, tw: Int, th: Int): Int {
        var s = 1
        while (w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2
        return s
    }

    private fun cropToDisplay(src: Bitmap, tw: Int, th: Int): Bitmap {
        val scale = max(tw.toFloat() / src.width, th.toFloat() / src.height)
        val w = src.width * scale
        val h = src.height * scale
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src,
            null,
            RectF((tw - w) / 2f, (th - h) / 2f, (tw + w) / 2f, (th + h) / 2f),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }

    private fun blur(src: Bitmap, radius: Float): Bitmap? {
        val reader = ImageReader.newInstance(
            src.width,
            src.height,
            PixelFormat.RGBA_8888,
            1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            val node = RenderNode("hyper-backdrop")
            node.setPosition(0, 0, src.width, src.height)
            node.setRenderEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            )
            node.beginRecording().drawBitmap(src, 0f, 0f, null)
            node.endRecording()
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            reader.acquireNextImage().use { image ->
                val buffer = image?.hardwareBuffer ?: return null
                try {
                    return Bitmap.wrapHardwareBuffer(
                        buffer,
                        ColorSpace.get(ColorSpace.Named.SRGB),
                    )?.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    buffer.close()
                }
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }
}
