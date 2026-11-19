/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.edgego;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.LruCache;
import android.util.TypedValue;
import android.util.Xml;

import androidx.core.graphics.ColorUtils;

import com.android.internal.util.lunaris.EdgeGoConfig;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

final class EdgeGoIcons {
    private final Context context;
    private final Handler handler;
    private final Executor background;
    private final boolean feedback;
    private volatile Context userContext;
    private String themeOverride = "";
    private final LruCache<String, Drawable.ConstantState> icons = new LruCache<>(64);
    private final Map<String, List<Consumer<Drawable>>> loading = new HashMap<>();
    private volatile String pack = "";
    private Map<String, String> packIcons = new HashMap<>();
    private String parsedPack = "";
    private volatile int generation;

    EdgeGoIcons(Context context, Handler handler, Executor background) {
        this(context, handler, background, false);
    }

    EdgeGoIcons(Context context, Handler handler, Executor background, boolean feedback) {
        this.context = context;
        this.handler = handler;
        this.background = background;
        this.feedback = feedback;
        userContext = context;
    }

    void setThemeOverride(String theme) { themeOverride = theme; }
    void cancelPending() { generation++; loading.clear(); }
    void setUserContext(Context context) {
        if (userContext.getUserId() != context.getUserId()) {
            generation++;
            icons.evictAll();
            loading.clear();
            synchronized (this) { parsedPack = ""; packIcons = new HashMap<>(); }
        }
        userContext = context;
    }

    Drawable get(String action, EdgeGoConfig config, Consumer<Drawable> ready) {
        String selected = config.string("app_icon_pack_package");
        if (!selected.equals(pack)) { pack = selected; generation++; icons.evictAll(); loading.clear(); }
        if (!action.startsWith("launch_app") && !action.startsWith("shortcut:") && !action.startsWith("intent:")) return glyph(action, config);
        if (feedback && !config.bool("pull_animation_show_launch_app_icon")) return glyph("app_launcher", config);
        Drawable.ConstantState cached = icons.get(action);
        if (cached != null) return cached.newDrawable(context.getResources()).mutate();
        List<Consumer<Drawable>> waiting = loading.get(action);
        boolean first = waiting == null;
        if (first) { waiting = new ArrayList<>(); loading.put(action, waiting); }
        if (ready != null) waiting.add(ready);
        if (first) {
            int token = generation;
            Context source = userContext;
            background.execute(() -> {
                if (token != generation) return;
                Drawable loaded = load(action, selected, source, token);
                handler.post(() -> {
                    if (token != generation) return;
                    List<Consumer<Drawable>> subscribers = loading.remove(action);
                    if (loaded != null && loaded.getConstantState() != null) icons.put(action, loaded.getConstantState());
                    if (subscribers != null) for (Consumer<Drawable> subscriber : subscribers) {
                        Drawable result = loaded == null ? glyph("app_launcher", config)
                                : loaded.getConstantState() == null ? loaded
                                : loaded.getConstantState().newDrawable(source.getResources()).mutate();
                        subscriber.accept(result);
                    }
                });
            });
        }
        return glyph("app_launcher", config);
    }

