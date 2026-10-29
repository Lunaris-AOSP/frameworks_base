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

import android.graphics.Rect

object LunarisBarTransitionState {
    private data class Target(
        val owner: Any,
        val packageName: String,
        val userId: Int,
        val displayId: Int,
        val rotation: Int,
        val bounds: Rect,
    )

    @Volatile private var targets: Map<Int, Target> = emptyMap()

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
        targets =
            targets +
                (displayId to Target(owner, packageName, userId, displayId, rotation, Rect(bounds)))
    }

    @Synchronized
    fun clear(owner: Any) {
        targets = targets.filterValues { it.owner !== owner }
    }

    @JvmStatic
    fun getTarget(packageName: String?, userId: Int, displayId: Int, rotation: Int): Rect? {
        val current = targets[displayId] ?: return null
        if (
            current.packageName != packageName ||
                current.userId != userId ||
                current.displayId != displayId ||
                current.rotation != rotation
        ) {
            return null
        }
        return Rect(current.bounds)
    }
}
