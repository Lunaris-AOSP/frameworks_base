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

package com.android.systemui.statusbar.systemstatusicons

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository that exposes the currently active status-bar icon slot-name ordering.
 */
@SysUISingleton
class StatusBarIconOrderRepository
@Inject
constructor(@Application private val context: Context) {

    /** Callback invoked on the **main thread** whenever the icon ordering changes. */
    fun interface OnIconOrderChangedListener {
        fun onIconOrderChanged(newSlotNames: Array<String>)
    }

    private val _iconSlotNamesFlow = MutableStateFlow(resolveIconSlotNames())

    private val listeners = CopyOnWriteArrayList<OnIconOrderChangedListener>()

    private val settingObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val newSlotNames = resolveIconSlotNames()
                _iconSlotNamesFlow.value = newSlotNames
                for (listener in listeners) {
                    listener.onIconOrderChanged(newSlotNames)
                }
            }
        }

    init {
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.STATUS_BAR_ICON_ORDER_LEGACY),
            /* notifyForDescendants= */ false,
            settingObserver,
        )
    }

    val iconSlotNamesFlow: StateFlow<Array<String>>
        get() = _iconSlotNamesFlow.asStateFlow()

    fun getCurrentIconSlotNames(): Array<String> = _iconSlotNamesFlow.value

    fun addOnIconOrderChangedListener(listener: OnIconOrderChangedListener) {
        listeners.add(listener)
    }

    /** Removes a previously registered [listener]. No-op if not registered. */
    fun removeOnIconOrderChangedListener(listener: OnIconOrderChangedListener) {
        listeners.remove(listener)
    }

    private fun resolveIconSlotNames(): Array<String> {
        val useLegacy =
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.STATUS_BAR_ICON_ORDER_LEGACY,
                /* def= */ LEGACY_DISABLED,
            ) == LEGACY_ENABLED

        val arrayResId =
            if (useLegacy) {
                com.android.internal.R.array.config_statusBarIconsLegacy
            } else {
                com.android.internal.R.array.config_statusBarIcons
            }

        return context.resources.getStringArray(arrayResId)
    }

    companion object {
        private const val LEGACY_DISABLED = 0
        private const val LEGACY_ENABLED = 1
    }
}
