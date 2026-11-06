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

import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object LunarisBarTransitionState {
    private data class Target(
        val owner: Any,
        val packageName: String,
        val userId: Int,
        val displayId: Int,
        val rotation: Int,
        val bounds: Rect,
        val lastRequestUptime: Long? = null,
    )

    @Volatile private var targets: Map<Int, Target> = emptyMap()
    private val _targetRequestVersion = MutableStateFlow(0L)
    val targetRequestVersion = _targetRequestVersion.asStateFlow()

    @Synchronized
    fun update(
        owner: Any,
        packageName: String,
        userId: Int,
        displayId: Int,
        rotation: Int,
        bounds: Rect,
    ) {
        if (bounds.isEmpty || packageName.isEmpty() || userId < 0) {
            clear(owner)
            return
        }
        val lastRequestUptime =
            targets[displayId]
                ?.takeIf { it.owner === owner && it.matches(packageName, userId, rotation) }
                ?.lastRequestUptime
        targets =
            targets +
                (displayId to
                    Target(
                        owner,
                        packageName,
                        userId,
                        displayId,
                        rotation,
                        Rect(bounds),
                        lastRequestUptime,
                    ))
    }

    @Synchronized
    fun clear(owner: Any) {
        targets = targets.filterValues { it.owner !== owner }
    }

    @JvmStatic
    @Synchronized
    fun getTarget(packageName: String?, userId: Int, displayId: Int, rotation: Int): Rect? {
        val current = targets[displayId] ?: return null
        if (!current.matches(packageName, userId, rotation)) {
            return null
        }
        targets =
            targets + (displayId to current.copy(lastRequestUptime = SystemClock.uptimeMillis()))
        _targetRequestVersion.value += 1L
        return Rect(current.bounds)
    }

    @Synchronized
    fun consumeTargetRequest(
        packageName: String?,
        userId: Int,
        displayId: Int,
        rotation: Int,
    ): Boolean {
        val current = targets[displayId] ?: return false
        if (!current.matches(packageName, userId, rotation)) return false
        val requestedAt = current.lastRequestUptime ?: return false
        targets = targets + (displayId to current.copy(lastRequestUptime = null))
        val timeout = (1500L * ValueAnimator.getDurationScale().coerceAtLeast(1f)).toLong()
        return SystemClock.uptimeMillis() - requestedAt in 0L until timeout
    }

    private fun Target.matches(packageName: String?, userId: Int, rotation: Int): Boolean =
        this.packageName == packageName && this.userId == userId && this.rotation == rotation
}
