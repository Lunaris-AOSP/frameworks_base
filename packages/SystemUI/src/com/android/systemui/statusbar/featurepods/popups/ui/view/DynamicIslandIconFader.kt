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

package com.android.systemui.statusbar.featurepods.popups.ui.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.Annotation
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.Space
import android.widget.TextView
import kotlin.math.roundToInt

class DynamicIslandIconFader(
    private val root: ViewGroup,
    private val onCoverageChanged: (Rect) -> Unit = {},
) {
    private val coverage = Rect()
    private val publishedCoverage = Rect()
    private val location = IntArray(2)
    private val iconBounds = RectF()
    private val dateBounds = RectF()
    private val timeBounds = RectF()
    private val textPath = Path()
    private val ids = mutableMapOf<String, Int>()
    private val candidates = linkedSetOf<View>()
    private val fades = mutableMapOf<View, Fade>()
    private val dateFades = mutableMapOf<TextView, DateFade>()
    private var islandView: View? = null
    private var enabled = false
    private var dirty = true
    private var observer: ViewTreeObserver? = null
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { dirty = true }
    private val drawListener =
        ViewTreeObserver.OnPreDrawListener {
            apply()
            true
        }
    private val attachListener =
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = start()

            override fun onViewDetachedFromWindow(view: View) = stop()
        }

    fun attach(host: View) {
        if (islandView === host) return
        if (islandView != null) {
            root.removeOnAttachStateChangeListener(attachListener)
            stop()
        }
        islandView = host
        root.addOnAttachStateChangeListener(attachListener)
        if (root.isAttachedToWindow) start()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        apply()
    }

    fun update(boundsInWindow: Rect) {
        coverage.set(boundsInWindow)
        apply()
    }

    private fun start() {
        if (observer != null) return
        observer =
            root.viewTreeObserver.also {
                it.addOnGlobalLayoutListener(layoutListener)
                it.addOnPreDrawListener(drawListener)
            }
        dirty = true
        apply()
    }

    private fun stop() {
        observer
            ?.takeIf { it.isAlive }
            ?.let {
                it.removeOnGlobalLayoutListener(layoutListener)
                it.removeOnPreDrawListener(drawListener)
            }
        observer = null
        fades.values.forEach { it.reset() }
        dateFades.values.forEach { it.reset() }
        fades.clear()
        dateFades.clear()
        candidates.clear()
        publish(false)
    }

    private fun publish(active: Boolean) {
        if (active) {
            if (publishedCoverage == coverage) return
            publishedCoverage.set(coverage)
        } else {
            if (publishedCoverage.isEmpty) return
            publishedCoverage.setEmpty()
        }
        onCoverageChanged(Rect(publishedCoverage))
    }

    private fun apply() {
        val active =
            observer != null &&
                enabled &&
                !coverage.isEmpty &&
                root.isShown &&
                islandView?.isShown == true
        publish(active)
        if (!active) {
            fades.values.forEach { it.animateTo(1f) }
            dateFades.values.forEach { it.fade.animateTo(1f) }
            return
        }
        if (dirty) collect()
        val density = root.resources.displayMetrics.density
        for (view in candidates) {
            val fade = fades[view]
            var covered = false
            var dateCovered = false
            val dateFade = if (view is TextView) dateFades[view] else null
            if (view.isShown && view.width > 0 && view.height > 0) {
                view.getLocationInWindow(location)
                iconBounds.set(
                    location[0].toFloat(),
                    location[1].toFloat(),
                    location[0] + view.width * view.scaleX,
                    location[1] + view.height * view.scaleY,
                )
                if (view is TextView && clockRegions(view)) {
                    val date = dateFade ?: DateFade(view).also { dateFades[view] = it }
                    if (date.region != dateBounds) {
                        date.region.set(dateBounds)
                        date.setBounds(0, 0, view.width, view.height)
                        date.invalidateSelf()
                    }
                    toWindow(dateBounds, view)
                    toWindow(timeBounds, view)
                    covered = overlaps(timeBounds, coverage, density, fade?.target == 0f)
                    dateCovered = overlaps(dateBounds, coverage, density, date.fade.target == 0f)
                } else {
                    if (view is TextView) dateFades.remove(view)?.reset()
                    covered = overlaps(iconBounds, coverage, density, fade?.target == 0f)
                }
            }
            if (view is TextView)
                dateFades[view]?.fade?.animateTo(if (dateCovered && !covered) 0f else 1f)
            val target = if (covered) 0f else 1f
            if (fade != null) {
                fade.animateTo(target)
            } else if (covered) {
                val initialAlpha = view.transitionAlpha
                fades[view] =
                    Fade { view.transitionAlpha = initialAlpha * it }.also { it.animateTo(target) }
            }
        }
    }

    private fun toWindow(bounds: RectF, view: View) {
        bounds.set(
            location[0] + bounds.left * view.scaleX,
            iconBounds.top,
            location[0] + bounds.right * view.scaleX,
            iconBounds.bottom,
        )
    }

    private fun collect() {
        dirty = false
        val next = linkedSetOf<View>()
        for (name in LEAVES) find(root, name)?.let { next.add(it) }
        for (name in GROUPS) {
            val group = find(root, name) as? ViewGroup ?: continue
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child.id == id("statusIcons") || next.contains(child)) continue
                if (isMobileGroup(child)) {
                    collectMobileLeaves(child, next)
                } else if (next.none { isDescendant(it, child) }) {
                    next.add(child)
                }
            }
        }
        for (removed in candidates - next) {
            fades.remove(removed)?.reset()
            if (removed is TextView) dateFades.remove(removed)?.reset()
        }
        candidates.clear()
        candidates.addAll(next)
    }

    private fun isDescendant(view: View, ancestor: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }

    private fun isMobileGroup(view: View): Boolean =
        view is ViewGroup &&
            (view.javaClass.simpleName.contains("Mobile") ||
                view.id == id("mobile_group") ||
                view.id == id("mobile_combo") ||
                (find(view, "mobile_signal") != null && find(view, "mobile_type") != null))

    private fun collectMobileLeaves(view: View, out: MutableSet<View>) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectMobileLeaves(view.getChildAt(i), out)
        } else if (view !is Space) {
            out.add(view)
        }
    }

    private fun clockRegions(view: TextView): Boolean {
        val text = view.text as? Spanned ?: return false
        val layout = view.layout ?: return false
        if (layout.lineCount != 1) return false
        val marker =
            text.getSpans(0, text.length, Annotation::class.java).firstOrNull {
                it.key == CLOCK_DATE_ANNOTATION
            } ?: return false
        val start = text.getSpanStart(marker)
        val end = text.getSpanEnd(marker)
        if (start < 0 || end <= start || end > text.length) return false
        textPath.reset()
        layout.getSelectionPath(start, end, textPath)
        textPath.computeBounds(dateBounds, true)
        textPath.reset()
        if (start > 0) layout.getSelectionPath(0, start, textPath)
        else if (end < text.length) layout.getSelectionPath(end, text.length, textPath)
        textPath.computeBounds(timeBounds, true)
        val offset = (view.compoundPaddingLeft - view.scrollX).toFloat()
        dateBounds.offset(offset, 0f)
        timeBounds.offset(offset, 0f)
        return dateBounds.width() > 0 && timeBounds.width() > 0
    }

    private fun id(name: String): Int =
        ids.getOrPut(name) { root.resources.getIdentifier(name, "id", "com.android.systemui") }

    private fun find(view: View, name: String): View? =
        id(name).takeIf { it != 0 }?.let { view.findViewById(it) }

    private class Fade(private val apply: (Float) -> Unit) {
        var target = 1f
            private set

        private var value = 1f
        private var animator: ValueAnimator? = null

        fun animateTo(next: Float) {
            if (target == next && (animator != null || value == next)) return
            target = next
            animator?.cancel()
            if (!ValueAnimator.areAnimatorsEnabled() || value == next) {
                value = next
                apply(next)
                return
            }
            animator =
                ValueAnimator.ofFloat(value, next).apply {
                    duration = (if (next == 0f) HIDE_DURATION else SHOW_DURATION).toLong()
                    startDelay = (if (next == 0f) 0 else SHOW_DELAY).toLong()
                    interpolator = if (next == 0f) HIDE_EASING else SHOW_EASING
                    addUpdateListener {
                        value = it.animatedValue as Float
                        apply(value)
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (animator === animation) animator = null
                            }
                        }
                    )
                    start()
                }
        }

        fun reset() {
            animator?.cancel()
            animator = null
            value = 1f
            target = 1f
            apply(1f)
        }
    }

    private class DateFade(private val view: TextView) : Drawable() {
        val region = RectF()
        private val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        private var fraction = 1f
        private var installed = false
        private var changedLayer = false
        val fade = Fade { value ->
            fraction = value
            if (value < 1f) install() else uninstall()
            invalidateSelf()
        }

        private fun install() {
            if (installed) return
            installed = true
            changedLayer = view.layerType == View.LAYER_TYPE_NONE
            if (changedLayer) view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBounds(0, 0, view.width, view.height)
            view.overlay.add(this)
        }

        private fun uninstall() {
            if (!installed) return
            installed = false
            view.overlay.remove(this)
            if (changedLayer && view.layerType == View.LAYER_TYPE_HARDWARE) {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            changedLayer = false
        }

        fun reset() {
            fade.reset()
            uninstall()
        }

        override fun draw(canvas: Canvas) {
            paint.alpha = ((1f - fraction) * 255f).roundToInt()
            canvas.drawRect(region.left, 0f, region.right, view.height.toFloat(), paint)
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        const val CLOCK_DATE_ANNOTATION = "status_bar_clock_date"
        const val HIDE_DURATION = 130
        const val SHOW_DURATION = 200
        const val SHOW_DELAY = 40
        private val HIDE_EASING = PathInterpolator(.4f, 0f, 1f, 1f)
        private val SHOW_EASING = PathInterpolator(0f, 0f, .2f, 1f)
        private val LEAVES =
            arrayOf(
                "clock",
                "clock_center",
                "clock_right",
                "battery",
                "network_speed",
                "networkSpeed",
                "user_switcher_container",
                "operator_name_frame",
                "statusbar_logo",
                "statusbar_logo_right",
                "keyguard_carrier_text",
                "ongoing_activity_chip_primary",
                "ongoing_activity_chip_secondary",
            )
        private val GROUPS = arrayOf("notificationIcons", "statusIcons", "system_icons")

        fun overlaps(icon: RectF, island: Rect, density: Float, hidden: Boolean): Boolean {
            if (icon.isEmpty || island.isEmpty) return false
            val margin = (if (hidden) 10f else 6f) * density
            return icon.bottom > island.top &&
                icon.top < island.bottom &&
                icon.right > island.left - margin &&
                icon.left < island.right + margin
        }
    }
}
