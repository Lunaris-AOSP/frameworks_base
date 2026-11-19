/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.graphics.Color
import kotlin.math.roundToInt

data class ClockElementFrame(
    val x: Float,
    val y: Float,
    val size: Float,
    val letterSpacing: Float,
    val rotation: Float,
    val visibility: Float,
    val alpha: Float,
    val color: Int,
    val imageWidth: Float,
    val imageHeight: Float,
    val imageBorderWidth: Float,
    val imageBorderColor: Int,
    val textStroke: Float,
    val textStrokeWidth: Float,
    val textStrokeColor: Int,
)

object ClockElementAnimation {
    fun progress(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    fun lerp(start: Float, end: Float, progress: Float): Float = start + (end - start) * progress

    fun color(start: Int, end: Int, fraction: Float): Int {
        val p = progress(fraction)
        var result = 0
        for (shift in 24 downTo 0 step 8) {
            val channel = lerp(((start ushr shift) and 255).toFloat(),
                ((end ushr shift) and 255).toFloat(), p).roundToInt()
            result = result or (channel shl shift)
        }
        return result
    }

    fun frame(element: ClockElement, aodProgress: Float, baseColor: Int = Color.WHITE): ClockElementFrame {
        val p = progress(aodProgress)
        val aod = element.aodStyle
        fun amount(value: Boolean) = if (value) 1f else 0f
        val visibility = lerp(amount(element.visible), amount(aod.visible), p)
        val rotation = when (p) {
            0f -> element.rotation.toFloat()
            1f -> aod.rotation.toFloat()
            else -> element.rotation + ((aod.rotation - element.rotation + 540) % 360 - 180) * p
        }
        return ClockElementFrame(
            x = lerp(element.x, aod.x, p), y = lerp(element.y, aod.y, p),
            size = lerp(element.size.toFloat(), aod.size.toFloat(), p),
            letterSpacing = lerp(element.letterSpacing / 100f, aod.letterSpacing / 100f, p),
            rotation = rotation, visibility = visibility,
            alpha = lerp(if (element.visible) element.opacity / 100f else 0f,
                if (aod.visible) aod.opacity / 100f else 0f, p),
            color = color(element.color ?: baseColor, Color.WHITE, p),
            imageWidth = lerp(element.imageWidth.toFloat(), aod.imageWidth.toFloat(), p),
            imageHeight = lerp(element.imageHeight.toFloat(), aod.imageHeight.toFloat(), p),
            imageBorderWidth = lerp(element.imageBorderWidth.toFloat(), aod.imageBorderWidth.toFloat(), p),
            imageBorderColor = color(element.imageBorderColor, aod.imageBorderColor, p),
            textStroke = lerp(amount(element.textStroke), amount(aod.textStroke), p),
            textStrokeWidth = lerp(element.textStrokeWidth.toFloat(), aod.textStrokeWidth.toFloat(), p),
            textStrokeColor = color(element.textStrokeColor, aod.textStrokeColor, p),
        )
    }
}
