/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.os.UserHandle.USER_SYSTEM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.PathParser;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.content.res.Configuration;
import android.content.res.ThemeEngine;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.Path;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ThemeUtils {

    public static final String TAG = "ThemeUtils";

    public static final String FONT_KEY = "android.theme.customization.font";
    public static final String ICON_SHAPE_KEY= "android.theme.customization.adaptive_icon_shape";

    /** @see ThemeEngineManagerService — status bar Wi‑Fi / cellular icons use theme_engine_data, not only RRO. */
    private static final String OVERLAY_CATEGORY_WIFI_ICON = "android.theme.customization.wifi_icon";
    private static final String OVERLAY_CATEGORY_SIGNAL_ICON = "android.theme.customization.signal_icon";
    private static final String OVERLAY_CATEGORY_BATTERY_STYLE =
            "android.theme.customization.battery_style";

    /**
     * {@link android.content.om.OverlayInfo#getCategory()} value for charging-animation RROs.
     * Matches {@code ThemeOverlayApplier} / ThemePicker; not the same string as
     * {@link ThemeEngine#CATEGORY_CHARGING_ANIMATION} ({@code charging_animation} is the
     * engine JSON key only).
     */
    public static final String OVERLAY_CATEGORY_CHARGING_ANIMATION =
            "android.theme.customization.charging_animation";

    /**
     * {@link OverlayInfo#getCategory()} for back-gesture RROs targeting SystemUI.
     * Matches {@link com.android.systemui.theme.ThemeOverlayApplier#OVERLAY_CATEGORY_BACK_GESTURE}.
     */
    public static final String OVERLAY_CATEGORY_BACK_GESTURE =
            "android.theme.customization.back_gesture";

    public static final Comparator<OverlayInfo> OVERLAY_INFO_COMPARATOR =
            Comparator.comparingInt(a -> a.priority);

    private static ThemeUtils instance;

    private Context mContext;
    private IOverlayManager mOverlayManager;
    private PackageManager pm;
    private Resources overlayRes;

    public ThemeUtils(Context context) {
        mContext = context;
        mOverlayManager = IOverlayManager.Stub
                .asInterface(ServiceManager.getService(Context.OVERLAY_SERVICE));
        pm = context.getPackageManager();
    }

    public static ThemeUtils getInstance(Context context) {
        if (instance == null) {
            synchronized (ThemeUtils.class) {
                if (instance == null) {
                    instance = new ThemeUtils(context);
                }
            }
        }
        return instance;
    }

    public void setOverlayEnabled(String category, String packageName, String target) {
        final String currentPackageName = getOverlayInfos(category, target).stream()
                .filter(info -> info.isEnabled())
                .map(info -> info.packageName)
                .findFirst()
                .orElse(null);

        try {
            if (target.equals(packageName)) {
                if (currentPackageName != null) {
                    mOverlayManager.setEnabled(currentPackageName, false, USER_SYSTEM);
                }
            } else {
                mOverlayManager.setEnabledExclusiveInCategory(packageName, USER_SYSTEM);
            }

            writeSettings(category, packageName, target.equals(packageName));

        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while setting overlay: " + e.getMessage(), e);
        }
    }

    public void writeSettings(String category, String packageName, boolean disable) {
        final String overlayPackageJson = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES, UserHandle.USER_CURRENT);
        JSONObject object;
        try {
            if (overlayPackageJson == null) {
                object = new JSONObject();
            } else {
                object = new JSONObject(overlayPackageJson);
            }
            if (disable) {
                if (object.has(category)) object.remove(category);
            } else {
                object.put(category, packageName);
            }
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                    object.toString(), UserHandle.USER_CURRENT);

            if (OVERLAY_CATEGORY_WIFI_ICON.equals(category)
                    || OVERLAY_CATEGORY_SIGNAL_ICON.equals(category)
                    || OVERLAY_CATEGORY_BATTERY_STYLE.equals(category)
                    || OVERLAY_CATEGORY_CHARGING_ANIMATION.equals(category)
                    || OVERLAY_CATEGORY_BACK_GESTURE.equals(category)) {
                syncThemeEngineOverlayInSettings(category, packageName, disable);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e);
        }
    }

    /**
     * Keeps {@link ThemeEngine#SETTINGS_THEME_ENGINE_DATA} aligned with
     * {@link Settings.Secure#THEME_CUSTOMIZATION_OVERLAY_PACKAGES} when overlays are applied
     * from ThemePicker / Alpha so {@link ThemeEngineManagerService} sees the same categories as RRO.
     * Wi‑Fi / signal / battery also mirror {@code statusbar_<key>} for
     * {@link com.android.systemui.statusbar.connectivity.ThemeIconController}; charging uses
     * {@link ThemeEngine#CATEGORY_CHARGING_ANIMATION} and back-gesture uses
     * {@link ThemeEngine#CATEGORY_BACK_GESTURE} only.
     */
    private void syncThemeEngineOverlayInSettings(String overlayCategory, String packageName,
            boolean disable) {
        final String engineKey;
        final boolean mirrorStatusBarAlias;
        if (OVERLAY_CATEGORY_WIFI_ICON.equals(overlayCategory)) {
            engineKey = "wifi";
            mirrorStatusBarAlias = true;
        } else if (OVERLAY_CATEGORY_SIGNAL_ICON.equals(overlayCategory)) {
            engineKey = "signal";
            mirrorStatusBarAlias = true;
        } else if (OVERLAY_CATEGORY_BATTERY_STYLE.equals(overlayCategory)) {
            engineKey = ThemeEngine.CATEGORY_BATTERY_STYLE;
            mirrorStatusBarAlias = true;
        } else if (OVERLAY_CATEGORY_CHARGING_ANIMATION.equals(overlayCategory)) {
            engineKey = ThemeEngine.CATEGORY_CHARGING_ANIMATION;
            mirrorStatusBarAlias = false;
        } else if (OVERLAY_CATEGORY_BACK_GESTURE.equals(overlayCategory)) {
            engineKey = ThemeEngine.CATEGORY_BACK_GESTURE;
            mirrorStatusBarAlias = false;
        } else {
            return;
        }
        try {
            String json = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    ThemeEngine.SETTINGS_THEME_ENGINE_DATA, UserHandle.USER_CURRENT);
            JSONObject root = (json == null || json.isEmpty())
                    ? new JSONObject() : new JSONObject(json);

            JSONObject themes = root.optJSONObject("themes");
            if (themes == null) {
                themes = new JSONObject();
            }
            JSONObject categoryThemes = root.optJSONObject("categoryThemes");
            if (categoryThemes == null) {
                categoryThemes = new JSONObject();
            }

            if (disable) {
                themes.remove(engineKey);
                categoryThemes.remove(engineKey);
                if (mirrorStatusBarAlias) {
                    categoryThemes.remove("statusbar_" + engineKey);
                }
            } else {
                JSONObject catEntry = new JSONObject();
                catEntry.put("enabled", true);
                catEntry.put("packageName", packageName);
                themes.put(engineKey, catEntry);
                categoryThemes.put(engineKey, packageName);
                if (mirrorStatusBarAlias) {
                    categoryThemes.put("statusbar_" + engineKey, packageName);
                }
            }

            root.put("themes", themes);
            root.put("categoryThemes", categoryThemes);
            if (!root.has("version")) {
                root.put("version", 1);
            }

            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    ThemeEngine.SETTINGS_THEME_ENGINE_DATA, root.toString(),
                    UserHandle.USER_CURRENT);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to sync theme_engine_data for " + overlayCategory, e);
        }
    }

    public List<String> getOverlayPackagesForCategory(String category) {
        return getOverlayPackagesForCategory(category, "android");
    }

    public List<String> getOverlayPackagesForCategory(String category, String target) {
        List<String> overlays = new ArrayList<>();
        List<String> mPkgs = new ArrayList<>();
        overlays.add(target);
        for (OverlayInfo info : getOverlayInfos(category, target)) {
            if (category.equals(info.getCategory())) {
                mPkgs.add(info.getPackageName());
            }
        }
        Collections.sort(mPkgs);
        overlays.addAll(mPkgs);
        return overlays;
    }

    public List<OverlayInfo> getOverlayInfos(String category) {
        return getOverlayInfos(category, "android");
    }

    public List<OverlayInfo> getOverlayInfos(String category, String target) {
        final List<OverlayInfo> filteredInfos = new ArrayList<>();
        try {
            List<OverlayInfo> overlayInfos = mOverlayManager
                    .getOverlayInfosForTarget(target, USER_SYSTEM);
            for (OverlayInfo overlayInfo : overlayInfos) {
                if (category.equals(overlayInfo.category)) {
                    filteredInfos.add(overlayInfo);
                }
            }
        } catch (RemoteException re) {
            Log.e(TAG, "RemoteException while getting overlay info: " + re.getMessage(), re);
        }
        filteredInfos.sort(OVERLAY_INFO_COMPARATOR);
        return filteredInfos;
    }

    public List<Typeface> getFonts() {
        final List<Typeface> fontlist = new ArrayList<>();
                for (String overlayPackage : getOverlayPackagesForCategory(FONT_KEY)) {
            Resources overlayRes = null;
            try {
                overlayRes = overlayPackage.equals("android") ? Resources.getSystem()
                        : pm.getResourcesForApplication(overlayPackage);
                if (overlayRes != null) {
                    int fontId = overlayRes.getIdentifier("config_bodyFontFamily", "string", overlayPackage);
                    if (fontId != 0) {
                        String fontName = overlayRes.getString(fontId);
                        fontlist.add(Typeface.create(fontName, Typeface.NORMAL));
                    }
                }
            } catch (NameNotFoundException | NotFoundException e) {
                Log.e(TAG, "Error fetching fonts for package: " + overlayPackage, e);
            }
        }
        return fontlist;
    }

    public List<ShapeDrawable> getShapeDrawables() {
        final List<ShapeDrawable> shapelist = new ArrayList<>();
            for (String overlayPackage : getOverlayPackagesForCategory(ICON_SHAPE_KEY)) {
                    shapelist.add(createShapeDrawable(overlayPackage));
            }
        return shapelist;
    }

    public ShapeDrawable createShapeDrawable(String overlayPackage) {
        try {
            if (overlayPackage.equals("android")) {
                overlayRes = Resources.getSystem();
            } else {
                if (overlayPackage.equals("default")) overlayPackage = "android";
                overlayRes = pm.getResourcesForApplication(overlayPackage);
            }
        } catch (NameNotFoundException | NotFoundException e) {
            // Do nothing
        }
        if (overlayRes == null) {
            Log.e(TAG, "Resources not found for package: " + overlayPackage);
            return null;
        }
        final String shape = overlayRes.getString(
            overlayRes.getIdentifier("config_icon_mask",
            "string", overlayPackage));
        Path path = TextUtils.isEmpty(shape) ? null : PathParser.createPathFromPathData(shape);
        PathShape pathShape = new PathShape(path, 100f, 100f);
        ShapeDrawable shapeDrawable = new ShapeDrawable(pathShape);
        int mThumbSize = (int) (mContext.getResources().getDisplayMetrics().density * 72);
        shapeDrawable.setIntrinsicHeight(mThumbSize);
        shapeDrawable.setIntrinsicWidth(mThumbSize);
        return shapeDrawable;
    }

    public boolean isOverlayEnabled(String overlayPackage) {
        try {
            OverlayInfo info = mOverlayManager.getOverlayInfo(overlayPackage, USER_SYSTEM);
            return info == null ? false : info.isEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while checking if overlay is enabled: " + e.getMessage(), e);
        }
        return false;
    }

    public boolean isDefaultOverlay(String category) {
        return getOverlayPackagesForCategory(category).stream()
               .noneMatch(pkg -> isOverlayEnabled(pkg));
    }

    public static String batteryTemperature(Context context, Boolean ForC) {
        Intent intent = context.registerReceiver(null, new IntentFilter(
                Intent.ACTION_BATTERY_CHANGED));
        float  temp = ((float) (intent != null ? intent.getIntExtra(
                BatteryManager.EXTRA_TEMPERATURE, 0) : 0)) / 10;
        // Round up to nearest number
        int c = (int) ((temp) + 0.5f);
        float n = temp + 0.5f;
        // Use boolean to determine celsius or fahrenheit
        return String.valueOf((n - c) % 2 == 0 ? (int) temp :
                ForC ? c * 9/5 + 32 + "°F" :c + "°C");
    }

    public static String getCPUTemp(Context context) {
        String value = null;
        String cpuTempPath = context.getResources().getString(
                com.android.internal.R.string.config_cpu_temp_path);
        
        if (fileExists(cpuTempPath)) {
            value = readOneLine(cpuTempPath);
        }
        
        if (value == null || value.isEmpty()) {
            return "N/A";
        }
        
        try {
            int cpuTempMultiplier = context.getResources().getInteger(
                    com.android.internal.R.integer.config_sysCPUTempMultiplier);
            
            if (cpuTempMultiplier == 0) {
                cpuTempMultiplier = 1;
            }
            
            int temp = Integer.parseInt(value.trim()) / cpuTempMultiplier;
            return String.format("%d°C", temp);
        } catch (NumberFormatException | Resources.NotFoundException e) {
            Log.w("ThemeUtils", "Failed to parse CPU temperature: " + value, e);
            return "N/A";
        }
    }

    public static boolean fileExists(String filename) {
        if (filename == null || filename.isEmpty()) {
            return false;
        }
        return new File(filename).exists();
    }

    public static String readOneLine(String fname) {
        if (fname == null || fname.isEmpty()) {
            return null;
        }
        
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(fname), 512);
            return br.readLine();
        } catch (Exception e) {
            Log.w("ThemeUtils", "Failed to read file: " + fname, e);
            return null;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    // Ignore close errors
                }
            }
        }
    }

    /**
     * Whether {@code targetPackage} has an enabled overlay in {@code category} for {@code user}.
     * Prefer this over ad-hoc scans so category strings stay consistent with
     * {@link #OVERLAY_CATEGORY_CHARGING_ANIMATION} and friends.
     */
    public static boolean isOverlayEnabledForCategory(
            @Nullable IOverlayManager overlayManager,
            @NonNull String targetPackage,
            @NonNull String category,
            @NonNull UserHandle user) {
        if (overlayManager == null) {
            return false;
        }
        try {
            List<OverlayInfo> overlays = overlayManager.getOverlayInfosForTarget(
                    targetPackage, user.getIdentifier());
            if (overlays == null) {
                return false;
            }
            for (int i = 0; i < overlays.size(); i++) {
                OverlayInfo o = overlays.get(i);
                if (o == null) {
                    continue;
                }
                if (!category.equals(o.getCategory())) {
                    continue;
                }
                if (o.isEnabled()) {
                    return true;
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "isOverlayEnabledForCategory: " + category, e);
        }
        return false;
    }
}
