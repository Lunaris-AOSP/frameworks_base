/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import com.android.systemui.qs.MiuiQsMotion
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

internal class MiuiQsMotionController {
    private val panels = mutableSetOf<MiuiQsPanelMotion>()
    private var interacting = false

    fun registerPanel(motion: MiuiQsPanelMotion) {
        panels.add(motion)
        if (interacting) motion.beginInteraction()
    }

    fun removePanel(motion: MiuiQsPanelMotion) {
        if (panels.remove(motion)) motion.endInteraction()
    }

    fun beginInteraction() {
        interacting = true
        panels.forEach { it.beginInteraction() }
    }

    fun endInteraction() {
        interacting = false
        panels.forEach { it.endInteraction() }
    }
}

internal val LocalMiuiQsMotionController = staticCompositionLocalOf<MiuiQsMotionController?> { null }

internal class MiuiQsPanelMotion(
    val expansion: () -> Float,
    val visible: () -> Boolean,
    val swiping: () -> Boolean,
    val gestureFromLeft: () -> Boolean,
    val viewportSize: () -> Size,
    val resetSequence: () -> Int,
) {
    val initialResetSequence = resetSequence()
    var bounds by mutableStateOf(Rect.Zero)
    var headerY by mutableFloatStateOf(0f)
        private set
    var showing by mutableStateOf(visible() && expansion() >= 1f)
    var fromLeft by mutableStateOf(gestureFromLeft())
    private var interactionResetSequence by mutableStateOf<Int?>(null)
    val interacting: Boolean
        get() = interactionResetSequence == resetSequence()
    private val grids = mutableMapOf<MiuiQsGridGeometry, Pair<Int, Float>>()
    private val animations = mutableStateMapOf<Any, ItemAnimation>()
    val animationsRunning by derivedStateOf {
        visible() && expansion() > 0f && !swiping() &&
            animations.values.any { it.running() }
    }

    fun beginInteraction() {
        if (interacting || !visible() || expansion() <= 0f || swiping()) return
        interactionResetSequence = resetSequence()
        animations.values.forEach { it.pause() }
    }

    fun endInteraction() {
        interactionResetSequence = null
    }

    fun registerAnimation(token: Any, running: () -> Boolean, pause: () -> Unit) {
        animations[token] = ItemAnimation(running, pause)
        if (interacting) pause()
    }

    fun removeAnimation(token: Any) {
        animations.remove(token)
    }

    fun updateBounds(value: Rect) {
        bounds = value
        updateHeader()
    }

    fun updateGrid(geometry: MiuiQsGridGeometry, row: Int, origin: Offset) {
        geometry.origin = origin
        geometry.placed = true
        grids[geometry] = row to origin.y
        updateHeader()
    }

    fun removeGrid(geometry: MiuiQsGridGeometry) {
        grids.remove(geometry)
        updateHeader()
    }

    private fun updateHeader() {
        headerY = grids.values.minByOrNull { it.first }?.second ?: bounds.top
    }

    private class ItemAnimation(val running: () -> Boolean, val pause: () -> Unit)
}

private class MiuiQsAnimationJobs {
    var animation: Job? = null
}

internal class MiuiQsGridGeometry {
    var origin by mutableStateOf(Offset.Zero)
    var placed by mutableStateOf(false)
    var itemBounds by mutableStateOf<Map<String, Rect>>(emptyMap())
}

private data class MiuiQsHeaderGeometryElement(
    val geometry: MiuiQsGridGeometry,
) : ModifierNodeElement<MiuiQsHeaderGeometryNode>() {
    override fun create() = MiuiQsHeaderGeometryNode(geometry)

    override fun update(node: MiuiQsHeaderGeometryNode) {
        node.updateGeometry(geometry)
    }
}

