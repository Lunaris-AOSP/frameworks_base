/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.compose.animation

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.IntSize
import com.android.systemui.animation.DynamicExpandable
import com.android.systemui.animation.TransitionSource

internal fun Modifier.registerSource(
    source: TransitionSource,
    expandable: DynamicExpandable?,
): Modifier = if (expandable == null) this else then(RegisterTransitionSource(source, expandable))

private data class RegisterTransitionSource(
    val source: TransitionSource,
    val expandable: DynamicExpandable,
) : ModifierNodeElement<TransitionSourceNode>() {
    override fun create() = TransitionSourceNode(source, expandable)

    override fun update(node: TransitionSourceNode) {
        node.update(source, expandable)
    }
}

private class TransitionSourceNode(
    private var source: TransitionSource,
    private var expandable: DynamicExpandable,
) : Modifier.Node(), LayoutAwareModifierNode {
    private var isPlaced = false

    fun update(source: TransitionSource, expandable: DynamicExpandable) {
        if (this.source === source && this.expandable === expandable) return
        this.expandable.removeSource(this.source)
        this.source = source
        this.expandable = expandable
        if (isAttached && isPlaced) expandable.addSource(source)
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        isPlaced = coordinates.isAttached
        if (isPlaced) expandable.addSource(source)
    }

    override fun onRemeasured(size: IntSize) {
        isPlaced = false
        expandable.removeSource(source)
    }

    override fun onDetach() {
        isPlaced = false
        expandable.removeSource(source)
    }
}
