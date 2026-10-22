/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.axdynamicbar.shared

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import com.android.axion.blur.AxBlurBackgroundRenderer
import com.android.axion.blur.AxBlurSurfaceDefaults
import com.android.axion.blur.LocalAxBlurEnabled
import kotlin.math.roundToInt

/**
 * Draws frost inside the surface's clip, before its tint and padding. Uses the renderer directly
 * because this branch's axBlurBackground modifier does not expose a surface alpha parameter.
 */
@Composable
internal fun Modifier.islandBlur(
    cornerRadius: Dp,
    fallbackColor: Color,
): Modifier {
    val view = LocalView.current
    val enabled = LocalAxBlurEnabled.current
    val blur = remember(view) { AxBlurBackgroundRenderer(view) }
    val radiusPx = with(LocalDensity.current) { cornerRadius.toPx() }
    val tintArgb = AxBlurSurfaceDefaults.tintColor(fallbackColor).toArgb()

    DisposableEffect(blur) {
        blur.onAttachedToWindow()
        onDispose { blur.onDetachedFromWindow() }
    }
    SideEffect {
        blur.setEnabled(enabled)
    }

    return islandBlurFrameTracking(enabled).drawWithContent {
        var drewBlur = false
        if (enabled && size.width > 0 && size.height > 0) {
            drawIntoCanvas { canvas ->
                drewBlur =
                    blur.draw(
                        canvas.nativeCanvas,
                        0,
                        0,
                        size.width.roundToInt(),
                        size.height.roundToInt(),
                        radiusPx,
                        tintArgb,
                    )
            }
        }
        if (!drewBlur) {
            // The opaque fallback is ordinary Compose content and already follows the layer fade.
            drawRoundRect(color = fallbackColor, cornerRadius = CornerRadius(radiusPx))
        }
        drawContent()
    }
}

// Keep the drawable geometry current when a parent scrolls, scales or swipes a cached layer.
private fun Modifier.islandBlurFrameTracking(enabled: Boolean): Modifier =
    this then IslandBlurFrameTrackingElement(enabled)

private data class IslandBlurFrameTrackingElement(val enabled: Boolean) :
    ModifierNodeElement<IslandBlurFrameTrackingNode>() {
    override fun create() = IslandBlurFrameTrackingNode(enabled)

    override fun update(node: IslandBlurFrameTrackingNode) {
        node.update(enabled)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "islandBlurFrameTracking"
        properties["enabled"] = enabled
    }
}

private class IslandBlurFrameTrackingNode(private var enabled: Boolean) :
    Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    DrawModifierNode,
    GlobalPositionAwareModifierNode,
    LayoutAwareModifierNode,
    ObserverModifierNode {

    private var lastCoordinates: LayoutCoordinates? = null
    private var geometry = IslandBlurFrameGeometry()
    private var hostView: View? = null
    private var observingPreDraw = false
    private var skipNextHostDirty = false
    private val preDrawListener =
        ViewTreeObserver.OnPreDrawListener {
            if (isAttached && enabled) {
                val viewDirty = hostView?.isDirty == true
                lastCoordinates?.let { updateCoordinates(it) }
                if (viewDirty) {
                    if (skipNextHostDirty) {
                        skipNextHostDirty = false
                    } else {
                        skipNextHostDirty = true
                        invalidateDraw()
                    }
                } else {
                    skipNextHostDirty = false
                }
            }
            true
        }

    override val shouldAutoInvalidate = false

    fun update(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        updatePreDrawObserver()
        invalidateDraw()
    }

    override fun onAttach() {
        onObservedReadsChanged()
    }

    override fun onDetach() {
        removePreDrawObserver()
        hostView = null
        lastCoordinates = null
        geometry = IslandBlurFrameGeometry()
        skipNextHostDirty = false
    }

    override fun onObservedReadsChanged() {
        observeReads {
            updatePreDrawObserver()
            lastCoordinates?.let { updateCoordinates(it) }
        }
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        updateCoordinates(coordinates)
    }

    override fun onRemeasured(size: IntSize) {
        val newSize = size.toSize()
        val nextGeometry = geometry.copy(size = newSize)
        if (geometry != nextGeometry) {
            geometry = nextGeometry
            invalidateDraw()
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        updateCoordinates(coordinates)
    }

    override fun ContentDrawScope.draw() {
        drawContent()
    }

    private fun updateCoordinates(coordinates: LayoutCoordinates) {
        lastCoordinates = coordinates
        val bounds = coordinates.boundsInRoot()
        val newPosition = bounds.topLeft
        val newSize = bounds.size
        val newWindowId = currentWindowId()
        val nextGeometry =
            IslandBlurFrameGeometry(position = newPosition, size = newSize, windowId = newWindowId)
        if (geometry != nextGeometry) {
            geometry = nextGeometry
            invalidateDraw()
        }
    }

    private fun currentWindowId(): Any? {
        return hostView?.windowId ?: currentValueOf(LocalView).windowId
    }

    private data class IslandBlurFrameGeometry(
        val position: Offset = Offset.Unspecified,
        val size: Size = Size.Unspecified,
        val windowId: Any? = null,
    )

    private fun updatePreDrawObserver() {
        val view = if (isAttached && enabled) currentValueOf(LocalView) else null
        if (hostView !== view) {
            removePreDrawObserver()
            hostView = view
            skipNextHostDirty = false
        }
        if (view != null && !observingPreDraw) {
            view.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            observingPreDraw = true
        }
        if (view == null) {
            removePreDrawObserver()
        }
    }

    private fun removePreDrawObserver() {
        if (!observingPreDraw) return
        hostView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
        observingPreDraw = false
    }
}
