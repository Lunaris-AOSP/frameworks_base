package com.android.systemui.axdynamicbar.model

import kotlin.math.abs

data class ChargerReadings(
    val currentMa: Float?,
    val voltageV: Float?,
    val temperatureC: Float?,
) {
    val powerW: Float? =
        if (currentMa != null && voltageV != null) currentMa / 1000f * voltageV else null

    companion object {
        fun fromBattery(current: Int?, voltage: Int?, temperature: Int?, currentDivider: Int) =
            ChargerReadings(
                currentMa = current?.takeIf { it != Int.MIN_VALUE && it != 0 }
                    ?.let { abs(it).toFloat() / currentDivider.coerceAtLeast(1) },
                // The battery broadcast specifies mV, but some devices report µV.
                voltageV = voltage?.takeIf { it > 0 }
                    ?.let { if (it > 100_000) it / 1_000_000f else it / 1000f },
                temperatureC = temperature?.takeIf { it > 0 }?.let { it / 10f },
            )
    }
}
