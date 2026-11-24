/*
 * Copyright (C) 2026 Lunaris AOSP
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

package com.android.systemui.qs.panels.domain.interactor

import android.content.Context
import android.telephony.SubscriptionManager
import android.text.format.Formatter
import android.util.Log
import com.android.settingslib.net.DataUsageController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.panels.data.repository.AxQsSettingsRepository
import com.android.systemui.qs.panels.shared.model.LunarisQsDataUsage
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

@SysUISingleton
class LunarisQsDataUsageInteractor
@Inject
constructor(
    @Application private val context: Context,
    settingsRepository: AxQsSettingsRepository,
    wifiInteractor: WifiInteractor,
    mobileIconsInteractor: MobileIconsInteractor,
    connectivityRepository: ConnectivityRepository,
    @Application applicationScope: CoroutineScope,
    @Background backgroundDispatcher: CoroutineDispatcher,
) {
    private data class MobileNetwork(
        val subscriptionId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        val name: String? = null,
        val enabled: Boolean = false,
    )

    private data class MobileUsage(val networkName: String?, val usage: String?)

    private data class Request(
        val enabled: Boolean,
        val wifiNetworkName: String?,
        val mobile: MobileNetwork,
        val connections: DefaultConnectionModel,
    )

    private val mobileNetwork =
        mobileIconsInteractor.activeDataIconInteractor.flatMapLatest { interactor ->
            if (interactor == null) {
                flowOf(MobileNetwork())
            } else {
                combine(interactor.networkName, interactor.isDataEnabled) { name, enabled ->
                    MobileNetwork(interactor.subscriptionId, name.name, enabled)
                }
            }
        }

    val dataUsage: StateFlow<LunarisQsDataUsage> =
        combine(
                settingsRepository.showDataUsage,
                wifiInteractor.wifiNetwork,
                mobileNetwork,
                connectivityRepository.defaultConnections,
            ) { enabled, wifi, mobile, connections ->
                Request(
                    enabled,
                    (wifi as? WifiNetworkModel.Active)?.takeIf { it.hasValidSsid() }
                        ?.ssid?.removeSurrounding("\"")
                        ?.takeIf { it.isNotBlank() },
                    mobile,
                    connections,
                )
            }
            .distinctUntilChanged()
            .transformLatest { request ->
                emit(buildUsage(request))
                if (request.enabled) {
                    val controller = DataUsageController(context)
                    while (true) {
                        emit(queryUsage(controller, request))
                        delay(REFRESH_INTERVAL_MILLIS)
                    }
                }
            }
            .flowOn(backgroundDispatcher)
            .stateIn(
                applicationScope,
                SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
                LunarisQsDataUsage(),
            )

    private fun queryUsage(controller: DataUsageController, request: Request): LunarisQsDataUsage {
        val wifiUsage =
            if (request.wifiNetworkName != null) {
                formattedUsage {
                    controller.getWifiDailyDataUsageInfo(true)
                        ?: controller.getWifiDailyDataUsageInfo(false)
                }
            } else {
                null
            }
        val mobileUsage = queryMobileUsage(controller, request)
        return buildUsage(request, wifiUsage, mobileUsage.usage, mobileUsage.networkName)
    }

    private fun queryMobileUsage(controller: DataUsageController, request: Request): MobileUsage {
        val networkName = request.mobile.name?.takeIf { it.isNotBlank() }
        return try {
            val subscriptionId =
                request.mobile.subscriptionId.takeIf(SubscriptionManager::isValidSubscriptionId)
                    ?: SubscriptionManager.getActiveDataSubscriptionId()
                        .takeIf(SubscriptionManager::isValidSubscriptionId)
                    ?: SubscriptionManager.getDefaultDataSubscriptionId()
            if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
                return MobileUsage(networkName, null)
            }
            controller.setSubscriptionId(subscriptionId)
            val usage =
                if (
                    request.mobile.enabled || usesMobileNetwork(request.connections) ||
                        controller.isMobileDataEnabled
                ) {
                    formattedUsage { controller.dailyDataUsageInfo }
                } else {
                    null
                }
            MobileUsage(
                networkName ?: mobileNetworkName(controller),
                usage,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to query mobile tile data usage", e)
            MobileUsage(networkName, null)
        }
    }

    private fun mobileNetworkName(controller: DataUsageController): String? =
        try {
            val telephony = controller.telephonyManager
            telephony.networkOperatorName?.takeIf { it.isNotBlank() }
                ?: telephony.simOperatorName?.takeIf { it.isNotBlank() }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to read mobile tile network name", e)
            null
        }

    private fun buildUsage(
        request: Request,
        wifiUsage: String? = null,
        mobileUsage: String? = null,
        mobileNetworkName: String? = request.mobile.name?.takeIf { it.isNotBlank() },
    ): LunarisQsDataUsage {
        val wifiName = request.wifiNetworkName
        val connections = request.connections
        val useMobile = usesMobileNetwork(connections)
        val useWifi = !connections.ethernet.isDefault && !useMobile && connections.wifi.isDefault
        return LunarisQsDataUsage(
            enabled = request.enabled,
            wifiNetworkName = wifiName,
            wifiUsage = wifiUsage,
            mobileNetworkName = mobileNetworkName,
            mobileUsage = mobileUsage,
            internetNetworkName =
                when {
                    useMobile -> mobileNetworkName
                    useWifi -> wifiName
                    else -> null
                },
            internetUsage =
                when {
                    useMobile -> mobileUsage
                    useWifi -> wifiUsage
                    else -> null
                },
        )
    }

    private fun usesMobileNetwork(connections: DefaultConnectionModel): Boolean =
        !connections.ethernet.isDefault &&
            (connections.mobile.isDefault || connections.carrierMerged.isDefault)

    private fun formattedUsage(query: () -> DataUsageController.DataUsageInfo?): String? =
        try {
            query()?.usageLevel?.takeIf { it >= 0 }?.let {
                Formatter.formatFileSize(context, it, Formatter.FLAG_SI_UNITS)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Unable to query tile data usage", e)
            null
        }

    private companion object {
        const val TAG = "LunarisQsDataUsage"
        const val REFRESH_INTERVAL_MILLIS = 60_000L
    }
}
