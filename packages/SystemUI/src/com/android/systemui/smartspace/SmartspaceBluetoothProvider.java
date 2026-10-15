
/*
 * Copyright (C) 2026 Lunaris-AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.smartspace;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class SmartspaceBluetoothProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return openFile(uri, mode, null);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        Context context = getContext();
        context.enforceCallingOrSelfPermission(Manifest.permission.MANAGE_SMARTSPACE,
                "Smartspace access required");
        context.enforceCallingOrSelfPermission(Manifest.permission.BLUETOOTH_CONNECT,
                "Bluetooth access required");
        List<String> path = uri.getPathSegments();
        if (!"r".equals(mode) || path.size() != 2 || !"device".equals(path.get(0))
                || !BluetoothAdapter.checkBluetoothAddress(path.get(1))
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new FileNotFoundException("Invalid Bluetooth artwork request");
        }
        long identity = Binder.clearCallingIdentity();
        try {
            if (signal != null) signal.throwIfCanceled();
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                throw new FileNotFoundException("Bluetooth is unavailable");
            }
            BluetoothDevice device = adapter.getRemoteDevice(path.get(1));
            if (device.getBondState() != BluetoothDevice.BOND_BONDED || !device.isConnected()) {
                throw new FileNotFoundException("Bluetooth device is not connected");
            }
            byte[] metadata = device.getMetadata(BluetoothDevice.METADATA_MAIN_ICON);
            if (metadata == null) throw new FileNotFoundException("No Fast Pair artwork metadata");
            Uri imageUri = Uri.parse(new String(metadata, StandardCharsets.UTF_8).trim());
            String imagePath = imageUri.getPath();
            if (!"content".equals(imageUri.getScheme())
                    || !"com.google.android.gms.fileprovider".equals(imageUri.getAuthority())
                    || imagePath == null || !imagePath.startsWith("/nearby_bluetooth_metadata/")) {
                throw new FileNotFoundException("Unsupported Fast Pair artwork source");
            }
            try {
                context.getContentResolver().takePersistableUriPermission(
                        imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
            ParcelFileDescriptor image = context.getContentResolver().openFileDescriptor(
                    imageUri, "r", signal);
            if (image == null) throw new FileNotFoundException("Fast Pair artwork is unavailable");
            return image;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
