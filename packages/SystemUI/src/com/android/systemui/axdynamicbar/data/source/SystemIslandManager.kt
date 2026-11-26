package com.android.systemui.axdynamicbar.data.source

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import com.android.systemui.axdynamicbar.model.ChargerReadings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** AxDynamicBar owns a separate session of the shared event source. */
@SysUISingleton
class SystemIslandManager @Inject constructor(
    @Application context: Context,
    @Application applicationScope: CoroutineScope,
    @Background backgroundDispatcher: CoroutineDispatcher,
    @Main mainHandler: Handler,
    batteryInteractor: BatteryInteractor,
    batteryController: BatteryController,
) :
    com.android.systemui.islandevents.data.source.SystemIslandManager(
        context, applicationScope, backgroundDispatcher, mainHandler, batteryInteractor, batteryController
    ) {
    val chargerReadings: StateFlow<ChargerReadings?> =
        flow {
            val manager = context.getSystemService(BatteryManager::class.java)
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val divider = context.resources.getInteger(R.integer.config_currentInfoDivider)
            while (true) {
                val battery = context.registerReceiver(null, filter)
                emit(
                    ChargerReadings.fromBattery(
                        current = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                        voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
                        temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
                        currentDivider = divider,
                    )
                )
                delay(1000L)
            }
        }
            .flowOn(backgroundDispatcher)
            .stateIn(applicationScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), null)
}
