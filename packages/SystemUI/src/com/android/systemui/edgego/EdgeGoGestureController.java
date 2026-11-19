/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.edgego;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_BOUNCER_SHOWING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_DEVICE_DOZING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_DEVICE_DREAMING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_IME_VISIBLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_QUICK_SETTINGS_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_DIALOG_SHOWING;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.ISystemGestureExclusionListener;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityManager;
import android.window.BackEvent;

import com.android.internal.util.lunaris.EdgeGoConfig;
import com.android.internal.util.lunaris.EdgeGoDrawings;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.shared.system.InputMonitorCompat;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.window.data.repository.StatusBarWindowStateRepositoryStore;
import com.android.systemui.statusbar.window.shared.model.StatusBarWindowState;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.onehanded.OneHanded;
import com.android.wm.shell.onehanded.OneHandedTransitionCallback;
import com.axion.applocker.AxAppLockerHelper;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Provider;

@SysUISingleton
public final class EdgeGoGestureController implements CoreStartable, EdgeGoGestureRecognizer.Listener,
        EdgeGoActions.Host, EdgeGoInputSession.Host {
    public static final String GAME_ACTIVE = "ax_gaming_mode_active";
    public static final String GAME_PACKAGE = "io.chaldeaprjkt.gamespace";
    private static final String TAG = "EdgeGoGesture";
    private static final long BLOCKING_FLAGS = SYSUI_STATE_BOUNCER_SHOWING | SYSUI_STATE_DEVICE_DOZING
            | SYSUI_STATE_DEVICE_DREAMING | SYSUI_STATE_SCREEN_PINNING;

    static final class Zone {
        final String id, edge;
        final Rect bounds;
        final int color;
        final boolean captureTaps, indicator, vibrate;
        final EdgeGoDrawings drawings;
        Zone(String id, String edge, Rect bounds, EdgeGoConfig config, EdgeGoDrawings drawings) {
            this.id = id;
            this.edge = edge;
            this.bounds = bounds;
            color = config.integer("overlay_color_" + edge + "_rgb");
            captureTaps = config.capturesTaps(id);
            indicator = config.zoneBoolean(id, "indicator", true);
            vibrate = config.zoneBoolean(id, "vibrate", true);
            this.drawings = drawings;
        }
        boolean side() { return edge.equals("left") || edge.equals("right"); }
    }

    private static final class RuntimeState {
        final List<Zone> zones;
        final Region exclusions;
        final boolean active;
        RuntimeState(List<Zone> zones, Region exclusions, boolean active) {
            this.zones = Collections.unmodifiableList(zones);
            this.exclusions = new Region(exclusions);
            this.active = active;
        }
    }

    private final Context context;
    private final Handler handler;
    private final Executor background;
    private final UserTracker users;
    private final NavigationModeController navigation;
    private final SysUiState sysUiState;
    private final ConfigurationController configuration;
    private final KeyguardStateController keyguard;
    private final DeviceProvisionedController provisioned;
    private final WakefulnessLifecycle wakefulness;
    private final IWindowManager windowService;
    private final InputManager input;
    private final AccessibilityManager accessibility;
    private final Optional<BackAnimation> backAnimation;
    private final Optional<OneHanded> oneHanded;
    private final Provider<EdgeGoActions> actionsProvider;
    private final AxAppLockerHelper appLocker;
    private final StatusBarWindowStateRepositoryStore statusBarWindows;
    private final JavaAdapter javaAdapter;
    private final AtomicInteger foregroundGeneration = new AtomicInteger();
    private final Map<String, Long> pausedZones = new HashMap<>();
    private final Map<String, EdgeGoDrawings> drawingModels = new HashMap<>();
    private final Runnable refresh = this::updateRuntime;
    private final Runnable pauseExpiry = this::scheduleRefresh;
    private volatile RuntimeState runtime = new RuntimeState(new ArrayList<>(), new Region(), false);
    private volatile EdgeGoConfig config = new EdgeGoConfig(false, null);
    private volatile InputMonitorCompat monitor;
    private volatile String foreground = "";
    private volatile int foregroundUser = -1, foregroundTask = -1;
    private volatile long flags;
    private volatile boolean switchingUser, injecting, gestureOwned, navigationBlocked;
    private volatile int user;
    private InputChannelCompat.InputEventReceiver receiver;
    private WindowManager windows;
    private EdgeGoFeedbackWindow feedback;
    private EdgeGoPanelWindow panels;
    private EdgeGoGestureRecognizer recognizer;
    private EdgeGoInputSession inputSession;
    private EdgeGoActions actions;
    private EdgeGoMacroRunner macros;
    private Region exclusions = new Region();
    private WindowInsets latestInsets;
    private WindowInsets panelInsets;
    private volatile boolean imeBoundsKnown = true;
    private volatile boolean started, gestural;
    private volatile boolean statusBarHidden;
    private boolean listening, home, gaming, nativeBack, nativeBackThreshold;
    private int nativeBackEdge;
    private boolean gestureDidExecute;
    private boolean oneHandedTransition;
    private Rect oneHandedBounds;
    private long pausedUntil, lastActionTime;
    private long decisionTime = Long.MIN_VALUE;
    private int decisionDevice;
    private Zone decision;
    private boolean decisionConsumesOutside;
    private volatile Rect appTouchBounds = new Rect();
    private volatile Rect displayBounds = new Rect();
    private volatile Rect replayBounds = new Rect();
    private volatile long[] injectedDownTimes = new long[0];
    private float density = 1;

    private final ISystemGestureExclusionListener exclusionListener =
            new ISystemGestureExclusionListener.Stub() {
                @Override
                public void onSystemGestureExclusionChanged(int displayId, Region exclusion,
                        Region unrestricted) {
                    if (displayId == Display.DEFAULT_DISPLAY) handler.post(() -> {
                        exclusions = new Region(exclusion);
                        scheduleRefresh();
                    });
                }
            };

    private final TaskStackChangeListener taskListener = new TaskStackChangeListener() {
        @Override public void onTaskStackChanged() { refreshForeground(); }
        @Override public void onTaskMovedToFront(ActivityManager.RunningTaskInfo task) { refreshForeground(); }
    };

    @Inject
    public EdgeGoGestureController(Context context, @Main Handler handler, @Background Executor background,
            UserTracker users, NavigationModeController navigation, SysUiState sysUiState,
            ConfigurationController configuration, KeyguardStateController keyguard,
            DeviceProvisionedController provisioned, WakefulnessLifecycle wakefulness,
            IWindowManager windowService, InputManager input, Optional<BackAnimation> backAnimation,
            Provider<EdgeGoActions> actionsProvider, AxAppLockerHelper appLocker, Optional<OneHanded> oneHanded,
            StatusBarWindowStateRepositoryStore statusBarWindows, JavaAdapter javaAdapter) {
        this.context = context;
        this.handler = handler;
        this.background = background;
        this.users = users;
        this.navigation = navigation;
        this.sysUiState = sysUiState;
        this.configuration = configuration;
        this.keyguard = keyguard;
        this.provisioned = provisioned;
        this.wakefulness = wakefulness;
        this.windowService = windowService;
        this.input = input;
        this.backAnimation = backAnimation;
        this.oneHanded = oneHanded;
        this.actionsProvider = actionsProvider;
        this.appLocker = appLocker;
        this.statusBarWindows = statusBarWindows;
        this.javaAdapter = javaAdapter;
        accessibility = context.getSystemService(AccessibilityManager.class);
    }

    @Override
    public void start() {
        if (started) return;
        started = true;
        user = users.getUserId();
        Context windowContext = context.createWindowContext(context.getSystemService(DisplayManager.class)
                        .getDisplay(Display.DEFAULT_DISPLAY),
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL, null);
        windows = windowContext.getSystemService(WindowManager.class);
        feedback = new EdgeGoFeedbackWindow(windowContext, windows, handler, background, this::insetsChanged);
        actions = actionsProvider.get();
        actions.bind(this);
        panels = new EdgeGoPanelWindow(windowContext, windows, handler, actions, this);
        recognizer = new EdgeGoGestureRecognizer(handler, this);
        inputSession = new EdgeGoInputSession(input, Choreographer.getInstance(), this);
        macros = new EdgeGoMacroRunner(context, handler, this, actions, inputSession);
        javaAdapter.alwaysCollectFlow(statusBarWindows.getDefaultDisplay().getWindowState(), state -> {
            boolean hidden = state != StatusBarWindowState.Showing;
            if (statusBarHidden != hidden) { statusBarHidden = hidden; systemBarsChanged(); }
        });
        oneHanded.ifPresent(shell -> shell.registerTransitionCallback(new OneHandedTransitionCallback() {
            @Override public void onStartTransition(boolean entering) {
                handler.post(() -> { oneHandedTransition = true; stateChanged(); });
            }
            @Override public void onStartFinished(Rect bounds) {
                Rect target = new Rect(bounds);
                handler.post(() -> { oneHandedBounds = target; oneHandedTransition = false; stateChanged(); });
            }
            @Override public void onStopFinished(Rect bounds) {
                handler.post(() -> { oneHandedBounds = null; oneHandedTransition = false; stateChanged(); });
            }
        }));
        ContentObserver observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                if (Settings.Secure.getUriFor(EdgeGoConfig.COMMAND).equals(uri)) readCommand();
                else reload();
            }
        };
        for (String key : new String[]{EdgeGoConfig.ENABLED, EdgeGoConfig.CONFIG,
                EdgeGoConfig.COMMAND, GAME_ACTIVE}) {
            context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key),
                    false, observer, UserHandle.USER_ALL);
        }
        users.addCallback(new UserTracker.Callback() {
            @Override public void onUserChanging(int newUser, Context userContext) {
                switchingUser = true;
                stopInteraction();
                disposeMonitor();
                pausedZones.clear();
                actions.onUserChanged();
            }
            @Override public void onUserChanged(int newUser, Context userContext) {
                user = newUser;
                foreground = "";
                foregroundUser = foregroundTask = -1;
                home = false;
                pausedUntil = 0;
                switchingUser = false;
                reload();
                refreshForeground();
            }
        }, handler::post);
        int mode = navigation.addListener(modeValue -> {
            gestural = QuickStepContract.isGesturalMode(modeValue);
            stopInteraction();
            scheduleRefresh();
        });
        gestural = QuickStepContract.isGesturalMode(mode);
        sysUiState.addCallback((newFlags, displayId) -> {
            if (displayId != Display.DEFAULT_DISPLAY) return;
            handler.post(() -> {
                long changed = flags ^ newFlags;
                flags = newFlags;
                if ((changed & BLOCKING_FLAGS) != 0) {
                    stopInteraction();
                    scheduleRefresh();
                } else if ((changed & (SYSUI_STATE_NAV_BAR_HIDDEN | SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED | SYSUI_STATE_QUICK_SETTINGS_EXPANDED
                        | SYSUI_STATE_DIALOG_SHOWING)) != 0) {
                    systemBarsChanged();
                } else if ((changed & SYSUI_STATE_IME_VISIBLE) != 0) {
                    imeBoundsKnown = (newFlags & SYSUI_STATE_IME_VISIBLE) == 0;
                    recognizer.cancel(true);
                    cancelNativeBack();
                    scheduleRefresh();
                }
            });
        });
        keyguard.addCallback(new KeyguardStateController.Callback() {
            @Override public void onKeyguardShowingChanged() { stateChanged(); }
            @Override public void onPrimaryBouncerShowingChanged() { stateChanged(); }
        });
        wakefulness.addObserver(new WakefulnessLifecycle.Observer() {
            @Override public void onStartedGoingToSleep() { stateChanged(); }
            @Override public void onFinishedWakingUp() { stateChanged(); }
        });
        provisioned.addCallback(new DeviceProvisionedController.DeviceProvisionedListener() {
            @Override public void onUserSetupChanged() { stateChanged(); }
            @Override public void onDeviceProvisionedChanged() { stateChanged(); }
        });
        configuration.addCallback(new ConfigurationController.ConfigurationListener() {
            @Override public void onConfigChanged(Configuration configuration) {
                latestInsets = null;
                stateChanged();
            }
        });
        accessibility.addTouchExplorationStateChangeListener(enabled -> handler.post(this::stateChanged));
        reload();
    }

    private void stateChanged() {
        stopInteraction();
        scheduleRefresh();
    }

    private void systemBarsChanged() {
        if (inputSession.isStatusBarGesture() && canContinueStatusBarGesture(foreground, user)) {
            // Opening the shade or revealing the bars must not cancel its drag before ACTION_UP.
            recognizer.cancel(true);
            cancelNativeBack();
            panels.close();
            actions.cancelInteraction();
        } else stopInteraction();
        scheduleRefresh();
    }

    private void reload() {
        if (!started) return;
        stopInteraction();
        EdgeGoConfig next = new EdgeGoConfig(context.getContentResolver(), user);
        if (!config.enabled && next.enabled) { pausedUntil = 0; pausedZones.clear(); }
        config = next;
        drawingModels.clear();
        feedback.setUserContext(users.getUserContext());
        gaming = Settings.Secure.getIntForUser(context.getContentResolver(), GAME_ACTIVE, 0, user) == 1;
        updateListeners();
        actions.updateSettings();
        updateRuntime();
    }

    private void updateListeners() {
        if (config.enabled == listening) return;
        listening = config.enabled;
        if (listening) {
            TaskStackChangeListeners.getInstance().registerTaskStackListener(taskListener);
            try { windowService.registerSystemGestureExclusionListener(exclusionListener, Display.DEFAULT_DISPLAY); }
            catch (RemoteException e) { Log.w(TAG, "Cannot observe app exclusions", e); }
            refreshForeground();
        } else {
            TaskStackChangeListeners.getInstance().unregisterTaskStackListener(taskListener);
            try { windowService.unregisterSystemGestureExclusionListener(exclusionListener, Display.DEFAULT_DISPLAY); }
            catch (RemoteException | IllegalArgumentException e) { Log.w(TAG, "Cannot remove exclusion observer", e); }
            exclusions = new Region();
            foregroundGeneration.incrementAndGet();
        }
    }

    private void refreshForeground() {
        int generation = foregroundGeneration.incrementAndGet();
        int expectedUser = user;
        background.execute(() -> {
            ActivityManager.RunningTaskInfo[] tasks;
            try {
                tasks = ActivityManagerWrapper.getInstance().getRunningTasks(false, Display.DEFAULT_DISPLAY);
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot read the foreground task", e);
                tasks = new ActivityManager.RunningTaskInfo[0];
            }
            ActivityManager.RunningTaskInfo task = tasks.length == 0 ? null : tasks[0];
            ComponentName top = task == null ? null : task.topActivity;
            String pkg = top == null ? "" : top.getPackageName();
            boolean isHome = task != null && task.getActivityType()
                    == android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
            int taskUser = task == null ? -1 : task.userId, taskId = task == null ? -1 : task.taskId;
            handler.post(() -> {
                if (generation != foregroundGeneration.get() || expectedUser != user || switchingUser) return;
                boolean changed = !foreground.equals(pkg) || foregroundUser != taskUser || foregroundTask != taskId;
                foreground = pkg;
                foregroundUser = taskUser; foregroundTask = taskId;
                home = isHome;
                if (changed) {
                    recognizer.cancel(true);
                    inputSession.cancel();
                    panels.onForegroundChanged();
                    actions.cancelInteraction();
                    macros.onForegroundChanged(pkg);
                }
                scheduleRefresh();
            });
        });
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    private void insetsChanged(WindowInsets insets) {
        if (latestInsets == null || !latestInsets.equals(insets)) {
            latestInsets = insets;
            scheduleRefresh();
        }
    }

    private void updateRuntime() {
        if (!started) return;
        density = context.getResources().getDisplayMetrics().density;
        long now = SystemClock.elapsedRealtime();
        pausedZones.values().removeIf(until -> until <= now);
        if (pausedUntil <= now) pausedUntil = 0;
        long nextExpiry = pausedUntil > now ? pausedUntil : Long.MAX_VALUE;
        for (long until : pausedZones.values()) nextExpiry = Math.min(nextExpiry, until);
        handler.removeCallbacks(pauseExpiry);
        if (nextExpiry != Long.MAX_VALUE) handler.postDelayed(pauseExpiry, Math.max(1, nextExpiry - now));
        boolean active = canRun() && now >= pausedUntil;
        List<Zone> zones = active ? makeZones() : new ArrayList<>();
        runtime = new RuntimeState(zones, exclusions, active && (!zones.isEmpty() || !panels.isClosed() || macros.recording() || macros.running()));
        panels.onRuntimeChanged();
        if (!runtime.active) {
            if (!active) stopInteraction();
            else recognizer.cancel(true);
            disposeMonitor();
            feedback.hide();
            actions.setActive(active && !panels.isClosed());
            return;
        }
        if (monitor == null) {
            try {
                monitor = new InputMonitorCompat("edgego-gesture", Display.DEFAULT_DISPLAY);
                receiver = monitor.getInputReceiver(Looper.getMainLooper(), Choreographer.getInstance(), this::onInput);
                receiver.setBatchingEnabled(true);
            } catch (RuntimeException e) {
                disposeMonitor();
                runtime = new RuntimeState(new ArrayList<>(), exclusions, false);
                feedback.hide();
                actions.setActive(false);
                Log.e(TAG, "Cannot monitor edge gestures", e);
                return;
            }
        }
        actions.setActive(true);
        feedback.setExclusions(exclusions);
        feedback.setFullscreen(fullscreen());
        feedback.show(config, macros.recording() ? Collections.emptyList() : zones, injecting);
    }

    private List<Zone> makeZones() {
        List<Zone> result = new ArrayList<>();
        WindowMetrics metrics = windows.getMaximumWindowMetrics();
        Rect screen = metrics.getBounds();
        displayBounds = new Rect(0, 0, screen.width(), screen.height());
        WindowInsets insets = metrics.getWindowInsets();
        Insets bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        Insets mandatory = insets.getInsetsIgnoringVisibility(WindowInsets.Type.mandatorySystemGestures());
        int navigationInset = Math.max(bars.bottom, mandatory.bottom);
        if (gestural) navigationInset = Math.max(navigationInset, context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_gesture_height));
        boolean keyboard = (flags & SYSUI_STATE_IME_VISIBLE) != 0;
        Insets visible = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
        if (latestInsets != null) ime = Math.max(ime, latestInsets.getInsets(WindowInsets.Type.ime()).bottom);
        if (panelInsets != null) ime = Math.max(ime, panelInsets.getInsets(WindowInsets.Type.ime()).bottom);
        imeBoundsKnown = !keyboard || ime > navigationInset;
        int appTop = Math.max(visible.top, (int) (24 * density));
        if (oneHandedBounds != null) appTop = Math.max(appTop, oneHandedBounds.top);
        appTouchBounds = new Rect(visible.left, appTop, screen.width() - visible.right,
                screen.height() - Math.max(Math.max(navigationInset, (int) (24 * density)), keyboard ? ime : 0));
        replayBounds = new Rect(appTouchBounds);
        replayBounds.bottom = screen.height() - Math.max(navigationInset, (int) (24 * density));
        if (keyboard && !config.bool("keep_edges_over_keyboard")) return result;
        int bottomInset = navigationInset;
        if (keyboard) {
            if (ime <= bottomInset) return result;
            bottomInset = Math.max(bottomInset, ime);
        }
        int width = screen.width(), height = screen.height();
        int top = Math.max(bars.top, (int) (24 * density));
        if (oneHandedBounds != null) top = Math.max(top, oneHandedBounds.top);
        int bottom = height - Math.max(bottomInset, (int) (24 * density));
        int left = bars.left, right = width - bars.right;
        boolean landscape = width > height;
        for (String id : EdgeGoConfig.ZONES) {
            if (!config.zoneEnabled(id) || !config.hasActions(id)) continue;
            String edge = id.substring(0, id.indexOf('_'));
            if (config.paused(foreground, id, home, keyguard.isShowing())) continue;
            if (pausedZones.getOrDefault(id, 0L) > SystemClock.elapsedRealtime()) continue;
            String orientation = config.zoneString(id, "orientation_mode", "both");
            if (landscape && orientation.equals("portrait") || !landscape && orientation.equals("landscape")
                    ) continue;
            boolean side = edge.equals("left") || edge.equals("right");
            if (side && gestural && !config.bool("touch_priority_enabled")) continue;
            float thickness = config.zoneInt(id, "thickness", 16, 4, 40) * density;
            int length = config.zoneInt(id, landscape ? "landscape_length" : "length",
                    side ? 25 : id.startsWith("bottom_navigation") ? 100 : 65, 5, 100);
            int position = config.zoneInt(id, "position", id.endsWith("z2") ? 88 : 50, 0, 100);
            Rect bounds;
            if (side) {
                int start = top + (int) (32 * density);
                int end = bottom - (int) (32 * density);
                int size = Math.max(0, end - start) * length / 100;
                int offset = start + (end - start - size) * position / 100;
                bounds = edge.equals("left") ? new Rect(left, offset, left + (int) thickness, offset + size)
                        : new Rect(right - (int) thickness, offset, right, offset + size);
            } else {
                int size = (right - left) * length / 100;
                int offset = left + (right - left - size) * position / 100;
                if (id.startsWith("bottom_navigation")) {
                    int index = id.charAt(id.length() - 1) - '1';
                    int segment = (right - left - (int) (48 * density)) / 3;
                    size = segment * length / 100;
                    offset = left + (int) (24 * density) + index * segment
                            + (segment - size) * position / 100;
                }
                int y = edge.equals("top") ? top + (int) (config.integer("edge_top_vertical_offset_dp") * density)
                        : bottom - (int) thickness - (int) (6 * density);
                bounds = new Rect(offset, y, offset + size, y + (int) thickness);
            }
            for (Zone earlier : result) {
                if (!earlier.edge.equals(edge) || !Rect.intersects(bounds, earlier.bounds)) continue;
                if (side) {
                    if (bounds.centerY() >= earlier.bounds.centerY()) bounds.top = earlier.bounds.bottom + (int) (4 * density);
                    else bounds.bottom = earlier.bounds.top - (int) (4 * density);
                }
            }
            if (!bounds.intersect(left, top, right, bottom) || bounds.isEmpty()) continue;
            EdgeGoDrawings drawings = drawingModels.computeIfAbsent(id, key -> new EdgeGoDrawings(config.drawings(key),
                    config.bool("drawing_orientation_sensitive"), config.integer("drawing_confidence"), edge));
            result.add(new Zone(id, edge, bounds, config, drawings));
        }
        return result;
    }

    public synchronized boolean shouldIntercept(MotionEvent event) {
        if (!started || !runtime.active || injecting || macros.recording() || gestural && navigationBlocked
                || event.getDisplayId() != Display.DEFAULT_DISPLAY
                || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                || event.getDeviceId() == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
                || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false;
        if (event.getDownTime() == decisionTime && event.getDeviceId() == decisionDevice) return decision != null || decisionConsumesOutside;
        decisionTime = event.getDownTime();
        gestureOwned = false;
        decisionDevice = event.getDeviceId();
        decision = null;
        decisionConsumesOutside = false;
        int x = (int) event.getX(), y = (int) event.getY();
        if (panels != null && panels.ownsTouch(x, y)) return false;
        if (config.bool("respect_app_exclusion") && runtime.exclusions.contains(x, y)) return false;
        for (Zone zone : runtime.zones) if (zone.bounds.contains(x, y)) {
            decision = zone;
            break;
        }
        if (decision == null && imeBoundsKnown && appTouchBounds.contains(x, y) && panels != null) decisionConsumesOutside = panels.consumesOutsideTouch(x, y);
        return decision != null || decisionConsumesOutside;
    }

    public synchronized boolean pilferIfOwned() {
        if (decision == null && !decisionConsumesOutside) return false;
        if (gestureOwned && monitor != null) handler.post(this::onClaim);
        return true;
    }

    public void setNavigationBlocked(boolean blocked) {
        if (navigationBlocked == blocked) return;
        navigationBlocked = blocked;
        handler.post(() -> { if (started && gestural) stateChanged(); });
    }
    public void onNativeBackGesture(long downTime) { handler.post(() -> { if (macros != null) macros.recordBack(downTime); }); }
    public boolean isEdgeGoInjectedGesture(MotionEvent event) {
        if (event.getDeviceId() != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
                || (event.getFlags() & MotionEvent.FLAG_IS_GENERATED_GESTURE) == 0) return false;
        for (long downTime : injectedDownTimes) if (downTime == event.getDownTime()) return true;
        return false;
    }

    private void onInput(InputEvent inputEvent) {
        if (!(inputEvent instanceof MotionEvent)) return;
        MotionEvent event = (MotionEvent) inputEvent;
        if (event.getDisplayId() != Display.DEFAULT_DISPLAY
                || event.getDeviceId() == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD) return;
        macros.observe(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            gestureOwned = false;
            gestureDidExecute = false;
            if (!canRun()) { stopInteraction(); return; }
            if (receiver != null) receiver.setBatchingEnabled(false);
            if (!shouldIntercept(event)) {
                recognizer.cancel(true);
                if (!panels.ownsTouch((int) event.getX(), (int) event.getY())) {
                    panels.outsideTouch(event.getX(), event.getY());
                    actions.onOutsideTouch();
                }
                return;
            }
            Zone zone;
            synchronized (this) { zone = decision; }
            if (zone == null && decisionConsumesOutside) {
                gestureOwned = true;
                if (monitor != null) monitor.pilferPointers();
                panels.outsideTouch(event.getX(), event.getY());
                actions.onOutsideTouch();
                return;
            }
            if (zone != null) {
                feedback.touch(zone);
                recognizer.start(zone, config, event, density);
            }
        } else {
            recognizer.event(event);
        }
        if (receiver != null && (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL)) {
            gestureOwned = false;
            receiver.setBatchingEnabled(true);
        }
    }

    @Override public void onClaim() {
        InputMonitorCompat current = monitor;
        if (current != null && recognizer.tracking()) {
            gestureOwned = true;
            current.pilferPointers();
        }
    }

    @Override
    public void onProgress(Zone zone, String action, float startX, float startY,
            float x, float y, float progress, boolean held) {
        if (nativeBack && (!action.equals("back") || held)) cancelNativeBack();
        if (action.equals("back") && !held && zone.side() && backAnimation.isPresent()
                && panels.isClosed() && !keyguard.isShowing()
                && !QuickStepContract.isBackGestureDisabled(flags, false)) {
            BackAnimation back = backAnimation.get();
            if (!nativeBack) {
                nativeBack = true;
                nativeBackThreshold = false;
                nativeBackEdge = zone.edge.equals("left") ? BackEvent.EDGE_LEFT : BackEvent.EDGE_RIGHT;
                back.onBackMotion(startX, startY, MotionEvent.ACTION_DOWN,
                        nativeBackEdge, Display.DEFAULT_DISPLAY);
            }
            back.setTriggerBack(progress >= .7f);
            back.onBackMotion(x, y, MotionEvent.ACTION_MOVE,
                    nativeBackEdge, Display.DEFAULT_DISPLAY);
            if (!nativeBackThreshold && progress >= 1) {
                nativeBackThreshold = true;
                back.onThresholdCrossed();
            }
        }
        feedback.track(zone, action, actions.label(action), startX, startY, x, y, progress, held, recognizer.selectedSector());
    }

    @Override
    public void onAction(String action, Zone zone, float x, float y, boolean held) {
        if (!canRun()) return;
        if (config.bool("disable_in_gaming_mode") && Settings.Secure.getIntForUser(
                context.getContentResolver(), GAME_ACTIVE, 0, user) == 1) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastActionTime < config.integer("gesture_cooldown_ms")) { cancelNativeBack(); return; }
        lastActionTime = now;
        gestureDidExecute = true;
        if (action.equals("back") && !panels.isClosed()) {
            cancelNativeBack(); panels.dismiss(); return;
        }
        if (action.equals("back") && nativeBack) {
            backAnimation.get().setTriggerBack(true);
            backAnimation.get().onBackMotion(x, y, MotionEvent.ACTION_UP,
                    zone.edge.equals("left") ? BackEvent.EDGE_LEFT : BackEvent.EDGE_RIGHT, Display.DEFAULT_DISPLAY);
            nativeBack = false;
        } else {
            cancelNativeBack();
            actions.execute(action, zone, x, y, held);
        }
    }

    @Override public void onTapAction(String action, Zone zone, float x, float y) {
        if (!canRun()) return;
        feedback.touch(zone);
        feedback.track(zone, action, actions.label(action), x, y, x, y, 1, false, -1);
        if (config.bool("haptic_feedback") && zone.vibrate) feedback.haptic(config, false);
        onAction(action, zone, x, y, false);
        feedback.finish(true, 0);
    }

    @Override public boolean onContinuousStart(String action, Zone zone,
            float startX, float startY, float x, float y) {
        cancelNativeBack();
        if (!canRun() || config.bool("disable_in_gaming_mode") && Settings.Secure.getIntForUser(
                context.getContentResolver(), GAME_ACTIVE, 0, user) == 1) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastActionTime < config.integer("gesture_cooldown_ms")) return false;
        if (!actions.beginContinuous(action, zone, startX, startY, x, y)) return false;
        lastActionTime = now;
        gestureDidExecute = true;
        return true;
    }
    @Override public void onContinuousMove(float x, float y) { actions.moveContinuous(x, y); }
    @Override public void onFinish(boolean canceled, float velocity) {
        cancelNativeBack();
        gestureOwned = false;
        if (receiver != null) receiver.setBatchingEnabled(true);
        if (actions != null) actions.endContinuous(canceled);
        if (panels != null) panels.endTracking(canceled);
        if (feedback != null) feedback.finish(!canceled && gestureDidExecute, velocity);
    }
    @Override public void onDirectionChanged(boolean held) {
        if (config.bool("haptic_feedback") && (held || config.bool("sector_haptics"))) feedback.haptic(config, held);
    }

    private void cancelNativeBack() {
        if (!nativeBack) return;
        backAnimation.ifPresent(back -> {
            back.setTriggerBack(false);
            back.onBackMotion(0, 0, MotionEvent.ACTION_CANCEL, nativeBackEdge, Display.DEFAULT_DISPLAY);
        });
        nativeBack = false;
        nativeBackThreshold = false;
    }

    private void stopInteraction() {
        if (recognizer != null) recognizer.cancel(true);
        cancelNativeBack();
        gestureOwned = false;
        if (inputSession != null) inputSession.cancel();
        if (panels != null) panels.close();
        if (actions != null) actions.cancelInteraction();
        if (macros != null) macros.cancel();
        synchronized (this) { decisionTime = Long.MIN_VALUE; decision = null; decisionConsumesOutside = false; }
    }

    private void disposeMonitor() {
        if (receiver != null) { receiver.dispose(); receiver = null; }
        if (monitor != null) { monitor.dispose(); monitor = null; }
    }

    private void readCommand() {
        String raw = Settings.Secure.getStringForUser(context.getContentResolver(), EdgeGoConfig.COMMAND, user);
        String[] parts = raw == null ? new String[0] : raw.split("\\|", 4);
        if (parts.length < 2) return;
        try {
            if (Math.abs(System.currentTimeMillis() - Long.parseLong(parts[0])) > 3000) return;
        } catch (NumberFormatException e) { return; }
        if (parts[1].equals("record") && parts.length >= 3 && canRun()) {
            int returnTask = -1;
            if (parts.length == 4) {
                try { returnTask = Integer.parseInt(parts[3]); } catch (NumberFormatException ignored) { }
            }
            macros.record(parts[2], returnTask);
        }
        else if (parts[1].equals("run_macro") && parts.length == 3 && canRun()) macros.run(parts[2]);
        else if (parts[1].equals("stop_record")) macros.finishRecording();
        else if (parts[1].equals("haptic")) feedback.haptic(config, true);
        else if (parts[1].equals("reset_positions")) panels.resetPositions();
        else if (parts[1].equals("training_on")) pauseFeature(120000);
        else if (parts[1].equals("training_off")) { pausedUntil = 0; scheduleRefresh(); }
    }

    @Override public EdgeGoConfig config() { return config; }
    @Override public int userId() { return user; }
    @Override public Context userContext() { return users.getUserContext(); }
    @Override public String foreground() { return foreground; }
    @Override public int taskId() { return foregroundTask; }
    @Override public Rect inputBounds() { return new Rect(replayBounds); }
    @Override public Rect cursorBounds() {
        Rect bounds = new Rect(replayBounds);
        bounds.top = 0;
        return bounds;
    }
    Rect panelBounds() { return new Rect(appTouchBounds); }
    boolean keyboardVisible() { return (flags & SYSUI_STATE_IME_VISIBLE) != 0; }
    boolean hasKeyboardBounds() { return imeBoundsKnown; }
    @Override public void markInjectedGesture(long downTime) {
        long[] previous = injectedDownTimes;
        long[] next = new long[Math.min(previous.length + 1, 4)];
        next[0] = downTime; System.arraycopy(previous, 0, next, 1, next.length - 1); injectedDownTimes = next;
    }
    int foregroundUserId() { return foregroundUser; }
    @Override public boolean locked() { return keyguard.isShowing(); }
    @Override public long flags() { return flags; }
    @Override public boolean canRun() {
        return config.enabled && !switchingUser && provisioned.isDeviceProvisioned()
                && provisioned.isUserSetup(user) && (flags & BLOCKING_FLAGS) == 0
                && !(gestural && navigationBlocked)
                && wakefulness.getWakefulness() == WakefulnessLifecycle.WAKEFULNESS_AWAKE
                && !keyguard.isPrimaryBouncerShowing()
                && !oneHandedTransition
                && !(config.bool("disable_in_gaming_mode") && (gaming || GAME_PACKAGE.equals(foreground)))
                && !(config.bool("disable_with_touch_exploration") && accessibility.isTouchExplorationEnabled())
                && !(config.bool("disable_in_fullscreen") && fullscreen());
    }
    @Override public boolean canNavigateBack() {
        return (flags & QuickStepContract.SYSUI_STATE_BACK_DISABLED) == 0 && !(gestural && navigationBlocked);
    }
    @Override public boolean canInject(String expectedPackage, int expectedUser) {
        return canInject(expectedPackage, expectedUser,
                SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED | SYSUI_STATE_QUICK_SETTINGS_EXPANDED | SYSUI_STATE_DIALOG_SHOWING);
    }
    @Override public boolean canContinueStatusBarGesture(String expectedPackage, int expectedUser) {
        return canInject(expectedPackage, expectedUser, SYSUI_STATE_DIALOG_SHOWING);
    }
    private boolean canInject(String expectedPackage, int expectedUser, long blockedFlags) {
        return canRun() && !locked() && expectedUser == user && foregroundUser == user && foregroundTask >= 0 && !expectedPackage.isEmpty()
                && (flags & blockedFlags) == 0
                && expectedPackage.equals(foreground) && !appLocker.needsAuth(expectedPackage, user);
    }
    @Override public void prepareInjection(boolean active) {
        injecting = active;
        panels.prepareInjection(active);
        if (canRun() && SystemClock.elapsedRealtime() >= pausedUntil) feedback.show(config, runtime.zones, active);
        else feedback.hide();
    }
    @Override public void showPanel(String action, Zone zone, float x, float y, boolean tracking) {
        panels.show(action, zone, x, y, tracking);
    }
    @Override public void closePanels() { panels.close(); }
    @Override public boolean prepareAppAction() { return panels.prepareAppAction(); }
    @Override public void mediaChanged() { if (panels != null) panels.onMediaChanged(); }
    @Override public void hideFeedback() { feedback.hideAnimation(); }
    @Override public void showTextPicker(ArrayList<android.os.Bundle> texts) { panels.showTextPicker(texts); }
    @Override public void trackPanel(float x, float y) { panels.track(x, y); }
    @Override public void showControl(String action, String label, float value, Zone zone, float x, float y) {
        feedback.control(action, label, value, zone, x, y);
    }
    @Override public void pauseZone(Zone zone, long duration) {
        if (zone == null) return;
        pausedZones.put(zone.id, SystemClock.elapsedRealtime() + duration);
        scheduleRefresh();
    }
    @Override public void pauseFeature(long duration) {
        pausedUntil = SystemClock.elapsedRealtime() + duration;
        stateChanged();
    }
    @Override public void runMacro(String id) { macros.run(id); }
    EdgeGoInputSession inputSession() { return inputSession; }
    EdgeGoMacroRunner macros() { return macros; }
    void refreshForMacroState() { scheduleRefresh(); }
    void refreshForPanelState() { if (panels != null && panels.isClosed()) panelInsets = null; scheduleRefresh(); }
    void onPanelInsetsChanged(WindowInsets insets) { panelInsets = insets; scheduleRefresh(); }
    boolean panelOwnsTouch(int x, int y) { return panels.ownsTouch(x, y); }
    Region reservedZoneRegion() {
        Region region = new Region();
        if (injecting || macros.recording()) return region;
        for (Zone zone : runtime.zones) region.union(zone.bounds);
        if (config.bool("respect_app_exclusion")) region.op(runtime.exclusions, Region.Op.DIFFERENCE);
        return region;
    }
    Region reservedSystemRegion() {
        Region region = new Region(displayBounds);
        region.op(appTouchBounds, Region.Op.DIFFERENCE);
        return region;
    }
    boolean fullscreen() { return statusBarHidden || (flags & SYSUI_STATE_NAV_BAR_HIDDEN) != 0; }

    @Override public void dump(PrintWriter pw, String[] args) {
        pw.println("enabled=" + config.enabled + " active=" + runtime.active + " user=" + user);
        pw.println("zones=" + runtime.zones.size() + " nativeBack=" + nativeBack + " injecting=" + injecting);
        pw.println("gameSpaceGuard=" + config.bool("disable_in_gaming_mode") + " gameSpaceActive=" + gaming);
        pw.println("gestureNavigation=" + gestural + " tracking=" + (recognizer != null && recognizer.tracking()));
    }
}
