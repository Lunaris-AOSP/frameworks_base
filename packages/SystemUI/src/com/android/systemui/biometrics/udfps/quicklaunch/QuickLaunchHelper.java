/*
 * Copyright (C) 2026 Project ASCP
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

package com.android.systemui.biometrics.udfps.quicklaunch;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class QuickLaunchHelper {

    private static final String TAG = "QuickLaunchHelper";

    public static final String SETTING_QUICK_LAUNCH_ENABLED = "quick_launch_enabled";
    public static final String SETTING_QUICK_LAUNCH_ITEMS = "quick_launch_items";

        public static final int[] SLOT_CHECK_ORDER = new int[]{2, 3, 1, 4, 0};

    private static QuickLaunchHelper sInstance;

    private final Context mContext;
    private final LauncherApps mLauncherApps;
    private final PackageManager mPackageManager;
    private final int mDensityDpi;
    private boolean mQuickLaunchEnabled;

    private QuickLaunchHelper(Context context) {
        mContext = context.getApplicationContext();
        mLauncherApps = (LauncherApps) mContext.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        mPackageManager = mContext.getPackageManager();
        mDensityDpi = mContext.getResources().getDisplayMetrics().densityDpi;
        updateQuickLaunchEnabled();
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SETTING_QUICK_LAUNCH_ENABLED),
                false,
                new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateQuickLaunchEnabled();
                    }
                },
                UserHandle.USER_ALL);
    }

    public void updateQuickLaunchEnabled() {
        mQuickLaunchEnabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                SETTING_QUICK_LAUNCH_ENABLED, 1, UserHandle.USER_CURRENT) == 1;
    }

    public static synchronized QuickLaunchHelper getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new QuickLaunchHelper(context);
        }
        return sInstance;
    }

    public boolean isQuickLaunchEnabled() {
        return mQuickLaunchEnabled;
    }

    public List<QuickLaunchItem> getQuickLaunchItems() {
        String json = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                SETTING_QUICK_LAUNCH_ITEMS, UserHandle.USER_CURRENT);
        List<QuickLaunchItem> items = QuickLaunchItem.parseJson(json);
        if (isAllEmpty(items)) {
            items = createInitialDefaultItems();
        }
        for (QuickLaunchItem item : items) {
            loadItemIcon(item);
        }
        return items;
    }

    private boolean isAllEmpty(List<QuickLaunchItem> items) {
        if (items == null || items.isEmpty()) return true;
        for (QuickLaunchItem item : items) {
            if (!item.isItemEmpty()) return false;
        }
        return true;
    }

    private List<QuickLaunchItem> createInitialDefaultItems() {
        List<QuickLaunchItem> items = new ArrayList<>();
        for (int i = 0; i < QuickLaunchItem.MAX_ITEMS; i++) {
            items.add(new QuickLaunchItem(QuickLaunchItem.VIEW_TYPE_EMPTY, i));
        }

                Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        ResolveInfo cameraInfo = mPackageManager.resolveActivity(cameraIntent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (cameraInfo != null && cameraInfo.activityInfo != null) {
            QuickLaunchItem camera = items.get(2);
            camera.setViewType(QuickLaunchItem.VIEW_TYPE_APP);
            camera.setPackageName(cameraInfo.activityInfo.packageName);
            camera.setClassName(cameraInfo.activityInfo.name);
            camera.setTitle(cameraInfo.loadLabel(mPackageManager).toString());
            camera.setItemEmpty(false);
        }

                Intent dialerIntent = new Intent(Intent.ACTION_DIAL);
        ResolveInfo dialerInfo = mPackageManager.resolveActivity(dialerIntent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (dialerInfo != null && dialerInfo.activityInfo != null) {
            QuickLaunchItem dialer = items.get(1);
            dialer.setViewType(QuickLaunchItem.VIEW_TYPE_APP);
            dialer.setPackageName(dialerInfo.activityInfo.packageName);
            dialer.setClassName(dialerInfo.activityInfo.name);
            dialer.setTitle(dialerInfo.loadLabel(mPackageManager).toString());
            dialer.setItemEmpty(false);
        }

                Intent calcIntent = new Intent();
        calcIntent.setAction(Intent.ACTION_MAIN);
        calcIntent.addCategory(Intent.CATEGORY_APP_CALCULATOR);
        ResolveInfo calcInfo = mPackageManager.resolveActivity(calcIntent,
                PackageManager.MATCH_DEFAULT_ONLY);
        if (calcInfo != null && calcInfo.activityInfo != null) {
            QuickLaunchItem calc = items.get(3);
            calc.setViewType(QuickLaunchItem.VIEW_TYPE_APP);
            calc.setPackageName(calcInfo.activityInfo.packageName);
            calc.setClassName(calcInfo.activityInfo.name);
            calc.setTitle(calcInfo.loadLabel(mPackageManager).toString());
            calc.setItemEmpty(false);
        }

        return items;
    }

    public void loadItemIcon(QuickLaunchItem item) {
        if (item.isItemEmpty()) {
            item.setIcon(ContextCompat.getDrawable(mContext, R.drawable.kgd_fp_quick_launch_empty_icon));
            item.setSubIcon(null);
            return;
        }

        if (item.getViewType() == QuickLaunchItem.VIEW_TYPE_APP) {
            try {
                ComponentName cn = new ComponentName(item.getPackageName(), item.getClassName());
                Drawable icon = mPackageManager.getActivityIcon(cn);
                item.setIcon(ensureCircleShape(icon));
            } catch (Exception e) {
                try {
                    item.setIcon(ensureCircleShape(mPackageManager.getApplicationIcon(item.getPackageName())));
                } catch (Exception ex) {
                    item.setIcon(ContextCompat.getDrawable(mContext, R.drawable.kgd_fp_quick_launch_empty_icon));
                }
            }
            item.setSubIcon(null);
        } else if (item.getViewType() == QuickLaunchItem.VIEW_TYPE_SHORTCUT) {
            Drawable shortcutIcon = null;
            try {
                LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery();
                query.setPackage(item.getPackageName());
                query.setShortcutIds(Collections.singletonList(item.getShortcutId()));
                query.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                        | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                        | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
                List<ShortcutInfo> shortcuts = mLauncherApps.getShortcuts(query,
                        UserHandle.of(item.getUserId()));
                if (shortcuts != null && !shortcuts.isEmpty()) {
                    ShortcutInfo info = shortcuts.get(0);
                    shortcutIcon = mLauncherApps.getShortcutIconDrawable(info, mDensityDpi);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed loading shortcut icon for " + item.getShortcutId(), e);
            }

            if (shortcutIcon != null) {
                item.setIcon(ensureCircleShape(shortcutIcon));
            } else {
                try {
                    item.setIcon(ensureCircleShape(mPackageManager.getApplicationIcon(item.getPackageName())));
                } catch (Exception e) {
                    item.setIcon(ContextCompat.getDrawable(mContext, R.drawable.kgd_fp_quick_launch_empty_icon));
                }
            }

                        if (TextUtils.isEmpty(item.getSubTitle()) && !TextUtils.isEmpty(item.getPackageName())) {
                try {
                    String appLabel = mPackageManager.getApplicationLabel(
                            mPackageManager.getApplicationInfo(item.getPackageName(), 0)).toString();
                    if (!TextUtils.isEmpty(appLabel) && !appLabel.equalsIgnoreCase(item.getTitle())) {
                        item.setSubTitle(appLabel);
                    }
                } catch (Exception ignored) {
                }
            }

            try {
                item.setSubIcon(ensureCircleShape(mPackageManager.getApplicationIcon(item.getPackageName())));
            } catch (Exception e) {
                item.setSubIcon(null);
            }
        }
    }

    private Drawable ensureCircleShape(Drawable icon) {
        if (icon == null) {
            return null;
        }
        if (icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable aid = (AdaptiveIconDrawable) icon;
            if (aid.getBackground() != null) {
                return icon;
            }
            Drawable fg = aid.getForeground();
            if (fg != null) {
                return new AdaptiveIconDrawable(new ColorDrawable(Color.WHITE), fg);
            }
        }
        float inset = AdaptiveIconDrawable.getExtraInsetFraction();
        return new AdaptiveIconDrawable(new ColorDrawable(Color.WHITE), new InsetDrawable(icon, inset));
    }
}
