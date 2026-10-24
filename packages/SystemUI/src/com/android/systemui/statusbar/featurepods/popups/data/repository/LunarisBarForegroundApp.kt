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

package com.android.systemui.statusbar.featurepods.popups.data.repository

import android.app.ActivityManager
import android.app.ActivityTaskManager
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull

internal data class LunarisBarForegroundApp(val packageName: String, val userId: Int)

internal fun observeLunarisBarForegroundApp() =
    callbackFlow {
            val listener =
                object : TaskStackChangeListener {
                    override fun onTaskStackChanged() {
                        trySend(Unit)
                    }

                    override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
                        trySend(Unit)
                    }

                    override fun onTaskRemoved(taskId: Int) {
                        trySend(Unit)
                    }

                    override fun onActivityUnpinned() {
                        trySend(Unit)
                    }

                    override fun onTaskDisplayChanged(taskId: Int, newDisplayId: Int) {
                        trySend(Unit)
                    }
                }
            val listeners = TaskStackChangeListeners.getInstance()
            listeners.registerTaskStackListener(listener)
            trySend(Unit)
            awaitClose { listeners.unregisterTaskStackListener(listener) }
        }
        .buffer(Channel.CONFLATED)
        .mapNotNull {
            runCatching {
                    ActivityTaskManager.getService().focusedRootTaskInfo?.let { task ->
                        task.topActivity?.let {
                            LunarisBarForegroundApp(it.packageName, task.userId)
                        }
                    }
                }
                .getOrNull()
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
