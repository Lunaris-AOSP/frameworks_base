/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.view.View
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintSet
import com.android.app.animation.Interpolators
import com.android.systemui.plugins.keyguard.ui.clocks.ClockController
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import org.lunaris.clockmaker.ClockLayoutProfile
import org.lunaris.clockmaker.DynamicClockSettings
import kotlin.math.abs
import kotlin.math.roundToInt

internal class DynamicClockController(private val host: ClockStyle) {
    var enabled = false
        private set
    private var smallClock: ClockStyle? = null
    private var largeClock: ClockStyle? = null
    private var smallProfile: ClockLayoutProfile? = null
    private var largeProfile: ClockLayoutProfile? = null
    private var hasNotifications = false
    private var switchingAllowed = false
    private var smallFraction = 0f
    private var largeIsTaller = true
    private var dozeAmount = 0f
    private var animator: ValueAnimator? = null
    private var selectionPending = false
    private var animateSelection = false
    private var initialized = false
    private var nativeClockHeight = 0
    private var managesNativeClock = false
    private val nativeClockAlphas = linkedMapOf<View, Float>()
    private var attached = false
    private var userId = -1
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        updateNativeClockHeight()
        val ready = applyPendingSelection()
        applyFrame()
        ready
    }

    val activeClock: ClockStyle?
        get() = if (!enabled || dozeAmount >= 1f) null
            else if (faceFraction >= 0.5f) smallClock else largeClock

    private val faceFraction: Float
        get() = when {
            smallFraction <= 0f -> 0f
            smallFraction >= 1f -> 1f
            largeIsTaller -> (smallFraction / 0.4f).coerceIn(0f, 1f)
            else -> ((smallFraction - 0.6f) / 0.4f).coerceIn(0f, 1f)
        }
    private val layoutFraction: Float
        get() = (if (largeIsTaller) (smallFraction - 0.4f) / 0.6f
            else smallFraction / 0.6f).coerceIn(0f, 1f)

    val isAnimating: Boolean
        get() = animator != null || selectionPending && animateSelection && enabled && switchingAllowed &&
            dozeAmount == 0f && smallFraction != if (hasNotifications) 1f else 0f
    val visibleClocks: List<ClockStyle>
        get() = if (enabled) listOfNotNull(smallClock, largeClock).filter { it.alpha > 0f }
            else emptyList()
    val isBaselineVisible: Boolean get() = !enabled || dozeAmount > 0f
    val isOnLockscreen: Boolean
        get() = enabled && dozeAmount == 0f && !host.isClockDozing
    val isLayoutActive: Boolean get() = enabled && dozeAmount < 1f

    fun attach(amount: Float) {
        dozeAmount = amount.coerceIn(0f, 1f)
        if (attached) return
        attached = true
        host.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        initialized = false
        smallFraction = if (hasNotifications) 1f else 0f
        applyFrame()
    }

    fun detach() {
        cancelAnimation()
        if (attached && host.viewTreeObserver.isAlive) {
            host.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        }
        attached = false
        initialized = false
        restoreNativeClock()
        nativeClockAlphas.clear()
    }

    fun configure(enabled: Boolean, small: ClockLayoutProfile, large: ClockLayoutProfile) {
        val user = ActivityManager.getCurrentUser()
        val userChanged = userId != user
        if (this.enabled == enabled && (!enabled || !userChanged && smallProfile == small && largeProfile == large)) return
        userId = user
        cancelAnimation()
        this.enabled = enabled
        if (!enabled) {
            smallClock?.let(host::removeView)
            largeClock?.let(host::removeView)
            smallClock = null
            largeClock = null
            smallProfile = null
            largeProfile = null
            restoreNativeClock()
        } else {
            if (userChanged || smallProfile != small) {
                smallClock?.let(host::removeView)
                smallClock = host.createLayoutClock(small)
                smallProfile = small
            }
            if (userChanged || largeProfile != large) {
                largeClock?.let(host::removeView)
                largeClock = host.createLayoutClock(large)
                largeProfile = large
            }
            smallFraction = if (hasNotifications) 1f else 0f
            initialized = false
            selectionPending = true
            animateSelection = false
        }
        applyFrame()
        host.updateClockVisibility()
        host.requestLayout()
        host.onDynamicClockLayoutChanged?.invoke()
    }

    fun setNotificationPresence(present: Boolean) {
        if (hasNotifications == present) return
        hasNotifications = present
        if (!enabled) return
        if (dozeAmount >= 1f) {
            smallFraction = if (present) 1f else 0f
        } else {
            selectionPending = true
            animateSelection = initialized && switchingAllowed && host.isClockSwitchingVisible && dozeAmount == 0f
        }
        host.invalidate()
    }

    fun setSwitchingAllowed(allowed: Boolean) {
        if (switchingAllowed == allowed) return
        switchingAllowed = allowed
        cancelAnimation()
        if (allowed) {
            selectionPending = true
            animateSelection = false
            initialized = false
        }
        applyFrame()
        host.invalidate()
    }

    fun onDozingChanged() {
        cancelAnimation()
        applyFrame()
        if (enabled) host.onDynamicClockLayoutChanged?.invoke()
    }

    fun onHostVisibilityChanged() {
        cancelAnimation()
        initialized = false
        selectionPending = true
        animateSelection = false
    }

    fun onDozeAmountChanged(amount: Float) {
        val next = amount.coerceIn(0f, 1f)
        if (next == dozeAmount) return
        val wasLayoutActive = isLayoutActive
        val wasOnLockscreen = isOnLockscreen
        if (next > 0f) cancelAnimation()
        if (next == 1f || dozeAmount == 1f) {
            smallFraction = if (hasNotifications) 1f else 0f
            selectionPending = false
        }
        dozeAmount = next
        applyFrame()
        host.updateClockVisibility()
        host.requestLayout()
        if (wasLayoutActive != isLayoutActive || wasOnLockscreen != isOnLockscreen)
            host.onDynamicClockLayoutChanged?.invoke()
    }

    fun applyNativeClockConstraints(constraints: ConstraintSet, clock: ClockController?) {
        if (!enabled || host.baseClockStyle != 0) return
        val views = clock?.let { it.smallClock.layout.views + it.largeClock.layout.views }
            ?: nativeClockViews()
        nativeClockAlphas.keys.filter { it !in views }.forEach { view ->
            nativeClockAlphas.remove(view)?.let { view.alpha = it * host.alpha }
        }
        views.forEach { view ->
            val alpha = constraints.getConstraint(view.id)?.propertySet?.alpha ?: return@forEach
            nativeClockAlphas[view] = alpha
            constraints.setAlpha(view.id, alpha * dozeAmount)
        }
    }

    fun measuredHeight(baselineHeight: Int): Int? {
        if (!enabled) return null
        val small = smallClock ?: return null
        val large = largeClock ?: return null
        val dynamicHeight = lerp(large.measuredHeight.toFloat(), small.measuredHeight.toFloat(), layoutFraction)
        val aodHeight = if (host.baseClockStyle == 0) nativeClockHeight else baselineHeight
        return lerp(dynamicHeight, aodHeight.toFloat(), dozeAmount).roundToInt().coerceAtLeast(0)
    }

    private fun applyPendingSelection(): Boolean {
        if (!host.isClockSwitchingVisible) {
            cancelAnimation()
            initialized = false
            animateSelection = false
            return true
        }
        if (!enabled || !switchingAllowed || host.isClockDozing || dozeAmount != 0f) return true
        val small = smallClock ?: return true
        val large = largeClock ?: return true
        if (!small.isLaidOut || !large.isLaidOut || small.width <= 0 || large.width <= 0) return false
        if (animator == null && (!initialized || smallFraction == 0f || smallFraction == 1f)) {
            largeIsTaller = large.measuredHeight >= small.measuredHeight
        }
        if (!selectionPending) {
            initialized = true
            return true
        }
        selectionPending = false
        val target = if (hasNotifications) 1f else 0f
        if (smallFraction == target) {
            initialized = true
            return true
        }
        cancelAnimation()
        if (!animateSelection || !ValueAnimator.areAnimatorsEnabled()) {
            smallFraction = target
            applyFrame()
            host.requestLayout()
            initialized = true
            return false
        } else {
            val animation = ValueAnimator.ofFloat(smallFraction, target)
            animator = animation
            animation.duration = (DynamicClockSettings.TRANSITION_DURATION * abs(target - smallFraction))
                .toLong().coerceAtLeast(80L)
            animation.interpolator = Interpolators.FAST_OUT_SLOW_IN
            animation.addUpdateListener {
                if (animator !== animation) return@addUpdateListener
                smallFraction = it.animatedValue as Float
                applyFrame()
                host.requestLayout()
            }
            animation.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator !== animation) return
                    animator = null
                    smallFraction = target
                    applyFrame()
                    host.requestLayout()
                }
            })
            animation.start()
        }
        initialized = true
        return true
    }

    private fun applyFrame() {
        host.setBaselineClockAlpha(if (enabled) dozeAmount else 1f)
        if (!enabled) {
            host.configureClockParallax(null, null, 0f, false)
            return
        }
        val dynamicAlpha = 1f - dozeAmount
        val small = smallClock
        val large = largeClock
        val fraction = faceFraction
        val distance = (small?.layoutClockCenterY ?: 0f) - (large?.layoutClockCenterY ?: 0f)
        small?.apply {
            alpha = fraction * dynamicAlpha
            visibility = if (alpha > 0f) View.VISIBLE else View.INVISIBLE
            scaleX = lerp(0.92f, 1f, fraction)
            scaleY = scaleX
            pivotX = width / 2f
            pivotY = layoutClockCenterY
            translationY = -distance * (1f - smallFraction) * 0.15f
            setLayoutClockInteractive(fraction >= 0.5f && dozeAmount == 0f && switchingAllowed &&
                host.isClockSwitchingVisible && animator == null)
        }
        large?.apply {
            alpha = (1f - fraction) * dynamicAlpha
            visibility = if (alpha > 0f) View.VISIBLE else View.INVISIBLE
            scaleX = lerp(1f, 0.92f, fraction)
            scaleY = scaleX
            pivotX = width / 2f
            pivotY = layoutClockCenterY
            translationY = distance * smallFraction * 0.15f
            setLayoutClockInteractive(fraction < 0.5f && dozeAmount == 0f && switchingAllowed &&
                host.isClockSwitchingVisible && animator == null)
        }
        if (host.baseClockStyle == 0 && dozeAmount < 1f) {
            managesNativeClock = true
            nativeClockViews().forEach {
                it.alpha = if (it.visibility == View.VISIBLE)
                    (nativeClockAlphas[it] ?: 1f) * dozeAmount * host.alpha else 0f
            }
        } else restoreNativeClock()
        host.configureClockParallax(small, large, fraction, isAnimating)
    }

    private fun updateNativeClockHeight() {
        if (!enabled || host.baseClockStyle != 0) return
        val origin = (host.resources.getDimensionPixelSize(R.dimen.status_bar_height) * 1.25f).toInt()
        val height = nativeClockViews().filter { it.visibility == View.VISIBLE && it.isLaidOut }
            .maxOfOrNull { (it.bottom - origin).coerceAtLeast(0) } ?: 0
        if (nativeClockHeight == height) return
        nativeClockHeight = height
        if (dozeAmount > 0f) host.requestLayout()
    }

    private fun nativeClockViews(): List<View> = if (nativeClockAlphas.isNotEmpty())
        nativeClockAlphas.keys.toList() else listOf(
            ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
            ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
        ).mapNotNull { host.rootView.findViewById<View>(it) }

    private fun restoreNativeClock() {
        if (!managesNativeClock) return
        nativeClockViews().forEach {
            it.alpha = if (host.baseClockStyle == 0 && it.visibility == View.VISIBLE)
                (nativeClockAlphas[it] ?: 1f) * host.alpha else 0f
        }
        managesNativeClock = false
    }

    private fun cancelAnimation() {
        val running = animator
        animator = null
        running?.cancel()
    }

    private fun lerp(start: Float, end: Float, amount: Float) = start + (end - start) * amount
}
