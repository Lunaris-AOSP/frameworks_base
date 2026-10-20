/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.data.repository

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.android.systemui.qs.panels.shared.model.LunarisQsPhotoFrame
import java.io.FileNotFoundException

class LunarisQsPhotoProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Photo frames are read-only")
        val name = uri.pathSegments.singleOrNull()
        if (name == null || !LunarisQsPhotoFrame.isValidFileName(name)) {
            throw FileNotFoundException("Invalid photo frame")
        }
        val context = checkNotNull(context)
        var file =
            LunarisQsPhotoStorage.file(context, LunarisQsPhotoFrame(context.userId, name))
                ?: throw FileNotFoundException("Invalid photo frame")
        if (!file.isFile && name.endsWith(".webp")) {
            file =
                LunarisQsPhotoStorage.file(
                    context,
                    LunarisQsPhotoFrame(context.userId, name.removeSuffix(".webp") + ".anim"),
                ) ?: throw FileNotFoundException("Photo unavailable")
        }
        val maxBytes =
            if (file.extension == "anim") LunarisQsPhotoStorage.MAX_ANIMATED_BYTES
            else LunarisQsPhotoStorage.MAX_IMPORT_BYTES
        if (!file.isFile || file.length() !in 1..maxBytes) {
            throw FileNotFoundException("Photo unavailable")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/*"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Photo frames are read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Photo frames are read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Photo frames are read-only")

    companion object {
        private const val AUTHORITY = "com.android.systemui.qs.photo"

        fun uri(photo: LunarisQsPhotoFrame, preview: Boolean): Uri? {
            val name = photo.fileName ?: return null
            if (photo.userId < 0 || !LunarisQsPhotoFrame.isValidFileName(name)) return null
            return ContentProvider.maybeAddUserId(
                Uri.Builder()
                    .scheme("content")
                    .authority(AUTHORITY)
                    .appendPath(if (preview) LunarisQsPhotoStorage.previewName(name) else name)
                    .build(),
                photo.userId,
            )
        }
    }
}
