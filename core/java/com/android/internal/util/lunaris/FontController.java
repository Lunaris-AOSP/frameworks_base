/*
 * Copyright (C) 2025-2026 AxionOS
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

import android.app.ActivityThread;
import android.graphics.Typeface;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LruCache;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class FontController {

    private static final String TAG = "FontController";

    private static final FontController sInstance = new FontController();

    public static final String GSF_FONT = "google-sans-flex";
    public static final String PROP_DEFAULT_FONT = "persist.sys.ax_default_font";
    public static final String PROP_OVERLAY_FONTS = "persist.sys.ax_overlay_fonts";
    private static final String SEPARATOR = ":";

    private String[] mFontConfig;
    private String mRawProp;
    private String mDefaultFont;

    private static final int IDX_BODY = 0;
    private static final int IDX_BODY_MEDIUM = 1;
    private static final int IDX_HEADLINE = 2;
    private static final int IDX_HEADLINE_MEDIUM = 3;
    private static final int FIELD_COUNT = 4;

    private final LruCache<String, Typeface> mCache = new LruCache<>(30);

    private static final Set<String> EXCLUDED_APPS = new HashSet<>(Arrays.asList(
            "it.subito",
            "tv.arte.plus7",
            "com.google.android.gm"
    ));

    private static final Set<String> WHITELIST_FONTS = new HashSet<>(Arrays.asList(
            "serif",
            "monospace",
            "sans-serif-monospace",
            "monaco",
            "courier",
            "courier new",
            "cursive",
            "NotoSansSC",
            "NotoSansTC",
            "NotoSansJP",
            "NotoSansKR",
            "NotoColorEmoji",
            "NotoColorEmojiFlags",
            "NotoSansMono",
            "RobotoMono",
            "DroidSansMono",
            "CutiveMono",
            "CarroisGothicSC",
            "source-code-pro",
            "gs-clock",
            "google-sans-flex-clock",
            // Clock fonts
            "Ayame",
            "Badeen",
            "Big-clock6",
            "BR_SC",
            "Bebas",
            "Blackout",
            "Cotta",
            "Delirium",
            "DeliriumNeon",
            "Digit4",
            "Digit8",
            "Electroharmonix",
            "EncodeSansBold",
            "EncodeSansLight",
            "Expanse",
            "Fontarian",
            "FontsFree-Net-Carthart",
            "Galada",
            "Getaway",
            "Gobold",
            "Gobold_long",
            "GoldenAge",
            "Heathergreen",
            "Hollow",
            "Hollow2",
            "Kenyen",
            "LightPixel",
            "Longest",
            "Modak",
            "Neu",
            "Neumatic",
            "PXtall",
            "PlayfairDisplay",
            "Quartilla",
            "Quartilla_thin",
            "Rothefigh",
            "Sans_Bold",
            "Skewrom",
            "SlateForOnePlus-Bold",
            "SlateForOnePlus-Medium",
            "SlateForOnePlus-Regular",
            "Taller",
            "Templer",
            "Varino",
            "astthillabeltaden",
            "bebasneue",
            "bebasneue_bold",
            "cattyabrown",
            "general_sans",
            "japanese3017",
            "montserrat_bold",
            "ntype82",
            "polyline",
            "samsung7",
            "slim",
            "spaceage",
            "subway",
            "tallclock"
    ));

    private static final String[][] WEIGHT_KEYWORDS = {
            {"thin", "100"},
            {"extralight", "200"},
            {"light", "300"},
            {"semibold", "600"},
            {"extrabold", "800"},
            {"bold", "700"},
            {"medium", "500"},
            {"black", "900"},
    };

    private static final Map<String, Integer> VARIABLE_WEIGHT_MAP = new ArrayMap<>();
    static {
        VARIABLE_WEIGHT_MAP.put("variable-display-large", 400);
        VARIABLE_WEIGHT_MAP.put("variable-display-medium", 400);
        VARIABLE_WEIGHT_MAP.put("variable-display-small", 400);
        VARIABLE_WEIGHT_MAP.put("variable-headline-large", 400);
        VARIABLE_WEIGHT_MAP.put("variable-headline-medium", 400);
        VARIABLE_WEIGHT_MAP.put("variable-headline-small", 400);
        VARIABLE_WEIGHT_MAP.put("variable-title-large", 400);
        VARIABLE_WEIGHT_MAP.put("variable-title-medium", 500);
        VARIABLE_WEIGHT_MAP.put("variable-title-small", 500);
        VARIABLE_WEIGHT_MAP.put("variable-label-large", 500);
        VARIABLE_WEIGHT_MAP.put("variable-label-medium", 500);
        VARIABLE_WEIGHT_MAP.put("variable-label-small", 500);
        VARIABLE_WEIGHT_MAP.put("variable-body-large", 400);
        VARIABLE_WEIGHT_MAP.put("variable-body-medium", 400);
        VARIABLE_WEIGHT_MAP.put("variable-body-small", 400);
        VARIABLE_WEIGHT_MAP.put("variable-display-large-emphasized", 500);
        VARIABLE_WEIGHT_MAP.put("variable-display-medium-emphasized", 500);
        VARIABLE_WEIGHT_MAP.put("variable-display-small-emphasized", 500);
        VARIABLE_WEIGHT_MAP.put("variable-headline-large-emphasized", 500);
        VARIABLE_WEIGHT_MAP.put("variable-headline-medium-emphasized", 500);
        VARIABLE_WEIGHT_MAP.put("variable-headline-small-emphasized", 500);
        VARIABLE_WEIGHT_MAP.put("variable-title-large-emphasized", 500);
        VARIABLE_WEIGHT_MAP.put("variable-title-medium-emphasized", 600);
        VARIABLE_WEIGHT_MAP.put("variable-title-small-emphasized", 600);
        VARIABLE_WEIGHT_MAP.put("variable-label-large-emphasized", 600);
        VARIABLE_WEIGHT_MAP.put("variable-label-medium-emphasized", 600);
        VARIABLE_WEIGHT_MAP.put("variable-label-small-emphasized", 600);
        VARIABLE_WEIGHT_MAP.put("variable-body-large-emphasized", 500);
        VARIABLE_WEIGHT_MAP.put("variable-body-medium-emphasized", 500);
        VARIABLE_WEIGHT_MAP.put("variable-body-small-emphasized", 500);
    }

    public static FontController get() {
        return sInstance;
    }

    private FontController() {}

    public static String getBodyFont() {
        return get().resolveConfig()[IDX_BODY];
    }

    public static String getBodyFontMedium() {
        return get().resolveConfig()[IDX_BODY_MEDIUM];
    }

    public static String getHeadlineFont() {
        return get().resolveConfig()[IDX_HEADLINE];
    }

    public static String getHeadlineFontMedium() {
        return get().resolveConfig()[IDX_HEADLINE_MEDIUM];
    }

    public static boolean isCustomFontActive() {
        return isCustomFontActive(get().resolveConfig());
    }

    private static boolean isCustomFontActive(String[] config) {
        for (String family : config) {
            if (!GSF_FONT.equals(family)) return true;
        }
        return false;
    }

    public static boolean isExcludedApp() {
        String pkg = getCurrentPackageName();
        return pkg != null && EXCLUDED_APPS.contains(pkg);
    }

    public static boolean isFontWhitelisted(String familyName) {
        if (familyName == null) return false;
        for (String wl : WHITELIST_FONTS) {
            if (familyName.equals(wl) || familyName.startsWith(wl + "-")) return true;
        }
        return false;
    }

    public static Typeface getOverrideTypeface(String familyName) {
        if (familyName == null) return null;
        if (isExcludedApp()) return null;
        if (isFontWhitelisted(familyName)) return null;
        return get().resolveOverrideTypeface(familyName);
    }

    private synchronized Typeface resolveOverrideTypeface(String familyName) {
        String[] config = resolveConfig();
        if (!isCustomFontActive(config)) return null;

        for (String family : config) {
            if (familyName.equals(family)) {
                return Typeface.getSystemDefaultTypeface(family);
            }
        }

        boolean isVariable = familyName.startsWith("variable-");
        int weight = resolveWeight(familyName);
        boolean isItalic = familyName.contains("italic");

        Typeface base = resolveBase(config, familyName, isVariable, weight);

        if (weight == 400 && !isItalic && base == Typeface.DEFAULT) {
            return Typeface.DEFAULT;
        }

        Typeface cached = mCache.get(familyName);
        if (cached != null) return cached;

        Typeface result = Typeface.create(base, weight, isItalic);
        mCache.put(familyName, result);
        return result;
    }

    private static Typeface resolveBase(String[] config, String name,
            boolean isVariable, int weight) {
        boolean isHeadlineRole = isVariable && (name.startsWith("variable-headline")
                || name.startsWith("variable-display"));
        boolean isMediumWeight = weight >= 500;

        String baseName;
        if (isHeadlineRole) {
            baseName = isMediumWeight ? config[IDX_HEADLINE_MEDIUM] : config[IDX_HEADLINE];
        } else {
            baseName = isMediumWeight ? config[IDX_BODY_MEDIUM] : config[IDX_BODY];
        }

        Typeface base = Typeface.getSystemDefaultTypeface(baseName);
        return base != null ? base : Typeface.DEFAULT;
    }

    private static int resolveWeight(String name) {
        if (name.startsWith("variable-")) {
            Integer exact = VARIABLE_WEIGHT_MAP.get(name);
            if (exact != null) return exact;
            return name.endsWith("-emphasized") ? 500 : 400;
        }

        for (String[] entry : WEIGHT_KEYWORDS) {
            if (name.contains(entry[0])) return Integer.parseInt(entry[1]);
        }
        return 400;
    }

    public static void clearCaches() {
        synchronized (sInstance) {
            sInstance.mCache.evictAll();
        }
    }

    private synchronized String[] resolveConfig() {
        String defaultFont = SystemProperties.get(PROP_DEFAULT_FONT, GSF_FONT);
        String raw = SystemProperties.get(PROP_OVERLAY_FONTS, "");

        if (raw.equals(mRawProp) && defaultFont.equals(mDefaultFont) && mFontConfig != null) {
            return mFontConfig;
        }

        String[] config;
        if (raw.isEmpty()) {
            config = new String[] { defaultFont, defaultFont, defaultFont, defaultFont };
        } else {
            String[] parts = raw.split(SEPARATOR, -1);
            config = new String[FIELD_COUNT];
            for (int i = 0; i < FIELD_COUNT; i++) {
                config[i] = (i < parts.length && !parts[i].isEmpty())
                        ? parts[i] : defaultFont;
            }
        }

        mRawProp = raw;
        mDefaultFont = defaultFont;
        mFontConfig = config;
        mCache.evictAll();
        logger("Font config: body=" + config[IDX_BODY]
                + " bodyMed=" + config[IDX_BODY_MEDIUM]
                + " headline=" + config[IDX_HEADLINE]
                + " headlineMed=" + config[IDX_HEADLINE_MEDIUM]);
        return config;
    }

    private static String getCurrentPackageName() {
        try {
            return ActivityThread.currentPackageName();
        } catch (Exception e) {
            return null;
        }
    }

    private static void logger(String msg) {
        if (SystemProperties.getBoolean("persist.sys.ax_font_debug", false)) {
            Log.d(TAG, msg);
        }
    }
}