    private Drawable load(String action, String pack, Context source, int token) {
        try {
            String[] value = action.substring(action.indexOf(':') + 1).split("\\|", 2);
            String pkg = value[0];
            if (action.startsWith("intent:")) {
                Intent intent = Intent.parseUri(android.net.Uri.decode(value[0]), Intent.URI_INTENT_SCHEME);
                android.content.pm.ActivityInfo info = intent.resolveActivityInfo(source.getPackageManager(), 0);
                if (info == null) return null;
                pkg = info.packageName;
            }
            Intent launch = source.getPackageManager().getLaunchIntentForPackage(pkg);
            ComponentName component = value.length == 2 && action.startsWith("launch_app_activity:")
                    ? new ComponentName(pkg, value[1]) : launch == null ? null : launch.getComponent();
            if (!pack.isEmpty() && component != null) {
                Resources resources = source.getPackageManager().getResourcesForApplication(pack);
                Map<String, String> map = readPack(resources, pack, token);
                String drawable = map.get("ComponentInfo{" + component.flattenToString() + "}");
                if (drawable == null) drawable = map.get("ComponentInfo{" + component.flattenToShortString() + "}");
                int id = drawable == null ? 0 : resources.getIdentifier(drawable, "drawable", pack);
                if (id != 0) return resources.getDrawable(id, null).mutate();
            }
            if (action.startsWith("shortcut:") && value.length == 2) {
                android.content.pm.LauncherApps launcher = source.getSystemService(android.content.pm.LauncherApps.class);
                android.content.pm.LauncherApps.ShortcutQuery query = new android.content.pm.LauncherApps.ShortcutQuery()
                        .setPackage(pkg).setShortcutIds(List.of(value[1])).setQueryFlags(
                                android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                                        | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                                        | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
                List<android.content.pm.ShortcutInfo> shortcuts = launcher.getShortcuts(query, android.os.UserHandle.of(source.getUserId()));
                if (shortcuts != null && !shortcuts.isEmpty()) {
                    Drawable icon = launcher.getShortcutIconDrawable(shortcuts.get(0), source.getResources().getDisplayMetrics().densityDpi);
                    if (icon != null) return icon.mutate();
                }
            }
            return source.getPackageManager().getApplicationIcon(pkg).mutate();
        } catch (Exception ignored) { return null; }
    }

    private Map<String, String> readPack(Resources resources, String selected, int token) {
        synchronized (this) {
            if (token != generation) return java.util.Collections.emptyMap();
            if (selected.equals(parsedPack)) return packIcons;
        }
        Map<String, String> parsed = new HashMap<>();
        try {
            int xml = resources.getIdentifier("appfilter", "xml", selected);
            if (xml != 0) {
                try (android.content.res.XmlResourceParser parser = resources.getXml(xml)) { parsePack(parser, parsed); }
            } else {
                try (InputStream input = resources.getAssets().open("appfilter.xml")) {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(input, "UTF-8");
                    parsePack(parser, parsed);
                }
            }
        } catch (Exception ignored) { }
        synchronized (this) {
            if (token == generation && selected.equals(pack)) { packIcons = parsed; parsedPack = selected; }
        }
        return parsed;
    }

    private void parsePack(XmlPullParser parser, Map<String, String> icons) throws Exception {
        int visited = 0;
        for (int type = parser.getEventType(); type != XmlPullParser.END_DOCUMENT && visited++ < 60000; type = parser.next()) {
            if (type == XmlPullParser.START_TAG && parser.getName().equals("item")) {
                String component = parser.getAttributeValue(null, "component");
                String drawable = parser.getAttributeValue(null, "drawable");
                if (component != null && drawable != null && component.length() < 512 && drawable.length() < 255) {
                    icons.put(component, drawable);
                }
            }
        }
    }

    int accent(EdgeGoConfig config) {
        if (!config.bool("material_you_accent_enabled")) {
            return config.integer(dark(config) ? "ui_accent_dark_rgb" : "ui_accent_light_rgb") | 0xff000000;
        }
        return context.getColor(dark(config) ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
    }

    boolean dark(EdgeGoConfig config) {
        String mode = themeOverride.isEmpty() ? config.string("ui_theme_mode") : themeOverride;
        return mode.equals("dark") || mode.equals("system")
                && (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    int surface(EdgeGoConfig config, String key) {
        boolean tint = config.bool(key.equals("quick_access_arc_color_rgb")
                ? "material_you_sync_quick_access_background" : "material_you_surface_tint_enabled");
        if (!tint) return config.integer(key) | 0xff000000;
        int base = context.getColor(dark(config) ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_50);
        return ColorUtils.blendARGB(base, accent(config), .08f);
    }

    int text(EdgeGoConfig config) {
        return context.getColor(dark(config) ? android.R.color.system_neutral1_100 : android.R.color.system_neutral1_900);
    }

    int container(EdgeGoConfig config) {
        return ColorUtils.blendARGB(surface(config, "continuous_gesture_overlay_background_color_rgb"), accent(config), .16f);
    }

    int onAccent(EdgeGoConfig config) {
        return EdgeGoUi.contrastColor(accent(config));
    }

    private int themeColor(int attribute, int fallback) {
        TypedValue value = new TypedValue();
        return context.getTheme().resolveAttribute(attribute, value, true) ? value.data : fallback;
    }

    private Drawable glyph(String action, EdgeGoConfig config) {
        String base = action.split(":", 2)[0];
        if (base.startsWith("mute_")) base = "mute_volume";
        else if (base.contains("volume") && !base.equals("mute_volume")) base = "volume_slider";
        else if (base.contains("brightness") || base.equals("extra_dim") || base.equals("screen_blackout")) base = "brightness_slider";
        else if (base.equals("voice_command") || base.equals("song_search")) base = "voice_command";
        else if (base.equals("notification_carousel") || base.equals("dismiss_notification_shade")) base = "notifications";
        else if (base.equals("internet_panel")) base = "wifi";
        else if (base.equals("one_handed") || base.equals("touch_pad") || base.equals("remote_cursor") || base.equals("tap_through")) base = "pointer";
        else if (base.equals("notch_timer") || base.equals("cycle_screen_timeout")) base = "timer";
        else if (base.equals("copy") || base.equals("paste") || base.equals("select_all_text") || base.equals("text_picker") || base.equals("side_panel")) base = "text";
        else if (base.equals("notch_apps") || base.equals("preset_picker")) base = "app_launcher";
        else if (base.equals("current_app_info") || base.startsWith("open_")) base = "quick_settings";
        Map<String, String> names = Map.ofEntries(Map.entry("back", "ic_arrow_back"),
                Map.entry("home", "ic_home"), Map.entry("notifications", "ic_notifications"),
                Map.entry("quick_settings", "ic_settings"), Map.entry("flashlight", "ic_dynamic_island_flashlight"),
                Map.entry("media_next", "ic_media_next"), Map.entry("media_previous", "ic_media_prev"),
                Map.entry("volume_slider", "ic_qs_volume_panel"), Map.entry("brightness_slider", "ic_brightness"),
                Map.entry("mute_volume", "ic_ring_volume_off"), Map.entry("camera", "ic_camera"),
                Map.entry("bluetooth", "ic_bluetooth_connected"), Map.entry("screen_record", "ic_screenrecord"),
                Map.entry("location", "ic_location"), Map.entry("power_menu", "ic_qs_footer_power"),
                Map.entry("battery_saver", "ic_power_saver"));
        String name = names.get(base);
        int color = config.bool("pull_animation_sync_arrow_with_accent") ? accent(config)
                : config.integer("pull_animation_arrow_color_rgb") | 0xff000000;
        if (feedback && base.equals("back")) return new Glyph(base, color, true);
        if (name != null) {
            int id = context.getResources().getIdentifier(name, "drawable", "com.android.systemui");
            if (id != 0) {
                Drawable drawable = context.getDrawable(id).mutate();
                drawable.setTint(color);
                return drawable;
            }
        }
        return new Glyph(base, color);
    }

    static boolean isGlyph(Drawable drawable) { return drawable instanceof Glyph; }

    private static final class Glyph extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final String action;
        final int baseColor;
        final boolean outlined;
        ColorStateList tint;
        Glyph(String action, int color) { this(action, color, false); }
        Glyph(String action, int color, boolean outlined) {
            this.action = action; baseColor = color; this.outlined = outlined;
            paint.setColor(color); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            paint.setStyle(Paint.Style.STROKE);
            path.reset();
            switch (action) {
                case "back": path.moveTo(14, 5); path.lineTo(7, 12); path.lineTo(14, 19); break;
                case "home": path.moveTo(3, 11); path.lineTo(12, 4); path.lineTo(21, 11); path.moveTo(6, 9); path.lineTo(6, 20); path.lineTo(18, 20); path.lineTo(18, 9); break;
                case "recents": case "previous_app": canvas.drawRoundRect(4, 5, 18, 20, 2, 2, paint); canvas.drawLine(8, 2, 21, 2, paint); break;
                case "media_play_pause": case "media_controller": case "notch_media":
                    path.moveTo(7, 4); path.lineTo(19, 12); path.lineTo(7, 20); path.close(); break;
                case "media_next": path.moveTo(5, 5); path.lineTo(16, 12); path.lineTo(5, 19); path.close(); canvas.drawLine(19, 5, 19, 19, paint); break;
                case "media_previous": path.moveTo(19, 5); path.lineTo(8, 12); path.lineTo(19, 19); path.close(); canvas.drawLine(5, 5, 5, 19, paint); break;
                case "close": case "none": canvas.drawLine(6, 6, 18, 18, paint); canvas.drawLine(18, 6, 6, 18, paint); break;
                case "volume_slider": case "volume_up": case "volume_down":
                    path.moveTo(3, 9); path.lineTo(8, 9); path.lineTo(13, 4); path.lineTo(13, 20); path.lineTo(8, 15); path.lineTo(3, 15); path.close(); canvas.drawArc(10, 6, 22, 18, -70, 140, false, paint); break;
                case "brightness_slider": case "brightness_up": case "brightness_down":
                    canvas.drawCircle(12, 12, 4, paint);
                    for (int i = 0; i < 8; i++) { canvas.save(); canvas.rotate(i * 45, 12, 12); canvas.drawLine(12, 2, 12, 4, paint); canvas.restore(); }
                    break;
                case "quick_settings": case "quick_tools": case "app_launcher": case "show_quick_access":
                    for (int row = 0; row < 2; row++) for (int column = 0; column < 2; column++) canvas.drawRoundRect(4 + column * 10, 4 + row * 10, 10 + column * 10, 10 + row * 10, 2, 2, paint);
                    break;
                case "copy": canvas.drawRoundRect(8, 7, 20, 21, 2, 2, paint); path.moveTo(15, 3); path.lineTo(4, 3); path.lineTo(4, 16); break;
                case "lock_screen": canvas.drawRoundRect(5, 10, 19, 21, 2, 2, paint); canvas.drawArc(8, 2, 16, 16, 180, 180, false, paint); break;
                case "wifi": canvas.drawArc(2, 4, 22, 24, 225, 90, false, paint); canvas.drawArc(6, 9, 18, 21, 225, 90, false, paint); canvas.drawCircle(12, 18, 1, paint); break;
                case "bluetooth": path.moveTo(12, 3); path.lineTo(18, 8); path.lineTo(6, 16); path.moveTo(6, 8); path.lineTo(18, 16); path.lineTo(12, 21); path.lineTo(12, 3); break;
                case "mobile_data": for (int i = 0; i < 4; i++) canvas.drawLine(5 + i * 5, 20, 5 + i * 5, 17 - i * 4, paint); break;
                case "auto_rotate": canvas.drawRoundRect(8, 4, 16, 20, 2, 2, paint); canvas.drawArc(1, 1, 23, 23, 15, 90, false, paint); canvas.drawArc(1, 1, 23, 23, 195, 90, false, paint); break;
                case "do_not_disturb": canvas.drawCircle(12, 12, 9, paint); canvas.drawLine(6, 12, 18, 12, paint); break;
                case "screenshot": case "partial_screenshot": canvas.drawRoundRect(4, 5, 20, 19, 2, 2, paint); canvas.drawLine(1, 8, 1, 2, paint); canvas.drawLine(1, 2, 7, 2, paint); canvas.drawLine(23, 16, 23, 22, paint); canvas.drawLine(23, 22, 17, 22, paint); break;
                case "media_stop": canvas.drawRect(6, 6, 18, 18, paint); break;
                case "media_scrub": canvas.drawLine(3, 12, 21, 12, paint); canvas.drawCircle(9, 12, 3, paint); break;
                case "voice_command": canvas.drawRoundRect(9, 3, 15, 14, 3, 3, paint); canvas.drawArc(6, 7, 18, 19, 0, 180, false, paint); canvas.drawLine(12, 19, 12, 22, paint); break;
                case "pointer": path.moveTo(6, 3); path.lineTo(7, 21); path.lineTo(12, 16); path.lineTo(19, 15); path.close(); break;
                case "timer": canvas.drawCircle(12, 13, 8, paint); canvas.drawLine(9, 2, 15, 2, paint); canvas.drawLine(12, 13, 12, 8, paint); canvas.drawLine(12, 13, 16, 13, paint); break;
                case "text": canvas.drawRoundRect(5, 3, 19, 21, 2, 2, paint); canvas.drawLine(8, 8, 16, 8, paint); canvas.drawLine(8, 12, 16, 12, paint); canvas.drawLine(8, 16, 14, 16, paint); break;
                case "page_scroll": case "scroll_to_top": case "scroll_to_bottom": canvas.drawLine(12, 3, 12, 21, paint); path.moveTo(7, 8); path.lineTo(12, 3); path.lineTo(17, 8); path.moveTo(7, 16); path.lineTo(12, 21); path.lineTo(17, 16); break;
                case "drawing_pad": path.moveTo(3, 18); path.cubicTo(7, 2, 11, 22, 15, 7); path.cubicTo(17, 2, 20, 9, 21, 4); break;
                case "split_screen": canvas.drawRect(3, 5, 21, 19, paint); canvas.drawLine(12, 5, 12, 19, paint); break;
                case "pause_edgego": canvas.drawLine(8, 4, 8, 20, paint); canvas.drawLine(16, 4, 16, 20, paint); break;
                case "notifications": path.moveTo(4, 18); path.lineTo(6, 15); path.lineTo(6, 9); path.cubicTo(6, 1, 18, 1, 18, 9); path.lineTo(18, 15); path.lineTo(20, 18); path.close(); canvas.drawLine(10, 21, 14, 21, paint); break;
                default: canvas.drawCircle(12, 12, 9, paint); canvas.drawLine(8, 12, 16, 12, paint); canvas.drawLine(12, 8, 12, 16, paint);
            }
            if (outlined) {
                int color = paint.getColor(), alpha = paint.getAlpha();
                paint.setColor(EdgeGoUi.contrastColor(color));
                paint.setAlpha(alpha);
                paint.setStrokeWidth(4);
                canvas.drawPath(path, paint);
                paint.setColor(color);
                paint.setAlpha(alpha);
                paint.setStrokeWidth(1.8f);
            }
            canvas.drawPath(path, paint);
            canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public int getIntrinsicWidth() { return 24; }
        @Override public int getIntrinsicHeight() { return 24; }
        @Override public void setTint(int color) { setTintList(ColorStateList.valueOf(color)); }
        @Override public void setTintList(ColorStateList colors) {
            tint = colors;
            paint.setColor(colors == null ? baseColor : colors.getColorForState(getState(), colors.getDefaultColor()));
            invalidateSelf();
        }
        @Override protected boolean onStateChange(int[] state) {
            if (tint == null) return false;
            int color = tint.getColorForState(state, tint.getDefaultColor());
            if (color == paint.getColor()) return false;
            paint.setColor(color); invalidateSelf(); return true;
        }
        @Override public boolean isStateful() { return tint != null && tint.isStateful() || super.isStateful(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
