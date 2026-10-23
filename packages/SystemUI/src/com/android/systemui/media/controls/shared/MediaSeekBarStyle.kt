/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.media.controls.shared

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.Dependency
import com.android.systemui.settings.UserTracker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class MediaSeekBarStyle(val value: Int) {
    DEFAULT(0),
    WAVEFORM(1),
    GLOWING(2),
    GRADIENT_WAVE(3);

    companion object {
        fun fromValue(value: Int): MediaSeekBarStyle =
            entries.firstOrNull { it.value == value } ?: DEFAULT

        fun read(context: Context, userId: Int = UserHandle.USER_CURRENT): MediaSeekBarStyle =
            fromValue(
                Settings.System.getIntForUser(
                    context.contentResolver,
                    Settings.System.MEDIA_WAVEFORM_SEEKBAR,
                    DEFAULT.value,
                    userId,
                )
            )

        fun observe(context: Context): Flow<MediaSeekBarStyle> =
            callbackFlow {
                    val resolver = context.contentResolver
                    val userTracker = Dependency.get(UserTracker::class.java)
                    val observer =
                        object : ContentObserver(Handler(Looper.getMainLooper())) {
                            override fun onChange(selfChange: Boolean) {
                                trySend(read(context, userTracker.userId))
                            }
                        }
                    val userCallback =
                        object : UserTracker.Callback {
                            override fun onUserChanged(newUser: Int, userContext: Context) {
                                trySend(read(context, newUser))
                            }
                        }
                    resolver.registerContentObserver(
                        Settings.System.getUriFor(Settings.System.MEDIA_WAVEFORM_SEEKBAR),
                        false,
                        observer,
                        UserHandle.USER_ALL,
                    )
                    userTracker.addCallback(userCallback, context.mainExecutor)
                    trySend(read(context, userTracker.userId))
                    awaitClose {
                        resolver.unregisterContentObserver(observer)
                        userTracker.removeCallback(userCallback)
                    }
                }
                .distinctUntilChanged()
    }
}
