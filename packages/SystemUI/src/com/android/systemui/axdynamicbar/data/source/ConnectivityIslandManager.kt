package com.android.systemui.axdynamicbar.data.source

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.HotspotController
import com.android.systemui.statusbar.policy.vpn.domain.interactor.VpnInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/** AxDynamicBar owns a separate session of the shared event source. */
@SysUISingleton
class ConnectivityIslandManager @Inject constructor(
    @Application applicationScope: CoroutineScope,
    @Background backgroundDispatcher: CoroutineDispatcher,
    bluetoothController: BluetoothController,
    hotspotController: HotspotController,
    vpnInteractor: VpnInteractor,
) :
    com.android.systemui.islandevents.data.source.ConnectivityIslandManager(
        applicationScope, backgroundDispatcher, bluetoothController, hotspotController, vpnInteractor
    )
