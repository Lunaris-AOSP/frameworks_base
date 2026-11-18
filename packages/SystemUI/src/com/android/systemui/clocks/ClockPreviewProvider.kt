/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.Manifest
import android.app.WallpaperManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.UserHandle
import org.lunaris.clockmaker.ClockPreviewGeometry

class ClockPreviewProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        requireNotNull(context).enforceCallingPermission(
            Manifest.permission.STATUS_BAR_SERVICE, "Lock-screen preview geometry",
        )
        if (method != ClockPreviewGeometry.METHOD) return null
        val user = UserHandle.getUserId(Binder.getCallingUid())
        val geometry = ClockStyle.previewGeometry(extras?.getInt("display_id") ?: 0, user)
            ?: return null
        val identity = Binder.clearCallingIdentity()
        return try {
            val dimAmount = runCatching {
                requireNotNull(context).getSystemService(WallpaperManager::class.java)
                    ?.wallpaperDimAmount ?: 0f
            }.getOrDefault(0f)
            geometry.copy(wallpaperDimAmount = dimAmount.coerceIn(0f, 1f)).toBundle()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
