/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.edgego;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherApps;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Choreographer;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Toast;

import com.android.internal.util.ScreenshotHelper;
import com.android.internal.util.lunaris.EdgeGoConfig;
import com.android.internal.util.lunaris.EdgeGoWindowActions;
import com.android.settingslib.display.BrightnessUtils;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.ReduceBrightColorsController;
import com.android.systemui.recents.Recents;
import com.android.systemui.settings.UserFileManager;
import com.android.systemui.res.R;
import com.android.systemui.screenrecord.ScreenRecordUxController;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.connectivity.NetworkController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.splitscreen.SplitScreen;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;
import javax.inject.Provider;

@SysUISingleton
public final class EdgeGoActions {
    interface Host {
        EdgeGoConfig config();
        Context userContext();
        int userId();
        String foreground();
        boolean canRun();
        boolean locked();
        boolean canNavigateBack();
        long flags();
        void showPanel(String action, EdgeGoGestureController.Zone zone, float x, float y, boolean tracking);
        void closePanels();
        boolean prepareAppAction();
        void mediaChanged();
        void hideFeedback();
        void showTextPicker(ArrayList<Bundle> texts);
        void trackPanel(float x, float y);
        void showControl(String action, String label, float value, EdgeGoGestureController.Zone zone, float x, float y);
        void pauseZone(EdgeGoGestureController.Zone zone, long duration);
        void pauseFeature(long duration);
        void runMacro(String id);
    }

    private static final String TAG = "EdgeGoActions";
    private static final List<String> PANELS = Arrays.asList("media_controller", "notch_media",
            "app_launcher", "show_quick_access", "quick_tools", "side_panel", "notch_apps",
            "notification_carousel", "preset_picker", "touch_pad", "remote_cursor", "drawing_pad",
            "notch_timer", "screen_blackout", "page_scroll");
    private static final List<String> LOCK_ACTIONS = Arrays.asList("flashlight", "volume_up", "volume_down",
            "ring_volume_up", "ring_volume_down", "notification_volume_up", "notification_volume_down",
            "volume_slider", "brightness_slider", "brightness_up", "brightness_down", "extra_dim",
            "lock_screen", "power_menu", "media_play_pause", "media_next", "media_previous", "media_stop",
            "media_scrub", "media_controller", "notch_media", "open_volume_panel", "notifications",
            "quick_settings", "dismiss_notification_shade", "close", "tap_through", "back");

