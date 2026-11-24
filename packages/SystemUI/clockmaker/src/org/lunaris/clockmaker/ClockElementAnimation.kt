/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.graphics.Color
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class ClockElementFrame(
    val x: Float,
    val y: Float,
    val size: Float,
    val stretchX: Float,
    val stretchY: Float,
    val stretchFromTop: Float,
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
) {
    private fun textOffset(height: Int): Float = height * (stretchY - 1f) * stretchFromTop / 2f

    fun textOffsetX(height: Int): Float = -sin(Math.toRadians(rotation.toDouble())).toFloat() * textOffset(height)

    fun textOffsetY(height: Int): Float = cos(Math.toRadians(rotation.toDouble())).toFloat() * textOffset(height)
}

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

    fun layoutFrame(source: ClockElement, target: ClockElement, fraction: Float,
        baseColor: Int = Color.WHITE): ClockElementFrame {
        val p = progress(fraction)
        val from = frame(source, 0f, baseColor)
        if (p == 0f) return from
        val to = frame(target, 0f, baseColor)
        if (p == 1f) return to
        return from.copy(
            x = lerp(from.x, to.x, p), y = lerp(from.y, to.y, p),
            size = lerp(from.size, to.size, p),
            stretchX = lerp(from.stretchX, to.stretchX, p),
            stretchY = lerp(from.stretchY, to.stretchY, p),
            stretchFromTop = lerp(from.stretchFromTop, to.stretchFromTop, p),
            letterSpacing = lerp(from.letterSpacing, to.letterSpacing, p),
            rotation = from.rotation +
                ((to.rotation - from.rotation + 540f) % 360f - 180f) * p,
            visibility = lerp(from.visibility, to.visibility, p),
            alpha = lerp(from.alpha, to.alpha, p), color = color(from.color, to.color, p),
            imageWidth = lerp(from.imageWidth, to.imageWidth, p),
            imageHeight = lerp(from.imageHeight, to.imageHeight, p),
        )
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
            stretchX = lerp(element.stretchX / 100f, aod.stretchX / 100f, p),
            stretchY = lerp(element.stretchY / 100f, aod.stretchY / 100f, p),
            stretchFromTop = lerp(amount(element.stretchFromTop), amount(aod.stretchFromTop), p),
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