private class MiuiQsHeaderGeometryNode(
    private var geometry: MiuiQsGridGeometry,
) : Modifier.Node(), LayoutAwareModifierNode {
    fun updateGeometry(value: MiuiQsGridGeometry) {
        if (geometry === value) return
        value.origin = geometry.origin
        value.itemBounds = geometry.itemBounds
        value.placed = geometry.placed
        clearGeometry()
        geometry = value
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) {
            clearGeometry()
            return
        }
        geometry.origin = coordinates.positionInRoot()
        geometry.itemBounds =
            mapOf(
                "header" to
                    Rect(0f, 0f, coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
            )
        geometry.placed = true
    }

    override fun onRemeasured(size: IntSize) {
        geometry.placed = false
    }

    override fun onDetach() {
        clearGeometry()
    }

    private fun clearGeometry() {
        geometry.placed = false
        geometry.itemBounds = emptyMap()
    }
}

internal val LocalMiuiQsHeaderMotion = staticCompositionLocalOf<MiuiQsPanelMotion?> { null }

internal fun Modifier.miuiQsMotionBounds(motion: MiuiQsPanelMotion?): Modifier =
    if (motion == null) this
    else onPlaced {
        val origin = it.positionInRoot()
        motion.updateBounds(
            Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
        )
    }

private data class MiuiQsAppearanceInput(
    val expansion: Float,
    val visible: Boolean,
    val swiping: Boolean,
    val fromLeft: Boolean,
    val resetSequence: Int,
)

@Composable
internal fun rememberMiuiQsPanelMotion(
    expansion: () -> Float,
    visible: () -> Boolean,
    swiping: () -> Boolean,
    fromLeft: () -> Boolean,
    resetSequence: () -> Int = { 0 },
): MiuiQsPanelMotion {
    val currentExpansion by rememberUpdatedState(expansion)
    val currentVisible by rememberUpdatedState(visible)
    val currentSwiping by rememberUpdatedState(swiping)
    val currentFromLeft by rememberUpdatedState(fromLeft)
    val currentResetSequence by rememberUpdatedState(resetSequence)
    val view = LocalView.current
    val motion = remember(view) {
        MiuiQsPanelMotion(
            expansion = { currentExpansion() },
            visible = { currentVisible() },
            swiping = { currentSwiping() },
            gestureFromLeft = { currentFromLeft() },
            viewportSize = {
                val metrics = view.resources.displayMetrics
                Size(
                    (if (view.width > 0) view.width else metrics.widthPixels).toFloat(),
                    maxOf(view.height, metrics.heightPixels).toFloat(),
                )
            },
            resetSequence = { currentResetSequence() },
        )
    }
    val controller = LocalMiuiQsMotionController.current
    DisposableEffect(motion, controller) {
        controller?.registerPanel(motion)
        onDispose { controller?.removePanel(motion) }
    }
    LaunchedEffect(motion) {
        val appearance = MiuiQsMotion.Appearance()
        var lastResetSequence = motion.initialResetSequence
        snapshotFlow {
                MiuiQsAppearanceInput(
                    motion.expansion(), motion.visible(), motion.swiping(),
                    motion.gestureFromLeft(),
                    motion.resetSequence(),
                )
            }
            .collect { input ->
                if (input.resetSequence != lastResetSequence) {
                    appearance.update(0f, false, false)
                    motion.fromLeft = input.fromLeft
                    motion.showing = false
                    motion.endInteraction()
                    lastResetSequence = input.resetSequence
                }
                val showing =
                    appearance.update(
                        input.expansion, input.visible, input.swiping
                    )
                if (!showing || input.swiping) motion.endInteraction()
                if (input.expansion <= 0f || (showing && !motion.showing)) {
                    motion.fromLeft = input.fromLeft
                }
                motion.showing = showing
            }
    }
    return motion
}

