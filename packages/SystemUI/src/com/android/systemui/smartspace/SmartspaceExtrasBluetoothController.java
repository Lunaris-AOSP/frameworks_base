/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.smartspace;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.systemui.res.R;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.Executor;

final class SmartspaceExtrasBluetoothController {
    private static final String TAG = "SmartspaceBluetooth";
    private static final long[] IMAGE_RETRY_DELAYS = {1000, 3000, 10000};
    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter mAdapter;
    private final Consumer<List<SmartspaceExtrasCard>> mListener;
    private final Executor mMainExecutor;
    private final Executor mBackgroundExecutor;
    private final Map<BluetoothDevice, DeviceState> mDevices = new LinkedHashMap<>();
    private boolean mActive;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    private final BluetoothAdapter.OnMetadataChangedListener mMetadataListener =
            (device, key, value) -> {
                if (!mActive) return;
                DeviceState state = mDevices.get(device);
                if (state == null) return;
                if (key == BluetoothDevice.METADATA_MAIN_ICON) loadImage(state, true);
                publish();
            };

    SmartspaceExtrasBluetoothController(Context context, Executor mainExecutor,
            Executor backgroundExecutor, Consumer<List<SmartspaceExtrasCard>> listener) {
        mContext = context;
        mMainExecutor = mainExecutor;
        mBackgroundExecutor = backgroundExecutor;
        mListener = listener;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        mAdapter = manager == null ? null : manager.getAdapter();
    }

    void start() {
        if (mActive || mAdapter == null || mContext.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return;
        mActive = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ALIAS_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED);
        mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
        refresh();
    }

    void stop() {
        if (!mActive) return;
        mActive = false;
        mContext.unregisterReceiver(mReceiver);
        for (DeviceState state : mDevices.values()) unwatch(state);
        mDevices.clear();
    }

