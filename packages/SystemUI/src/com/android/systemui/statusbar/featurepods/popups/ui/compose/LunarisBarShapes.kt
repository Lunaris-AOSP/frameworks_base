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

import android.graphics.Matrix
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

private val lunarisBarShapes = mutableMapOf<Int, Shape>()

internal fun lunarisBarButtonShape(type: Int): Shape =
    if (type == 20) CircleShape else lunarisBarIconShape(type)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun lunarisBarIconShape(type: Int): Shape =
    lunarisBarShapes.getOrPut(type) {
        val polygon =
            when (type) {
                3 ->
                    RoundedPolygon.star(
                        10,
                        radius = 68f,
                        innerRadius = 55f,
                        rounding = CornerRounding(20f),
                        centerX = 63f,
                        centerY = 63f,
                    )
                5 -> MaterialShapes.Square
                6 ->
                    RoundedPolygon(
                        6,
                        radius = 63f,
                        centerX = 63f,
                        centerY = 63f,
                        rounding = CornerRounding(20f),
                    )
                7 ->
                    RoundedPolygon.star(
                        4,
                        radius = 90f,
                        innerRadius = 50f,
                        rounding = CornerRounding(30f, 0.5f),
                        centerX = 63f,
                        centerY = 63f,
                    )
                8 ->
                    RoundedPolygon(
                        4,
                        radius = 68f,
                        centerX = 63f,
                        centerY = 63f,
                        rounding = CornerRounding(30f),
                    )
                9 ->
                    RoundedPolygon(
                        3,
                        radius = 80f,
                        centerX = 58f,
                        centerY = 63f,
                        rounding = CornerRounding(15.75f, 0.5f),
                    )
                10 -> MaterialShapes.Triangle
                12 -> MaterialShapes.Arrow
                13 -> MaterialShapes.Sunny
                14 -> MaterialShapes.VerySunny
                15 -> MaterialShapes.Diamond
                16 -> MaterialShapes.Pentagon
                17 -> MaterialShapes.SemiCircle
                18 -> MaterialShapes.Arch
                19 -> MaterialShapes.Ghostish
                20 -> MaterialShapes.Pill
                21 -> MaterialShapes.Burst
                22 -> MaterialShapes.SoftBurst
                23 -> MaterialShapes.Clover8Leaf
                24 -> MaterialShapes.Clover4Leaf
                25 -> MaterialShapes.Flower
                26 -> MaterialShapes.ClamShell
                27 -> MaterialShapes.Cookie4Sided
                28 -> MaterialShapes.Fan
                29 -> MaterialShapes.PixelCircle
                30 -> MaterialShapes.Bun
                31 -> MaterialShapes.Heart
                else -> null
            }
        val base =
            (if (type == 1)
                android.util.PathParser.createPathFromPathData(
                    android.content.res.Resources.getSystem()
                        .getString(com.android.internal.R.string.config_icon_mask)
                )
            else null)
                ?: polygon?.toPath()
                ?: AndroidPath().apply {
                    when (type) {
                        0,
                        11 -> addRect(6f, 6f, 120f, 120f, AndroidPath.Direction.CW)
                        4 -> addRoundRect(3f, 3f, 123f, 123f, 32f, 32f, AndroidPath.Direction.CW)
                        else -> addCircle(63f, 63f, 60f, AndroidPath.Direction.CW)
                    }
                }
        if (type == 7) base.transform(Matrix().apply { setRotate(45f, 63f, 63f) })
        val bounds = RectF().also { base.computeBounds(it, true) }
        object : Shape {
            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Outline {
                val matrix =
                    Matrix().apply {
                        setRectToRect(
                            bounds,
                            RectF(0f, 0f, size.width, size.height),
                            Matrix.ScaleToFit.FILL,
                        )
                    }
                return Outline.Generic(
                    AndroidPath(base).apply { transform(matrix) }.asComposePath()
                )
            }
        }
    }