    private final Context context;
    private final Handler handler;
    private final Executor background;
    private final ActivityStarter starter;
    private final Optional<Recents> recents;
    private final ShadeController shade;
    private final FlashlightController flashlight;
    private final BluetoothController bluetooth;
    private final LocationController location;
    private final RotationLockController rotation;
    private final ZenModeController zen;
    private final BatteryController battery;
    private final NetworkController network;
    private final ReduceBrightColorsController dim;
    private final Provider<AssistManager> assistant;
    private final Provider<ScreenRecordUxController> recording;
    private final Optional<OneHanded> oneHanded;
    private final Optional<SplitScreen> splitScreen;
    private final IWindowManager windows;
    private final AudioManager audio;
    private final DisplayManager display;
    private final InputManager input;
    private final MediaSessionManager sessions;
    private final UserManager users;
    private final UserFileManager files;
    private final Provider<EdgeGoNotifications> notifications;
    private Host host;
    private EdgeGoSchedules schedules;
    private EdgeGoPrivateStore privateStore;
    private final ScreenshotHelper screenshotHelper;
    private MediaController media;
    private boolean mediaListening, scrolling, scrollPaused, requestInFlight;
    private int generation, unlockToken, mediaGeneration, scrollDirection = 1, scrollSpeed = 3;
    private String continuous = "", scrollTarget;
    private int continuousStream, continuousPlaybackType;
    private int continuousVolumeRange, currentRemoteVolume, lastAppliedVolume;
    private boolean continuousRelativeVolume;
    private int lastRemoteVolume = -1;
    private long lastRemoteVolumeTime;
    private EdgeGoGestureController.Zone continuousZone;
    private float originX, originY, currentLevel, controlX, controlY;
    private float controlDirectionX, controlDirectionY, controlSign, controlStep;
    private boolean continuousIncremental, continuousFrameScheduled;
    private long lastControlFrame;
    private String continuousLabel;
    private final Choreographer.FrameCallback continuousFrame = this::advanceContinuous;
    private long initialPosition, seekDuration, lastSeek;
    private String seekMediaId, seekTitle;
    private final Runnable scroll = this::scrollStep;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionListener;
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) {
            if (continuous.equals("media_scrub") && (state == null
                    || (state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0)) endContinuous(true);
        }
        @Override public void onSessionDestroyed() {
            if (continuous.equals("media_scrub") || continuous.equals("volume_slider")
                    && continuousPlaybackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) endContinuous(true);
        }
        @Override public void onMetadataChanged(MediaMetadata metadata) {
            if (continuous.equals("media_scrub") && (metadata == null
                    || metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) != seekDuration
                    || !Objects.equals(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), seekMediaId)
                    || !Objects.equals(metadata.getString(MediaMetadata.METADATA_KEY_TITLE), seekTitle))) endContinuous(true);
        }
        @Override public void onAudioInfoChanged(MediaController.PlaybackInfo info) {
            if (continuous.equals("volume_slider") && continuousStream == AudioManager.STREAM_MUSIC) {
                if (info == null || info.getPlaybackType() != continuousPlaybackType
                        || continuousPlaybackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
                        && (info.getMaxVolume() != continuousVolumeRange
                        || info.getVolumeControl() != (continuousRelativeVolume
                        ? android.media.VolumeProvider.VOLUME_CONTROL_RELATIVE
                        : android.media.VolumeProvider.VOLUME_CONTROL_ABSOLUTE))) {
                    endContinuous(true);
                } else if (continuousRelativeVolume) {
                    currentRemoteVolume = info.getCurrentVolume();
                    scheduleContinuousFrame();
                }
            }
        }
    };

    @Inject
    public EdgeGoActions(Context context, @Main Handler handler, @Background Executor background,
            ActivityStarter starter, Optional<Recents> recents, ShadeController shade,
            FlashlightController flashlight, BluetoothController bluetooth, LocationController location,
            RotationLockController rotation, ZenModeController zen, BatteryController battery,
            NetworkController network, ReduceBrightColorsController dim, Provider<AssistManager> assistant,
            Provider<ScreenRecordUxController> recording, Optional<OneHanded> oneHanded,
            Optional<SplitScreen> splitScreen, IWindowManager windows, UserFileManager files,
            Provider<EdgeGoNotifications> notifications) {
        this.context = context;
        this.handler = handler;
        this.background = background;
        this.starter = starter;
        this.recents = recents;
        this.shade = shade;
        this.flashlight = flashlight;
        this.bluetooth = bluetooth;
        this.location = location;
        this.rotation = rotation;
        this.zen = zen;
        this.battery = battery;
        this.network = network;
        this.dim = dim;
        this.assistant = assistant;
        this.recording = recording;
        this.oneHanded = oneHanded;
        this.splitScreen = splitScreen;
        this.windows = windows;
        this.files = files;
        this.notifications = notifications;
        audio = context.getSystemService(AudioManager.class);
        display = context.getSystemService(DisplayManager.class);
        input = context.getSystemService(InputManager.class);
        sessions = context.getSystemService(MediaSessionManager.class);
        users = context.getSystemService(UserManager.class);
        screenshotHelper = new ScreenshotHelper(context);
    }

    void bind(Host host) {
        this.host = host;
        schedules = new EdgeGoSchedules(context, handler, host, dim);
        privateStore = new EdgeGoPrivateStore(context, files, host);
    }

    void updateSettings() {
        unlockToken++;
        if (schedules != null) schedules.update();
        if (privateStore != null) privateStore.update();
    }

    void setActive(boolean active) {
        if (privateStore != null) privateStore.update();
        if (mediaListening == active) return;
        mediaListening = active;
        int token = ++mediaGeneration, expectedUser = host.userId();
        if (active) {
            try {
                sessionListener = controllers -> {
                    if (mediaListening && token == mediaGeneration && expectedUser == host.userId()) selectMedia(controllers);
                };
                sessions.addOnActiveSessionsChangedListener(null, UserHandle.of(expectedUser), handler::post, sessionListener);
                background.execute(() -> {
                    try {
                        List<MediaController> controllers = sessions.getActiveSessionsForUser(null, UserHandle.of(expectedUser));
                        handler.post(() -> { if (mediaListening && token == mediaGeneration && expectedUser == host.userId()) selectMedia(controllers); });
                    } catch (RuntimeException e) { Log.w(TAG, "Cannot read active media sessions", e); }
                });
            } catch (SecurityException e) { Log.w(TAG, "Media sessions unavailable", e); }
        } else {
            if (sessionListener != null) sessions.removeOnActiveSessionsChangedListener(sessionListener);
            sessionListener = null;
            if (media != null) media.unregisterCallback(mediaCallback);
            media = null;
        }
    }

    private void selectMedia(List<MediaController> controllers) {
        MediaController before = media;
        media = null;
        if (controllers != null) for (MediaController candidate : controllers) {
            PlaybackState state = candidate.getPlaybackState();
            if (media == null) media = candidate;
            if (state != null && (state.getState() == PlaybackState.STATE_PLAYING
                    || state.getState() == PlaybackState.STATE_BUFFERING)) { media = candidate; break; }
        }
        if (before != null && media != null && before.getSessionToken().equals(media.getSessionToken())) media = before;
        if (before != media) {
            lastRemoteVolume = -1;
            lastRemoteVolumeTime = 0;
            if (continuous.equals("media_scrub") || continuous.equals("volume_slider")) endContinuous(true);
            if (before != null) before.unregisterCallback(mediaCallback);
            if (media != null) media.registerCallback(mediaCallback, handler);
            host.mediaChanged();
        }
    }

    void onUserChanged() {
        unlockToken++;
        cancelInteraction();
        setActive(false);
        if (privateStore != null) privateStore.stop();
        if (schedules != null) schedules.cancel();
    }

    void execute(String raw, EdgeGoGestureController.Zone zone, float x, float y, boolean held) {
        String action = EdgeGoConfig.normalizeAction(raw);
        if (action.equals("none") || host == null || !host.canRun()) return;
        String base = action.split(":", 2)[0];
        int expectedUser = host.userId();
        int expectedUnlockToken = unlockToken;
        if (host.locked() && !LOCK_ACTIONS.contains(base)) {
            host.closePanels();
            starter.executeRunnableDismissingKeyguard(() -> handler.post(() -> {
                if (host.userId() == expectedUser && unlockToken == expectedUnlockToken && host.canRun() && !host.locked()) {
                    execute(action, zone, x, y, held);
                }
            }), null, true, true, false);
            return;
        }
        try {
            if (PANELS.contains(base)) {
                if (base.equals("page_scroll") && host.config().string("page_scroll_variant").equals("auto_scroll")) {
                    startScrolling(action.endsWith(":up") ? -1 : 1);
                }
                host.showPanel(action, zone, x, y, held && base.equals("show_quick_access"));
                return;
            }
            if (base.startsWith("launch_app") || base.equals("shortcut") || base.equals("intent")) {
                launch(action);
                return;
            }
            switch (base) {
                case "back":
                    if (!host.canNavigateBack()) return;
                    navigationKey(KeyEvent.KEYCODE_BACK);
                    break;
                case "home":
                    if ((host.flags() & QuickStepContract.SYSUI_STATE_HOME_DISABLED) == 0) {
                        navigationKey(KeyEvent.KEYCODE_HOME);
                    }
                    break;
                case "recents":
                    if ((host.flags() & QuickStepContract.SYSUI_STATE_OVERVIEW_DISABLED) == 0) {
                        host.prepareAppAction();
                        recents.ifPresent(Recents::toggleRecentApps);
                    }
                    break;
                case "previous_app": switchTask(false); break;
                case "split_screen": switchTask(true); break;
                case "notifications": host.closePanels(); shade.animateExpandShade(); break;
                case "quick_settings": host.closePanels(); shade.animateExpandQs(); break;
                case "dismiss_notification_shade": shade.animateCollapseShade(); break;
                case "power_menu": host.closePanels(); windows.showGlobalActions(); break;
                case "lock_screen":
                    host.closePanels();
                    windows.lockNow(null);
                    context.getSystemService(PowerManager.class).goToSleep(SystemClock.uptimeMillis(),
                            PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0);
                    break;
                case "screenshot": screenshot(false); break;
                case "partial_screenshot": screenshot(true); break;
                case "one_handed":
                    host.closePanels();
                    if (oneHanded.isEmpty()) { unavailable(); return; }
                    if (context.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            || (host.flags() & QuickStepContract.SYSUI_STATE_IME_VISIBLE) != 0) { unavailable(); return; }
                    if ((host.flags() & QuickStepContract.SYSUI_STATE_ONE_HANDED_ACTIVE) != 0) oneHanded.get().stopOneHanded();
                    else oneHanded.get().startOneHanded();
                    break;
                case "voice_command":
                    if ((host.flags() & QuickStepContract.SYSUI_STATE_SEARCH_DISABLED) == 0) assistant.get().startAssist(new Bundle());
                    break;
                case "flashlight": if (flashlight.isAvailable()) flashlight.setFlashlight(!flashlight.isEnabled()); else unavailable(); break;
                case "wifi":
                    if (restricted(UserManager.DISALLOW_CONFIG_WIFI)) return;
                    background.execute(() -> {
                        try {
                            WifiManager wifi = context.getSystemService(WifiManager.class);
                            if (host.canRun() && host.userId() == expectedUser) wifi.setWifiEnabled(!wifi.isWifiEnabled());
                        } catch (RuntimeException e) {
                            Log.w(TAG, "Cannot change Wi-Fi", e);
                            handler.post(() -> { if (host.canRun() && host.userId() == expectedUser) unavailable(); });
                        }
                    });
                    break;
                case "bluetooth": if (!restricted(UserManager.DISALLOW_BLUETOOTH)) bluetooth.setBluetoothEnabled(!bluetooth.isBluetoothEnabled()); break;
                case "mobile_data":
                    if (restricted(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS) || !network.hasMobileDataFeature()) return;
                    network.getMobileDataController().setMobileDataEnabled(!network.getMobileDataController().isMobileDataEnabled());
                    break;
                case "location": if (!restricted(UserManager.DISALLOW_SHARE_LOCATION)) location.setLocationEnabled(!location.isLocationEnabled()); break;
                case "auto_rotate": rotation.setRotationLocked(!rotation.isRotationLocked(), "EdgeGo"); break;
                case "do_not_disturb": if (zen.isZenAvailable()) zen.setZen(zen.getZen() == 0 ? Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS : 0, null, "EdgeGo"); break;
                case "battery_saver": battery.setPowerSaveMode(!battery.isPowerSave()); break;
                case "internet_panel": startActivity(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)); break;
                case "open_nfc": startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)); break;
                case "open_accessibility": startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); break;
                case "current_app_info":
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", host.foreground(), null)));
                    break;
                case "camera": startActivity(new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); break;
                case "open_volume_panel": audio.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI); break;
                case "volume_up": adjustVolume(AudioManager.STREAM_MUSIC, 1); break;
                case "volume_down": adjustVolume(AudioManager.STREAM_MUSIC, -1); break;
                case "ring_volume_up": adjustVolume(AudioManager.STREAM_RING, 1); break;
                case "ring_volume_down": adjustVolume(AudioManager.STREAM_RING, -1); break;
                case "notification_volume_up": adjustVolume(AudioManager.STREAM_NOTIFICATION, 1); break;
                case "notification_volume_down": adjustVolume(AudioManager.STREAM_NOTIFICATION, -1); break;
                case "mute_volume": mute(host.config().string("audio_mute_target")); break;
                case "mute_media": mute("media"); break;
                case "mute_ringtone": mute("ringtone"); break;
                case "mute_notifications": mute("notifications"); break;
                case "mute_all": mute("all"); break;
                case "brightness_up": setBrightness(brightness() + host.config().integer("brightness_step") / 100f, false); break;
                case "brightness_down": setBrightness(brightness() - host.config().integer("brightness_step") / 100f, false); break;
                case "auto_brightness":
                    int mode = Settings.System.getIntForUser(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0, host.userId());
                    Settings.System.putIntForUser(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, mode == 0 ? 1 : 0, host.userId());
                    break;
                case "cycle_screen_timeout": cycleScreenTimeout(); break;
                case "extra_dim": schedules.toggleDim(); break;
                case "media_play_pause": mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
                case "media_next": mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); break;
                case "media_previous": mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
                case "media_stop": mediaKey(KeyEvent.KEYCODE_MEDIA_STOP); break;
                case "song_search": startActivity(new Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH")); break;
                case "copy": windowAction(EdgeGoWindowActions.COPY, null); break;
                case "paste": windowAction(EdgeGoWindowActions.PASTE, null); break;
                case "select_all_text": windowAction(EdgeGoWindowActions.SELECT_ALL, null); break;
                case "scroll_to_top": windowAction(EdgeGoWindowActions.SCROLL_TOP, null); break;
                case "scroll_to_bottom": windowAction(EdgeGoWindowActions.SCROLL_BOTTOM, null); break;
                case "text_picker":
                    windowAction(EdgeGoWindowActions.GET_TEXT, result -> {
                        ArrayList<Bundle> texts = result.getParcelableArrayList("texts", Bundle.class);
                        if (texts != null && !texts.isEmpty()) host.showTextPicker(texts);
                    });
                    break;
                case "tap_through": host.pauseZone(zone, host.config().integer("tap_through_duration_ms")); break;
                case "pause_edgego": host.pauseFeature(60000); break;
                case "close": host.closePanels(); stopScrolling(); break;
                case "run_macro": host.closePanels(); host.runMacro(action.substring(action.indexOf(':') + 1)); break;
                case "apply_preset": applyPreset(action.substring(action.indexOf(':') + 1)); break;
                case "screen_record": host.closePanels(); recording.get().createScreenRecordDialog(null).show(); break;
                case "volume_slider": case "brightness_slider": case "media_scrub":
                    host.showPanel(base, zone, x, y, false);
                    break;
                default: unavailable();
            }
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Action unavailable: " + base, e);
            unavailable();
        }
    }

    private boolean restricted(String restriction) {
        if (!users.hasUserRestriction(restriction, UserHandle.of(host.userId()))) return false;
        unavailable();
        return true;
    }

    private void screenshot(boolean partial) {
        host.closePanels();
        host.hideFeedback();
        int token = generation, user = host.userId();
        handler.postDelayed(() -> {
            if (host.canRun() && host.userId() == user && generation == token) {
                screenshotHelper.takeScreenshot(partial ? WindowManager.TAKE_SCREENSHOT_SELECTED_REGION
                        : WindowManager.TAKE_SCREENSHOT_FULLSCREEN, WindowManager.ScreenshotSource.SCREENSHOT_GLOBAL_ACTIONS,
                        handler, null);
            }
        }, 80);
    }

    private void switchTask(boolean split) {
        if ((host.flags() & QuickStepContract.SYSUI_STATE_OVERVIEW_DISABLED) != 0) return;
        host.prepareAppAction();
        int expectedUser = host.userId(), token = generation;
        String expectedPackage = host.foreground();
        background.execute(() -> {
            try {
                List<ActivityManager.RecentTaskInfo> tasks = ActivityTaskManager.getService()
                        .getRecentTasks(12, ActivityManager.RECENT_IGNORE_UNAVAILABLE, expectedUser).getList();
                ActivityManager.RecentTaskInfo previous = null, current = null;
                for (ActivityManager.RecentTaskInfo task : tasks) {
                    if (task.baseIntent == null || task.getActivityType() == android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
                            || task.userId != expectedUser || task.persistentId < 0 || taskPackage(task).isEmpty()) continue;
                    if (current == null && taskPackage(task).equals(expectedPackage)) current = task;
                    else if (previous == null) previous = task;
                }
                ActivityManager.RecentTaskInfo first = current, second = previous;
                handler.post(() -> {
                    if (!host.canRun() || host.userId() != expectedUser || generation != token || second == null
                            || !expectedPackage.equals(host.foreground())) return;
                    try {
                        if (split) {
                            if (splitScreen.isEmpty() || first == null || !first.isResizeable || !second.isResizeable) { unavailable(); return; }
                            splitScreen.get().startTasks(first.persistentId, null, second.persistentId, null,
                                    com.android.wm.shell.shared.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT,
                                    com.android.wm.shell.shared.split.SplitScreenConstants.SNAP_TO_2_50_50, null, null);
                        } else {
                            ActivityTaskManager.getService().startActivityFromRecents(second.persistentId, ActivityOptions.makeBasic().toBundle());
                        }
                    } catch (RemoteException | RuntimeException e) { unavailable(); }
                });
            } catch (RemoteException | RuntimeException e) { handler.post(this::unavailable); }
        });
    }

    static String taskPackage(ActivityManager.RecentTaskInfo task) {
        android.content.ComponentName activity = task.topActivity != null ? task.topActivity : task.realActivity != null
                ? task.realActivity : task.baseIntent == null ? null : task.baseIntent.getComponent();
        return activity == null ? "" : activity.getPackageName();
    }

    private void launch(String action) {
        host.prepareAppAction();
        int expectedUser = host.userId(), token = generation;
        String[] parts = action.split(":", 2);
        background.execute(() -> {
            try {
                if (parts[0].equals("shortcut") || parts[0].equals("launch_app_quick")) {
                    String[] shortcut = parts[1].split("\\|", 2);
                    if (shortcut.length != 2) { handler.post(this::unavailable); return; }
                    handler.post(() -> {
                        if (host.canRun() && host.userId() == expectedUser && generation == token) {
                            try { context.getSystemService(LauncherApps.class).startShortcut(shortcut[0], shortcut[1], null, null, UserHandle.of(expectedUser)); }
                            catch (RuntimeException e) { unavailable(); }
                        }
                    });
                    return;
                }
                Intent intent;
                if (parts[0].equals("launch_app_recent")) {
                    String[] value = parts[1].split("\\|", 2);
                    if (value.length != 2) return;
                    int id = Integer.parseInt(value[1]);
                    List<ActivityManager.RecentTaskInfo> tasks = ActivityTaskManager.getService()
                            .getRecentTasks(64, ActivityManager.RECENT_IGNORE_UNAVAILABLE, expectedUser).getList();
                    boolean found = false;
                    for (ActivityManager.RecentTaskInfo task : tasks) if (task.persistentId == id
                            && task.userId == expectedUser && value[0].equals(taskPackage(task))) { found = true; break; }
                    if (!found) { handler.post(this::unavailable); return; }
                    handler.post(() -> {
                        if (host.canRun() && host.userId() == expectedUser && generation == token) {
                            try { ActivityTaskManager.getService().startActivityFromRecents(id, ActivityOptions.makeBasic().toBundle()); }
                            catch (RemoteException | RuntimeException e) { unavailable(); }
                        }
                    });
                    return;
                } else if (parts[0].equals("intent")) intent = Intent.parseUri(Uri.decode(parts[1]), Intent.URI_INTENT_SCHEME);
                else if (parts[0].equals("launch_app_activity")) {
                    String[] component = parts[1].split("\\|", 2);
                    if (component.length != 2) return;
                    intent = new Intent(Intent.ACTION_MAIN).setClassName(component[0], component[1]);
                } else {
                    intent = host.userContext().getPackageManager().getLaunchIntentForPackage(parts[1]);
                }
                if (intent == null) { handler.post(this::unavailable); return; }
                ActivityInfo info = intent.resolveActivityInfo(host.userContext().getPackageManager(), 0);
                if (info == null || !info.exported) { handler.post(this::unavailable); return; }
                if (info.permission != null) {
                    android.content.pm.PermissionInfo permission = host.userContext().getPackageManager().getPermissionInfo(info.permission, 0);
                    if ((permission.protectionLevel & android.content.pm.PermissionInfo.PROTECTION_MASK_BASE)
                            >= android.content.pm.PermissionInfo.PROTECTION_SIGNATURE) { handler.post(this::unavailable); return; }
                }
                intent.setClipData(null);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                Intent launch = intent;
                handler.post(() -> {
                    if (host.canRun() && host.userId() == expectedUser && generation == token) startActivity(launch);
                });
            } catch (Exception e) { handler.post(this::unavailable); }
        });
    }

    void startActivity(Intent intent) {
        host.prepareAppAction();
        try { starter.startActivity(intent, true, null, false, UserHandle.of(host.userId())); }
        catch (RuntimeException e) { unavailable(); }
    }

    void mediaKey(int keyCode) {
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    void seekBy(long delta) {
        if (media == null || !host.canRun()) return;
        PlaybackState state = media.getPlaybackState(); MediaMetadata metadata = media.getMetadata();
        if (state == null || metadata == null || (state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0) return;
        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) position += Math.round(
                (SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        media.getTransportControls().seekTo(Math.max(0, Math.min(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION), position + delta)));
    }

    private void key(int code) {
        long when = SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent event = new KeyEvent(when, when, action, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD);
            event.setDisplayId(Display.DEFAULT_DISPLAY);
            try { input.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC); }
            catch (RuntimeException e) { Log.w(TAG, "Cannot deliver a navigation key", e); return; }
        }
    }

    private void navigationKey(int code) {
        boolean changedFocus = host.prepareAppAction();
        int token = generation, expectedUser = host.userId();
        Runnable send = () -> {
            if (host.canRun() && token == generation && expectedUser == host.userId()) key(code);
        };
        if (changedFocus) handler.postDelayed(send, 64); else send.run();
    }

    void adjustVolume(int stream, int direction) {
        if (restricted(UserManager.DISALLOW_ADJUST_VOLUME)) return;
        if (stream == AudioManager.STREAM_MUSIC && remoteVolume()) media.adjustVolume(direction, AudioManager.FLAG_SHOW_UI);
        else audio.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI);
    }

    int volumeStream() {
        if (!host.config().bool("volume_slider_follow_active_audio")) return AudioManager.STREAM_MUSIC;
        int mode = audio.getMode();
        return mode == AudioManager.MODE_RINGTONE ? AudioManager.STREAM_RING
                : mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC;
    }

    float volume(int stream) {
        if (stream == AudioManager.STREAM_MUSIC && remoteVolume()) {
            MediaController.PlaybackInfo info = media.getPlaybackInfo();
            return info.getMaxVolume() == 0 ? 0 : info.getCurrentVolume() / (float) info.getMaxVolume();
        }
        int min = audio.getStreamMinVolume(stream), max = audio.getStreamMaxVolume(stream);
        return max == min ? 0 : (audio.getStreamVolume(stream) - min) / (float) (max - min);
    }

    void setVolume(int stream, float level) {
        if (stream == AudioManager.STREAM_MUSIC && remoteVolume()) {
            if (restricted(UserManager.DISALLOW_ADJUST_VOLUME)) return;
            MediaController.PlaybackInfo info = media.getPlaybackInfo();
            int desired = Math.round(Math.max(0, Math.min(1, level)) * info.getMaxVolume());
            long now = SystemClock.uptimeMillis();
            if (info.getVolumeControl() == android.media.VolumeProvider.VOLUME_CONTROL_ABSOLUTE
                    && desired != info.getCurrentVolume()
                    && (desired != lastRemoteVolume || now - lastRemoteVolumeTime >= 180)) {
                lastRemoteVolume = desired; lastRemoteVolumeTime = now;
                media.setVolumeTo(desired, 0);
            } else if (info.getVolumeControl() == android.media.VolumeProvider.VOLUME_CONTROL_RELATIVE
                    && desired != info.getCurrentVolume()
                    && now - lastRemoteVolumeTime >= host.config().integer("continuous_adjustment_interval_ms")) {
                lastRemoteVolume = desired; lastRemoteVolumeTime = now;
                media.adjustVolume(desired > info.getCurrentVolume() ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            }
            return;
        }
        if (audio.isVolumeFixed() || restricted(UserManager.DISALLOW_ADJUST_VOLUME)) return;
        int min = audio.getStreamMinVolume(stream), max = audio.getStreamMaxVolume(stream);
        int desired = min + Math.round(Math.max(0, Math.min(1, level)) * (max - min));
        if (desired != audio.getStreamVolume(stream)) audio.setStreamVolume(stream, desired, 0);
    }

    private boolean remoteVolume() {
        if (media == null) return false;
        MediaController.PlaybackInfo info = media.getPlaybackInfo();
        return info != null && info.getPlaybackType() == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE;
    }

    private void mute(String target) {
        if (audio.isVolumeFixed() || restricted(UserManager.DISALLOW_ADJUST_VOLUME)) return;
        int[] streams = target.equals("media") ? new int[]{AudioManager.STREAM_MUSIC}
                : target.equals("ringtone") ? new int[]{AudioManager.STREAM_RING}
                : target.equals("notifications") ? new int[]{AudioManager.STREAM_NOTIFICATION}
                : new int[]{AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION};
        JSONObject state = EdgeGoSchedules.state(context, host.userId());
        JSONObject snapshot = state.optJSONObject("muteSnapshot");
        if (snapshot == null) snapshot = new JSONObject();
        JSONObject saved = snapshot.optJSONObject("streams");
        if (saved == null) saved = new JSONObject();
        boolean restoring = false;
        for (int stream : streams) restoring |= saved.has(String.valueOf(stream));
        if (restoring) {
            boolean[] unchanged = new boolean[streams.length];
            boolean ringerUnchanged = audio.getRingerModeInternal() == snapshot.optInt("appliedRingerMode", audio.getRingerModeInternal());
            boolean restoreRinger = false;
            for (int i = 0; i < streams.length; i++) {
                JSONObject previous = saved.optJSONObject(String.valueOf(streams[i]));
                unchanged[i] = previous != null && audio.isStreamMute(streams[i]) == previous.optBoolean("appliedMuted")
                        && audio.getStreamVolume(streams[i]) == previous.optInt("appliedVolume")
                        && (streams[i] == AudioManager.STREAM_MUSIC || ringerUnchanged);
                restoreRinger |= unchanged[i] && streams[i] != AudioManager.STREAM_MUSIC;
            }
            for (int i = 0; i < streams.length; i++) {
                JSONObject previous = saved.optJSONObject(String.valueOf(streams[i]));
                if (unchanged[i] && !previous.optBoolean("muted")) audio.adjustStreamVolume(streams[i], AudioManager.ADJUST_UNMUTE, 0);
                saved.remove(String.valueOf(streams[i]));
            }
            if (restoreRinger && ringerUnchanged && snapshot.optBoolean("ringerOwned")) {
                audio.setRingerModeInternal(snapshot.optInt("ringerMode", AudioManager.RINGER_MODE_NORMAL));
            }
        } else {
            int previousRinger = audio.getRingerModeInternal();
            for (int stream : streams) {
                JSONObject previous = new JSONObject();
                EdgeGoConfig.put(previous, "muted", audio.isStreamMute(stream));
                EdgeGoConfig.put(previous, "volume", audio.getStreamVolume(stream));
                EdgeGoConfig.put(saved, String.valueOf(stream), previous);
            }
            for (int stream : streams) if (!saved.optJSONObject(String.valueOf(stream)).optBoolean("muted")) {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0);
            }
            for (int stream : streams) {
                JSONObject previous = saved.optJSONObject(String.valueOf(stream));
                EdgeGoConfig.put(previous, "appliedMuted", audio.isStreamMute(stream));
                EdgeGoConfig.put(previous, "appliedVolume", audio.getStreamVolume(stream));
            }
            if (previousRinger != audio.getRingerModeInternal()) {
                EdgeGoConfig.put(snapshot, "ringerMode", previousRinger);
                EdgeGoConfig.put(snapshot, "appliedRingerMode", audio.getRingerModeInternal());
                EdgeGoConfig.put(snapshot, "ringerOwned", true);
            }
        }
        EdgeGoConfig.put(snapshot, "streams", saved);
        if (saved.length() == 0) state.remove("muteSnapshot");
        else EdgeGoConfig.put(state, "muteSnapshot", snapshot);
        EdgeGoSchedules.save(context, host.userId(), state);
    }

    float brightness() {
        BrightnessInfo info = display.getDisplay(Display.DEFAULT_DISPLAY).getBrightnessInfo();
        float min = info == null ? 0 : info.brightnessMinimum, max = info == null ? 1 : info.brightnessMaximum;
        float current = display.getBrightness(Display.DEFAULT_DISPLAY);
        if (!Float.isFinite(current) || current < 0) return .5f;
        if (!Float.isFinite(min) || !Float.isFinite(max) || max <= min) return 0;
        return BrightnessUtils.convertLinearToGammaFloat(current, min, max) / (float) BrightnessUtils.GAMMA_SPACE_MAX;
    }

    void setBrightness(float level, boolean temporary) {
        if (!temporary && Settings.System.getIntForUser(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, host.userId()) != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
            Settings.System.putIntForUser(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, host.userId());
        }
        BrightnessInfo info = display.getDisplay(Display.DEFAULT_DISPLAY).getBrightnessInfo();
        float min = info == null ? 0 : info.brightnessMinimum, max = info == null ? 1 : info.brightnessMaximum;
        float value = BrightnessUtils.convertGammaToLinearFloat(Math.round(Math.max(0, Math.min(1, level))
                * BrightnessUtils.GAMMA_SPACE_MAX), min, max);
        if (temporary) display.setTemporaryBrightness(Display.DEFAULT_DISPLAY, value);
        else display.setBrightness(Display.DEFAULT_DISPLAY, value);
    }

    void clearTemporaryBrightness() { display.setTemporaryBrightness(Display.DEFAULT_DISPLAY, Float.NaN); }

    private void cycleScreenTimeout() {
        int[] values = {30000, 60000, 120000, 300000, 600000};
        int current = Settings.System.getIntForUser(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 30000, host.userId());
        int next = values[0];
        for (int value : values) if (value > current) { next = value; break; }
        Settings.System.putIntForUser(context.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, next, host.userId());
    }

    boolean beginContinuous(String action, EdgeGoGestureController.Zone zone,
            float startX, float startY, float x, float y) {
        if (!host.canRun()) return false;
        String base = action.split(":", 2)[0];
        if (host.locked() && !LOCK_ACTIONS.contains(base)) return false;
        endContinuous(true);
        if (base.equals("show_quick_access") || base.equals("remote_cursor")) {
            continuous = base;
            host.showPanel(action, zone, x, y, true);
            return true;
        }
        try {
            continuousIncremental = EdgeGoConfig.incremental(base);
            continuous = continuousIncremental ? base.startsWith("brightness_")
                    ? "brightness_slider" : "volume_slider" : base;
            continuousLabel = label(continuousIncremental ? base : continuous);
            continuousZone = zone;
            originX = controlX = x;
            originY = controlY = y;
            lastControlFrame = SystemClock.uptimeMillis();
            float distance = (float) Math.hypot(x - startX, y - startY);
            controlDirectionX = distance > 0 ? (x - startX) / distance : 0;
            controlDirectionY = distance > 0 ? (y - startY) / distance : -1;
            controlSign = base.endsWith("_down") ? -1 : 1;
            continuousStream = base.startsWith("ring_volume_") ? AudioManager.STREAM_RING
                    : base.startsWith("notification_volume_") ? AudioManager.STREAM_NOTIFICATION
                    : continuousIncremental ? AudioManager.STREAM_MUSIC : volumeStream();
            boolean remote = continuousStream == AudioManager.STREAM_MUSIC && remoteVolume();
            continuousPlaybackType = remote ? MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
                    : MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL;
            currentLevel = continuous.equals("brightness_slider") ? brightness() : volume(continuousStream);
            if (continuous.equals("volume_slider")) {
                if (restricted(UserManager.DISALLOW_ADJUST_VOLUME)) { endContinuous(true); return false; }
                MediaController.PlaybackInfo info = remote ? media.getPlaybackInfo() : null;
                int range = remote ? info.getMaxVolume()
                        : audio.getStreamMaxVolume(continuousStream) - audio.getStreamMinVolume(continuousStream);
                if (range <= 0 || !remote && audio.isVolumeFixed()
                        || remote && info.getVolumeControl() == android.media.VolumeProvider.VOLUME_CONTROL_FIXED) {
                    endContinuous(true);
                    unavailable();
                    return false;
                }
                controlStep = 1f / range;
                continuousVolumeRange = range;
                lastAppliedVolume = -1;
                continuousRelativeVolume = remote && info.getVolumeControl() == android.media.VolumeProvider.VOLUME_CONTROL_RELATIVE;
                currentRemoteVolume = remote ? info.getCurrentVolume() : 0;
            } else controlStep = host.config().integer("brightness_step") / 100f;
            if (continuous.equals("media_scrub")) {
                PlaybackState state = media == null ? null : media.getPlaybackState();
                MediaMetadata metadata = media == null ? null : media.getMetadata();
                if (state == null || metadata == null || (state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0
                        || (seekDuration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)) <= 0) {
                    endContinuous(true);
                    unavailable();
                    return false;
                }
                initialPosition = state.getPosition();
                seekMediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
                seekTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (state.getState() == PlaybackState.STATE_PLAYING) initialPosition += Math.round(
                        (SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
                currentLevel = Math.max(0, Math.min(1, initialPosition / (float) seekDuration));
                lastSeek = 0;
            } else if (continuous.equals("page_scroll")) {
                if (host.config().string("page_scroll_variant").equals("auto_scroll")) startScrolling(action.endsWith(":up") ? -1 : 1);
                host.showPanel("page_scroll", zone, x, y, false);
            }
            if (continuousIncremental) currentLevel = Math.max(0, Math.min(1, currentLevel + controlSign * controlStep));
            applyContinuous(SystemClock.uptimeMillis());
            if (continuousIncremental) scheduleContinuousFrame();
            return !continuous.isEmpty();
        } catch (RuntimeException e) {
            endContinuous(true);
            Log.w(TAG, "Cannot start the gesture control", e);
            unavailable();
            return false;
        }
    }

    void moveContinuous(float x, float y) {
        if (continuous.isEmpty() || !Float.isFinite(x) || !Float.isFinite(y)) return;
        if (!host.canRun()) { endContinuous(true); return; }
        if (continuous.equals("show_quick_access") || continuous.equals("remote_cursor")) {
            host.trackPanel(x, y);
            return;
        }
        controlX = x;
        controlY = y;
        scheduleContinuousFrame();
    }

    private void scheduleContinuousFrame() {
        if (continuous.isEmpty() || continuousFrameScheduled) return;
        continuousFrameScheduled = true;
        Choreographer.getInstance().postFrameCallback(continuousFrame);
    }

    private void advanceContinuous(long frameTimeNanos) {
        continuousFrameScheduled = false;
        if (continuous.isEmpty()) return;
        if (!host.canRun()) { endContinuous(true); return; }
        applyContinuous(SystemClock.uptimeMillis());
        boolean adjusting = continuousIncremental && (controlSign > 0 ? currentLevel < 1 : currentLevel > 0);
        boolean settling = continuousRelativeVolume && Math.round(currentLevel * continuousVolumeRange) != currentRemoteVolume;
        if (!continuous.isEmpty() && (adjusting || settling)) scheduleContinuousFrame();
    }

    private void applyContinuous(long now) {
        if (continuous.isEmpty()) return;
        if (continuous.equals("volume_slider") && (users.hasUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME,
                UserHandle.of(host.userId())) || continuousPlaybackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL && audio.isVolumeFixed())) {
            endContinuous(true);
            unavailable();
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        String orientation = host.config().string("slider_orientation");
        boolean horizontal = orientation.equals("horizontal") || orientation.equals("edge")
                && continuousZone != null && !continuousZone.side();
        float deltaX = controlX - originX, deltaY = controlY - originY;
        float delta = horizontal ? deltaX : -deltaY;
        float sensitivity = host.config().integer("slider_sensitivity") / 100f;
        if (continuousIncremental) {
            float elapsed = Math.max(0, Math.min(250, now - lastControlFrame));
            delta = (deltaX * controlDirectionX + deltaY * controlDirectionY) / (220 * density) * sensitivity
                    + elapsed / host.config().integer("continuous_adjustment_interval_ms") * controlStep;
            currentLevel = Math.max(0, Math.min(1, currentLevel + controlSign * delta));
        } else if (continuous.equals("volume_slider") || continuous.equals("brightness_slider")) {
            currentLevel = Math.max(0, Math.min(1, currentLevel + delta / (220 * density) * sensitivity));
        }
        lastControlFrame = now;
        try {
            switch (continuous) {
                case "volume_slider":
                    int desired = Math.round(currentLevel * continuousVolumeRange);
                    if (desired != lastAppliedVolume || continuousRelativeVolume
                            && now - lastRemoteVolumeTime >= host.config().integer("continuous_adjustment_interval_ms")) {
                        setVolume(continuousStream, currentLevel);
                        lastAppliedVolume = desired;
                    }
                    break;
                case "brightness_slider": setBrightness(currentLevel, true); break;
                case "media_scrub":
                    currentLevel = Math.max(0, Math.min(1, currentLevel
                            + deltaX / (400 * density) * host.config().integer("media_scrub_sensitivity") / 100f));
                    if (media != null && now - lastSeek >= 50) {
                        lastSeek = now;
                        media.getTransportControls().seekTo((long) (currentLevel * seekDuration));
                    }
                    break;
                case "page_scroll":
                    currentLevel = Math.max(0, Math.min(1, Math.abs(deltaY) / (220 * density)));
                    if (host.config().string("page_scroll_variant").equals("hold_drag")
                            && Math.abs(deltaY) > 48 * density) {
                        scrollDirection = deltaY > 0 ? 1 : -1;
                        scrollTarget = host.foreground();
                        scrollStep();
                        originY = controlY;
                    }
                    break;
            }
            if (!continuous.equals("page_scroll")) { originX = controlX; originY = controlY; }
        } catch (RuntimeException e) {
            endContinuous(true);
            Log.w(TAG, "Cannot adjust the gesture control", e);
            unavailable();
        }
        if (!continuous.isEmpty()) host.showControl(continuous, continuousLabel, currentLevel, continuousZone, controlX, controlY);
    }

    void endContinuous(boolean canceled) {
        if (!canceled && host != null && host.canRun() && (continuousFrameScheduled || continuousIncremental)) {
            applyContinuous(SystemClock.uptimeMillis());
        }
        if (continuousFrameScheduled) Choreographer.getInstance().removeFrameCallback(continuousFrame);
        continuousFrameScheduled = continuousIncremental = continuousRelativeVolume = false;
        String action = continuous;
        continuous = "";
        continuousZone = null;
        try {
            if (action.equals("brightness_slider")) {
                try {
                    if (!canceled && host.canRun()) setBrightness(currentLevel, false);
                } finally {
                    clearTemporaryBrightness();
                }
            } else if (action.equals("media_scrub") && !canceled && media != null && host.canRun()) {
                media.getTransportControls().seekTo((long) (currentLevel * seekDuration));
            } else if (action.equals("page_scroll") && canceled) stopScrolling();
        } catch (RuntimeException e) { Log.w(TAG, "Cannot finish the gesture control", e); }
    }

    private void startScrolling(int direction) {
        stopScrolling();
        if (host.locked() || !host.canRun()) return;
        scrolling = true;
        scrollTarget = host.foreground();
        scrollSpeed = host.config().integer("page_scroll_auto_speed_level");
        scrollDirection = direction;
        handler.post(scroll);
    }

    private void scrollStep() {
        if (requestInFlight || scrollPaused || host.locked() || !host.canRun()
                || scrollTarget == null || !scrollTarget.equals(host.foreground())) return;
        requestInFlight = true;
        int token = generation;
        windowAction(scrollDirection > 0 ? EdgeGoWindowActions.SCROLL_DOWN : EdgeGoWindowActions.SCROLL_UP,
                result -> {
                    if (token != generation) return;
                    requestInFlight = false;
                    if (!result.getBoolean("performed")) { stopScrolling(); return; }
                    if (scrolling) handler.postDelayed(scroll, 1800 - scrollSpeed * 180L);
                }, false);
    }

    void changeScroll(int delta) { scrollSpeed = EdgeGoConfig.clamp(scrollSpeed + delta, 1, 7); }
    int scrollSpeed() { return scrollSpeed; }
    void reverseScroll() { scrollDirection *= -1; }
    void scrollOnce(boolean up) {
        scrollTarget = host.foreground(); scrollDirection = up ? -1 : 1; scrollStep();
    }
    boolean scrollPaused() { return scrollPaused; }
    void toggleScroll() { scrollPaused = !scrollPaused; if (!scrollPaused) handler.post(scroll); }
    void stopScrolling() { scrolling = false; scrollPaused = false; requestInFlight = false; handler.removeCallbacks(scroll); }
    void onOutsideTouch() { if (scrolling) { scrollPaused = true; handler.removeCallbacks(scroll); } }

    private void windowAction(int action, java.util.function.Consumer<Bundle> callback) {
        host.prepareAppAction();
        windowAction(action, callback, true);
    }

    private void windowAction(int action, java.util.function.Consumer<Bundle> callback, boolean delayed) {
        int token = generation, user = host.userId();
        String target = host.foreground();
        AtomicBoolean complete = new AtomicBoolean();
        Runnable failure = () -> {
            if (!complete.compareAndSet(false, true) || token != generation) return;
            requestInFlight = false;
            if (user != host.userId() || !host.canRun() || host.locked() || !target.equals(host.foreground())) {
                stopScrolling();
                return;
            }
            if (callback != null) callback.accept(new Bundle());
            else unavailable();
        };
        Runnable request = () -> {
            if (!host.canRun() || host.locked() || token != generation || user != host.userId()
                    || !target.equals(host.foreground())) { failure.run(); return; }
            handler.postDelayed(failure, 1500);
            try {
                windows.requestEdgeGoAction(action, target, user, new RemoteCallback(result -> {
                    if (!complete.compareAndSet(false, true)) return;
                    handler.removeCallbacks(failure);
                    if (token != generation || user != host.userId() || !host.canRun() || host.locked()
                            || !target.equals(host.foreground())) {
                        if (token == generation) stopScrolling();
                        return;
                    }
                    try {
                        if (callback != null) callback.accept(result);
                        else if (!result.getBoolean("performed")) unavailable();
                    } catch (RuntimeException e) {
                        requestInFlight = false;
                        Log.w(TAG, "Invalid focused-window response", e); unavailable();
                    }
                }, handler));
            } catch (RemoteException | RuntimeException e) { failure.run(); }
        };
        if (delayed) handler.postDelayed(request, 64); else request.run();
    }

    void applyPreset(String id) {
        JSONObject state = EdgeGoConfig.presetStore(context.getContentResolver(), host.userId());
        JSONArray presets = state.optJSONArray("presets");
        if (presets == null) return;
        for (int i = 0; i < Math.min(presets.length(), 24); i++) {
            JSONObject preset = presets.optJSONObject(i);
            if (preset != null && id.equals(preset.optString("id")) && preset.optJSONObject("values") != null) {
                host.closePanels();
                Settings.Secure.putStringForUser(context.getContentResolver(), EdgeGoConfig.CONFIG,
                        EdgeGoConfig.encode(preset.optJSONObject("values")), host.userId());
                return;
            }
        }
    }

    void copy(String text) {
        if (host.locked() || text == null || text.length() > 16384) return;
        host.userContext().getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("EdgeGo", text));
    }

    MediaController media() { return media; }
    boolean canShowMediaMetadata() {
        return media != null && notifications.get().canShowMedia(media.getPackageName(), host.userId());
    }
    boolean active(String action) {
        switch (action) {
            case "flashlight": return flashlight.isEnabled();
            case "wifi": return context.getSystemService(WifiManager.class).isWifiEnabled();
            case "mobile_data": return network.getMobileDataController().isMobileDataEnabled();
            case "auto_brightness": return Settings.System.getIntForUser(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0, host.userId()) == 1;
            case "bluetooth": return bluetooth.isBluetoothEnabled();
            case "location": return location.isLocationEnabled();
            case "auto_rotate": return !rotation.isRotationLocked();
            case "do_not_disturb": return zen.getZen() != 0;
            case "battery_saver": return battery.isPowerSave();
            case "extra_dim": return dim.isReduceBrightColorsActivated();
            default: return false;
        }
    }
    EdgeGoSchedules schedules() { return schedules; }
    Executor background() { return background; }
    EdgeGoPrivateStore privateStore() { return privateStore; }
    EdgeGoNotifications notifications() { return notifications.get(); }
    String label(String action) {
        String base = action.split(":", 2)[0];
        int index = Arrays.asList(EdgeGoConfig.ACTIONS).indexOf(base);
        if (index >= 0) return context.getResources().getStringArray(R.array.edgego_action_labels)[index];
        if (base.startsWith("launch_app")) return context.getString(R.string.edgego_launch_app);
        if (base.equals("run_macro")) return context.getString(R.string.edgego_macro);
        return context.getString(R.string.edgego_shortcut);
    }

    void unavailable() { Toast.makeText(context, R.string.edgego_action_unavailable, Toast.LENGTH_SHORT).show(); }
    void cancelInteraction() {
        generation++;
        endContinuous(true);
        stopScrolling();
    }
}