    private void refresh() {
        if (!mActive) return;
        Set<BluetoothDevice> connected = new HashSet<>();
        try {
            if (mAdapter.isEnabled()) {
                for (BluetoothDevice device : mAdapter.getBondedDevices()) {
                    if (device.isConnected()) connected.add(device);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot query connected Bluetooth devices", e);
        }
        for (BluetoothDevice device : new ArrayList<>(mDevices.keySet())) {
            if (!connected.contains(device)) unwatch(mDevices.remove(device));
        }
        List<BluetoothDevice> ordered = new ArrayList<>(connected);
        ordered.sort(Comparator.comparing(BluetoothDevice::getAddress));
        for (BluetoothDevice device : ordered) {
            DeviceState state = mDevices.get(device);
            if (state == null) {
                state = new DeviceState(device);
                mDevices.put(device, state);
            }
            if (!state.watching) {
                try {
                    state.watching = mAdapter.addOnMetadataChangedListener(
                            device, mMainExecutor, mMetadataListener);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Cannot observe Fast Pair metadata", e);
                }
            }
            loadImage(state, false);
        }
        publish();
    }

    private void unwatch(DeviceState state) {
        state.imageGeneration++;
        if (state.imageRetry != null) mHandler.removeCallbacks(state.imageRetry);
        if (!state.watching) return;
        try {
            mAdapter.removeOnMetadataChangedListener(state.device, mMetadataListener);
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot remove Bluetooth metadata listener", e);
        }
    }

    private void publish() {
        if (!mActive) return;
        List<SmartspaceExtrasCard> cards = new ArrayList<>();
        for (DeviceState state : mDevices.values()) {
            BluetoothDevice device = state.device;
            try {
                String name = device.getAlias();
                if (TextUtils.isEmpty(name)) name = mContext.getString(
                        R.string.smartspace_extras_bluetooth_device);
                List<String> batteries = new ArrayList<>();
                addBattery(batteries, device, BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY,
                        BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING,
                        R.string.smartspace_extras_bluetooth_left_battery);
                addBattery(batteries, device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY,
                        BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING,
                        R.string.smartspace_extras_bluetooth_right_battery);
                addBattery(batteries, device, BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY,
                        BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING,
                        R.string.smartspace_extras_bluetooth_case_battery);
                if (batteries.isEmpty()) {
                    int level = batteryMetadata(device, BluetoothDevice.METADATA_MAIN_BATTERY);
                    if (level < 0) level = device.getBatteryLevel();
                    if (level >= 0 && level <= 100) {
                        batteries.add(batteryText(level, R.string.smartspace_extras_bluetooth_battery,
                                metadata(device, BluetoothDevice.METADATA_MAIN_CHARGING)));
                    }
                }
                cards.add(new SmartspaceExtrasCard("bluetooth_" + device.getAddress(), name,
                        batteries.isEmpty()
                                ? mContext.getString(R.string.smartspace_extras_bluetooth_connected)
                                : TextUtils.join(" · ", batteries),
                        state.image, R.drawable.ic_bluetooth_connected, null,
                        new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                        SmartspaceExtrasCard.Type.BLUETOOTH));
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot read Bluetooth device", e);
            }
        }
        mListener.accept(cards);
    }

    private void addBattery(List<String> items, BluetoothDevice device, int batteryKey,
            int chargingKey, int label) {
        int level = batteryMetadata(device, batteryKey);
        if (level >= 0) items.add(batteryText(level, label, metadata(device, chargingKey)));
    }

    private String batteryText(int level, int label, String charging) {
        String text = mContext.getString(label, level);
        return Boolean.parseBoolean(charging)
                ? mContext.getString(R.string.smartspace_extras_bluetooth_charging, text) : text;
    }

    private static int batteryMetadata(BluetoothDevice device, int key) {
        return SmartspaceExtrasPolicy.parseBattery(metadata(device, key));
    }

    private static String metadata(BluetoothDevice device, int key) {
        try {
            byte[] value = device.getMetadata(key);
            return value == null ? null : new String(value, StandardCharsets.UTF_8).trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void loadImage(DeviceState state, boolean force) {
        String value = metadata(state.device, BluetoothDevice.METADATA_MAIN_ICON);
        Uri uri = TextUtils.isEmpty(value) ? null : Uri.parse(value);
        if (uri != null && !"content".equals(uri.getScheme())
                && !"android.resource".equals(uri.getScheme())) uri = null;
        boolean sameUri = Objects.equals(uri, state.imageUri);
        if (!force && sameUri && (state.imageLoading || state.image != null
                || state.imageRetry != null || state.imageAttempts > IMAGE_RETRY_DELAYS.length)) {
            return;
        }
        if (state.imageRetry != null) {
            mHandler.removeCallbacks(state.imageRetry);
            state.imageRetry = null;
        }
        if (!sameUri || force) state.imageAttempts = 0;
        if (!sameUri || force) state.image = null;
        state.imageUri = uri;
        int generation = ++state.imageGeneration;
        state.imageLoading = uri != null;
        if (uri == null) {
            state.image = null;
            return;
        }
        state.imageAttempts++;
        Uri imageUri = uri;
        mBackgroundExecutor.execute(() -> {
            Icon image = null;
            try {
                image = decodeImage(imageUri);
            } catch (Exception e) {
                Log.w(TAG, "Cannot load Fast Pair artwork", e);
            }
            Icon result = image;
            mMainExecutor.execute(() -> {
                if (!mActive || mDevices.get(state.device) != state
                        || state.imageGeneration != generation) return;
                state.imageLoading = false;
                if (result != null) state.image = result;
                if (result == null && state.imageAttempts <= IMAGE_RETRY_DELAYS.length) {
                    state.imageRetry = () -> {
                        state.imageRetry = null;
                        if (mActive && mDevices.get(state.device) == state) loadImage(state, false);
                    };
                    mHandler.postDelayed(state.imageRetry,
                            IMAGE_RETRY_DELAYS[state.imageAttempts - 1]);
                }
                publish();
            });
        });
    }

    private Icon decodeImage(Uri uri) throws java.io.IOException {
        if ("content".equals(uri.getScheme())
                && !"com.android.systemui.smartspace.bluetooth".equals(uri.getAuthority())) {
            try {
                mContext.getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
        }
        Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(
                mContext.getContentResolver(), uri), (decoder, info, source) -> {
                    int largest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    if (largest > 256) {
                        float scale = 256f / largest;
                        decoder.setTargetSize(Math.max(1,
                                Math.round(info.getSize().getWidth() * scale)), Math.max(1,
                                Math.round(info.getSize().getHeight() * scale)));
                    }
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
        return Icon.createWithBitmap(bitmap);
    }

    private static final class DeviceState {
        final BluetoothDevice device;
        boolean watching;
        Uri imageUri;
        Icon image;
        int imageGeneration;
        boolean imageLoading;
        int imageAttempts;
        Runnable imageRetry;

        DeviceState(BluetoothDevice device) {
            this.device = device;
        }
    }
}
