/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lunaris.clockmaker

import android.graphics.Matrix
import android.view.View
import kotlin.math.ceil

object ClockViewTransform {
    const val AOD_OPACITY_CAP = 70
    const val AOD_SCALE_DOWN = 0.85f
    const val AOD_ANIM_OUT_MS = 300L
    const val AOD_ANIM_IN_MS = 400L

    fun alpha(opacity: Int, dozing: Boolean = false): Float =
        opacity.coerceIn(0, if (dozing) AOD_OPACITY_CAP else 100) / 100f

    fun alpha(opacity: Int, aodProgress: Float): Float = ClockElementAnimation.lerp(
        alpha(opacity), alpha(opacity, true), ClockElementAnimation.progress(aodProgress))

    fun renderScale(scale: Float, dozing: Boolean, aodAnimation: Boolean): Float =
        if (dozing && aodAnimation) AOD_SCALE_DOWN else scale

    fun renderScale(scale: Float, aodProgress: Float, aodAnimation: Boolean): Float =
        ClockElementAnimation.lerp(scale, renderScale(scale, true, aodAnimation),
            ClockElementAnimation.progress(aodProgress))

    fun layoutHeight(width: Int, height: Int, scale: Float, clockMaker: Boolean = false): Int {
        val pivot = if (clockMaker) width / 2f else height / 2f
        return ceil(pivot + (height - pivot) * scale).toInt()
    }

    fun cameraDistance(width: Int, height: Int, density: Float, clockMaker: Boolean = false): Float {
        val extent = if (clockMaker) width else maxOf(width, height)
        return maxOf(extent * 4f, 1280f * density)
    }

    fun applyClockTransform(view: View, rotation: Int, tiltX: Int, tiltY: Int) {
        view.pivotX = view.width / 2f
        view.pivotY = (view as? ClockDesignView)?.transformPivotY ?: (view.height / 2f)
        if (view.width > 0 && view.height > 0) {
            view.cameraDistance = cameraDistance(view.width, view.height,
                view.resources.displayMetrics.density, view is ClockDesignView)
        }
        view.rotation = rotation.coerceIn(-180, 180).toFloat()
        view.rotationX = tiltX.coerceIn(-75, 75).toFloat()
        view.rotationY = tiltY.coerceIn(-75, 75).toFloat()
    }

    fun toGlobal(view: View, matrix: Matrix, excludeAnimationOf: View? = null,
        excludeTransformOf: View? = null): Boolean {
        matrix.reset()
        return append(view, matrix, excludeAnimationOf, excludeTransformOf = excludeTransformOf)
    }

    fun toAncestor(view: View, ancestor: View, matrix: Matrix): Boolean {
        matrix.reset()
        return append(view, matrix, null, ancestor)
    }

    private fun append(view: View, matrix: Matrix, excludeAnimationOf: View?,
        ancestor: View? = null, excludeTransformOf: View? = null): Boolean {
        if (view === ancestor) return true
        val parent = view.parent as? View
        if (parent != null) {
            if (!append(parent, matrix, excludeAnimationOf, ancestor, excludeTransformOf)) return false
            matrix.preTranslate(-parent.scrollX.toFloat(), -parent.scrollY.toFloat())
        } else {
            if (ancestor != null) return false
            view.transformMatrixToGlobal(matrix)
            if (!view.matrix.isIdentity) {
                val inverse = Matrix()
                if (!view.matrix.invert(inverse)) return false
                matrix.preConcat(inverse)
            }
            matrix.preTranslate(-view.left.toFloat(), -view.top.toFloat())
        }
        matrix.preTranslate(view.left.toFloat(), view.top.toFloat())
        if (view !== excludeTransformOf) {
            if (view !== excludeAnimationOf) view.animationMatrix?.let { matrix.preConcat(it) }
            matrix.preConcat(view.matrix)
        }
        return true
    }
}
