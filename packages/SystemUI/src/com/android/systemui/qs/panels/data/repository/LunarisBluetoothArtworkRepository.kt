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

package com.android.systemui.qs.panels.data.repository

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

@SysUISingleton
class LunarisBluetoothArtworkRepository
@Inject
constructor(
    @Application private val context: Context,
    private val bluetoothController: BluetoothController,
    @Application applicationScope: CoroutineScope,
    @Main mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    private data class DeviceRequest(val device: BluetoothDevice?, val revision: Int)

    private val requests =
        conflatedCallbackFlow<DeviceRequest> {
            var observedDevice: CachedBluetoothDevice? = null
            var revision = 0
            var listening = true
            val metadataListener =
                BluetoothAdapter.OnMetadataChangedListener { device, key, _ ->
                    if (
                        listening && device == observedDevice?.device &&
                            key == BluetoothDevice.METADATA_MAIN_ICON
                    ) {
                        trySend(DeviceRequest(device, ++revision))
                    }
                }

            fun stopObserving() {
                observedDevice?.let { device ->
                    try {
                        bluetoothController.removeOnMetadataChangedListener(device, metadataListener)
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "Unable to remove Bluetooth artwork listener", e)
                    }
                }
                observedDevice = null
            }

            fun refresh() {
                if (!listening) return
                val device =
                    bluetoothController.connectedDevices.singleOrNull()
                        ?.takeIf { bluetoothController.isBluetoothEnabled }
                if (device != observedDevice) {
                    stopObserving()
                    observedDevice = device
                    device?.let {
                        try {
                            bluetoothController.addOnMetadataChangedListener(
                                it, context.mainExecutor, metadataListener,
                            )
                        } catch (e: RuntimeException) {
                            Log.w(TAG, "Unable to observe Bluetooth artwork", e)
                        }
                    }
                }
                trySend(DeviceRequest(device?.device, revision))
            }

            val callback =
                object : BluetoothController.Callback {
                    override fun onBluetoothStateChange(enabled: Boolean) = refresh()
                    override fun onBluetoothDevicesChanged() = refresh()
                }
            bluetoothController.addCallback(callback)
            refresh()
            awaitClose {
                listening = false
                bluetoothController.removeCallback(callback)
                stopObserving()
            }
        }.flowOn(mainDispatcher)

    val artwork: StateFlow<Drawable?> =
        requests
            .map { request ->
                val uri =
                    try {
                        request.device?.getMetadata(BluetoothDevice.METADATA_MAIN_ICON)
                            ?.toString(Charsets.UTF_8)
                            ?.takeIf(String::isNotBlank)
                            ?.let(Uri::parse)
                            ?.takeIf { it.scheme == "content" || it.scheme == "android.resource" }
                    } catch (_: RuntimeException) {
                        null
                    }
                request to uri
            }
            .flowOn(backgroundDispatcher)
            .distinctUntilChanged()
            .transformLatest<Pair<DeviceRequest, Uri?>, Drawable?> { (_, uri) ->
                emit(null)
                if (uri != null) {
                    emit(withContext(backgroundDispatcher) { loadArtwork(uri) })
                }
            }
            .stateIn(
                applicationScope,
                SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
                null,
            )

    private fun loadArtwork(uri: Uri): Drawable? =
        try {
            if (uri.scheme == "content") {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                }
            }
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val largest = maxOf(info.size.width, info.size.height)
                if (largest > IMAGE_SIZE_PX) {
                    val scale = IMAGE_SIZE_PX.toFloat() / largest
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            BitmapDrawable(context.resources, bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to load Bluetooth artwork", e)
            null
        }

    private companion object {
        const val TAG = "LunarisBluetoothArtwork"
        const val IMAGE_SIZE_PX = 256
    }
}
