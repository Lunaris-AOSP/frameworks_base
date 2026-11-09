/*
 * Copyright (C) 2026 Lunaris AOSP
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

package com.android.systemui.qs.composefragment.mist

import kotlin.math.abs

internal class QsTilePanelSwipeGesture(private val touchSlop: Float) {
    private var downX = 0f
    private var downY = 0f
    private var startedOnTilePager = false
    private var canSwipeFromTiles = false
    private var directionResolved = false

    var disallowParentIntercept: Boolean? = null
        private set

    fun onDown(x: Float, y: Float) {
        reset()
        downX = x
        downY = y
    }

    fun onTilePagerDown(canSwipeToNotifications: Boolean) {
        startedOnTilePager = true
        canSwipeFromTiles = canSwipeToNotifications
    }

    fun onMove(x: Float, y: Float) {
        if (!startedOnTilePager || directionResolved) return
        val dx = x - downX
        val dy = y - downY
        if (abs(dy) > touchSlop && abs(dy) >= abs(dx)) {
            directionResolved = true
        } else if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f) {
            directionResolved = true
            disallowParentIntercept = !(canSwipeFromTiles && dx > 0f)
        }
    }

    fun reset() {
        startedOnTilePager = false
        canSwipeFromTiles = false
        directionResolved = false
        disallowParentIntercept = null
    }
}
