/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.ims.data.repository

import android.content.Context
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.telephony.ims.ImsException
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.feature.MmTelFeature.MmTelCapabilities
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_CROSS_SIM
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_NR
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn

interface ImsRepository {
    val isVoLteAvailable: StateFlow<Boolean>
    val isVoWifiAvailable: StateFlow<Boolean>
    val isVoNrAvailable: StateFlow<Boolean>
}

@SysUISingleton
class ImsRepositoryStore
@Inject
constructor(
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    @Application private val context: Context,
) {
    private val cache = mutableMapOf<Int, WeakReference<ImsRepository>>()
    private val telephonyManager by lazy {
        context.getSystemService(TelephonyManager::class.java)
    }

    fun getRepoForSubId(subId: Int): ImsRepository =
        cache[subId]?.get()
            ?: ImsRepositoryImpl(
                    subId = subId,
                    scope = scope,
                    callbackExecutor = bgDispatcher.asExecutor(),
                    telephonyManagerFactory = { id ->
                        telephonyManager?.createForSubscriptionId(id)
                    },
                )
                .also { cache[subId] = WeakReference(it) }
}

@Suppress("DEPRECATION")
private class ImsRepositoryImpl(
    private val subId: Int,
    scope: CoroutineScope,
    callbackExecutor: Executor,
    imsManagerFactory: (Int) -> ImsMmTelManager = ImsMmTelManager::createForSubscriptionId,
    telephonyManagerFactory: (Int) -> TelephonyManager? = { null },
) : ImsRepository {
    private val imsAvailability: StateFlow<ImsAvailabilityState> =
        callbackFlow {
                val imsMmTelManager =
                    try {
                        imsManagerFactory(subId)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Unable to create ImsMmTelManager for subId=$subId", e)
                        throw e
                    }
                val telephonyManager = telephonyManagerFactory(subId)

                var capabilityRegistered = false
                var registrationRegistered = false
                var capabilityCallback: ImsMmTelManager.CapabilityCallback? = null
                var registrationCallback: ImsMmTelManager.RegistrationCallback? = null
                var telephonyRegistered = false
                var telephonyCallback: TelephonyCallback? = null
                var lastVoiceNetworkType: Int? = null

                fun sendAvailabilityUpdate() {
                    trySend(
                        queryAvailability(
                            imsMmTelManager = imsMmTelManager,
                            lastVoiceNetworkType = lastVoiceNetworkType,
                        )
                    )
                }

                fun unregisterCallbacks() {
                    if (capabilityRegistered) {
                        runCatching {
                                imsMmTelManager.unregisterMmTelCapabilityCallback(
                                    checkNotNull(capabilityCallback)
                                )
                            }
                            .onFailure {
                                Log.w(
                                    TAG,
                                    "Unable to unregister IMS capability callback for subId=$subId",
                                    it,
                                )
                            }
                    }

                    if (registrationRegistered) {
                        runCatching {
                                imsMmTelManager.unregisterImsRegistrationCallback(
                                    checkNotNull(registrationCallback)
                                )
                            }
                            .onFailure {
                                Log.w(
                                    TAG,
                                    "Unable to unregister IMS registration callback for subId=$subId",
                                    it,
                                )
                            }
                    }

                    if (telephonyRegistered) {
                        runCatching {
                                telephonyManager?.unregisterTelephonyCallback(
                                    checkNotNull(telephonyCallback)
                                )
                            }
                            .onFailure {
                                Log.w(
                                    TAG,
                                    "Unable to unregister TelephonyCallback for subId=$subId",
                                    it,
                                )
                            }
                    }
                }

                capabilityCallback =
                    object : ImsMmTelManager.CapabilityCallback() {
                        override fun onCapabilitiesStatusChanged(capabilities: MmTelCapabilities) {
                            sendAvailabilityUpdate()
                        }
                    }

                registrationCallback =
                    object : ImsMmTelManager.RegistrationCallback() {
                        override fun onRegistered(attributes: ImsRegistrationAttributes) {
                            sendAvailabilityUpdate()
                        }

                        override fun onRegistering(attributes: ImsRegistrationAttributes) {
                            trySend(ImsAvailabilityState())
                        }

                        override fun onUnregistered(
                            info: ImsReasonInfo,
                            suggestedAction: Int,
                            imsRadioTech: Int,
                        ) {
                            trySend(ImsAvailabilityState())
                        }

                        override fun onTechnologyChangeFailed(
                            imsTransportType: Int,
                            info: ImsReasonInfo,
                        ) {
                            sendAvailabilityUpdate()
                        }
                    }

                telephonyCallback =
                    object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
                        override fun onServiceStateChanged(serviceState: ServiceState) {
                            lastVoiceNetworkType = serviceState.voiceNetworkType
                            sendAvailabilityUpdate()
                        }
                    }

                try {
                    imsMmTelManager.registerMmTelCapabilityCallback(
                        callbackExecutor,
                        checkNotNull(capabilityCallback),
                    )
                    capabilityRegistered = true
                    imsMmTelManager.registerImsRegistrationCallback(
                        callbackExecutor,
                        checkNotNull(registrationCallback),
                    )
                    registrationRegistered = true
                } catch (e: ImsException) {
                    unregisterCallbacks()
                    Log.w(TAG, "Unable to register IMS callbacks for subId=$subId", e)
                    throw e
                } catch (e: RuntimeException) {
                    unregisterCallbacks()
                    Log.w(TAG, "Unable to register IMS callbacks for subId=$subId", e)
                    throw e
                }

                runCatching {
                        if (telephonyManager != null) {
                            telephonyManager.registerTelephonyCallback(
                                callbackExecutor,
                                checkNotNull(telephonyCallback),
                            )
                            telephonyRegistered = true
                        }
                    }
                    .onFailure {
                        Log.w(TAG, "Unable to register TelephonyCallback for subId=$subId", it)
                    }

                runCatching { telephonyManager?.serviceState }
                    .getOrNull()
                    ?.let { serviceState ->
                        lastVoiceNetworkType = serviceState.voiceNetworkType
                    }

                // Seed the current availability so we do not have to wait for a later state change.
                sendAvailabilityUpdate()

                awaitClose { unregisterCallbacks() }
            }
            .retryWhen { cause, attempt ->
                Log.w(
                    TAG,
                    "Retrying IMS callback registration for subId=$subId " +
                        "(attempt=${attempt + 1})",
                    cause,
                )
                delay(CALLBACK_REGISTRATION_RETRY_DELAY_MS)
                true
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), ImsAvailabilityState())

    override val isVoLteAvailable: StateFlow<Boolean> =
        imsAvailability
            .map { availability ->
                val prioritizedAvailability = availability.prioritizeImsIcons()
                prioritizedAvailability.isVoLteAvailable
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isVoWifiAvailable: StateFlow<Boolean> =
        imsAvailability
            .map { availability ->
                val prioritizedAvailability = availability.prioritizeImsIcons()
                prioritizedAvailability.isVoWifiAvailable
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isVoNrAvailable: StateFlow<Boolean> =
        imsAvailability
            .map { availability ->
                val prioritizedAvailability = availability.prioritizeImsIcons()
                prioritizedAvailability.isVoNrAvailable
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private fun queryAvailability(
        imsMmTelManager: ImsMmTelManager,
        lastVoiceNetworkType: Int?,
    ): ImsAvailabilityState {
        val isVoNrFromVoiceNetwork =
            lastVoiceNetworkType == TelephonyManager.NETWORK_TYPE_NR
        return ImsAvailabilityState(
            isVoLteAvailable =
                queryVoiceAvailability(imsMmTelManager, REGISTRATION_TECH_LTE),
            isVoWifiAvailable =
                queryVoiceAvailability(imsMmTelManager, REGISTRATION_TECH_IWLAN) ||
                    queryVoiceAvailability(imsMmTelManager, REGISTRATION_TECH_CROSS_SIM),
            isVoNrAvailable =
                queryVoiceAvailability(imsMmTelManager, REGISTRATION_TECH_NR) ||
                    isVoNrFromVoiceNetwork,
        )
    }

    private fun queryVoiceAvailability(
        imsMmTelManager: ImsMmTelManager,
        registrationTech: Int,
    ): Boolean =
        runCatching {
                imsMmTelManager.isAvailable(
                    MmTelCapabilities.CAPABILITY_TYPE_VOICE,
                    registrationTech,
                )
            }
            .getOrElse {
                Log.w(
                    TAG,
                    "Unable to query IMS voice availability for subId=$subId " +
                        "regTech=${registrationTechToString(registrationTech)}",
                    it,
                )
                false
            }

    private data class ImsAvailabilityState(
        val isVoLteAvailable: Boolean = false,
        val isVoWifiAvailable: Boolean = false,
        val isVoNrAvailable: Boolean = false,
    ) {
        fun prioritizeImsIcons(): ImsAvailabilityState {
            return when {
                isVoWifiAvailable ->
                    copy(
                        isVoLteAvailable = false,
                        isVoNrAvailable = false,
                    )
                isVoNrAvailable ->
                    copy(isVoLteAvailable = false)
                else -> this
            }
        }
    }

    companion object {
        private const val TAG = "ImsRepository"
        private const val CALLBACK_REGISTRATION_RETRY_DELAY_MS = 2_000L

        private fun registrationTechToString(registrationTech: Int): String =
            when (registrationTech) {
                REGISTRATION_TECH_LTE -> "LTE"
                REGISTRATION_TECH_IWLAN -> "IWLAN"
                REGISTRATION_TECH_CROSS_SIM -> "CROSS_SIM"
                REGISTRATION_TECH_NR -> "NR"
                else -> registrationTech.toString()
            }
    }
}