@Composable
internal fun Modifier.miuiQsElementMotion(
    motion: MiuiQsPanelMotion,
    geometry: MiuiQsGridGeometry,
    itemId: String,
    canAnimate: () -> Boolean,
    rowTranslationX: () -> Float,
    originY: Float = motion.headerY,
): Modifier {
    val resetSequence = motion.resetSequence()
    val previousResetSequence = remember(motion) { intArrayOf(resetSequence) }
    val freshCycle = previousResetSequence[0] != resetSequence
    SideEffect { previousResetSequence[0] = resetSequence }
    return key(motion, resetSequence) {
        miuiQsElementMotionLayer(
            motion, geometry, itemId, canAnimate, rowTranslationX, originY, freshCycle
        )
    }
}

@Composable
private fun Modifier.miuiQsElementMotionLayer(
    motion: MiuiQsPanelMotion,
    geometry: MiuiQsGridGeometry,
    itemId: String,
    canAnimate: () -> Boolean,
    rowTranslationX: () -> Float,
    originY: Float,
    freshCycle: Boolean,
): Modifier {
    val itemBounds = geometry.itemBounds[itemId]
    val panel = motion.bounds
    val ready =
        geometry.placed && itemBounds != null && itemBounds.width > 0f && itemBounds.height > 0f &&
            panel.width > 0f && panel.height > 0f
    val withinViewport =
        itemBounds != null &&
            geometry.origin.x + itemBounds.left < panel.right &&
            geometry.origin.x + itemBounds.right > panel.left &&
            geometry.origin.y + itemBounds.top < panel.bottom &&
            geometry.origin.y + itemBounds.bottom > panel.top
    val center = geometry.origin + (itemBounds?.center ?: Offset.Zero)
    val fromLeft = if (freshCycle) motion.gestureFromLeft() else motion.fromLeft
    val cornerX = if (fromLeft) panel.left else panel.right
    val viewport = motion.viewportSize()
    val distance =
        if (ready) {
            MiuiQsMotion.distance(
                center.x, center.y, cornerX, originY,
                if (viewport.width > 0f) viewport.width else panel.width,
                if (viewport.height > 0f) viewport.height else panel.height,
            )
        } else 0f
    val currentCanAnimate by rememberUpdatedState(canAnimate)
    val currentReady by rememberUpdatedState(ready)
    val currentVisibleInViewport by rememberUpdatedState(!ready || withinViewport)
    val active by remember(motion) {
        derivedStateOf { currentCanAnimate() && currentVisibleInViewport }
    }
    val initial = remember(motion) {
        when {
            motion.expansion() <= 0f -> 0f
            motion.interacting || motion.swiping() || !currentCanAnimate() -> 1f
            freshCycle -> 0f
            motion.expansion() >= 1f -> 1f
            else -> 0f
        }
    }
    val opacity = remember(motion) { Animatable(initial, MiuiQsMotion.VISIBILITY_THRESHOLD) }
    val scale = remember(motion) { Animatable(initial, MiuiQsMotion.VISIBILITY_THRESHOLD) }
    val horizontal = remember(motion) { Animatable(initial, MiuiQsMotion.VISIBILITY_THRESHOLD) }
    val vertical = remember(motion) { Animatable(initial, MiuiQsMotion.VISIBILITY_THRESHOLD) }
    val swiping by remember(motion) { derivedStateOf { motion.swiping() } }
    val closed by remember(motion) { derivedStateOf { motion.expansion() <= 0f } }
    val fullyExpanded by remember(motion) { derivedStateOf { motion.expansion() >= 1f } }
    val target by remember(motion) {
        derivedStateOf {
            MiuiQsMotion.itemTarget(
                closed, fullyExpanded, motion.showing, active, currentCanAnimate(), swiping
            )
        }
    }
    val resetNow by remember(motion) {
        derivedStateOf { closed || swiping || !active }
    }
    val jobs = remember(motion) { MiuiQsAnimationJobs() }
    var delaying by remember(motion) { mutableStateOf(false) }
    val snapProgress: suspend (Float) -> Unit = remember(motion) {
        { value ->
            coroutineScope {
                launch(start = CoroutineStart.UNDISPATCHED) { opacity.snapTo(value) }
                launch(start = CoroutineStart.UNDISPATCHED) { scale.snapTo(value) }
                launch(start = CoroutineStart.UNDISPATCHED) { horizontal.snapTo(value) }
                launch(start = CoroutineStart.UNDISPATCHED) { vertical.snapTo(value) }
            }
        }
    }
    val running = remember(motion) {
        derivedStateOf {
            active && currentReady &&
                (delaying || opacity.isRunning || scale.isRunning ||
                    horizontal.isRunning || vertical.isRunning || opacity.value != target ||
                    scale.value != target ||
                    horizontal.value != target || vertical.value != target)
        }
    }
    DisposableEffect(motion) {
        val token = Any()
        motion.registerAnimation(
            token,
            running = { running.value },
            pause = {
                jobs.animation?.cancel()
                delaying = false
            },
        )
        onDispose { motion.removeAnimation(token) }
    }

    LaunchedEffect(motion, ready, target, resetNow, motion.interacting) {
        jobs.animation = currentCoroutineContext().job
        if (motion.interacting && !resetNow) return@LaunchedEffect
        if (resetNow) {
            snapProgress(target)
        } else if (ready &&
            (opacity.value != target || scale.value != target || horizontal.value != target ||
            vertical.value != target || opacity.isRunning || scale.isRunning ||
            horizontal.isRunning || vertical.isRunning)
        ) {
            delaying = true
            try {
                val revealing = target == 1f
                if (revealing && opacity.value == 0f && scale.value == 0f &&
                    horizontal.value == 0f && vertical.value == 0f
                ) delay(MiuiQsMotion.showDelayMillis(distance))
                val appearance = MiuiQsMotion.appearanceSpring(distance, revealing).spec()
                coroutineScope {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        opacity.animateTo(target, appearance)
                    }
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        scale.animateTo(target, appearance)
                    }
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        horizontal.animateTo(target, MiuiQsMotion.horizontalSpring(distance).spec())
                    }
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        vertical.animateTo(target, MiuiQsMotion.verticalSpring(distance).spec())
                    }
                }
            } finally {
                delaying = false
            }
        }
    }
    return graphicsLayer {
        val holdExpanded = motion.swiping() || !active
        alpha =
            if (ready && !withinViewport && motion.animationsRunning) 0f
            else if (holdExpanded) 1f
            else if (ready || initial == 1f) MiuiQsMotion.clamp(opacity.value)
            else 0f
        scaleX = if (holdExpanded) 1f else MiuiQsMotion.scale(scale.value)
        scaleY = scaleX
        transformOrigin = TransformOrigin.Center
        translationX =
            (if (holdExpanded) 0f
            else MiuiQsMotion.translation(center.x, cornerX, horizontal.value)) + rowTranslationX()
        translationY =
            if (holdExpanded) 0f
            else MiuiQsMotion.translation(center.y, originY, vertical.value)
    }
}

@Composable
internal fun Modifier.miuiQsHeaderMotion(
    motion: MiuiQsPanelMotion? =
        LocalMiuiQsHeaderMotion.current ?: LocalLunarisQsElementMotion.current?.miuiAnimation,
): Modifier {
    if (motion == null) return this
    val geometry = remember(motion) { MiuiQsGridGeometry() }
    return then(MiuiQsHeaderGeometryElement(geometry))
        .then(
            Modifier.miuiQsElementMotion(
                motion = motion,
                geometry = geometry,
                itemId = "header",
                canAnimate = { true },
                rowTranslationX = { 0f },
                originY = motion.bounds.top,
            )
        )
}

private fun MiuiQsMotion.SpringParameters.spec(): SpringSpec<Float> =
    spring(
        dampingRatio = dampingRatio,
        stiffness = stiffness,
        visibilityThreshold = MiuiQsMotion.VISIBILITY_THRESHOLD,
    )
