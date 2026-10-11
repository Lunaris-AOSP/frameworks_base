/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.toPath

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object ClockImageShapes {
    private val shapes by lazy {
        linkedMapOf(
            "Circle" to MaterialShapes.Circle,
            "Square" to MaterialShapes.Square,
            "Slanted" to MaterialShapes.Slanted,
            "Arch" to MaterialShapes.Arch,
            "Fan" to MaterialShapes.Fan,
            "Arrow" to MaterialShapes.Arrow,
            "SemiCircle" to MaterialShapes.SemiCircle,
            "Oval" to MaterialShapes.Oval,
            "Pill" to MaterialShapes.Pill,
            "Triangle" to MaterialShapes.Triangle,
            "Diamond" to MaterialShapes.Diamond,
            "ClamShell" to MaterialShapes.ClamShell,
            "Pentagon" to MaterialShapes.Pentagon,
            "Gem" to MaterialShapes.Gem,
            "Sunny" to MaterialShapes.Sunny,
            "VerySunny" to MaterialShapes.VerySunny,
            "Cookie4Sided" to MaterialShapes.Cookie4Sided,
            "Cookie6Sided" to MaterialShapes.Cookie6Sided,
            "Cookie7Sided" to MaterialShapes.Cookie7Sided,
            "Cookie9Sided" to MaterialShapes.Cookie9Sided,
            "Cookie12Sided" to MaterialShapes.Cookie12Sided,
            "Ghostish" to MaterialShapes.Ghostish,
            "Clover4Leaf" to MaterialShapes.Clover4Leaf,
            "Clover8Leaf" to MaterialShapes.Clover8Leaf,
            "Burst" to MaterialShapes.Burst,
            "SoftBurst" to MaterialShapes.SoftBurst,
            "Boom" to MaterialShapes.Boom,
            "SoftBoom" to MaterialShapes.SoftBoom,
            "Flower" to MaterialShapes.Flower,
            "Puffy" to MaterialShapes.Puffy,
            "PuffyDiamond" to MaterialShapes.PuffyDiamond,
            "PixelCircle" to MaterialShapes.PixelCircle,
            "PixelTriangle" to MaterialShapes.PixelTriangle,
            "Bun" to MaterialShapes.Bun,
            "Heart" to MaterialShapes.Heart,
        )
    }
    val ids: List<String> get() = listOf("none") + shapes.keys

    fun path(id: String, width: Float, height: Float): Path {
        val polygon = shapes[id]
        if (polygon == null) return Path().apply { addRect(0f, 0f, width, height, Path.Direction.CW) }
        val path = polygon.toPath()
        val bounds = RectF().also { path.computeBounds(it, true) }
        path.transform(Matrix().apply {
            setRectToRect(bounds, RectF(0f, 0f, width, height), Matrix.ScaleToFit.FILL)
        })
        return path
    }
}
