/*
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.util.Log
import java.io.IOException

object ClockBlurWallpaperProvider {

    private const val TAG = "ClockBlurWallpaperProvider"

    private const val DOWNSAMPLE_FACTOR = 4
    private const val BLUR_RADIUS = 18

    @Volatile private var cachedBitmap: Bitmap? = null
    @Volatile private var cachedForWidth = 0
    @Volatile private var cachedForHeight = 0

    @Synchronized
    fun getBlurredWallpaper(context: Context): Bitmap? {
        val dm: DisplayMetrics = context.resources.displayMetrics
        val cached = cachedBitmap
        if (cached != null && !cached.isRecycled &&
            cachedForWidth == dm.widthPixels && cachedForHeight == dm.heightPixels) {
            return cached
        }
        return regenerate(context, dm)
    }

    @Synchronized
    fun invalidate(context: Context) {
        cachedBitmap?.let { if (!it.isRecycled) it.recycle() }
        cachedBitmap = null
        regenerate(context, context.resources.displayMetrics)
    }

    private fun regenerate(context: Context, dm: DisplayMetrics): Bitmap? {
        val source = loadWallpaperBitmap(context) ?: return null
        val blurred = try {
            downsampleAndBlur(source, dm.widthPixels, dm.heightPixels)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to blur wallpaper", t)
            null
        } finally {
            if (source != cachedBitmap) source.recycle()
        }
        cachedBitmap = blurred
        cachedForWidth = dm.widthPixels
        cachedForHeight = dm.heightPixels
        return blurred
    }

    private fun loadWallpaperBitmap(context: Context): Bitmap? {
        val wm = WallpaperManager.getInstance(context)

        fun fromFlag(flag: Int): Bitmap? {
            var pfd: ParcelFileDescriptor? = null
            return try {
                pfd = wm.getWallpaperFile(flag)
                pfd?.let { BitmapFactory.decodeFileDescriptor(it.fileDescriptor) }
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            } finally {
                try { pfd?.close() } catch (e: IOException) { /* no-op */ }
            }
        }

        fromFlag(WallpaperManager.FLAG_LOCK)?.let { return it }
        fromFlag(WallpaperManager.FLAG_SYSTEM)?.let { return it }

        val drawable: Drawable = wm.drawable ?: return null
        if (drawable is BitmapDrawable) return drawable.bitmap

        val w = drawable.intrinsicWidth.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val h = drawable.intrinsicHeight.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bmp
        } catch (t: Throwable) {
            null
        }
    }

    private fun downsampleAndBlur(source: Bitmap, screenW: Int, screenH: Int): Bitmap {
        val smallW = (screenW / DOWNSAMPLE_FACTOR).coerceAtLeast(1)
        val smallH = (screenH / DOWNSAMPLE_FACTOR).coerceAtLeast(1)

        val scale = maxOf(smallW.toFloat() / source.width, smallH.toFloat() / source.height)
        val scaledW = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)

        val blurred = stackBlur(scaled, BLUR_RADIUS)
        if (blurred != scaled) scaled.recycle()
        return blurred
    }

    private fun stackBlur(src: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return src
        val bitmap = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh); val g = IntArray(wh); val b = IntArray(wh)
        var rsum: Int; var gsum: Int; var bsum: Int
        var x: Int; var y: Int; var i: Int; var p: Int; var yp: Int; var yi: Int; var yw: Int
        val vmin = IntArray(maxOf(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (j in dv.indices) dv[j] = j / divsum

        yw = 0; yi = 0
        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int; var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int; var goutsum: Int; var boutsum: Int
        var rinsum: Int; var ginsum: Int; var binsum: Int

        y = 0
        while (y < h) {
            rinsum = 0; ginsum = 0; binsum = 0; routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            i = -radius
            while (i <= radius) {
                p = pix[yi + minOf(wm, maxOf(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r1 - abs(i)
                rsum += sir[0] * rbs; gsum += sir[1] * rbs; bsum += sir[2] * rbs
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2] }
                else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2] }
                i++
            }
            stackpointer = radius

            x = 0
            while (x < w) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]

                if (y == 0) vmin[x] = minOf(x + radius + 1, wm)
                p = pix[yw + vmin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)

                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]

                yi++
                x++
            }
            yw += w
            y++
        }

        x = 0
        while (x < w) {
            rinsum = 0; ginsum = 0; binsum = 0; routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = maxOf(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                rbs = r1 - abs(i)
                rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += b[yi] * rbs
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2] }
                else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2] }
                if (i < hm) yp += w
                i++
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (0xff000000.toInt()) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]

                if (x == 0) vmin[y] = minOf(y + r1, hm) * w
                p = x + vmin[y]

                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]

                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]

                yi += w
                y++
            }
            x++
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun abs(v: Int) = if (v < 0) -v else v
}
