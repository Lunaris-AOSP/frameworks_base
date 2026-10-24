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

package com.android.internal.util.lunaris;

import android.content.ContentResolver;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LunarisBarSettings {
    public static final String KEY = "status_bar_lunaris_bar";
    public static final String ALTERNATIVE_ANIMATION =
            "status_bar_dynamic_island_alternative_animation";
    public static final String ALTERNATIVE_MEDIA =
            "status_bar_dynamic_island_alternative_media";
    public static final String ALTERNATIVE_FLASHLIGHT =
            "status_bar_dynamic_island_alternative_flashlight";
    public static final int MAX_CARDS = 24;
    public static final int WIDGET_HOST_ID = 0x4c424152;
    public static final String WIDGET_PICKER_ACTION =
            "com.android.systemui.action.LUNARIS_BAR_WIDGET_PICKER";
    private static final int MAX_CONFIG_LENGTH = 131072;
    public static final String[] PROFILE_KEYS = {
            "mini_alignment", "mini_scale_alignment", "mini_scale", "offset_x", "offset_y",
            "expanded_mode", "expanded_width", "expanded_shape", "corner_radius", "custom_radius",
            "collapsed_opacity", "collapsed_border", "camera_width", "camera_height", "mini_fade"
    };
    private final JSONObject values;

    public LunarisBarSettings() {
        values = new JSONObject();
    }

    private LunarisBarSettings(JSONObject values) {
        this.values = values;
    }

    public static LunarisBarSettings parse(String json) {
        if (json == null || json.length() > MAX_CONFIG_LENGTH) {
            return new LunarisBarSettings();
        }
        try {
            return new LunarisBarSettings(new JSONObject(json));
        } catch (JSONException e) {
            return new LunarisBarSettings();
        }
    }

    public static LunarisBarSettings read(ContentResolver resolver, int userId) {
        return parse(Settings.System.getStringForUser(resolver, KEY, userId));
    }

    public static LunarisBarSettings read(ContentResolver resolver) {
        return read(resolver, UserHandle.USER_CURRENT);
    }

    public static Map<String, Integer> systemDefaults() {
        Map<String, Integer> defaults = new LinkedHashMap<>();
        defaults.put("status_bar_show_dynamic_island", 0);
        for (String feature : new String[] {"battery", "unlock", "bluetooth", "calls",
                "notifications", "ongoing_activities", "hotspot", "vpn", "ringer", "timer",
                "audio_recording", "now_playing", "lyrics", "lockscreen", "disable_in_landscape"}) {
            defaults.put("status_bar_dynamic_island_" + feature, 0);
        }
        for (String feature : new String[] {"media_controls", "screen_recording", "alarms",
                "flashlight", "stopwatch", "live_scores", "redesigned_media"}) {
            defaults.put("status_bar_dynamic_island_" + feature, 1);
        }
        defaults.put("status_bar_dynamic_island_width_scale", 100);
        defaults.put("status_bar_dynamic_island_height_scale", 100);
        defaults.put("status_bar_dynamic_island_popup_color_mode", 0);
        defaults.put(ALTERNATIVE_ANIMATION, 1);
        defaults.put(ALTERNATIVE_MEDIA, 1);
        defaults.put(ALTERNATIVE_FLASHLIGHT, 1);
        return Collections.unmodifiableMap(defaults);
    }

    public static boolean reset(ContentResolver resolver, int userId) {
        boolean success = true;
        for (Map.Entry<String, Integer> entry : systemDefaults().entrySet()) {
            success &= Settings.System.putIntForUser(resolver, entry.getKey(), entry.getValue(), userId);
        }
        success &= new LunarisBarSettings().write(resolver, userId);
        return success;
    }

    public boolean write(ContentResolver resolver, int userId) {
        String json = toString();
        return json.length() <= MAX_CONFIG_LENGTH
                && Settings.System.putStringForUser(resolver, KEY, json, userId);
    }

    public int integer(String key, int fallback, int minimum, int maximum) {
        long value = values.optLong(key, fallback);
        return (int) Math.max(minimum, Math.min(maximum, value));
    }

    public boolean enabled(String key, boolean fallback) {
        return values.optBoolean(key, fallback);
    }

    public String text(String key, String fallback) {
        String value = values.optString(key, fallback);
        return value.length() <= 8192 ? value : fallback;
    }

    public LunarisBarSettings with(String key, Object value) {
        try {
            JSONObject copy = new JSONObject(values.toString());
            copy.put(key, value);
            return new LunarisBarSettings(copy);
        } catch (JSONException e) {
            return this;
        }
    }

    public static int screenWidth(Context context) {
        return Math.round(context.getResources().getConfiguration().windowConfiguration
                .getMaxBounds().width() / context.getResources().getDisplayMetrics().density);
    }

    public static int screenHeight(Context context) {
        return Math.round(context.getResources().getConfiguration().windowConfiguration
                .getMaxBounds().height() / context.getResources().getDisplayMetrics().density);
    }

    public boolean hasScreenProfile(int width, int height) {
        return values.optJSONObject("profile_" + width + "x" + height) != null;
    }

    public static boolean isProfileKey(String key) {
        for (String entry : PROFILE_KEYS) if (entry.equals(key)) return true;
        return false;
    }

    public LunarisBarSettings forScreen(int width, int height) {
        JSONObject profile = values.optJSONObject("profile_" + width + "x" + height);
        if (profile == null) return this;
        LunarisBarSettings result = this;
        for (String key : PROFILE_KEYS) {
            if (profile.has(key)) result = result.with(key, profile.opt(key));
        }
        return result;
    }

    public LunarisBarSettings withScreenProfile(int width, int height, boolean enabled) {
        JSONObject profile = new JSONObject();
        if (enabled) {
            try {
                for (String key : PROFILE_KEYS) {
                    if (values.has(key)) profile.put(key, values.opt(key));
                }
            } catch (JSONException e) {
                return this;
            }
        }
        return with("profile_" + width + "x" + height, enabled ? profile : null);
    }

    public LunarisBarSettings withScreenValue(int width, int height, String key, Object value) {
        String profileKey = "profile_" + width + "x" + height;
        JSONObject profile = values.optJSONObject(profileKey);
        if (profile == null || !isProfileKey(key)) return with(key, value);
        try {
            JSONObject copy = new JSONObject(profile.toString());
            copy.put(key, value);
            return with(profileKey, copy);
        } catch (JSONException e) {
            return this;
        }
    }

    public int getTheme() { return integer("theme", 0, 0, 2); }
    public int getAlignment() { return integer("mini_alignment", 0, 0, 2); }
    public int getScaleAlignment() { return integer("mini_scale_alignment", 0, 0, 2); }
    public float getMiniScale() { return integer("mini_scale", 100, 70, 200) / 100f; }
    public int getOffsetX() { return integer("offset_x", 0, -200, 200); }
    public int getOffsetY() { return integer("offset_y", 0, -24, 100); }
    public int getExpandedMode() { return integer("expanded_mode", 1, 0, 1); }
    public boolean isFullWidth() { return integer("expanded_width", 1, 0, 1) == 0; }
    public int getCornerRadius() { return enabled("custom_radius", true) ? integer("corner_radius", 32, 0, 64) : 32; }
    public int getExpandedShape() { return integer("expanded_shape", 1, 0, 2); }
    public int getAnimationStyle() { return integer("animation_style", 0, 0, 1); }
    public float getAnimationSpeed() { return integer("animation_speed", 100, 50, 200) / 100f; }
    public int getBounce() { return integer("animation_bounce", 1, 0, 2); }
    public boolean isReducedMotion() { return enabled("reduce_motion", false); }
    public boolean isHapticsEnabled() { return enabled("haptics", true); }
    public boolean isCompanionEnabled() { return enabled("companion", true); }
    public boolean isAlwaysVisible() { return enabled("always_visible", false); }
    public int getMediaBackground() { return integer("media_background", 2, 0, 4); }
    public int getArtworkShape() { return integer("artwork_shape", 4, 0, 31); }
    public int getPlayerIcon() { return integer("player_icon", 0, 0, 2); }
    public boolean isArtworkRotating() { return enabled("rotate_artwork", true); }
    public boolean isEqualizerEnabled() { return enabled("equalizer", true); }
    public boolean isSliderVisible() { return enabled("media_slider", true); }

    public int palette(boolean mini, boolean dark) {
        return integer((mini ? "mini" : "capsule") + (dark ? "_palette_night" : "_palette_day"),
                4, 0, 20);
    }

    public int color(boolean mini, boolean dark, boolean foreground) {
        String prefix = mini ? "mini" : "capsule";
        return integer(prefix + (foreground ? "_foreground" : "_background")
                        + (dark ? "_night" : "_day"),
                foreground ? 0xffffffff : 0xff000000,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public float opacity(boolean mini, boolean dark) {
        return integer((mini ? "mini" : "capsule") + (dark ? "_opacity_night" : "_opacity_day"),
                100, 10, 100) / 100f;
    }

    public boolean outline(boolean dark) {
        return enabled(dark ? "outline_night" : "outline_day", dark);
    }

    public boolean shadow(boolean dark) {
        return enabled(dark ? "shadow_night" : "shadow_day", !dark);
    }

    public float getOutlineWidth() { return integer("outline_width", 10, 0, 30) / 10f; }
    public int getShadowElevation() { return integer("shadow_elevation", 12, 0, 32); }
    public float getCollapsedOpacity() { return integer("collapsed_opacity", 0, 0, 100) / 100f; }
    public int getCollapsedBorder() { return integer("collapsed_border", 0, 0, 3); }

    public List<Card> getCards() {
        JSONArray array = values.optJSONArray("cards");
        if (array == null) return Collections.emptyList();
        List<Card> cards = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < Math.min(array.length(), MAX_CARDS); i++) {
            JSONObject value = array.optJSONObject(i);
            if (value == null) continue;
            Card card = new Card(value);
            if (card.isValid() && ids.add(card.getId())) cards.add(card);
        }
        return Collections.unmodifiableList(cards);
    }

    public LunarisBarSettings withCards(List<Card> cards) {
        JSONArray array = new JSONArray();
        Set<String> ids = new HashSet<>();
        for (Card card : cards) {
            if (array.length() == MAX_CARDS) break;
            if (card.isValid() && ids.add(card.getId())) array.put(card.values);
        }
        return with("cards", array);
    }

    @Override
    public String toString() { return values.toString(); }

    @Override
    public boolean equals(Object other) {
        return other instanceof LunarisBarSettings
                && toString().equals(other.toString());
    }

    @Override
    public int hashCode() { return toString().hashCode(); }

    public static final class Card {
        public static final String[] TYPES = {
                "shortcuts", "widget", "clock", "timer", "barcode", "brightness",
                "volume", "flashlight", "battery"
        };
        private final JSONObject values;

        private Card(JSONObject values) { this.values = values; }

        public static Card create(String type, String title) {
            return new Card(new JSONObject()).with("id", UUID.randomUUID().toString())
                    .with("type", type).with("title", title);
        }

        public Card with(String key, Object value) {
            try {
                JSONObject copy = new JSONObject(values.toString());
                copy.put(key, value);
                return new Card(copy);
            } catch (JSONException e) {
                return this;
            }
        }

        public String text(String key, String fallback) {
            String value = values.optString(key, fallback);
            return value.length() <= 8192 ? value : fallback;
        }

        public int integer(String key, int fallback, int minimum, int maximum) {
            return (int) Math.max(minimum, Math.min(maximum, values.optLong(key, fallback)));
        }

        public boolean enabled(String key, boolean fallback) {
            return values.optBoolean(key, fallback);
        }

        public String getId() { return text("id", ""); }
        public String getType() { return text("type", ""); }
        public String getTitle() { return text("title", ""); }
        public int getWidgetId() { return integer("widget_id", -1, -1, Integer.MAX_VALUE); }
        public int getHeight() { return integer("height", 180, 64, 480); }
        public boolean isEnabled() { return enabled("enabled", true); }
        public boolean isAllowedOnLockscreen() { return enabled("lockscreen", false); }

        private boolean isValid() {
            if (getId().isEmpty() || getId().length() > 80) return false;
            for (String type : TYPES) {
                if (type.equals(getType())) return true;
            }
            return false;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Card && values.toString().equals(other.toString());
        }

        @Override
        public String toString() { return values.toString(); }

        @Override
        public int hashCode() { return values.toString().hashCode(); }
    }
}
