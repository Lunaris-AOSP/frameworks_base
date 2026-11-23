/* Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.statusbar.featurepods.popups.ui.compose

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.android.internal.util.lunaris.LunarisBarSettings
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipId
import com.android.systemui.statusbar.featurepods.popups.ui.model.PopupChipModel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal val LunarisBarState.useOriginalMotion: Boolean
    get() =
        !alternativeAnimation &&
            settings.animationStyle == 0 &&
            !settings.isReducedMotion &&
            ValueAnimator.areAnimatorsEnabled()

internal fun lunarisBarOriginalSpring(
    settings: LunarisBarSettings,
    stiffness: Float = 430f,
    damping: Float = 0.76f,
    threshold: Float = 0.001f,
): SpringSpec<Float> =
    spring(
        dampingRatio =
            when (settings.bounce) {
                0 -> 1f
                2 -> (damping - 0.06f).coerceAtLeast(0.68f)
                else -> damping
            },
        stiffness = stiffness * settings.animationSpeed * settings.animationSpeed,
        visibilityThreshold = threshold,
    )

internal class LunarisBarOriginalSpring(initialValue: Float) {
    private val animation = Animatable(initialValue)
    private var retainedVelocity = 0f
    val value: Float
        get() = animation.value

    fun asState(): State<Float> = animation.asState()

    suspend fun snapTo(value: Float) {
        animation.snapTo(value)
        retainedVelocity = 0f
    }

    suspend fun animateTo(target: Float, spec: SpringSpec<Float>, impulse: Float = 0f) {
        animation.animateTo(target, spec, initialVelocity = retainedVelocity + impulse) {
            retainedVelocity = velocity
        }
        retainedVelocity = 0f
    }
}

internal class LunarisBarOriginalPopupState {
    var launchChipId: PopupChipId? = null
    var launchScaleX = 1f
    var launchScaleY = 1f
    var chipId by mutableStateOf<PopupChipId?>(null)
    var visible by mutableStateOf(false)
    var width by mutableStateOf<State<Float>?>(null)
    var height by mutableStateOf<State<Float>?>(null)
    var handoffFraction by mutableFloatStateOf(0.12f)
    var returnProgress by mutableFloatStateOf(0f)

    fun returningChip(id: PopupChipId): Float =
        if (chipId == id && !visible && width != null && height != null) {
            maxOf(
                returnProgress,
                lunarisBarOriginalReturnFraction(width!!.value, height!!.value, handoffFraction),
            )
        } else 0f
}

internal fun lunarisBarOriginalReturnFraction(
    width: Float,
    height: Float,
    threshold: Float,
): Float {
    val fraction = ((threshold - maxOf(width, height)) / threshold).coerceIn(0f, 1f)
    return fraction * fraction * (3f - 2f * fraction)
}

internal val LocalLunarisBarOriginalPopupState = compositionLocalOf {
    LunarisBarOriginalPopupState()
}

internal class LunarisBarOriginalShape(private val radius: Dp, private val roundness: Float) :
    Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = minOf(with(density) { radius.toPx() }, size.minDimension / 2f).coerceAtLeast(0f)
        val handle = r * (0.49477f + (0.5522848f - 0.49477f) * roundness.coerceIn(0f, 1f))
        val w = size.width
        val h = size.height
        return Outline.Generic(
            Path().apply {
                moveTo(r, 0f)
                lineTo(w - r, 0f)
                cubicTo(w - r + handle, 0f, w, r - handle, w, r)
                lineTo(w, h - r)
                cubicTo(w, h - r + handle, w - r + handle, h, w - r, h)
                lineTo(r, h)
                cubicTo(r - handle, h, 0f, h - r + handle, 0f, h - r)
                lineTo(0f, r)
                cubicTo(0f, r - handle, r - handle, 0f, r, 0f)
                close()
            }
        )
    }
}

@Composable
internal fun LunarisBarOriginalChipSurface(
    progress: State<Float>,
    diameter: Dp,
    resting: Boolean,
    modifier: Modifier,
    onMainSizeChanged: (IntSize) -> Unit,
    compactContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val settings = LocalLunarisBarState.current.settings
    val fraction = progress.value
    val blend = fraction.coerceIn(0f, 1f)
    val radius = diameter / 2f + (settings.cornerRadius.dp - diameter / 2f) * blend
    val surfaceShape = remember(radius, blend) { LunarisBarOriginalShape(radius, 1f - blend) }
    var mainSize by remember { mutableStateOf(IntSize.Zero) }
    val mainWidth = remember { LunarisBarOriginalSpring(0f) }
    val mainHeight = remember { LunarisBarOriginalSpring(0f) }
    LaunchedEffect(mainSize, settings.animationSpeed, settings.bounce) {
        if (mainSize.width <= 0 || mainSize.height <= 0) return@LaunchedEffect
        coroutineScope {
            launch {
                if (mainWidth.value == 0f) mainWidth.snapTo(mainSize.width.toFloat())
                else
                    mainWidth.animateTo(
                        mainSize.width.toFloat(),
                        lunarisBarOriginalSpring(settings, threshold = 0.5f),
                    )
            }
            launch {
                if (mainHeight.value == 0f) mainHeight.snapTo(mainSize.height.toFloat())
                else
                    mainHeight.animateTo(
                        mainSize.height.toFloat(),
                        lunarisBarOriginalSpring(settings, threshold = 0.5f),
                    )
            }
        }
    }
    Layout(
        modifier = modifier,
        content = {
            LunarisBarSurface(
                mini = true,
                resting = resting,
                shape = surfaceShape,
                restingProgress = if (resting) blend else null,
            ) {}
            Box(if (blend < 0.5f) Modifier.clearAndSetSemantics {} else Modifier) { content() }
            Box(if (blend >= 0.5f) Modifier.clearAndSetSemantics {} else Modifier) {
                compactContent()
            }
        },
    ) { measurables, constraints ->
        val main = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        mainSize = IntSize(main.width, main.height)
        onMainSizeChanged(mainSize)
        val compactSize = diameter.roundToPx().coerceAtLeast(1)
        val compact = measurables[2].measure(Constraints.fixed(compactSize, compactSize))
        val fullWidth = if (mainWidth.value > 0f) mainWidth.value else main.width.toFloat()
        val fullHeight = if (mainHeight.value > 0f) mainHeight.value else main.height.toFloat()
        val width =
            constraints.constrainWidth(
                (compactSize + (fullWidth - compactSize) * fraction).roundToInt().coerceAtLeast(1)
            )
        val height =
            constraints.constrainHeight(
                (compactSize + (fullHeight - compactSize) * fraction).roundToInt().coerceAtLeast(1)
            )
        val surface = measurables[0].measure(Constraints.fixed(width, height))
        layout(width, height) {
            surface.place(0, 0)
            val mainLeft = (width - main.width) / 2f
            val mainTop = (height - main.height) / 2f
            main.placeWithLayer(mainLeft.roundToInt(), mainTop.roundToInt()) {
                translationX = mainLeft - mainLeft.roundToInt()
                translationY = mainTop - mainTop.roundToInt()
                alpha = ((blend - 0.35f) / 0.65f).coerceIn(0f, 1f)
                val contentScale =
                    minOf(
                            width.toFloat() / main.width.coerceAtLeast(1),
                            height.toFloat() / main.height.coerceAtLeast(1),
                        )
                        .coerceAtLeast(0.01f)
                scaleX = contentScale
                scaleY = contentScale
                val sx = scaleX
                val sy = scaleY
                this.shape =
                    object : Shape {
                        override fun createOutline(
                            size: Size,
                            layoutDirection: LayoutDirection,
                            density: Density,
                        ): Outline {
                            val outline =
                                surfaceShape.createOutline(
                                    Size(width.toFloat(), height.toFloat()),
                                    layoutDirection,
                                    density,
                                ) as Outline.Generic
                            return Outline.Generic(
                                Path().apply {
                                    addPath(outline.path)
                                    transform(
                                        Matrix().apply {
                                            translate(size.width / 2f, size.height / 2f)
                                            scale(1f / sx, 1f / sy)
                                            translate(-width / 2f, -height / 2f)
                                        }
                                    )
                                }
                            )
                        }
                    }
                clip = true
            }
            val compactLeft = (width - compact.width) / 2f
            val compactTop = (height - compact.height) / 2f
            compact.placeWithLayer(compactLeft.roundToInt(), compactTop.roundToInt()) {
                translationX = compactLeft - compactLeft.roundToInt()
                translationY = compactTop - compactTop.roundToInt()
                alpha = (1f - blend / 0.65f).coerceIn(0f, 1f)
                scaleX = 1f + 0.08f * blend
                scaleY = 1f - 0.04f * blend
            }
        }
    }
}

private class OriginalChipMotion(main: Boolean, side: Float) {
    val expansion = LunarisBarOriginalSpring(if (main) 1f else 0f)
    val slot = LunarisBarOriginalSpring(if (main) 0f else side)
    val visibility = LunarisBarOriginalSpring(0f)
}

@Composable
internal fun LunarisBarOriginalChips(
    chips: List<PopupChipModel.Shown>,
    selectedChip: PopupChipModel.Shown,
    companionChip: PopupChipModel.Shown?,
    companionOnRight: Boolean,
    balanceCompanion: Boolean,
    diameter: Dp,
    collapseForShade: Boolean,
    onGesture: (Float, Float) -> Unit,
    content: @Composable (PopupChipModel.Shown, State<Float>, Boolean, (IntSize) -> Unit) -> Unit,
) {
    val settings = LocalLunarisBarState.current.settings
    val scope = rememberCoroutineScope()
    var retained by remember { mutableStateOf<List<PopupChipModel.Shown>>(emptyList()) }
    val desired = listOfNotNull(selectedChip, companionChip)
    val rendered = (desired + retained).distinctBy { it.chipId }
    val currentDesired by rememberUpdatedState(desired.map { it.chipId })
    val currentGesture by rememberUpdatedState(onGesture)
    val sizes = remember { mutableStateMapOf<PopupChipId, IntSize>() }
    val side = if (companionOnRight) 1f else -1f
    var releaseVelocity by remember { mutableFloatStateOf(0f) }
    val width = remember { LunarisBarOriginalSpring(0f) }
    val targetWidth = (sizes[selectedChip.chipId]?.width ?: 0).toFloat()
    LaunchedEffect(targetWidth, settings) {
        if (targetWidth <= 0f) return@LaunchedEffect
        if (width.value == 0f) width.snapTo(targetWidth)
        else width.animateTo(targetWidth, lunarisBarOriginalSpring(settings, threshold = 0.5f))
    }
    val reserve by
        animateFloatAsState(
            if (companionChip != null) 1f else 0f,
            lunarisBarOriginalSpring(settings),
            label = "lunarisbar_companion_space",
        )
    val motions = remember { mutableMapOf<PopupChipId, OriginalChipMotion>() }
    LaunchedEffect(desired.map { it.chipId }) { retained = rendered }
    val follow = remember { LunarisBarOriginalSpring(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragOriginX by remember { mutableFloatStateOf(0f) }
    var gestureX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var followJob by remember { mutableStateOf<Job?>(null) }
    val tracker = remember { VelocityTracker() }
    Layout(
        modifier =
            Modifier.pointerInput(collapseForShade, settings.animationSpeed, settings.bounce) {
                if (collapseForShade) return@pointerInput
                val maxFollow = 40.dp.toPx()
                detectDragGestures(
                    onDragStart = {
                        followJob?.cancel()
                        dragging = true
                        dragOriginX = follow.value
                        dragX = dragOriginX
                        gestureX = 0f
                        dragY = 0f
                        tracker.resetTracking()
                    },
                    onDrag = { change, amount ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        gestureX += amount.x
                        val offset = gestureX * 0.5f
                        dragX = dragOriginX + offset / (1f + abs(offset) / maxFollow)
                        dragY += amount.y
                        change.consume()
                    },
                    onDragEnd = {
                        releaseVelocity = tracker.calculateVelocity().x.coerceIn(-4000f, 4000f)
                        val x = gestureX
                        val y = dragY
                        val offset = dragX
                        followJob =
                            scope.launch {
                                follow.snapTo(offset)
                                dragging = false
                                currentGesture(x, y)
                                follow.animateTo(
                                    0f,
                                    lunarisBarOriginalSpring(settings, 520f, threshold = 0.5f),
                                    (releaseVelocity * 0.5f).coerceIn(-700f, 700f),
                                )
                                releaseVelocity = 0f
                            }
                    },
                    onDragCancel = {
                        releaseVelocity = 0f
                        followJob =
                            scope.launch {
                                follow.snapTo(dragX)
                                dragging = false
                                follow.animateTo(
                                    0f,
                                    lunarisBarOriginalSpring(settings, 520f, threshold = 0.5f),
                                )
                            }
                    },
                )
            },
        content = {
            rendered.forEach { chip ->
                key(chip.chipId) {
                    val main = chip.chipId == selectedChip.chipId
                    val peek = chip.chipId == companionChip?.chipId
                    val motion = remember { OriginalChipMotion(main, side) }
                    motions[chip.chipId] = motion
                    LaunchedEffect(main, peek, side, settings) {
                        val impulse =
                            (abs(releaseVelocity) / width.value.coerceAtLeast(100f)).coerceAtMost(
                                2f
                            )
                        coroutineScope {
                            launch {
                                motion.expansion.animateTo(
                                    if (main) 1f else 0f,
                                    lunarisBarOriginalSpring(settings, 440f, 0.78f),
                                    when {
                                        main && motion.expansion.value < 1f -> impulse
                                        !main && motion.expansion.value > 0f -> -impulse
                                        else -> 0f
                                    },
                                )
                            }
                            launch {
                                val target = if (main) 0f else if (peek) side else -side
                                motion.slot.animateTo(
                                    target,
                                    lunarisBarOriginalSpring(settings, 380f, 0.82f),
                                    sign(target - motion.slot.value) * impulse,
                                )
                            }
                            launch {
                                motion.visibility.animateTo(
                                    if (main || peek) 1f else 0f,
                                    lunarisBarOriginalSpring(settings, 600f, 1f),
                                )
                            }
                        }
                        if (!main && !peek && chip.chipId !in currentDesired) {
                            retained = retained.filter { it.chipId != chip.chipId }
                            sizes.remove(chip.chipId)
                            motions.remove(chip.chipId)
                        }
                    }
                    Box(
                        Modifier.graphicsLayer { alpha = motion.visibility.value.coerceIn(0f, 1f) }
                    ) {
                        content(
                            chips.firstOrNull { it.chipId == chip.chipId } ?: chip,
                            motion.expansion.asState(),
                            peek,
                        ) {
                            sizes[chip.chipId] = it
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val compact = diameter.toPx()
        val gap = DynamicIslandCompanionGap.toPx()
        val reserved = (compact + gap) * reserve.coerceIn(0f, 1f)
        val spaceCount = if (balanceCompanion) 2 else 1
        val children =
            measurables.map {
                it.measure(
                    constraints.copy(
                        minWidth = 0,
                        maxWidth =
                            (constraints.maxWidth - reserved * spaceCount)
                                .roundToInt()
                                .coerceAtLeast(1),
                    )
                )
            }
        val mainWidth =
            if (width.value > 0f) width.value.coerceAtLeast(1f)
            else (sizes[selectedChip.chipId]?.width ?: compact.roundToInt()).toFloat()
        val rowWidth = constraints.constrainWidth((mainWidth + reserved * spaceCount).roundToInt())
        val height = constraints.constrainHeight(children.maxOfOrNull { it.height } ?: 0)
        val center =
            if (balanceCompanion) rowWidth / 2f
            else if (companionOnRight) mainWidth / 2f else reserved + mainWidth / 2f
        layout(rowWidth, height) {
            children.forEachIndexed { index, child ->
                val chip = rendered[index]
                val motion = motions.getValue(chip.chipId)
                val x =
                    center + motion.slot.value * (mainWidth / 2f + gap + compact / 2f) -
                        child.width / 2f
                val y = (height - child.height) / 2f
                child.placeWithLayer(
                    x.roundToInt(),
                    y.roundToInt(),
                    zIndex = if (chip.chipId == selectedChip.chipId) 1f else 0f,
                ) {
                    translationX = x - x.roundToInt() + if (dragging) dragX else follow.value
                    translationY = y - y.roundToInt()
                    transformOrigin = TransformOrigin.Center
                }
            }
        }
    }
}
