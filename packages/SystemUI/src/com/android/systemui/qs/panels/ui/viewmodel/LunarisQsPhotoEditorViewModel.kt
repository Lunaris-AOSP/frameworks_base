/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.android.systemui.qs.panels.data.repository.LunarisQsPhotoStorage
import com.android.systemui.qs.panels.shared.model.AxQsLayout
import com.android.systemui.qs.panels.shared.model.LunarisQsPhotoFrame
import com.android.systemui.qs.panels.ui.edit.LunarisQsPhotoActivity
import com.android.systemui.res.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LunarisQsPhotoEditorViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
    private val context = application
    private val userId = context.userId
    private val layout =
        AxQsLayout.valueOf(checkNotNull(savedState[LunarisQsPhotoActivity.EXTRA_LAYOUT]))
    var photo by mutableStateOf(LunarisQsPhotoFrame(userId = userId))
        private set

    var error by mutableStateOf<Int?>(null)
        private set

    var finished by mutableStateOf(false)
        private set

    private var loading by mutableStateOf(true)
    private var working by mutableStateOf(false)
    private var initialized by mutableStateOf(false)
    var picking by mutableStateOf(savedState.get<Boolean>(PICKING) ?: false)
        private set

    val busy: Boolean
        get() = loading || working || picking

    val canEdit: Boolean
        get() = initialized && !busy && !finished

    private val initialization =
        viewModelScope.launch {
            try {
                photo =
                    withContext(Dispatchers.IO) {
                        LunarisQsPhotoFrame.fromJson(
                            savedState.get<String>(PHOTO)
                                ?: Settings.Secure.getStringForUser(
                                    context.contentResolver,
                                    LunarisQsPhotoFrame.settingKey(layout),
                                    userId,
                                ),
                            userId,
                        )
                    }
                initialized = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot load photo frame settings", e)
                error = R.string.lunaris_qs_photo_load_error
            } finally {
                loading = false
            }
            if (initialized) {
                try {
                    LunarisQsPhotoStorage.prune(context, userId, photo.fileName)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot prune unused photo frames", e)
                }
            }
        }

    fun beginPicking(): Boolean {
        if (!canEdit) return false
        error = null
        updatePicking(true)
        return true
    }

    fun pickerUnavailable() {
        updatePicking(false)
        error = R.string.lunaris_qs_photo_picker_error
    }

    fun importPhoto(uri: Uri?) {
        updatePicking(false)
        if (uri == null || working || finished) return
        working = true
        error = null
        viewModelScope.launch {
            try {
                initialization.join()
                check(initialized)
                val name = LunarisQsPhotoStorage.importPhoto(context, uri, layout, userId)
                val previous = photo
                update(photo.copy(fileName = name, positionX = .5f, positionY = .5f, zoom = 1f))
                discard(previous)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot import photo frame", e)
                error = R.string.lunaris_qs_photo_error
            } catch (_: OutOfMemoryError) {
                error = R.string.lunaris_qs_photo_error
            } finally {
                working = false
            }
        }
    }

    fun change(updated: LunarisQsPhotoFrame) {
        if (!canEdit) return
        val previous = photo
        update(updated)
        if (previous.fileName != updated.fileName) viewModelScope.launch { discard(previous) }
    }

    fun save() {
        if (!canEdit) return
        working = true
        error = null
        val current = photo
        viewModelScope.launch {
            try {
                val previous =
                    withContext(Dispatchers.IO) {
                        val key = LunarisQsPhotoFrame.settingKey(layout)
                        val previous =
                            LunarisQsPhotoFrame.fromJson(
                                Settings.Secure.getStringForUser(
                                    context.contentResolver,
                                    key,
                                    userId,
                                ),
                                userId,
                            )
                        check(
                            Settings.Secure.putStringForUser(
                                context.contentResolver,
                                key,
                                current.toJson(),
                                userId,
                            )
                        )
                        previous
                    }
                if (previous.fileName != current.fileName) discard(previous)
                finished = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cannot save photo frame settings", e)
                error = R.string.lunaris_qs_photo_save_error
            } finally {
                working = false
            }
        }
    }

    fun cancel() {
        if (busy || finished) return
        working = true
        viewModelScope.launch {
            if (initialized) discard(photo)
            finished = true
            working = false
        }
    }

    private fun update(value: LunarisQsPhotoFrame) {
        photo = value
        savedState[PHOTO] = value.toJson()
    }

    private fun updatePicking(value: Boolean) {
        picking = value
        savedState[PICKING] = value
    }

    private suspend fun discard(value: LunarisQsPhotoFrame) {
        try {
            LunarisQsPhotoStorage.deleteDraft(context, value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cannot discard unused photo frame", e)
        }
    }

    private companion object {
        const val TAG = "QsPhotoEditor"
        const val PHOTO = "photo"
        const val PICKING = "picking"
    }
}
