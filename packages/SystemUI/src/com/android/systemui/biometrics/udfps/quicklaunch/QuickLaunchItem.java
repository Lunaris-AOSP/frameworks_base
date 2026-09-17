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

import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class QuickLaunchItem {

    public static final int MAX_ITEMS = 5;

    public static final int VIEW_TYPE_EMPTY = 0;
    public static final int VIEW_TYPE_APP = 1;
    public static final int VIEW_TYPE_SHORTCUT = 2;

    public static final String KEY_PACKAGE_NAME = "packageName";
    public static final String KEY_CLASS_NAME = "className";
    public static final String KEY_SHORTCUT_ID = "shortcutId";
    public static final String KEY_TITLE = "title";
    public static final String KEY_USER_ID = "userId";
    public static final String KEY_POSITION = "position";
    public static final String KEY_VIEW_TYPE = "viewType";
    public static final String KEY_APP_LABEL = "appLabel";

    private String mPackageName = "";
    private String mClassName = "";
    private String mShortcutId = "";
    private String mTitle = "";
    private String mSubTitle = "";
    private int mUserId = 0;
    private int mPosition = 0;
    private int mViewType = VIEW_TYPE_EMPTY;
    private boolean mIsClone = false;
    private boolean mItemEmpty = true;

        private FrameLayout mImageLayout;
    private ImageView mMainImageView;
    private ImageView mSecondImageView;
    private Drawable mIcon;
    private Drawable mSubIcon;

    public QuickLaunchItem() {
        this(VIEW_TYPE_EMPTY, 0);
    }

    public QuickLaunchItem(int viewType, int position) {
        mViewType = viewType;
        mPosition = position;
        mItemEmpty = (viewType == VIEW_TYPE_EMPTY);
    }

    public static List<QuickLaunchItem> parseJson(String json) {
        List<QuickLaunchItem> list = new ArrayList<>();
        for (int i = 0; i < MAX_ITEMS; i++) {
            list.add(new QuickLaunchItem(VIEW_TYPE_EMPTY, i));
        }
        if (TextUtils.isEmpty(json)) {
            return list;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                int pos = obj.optInt(KEY_POSITION, i);
                if (pos >= 0 && pos < MAX_ITEMS) {
                    QuickLaunchItem item = list.get(pos);
                    item.setViewType(obj.optInt(KEY_VIEW_TYPE, VIEW_TYPE_EMPTY));
                    item.setPackageName(obj.optString(KEY_PACKAGE_NAME, ""));
                    item.setClassName(obj.optString(KEY_CLASS_NAME, ""));
                    item.setShortcutId(obj.optString(KEY_SHORTCUT_ID, ""));
                    item.setTitle(obj.optString(KEY_TITLE, ""));
                    item.setSubTitle(obj.optString(KEY_APP_LABEL, ""));
                    item.setUserId(obj.optInt(KEY_USER_ID, 0));
                    item.setItemEmpty(item.getViewType() == VIEW_TYPE_EMPTY
                            || TextUtils.isEmpty(item.getPackageName()));
                }
            }
        } catch (Exception e) {
        }
        return list;
    }

    public int getPosition() {
        return mPosition;
    }

    public void setPosition(int position) {
        mPosition = position;
    }

    public int getViewType() {
        return mViewType;
    }

    public void setViewType(int viewType) {
        mViewType = viewType;
        mItemEmpty = (viewType == VIEW_TYPE_EMPTY);
    }

    public String getPackageName() {
        return mPackageName;
    }

    public void setPackageName(String packageName) {
        mPackageName = packageName != null ? packageName : "";
    }

    public String getClassName() {
        return mClassName;
    }

    public void setClassName(String className) {
        mClassName = className != null ? className : "";
    }

    public String getShortcutId() {
        return mShortcutId;
    }

    public void setShortcutId(String shortcutId) {
        mShortcutId = shortcutId != null ? shortcutId : "";
    }

    public String getTitle() {
        return mTitle;
    }

    public void setTitle(String title) {
        mTitle = title != null ? title : "";
    }

    public String getSubTitle() {
        return mSubTitle;
    }

    public void setSubTitle(String subTitle) {
        mSubTitle = subTitle != null ? subTitle : "";
    }

    public int getUserId() {
        return mUserId;
    }

    public void setUserId(int userId) {
        mUserId = userId;
    }

    public boolean isClone() {
        return mIsClone;
    }

    public void setClone(boolean clone) {
        mIsClone = clone;
    }

    public boolean isItemEmpty() {
        return mItemEmpty || mViewType == VIEW_TYPE_EMPTY || TextUtils.isEmpty(mPackageName);
    }

    public void setItemEmpty(boolean itemEmpty) {
        mItemEmpty = itemEmpty;
    }

    public FrameLayout getImageLayout() {
        return mImageLayout;
    }

    public void setImageLayout(FrameLayout imageLayout) {
        mImageLayout = imageLayout;
    }

    public ImageView getMainImageView() {
        return mMainImageView;
    }

    public void setMainImageView(ImageView mainImageView) {
        mMainImageView = mainImageView;
    }

    public ImageView getSecondImageView() {
        return mSecondImageView;
    }

    public void setSecondImageView(ImageView secondImageView) {
        mSecondImageView = secondImageView;
    }

    public Drawable getIcon() {
        return mIcon;
    }

    public void setIcon(Drawable icon) {
        mIcon = icon;
    }

    public Drawable getSubIcon() {
        return mSubIcon;
    }

    public void setSubIcon(Drawable subIcon) {
        mSubIcon = subIcon;
    }
}
