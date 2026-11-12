/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.app.WallpaperManager
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
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
    private data class Key(val user: Int, val width: Int, val height: Int, val display: Int)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private data class Cached(val generation: Int, val material: ClockGlass)
    private val cache = LruCache<Key, Cached>(2)
    private val loading = mutableSetOf<Key>()
    private val attempted = mutableSetOf<Key>()
    private val listeners = mutableSetOf<Runnable>()
    private var generation = 0
    private var manager: WallpaperManager? = null
    private val galleryUri = Uri.parse("content://org.lunaris.lockstyles.clockglass/wallpaper")
    private var galleryResolver: ContentResolver? = null
    private val galleryChanged = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            reset()
            listeners.toList().forEach { it.run() }
        }
    }
    private val changed =
        WallpaperManager.OnColorsChangedListener { _, _ ->
            reset()
            listeners.toList().forEach { it.run() }
        }

    private fun reset() {
        generation++
        attempted.clear()
    }

    fun listen(context: Context, listener: Runnable) {
        if (listeners.isEmpty()) reset()
        if (manager == null) {
            manager = context.getSystemService(WallpaperManager::class.java)
            runCatching { manager?.addOnColorsChangedListener(changed, main, UserHandle.USER_ALL) }
                .onFailure { Log.w("ClockGlass", "Cannot watch wallpaper changes", it) }
        }
        if (galleryResolver == null) {
            runCatching {
                context.contentResolver.registerContentObserver(galleryUri, true, galleryChanged, UserHandle.USER_ALL)
                galleryResolver = context.contentResolver
            }.onFailure { Log.w("ClockGlass", "Cannot watch gallery wallpaper", it) }
        }
        listeners.add(listener)
    }

    fun unlisten(listener: Runnable) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            runCatching { manager?.removeOnColorsChangedListener(changed) }
            manager = null
            runCatching { galleryResolver?.unregisterContentObserver(galleryChanged) }
            galleryResolver = null
        }
    }

    fun get(context: Context, user: Int): ClockGlass? {
        val metrics = context.resources.displayMetrics
        val key =
            Key(user, metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1),
                runCatching { context.display?.displayId ?: 0 }.getOrDefault(0))
        val cached = cache.get(key)
        if (cached?.generation == generation) return cached.material
        if (key in loading || !attempted.add(key)) return cached?.material
        loading.add(key)
        val version = generation
        val app = context.applicationContext ?: context
        worker.execute {
            val result =
                runCatching {
                        val userContext = app.createContextAsUser(UserHandle.of(user), 0)
                        decode(userContext, key.width, key.height, key.display)?.let {
                            ClockGlass.prepare(it, referenceWidth = key.width, referenceHeight = key.height)
                        }
                    }
                    .onFailure { Log.w("ClockGlass", "Cannot prepare wallpaper fill", it) }
                    .getOrNull()
            main.post {
                loading.remove(key)
                if (result != null) cache.put(key, Cached(version, result))
                else if (version == generation) cache.remove(key)
                listeners.toList().forEach { it.run() }
            }
        }
        return cached?.material
    }

    private fun decode(context: Context, width: Int, height: Int, display: Int): Bitmap? {
        val wm = context.getSystemService(WallpaperManager::class.java) ?: return null
        val which =
            if (wm.getWallpaperId(WallpaperManager.FLAG_LOCK) >= 0) {
                WallpaperManager.FLAG_LOCK
            } else WallpaperManager.FLAG_SYSTEM
        val live = wm.getWallpaperInfo(which)
        if (live != null) {
            if (live.packageName != "org.lunaris.lockstyles" ||
                (!live.serviceName.endsWith(".GalleryWallpaperService") &&
                    !live.serviceName.endsWith(".GalleryEffectService"))) return null
            val uri = galleryUri.buildUpon().appendQueryParameter("width", width.toString())
                .appendQueryParameter("height", height.toString())
                .appendQueryParameter("display", display.toString())
                .appendQueryParameter("lock", if (which == WallpaperManager.FLAG_LOCK) "1" else "0").build()
            return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
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
