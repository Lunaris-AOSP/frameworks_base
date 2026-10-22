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

package com.android.systemui.screenrecord

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.SensorPrivacyManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import com.android.systemui.res.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class ScreenRecordCameraOverlay(private val context: Context, private val displayId: Int) {
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var displayManager: DisplayManager? = null
    private var privacyManager: SensorPrivacyManager? = null
    private var preview: TextureView? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var active = false
    private var viewAdded = false
    private var displayListenerRegistered = false
    private var receiverRegistered = false
    private var privacyListenerRegistered = false
    private var generation = 0
    private var sensorOrientation = 0
    private var streamSize = Size(640, 480)
    private var layout: WindowManager.LayoutParams? = null

    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                hide()
            }
        }

    private val privacyListener =
        SensorPrivacyManager.OnSensorPrivacyChangedListener { _, enabled -> if (enabled) fail() }

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}

            override fun onDisplayRemoved(id: Int) {
                if (id == displayId) hide()
            }

            override fun onDisplayChanged(id: Int) {
                if (id != displayId || !active) return
                updateTransform()
                updatePosition()
            }
        }

    fun show() {
        onMain {
            if (active) return@onMain
            active = true
            try {
                check(
                    context.checkSelfPermission(Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                )
                check(context.getSystemService(PowerManager::class.java).isInteractive)
                check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                privacyManager = context.getSystemService(SensorPrivacyManager::class.java)
                check(
                    privacyManager?.areAnySensorPrivacyTogglesEnabled(
                        SensorPrivacyManager.Sensors.CAMERA
                    ) != true
                )
                displayManager = context.getSystemService(DisplayManager::class.java)
                val display = checkNotNull(displayManager?.getDisplay(displayId))
                val windowContext =
                    context
                        .createDisplayContext(display)
                        .createWindowContext(
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            null,
                        )
                val manager = windowContext.getSystemService(WindowManager::class.java)
                windowManager = manager
                val cameraManager = context.getSystemService(CameraManager::class.java)
                val cameraId =
                    cameraManager.cameraIdList.firstOrNull {
                        cameraManager
                            .getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] ==
                            CameraCharacteristics.LENS_FACING_FRONT
                    }
                checkNotNull(cameraId)
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                sensorOrientation = characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
                val sizes =
                    checkNotNull(
                        characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
                            ?.getOutputSizes(SurfaceTexture::class.java)
                    )
                streamSize =
                    (sizes
                        .filter {
                            minOf(it.width, it.height) >= 360 && maxOf(it.width, it.height) <= 1280
                        }
                        .minByOrNull { it.width.toLong() * it.height }
                        ?: sizes.minByOrNull { it.width.toLong() * it.height })
                        ?: error("No front camera preview sizes")
                val density = windowContext.resources.displayMetrics.density
                val size = (120 * density).roundToInt()
                val params =
                    WindowManager.LayoutParams(
                            size,
                            size,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                            PixelFormat.TRANSLUCENT,
                        )
                        .apply {
                            gravity = Gravity.TOP or Gravity.LEFT
                            fitInsetsTypes = 0
                            title = "Screen recording selfie camera"
                        }
                layout = params
                val bounds = availableBounds()
                params.x = bounds.right - size - (16 * density).roundToInt()
                params.y = bounds.bottom - size - (96 * density).roundToInt()
                clampPosition(params)
                val view =
                    TextureView(windowContext).apply {
                        contentDescription = context.getString(R.string.screenrecord_camera_preview)
                        outlineProvider =
                            object : ViewOutlineProvider() {
                                override fun getOutline(view: View, outline: Outline) {
                                    outline.setOval(0, 0, view.width, view.height)
                                }
                            }
                        clipToOutline = true
                        surfaceTextureListener =
                            object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    texture: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) {
                                    openCamera(cameraManager, cameraId, texture)
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    texture: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) {
                                    updateTransform()
                                }

                                override fun onSurfaceTextureDestroyed(
                                    texture: SurfaceTexture
                                ): Boolean {
                                    closeCamera()
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                            }
                    }
                preview = view
                installDragListener(view, params)
                manager.addView(view, params)
                viewAdded = true
                displayManager?.registerDisplayListener(displayListener, handler)
                displayListenerRegistered = true
                context.registerReceiver(
                    screenOffReceiver,
                    IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                        addAction(Intent.ACTION_USER_SWITCHED)
                    },
                    Context.RECEIVER_NOT_EXPORTED,
                )
                receiverRegistered = true
                privacyManager?.addSensorPrivacyListener(
                    SensorPrivacyManager.Sensors.CAMERA,
                    context.mainExecutor,
                    privacyListener,
                )
                privacyListenerRegistered = true
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun hide() {
        onMain {
            active = false
            closeCamera()
            preview?.let { view ->
                view.surfaceTextureListener = null
                if (viewAdded) windowManager?.removeViewImmediate(view)
            }
            viewAdded = false
            preview = null
            if (displayListenerRegistered)
                displayManager?.unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
            if (receiverRegistered) context.unregisterReceiver(screenOffReceiver)
            receiverRegistered = false
            if (privacyListenerRegistered) {
                privacyManager?.removeSensorPrivacyListener(
                    SensorPrivacyManager.Sensors.CAMERA,
                    privacyListener,
                )
            }
            privacyListenerRegistered = false
            windowManager = null
            layout = null
        }
    }

    private fun openCamera(manager: CameraManager, id: String, texture: SurfaceTexture) {
        if (!active) return
        val currentGeneration = ++generation
        try {
            texture.setDefaultBufferSize(streamSize.width, streamSize.height)
            updateTransform()
            val output = Surface(texture)
            surface = output
            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (!active || generation != currentGeneration) {
                            device.close()
                            return
                        }
                        camera = device
                        try {
                            val request =
                                device
                                    .createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                    .apply {
                                        addTarget(output)
                                        set(
                                            CaptureRequest.CONTROL_MODE,
                                            CaptureRequest.CONTROL_MODE_AUTO,
                                        )
                                    }
                                    .build()
                            device.createCaptureSession(
                                SessionConfiguration(
                                    SessionConfiguration.SESSION_REGULAR,
                                    listOf(OutputConfiguration(output)),
                                    context.mainExecutor,
                                    object : CameraCaptureSession.StateCallback() {
                                        override fun onConfigured(
                                            configuredSession: CameraCaptureSession
                                        ) {
                                            if (!active || generation != currentGeneration) {
                                                configuredSession.close()
                                                return
                                            }
                                            session = configuredSession
                                            try {
                                                configuredSession.setRepeatingRequest(
                                                    request,
                                                    null,
                                                    handler,
                                                )
                                            } catch (e: Exception) {
                                                fail(e)
                                            }
                                        }

                                        override fun onConfigureFailed(
                                            failedSession: CameraCaptureSession
                                        ) {
                                            failedSession.close()
                                            if (generation == currentGeneration) fail()
                                        }
                                    },
                                )
                            )
                        } catch (e: Exception) {
                            fail(e)
                        }
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        if (generation == currentGeneration) fail()
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        if (generation == currentGeneration)
                            fail(IllegalStateException("Camera error $error"))
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun closeCamera() {
        generation++
        session?.close()
        session = null
        camera?.close()
        camera = null
        surface?.release()
        surface = null
    }

    private fun updateTransform() {
        val view = preview ?: return
        if (view.width == 0 || view.height == 0) return
        val rotation = (view.display?.rotation ?: Surface.ROTATION_0) * 90
        val naturalWidth = if (sensorOrientation % 180 == 0) streamSize.width else streamSize.height
        val naturalHeight =
            if (sensorOrientation % 180 == 0) streamSize.height else streamSize.width
        val rotatedWidth = if (rotation % 180 == 0) naturalWidth else naturalHeight
        val rotatedHeight = if (rotation % 180 == 0) naturalHeight else naturalWidth
        val scale = max(view.width.toFloat() / rotatedWidth, view.height.toFloat() / rotatedHeight)
        val centerX = view.width / 2f
        val centerY = view.height / 2f
        view.setTransform(
            Matrix().apply {
                setScale(
                    naturalWidth.toFloat() / view.width,
                    naturalHeight.toFloat() / view.height,
                    centerX,
                    centerY,
                )
                postRotate(-rotation.toFloat(), centerX, centerY)
                postScale(scale, scale, centerX, centerY)
            }
        )
    }

    private fun installDragListener(view: TextureView, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    dragging = dragging || abs(dx) > slop || abs(dy) > slop
                    if (dragging) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        updatePosition()
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) view.performClick()
            }
            true
        }
    }

    private fun availableBounds(): Rect {
        val metrics = checkNotNull(windowManager).currentWindowMetrics
        val insets =
            metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
        return Rect(
            insets.left,
            insets.top,
            metrics.bounds.width() - insets.right,
            metrics.bounds.height() - insets.bottom,
        )
    }

    private fun clampPosition(params: WindowManager.LayoutParams) {
        val bounds = availableBounds()
        params.x = params.x.coerceIn(bounds.left, maxOf(bounds.left, bounds.right - params.width))
        params.y = params.y.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - params.height))
    }

    private fun updatePosition() {
        val view = preview ?: return
        val params = layout ?: return
        if (!view.isAttachedToWindow) return
        clampPosition(params)
        windowManager?.updateViewLayout(view, params)
    }

    private fun fail(error: Exception? = null) {
        if (!active) return
        Log.w("ScreenRecordCamera", "Selfie camera unavailable", error)
        hide()
        Toast.makeText(context, R.string.screenrecord_camera_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) action() else handler.post { action() }
    }
}
