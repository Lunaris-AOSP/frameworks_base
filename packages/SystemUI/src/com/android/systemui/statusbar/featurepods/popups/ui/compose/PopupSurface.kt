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

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.axion.blur.AxBlurBackgroundRenderer
import com.android.axion.blur.LocalAxBlurEnabled
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.observeDynamicIslandPopupColorMode
import com.android.systemui.statusbar.featurepods.popups.shared.DynamicIslandFeatureSettings.readDynamicIslandPopupColorMode
import kotlin.math.roundToInt

internal class PopupBlurHost(context: Context) : View(context) {
    private val blur = AxBlurBackgroundRenderer(this)
    private val bounds = RectF()
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shape: Shape? = null
    private var shapeDensity = Density(context.resources.displayMetrics.density)
    private var shapeLayoutDirection = LayoutDirection.Ltr
    private var clipPath: AndroidPath? = null
    private var cornerRadiusPx = 0f
    private var overlayColor = 0
    private var surfaceAlpha = 255

    fun updateAppearance(
        shape: Shape,
        density: Density,
        layoutDirection: LayoutDirection,
        overlayColor: Int,
        fallbackColor: Int,
        alpha: Float,
        enabled: Boolean,
    ) {
        if (
            this.shape != shape ||
                shapeDensity != density ||
                shapeLayoutDirection != layoutDirection
        ) {
            this.shape = shape
            shapeDensity = density
            shapeLayoutDirection = layoutDirection
            clipPath = null
        }
        this.overlayColor = overlayColor
        fallbackPaint.color = fallbackColor
        surfaceAlpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        blur.setSurfaceAlpha(surfaceAlpha)
        blur.setEnabled(enabled && surfaceAlpha > 0)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath = null
        if (w <= 0 || h <= 0) blur.clear()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blur.onAttachedToWindow()
        (parent as? ViewGroup)?.layoutTransition = null
    }

    override fun onDetachedFromWindow() {
        blur.onDetachedFromWindow()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        blur.onVisibilityAggregated(isVisible)
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        blur.verifyDrawable(who) || super.verifyDrawable(who)

    override fun draw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || surfaceAlpha == 0) return
        val currentShape = shape ?: return
        val path =
            clipPath
                ?: run {
                    val outline =
                        currentShape.createOutline(
                            Size(width.toFloat(), height.toFloat()),
                            shapeLayoutDirection,
                            shapeDensity,
                        )
                    cornerRadiusPx =
                        when (outline) {
                            is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
                            is Outline.Generic ->
                                if (currentShape is LunarisBarSquircle)
                                    minOf(
                                        with(shapeDensity) { currentShape.radius.toPx() },
                                        width / 2f,
                                        height / 2f,
                                    )
                                else 0f
                            is Outline.Rectangle -> 0f
                        }
                    Path()
                        .apply {
                            when (outline) {
                                is Outline.Generic -> addPath(outline.path)
                                is Outline.Rounded -> addRoundRect(outline.roundRect)
                                is Outline.Rectangle -> addRect(outline.rect)
                            }
                        }
                        .asAndroidPath()
                        .also { clipPath = it }
                }
        if (!blur.draw(canvas, bounds, path, cornerRadiusPx, overlayColor, surfaceAlpha)) {
            canvas.drawPath(path, fallbackPaint)
        }
    }
}

@Composable
internal fun PopupBlurBackground(
    shape: Shape,
    overlayColor: Int,
    fallbackColor: Int,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val enabled = LocalAxBlurEnabled.current
    AndroidView(
        factory = { PopupBlurHost(it) },
        update = { view ->
            view.updateAppearance(
                shape,
                density,
                layoutDirection,
                overlayColor,
                fallbackColor,
                alpha,
                enabled,
            )
        },
        modifier = modifier.clip(shape),
    )
}

@Composable
internal fun RedesignedMediaPopupBackground(shape: Shape) {
    PopupBlurBackground(
        shape = shape,
        overlayColor = 0x750E0E10,
        fallbackColor = 0xE6141416.toInt(),
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
fun rememberPopupColorMode(): Int {
    val context = LocalContext.current
    val initial = remember(context) { context.contentResolver.readDynamicIslandPopupColorMode() }
    val flow = remember(context) { observeDynamicIslandPopupColorMode(context) }
    val mode by flow.collectAsState(initial = initial)
    return mode
}

@Composable
fun PopupSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 12.dp,
    colorMode: Int = rememberPopupColorMode(),
    animateContentAsUnit: Boolean = true,
    content: @Composable () -> Unit,
) {
    val animatedContent: @Composable () -> Unit = {
        if (animateContentAsUnit && LocalLunarisBarState.current.alternativeAnimation) {
            Box(Modifier.islandPopupRow(0), propagateMinConstraints = true) { content() }
        } else content()
    }
    if (LocalIslandPopupContentOnly.current) {
        CompositionLocalProvider(LocalContentColor provides LocalLunarisBarColors.current.content) {
            Box(modifier, propagateMinConstraints = true) { animatedContent() }
        }
    } else {
        LunarisBarSurface(modifier = modifier) { animatedContent() }
    }
}
