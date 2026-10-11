/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.content.ContentProvider
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object ClockImages {
    val uri: Uri = Uri.parse("content://org.lunaris.lockstyles.clockimages/images")
    const val MAX_FILE_BYTES = 20 * 1024 * 1024
    const val MAX_STORAGE_BYTES = 128L * 1024 * 1024
    const val MAX_DECODE_EDGE = 768
    private val idPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.img")
    internal val worker = Executors.newFixedThreadPool(2)

    fun validId(id: String) = idPattern.matches(id)

    fun configureDecoder(decoder: ImageDecoder, info: ImageDecoder.ImageInfo) {
        val width = info.size.width
        val height = info.size.height
        require(width > 0 && height > 0 && width.toLong() * height <= 64L * 1024 * 1024)
        val scale = minOf(1f, MAX_DECODE_EDGE.toFloat() / maxOf(width, height))
        decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    internal fun load(context: Context, user: Int, id: String) = run {
        require(validId(id))
        val target = ContentProvider.maybeAddUserId(Uri.withAppendedPath(uri, id), user)
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, target)) { decoder, info, _ ->
            configureDecoder(decoder, info)
        }
    }
}
