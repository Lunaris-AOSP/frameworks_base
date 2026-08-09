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
package com.android.systemui.qs.panels.ui.compose.infinitegrid

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.ConcurrentHashMap

private object QsSettingsObserverCache {
    private data class Entry(
        var value: Any?,
        val listeners: MutableSet<(Any?) -> Unit> = ConcurrentHashMap.newKeySet(),
        var observer: ContentObserver? = null,
        var refCount: Int = 0,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    @Synchronized
    fun <T> subscribe(
        context: Context,
        key: String,
        read: (Context) -> T,
        onChange: (T) -> Unit,
    ): Pair<T, (Any?) -> Unit> {
        val appContext = context.applicationContext
        val cr = appContext.contentResolver
        val entry =
            entries.getOrPut(key) {
                Entry(value = read(appContext)).also { e ->
                    val observer =
                        object : ContentObserver(null) {
                            override fun onChange(selfChange: Boolean) {
                                appContext.mainExecutor.execute {
                                    val newValue = read(appContext)
                                    synchronized(this@QsSettingsObserverCache) {
                                        e.value = newValue
                                    }
                                    e.listeners.forEach { it(newValue) }
                                }
                            }
                        }
                    e.observer = observer
                    cr.registerContentObserver(
                        Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL
                    )
                }
            }
        entry.refCount++

        @Suppress("UNCHECKED_CAST")
        val listener: (Any?) -> Unit = { onChange(it as T) }
        entry.listeners.add(listener)

        @Suppress("UNCHECKED_CAST")
        return (entry.value as T) to listener
    }

    @Synchronized
    fun unsubscribe(context: Context, key: String, listener: (Any?) -> Unit) {
        val entry = entries[key] ?: return
        entry.listeners.remove(listener)
        entry.refCount--
        if (entry.refCount <= 0) {
            entry.observer?.let {
                context.applicationContext.contentResolver.unregisterContentObserver(it)
            }
            entries.remove(key)
        }
    }
}

@Composable
internal fun <T> rememberObservedSetting(key: String, default: T, read: (Context) -> T): T {
    val context = LocalContext.current
    var value by remember { mutableStateOf<T>(default) }

    DisposableEffect(context, key) {
        val (initial, listener) =
            QsSettingsObserverCache.subscribe(
                context = context,
                key = key,
                read = { ctx -> try { read(ctx) } catch (_: Throwable) { default } },
                onChange = { newValue -> value = newValue },
            )
        value = initial
        onDispose { QsSettingsObserverCache.unsubscribe(context, key, listener) }
    }

    return value
}

@Composable
internal fun <T> rememberObservedGlobalSetting(key: String, default: T, transform: (Int) -> T): T {
    val context = LocalContext.current
    var value by remember { mutableStateOf(default) }

    DisposableEffect(context, key) {
        fun read(): T = try {
            transform(Settings.Global.getInt(context.contentResolver, key, 0))
        } catch (_: Throwable) { default }

        value = read()
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                context.mainExecutor.execute { value = read() }
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(key), false, observer
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    return value
}
