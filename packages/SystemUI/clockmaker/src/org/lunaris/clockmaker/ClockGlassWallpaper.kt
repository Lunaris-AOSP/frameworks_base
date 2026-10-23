/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import android.util.LruCache
import java.util.concurrent.Executors

internal object ClockGlassWallpaper {
    private data class Key(val user: Int, val width: Int, val height: Int)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cache = LruCache<Key, ClockGlass>(2)
    private val attempted = mutableSetOf<Key>()
    private val listeners = mutableSetOf<Runnable>()
    private var generation = 0
    private var manager: WallpaperManager? = null
    private val changed =
        WallpaperManager.OnColorsChangedListener { _, _ ->
            reset()
            listeners.toList().forEach { it.run() }
        }

    private fun reset() {
        generation++
        cache.evictAll()
        attempted.clear()
    }

    fun listen(context: Context, listener: Runnable) {
        if (manager == null) {
            manager = context.getSystemService(WallpaperManager::class.java)
            runCatching { manager?.addOnColorsChangedListener(changed, main, UserHandle.USER_ALL) }
                .onFailure { Log.w("ClockGlass", "Cannot watch wallpaper changes", it) }
        }
        listeners.add(listener)
    }

    fun unlisten(listener: Runnable) {
        listeners.remove(listener)
    }

    fun get(context: Context, user: Int): ClockGlass? {
        val metrics = context.resources.displayMetrics
        val key =
            Key(user, metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
        cache.get(key)?.let {
            return it
        }
        if (!attempted.add(key)) return null
        val version = generation
        val app = context.applicationContext ?: context
        worker.execute {
            val result =
                runCatching {
                        val userContext = app.createContextAsUser(UserHandle.of(user), 0)
                        decode(userContext, key.width, key.height)?.let(ClockGlass::prepare)
                    }
                    .onFailure { Log.w("ClockGlass", "Cannot prepare wallpaper fill", it) }
                    .getOrNull()
            main.post {
                if (version == generation) {
                    if (result != null) {
                        attempted.remove(key)
                        cache.put(key, result)
                    }
                    listeners.toList().forEach { it.run() }
                }
            }
        }
        return null
    }

    private fun decode(context: Context, width: Int, height: Int): Bitmap? {
        val wm = context.getSystemService(WallpaperManager::class.java) ?: return null
        val which =
            if (wm.getWallpaperId(WallpaperManager.FLAG_LOCK) >= 0) {
                WallpaperManager.FLAG_LOCK
            } else WallpaperManager.FLAG_SYSTEM
        if (wm.getWallpaperInfo(which) != null) return null
        fun open() = wm.getWallpaperFile(which)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options =
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = 1
                while (
                    bounds.outWidth / (inSampleSize * 2) >= width &&
                        bounds.outHeight / (inSampleSize * 2) >= height
                ) inSampleSize *= 2
            }
        val raw =
            open()?.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options) }
                ?: return null
        try {
            val scale = maxOf(width.toFloat() / raw.width, height.toFloat() / raw.height)
            val w = raw.width * scale
            val h = raw.height * scale
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(
                    raw,
                    null,
                    RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
            }
        } finally {
            raw.recycle()
        }
    }
}
