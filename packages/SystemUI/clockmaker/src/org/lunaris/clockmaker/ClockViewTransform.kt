/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lunaris.clockmaker

import android.graphics.Matrix
import android.view.View

object ClockViewTransform {
    fun toGlobal(view: View, matrix: Matrix, excludeAnimationOf: View? = null): Boolean {
        matrix.reset()
        return append(view, matrix, excludeAnimationOf)
    }

    fun toAncestor(view: View, ancestor: View, matrix: Matrix): Boolean {
        matrix.reset()
        return append(view, matrix, null, ancestor)
    }

    private fun append(view: View, matrix: Matrix, excludeAnimationOf: View?,
        ancestor: View? = null): Boolean {
        if (view === ancestor) return true
        val parent = view.parent as? View
        if (parent != null) {
            if (!append(parent, matrix, excludeAnimationOf, ancestor)) return false
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
        if (view !== excludeAnimationOf) view.animationMatrix?.let { matrix.preConcat(it) }
        matrix.preConcat(view.matrix)
        return true
    }
}
