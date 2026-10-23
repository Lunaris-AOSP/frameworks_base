/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

internal object ClockStackBlur {
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height)
        require(radius in 1..254)
        val horizontal = pixels.copyOf()
        val result = pixels.copyOf()
        filter(pixels, horizontal, width, height, radius, true)
        filter(horizontal, result, width, height, radius, false)
        return result
    }

    private fun filter(
        input: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val stride = if (horizontal) 1 else width
        val divisor = (radius + 1) * (radius + 1)
        for (line in 0 until lines) {
            val start = if (horizontal) line * width else line
            for (shift in intArrayOf(16, 8, 0)) {
                fun sample(position: Int) =
                    (input[start + position.coerceIn(0, length - 1) * stride] ushr shift) and 255
                var sum = 0
                var left = 0
                var right = 0
                for (offset in -radius..radius) {
                    val value = sample(offset)
                    sum += value * (radius + 1 - kotlin.math.abs(offset))
                    if (offset <= 0) left += value else right += value
                }
                right += sample(radius + 1)
                for (position in 0 until length) {
                    val index = start + position * stride
                    output[index] =
                        (output[index] and (255 shl shift).inv()) or ((sum / divisor) shl shift)
                    sum += right - left
                    val next = sample(position + 1)
                    left += next - sample(position - radius)
                    right += sample(position + radius + 2) - next
                }
            }
        }
    }
}
