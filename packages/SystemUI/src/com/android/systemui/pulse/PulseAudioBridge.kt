package com.android.systemui.pulse

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

@SysUISingleton
class PulseAudioBridge @Inject constructor(
    private val context: Context,
    private val settingsRepository: PulseSettingsRepository,
) {
    private val pulseEnabledState = mutableStateOf(
        settingsRepository.isPulseEnabled() && settingsRepository.isPulseMediaEnabled()
    )
    val pulseEnabled: Boolean by pulseEnabledState

    private var activeClientCount = 0
    private val pulseEnabledListeners = mutableSetOf<Runnable>()

    val renderer: PulseRenderer by lazy { PulseRenderer(context, settingsRepository) }

    private val engine: PulseEngine by lazy {
        PulseEngine(context, settingsRepository) { heights ->
            renderer.updateHeights(heights)
        }
    }

    fun addOnPulseEnabledChangedListener(listener: Runnable) {
        pulseEnabledListeners.add(listener)
    }

    fun removeOnPulseEnabledChangedListener(listener: Runnable) {
        pulseEnabledListeners.remove(listener)
    }

    private val dataListener = object : PulseAudioDataProcessor.DataListener {
        override fun onDataUpdate(data: PulseData) {
            when (audioProcessor.captureMode) {
                PulseAudioDataProcessor.CaptureMode.FFT ->
                    data.fftBytes?.let { engine.processFFT(it) }
                PulseAudioDataProcessor.CaptureMode.WAVEFORM ->
                    data.waveformBytes?.let { engine.processWaveform(it) }
            }
        }
    }

    private val audioProcessor: PulseAudioDataProcessor by lazy {
        PulseAudioDataProcessor(context).apply {
            captureMode = settingsRepository.getCaptureMode()
            setDataListener(dataListener)
        }
    }

    private val settingsChangedListener: () -> Unit = {
        val newEnabled = settingsRepository.isPulseEnabled() && settingsRepository.isPulseMediaEnabled()
        if (newEnabled != pulseEnabledState.value) {
            pulseEnabledState.value = newEnabled
            updateCaptureState()
            pulseEnabledListeners.toList().forEach { it.run() }
        }
        audioProcessor.captureMode = settingsRepository.getCaptureMode()
    }

    init {
        settingsRepository.addOnSettingsChangedListener(settingsChangedListener)
    }

    fun acquire() {
        activeClientCount++
        updateCaptureState()
    }

    fun release() {
        activeClientCount = (activeClientCount - 1).coerceAtLeast(0)
        updateCaptureState()
    }

    private fun updateCaptureState() {
        if (activeClientCount > 0 && pulseEnabled) {
            if (!audioProcessor.isCapturing()) audioProcessor.startCapture()
        } else {
            audioProcessor.stopCapture()
        }
    }

    fun destroy() {
        pulseEnabledListeners.clear()
        activeClientCount = 0
        settingsRepository.removeOnSettingsChangedListener(settingsChangedListener)
        engine.stop()
        audioProcessor.cleanup()
        renderer.cleanup()
    }

    fun onMediaColorsChanged(color: Int) {
        renderer.onMediaColorsChanged(color)
    }
}
