/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.graphics.fonts;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.graphics.fonts.FontManager;
import android.graphics.fonts.SystemFonts;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SharedMemory;
import android.os.UserHandle;
import android.os.UserManager;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Puts the custom emoji font into apps that ship or download their own emoji, as the
 * Magisk-iOS-Emoji module's service.sh does. App data can only be written by installd, so the
 * file work goes through it.
 */
final class AppEmojiReplacer {

    private static final String TAG = "AppEmojiReplacer";

    private static final String GMS = "com.google.android.gms";
    private static final String MESSENGER = "com.facebook.orca";

    private static final ComponentName[] GMS_FONT_COMPONENTS = {
            new ComponentName(GMS, "com.google.android.gms.fonts.provider.FontsProvider"),
            new ComponentName(GMS, "com.google.android.gms.fonts.update.UpdateSchedulerService"),
    };

    private static final Map<String, String[]> LOCKED_FILES = Map.of(
            MESSENGER, new String[] {"app_ras_blobs/FacebookEmoji.ttf"},
            "com.facebook.katana", new String[] {"app_ras_blobs/FacebookEmoji.ttf"});

    private static final Set<String> CACHE_CLEARED_PACKAGES = Set.of(
            MESSENGER, "com.facebook.katana", "com.facebook.lite", "com.facebook.mlite",
            "com.google.android.inputmethod.latin");
    private static final String[] CACHE_DIRS = {
            "cache", "code_cache", "app_webview", "files/GCache",
    };

    private static final String[] NO_FILES = new String[0];

    private final Context mContext;
    private final AtomicFile mStateFile;
    private final Properties mState = new Properties();
    private Installer mInstaller;

    AppEmojiReplacer(@NonNull Context context, @NonNull File stateFile) {
        mContext = context;
        mStateFile = new AtomicFile(stateFile);
        if (mStateFile.exists()) {
            try (FileInputStream input = mStateFile.openRead()) {
                mState.load(input);
            } catch (IOException | IllegalArgumentException e) {
                mState.clear();
                Slog.w(TAG, "Failed to read emoji app state", e);
            }
        }
    }

