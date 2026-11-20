/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.clocks

import android.view.Choreographer
import com.android.systemui.util.ParallaxTiltSensor
import org.lunaris.clockmaker.ClockParallaxSettings
import kotlin.math.abs
import kotlin.math.exp

internal class ClockParallaxController(private val host: ClockStyle) : ParallaxTiltSensor.Listener {
    private val sensor = ParallaxTiltSensor.getInstance(host.context)
    private val choreographer = Choreographer.getInstance()
    private val horizontal = SpringAxis()
    private val vertical = SpringAxis()
    private var smallClock: ClockStyle? = null
    private var largeClock: ClockStyle? = null
    private var smallFraction = 0f
    private var transitioning = false
    private var active = false
    private var subscribed = false
    private var frameScheduled = false
    private var lastFrameTime = 0L
    private val frameCallback = Choreographer.FrameCallback { frameTime ->
        frameScheduled = false
        if (!host.canSenseClockParallax) updateState()
        if (active) {
            val seconds = ((frameTime - lastFrameTime) * 0.000000001f).coerceIn(0f, 0.05f)
            lastFrameTime = frameTime
            horizontal.step(seconds)
            vertical.step(seconds)
            applyRotations()
            if (!horizontal.settled || !vertical.settled) scheduleFrame()
            else lastFrameTime = 0L
        }
    }

    fun configure(small: ClockStyle?, large: ClockStyle?, fraction: Float, transitioning: Boolean) {
        if (smallClock !== small) smallClock?.setParallaxRotation(0f, 0f)
        if (largeClock !== large) largeClock?.setParallaxRotation(0f, 0f)
        smallClock = small
        largeClock = large
        smallFraction = fraction.coerceIn(0f, 1f)
        this.transitioning = transitioning
        updateState()
    }

    fun updateState() {
        val hasTilt = if (smallClock != null || largeClock != null) {
            smallClock?.canRenderParallax == true && (transitioning || smallFraction > 0f) ||
                largeClock?.canRenderParallax == true && (transitioning || smallFraction < 1f)
        } else host.canRenderParallax
        val shouldSense = hasTilt && host.canSenseClockParallax
        if (!shouldSense) {
            stop(host.isClockParallaxSuspended || !hasTilt)
        } else if (!active) {
            active = true
            subscribed = true
            if (!sensor.addListener(this)) {
                subscribed = false
                horizontal.reset(true)
                vertical.reset(true)
            }
            scheduleFrame()
        }
        applyRotations()
    }

    override fun onTiltChanged(horizontal: Float, vertical: Float) {
        if (!active) return
        if (!host.canSenseClockParallax) {
            updateState()
            return
        }
        this.horizontal.target = horizontal.coerceIn(-1f, 1f)
        this.vertical.target = vertical.coerceIn(-1f, 1f)
        if (!this.horizontal.settled || !this.vertical.settled) scheduleFrame()
    }

    fun destroy() {
        stop(true)
        applyRotations()
        smallClock = null
        largeClock = null
    }

    private fun stop(reset: Boolean) {
        active = false
        if (subscribed) {
            sensor.removeListener(this)
            subscribed = false
        }
        if (frameScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            frameScheduled = false
        }
        lastFrameTime = 0L
        horizontal.reset(reset)
        vertical.reset(reset)
    }

    private fun applyRotations() {
        val dynamic = smallClock != null || largeClock != null
        val intensity = if (dynamic) {
            val small = smallClock?.parallaxIntensity ?: 0f
            val large = largeClock?.parallaxIntensity ?: 0f
            large + (small - large) * smallFraction
        } else host.parallaxIntensity
        val angle = intensity * ClockParallaxSettings.MAX_ROTATION_DEGREES
        val rotationX = -vertical.position.coerceIn(-1f, 1f) * angle
        val rotationY = horizontal.position.coerceIn(-1f, 1f) * angle
        host.setParallaxRotation(if (dynamic) 0f else rotationX, if (dynamic) 0f else rotationY)
        smallClock?.setParallaxRotation(rotationX, rotationY)
        largeClock?.setParallaxRotation(rotationX, rotationY)
    }

    private fun scheduleFrame() {
        if (!active || frameScheduled || horizontal.settled && vertical.settled) return
        frameScheduled = true
        if (lastFrameTime == 0L) lastFrameTime = System.nanoTime()
        choreographer.postFrameCallback(frameCallback)
    }

    private class SpringAxis {
        var target = 0f
        var position = 0f
            private set
        private var velocity = 0f
        val settled: Boolean
            get() = abs(target - position) <= POSITION_THRESHOLD && abs(velocity) <= VELOCITY_THRESHOLD

        fun step(seconds: Float) {
            val displacement = position - target
            val impulse = velocity + SPRING_FREQUENCY * displacement
            val decay = exp(-SPRING_FREQUENCY * seconds)
            position = target + (displacement + impulse * seconds) * decay
            velocity = (velocity - SPRING_FREQUENCY * impulse * seconds) * decay
            if (settled) {
                position = target
                velocity = 0f
            }
        }

        fun reset(clearPosition: Boolean) {
            if (clearPosition) position = 0f
            target = position
            velocity = 0f
        }
    }

    private companion object {
        const val SPRING_FREQUENCY = 14f
        const val POSITION_THRESHOLD = 0.001f
        const val VELOCITY_THRESHOLD = 0.01f
    }
}
