/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.edgego;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.Typeface;
import android.database.ContentObserver;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupMenu;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.core.graphics.ColorUtils;

import com.android.internal.util.lunaris.EdgeGoConfig;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class EdgeGoPanelWindow {
    private final Context context;
    private final WindowManager windows;
    private final Handler handler;
    private final EdgeGoActions actions;
    private final EdgeGoGestureController host;
    private final EdgeGoIcons icons;
    private final DisplayManager displays;
    private final InputManager input;
    private final Runnable idle = this::dismiss;
    private final List<FrameLayout> closingViews = new ArrayList<>();
    private final Runnable tick = this::tick;
    private final Runnable layoutUpdate = this::updateLayout;
    private volatile Region touchRegion = new Region();
    private volatile boolean visible, consumeOutside;
    private FrameLayout root;
    private View content;
    private WindowManager.LayoutParams layout;
    private EdgeGoGestureController.Zone zone;
    private EdgeGoConfig config;
    private String action = "", base = "";
    private boolean fullscreen, editing;
    private int generation, notificationIndex;
    private float dp, openingX, openingY, dragX, dragY;
    private TextView progressText;
    private EdgeGoMediaView mediaView;
    private Button macroPause;
    private GradientDrawable macroIndicator;
    private ValueAnimator macroPulse;
    private SpringAnimation openingSpring;
    private MediaController observedMedia;
    private TextView launcherEmpty;
    private boolean passive;
    private int sidePage;
    private final List<Button> sideTabs = new ArrayList<>();
    private final List<EdgeGoLevelView> volumeLevels = new ArrayList<>(), brightnessLevels = new ArrayList<>();
    private boolean volumeListening, brightnessListening;
    private final BroadcastReceiver levelsChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshLevels(); }
    };
    private final ContentObserver brightnessChanged;
    private final DisplayManager.DisplayListener displayChanged = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }
        @Override public void onDisplayChanged(int displayId) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY) refreshLevels();
        }
    };
    private final Runnable privacyChanged = this::onPrivacyChanged;
    private EdgeGoNotifications notifications;
    private String notificationKey;
    private String displayedNotificationKey;
    private int notificationDirection = 1;
    private Button notificationFilterButton;
    private String notificationFilter = "";
    private OnBackInvokedCallback back;
    private OnBackInvokedDispatcher dispatcher;
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { tick(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { tick(); }
        @Override public void onSessionDestroyed() { tick(); }
        @Override public void onAudioInfoChanged(MediaController.PlaybackInfo info) { refreshLevels(); }
    };
    private final NotifCollectionListener notificationListener = new NotifCollectionListener() {
        @Override public void onEntryUpdated(NotificationEntry entry) { refreshNotifications(); }
        @Override public void onEntryAdded(NotificationEntry entry) { refreshNotifications(); }
        @Override public void onEntryRemoved(NotificationEntry entry, int reason) { refreshNotifications(); }
    };

    EdgeGoPanelWindow(Context context, WindowManager windows, Handler handler, EdgeGoActions actions,
            EdgeGoGestureController host) {
        this.context = context;
        this.windows = windows;
        this.handler = handler;
        this.actions = actions;
        this.host = host;
        icons = new EdgeGoIcons(context, handler, actions.background());
        displays = context.getSystemService(DisplayManager.class);
        input = context.getSystemService(InputManager.class);
        brightnessChanged = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) { refreshLevels(); }
        };
    }

    boolean isClosed() { return root == null; }
    boolean ownsTouch(int x, int y) { return touchRegion.contains(x, y); }

    void show(String action, EdgeGoGestureController.Zone zone, float x, float y, boolean tracking) {
        if (this.action.equals(action) && root != null) { dismiss(); return; }
        close();
        if (!host.canRun()) return;
        this.action = action;
        base = action.split(":", 2)[0];
        this.zone = zone;
        config = host.config();
        String theme = base.equals("side_panel") ? "side_panel_theme" : base.equals("notification_carousel")
                ? "notification_carousel_theme" : base.equals("notch_timer") ? "notch_timer_theme_mode"
                : base.equals("media_controller") || base.equals("notch_media") ? "media_controller_theme" : "";
        icons.setThemeOverride(theme.isEmpty() ? "" : config.string(theme));
        icons.setUserContext(host.userContext());
        dp = context.getResources().getDisplayMetrics().density * config.integer("floating_panel_scale") / 100f;
        Rect screen = windows.getMaximumWindowMetrics().getBounds();
        openingX = x == 0 ? screen.width() * .5f : x;
        openingY = y == 0 ? screen.height() * .75f : y;
        fullscreen = base.equals("show_quick_access") || base.equals("preset_picker") || base.equals("remote_cursor")
                || base.equals("touch_pad") || base.equals("drawing_pad") || base.equals("screen_blackout");
        root = new FrameLayout(context) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                boolean spy = spyPanel();
                if (spy && event.getDeviceId() == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
                        && (event.getFlags() & MotionEvent.FLAG_IS_GENERATED_GESTURE) != 0) return true;
                boolean terminal = event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL;
                if (spy && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    if (base.startsWith("macro_")) host.macros().beginPanelTouch();
                    else host.inputSession().cancel();
                    if (getViewRootImpl() != null && getViewRootImpl().getInputToken() != null) {
                        try { input.pilferPointers(getViewRootImpl().getInputToken()); }
                        catch (RuntimeException e) { Log.w("EdgeGoGesture", "Cannot claim panel input", e); }
                    }
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !passive
                        && (layout.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                    layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    updateLayout();
                }
                touched();
                boolean handled = super.dispatchTouchEvent(event);
                if (spy && terminal) {
                    host.macros().endPanelTouch();
                    tick();
                }
                return handled;
            }
            @Override protected void onMeasure(int width, int height) {
                if (!fullscreen) {
                    Rect bounds = windows.getMaximumWindowMetrics().getBounds();
                    Insets safe = windows.getMaximumWindowMetrics().getWindowInsets().getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                    WindowInsets own = getRootWindowInsets();
                    if (own != null) safe = Insets.max(safe, own.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout()));
                    Rect area = host.panelBounds();
                    int available = bounds.height() - safe.top - safe.bottom - (int) (24 * dp);
                    if (!area.isEmpty()) available = Math.min(available, area.height() - (int) (16 * dp));
                    height = MeasureSpec.makeMeasureSpec(Math.max(1, available), MeasureSpec.AT_MOST);
                }
                super.onMeasure(width, height);
            }
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) { dismiss(); return true; }
                return super.dispatchKeyEvent(event);
            }
            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !contentContains(event.getX(), event.getY())) dismiss();
                return true;
            }
        };
        root.setFocusableInTouchMode(true);
        root.setOnApplyWindowInsetsListener((view, insets) -> { if (view == root) host.onPanelInsetsChanged(insets); return insets; });
        root.setOnTouchListener((view, event) -> {
            touched();
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { outsideTouch(-1, -1); return true; }
            return false;
        });
        Rect area = host.panelBounds();
        int availableWidth = area.isEmpty() ? screen.width() : area.width();
        layout = new WindowManager.LayoutParams(fullscreen ? screen.width() : Math.max(1, Math.min(availableWidth - (int) (24 * dp), (int) (340 * dp))),
                fullscreen ? screen.height() : WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        layout.setTitle("EdgeGo " + base);
        layout.setFitInsetsTypes(0);
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        layout.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                | WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION;
        passive = base.equals("remote_cursor") || base.equals("touch_pad") || base.equals("drawing_pad")
                || base.equals("page_scroll") || base.startsWith("macro_") || base.equals("screen_blackout") || tracking;
        if (passive) layout.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (spyPanel()) layout.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_SPY;
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        boolean workspaceControl = base.startsWith("macro_") || base.equals("remote_cursor")
                || base.equals("touch_pad") || base.equals("page_scroll");
        if (!workspaceControl && (base.equals("show_quick_access") ? config.bool("quick_access_blur_background")
                : (!fullscreen || base.equals("preset_picker")) && config.bool("floating_panel_blur"))
                && !context.getSystemService(PowerManager.class).isPowerSaveMode()) {
            layout.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            layout.setBlurBehindRadius((int) (24 * dp));
        }
        if (base.equals("notification_carousel") && config.bool("notification_carousel_show_scrim")) {
            layout.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            layout.dimAmount = .25f;
        }
        if (base.equals("side_panel") || base.equals("notification_carousel") || base.equals("text_tools")) {
            layout.flags |= WindowManager.LayoutParams.FLAG_SECURE;
        }
        build(tracking);
        if (content == null) { close(); return; }
        if (fullscreen) root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        else {
            ScrollView scroll = new ScrollView(context);
            scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
            root.addView(scroll, new FrameLayout.LayoutParams(-1, -2));
        }
        position(screen);
        FrameLayout attached = root;
        root.getViewTreeObserver().addOnComputeInternalInsetsListener(info -> {
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
            if (root != attached) { info.touchableRegion.setEmpty(); return; }
            Region region = localTouchRegion();
            Region reserved = host.reservedZoneRegion();
            reserved.op(host.reservedSystemRegion(), Region.Op.UNION);
            reserved.translate(-layout.x, -layout.y);
            region.op(reserved, Region.Op.DIFFERENCE);
            info.touchableRegion.set(region);
            Region global = new Region(region);
            global.translate(layout.x, layout.y);
            touchRegion = global;
        });
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (root != attached) return;
            clampPosition();
            updateTouchRegion();
        });
        try {
            windows.addView(root, layout);
            visible = true;
            consumeOutside = !passesOutsideTaps() && !base.startsWith("macro_") && !base.equals("remote_cursor") && !base.equals("page_scroll");
            root.post(() -> {
                if (root != attached) return;
                dispatcher = root.findOnBackInvokedDispatcher();
                if (dispatcher != null) {
                    back = this::dismiss;
                    dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back);
                }
            });
            if (ValueAnimator.areAnimatorsEnabled() && !tracking) {
                root.setAlpha(0);
                root.setScaleX(.92f); root.setScaleY(.92f);
                openingSpring = new SpringAnimation(root, SpringAnimation.SCALE_X);
                openingSpring.setSpring(new SpringForce(1).setStiffness(config.integer("animation_stiffness"))
                        .setDampingRatio(config.integer("animation_damping") / 100f));
                openingSpring.addUpdateListener((animation, value, velocity) -> attached.setScaleY(value));
                openingSpring.animateToFinalPosition(1);
                root.animate().alpha(1).setDuration(180)
                        .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1)).start();
            }
            touched();
            tick();
            host.refreshForPanelState();
            if (!volumeLevels.isEmpty()) {
                IntentFilter changes = new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION);
                changes.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
                context.registerReceiver(levelsChanged, changes, null, handler, Context.RECEIVER_NOT_EXPORTED);
                volumeListening = true;
            }
            if (!brightnessLevels.isEmpty()) {
                displays.registerDisplayListener(displayChanged, handler, DisplayManager.EVENT_TYPE_DISPLAY_BRIGHTNESS);
                brightnessListening = true;
                context.getContentResolver().registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                        false, brightnessChanged, UserHandle.USER_ALL);
            }
        } catch (RuntimeException e) { Log.w("EdgeGoGesture", "Cannot show panel", e); close(); }
    }

    private void build(boolean tracking) {
        if (base.equals("show_quick_access") || base.equals("preset_picker")) {
            boolean preset = base.equals("preset_picker");
            List<EdgeGoPanelItem> items = preset ? presets() : items("quick_access_items" + (action.endsWith(":2") ? "_2" : ""));
            if (!preset) addPieModules(items);
            if (items.isEmpty()) {
                Toast.makeText(context, preset ? R.string.edgego_no_presets : R.string.edgego_no_items, Toast.LENGTH_SHORT).show();
                return;
            }
            content = new EdgeGoPieView(context, handler, config, icons, actions, items, zone, openingX, openingY,
                    tracking && config.string("quick_access_open_mode").equals("slide"), preset, new EdgeGoPieView.Listener() {
                @Override public void close() { EdgeGoPanelWindow.this.dismiss(); }
                @Override public void select(EdgeGoPanelItem item, boolean held) {
                    String selected = held ? item.holdAction : item.action;
                    if (!config.bool("quick_access_keep_open_on_action")) EdgeGoPanelWindow.this.close();
                    actions.execute(selected, zone, openingX, openingY, false);
                }
                @Override public void module(EdgeGoPanelItem item, java.util.function.Consumer<List<EdgeGoPanelItem>> ready) {
                    openModule(item, ready);
                }
            });
            return;
        }
        if (base.equals("remote_cursor") || base.equals("touch_pad")) {
            content = new EdgeGoCursorView(context, handler, config, host, actions, icons, base.equals("remote_cursor"),
                    openingX, openingY, tracking, new EdgeGoCursorView.Listener() {
                @Override public void close() { EdgeGoPanelWindow.this.dismiss(); }
                @Override public void touched() { EdgeGoPanelWindow.this.touched(); }
                @Override public void moved(Rect bounds) { updateTouchRegion(); }
            });
            return;
        }
        if (base.equals("drawing_pad")) { content = new EdgeGoDrawingView(context, config, actions, icons, host, this::close); return; }
        if (base.equals("screen_blackout")) {
            TextView blackout = new TextView(context);
            blackout.setBackgroundColor(0xff000000);
            blackout.setText(R.string.edgego_blackout_hint);
            blackout.setTextColor(0xff888888);
            blackout.setGravity(Gravity.CENTER);
            blackout.setOnClickListener(view -> close());
            content = blackout;
            return;
        }
        LinearLayout body = column();
        int padding = (int) ((base.startsWith("macro_") ? 12 : 16) * dp);
        body.setPadding(padding, (int) (12 * dp), padding, padding);
        body.setBackground(surface());
        body.setClipToOutline(true);
        TextView title = text(base.startsWith("macro_") ? context.getString(R.string.edgego_macro)
                : base.equals("text_tools") ? context.getString(R.string.edgego_text_tools) : actions.label(base), 18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, 0, 0, (int) (8 * dp));
        title.setOnTouchListener(this::drag);
        body.addView(title);
        if (base.equals("volume_slider") || base.equals("brightness_slider")) slider(body, base.equals("brightness_slider"));
        else if (base.equals("media_scrub")) media(body, true);
        else if (base.equals("media_controller") || base.equals("notch_media")) media(body, false);
        else if (base.equals("quick_tools")) tools(body);
        else if (base.equals("app_launcher")) launcher(body);
        else if (base.equals("side_panel")) sidePanel(body);
        else if (base.equals("notch_apps")) {
            List<EdgeGoPanelItem> items = config.bool("notch_apps_sync_quick_access_items") ? items("quick_access_items" + (action.endsWith(":2") ? "_2" : ""))
                    : items("notch_apps_items" + (action.endsWith(":2") ? "_2" : ""));
            list(body, items, ViewGroup.LayoutParams.WRAP_CONTENT);
        } else if (base.equals("notification_carousel")) {
            notifications = actions.notifications();
            notifications.pipeline().addCollectionListener(notificationListener);
            notifications.addPrivacyListener(privacyChanged);
            notificationIndex = 0;
            notification(body);
        } else if (base.equals("page_scroll")) {
            progressText = text("", 14);
            body.addView(progressText);
            if (config.string("page_scroll_variant").equals("hold_drag")) {
                final float[] origin = new float[1];
                progressText.setMinHeight((int) (72 * dp));
                progressText.setGravity(Gravity.CENTER);
                progressText.setOnTouchListener((view, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { origin[0] = event.getRawY(); view.getParent().requestDisallowInterceptTouchEvent(true); }
                    if (event.getActionMasked() == MotionEvent.ACTION_MOVE && Math.abs(event.getRawY() - origin[0]) >= 36 * dp) {
                        actions.scrollOnce(event.getRawY() < origin[0]); origin[0] = event.getRawY();
                    }
                    if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
                });
            }
            LinearLayout row = row();
            button(row, context.getString(R.string.edgego_slower), () -> { actions.changeScroll(-1); tick(); });
            button(row, context.getString(R.string.edgego_pause_resume), () -> { actions.toggleScroll(); tick(); });
            button(row, context.getString(R.string.edgego_faster), () -> { actions.changeScroll(1); tick(); });
            body.addView(row);
            button(body, context.getString(R.string.edgego_reverse), actions::reverseScroll);
            button(body, context.getString(R.string.edgego_close), () -> { actions.stopScrolling(); dismiss(); });
        } else if (base.equals("notch_timer")) timer(body);
        else if (base.startsWith("macro_")) {
            progressText = text("", 14);
            progressText.setMinHeight((int) (40 * dp));
            progressText.setGravity(Gravity.CENTER_VERTICAL);
            macroIndicator = new GradientDrawable();
            macroIndicator.setShape(GradientDrawable.OVAL);
            macroIndicator.setBounds(0, 0, (int) (10 * dp), (int) (10 * dp));
            macroIndicator.setColor(base.equals("macro_recording")
                    ? icons.dark(config) ? 0xfff2b8b5 : 0xffb3261e : icons.accent(config));
            progressText.setCompoundDrawables(macroIndicator, null, null, null);
            progressText.setCompoundDrawablePadding((int) (10 * dp));
            body.addView(progressText);
            LinearLayout controls = row();
            macroPause = button(controls, context.getString(R.string.edgego_pause), () -> { host.macros().togglePause(); tick(); });
            if (base.equals("macro_recording")) {
                Button save = button(controls, context.getString(R.string.edgego_save), () -> host.macros().finishRecording());
                EdgeGoUi.colors(save, icons.accent(config), icons.onAccent(config));
            }
            button(controls, context.getString(R.string.edgego_stop), () -> {
                if (host.macros().recording()) host.macros().discardRecording(); else host.macros().cancel();
            });
            body.addView(controls);
        }
        content = body;
    }

    private void position(Rect screen) {
        if (fullscreen) { layout.x = layout.y = 0; return; }
        Insets safe = windows.getMaximumWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        Rect area = host.panelBounds();
        if (!area.isEmpty()) safe = Insets.max(safe, Insets.of(area.left, area.top, screen.width() - area.right, screen.height() - area.bottom));
        layout.x = Math.max(safe.left + (int) (12 * dp), Math.min(screen.width() - layout.width - safe.right - (int) (12 * dp),
                (int) openingX - layout.width / 2));
        layout.y = base.startsWith("notch_") || base.startsWith("macro_")
                ? safe.top + (int) (12 * dp) : (int) openingY - (int) (120 * dp);
        if (config.bool("floating_panel_remember_position")) {
            JSONObject positions = EdgeGoSchedules.state(context, host.userId()).optJSONObject("positions");
            JSONObject position = positions == null ? null : positions.optJSONObject(base);
            if (position != null) {
                layout.x = (int) (position.optDouble("x", .5) * screen.width());
                layout.y = (int) (position.optDouble("y", .5) * screen.height());
            }
        }
    }

    private void clampPosition() {
        if (fullscreen || root == null) return;
        Rect screen = windows.getMaximumWindowMetrics().getBounds();
        Insets safe = windows.getMaximumWindowMetrics().getWindowInsets().getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
        WindowInsets own = root.getRootWindowInsets();
        if (own != null) safe = Insets.max(safe, own.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout()));
        Rect area = host.panelBounds();
        if (!area.isEmpty()) safe = Insets.max(safe, Insets.of(area.left, area.top, screen.width() - area.right, screen.height() - area.bottom));
        int x = Math.max(safe.left, Math.min(screen.width() - root.getWidth() - safe.right, layout.x));
        int y = Math.max(safe.top, Math.min(screen.height() - root.getHeight() - safe.bottom, layout.y));
        if (x != layout.x || y != layout.y) { layout.x = x; layout.y = y; handler.post(layoutUpdate); }
    }

    private boolean drag(View view, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { dragX = event.getRawX() - layout.x; dragY = event.getRawY() - layout.y; return true; }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            layout.x = (int) (event.getRawX() - dragX); layout.y = (int) (event.getRawY() - dragY);
            clampPosition();
            handler.removeCallbacks(layoutUpdate); handler.post(layoutUpdate);
            touched();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (config.bool("floating_panel_remember_position")) {
                Rect screen = windows.getMaximumWindowMetrics().getBounds();
                JSONObject state = EdgeGoSchedules.state(context, host.userId());
                JSONObject positions = state.optJSONObject("positions");
                if (positions == null) positions = new JSONObject();
                JSONObject position = new JSONObject();
                EdgeGoConfig.put(position, "x", layout.x / (double) screen.width());
                EdgeGoConfig.put(position, "y", layout.y / (double) screen.height());
                EdgeGoConfig.put(positions, base, position); EdgeGoConfig.put(state, "positions", positions);
                EdgeGoSchedules.save(context, host.userId(), state);
            }
            return true;
        }
        return false;
    }

    private void updateLayout() {
        if (root != null) {
            try { windows.updateViewLayout(root, layout); }
            catch (IllegalArgumentException ignored) { }
            updateTouchRegion();
        }
    }

    private Region localTouchRegion() {
        if (passive && host.keyboardVisible() && !host.hasKeyboardBounds()) return new Region();
        if (content instanceof EdgeGoPieView) return new Region(((EdgeGoPieView) content).touchBounds());
        if (content instanceof EdgeGoCursorView) return ((EdgeGoCursorView) content).touchRegion();
        return new Region(0, 0, root == null ? 0 : root.getWidth(), root == null ? 0 : root.getHeight());
    }

    private void updateTouchRegion() {
        if (root == null) return;
        Region region = localTouchRegion();
        region.translate(layout.x, layout.y);
        region.op(host.reservedZoneRegion(), Region.Op.DIFFERENCE);
        region.op(host.reservedSystemRegion(), Region.Op.DIFFERENCE);
        if (!region.equals(touchRegion)) {
            touchRegion = region;
            root.requestLayout();
        }
    }

    private boolean contentContains(float x, float y) { return localTouchRegion().contains((int) x, (int) y); }

    private void slider(LinearLayout body, boolean brightness) {
        String prefix = brightness ? "brightness_slider" : "volume_slider";
        TextView label = text(context.getString(brightness ? R.string.edgego_brightness : R.string.edgego_volume), 14);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(0, (int) (8 * dp), 0, 0);
        body.addView(label);
        boolean accent = base.equals("quick_tools") ? config.bool("material_you_sync_quick_tools")
                : config.bool("material_you_sync_sliders") || config.bool(prefix + "_use_accent_color");
        int color = accent ? icons.accent(config) : config.integer(prefix + "_color_rgb") | 0xff000000;
        if (base.equals("quick_tools") && !accent) color = config.integer("quick_tools_slider_color_rgb") | 0xff000000;
        String orientation = config.string("slider_orientation");
        boolean vertical = !base.equals("quick_tools") && (orientation.equals("vertical")
                || orientation.equals("edge") && zone != null && zone.side());
        EdgeGoLevelView slider = new EdgeGoLevelView(context, vertical, config.string(prefix + "_style"), color,
                icons.surface(config, "continuous_gesture_overlay_background_color_rgb"),
                brightness ? actions.brightness() : actions.volume(actions.volumeStream()), value -> {
                    if (!host.canRun()) return;
                    if (brightness) actions.setBrightness(value, false); else actions.setVolume(actions.volumeStream(), value);
                    touched();
                });
        slider.setContentDescription(actions.label(prefix));
        slider.setHapticFeedbackEnabled(config.bool("haptic_feedback"));
        float density = context.getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(vertical ? (int) Math.max(56 * density, 80 * dp) : -1,
                (int) Math.max(56 * density, (vertical ? 220 : 56) * dp));
        size.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(slider, size);
        (brightness ? brightnessLevels : volumeLevels).add(slider);
    }

    private void media(LinearLayout body, boolean seekOnly) {
        notifications = actions.notifications();
        notifications.addPrivacyListener(privacyChanged);
        mediaView = new EdgeGoMediaView(context, config, icons, seekOnly, base.equals("quick_tools"), host::canRun, this::touched);
        body.addView(mediaView, new LinearLayout.LayoutParams(-1, -2));
    }

    private void tools(LinearLayout body) {
        JSONArray order = config.array("quick_tools_section_order");
        if (!config.contains("quick_tools_section_order")) order = new JSONArray().put("volume").put("brightness").put("media").put("actions");
        for (int i = 0; i < Math.min(order.length(), 4); i++) {
            switch (order.optString(i)) {
                case "volume": slider(body, false); break;
                case "brightness": slider(body, true); break;
                case "media": if (config.bool("quick_tools_show_media_controls")) media(body, false); break;
                case "actions": list(body, items("quick_tools_items"), 160); break;
            }
        }
    }

    private void launcher(LinearLayout body) {
        EditText search = input();
        search.setSingleLine(true);
        search.setHint(R.string.edgego_search_apps);
        search.setTextColor(icons.text(config));
        if (!config.bool("app_launcher_disable_search")) body.addView(search);
        List<EdgeGoPanelItem> all = new ArrayList<>();
        RecyclerView list = list(body, all, 320);
        ItemAdapter adapter = (ItemAdapter) list.getAdapter();
        launcherEmpty = text(context.getString(R.string.edgego_no_apps), 14);
        body.addView(launcherEmpty);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase(Locale.getDefault());
                List<EdgeGoPanelItem> filtered = new ArrayList<>();
                for (EdgeGoPanelItem item : all) if (item.label.toLowerCase(Locale.getDefault()).contains(query)
                        || config.bool("include_package_in_picker_search") && item.action.toLowerCase(Locale.ROOT).contains(query)) filtered.add(item);
                adapter.replace(filtered);
                touched();
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        loadApps(items -> {
            if (config.bool("app_launcher_show_pinned") || config.bool("app_launcher_show_only_pinned")) all.addAll(items("app_launcher_pinned_items"));
            if (!config.bool("app_launcher_show_only_pinned")) all.addAll(items);
            adapter.replace(all);
        });
        if (config.bool("app_launcher_open_keyboard_on_show") && !config.bool("app_launcher_disable_search")) search.post(() -> {
            if (root != null && search.isAttachedToWindow()) {
                search.requestFocus();
                context.getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(search, 0);
            }
        });
    }

    private void loadApps(java.util.function.Consumer<List<EdgeGoPanelItem>> ready) {
        int token = generation, user = host.userId();
        actions.background().execute(() -> {
            List<EdgeGoPanelItem> apps = new ArrayList<>();
            try {
                List<LauncherActivityInfo> activities = context.getSystemService(LauncherApps.class).getActivityList(null, UserHandle.of(user));
                for (LauncherActivityInfo info : activities) apps.add(new EdgeGoPanelItem(info.getLabel().toString(),
                        "launch_app_activity:" + info.getComponentName().getPackageName() + "|" + info.getComponentName().getClassName()));
            } catch (RuntimeException e) { Log.w("EdgeGoGesture", "Cannot read launcher apps", e); }
            Collator collator = Collator.getInstance();
            apps.sort((a, b) -> collator.compare(a.label, b.label));
            handler.post(() -> { if (root != null && token == generation && user == host.userId()) ready.accept(apps); });
        });
    }

    private void openModule(EdgeGoPanelItem item, java.util.function.Consumer<List<EdgeGoPanelItem>> ready) {
        switch (item.module) {
            case "volume": actions.execute("volume_slider", zone, openingX, openingY, false); break;
            case "brightness": actions.execute("brightness_slider", zone, openingX, openingY, false); break;
            case "recent_apps": recentApps(ready); break;
            case "app_drawer":
                List<EdgeGoPanelItem> drawer = items("quick_access_drawer_items");
                if (drawer.isEmpty() && !config.contains("quick_access_drawer_items")) loadApps(ready);
                else if (drawer.isEmpty()) Toast.makeText(context, R.string.edgego_no_items, Toast.LENGTH_SHORT).show();
                else ready.accept(drawer);
                break;
            case "current_app": currentApp(ready); break;
            case "now_playing": actions.execute("media_controller", zone, openingX, openingY, false); break;
            default: actions.unavailable();
        }
    }

    private void currentApp(java.util.function.Consumer<List<EdgeGoPanelItem>> ready) {
        int token = generation, user = host.userId();
        String pkg = host.foreground();
        actions.background().execute(() -> {
            List<EdgeGoPanelItem> items = new ArrayList<>();
            items.add(new EdgeGoPanelItem(actions.label("current_app_info"), "current_app_info"));
            try {
                LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery().setPackage(pkg)
                        .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                                | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
                List<android.content.pm.ShortcutInfo> shortcuts = context.getSystemService(LauncherApps.class)
                        .getShortcuts(query, UserHandle.of(user));
                if (shortcuts != null) for (android.content.pm.ShortcutInfo shortcut : shortcuts) {
                    if (shortcut.isEnabled() && items.size() < EdgeGoConfig.MAX_ITEMS) items.add(new EdgeGoPanelItem(
                            String.valueOf(shortcut.getShortLabel()), "shortcut:" + pkg + "|" + shortcut.getId()));
                }
            } catch (RuntimeException ignored) { }
            handler.post(() -> {
                if (root != null && token == generation && user == host.userId() && pkg.equals(host.foreground())) ready.accept(items);
            });
        });
    }

    private void recentApps(java.util.function.Consumer<List<EdgeGoPanelItem>> ready) {
        int token = generation, user = host.userId();
        actions.background().execute(() -> {
            List<EdgeGoPanelItem> items = new ArrayList<>();
            try {
                List<android.app.ActivityManager.RecentTaskInfo> tasks = android.app.ActivityTaskManager.getService()
                        .getRecentTasks(12, android.app.ActivityManager.RECENT_IGNORE_UNAVAILABLE, user).getList();
                for (android.app.ActivityManager.RecentTaskInfo task : tasks) {
                    if (task.userId != user || task.persistentId < 0 || task.getActivityType() == android.app.WindowConfiguration.ACTIVITY_TYPE_HOME) continue;
                    String pkg = EdgeGoActions.taskPackage(task);
                    if (pkg.isEmpty()) continue;
                    String label = host.userContext().getPackageManager().getApplicationLabel(host.userContext().getPackageManager().getApplicationInfo(pkg, 0)).toString();
                    items.add(new EdgeGoPanelItem(label, "launch_app_recent:" + pkg + "|" + task.persistentId));
                }
            } catch (Exception ignored) { }
            handler.post(() -> { if (root != null && token == generation && user == host.userId()) ready.accept(items); });
        });
    }

    private void sidePanel(LinearLayout body) {
        LinearLayout tabs = row();
        for (int page = 0; page < 3; page++) {
            String key = page == 0 ? "side_panel_clipboard_enabled" : page == 1 ? "side_panel_notes_enabled" : "side_panel_actions_enabled";
            if (!config.bool(key)) continue;
            int selectedPage = page;
            Button tab = button(tabs, context.getString(page == 0 ? R.string.edgego_clipboard : page == 1 ? R.string.edgego_notes : R.string.edgego_actions),
                    () -> showSidePage(body, selectedPage));
            tab.setTag(page);
            sideTabs.add(tab);
        }
        body.addView(tabs);
        int page = config.bool("side_panel_remember_page") ? EdgeGoSchedules.state(context, host.userId()).optInt("sidePanelPage", 0) : 0;
        if (!config.bool(page == 0 ? "side_panel_clipboard_enabled" : page == 1 ? "side_panel_notes_enabled" : "side_panel_actions_enabled")) {
            page = config.bool("side_panel_clipboard_enabled") ? 0 : config.bool("side_panel_notes_enabled") ? 1 : 2;
        }
        showSidePage(body, page);
    }

    private void showSidePage(LinearLayout body, int page) {
        editing = false;
        sidePage = page;
        for (Button tab : sideTabs) {
            boolean selected = Integer.valueOf(page).equals(tab.getTag());
            tab.setSelected(selected);
            EdgeGoUi.colors(tab, selected ? icons.accent(config) : icons.container(config),
                    selected ? icons.onAccent(config) : icons.text(config));
        }
        if (config.bool("side_panel_remember_page")) {
            JSONObject state = EdgeGoSchedules.state(context, host.userId());
            EdgeGoConfig.put(state, "sidePanelPage", page); EdgeGoSchedules.save(context, host.userId(), state);
        }
        while (body.getChildCount() > 2) body.removeViewAt(body.getChildCount() - 1);
        if (!config.bool(page == 0 ? "side_panel_clipboard_enabled" : page == 1 ? "side_panel_notes_enabled" : "side_panel_actions_enabled")) {
            body.addView(text(context.getString(R.string.edgego_no_items), 14)); return;
        }
        if (page == 0) {
            JSONArray clips = actions.privateStore().clips();
            ScrollView scroll = new ScrollView(context);
            LinearLayout column = column();
            for (int i = 0; i < Math.min(clips.length(), config.integer("side_panel_clipboard_limit")); i++) {
                String text = clips.optString(i);
                button(column, text, () -> {
                    if (config.bool("side_panel_tap_to_paste")) {
                        actions.copy(text); actions.execute("paste", zone, openingX, openingY, false);
                    } else textTools(text);
                });
            }
            scroll.addView(column);
            body.addView(scroll, new LinearLayout.LayoutParams(-1, (int) (240 * dp)));
            button(body, context.getString(R.string.edgego_clear_history), () -> { actions.privateStore().clearClips(); showSidePage(body, 0); });
        } else if (page == 1) {
            JSONArray notes = actions.privateStore().notes();
            ScrollView scroll = new ScrollView(context);
            LinearLayout column = column();
            for (int i = 0; i < Math.min(notes.length(), 32); i++) {
                int index = i;
                button(column, notes.optString(i), () -> editNote(body, notes, index));
            }
            scroll.addView(column); body.addView(scroll, new LinearLayout.LayoutParams(-1, (int) (240 * dp)));
            button(body, context.getString(R.string.edgego_add_note), () -> editNote(body, notes, -1));
        } else {
            list(body, items("side_panel_items"), 180);
            if (config.bool("side_panel_show_recent_apps")) recentApps(items -> {
                if (sidePage == 2 && !editing) list(body, items, 180);
            });
        }
        touched();
    }

    private void editNote(LinearLayout body, JSONArray notes, int index) {
        while (body.getChildCount() > 2) body.removeViewAt(body.getChildCount() - 1);
        EditText editor = input(); editor.setMinLines(3); editor.setMaxLines(6);
        editor.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2048)});
        editor.setText(index >= 0 ? notes.optString(index) : ""); body.addView(editor);
        button(body, context.getString(R.string.edgego_save), () -> {
            JSONArray updated = new JSONArray();
            for (int i = 0; i < notes.length(); i++) updated.put(i == index ? editor.getText().toString() : notes.optString(i));
            if (index < 0 && !editor.getText().toString().isBlank()) updated.put(editor.getText().toString());
            if (actions.privateStore().saveNotes(updated)) showSidePage(body, 1); else actions.unavailable();
        });
        if (index >= 0) button(body, context.getString(R.string.edgego_delete), () -> {
            JSONArray updated = new JSONArray();
            for (int i = 0; i < notes.length(); i++) if (i != index) updated.put(notes.optString(i));
            actions.privateStore().saveNotes(updated); showSidePage(body, 1);
        });
        editing = true;
    }

    private void notification(LinearLayout body) {
        List<NotificationEntry> all = notifications.entries(host.userId());
        if (!config.bool("notification_carousel_show_ongoing")) all.removeIf(entry -> entry.getSbn().isOngoing());
        if (config.bool("notification_carousel_show_app_filter")) {
            Button filter = EdgeGoUi.button(context, config, icons.container(config), icons.text(config), 24 * dp);
            notificationFilterButton = filter;
            filter.setMinHeight((int) Math.max(48 * dp, 48 * context.getResources().getDisplayMetrics().density));
            filter.setAllCaps(false);
            filter.setText(notificationFilter.isEmpty() ? context.getString(R.string.edgego_filter_all) : appLabel(notificationFilter));
            filter.setOnClickListener(view -> {
                PopupMenu menu = new PopupMenu(context, filter);
                menu.getMenu().add(context.getString(R.string.edgego_filter_all)).setOnMenuItemClickListener(item -> {
                    notificationFilter = ""; notificationIndex = 0; notificationKey = null; refreshNotifications(); return true;
                });
                java.util.LinkedHashSet<String> packages = new java.util.LinkedHashSet<>();
                for (NotificationEntry entry : all) packages.add(entry.getSbn().getPackageName());
                for (String pkg : packages) menu.getMenu().add(appLabel(pkg)).setOnMenuItemClickListener(item -> {
                    notificationFilter = pkg; notificationIndex = 0; notificationKey = null; refreshNotifications(); return true;
                });
                menu.show(); touched();
            });
            body.addView(filter);
        }
        List<NotificationEntry> entries = new ArrayList<>(all);
        if (!notificationFilter.isEmpty()) entries.removeIf(entry -> !entry.getSbn().getPackageName().equals(notificationFilter));
        if (notificationKey != null) for (int i = 0; i < entries.size(); i++) {
            if (notificationKey.equals(entries.get(i).getKey())) { notificationIndex = i; break; }
        }
        notificationIndex = Math.max(0, Math.min(notificationIndex, entries.size() - 1));
        if (entries.isEmpty()) { body.addView(text(context.getString(R.string.edgego_no_notifications), 14)); return; }
        NotificationEntry entry = entries.get(notificationIndex);
        EdgeGoNotifications notificationActions = notifications;
        notificationKey = entry.getKey();
        int expectedUser = host.userId();
        LinearLayout card = EdgeGoUi.cell(context, config, icons.container(config), icons.text(config), 24 * dp);
        card.setPadding((int) (16 * dp), (int) (16 * dp), (int) (16 * dp), (int) (12 * dp));
        LinearLayout.LayoutParams cardSize = new LinearLayout.LayoutParams(-1, -2);
        cardSize.setMargins(0, (int) (8 * dp), 0, (int) (8 * dp));
        body.addView(card, cardSize);
        LinearLayout header = row();
        ImageView appIcon = new ImageView(context);
        boolean redacted = notifications.redacted(entry);
        String iconAction = redacted ? "notifications" : "launch_app:" + entry.getSbn().getPackageName();
        appIcon.setImageDrawable(icons.get(iconAction, config, drawable -> {
            if (root != null && entry.getKey().equals(notificationKey)) appIcon.setImageDrawable(drawable);
        }));
        appIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(appIcon, new LinearLayout.LayoutParams((int) (36 * dp), (int) (36 * dp)));
        LinearLayout labels = column();
        TextView appName = text(redacted ? context.getString(R.string.edgego_notification)
                : appLabel(entry.getSbn().getPackageName()), 12);
        appName.setMaxLines(1); appName.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(appName);
        labels.addView(text(context.getString(R.string.edgego_notification_position, notificationIndex + 1, entries.size()), 12));
        LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(0, -2, 1);
        labelSize.setMargins((int) (12 * dp), 0, 0, 0);
        header.addView(labels, labelSize);
        card.addView(header);
        TextView headline = text(notifications.title(entry), 18);
        headline.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        headline.setPadding(0, (int) (12 * dp), 0, (int) (6 * dp));
        card.addView(headline);
        TextView text = text(notifications.text(entry), 14); text.setMaxLines(6); text.setLineSpacing(3 * dp, 1);
        card.addView(text);
        LinearLayout row = row();
        Button previous = button(row, context.getString(R.string.edgego_previous), () -> navigateNotification(-1, entries.size()));
        button(row, context.getString(R.string.edgego_open), () -> { close(); notificationActions.open(entry.getKey(), expectedUser); });
        Button next = button(row, context.getString(R.string.edgego_next), () -> navigateNotification(1, entries.size()));
        previous.setEnabled(entries.size() > 1); next.setEnabled(entries.size() > 1);
        card.addView(row);
        LinearLayout controls = row();
        if (entry.getSbn().isClearable()) {
            button(controls, context.getString(R.string.edgego_dismiss), () -> { notifications.dismiss(entry.getKey(), host.userId()); refreshNotifications(); });
            if (!config.string("notification_carousel_snooze_minutes").equals("off")) {
                button(controls, context.getString(R.string.edgego_snooze), () -> { notifications.snooze(entry.getKey(), host.userId(), snoozeMinutes()); refreshNotifications(); });
            }
        }
        if (notifications.canReply(entry)) button(controls, context.getString(R.string.edgego_reply), () -> {
            if (editing) return;
            editing = true;
            previous.setEnabled(false); next.setEnabled(false);
            if (notificationFilterButton != null) notificationFilterButton.setEnabled(false);
            EditText reply = input(); reply.setSingleLine(true); reply.setHint(R.string.edgego_reply);
            reply.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4096)}); body.addView(reply);
            LinearLayout replyActions = row();
            button(replyActions, context.getString(R.string.edgego_send), () -> { if (notificationActions.reply(entry.getKey(), reply.getText().toString(), expectedUser)) close(); else actions.unavailable(); });
            button(replyActions, context.getString(R.string.edgego_cancel), () -> { editing = false; refreshNotifications(); });
            body.addView(replyActions);
            reply.requestFocus();
        });
        card.addView(controls);
        button(card, context.getString(R.string.edgego_expand), () -> {
            text.setMaxLines(text.getMaxLines() == 6 ? Integer.MAX_VALUE : 6); touched();
        });
        if (entries.stream().anyMatch(item -> item.getSbn().isClearable())) button(body, context.getString(R.string.edgego_clear_all), () -> {
            for (NotificationEntry item : entries) if (item.getSbn().isClearable()) notifications.dismiss(item.getKey(), host.userId());
            notificationKey = null; refreshNotifications();
        });
        if (entry.getSbn().getNotification().isMediaNotification() && !notifications.redacted(entry)) {
            LinearLayout media = row();
            MediaController controller = notificationActions.mediaController(entry.getKey(), expectedUser);
            PlaybackState state = controller == null ? null : controller.getPlaybackState();
            boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            long supported = state == null ? 0 : state.getActions();
            ImageButton back = iconButton(media, R.drawable.ic_skip_previous_filled, R.string.edgego_previous, false,
                    () -> notificationActions.mediaKey(entry.getKey(), expectedUser, KeyEvent.KEYCODE_MEDIA_PREVIOUS));
            ImageButton play = iconButton(media, playing ? R.drawable.ic_media_pause : R.drawable.ic_media_play,
                    playing ? R.string.edgego_pause : R.string.edgego_play, true,
                    () -> notificationActions.mediaKey(entry.getKey(), expectedUser, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
            ImageButton forward = iconButton(media, R.drawable.ic_skip_next_filled, R.string.edgego_next, false,
                    () -> notificationActions.mediaKey(entry.getKey(), expectedUser, KeyEvent.KEYCODE_MEDIA_NEXT));
            back.setEnabled((supported & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0);
            play.setEnabled((supported & (PlaybackState.ACTION_PLAY_PAUSE | (playing ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY))) != 0);
            forward.setEnabled((supported & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);
            card.addView(media);
        }
        card.setOnClickListener(view -> { if (!editing) { close(); notificationActions.open(entry.getKey(), expectedUser); } });
        if (!entry.getKey().equals(displayedNotificationKey) && ValueAnimator.areAnimatorsEnabled()) {
            card.setAlpha(0); card.setTranslationX(notificationDirection * 24 * dp);
            card.animate().alpha(1).translationX(0).setDuration(220)
                    .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1)).start();
        }
        displayedNotificationKey = entry.getKey();
        final float[] down = new float[2];
        final boolean[] swiping = new boolean[1];
        card.setOnTouchListener((view, event) -> {
            if (editing) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                view.animate().cancel();
                view.setAlpha(1); view.setTranslationX(0);
                view.setPressed(true);
                swiping[0] = true;
                down[0] = event.getRawX(); down[1] = event.getRawY();
            }
            if (event.getPointerCount() != 1 || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                swiping[0] = false;
                view.setPressed(false); view.setAlpha(1); view.setTranslationX(0);
                if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            if (!swiping[0]) return true;
            float distance = event.getRawX() - down[0];
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1
                    && Math.abs(distance) > 8 * dp && Math.abs(distance) > Math.abs(event.getRawY() - down[1])) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
                view.setTranslationX(distance * .45f); view.setAlpha(Math.max(.5f, 1 - Math.abs(distance) / Math.max(1, view.getWidth())));
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP && Math.abs(distance) > 64 * dp
                        && event.getPointerCount() == 1
                        && Math.abs(distance) > Math.abs(event.getRawY() - down[1]) * 1.3f) {
                    navigateNotification(distance > 0 ? -1 : 1, entries.size());
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP && event.getPointerCount() == 1
                        && Math.hypot(distance, event.getRawY() - down[1]) < 8 * dp) {
                    view.performClick();
                }
                view.setPressed(false);
                swiping[0] = false;
                if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                if (ValueAnimator.areAnimatorsEnabled()) view.animate().translationX(0).alpha(1).setDuration(140).start();
                else { view.setTranslationX(0); view.setAlpha(1); }
            }
            return true;
        });
    }

    private void navigateNotification(int direction, int count) {
        if (editing || count <= 1) return;
        notificationDirection = direction;
        notificationIndex = (notificationIndex + direction + count) % count;
        notificationKey = null;
        refreshNotifications();
    }

    private int snoozeMinutes() {
        String value = config.string("notification_carousel_snooze_minutes");
        return value.equals("off") ? 60 : Integer.parseInt(value);
    }

    private String appLabel(String pkg) {
        try { return host.userContext().getPackageManager().getApplicationLabel(host.userContext().getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return pkg; }
    }

    private void refreshNotifications() {
        if (!base.equals("notification_carousel") || root == null || editing || !(content instanceof LinearLayout)) return;
        LinearLayout body = (LinearLayout) content;
        while (body.getChildCount() > 1) body.removeViewAt(body.getChildCount() - 1);
        notification(body);
    }

    private void timer(LinearLayout body) {
        progressText = text("", 32);
        progressText.setGravity(Gravity.CENTER);
        progressText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        progressText.setFontFeatureSettings("tnum");
        progressText.setMinHeight((int) (88 * dp));
        GradientDrawable clock = new GradientDrawable();
        clock.setColor(icons.container(config)); clock.setCornerRadius(24 * dp);
        progressText.setBackground(clock);
        body.addView(progressText);
        LinearLayout times = row();
        for (int minutes : new int[]{1, 5, 10}) button(times, minutes + "m", () -> { actions.schedules().startTimer(minutes * 60000L); tick(); });
        body.addView(times);
        EditText minutes = input(); minutes.setSingleLine(true); minutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        minutes.setHint(R.string.edgego_timer_minutes); body.addView(minutes);
        button(body, context.getString(R.string.edgego_start), () -> {
            try { actions.schedules().startTimer(Math.max(1, Math.min(1440, Integer.parseInt(minutes.getText().toString()))) * 60000L); tick(); }
            catch (NumberFormatException ignored) { }
        });
        button(body, context.getString(R.string.edgego_pause_resume), () -> {
            if (actions.schedules().timerStatus().equals("running")) actions.schedules().pauseTimer(); else actions.schedules().resumeTimer();
            tick();
        });
        button(body, context.getString(R.string.edgego_stop), () -> { actions.schedules().cancelTimer(); tick(); });
    }

    private void tick() {
        handler.removeCallbacks(tick);
        if (root == null || !host.canRun()) return;
        if (!volumeLevels.isEmpty() || base.equals("media_controller") || base.equals("notch_media")
                || base.equals("media_scrub") || base.equals("quick_tools")) {
            MediaController current = actions.media();
            if (observedMedia != current) {
                if (observedMedia != null) observedMedia.unregisterCallback(mediaCallback);
                observedMedia = current;
                if (current != null) current.registerCallback(mediaCallback, handler);
            }
        }
        if (base.equals("notch_timer")) {
            long seconds = actions.schedules().remaining() / 1000;
            if (progressText != null) progressText.setText(String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60));
            if (actions.schedules().timerStatus().equals("running")) handler.postDelayed(tick, 1000);
        } else if (base.startsWith("macro_")) {
            boolean paused = host.macros().paused();
            if (macroPause != null) {
                macroPause.setText(paused ? R.string.edgego_resume : R.string.edgego_pause);
                EdgeGoUi.colors(macroPause, paused ? icons.accent(config) : icons.container(config),
                        paused ? icons.onAccent(config) : icons.text(config));
            }
            updateMacroFeedback(paused);
            if (progressText != null) progressText.setText(base.equals("macro_recording")
                    ? context.getString(R.string.edgego_recording_status, host.macros().count(),
                            context.getString(paused ? R.string.edgego_paused : R.string.edgego_recording))
                    : context.getString(R.string.edgego_macro_progress, host.macros().step(), host.macros().count(),
                            context.getString(paused ? R.string.edgego_paused : R.string.edgego_playing)));
            if (!host.macros().paused()) handler.postDelayed(tick, 500);
        } else if (base.equals("page_scroll")) {
            if (progressText != null) progressText.setText(config.string("page_scroll_variant").equals("hold_drag")
                    ? context.getString(R.string.edgego_scroll_drag_hint) : context.getString(R.string.edgego_scroll_speed, actions.scrollSpeed()));
        } else if (base.equals("media_controller") || base.equals("notch_media") || base.equals("media_scrub") || base.equals("quick_tools")) {
            MediaController current = actions.media();
            if (mediaView != null) mediaView.update(current, actions.canShowMediaMetadata());
            PlaybackState state = current == null ? null : current.getPlaybackState();
            if (mediaView != null && state != null && state.getState() == PlaybackState.STATE_PLAYING) handler.postDelayed(tick, 250);
        }
    }

    private void updateMacroFeedback(boolean paused) {
        if (macroIndicator == null) return;
        if (paused || !ValueAnimator.areAnimatorsEnabled()) {
            if (macroPulse != null) macroPulse.cancel();
            macroIndicator.setAlpha(paused ? 128 : 255);
            return;
        }
        if (macroPulse != null && macroPulse.isRunning()) return;
        macroPulse = ValueAnimator.ofFloat(.45f, 1);
        macroPulse.setDuration(1000);
        macroPulse.setRepeatCount(ValueAnimator.INFINITE);
        macroPulse.setRepeatMode(ValueAnimator.REVERSE);
        GradientDrawable indicator = macroIndicator;
        macroPulse.addUpdateListener(animation -> indicator.setAlpha(Math.round((float) animation.getAnimatedValue() * 255)));
        macroPulse.start();
    }

    private List<EdgeGoPanelItem> presets() {
        JSONObject store = EdgeGoConfig.presetStore(context.getContentResolver(), host.userId());
        JSONArray presets = store.optJSONArray("presets");
        List<EdgeGoPanelItem> items = new ArrayList<>();
        if (presets != null) for (int i = 0; i < Math.min(presets.length(), 24); i++) {
            JSONObject preset = presets.optJSONObject(i);
            if (preset != null) items.add(new EdgeGoPanelItem(preset.optString("name"), "apply_preset:" + preset.optString("id")));
        }
        return items;
    }

    private List<EdgeGoPanelItem> items(String key) { return EdgeGoPanelItem.decode(config.array(key), actions); }
    private List<EdgeGoPanelItem> defaults() {
        List<EdgeGoPanelItem> items = new ArrayList<>();
        for (String action : new String[]{"back", "home", "recents", "notifications", "quick_settings", "flashlight"}) items.add(new EdgeGoPanelItem(actions.label(action), action));
        return items;
    }

    private RecyclerView list(LinearLayout body, List<EdgeGoPanelItem> items, int height) {
        if (items.isEmpty() && !base.equals("app_launcher")) body.addView(text(context.getString(R.string.edgego_no_items), 14));
        boolean wrapContent = height == ViewGroup.LayoutParams.WRAP_CONTENT;
        RecyclerView recycler = new RecyclerView(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (wrapContent && View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.UNSPECIFIED) {
                    heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        int columns = base.equals("app_launcher") ? config.integer("app_launcher_columns") : 3;
        GridLayoutManager grid = new GridLayoutManager(context, columns);
        recycler.setLayoutManager(grid);
        int minimumWidth = (int) Math.ceil(48 * context.getResources().getDisplayMetrics().density + 6 * dp);
        recycler.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int available = right - left - view.getPaddingLeft() - view.getPaddingRight();
            int count = Math.max(1, Math.min(columns, available / minimumWidth));
            if (grid.getSpanCount() != count) grid.setSpanCount(count);
        });
        recycler.setAdapter(new ItemAdapter(items));
        recycler.setNestedScrollingEnabled(!wrapContent);
        body.addView(recycler, new LinearLayout.LayoutParams(-1,
                wrapContent ? ViewGroup.LayoutParams.WRAP_CONTENT : (int) (height * dp)));
        return recycler;
    }

    private final class ItemAdapter extends RecyclerView.Adapter<ItemHolder> {
        private List<EdgeGoPanelItem> items;
        ItemAdapter(List<EdgeGoPanelItem> items) { this.items = new ArrayList<>(items); }
        void replace(List<EdgeGoPanelItem> items) {
            this.items = new ArrayList<>(items); notifyDataSetChanged();
            if (launcherEmpty != null) launcherEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        }
        @Override public ItemHolder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout cell = EdgeGoUi.cell(context, config, icons.container(config), icons.text(config), 20 * dp);
            cell.setGravity(Gravity.CENTER); cell.setPadding((int) (6 * dp), (int) (10 * dp), (int) (6 * dp), (int) (10 * dp));
            cell.setMinimumHeight((int) (72 * dp));
            RecyclerView.LayoutParams size = new RecyclerView.LayoutParams(-1, -2);
            size.setMargins((int) (3 * dp), (int) (3 * dp), (int) (3 * dp), (int) (3 * dp));
            cell.setLayoutParams(size);
            ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(icon, new LinearLayout.LayoutParams(-1, (int) (40 * dp)));
            TextView label = text("", 12); label.setMaxLines(2); label.setEllipsize(TextUtils.TruncateAt.END); label.setGravity(Gravity.CENTER);
            label.setPadding(0, (int) (6 * dp), 0, 0);
            cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
            return new ItemHolder(cell, icon, label);
        }
        @Override public void onBindViewHolder(ItemHolder holder, int position) {
            EdgeGoPanelItem item = items.get(position);
            holder.label.setText(item.label); holder.itemView.setContentDescription(item.label);
            holder.label.setVisibility(base.equals("app_launcher") && config.bool("app_launcher_hide_labels") ? View.GONE : View.VISIBLE);
            holder.icon.setTag(item);
            holder.icon.setImageDrawable(icons.get(item.action, config, drawable -> { if (holder.icon.getTag() == item) holder.icon.setImageDrawable(drawable); }));
            boolean appIcon = item.action.startsWith("launch_app") || item.action.startsWith("shortcut:") || item.action.startsWith("intent:");
            boolean active = base.equals("quick_tools") && actions.active(item.action);
            int surface = active ? config.bool("material_you_sync_quick_tools") ? icons.accent(config)
                    : config.integer("quick_tools_selected_button_color_rgb") | 0xff000000 : icons.container(config);
            int foreground = active ? EdgeGoUi.contrastColor(surface) : icons.text(config);
            holder.label.setTextColor(foreground);
            EdgeGoUi.colors(holder.itemView, surface, foreground);
            holder.itemView.setSelected(active);
            if (!appIcon && active) holder.icon.setImageTintList(android.content.res.ColorStateList.valueOf(foreground));
            else if ((base.equals("quick_tools") || base.equals("notch_apps")) && !appIcon
                    && !(base.equals("quick_tools") ? config.bool("material_you_sync_quick_tools") : config.bool("material_you_accent_enabled"))) {
                holder.icon.setImageTintList(android.content.res.ColorStateList.valueOf(config.integer(base.equals("quick_tools")
                        ? "quick_tools_icon_color_rgb" : "notch_apps_action_color_rgb") | 0xff000000));
            } else holder.icon.setImageTintList(appIcon ? null : android.content.res.ColorStateList.valueOf(foreground));
            holder.itemView.setOnClickListener(view -> {
                if (root == null || !host.canRun()) return;
                if (!item.children.isEmpty()) replace(item.children);
                else if (!item.module.isEmpty()) openModule(item, this::replace);
                else actions.execute(item.action, zone, openingX, openingY, false);
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                if (root != null && base.equals("quick_tools")) {
                    int token = generation;
                    notifyDataSetChanged(); handler.postDelayed(() -> { if (root != null && token == generation) notifyDataSetChanged(); }, 300);
                }
                touched();
            });
            holder.itemView.setOnLongClickListener(view -> {
                if (base.equals("app_launcher") && root != null && host.canRun()) { pin(item); return true; }
                if (item.holdAction.equals("none") || root == null || !host.canRun()) return false;
                actions.execute(item.holdAction, zone, openingX, openingY, false); return true;
            });
        }
        @Override public int getItemCount() { return items.size(); }
    }

    private static final class ItemHolder extends RecyclerView.ViewHolder {
        final ImageView icon; final TextView label;
        ItemHolder(View view, ImageView icon, TextView label) { super(view); this.icon = icon; this.label = label; }
    }

    private void pin(EdgeGoPanelItem item) {
        EdgeGoConfig.edit(context.getContentResolver(), host.userId(), values -> {
            JSONArray pins = host.config().array("app_launcher_pinned_items"), updated = new JSONArray();
            boolean found = false;
            for (int i = 0; i < pins.length(); i++) {
                JSONObject pinned = pins.optJSONObject(i);
                if (pinned != null && EdgeGoPanelItem.decode(new JSONArray().put(pinned), actions).stream()
                        .anyMatch(value -> value.action.equals(item.action))) found = true;
                else updated.put(pins.opt(i));
            }
            if (!found && updated.length() < 24) {
                JSONObject pinned = new JSONObject(); EdgeGoConfig.put(pinned, "type", "action"); EdgeGoConfig.put(pinned, "label", item.label);
                EdgeGoConfig.put(pinned, "actionId", item.action); updated.put(pinned);
            }
            EdgeGoConfig.put(values, "app_launcher_pinned_items", updated);
        });
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }

    private EditText input() {
        EditText view = new EditText(context);
        GradientDrawable background = new GradientDrawable();
        background.setColor(icons.container(config));
        background.setCornerRadius(16 * dp);
        background.setStroke((int) Math.max(1, dp), ColorUtils.blendARGB(icons.text(config), icons.container(config), .7f));
        view.setBackground(background);
        view.setBackgroundTintList(null);
        view.setTextColor(icons.text(config));
        view.setHintTextColor(ColorUtils.blendARGB(icons.text(config), icons.container(config), .35f));
        view.setHighlightColor((icons.accent(config) & 0x00ffffff) | 0x50000000);
        view.setMinHeight((int) Math.max(56 * dp, 48 * context.getResources().getDisplayMetrics().density));
        view.setPadding((int) (16 * dp), (int) (12 * dp), (int) (16 * dp), (int) (12 * dp));
        view.setOnFocusChangeListener((v, focused) -> background.setStroke((int) Math.max(1, (focused ? 2 : 1) * dp),
                focused ? icons.accent(config) : ColorUtils.blendARGB(icons.text(config), icons.container(config), .7f)));
        return view;
    }
    private TextView text(String text, int size) {
        TextView view = new TextView(context); view.setText(text); view.setTextSize(size);
        String prefix = base.equals("media_scrub") ? "media_scrub_overlay" : base.equals("page_scroll") ? "page_scroll_overlay"
                : base.equals("volume_slider") || base.equals("brightness_slider") ? "continuous_gesture_overlay" : "";
        view.setTextColor(prefix.isEmpty() || config.bool(prefix + "_use_accent_color")
                ? icons.text(config) : config.integer(prefix + "_foreground_color_rgb") | 0xff000000);
        return view;
    }
    private Button button(LinearLayout parent, String text, Runnable click) {
        Button button = EdgeGoUi.button(context, config, icons.container(config), icons.text(config), 24 * dp);
        button.setText(text); button.setMinHeight((int) Math.max(48 * dp, 48 * context.getResources().getDisplayMetrics().density));
        button.setPadding((int) (8 * dp), (int) (6 * dp), (int) (8 * dp), (int) (6 * dp));
        int token = generation;
        button.setTextColor(icons.text(config)); button.setOnClickListener(view -> {
            if (root != null && token == generation && host.canRun()) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                click.run();
            }
            touched();
        });
        boolean horizontal = parent.getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams size = horizontal ? new LinearLayout.LayoutParams(0, -2, 1)
                : new LinearLayout.LayoutParams(-1, -2);
        size.setMargins(horizontal ? (int) (3 * dp) : 0, (int) (4 * dp), horizontal ? (int) (3 * dp) : 0, (int) (4 * dp));
        parent.addView(button, size);
        return button;
    }

    private ImageButton iconButton(LinearLayout parent, int icon, int description, boolean primary, Runnable click) {
        ImageButton button = EdgeGoUi.iconButton(context, config, primary ? icons.accent(config) : icons.container(config),
                primary ? icons.onAccent(config) : icons.text(config), (primary ? 20 : 24) * dp);
        button.setImageResource(icon);
        button.setContentDescription(context.getString(description));
        button.setPadding((int) (14 * dp), (int) (14 * dp), (int) (14 * dp), (int) (14 * dp));
        int token = generation;
        button.setOnClickListener(view -> {
            if (root != null && token == generation && host.canRun()) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                click.run();
            }
            touched();
        });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0,
                (int) Math.max(56 * dp, 48 * context.getResources().getDisplayMetrics().density), primary ? 1.4f : 1);
        size.setMargins((int) (4 * dp), (int) (6 * dp), (int) (4 * dp), (int) (6 * dp));
        parent.addView(button, size);
        return button;
    }

    private GradientDrawable surface() {
        GradientDrawable drawable = new GradientDrawable();
        String key = base.equals("quick_tools") ? "quick_tools_panel_color_rgb" : base.equals("notch_apps")
                ? "notch_apps_panel_color_rgb" : base.equals("media_scrub") ? "media_scrub_overlay_background_color_rgb"
                : base.equals("page_scroll") ? "page_scroll_overlay_background_color_rgb" : "continuous_gesture_overlay_background_color_rgb";
        int color = icons.surface(config, key);
        String opacity = base.equals("quick_tools") ? "quick_tools_panel_opacity" : base.equals("app_launcher")
                ? "app_launcher_opacity" : base.equals("side_panel") ? "side_panel_opacity"
                : base.equals("notification_carousel") ? "notification_carousel_opacity" : base.equals("notch_timer")
                ? "notch_timer_opacity" : base.equals("notch_apps") ? "notch_apps_opacity" : "media_controller_opacity";
        drawable.setColor((color & 0x00ffffff) | Math.round(config.integer(opacity) * 2.55f) << 24);
        drawable.setCornerRadius(config.integer("floating_panel_corner_radius") * dp);
        return drawable;
    }

    void showTextPicker(ArrayList<Bundle> texts) {
        show("drawing_pad", null, 0, 0, false);
        if (root == null) return;
        layout.flags |= WindowManager.LayoutParams.FLAG_SECURE;
        updateLayout();
        root.removeAllViews();
        content = new EdgeGoTextPickerView(context, texts, config, icons, text -> { close(); if (!text.isBlank()) textTools(text); });
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        updateTouchRegion();
    }

    private void textTools(String text) {
        show("text_tools", null, openingX, openingY, false);
        if (!(content instanceof LinearLayout)) return;
        LinearLayout body = (LinearLayout) content;
        TextView preview = text(text, 14); preview.setMaxLines(6); body.addView(preview);
        button(body, context.getString(R.string.edgego_copy), () -> { actions.copy(text); close(); });
        button(body, context.getString(R.string.edgego_search), () -> actions.startActivity(new Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", text)));
        button(body, context.getString(R.string.edgego_translate), () -> actions.startActivity(new Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)));
        button(body, context.getString(R.string.edgego_share), () -> actions.startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)));
    }

    void track(float x, float y) {
        if (content instanceof EdgeGoPieView) ((EdgeGoPieView) content).track(x, y);
        else if (content instanceof EdgeGoCursorView) ((EdgeGoCursorView) content).track(x, y);
        touched();
    }
    void endTracking(boolean canceled) {
        if (content instanceof EdgeGoPieView) ((EdgeGoPieView) content).endTracking(canceled);
        else if (content instanceof EdgeGoCursorView) ((EdgeGoCursorView) content).endOpening(canceled);
    }

    void prepareInjection(boolean active) {
        if (layout == null || spyPanel()) return;
        if (active) layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        updateLayout();
    }

    private void touched() {
        handler.removeCallbacks(idle);
        if (root == null || editing || base.startsWith("macro_") || base.equals("screen_blackout")) return;
        int timeout = base.equals("drawing_pad") ? config.integer("drawing_pad_timeout")
                : base.equals("remote_cursor") ? config.bool("remote_cursor_outside_only_dismiss") ? 0
                        : config.integer("remote_cursor_inactivity_timeout") : config.integer("floating_panel_timeout");
        if (timeout > 0) handler.postDelayed(idle, timeout * 1000L);
    }

    void outsideTouch(float x, float y) {
        if (root == null || ownsTouch((int) x, (int) y)) return;
        boolean pass = passesOutsideTaps();
        if (base.equals("remote_cursor")) { if (config.bool("remote_cursor_outside_dismiss")) dismiss(); }
        else if (!pass && !base.startsWith("macro_")) dismiss();
    }

    void resetPositions() {
        JSONObject state = EdgeGoSchedules.state(context, host.userId()); state.remove("positions");
        state.remove("cursorPads");
        EdgeGoSchedules.save(context, host.userId(), state);
    }

    void close() {
        close(false);
    }

    void dismiss() { close(true); }

    private void close(boolean animate) {
        animate &= host.canRun() && !host.locked() && ValueAnimator.areAnimatorsEnabled();
        if (!animate) {
            List<FrameLayout> snapshots = new ArrayList<>(closingViews); closingViews.clear();
            for (FrameLayout snapshot : snapshots) { snapshot.animate().cancel(); remove(snapshot); }
        }
        if (content instanceof EdgeGoPieView) ((EdgeGoPieView) content).cancelPending();
        if (content instanceof EdgeGoCursorView) ((EdgeGoCursorView) content).cancelPending();
        if (content instanceof EdgeGoDrawingView) ((EdgeGoDrawingView) content).cancelPending();
        icons.cancelPending();
        if (openingSpring != null) { openingSpring.cancel(); openingSpring = null; }
        if (macroPulse != null) { macroPulse.cancel(); macroPulse = null; }
        generation++;
        if (base.equals("page_scroll")) actions.stopScrolling();
        visible = consumeOutside = false;
        if (volumeListening) {
            volumeListening = false;
            try { context.unregisterReceiver(levelsChanged); } catch (IllegalArgumentException ignored) { }
        }
        if (brightnessListening) {
            brightnessListening = false;
            displays.unregisterDisplayListener(displayChanged);
            context.getContentResolver().unregisterContentObserver(brightnessChanged);
        }
        volumeLevels.clear(); brightnessLevels.clear();
        sideTabs.clear();
        handler.removeCallbacks(idle); handler.removeCallbacks(tick); handler.removeCallbacks(layoutUpdate);
        if (observedMedia != null) { observedMedia.unregisterCallback(mediaCallback); observedMedia = null; }
        if (notifications != null) {
            notifications.pipeline().removeCollectionListener(notificationListener);
            notifications.removePrivacyListener(privacyChanged); notifications = null;
        }
        if (dispatcher != null && back != null) dispatcher.unregisterOnBackInvokedCallback(back);
        dispatcher = null; back = null;
        FrameLayout previous = root;
        root = null; content = null; progressText = null; mediaView = null; macroPause = null; macroIndicator = null; launcherEmpty = null;
        notificationFilterButton = null; displayedNotificationKey = null;
        touchRegion = new Region(); action = base = ""; editing = false;
        if (previous != null) {
            previous.animate().cancel();
            if (animate) {
                layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                try { windows.updateViewLayout(previous, layout); } catch (IllegalArgumentException ignored) { }
                closingViews.add(previous);
                previous.animate().alpha(0).scaleX(.98f).scaleY(.98f).setDuration(120)
                        .setInterpolator(new android.view.animation.PathInterpolator(.3f, 0, .8f, 1))
                        .withEndAction(() -> { if (closingViews.remove(previous)) remove(previous); }).start();
            } else remove(previous);
        }
        if (previous != null) host.refreshForPanelState();
    }

    private void remove(View view) {
        try { windows.removeViewImmediate(view); } catch (IllegalArgumentException ignored) { }
    }

    private void addPieModules(List<EdgeGoPanelItem> items) {
        String[] settings = {"quick_access_slider_volume", "quick_access_slider_brightness", "quick_access_drawer_enabled", "quick_access_recents_enabled"};
        String[] modules = {"volume", "brightness", "app_drawer", "recent_apps"};
        for (int i = 0; i < modules.length; i++) if (config.bool(settings[i])) {
            String module = modules[i];
            if (items.stream().noneMatch(item -> item.module.equals(module))) items.add(new EdgeGoPanelItem(
                    context.getString(module.equals("volume") ? R.string.edgego_volume : module.equals("brightness")
                            ? R.string.edgego_brightness : module.equals("app_drawer") ? R.string.edgego_app_drawer : R.string.edgego_recent_apps),
                    module.equals("volume") ? "volume_slider" : module.equals("brightness") ? "brightness_slider" : "app_launcher",
                    "none", module, new ArrayList<>()));
        }
    }

    private boolean passesOutsideTaps() {
        String key = base.equals("touch_pad") ? "touch_pad" : base.equals("show_quick_access") ? "quick_access" : base;
        return config.bool("floating_panel_pass_through_outside_taps") || config.bool(key + "_pass_through_outside_taps");
    }

    boolean consumesOutsideTouch(int x, int y) {
        return visible && consumeOutside && !ownsTouch(x, y);
    }

    private boolean keepsOpen() {
        return base.equals("app_launcher") && config.bool("app_launcher_keep_open_on_action")
                || base.startsWith("macro_")
                || (base.equals("show_quick_access") || base.equals("notch_apps")) && config.bool("quick_access_keep_open_on_action");
    }

    private boolean spyPanel() {
        return base.startsWith("macro_") || base.equals("remote_cursor") || base.equals("touch_pad");
    }

    boolean prepareAppAction() {
        if (root == null) return false;
        if (keepsOpen()) { layout.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; updateLayout(); }
        else close();
        return true;
    }

    void onForegroundChanged() { if (!keepsOpen()) close(); }
    void onRuntimeChanged() {
        if (root == null) return;
        if (content instanceof EdgeGoCursorView) ((EdgeGoCursorView) content).updateBounds();
        updateTouchRegion();
    }
    void onMediaChanged() { if (root != null) { refreshLevels(); tick(); } }
    private void refreshLevels() {
        if (root == null || !host.canRun()) return;
        for (EdgeGoLevelView view : volumeLevels) view.setLevel(actions.volume(actions.volumeStream()));
        for (EdgeGoLevelView view : brightnessLevels) view.setLevel(actions.brightness());
    }
    private void onPrivacyChanged() { handler.post(this::close); }
}
