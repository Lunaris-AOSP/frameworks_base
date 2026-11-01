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
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class EdgeAuroraConfig {
    public static final String ENABLED = "edge_aurora_enabled";
    public static final String PROFILES = "edge_aurora_profiles";
    public static final String LOCK_SCREEN = "edge_aurora_lock_screen";
    public static final String WAKE = "edge_aurora_wake";
    public static final String BRIGHTNESS = "edge_aurora_brightness";
    public static final String TIMEOUT = "edge_aurora_timeout";
    public static final String CLOCK = "edge_aurora_clock";
    public static final String AUTO_PEEK = "edge_aurora_auto_peek";
    public static final String PEEK_TIMEOUT = "edge_aurora_peek_timeout";
    public static final String POPUP_BLUR = "edge_aurora_popup_blur";
    public static final String OPEN_ACTION = "edge_aurora_open_action";
    public static final int OPEN_ON_TAP = 0;
    public static final int OPEN_ON_HOLD = 1;
    public static final String QUIET_APPS = "edge_aurora_quiet_apps";
    public static final String ONLY_EDGE = "edge_aurora_only_edge";
    public static final String RECENT_COLORS = "edge_aurora_recent_colors";
    public static final String PREVIEW = "edge_aurora_preview";
    public static final String[] KEYS = {ENABLED, PROFILES, LOCK_SCREEN, WAKE, BRIGHTNESS,
            TIMEOUT, CLOCK, AUTO_PEEK, PEEK_TIMEOUT, POPUP_BLUR, OPEN_ACTION, QUIET_APPS,
            ONLY_EDGE, PREVIEW};
    public static final int[] PALETTE = {0xffff5c8a, 0xffff7a59, 0xffffc857, 0xff5cff9e,
            0xff5ce1e6, 0xff6c8cff, 0xff8b7cff, 0xffc77dff, 0xffff6ad5, 0xfff4f5f8};
    public static final int MAX_PROFILE_LENGTH = 131072;

    public enum Edge { LEFT, RIGHT, TOP, BOTTOM }
    public enum Motion { BREATHE, AURORA, COMET, PULSE, EMBER, NEON, WAVE }

    public static final class Profile {
        public String id;
        public String name = "";
        public boolean enabled = true;
        public int color = PALETTE[0];
        public Edge edge = Edge.RIGHT;
        public Motion motion = Motion.BREATHE;
        public float position = .28f;
        public float length = .20f;
        public float thickness = 5f;
        public float bloom = 22f;
        public final List<String> members = new ArrayList<>();

        public Profile(String id) { this.id = id; }
        public boolean isGroup() { return id.startsWith("group:"); }
        public boolean matches(String pkg) { return isGroup() ? members.contains(pkg) : id.equals(pkg); }
        public Profile copy() {
            Profile p = new Profile(id);
            p.name = name; p.enabled = enabled; p.color = color; p.edge = edge; p.motion = motion;
            p.position = position; p.length = length; p.thickness = thickness; p.bloom = bloom;
            p.members.addAll(members);
            return p;
        }
        public Profile clamp() {
            length = finiteClamp(length, .03f, .70f, .20f);
            position = finiteClamp(position, 0f, 1f - length, .28f);
            thickness = finiteClamp(thickness, 3f, 16f, 5f);
            bloom = finiteClamp(bloom, 8f, 40f, 22f);
            color |= 0xff000000;
            return this;
        }
        public JSONObject toJson() throws JSONException {
            clamp();
            return new JSONObject().put("id", id).put("name", name).put("enabled", enabled)
                    .put("color", color).put("edge", edge.name()).put("motion", motion.name())
                    .put("position", (double) position).put("length", (double) length)
                    .put("thickness", (double) thickness).put("bloom", (double) bloom)
                    .put("members", new JSONArray(members));
        }
    }

    public final boolean enabled, lockScreen, wake, clock, autoPeek, popupBlur, onlyEdge;
    public final int brightness, timeout, peekTimeout, openAction;
    public final List<Profile> profiles;
    public final Set<String> quietApps;

    public EdgeAuroraConfig(ContentResolver resolver, int user) {
        enabled = integer(resolver, ENABLED, 0, user) == 1;
        lockScreen = integer(resolver, LOCK_SCREEN, 1, user) == 1;
        wake = integer(resolver, WAKE, 1, user) == 1;
        clock = integer(resolver, CLOCK, 1, user) == 1;
        autoPeek = integer(resolver, AUTO_PEEK, 0, user) == 1;
        popupBlur = integer(resolver, POPUP_BLUR, 1, user) == 1;
        openAction = clamp(integer(resolver, OPEN_ACTION, OPEN_ON_TAP, user),
                OPEN_ON_TAP, OPEN_ON_HOLD);
        onlyEdge = integer(resolver, ONLY_EDGE, 0, user) == 1;
        brightness = clamp(integer(resolver, BRIGHTNESS, 10, user), 2, 50);
        timeout = clamp(integer(resolver, TIMEOUT, 15, user), 5, 60);
        int seconds = integer(resolver, PEEK_TIMEOUT, 6, user);
        peekTimeout = seconds == 0 ? 0 : clamp(seconds, 2, 30);
        profiles = readProfiles(Settings.System.getStringForUser(resolver, PROFILES, user));
        quietApps = readPackages(Settings.System.getStringForUser(resolver, QUIET_APPS, user));
    }

    public Profile profileFor(String pkg) {
        for (Profile p : profiles) if (p.isGroup() && p.matches(pkg)) return p.enabled ? p : null;
        for (Profile p : profiles) if (!p.isGroup() && p.matches(pkg)) return p.enabled ? p : null;
        return null;
    }

    public static List<Profile> readProfiles(String raw) {
        if (raw == null || raw.length() > MAX_PROFILE_LENGTH) return Collections.emptyList();
        List<Profile> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 1) != 1) return result;
            JSONArray array = root.optJSONArray("profiles");
            if (array == null) return result;
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                if (id.isEmpty() || id.length() > 255 || !ids.add(id)) continue;
                Profile p = new Profile(id);
                p.name = o.optString("name", "");
                if (p.name.length() > 80) p.name = p.name.substring(0, 80);
                p.enabled = o.optBoolean("enabled", true);
                p.color = o.optInt("color", PALETTE[i % PALETTE.length]);
                try { p.edge = Edge.valueOf(o.optString("edge", "RIGHT")); }
                catch (IllegalArgumentException ignored) { }
                try { p.motion = Motion.valueOf(o.optString("motion", "BREATHE")); }
                catch (IllegalArgumentException ignored) { }
                p.position = (float) o.optDouble("position", .28);
                p.length = (float) o.optDouble("length", .20);
                p.thickness = (float) o.optDouble("thickness", 5);
                p.bloom = (float) o.optDouble("bloom", 22);
                p.members.addAll(readPackages(o.optJSONArray("members")));
                result.add(p.clamp());
            }
        } catch (JSONException ignored) { }
        return result;
    }

    public static String encodeProfiles(List<Profile> profiles) {
        JSONArray array = new JSONArray();
        try {
            for (Profile p : profiles) array.put(p.toJson());
            return new JSONObject().put("version", 1).put("profiles", array).toString();
        } catch (JSONException e) { throw new IllegalArgumentException("Invalid glow profile", e); }
    }

    public static Set<String> readPackages(String raw) {
        try { return raw == null || raw.length() > 65536 ? Collections.emptySet()
                : readPackages(new JSONArray(raw)); }
        catch (JSONException ignored) { return Collections.emptySet(); }
    }

    private static Set<String> readPackages(JSONArray array) {
        Set<String> result = new java.util.LinkedHashSet<>();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            String pkg = array.optString(i, "");
            if (!pkg.isEmpty() && pkg.length() <= 255) result.add(pkg);
        }
        return result;
    }

    public static List<Profile> resolveOverlaps(List<Profile> profiles) {
        List<Profile> result = new ArrayList<>();
        for (Profile p : profiles) result.add(p.copy().clamp());
        for (Edge edge : Edge.values()) {
            List<Profile> side = new ArrayList<>();
            for (Profile p : result) if (p.edge == edge) side.add(p);
            side.sort((a, b) -> Float.compare(a.position, b.position));
            for (int i = 0; i < side.size();) {
                int end = i + 1;
                float start = side.get(i).position;
                float stop = start + side.get(i).length;
                while (end < side.size() && side.get(end).position < stop) {
                    stop = Math.max(stop, side.get(end).position + side.get(end).length);
                    end++;
                }
                int count = end - i;
                if (count > 1) {
                    float gap = Math.min(.012f, .06f * (stop - start) / count);
                    float length = (stop - start - gap * (count - 1)) / count;
                    for (int j = i; j < end; j++) {
                        side.get(j).position = start + (j - i) * (length + gap);
                        side.get(j).length = length;
                    }
                }
                i = end;
            }
        }
        return result;
    }

    private static int integer(ContentResolver r, String k, int d, int u) {
        return Settings.System.getIntForUser(r, k, d, u);
    }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(v, max)); }
    private static float finiteClamp(float v, float min, float max, float fallback) {
        return Math.max(min, Math.min(Float.isFinite(v) ? v : fallback, max));
    }
}
