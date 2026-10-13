package org.lunaris.clockmaker

import android.graphics.Matrix

data class ClockDesignPositioning(
    val clockWidth: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    private val transform: List<Float>,
) {
    constructor(clockWidth: Int, screenWidth: Int, screenHeight: Int, matrix: Matrix) :
        this(clockWidth, screenWidth, screenHeight,
            FloatArray(9).also(matrix::getValues).toList())

    private fun matrix() = Matrix().apply { setValues(transform.toFloatArray()) }

    fun position(x: Float, y: Float): Pair<Float, Float> {
        val point = floatArrayOf(x * clockWidth, y * clockWidth)
        matrix().mapPoints(point)
        return point[0] / screenWidth to point[1] / screenHeight
    }

    fun move(x: Float, y: Float, horizontal: Float? = null, vertical: Float? = null): Pair<Float, Float> {
        val current = position(x, y)
        val point = floatArrayOf(
            (horizontal ?: current.first).coerceIn(0f, 1f) * screenWidth,
            (vertical ?: current.second).coerceIn(0f, 1f) * screenHeight,
        )
        val inverse = Matrix()
        if (!matrix().invert(inverse)) return x to y
        inverse.mapPoints(point)
        return (point[0] / clockWidth).coerceIn(ClockElement.MIN_POSITION, ClockElement.MAX_POSITION) to
            (point[1] / clockWidth).coerceIn(ClockElement.MIN_POSITION, ClockElement.MAX_POSITION)
    }
}
