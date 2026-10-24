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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.min

internal class LunarisBarSquircle(val radius: Dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = min(with(density) { radius.toPx() }, size.minDimension / 2f)
        val h = r * 0.49477f
        val w = size.width
        val b = size.height
        return Outline.Generic(
            Path().apply {
                moveTo(r, 0f)
                lineTo(w - r, 0f)
                cubicTo(w - r + h, 0f, w, r - h, w, r)
                lineTo(w, b - r)
                cubicTo(w, b - r + h, w - r + h, b, w - r, b)
                lineTo(r, b)
                cubicTo(r - h, b, 0f, b - r + h, 0f, b - r)
                lineTo(0f, r)
                cubicTo(0f, r - h, r - h, 0f, r, 0f)
                close()
            }
        )
    }
}

internal fun lunarisBarExpandedShape(
    settings: com.android.internal.util.lunaris.LunarisBarSettings
): Shape =
    when (settings.expandedShape) {
        0 -> RoundedCornerShape(48.dp)
        1 -> RoundedCornerShape(settings.cornerRadius.dp)
        else -> LunarisBarSquircle(settings.cornerRadius.dp)
    }

internal fun lunarisBarScaledShape(shape: Shape, width: Float, height: Float): Shape =
    object : Shape {
        override fun createOutline(
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
        ): Outline {
            val outline = shape.createOutline(Size(width, height), layoutDirection, density)
            val path =
                Path().apply {
                    when (outline) {
                        is Outline.Generic -> addPath(outline.path)
                        is Outline.Rounded -> addRoundRect(outline.roundRect)
                        is Outline.Rectangle -> addRect(outline.rect)
                    }
                    transform(
                        Matrix().apply {
                            scale(
                                size.width / width.coerceAtLeast(1f),
                                size.height / height.coerceAtLeast(1f),
                            )
                        }
                    )
                }
            return Outline.Generic(path)
        }
    }

@Composable
internal fun LunarisBarSurface(
    modifier: Modifier = Modifier,
    mini: Boolean = false,
    resting: Boolean = false,
    shape: Shape = lunarisBarExpandedShape(LocalLunarisBarState.current.settings),
    background: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = LocalLunarisBarState.current.settings
    val colors = LocalLunarisBarColors.current
    val surfaceColor =
        if (resting) colors.miniSurface.copy(alpha = settings.collapsedOpacity)
        else if (mini) colors.miniSurface else colors.surface
    val foreground = if (mini) colors.miniContent else colors.content
    val blur =
        settings.enabled(if (mini) "mini_blur" else "capsule_blur", false) &&
            !(resting && (settings.collapsedBorder > 0 || settings.collapsedOpacity == 0f))
    val elevation = if (settings.shadow(colors.dark)) settings.shadowElevation.dp else 0.dp
    Surface(
        modifier = modifier,
        shape = shape,
        color =
            if (background != null || blur || (resting && settings.collapsedBorder > 0))
                Color.Transparent
            else surfaceColor,
        contentColor = foreground,
        shadowElevation = if (resting) 0.dp else if (mini) minOf(elevation, 4.dp) else elevation,
        border =
            if (resting && settings.collapsedBorder > 0)
                BorderStroke(settings.collapsedBorder.dp, surfaceColor)
            else if (!resting && settings.outline(colors.dark) && settings.outlineWidth > 0f)
                BorderStroke(settings.outlineWidth.dp, colors.outline)
            else null,
    ) {
        Box(propagateMinConstraints = true) {
            if (background != null) {
                background()
            } else if (blur) {
                PopupBlurBackground(
                    shape = shape,
                    overlayColor =
                        surfaceColor
                            .copy(alpha = if (resting) 0.55f else surfaceColor.alpha * 0.55f)
                            .toArgb(),
                    fallbackColor = surfaceColor.toArgb(),
                    modifier = Modifier.matchParentSize(),
                    alpha = if (resting) settings.collapsedOpacity else 1f,
                )
            }
            CompositionLocalProvider(LocalContentColor provides foreground) { content() }
        }
    }
}
