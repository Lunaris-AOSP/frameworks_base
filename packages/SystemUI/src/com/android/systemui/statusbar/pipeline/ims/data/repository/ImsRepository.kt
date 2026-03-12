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

import android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_INVALID
import android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WLAN
import android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN
import android.telephony.ims.ImsException
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.RegistrationManager.REGISTRATION_STATE_REGISTERED
import android.telephony.ims.feature.MmTelFeature.MmTelCapabilities
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
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

interface ImsRepository {
    val isVoLteAvailable: StateFlow<Boolean>
    val isVoWifiAvailable: StateFlow<Boolean>
}

@SysUISingleton
class ImsRepositoryStore
@Inject
constructor(
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
) {
    private val cache = mutableMapOf<Int, WeakReference<ImsRepository>>()

    fun getRepoForSubId(subId: Int): ImsRepository =
        cache[subId]?.get()
            ?: ImsRepositoryImpl(
                    subId = subId,
                    scope = scope,
                    callbackExecutor = bgDispatcher.asExecutor(),
                )
                .also { cache[subId] = WeakReference(it) }
}

@Suppress("DEPRECATION")
private class ImsRepositoryImpl(
    subId: Int,
    scope: CoroutineScope,
    callbackExecutor: Executor,
    imsManagerFactory: (Int) -> ImsMmTelManager = ImsMmTelManager::createForSubscriptionId,
) : ImsRepository {
    private val imsState: StateFlow<ImsConnectionState> =
        callbackFlow {
                val imsMmTelManager =
                    try {
                        imsManagerFactory(subId)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Unable to create ImsMmTelManager for subId=$subId", e)
                        throw e
                    }

                var capabilityRegistered = false
                var registrationRegistered = false
                var capabilityCallback: ImsMmTelManager.CapabilityCallback? = null
                var registrationCallback: ImsMmTelManager.RegistrationCallback? = null

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
                }

                capabilityCallback =
                    object : ImsMmTelManager.CapabilityCallback() {
                        override fun onCapabilitiesStatusChanged(
                            capabilities: MmTelCapabilities
                        ) {
                            trySend(
                                ImsCallbackEvent.OnVoiceCapabilityChanged(
                                    capabilities.isCapable(MmTelCapabilities.CAPABILITY_TYPE_VOICE)
                                )
                            )
                        }
                    }

                registrationCallback =
                    object : ImsMmTelManager.RegistrationCallback() {
                        override fun onRegistered(imsTransportType: Int) {
                            trySend(ImsCallbackEvent.OnRegistrationStateChanged(true))
                            trySend(ImsCallbackEvent.OnTransportTypeChanged(imsTransportType))
                        }

                        override fun onRegistering(imsTransportType: Int) {
                            trySend(ImsCallbackEvent.OnRegistrationStateChanged(false))
                            trySend(ImsCallbackEvent.OnTransportTypeChanged(TRANSPORT_TYPE_INVALID))
                        }

                        override fun onUnregistered(info: ImsReasonInfo) {
                            trySend(ImsCallbackEvent.OnRegistrationStateChanged(false))
                            trySend(ImsCallbackEvent.OnTransportTypeChanged(TRANSPORT_TYPE_INVALID))
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
                        imsMmTelManager.getRegistrationState(callbackExecutor) { registrationState ->
                            trySend(
                                ImsCallbackEvent.OnRegistrationStateChanged(
                                    registrationState == REGISTRATION_STATE_REGISTERED
                                )
                            )
                        }
                    }
                    .onFailure {
                        Log.w(TAG, "Unable to query IMS registration state for subId=$subId", it)
                    }

                runCatching {
                        imsMmTelManager.getRegistrationTransportType(callbackExecutor) {
                            transportType ->
                            trySend(ImsCallbackEvent.OnTransportTypeChanged(transportType))
                        }
                    }
                    .onFailure {
                        Log.w(
                            TAG,
                            "Unable to query IMS registration transport for subId=$subId",
                            it,
                        )
                    }

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
            .scan(ImsConnectionState()) { state, event -> state.applyEvent(event) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), ImsConnectionState())

    override val isVoLteAvailable: StateFlow<Boolean> =
        imsState
            .map { state ->
                state.isRegistered &&
                    state.voiceCapable &&
                    state.transportType == TRANSPORT_TYPE_WWAN
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isVoWifiAvailable: StateFlow<Boolean> =
        imsState
            .map { state ->
                state.isRegistered &&
                    state.voiceCapable &&
                    state.transportType == TRANSPORT_TYPE_WLAN
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private data class ImsConnectionState(
        val isRegistered: Boolean = false,
        val transportType: Int = TRANSPORT_TYPE_INVALID,
        val voiceCapable: Boolean = false,
    ) {
        fun applyEvent(event: ImsCallbackEvent): ImsConnectionState {
            return when (event) {
                is ImsCallbackEvent.OnRegistrationStateChanged ->
                    copy(isRegistered = event.isRegistered)
                is ImsCallbackEvent.OnTransportTypeChanged ->
                    copy(transportType = event.transportType)
                is ImsCallbackEvent.OnVoiceCapabilityChanged ->
                    copy(voiceCapable = event.isVoiceCapable)
            }
        }
    }

    private sealed interface ImsCallbackEvent {
        data class OnRegistrationStateChanged(val isRegistered: Boolean) : ImsCallbackEvent

        data class OnTransportTypeChanged(val transportType: Int) : ImsCallbackEvent

        data class OnVoiceCapabilityChanged(val isVoiceCapable: Boolean) : ImsCallbackEvent
    }

    companion object {
        private const val TAG = "ImsRepository"
        private const val CALLBACK_REGISTRATION_RETRY_DELAY_MS = 2_000L
    }
}