    /**
     * Returns the number of app files written. Blocking. Runs one at a time, so two callers
     * can't write the same app files at once.
     *
     * @param callerUid the app that asked; it is never force-stopped under its own feet
     */
    synchronized int apply(@NonNull ParcelFileDescriptor font, int callerUid) {
        final long token = Binder.clearCallingIdentity();
        try (ParcelFileDescriptor transferred = transferFont(font)) {
            Installer installer = getInstaller();
            int written = 0;
            UserManager um = mContext.getSystemService(UserManager.class);
            for (int userId : unlockedUsers()) {
                String serial = Integer.toString(um.getUserSerialNumber(userId));
                String saved = mState.getProperty(userKey(userId));
                if (saved != null && !serial.equals(saved) && !removeUser(userId)) {
                    return FontManager.RESULT_ERROR_FAILED_UPDATE_CONFIG;
                }
                mState.setProperty(userKey(userId), serial);
                if (!writeState()) return FontManager.RESULT_ERROR_FAILED_UPDATE_CONFIG;
                setGmsFontComponentsEnabled(userId, false);
                List<ApplicationInfo> apps = mContext.getPackageManager()
                        .getInstalledApplicationsAsUser(0, userId);
                for (ApplicationInfo app : apps) {
                    written += Math.max(0,
                            applyToApp(installer, transferred, app, userId, callerUid, true));
                }
            }
            return written;
        } catch (IOException e) {
            Slog.w(TAG, "Failed to transfer custom emoji font", e);
            return FontManager.RESULT_ERROR_FAILED_TO_OPEN_FONT_FILE;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Restores stock emoji in affected app files, re-enables the GMS font provider, and lets
     * Messenger download fonts again.
     */
    synchronized void restore() {
        if (mState.isEmpty()) return;
        final int callerUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            File stock = new File(SystemFonts.SYSTEM_FONT_DIR, "NotoColorEmoji.ttf");
            try (ParcelFileDescriptor font = ParcelFileDescriptor.open(
                    stock, ParcelFileDescriptor.MODE_READ_ONLY);
                    ParcelFileDescriptor transferred = transferFont(font)) {
                UserManager um = mContext.getSystemService(UserManager.class);
                for (UserInfo user : um.getAliveUsers()) {
                    String serial = mState.getProperty(userKey(user.id));
                    if (serial == null) continue;
                    if (!Integer.toString(user.serialNumber).equals(serial)) {
                        removeUser(user.id);
                        continue;
                    }
                    boolean restored = setGmsFontComponentsEnabled(user.id, true);
                    if (!um.isUserUnlocked(user.id)) continue;
                    List<ApplicationInfo> apps = mContext.getPackageManager()
                            .getInstalledApplicationsAsUser(0, user.id);
                    for (ApplicationInfo app : apps) {
                        if (applyToApp(getInstaller(), transferred, app, user.id,
                                callerUid, false) < 0) {
                            restored = false;
                        }
                    }
                    if (unblockMessengerFonts(user.id) && restored) {
                        mState.remove(userKey(user.id));
                        writeState();
                    }
                }
            } catch (IOException e) {
                Slog.w(TAG, "Failed to restore stock emoji in apps", e);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean removeUser(int userId) {
        for (String key : new ArrayList<>(mState.stringPropertyNames())) {
            if (key.equals(userKey(userId)) || key.startsWith(userKey(userId) + ".")) {
                mState.remove(key);
            }
        }
        return writeState();
    }

    private boolean unblockMessengerFonts(int userId) {
        ApplicationInfo app;
        try {
            app = mContext.getPackageManager().getApplicationInfoAsUser(MESSENGER, 0, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
        try {
            getInstaller().clearAppDataSubdir(app.volumeUuid, MESSENGER, userId,
                    UserHandle.getAppId(app.uid), "files/fonts", false);
            return true;
        } catch (InstallerException e) {
            Slog.w(TAG, "Failed to unblock Messenger fonts for user " + userId, e);
            return false;
        }
    }

    private int applyToApp(Installer installer, ParcelFileDescriptor font, ApplicationInfo app,
            int userId, int callerUid, boolean custom) {
        String pkg = app.packageName;
        int appId = UserHandle.getAppId(app.uid);
        if (appId < Process.FIRST_APPLICATION_UID || appId > Process.LAST_APPLICATION_UID) {
            return 0;
        }
        String uuid = app.volumeUuid;
        int written = 0;
        boolean touched = false;
        try {
            written = installer.replaceAppEmojiFonts(uuid, pkg, userId, appId, font,
                    LOCKED_FILES.getOrDefault(pkg, NO_FILES));
            if (custom && MESSENGER.equals(pkg)) {
                installer.clearAppDataSubdir(uuid, pkg, userId, appId, "files/fonts", true);
                touched = true;
            }
            if (CACHE_CLEARED_PACKAGES.contains(pkg)) {
                for (String dir : CACHE_DIRS) {
                    installer.clearAppDataSubdir(uuid, pkg, userId, appId, dir, false);
                }
                touched = true;
            }
            if (GMS.equals(pkg)) {
                installer.clearAppDataSubdir(uuid, pkg, userId, appId, "files/fonts", false);
            }
        } catch (InstallerException e) {
            Slog.w(TAG, "Failed to replace emoji fonts of " + pkg + " for user " + userId, e);
            written = -1;
        }
        if ((written > 0 || touched) && canForceStop(app, callerUid)) {
            mContext.getSystemService(ActivityManager.class).forceStopPackageAsUser(pkg, userId);
        }
        return written;
    }

    private static boolean canForceStop(ApplicationInfo app, int callerUid) {
        if (GMS.equals(app.packageName)) {
            return false;
        }
        if ((app.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) {
            return false;
        }
        int appId = UserHandle.getAppId(app.uid);
        return appId >= Process.FIRST_APPLICATION_UID
                && appId != UserHandle.getAppId(callerUid);
    }

    private int[] unlockedUsers() {
        UserManager um = mContext.getSystemService(UserManager.class);
        return um.getAliveUsers().stream()
                .mapToInt(user -> user.id)
                .filter(um::isUserUnlocked)
                .toArray();
    }

    private boolean setGmsFontComponentsEnabled(int userId, boolean enabled) {
        IPackageManager pm = AppGlobals.getPackageManager();
        boolean success = true;
        for (ComponentName component : GMS_FONT_COMPONENTS) {
            String key = userKey(userId) + "." + component.getClassName();
            try {
                String saved = mState.getProperty(key);
                if (enabled && saved == null) continue;
                int current = pm.getComponentEnabledSetting(component, userId);
                if (enabled) {
                    if (current == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        pm.setComponentEnabledSetting(component, Integer.parseInt(saved),
                                PackageManager.DONT_KILL_APP, userId, mContext.getOpPackageName());
                    }
                    mState.remove(key);
                    success &= writeState();
                } else {
                    if (current != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                            && current != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                        continue;
                    }
                    mState.setProperty(key, Integer.toString(current));
                    if (!writeState()) {
                        success = false;
                        continue;
                    }
                    pm.setComponentEnabledSetting(component,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP, userId, mContext.getOpPackageName());
                }
            } catch (Exception e) {
                Slog.i(TAG, "Couldn't change " + component + " for user " + userId + ": " + e);
                if (enabled && e instanceof IllegalArgumentException) {
                    mState.remove(key);
                    success &= writeState();
                } else {
                    success = false;
                }
            }
        }
        return success;
    }

    private static String userKey(int userId) {
        return "user." + userId;
    }

    private static ParcelFileDescriptor transferFont(ParcelFileDescriptor font) throws IOException {
        try (ParcelFileDescriptor input = ParcelFileDescriptor.dup(font.getFileDescriptor());
                FileInputStream stream = new FileInputStream(input.getFileDescriptor())) {
            FileChannel channel = stream.getChannel();
            long size = channel.size();
            if (size <= 0 || size > 128L * 1024L * 1024L) {
                throw new IOException("Invalid emoji font size: " + size);
            }
            try (SharedMemory memory = SharedMemory.create("system-emoji", (int) size)) {
                ByteBuffer buffer = memory.mapReadWrite();
                try {
                    long offset = 0;
                    while (buffer.hasRemaining()) {
                        int read = channel.read(buffer, offset);
                        if (read <= 0) throw new IOException("Incomplete emoji font transfer");
                        offset += read;
                    }
                } finally {
                    SharedMemory.unmap(buffer);
                }
                if (!memory.setProtect(OsConstants.PROT_READ)) {
                    throw new IOException("Failed to protect the transferred emoji font");
                }
                return memory.getFdDup();
            }
        } catch (ErrnoException e) {
            throw new IOException("Failed to create shared emoji font", e);
        }
    }

    private boolean writeState() {
        FileOutputStream output = null;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            mState.store(buffer, null);
            byte[] contents = buffer.toByteArray();
            output = mStateFile.startWrite();
            output.write(contents);
            output.getFD().sync();
            mStateFile.finishWrite(output);
            output = null;
            if (!Arrays.equals(contents, mStateFile.readFully())) {
                throw new IOException("Emoji app state was not committed");
            }
            return true;
        } catch (IOException e) {
            if (output != null) mStateFile.failWrite(output);
            Slog.w(TAG, "Failed to write emoji app state", e);
            mState.clear();
            if (mStateFile.exists()) {
                try (FileInputStream input = mStateFile.openRead()) {
                    mState.load(input);
                } catch (IOException | IllegalArgumentException readError) {
                    Slog.w(TAG, "Failed to reload emoji app state", readError);
                }
            }
            return false;
        }
    }

    private synchronized Installer getInstaller() {
        if (mInstaller == null) {
            mInstaller = new Installer(mContext);
            mInstaller.onStart();
        }
        return mInstaller;
    }
}
