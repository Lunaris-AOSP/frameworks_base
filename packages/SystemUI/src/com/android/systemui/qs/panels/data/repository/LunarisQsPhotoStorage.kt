/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.LunarisQsPhotoFrame
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object LunarisQsPhotoStorage {
    const val STILL_MAX_EDGE = 768
    const val ANIMATED_MAX_EDGE = 512
    internal const val MAX_IMPORT_BYTES = 20L * 1024 * 1024
    internal const val MAX_ANIMATED_BYTES = 8L * 1024 * 1024

    private fun directory(context: Context, userId: Int): File {
        require(userId == context.userId)
        val userContext = context.createCredentialProtectedStorageContext()
        return File(userContext.filesDir, "qs_photo_frames")
    }

    fun file(context: Context, photo: LunarisQsPhotoFrame): File? {
        val name = photo.fileName ?: return null
        if (photo.userId < 0 || !LunarisQsPhotoFrame.isValidFileName(name)) return null
        return File(directory(context, photo.userId), name)
    }

    fun targetSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxEdge > 0)
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(width, height))
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    private val decodeListener =
        ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
            val maxPixels = if (info.isAnimated) 16_000_000L else 120_000_000L
            if (info.size.width.toLong() * info.size.height > maxPixels) {
                throw IOException("Image dimensions exceed the import limit")
            }
            val limit = if (info.isAnimated) ANIMATED_MAX_EDGE else STILL_MAX_EDGE
            val (width, height) = targetSize(info.size.width, info.size.height, limit)
            decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.setOnPartialImageListener { false }
        }

    suspend fun importPhoto(context: Context, uri: Uri, layout: AxQsLayout, userId: Int): String {
        var output: File? = null
        var preview: File? = null
        return try {
            withContext(Dispatchers.IO) {
                require(uri.scheme == "content")
                val dir = directory(context, userId)
                if (!dir.exists() && !dir.mkdirs())
                    throw IOException("Cannot create photo directory")
                val input = File.createTempFile("import_", ".tmp", dir)
                try {
                    context.contentResolver.openInputStream(uri)?.use { source ->
                        input.outputStream().use { destination ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_IMPORT_BYTES)
                                    throw IOException("Photo is too large")
                                destination.write(buffer, 0, count)
                            }
                        }
                    } ?: throw IOException("Cannot open photo")
                    var animated = false
                    val bitmap =
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(input)) { d, info, s ->
                            animated = info.isAnimated
                            if (animated && input.length() > MAX_ANIMATED_BYTES) {
                                throw IOException("Animation is too large")
                            }
                            decodeListener.onHeaderDecoded(d, info, s)
                        }
                    try {
                        val extension = if (animated) "anim" else "webp"
                        output =
                            File(
                                dir,
                                "${layout.name.lowercase(Locale.ROOT)}_${UUID.randomUUID()}.$extension",
                            )
                        preview =
                            if (animated) File(dir, output!!.nameWithoutExtension + ".webp")
                            else output
                        preview!!.outputStream().use {
                            if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, it)) {
                                throw IOException("Cannot save photo")
                            }
                        }
                        if (animated) {
                            if (!input.renameTo(output!!))
                                throw IOException("Cannot save animation")
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    ensureActive()
                    output!!.name
                } finally {
                    input.delete()
                }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                output?.delete()
                preview?.delete()
            }
            throw e
        }
    }

    suspend fun load(
        context: Context,
        photo: LunarisQsPhotoFrame,
        animate: Boolean = false,
    ): Drawable? =
        withContext(Dispatchers.IO) {
            try {
                val uri =
                    LunarisQsPhotoProvider.uri(photo, preview = !animate) ?: return@withContext null
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                if (animate) {
                    ImageDecoder.decodeDrawable(source, decodeListener).also {
                        (it as? AnimatedImageDrawable)?.repeatCount =
                            AnimatedImageDrawable.REPEAT_INFINITE
                    }
                } else {
                    BitmapDrawable(
                        context.resources,
                        ImageDecoder.decodeBitmap(source, decodeListener),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }

    suspend fun delete(context: Context, photo: LunarisQsPhotoFrame) =
        withContext(Dispatchers.IO) {
            file(context, photo)?.delete()
            if (photo.fileName?.endsWith(".anim") == true) {
                file(context, photo.copy(fileName = previewName(photo.fileName)))?.delete()
            }
            Unit
        }

    suspend fun prune(context: Context, userId: Int, draft: String?) =
        withContext(Dispatchers.IO) {
            val keep =
                AxQsLayout.entries
                    .mapNotNull { layout ->
                        LunarisQsPhotoFrame.fromJson(
                                Settings.Secure.getStringForUser(
                                    context.contentResolver,
                                    LunarisQsPhotoFrame.settingKey(layout),
                                    userId,
                                ),
                                userId,
                            )
                            .fileName
                    }
                    .plus(listOfNotNull(draft))
                    .flatMap { listOf(it, previewName(it)) }
                    .toSet()
            val before = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            directory(context, userId).listFiles()?.forEach { file ->
                if (file.name !in keep && file.lastModified() < before) file.delete()
            }
        }

    suspend fun deleteDraft(context: Context, photo: LunarisQsPhotoFrame) =
        withContext(Dispatchers.IO) {
            if (photo.userId < 0 || photo.fileName == null) return@withContext
            val referenced =
                AxQsLayout.entries.any { layout ->
                    LunarisQsPhotoFrame.fromJson(
                            Settings.Secure.getStringForUser(
                                context.contentResolver,
                                LunarisQsPhotoFrame.settingKey(layout),
                                photo.userId,
                            ),
                            photo.userId,
                        )
                        .fileName == photo.fileName
                }
            if (!referenced) delete(context, photo)
        }

    internal fun previewName(name: String): String =
        if (name.endsWith(".anim")) name.removeSuffix(".anim") + ".webp" else name
}
