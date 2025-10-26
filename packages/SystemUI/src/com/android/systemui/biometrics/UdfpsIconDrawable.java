/*
 * Copyright (C) 2024-2025 crDroid Android Project
 * Copyright (C) 2024-2025 Lunaris AOSP
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

package com.android.systemui.biometrics;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import com.android.internal.util.lunaris.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.res.R;
import com.android.systemui.tuner.TunerService;

import java.io.File;
import java.io.IOException;

/**
 * Abstract base class for drawable displayed when the finger is not touching the
 * sensor area.
 */
public abstract class UdfpsIconDrawable extends Drawable {

    private static final String TAG = "UdfpsIconDrawable";
    private static final boolean DEBUG = false;
    
    private static final String UDFPS_ICON = "system:" + Settings.System.UDFPS_ICON;
    private static final String UDFPS_ICON_TYPE = "system:" + Settings.System.UDFPS_ICON_TYPE;
    private static final String UDFPS_CUSTOM_ICON_PATH = "system:" + Settings.System.UDFPS_CUSTOM_FP_ICON_PATH;
    
    private static final int ICON_TYPE_PREBUILT = 0;
    private static final int ICON_TYPE_CUSTOM = 1;
    private static final int MAX_IMAGE_SIZE = 2 * 1024 * 1024;
    private static final int MAX_DIMENSION = 400;
    
    private final String udfpsResourcesPackage = "com.lunaris.udfps.icons";
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @NonNull private final Context mContext;
    private Drawable mUdfpsDrawable;
    private Resources udfpsRes;
    private String[] mUdfpsIcons;
    private String mLastLoadedCustomPath = null;
    private int mCurrentIconType = ICON_TYPE_PREBUILT;
    private boolean mIsVisible = true;
    private boolean mIsDestroyed = false;

    private TunerService.Tunable mTunable;

    public UdfpsIconDrawable(@NonNull Context context) {
        mContext = context;
        init();
    }

    private void init() {
        if (Utils.isPackageInstalled(mContext, udfpsResourcesPackage)) {
            try {
                PackageManager pm = mContext.getPackageManager();
                udfpsRes = pm.getResourcesForApplication(udfpsResourcesPackage);
                int res = udfpsRes.getIdentifier("udfps_icons", "array", udfpsResourcesPackage);
                mUdfpsIcons = udfpsRes.getStringArray(res);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "UDFPS package not found", e);
            }
        }

        mCurrentIconType = Settings.System.getIntForUser(
                mContext.getContentResolver(),
                Settings.System.UDFPS_ICON_TYPE,
                ICON_TYPE_PREBUILT,
                UserHandle.USER_CURRENT
        );

        mTunable = (key, newValue) -> {
            if (mIsDestroyed) return;
            
            if (UDFPS_ICON_TYPE.equals(key)) {
                int iconType = newValue == null ? ICON_TYPE_PREBUILT : Integer.parseInt(newValue);
                mCurrentIconType = iconType;

                if (mCurrentIconType == ICON_TYPE_PREBUILT) {
                    runOnMainThread(() -> {
                        if (!mIsDestroyed) {
                            Settings.System.putStringForUser(
                                    mContext.getContentResolver(),
                                    Settings.System.UDFPS_CUSTOM_FP_ICON_PATH,
                                    null,
                                    UserHandle.USER_CURRENT
                            );
                        }
                    });
                    mLastLoadedCustomPath = null;
                }

                updateIcon();
            } else if (UDFPS_ICON.equals(key)) {
                if (mCurrentIconType == ICON_TYPE_PREBUILT) {
                    updateIcon();
                }
            } else if (UDFPS_CUSTOM_ICON_PATH.equals(key)) {
                String newPath = newValue;
                if (mCurrentIconType == ICON_TYPE_CUSTOM &&
                    (newPath == null || !newPath.equals(mLastLoadedCustomPath))) {
                    mLastLoadedCustomPath = null;
                    updateIcon();
                }
            }
        };

        Dependency.get(TunerService.class).addTunable(
                mTunable,
                UDFPS_ICON_TYPE,
                UDFPS_ICON,
                UDFPS_CUSTOM_ICON_PATH
        );
        
        updateIcon();
    }

    private void runOnMainThread(Runnable runnable) {
        if (mIsDestroyed) return;
        
        if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
            runnable.run();
        } else {
            mMainHandler.post(runnable);
        }
    }

    private void updateIcon() {
        if (mIsDestroyed) return;
        
        Drawable oldDrawable = mUdfpsDrawable;
        
        if (mCurrentIconType == ICON_TYPE_CUSTOM) {
            loadCustomIcon();
        } else {
            loadPrebuiltIcon();
        }
        
        runOnMainThread(() -> {
            if (!mIsDestroyed) {
                invalidateSelf();
                if (oldDrawable != null && oldDrawable != mUdfpsDrawable) {
                    mMainHandler.postDelayed(() -> cleanupDrawable(oldDrawable), 100);
                }
            }
        });
    }

    private void loadPrebuiltIcon() {
        int selectedIcon = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.UDFPS_ICON, 0,
                UserHandle.USER_CURRENT);
        
        if (selectedIcon == 0 || udfpsRes == null || mUdfpsIcons == null) {
            mUdfpsDrawable = null;
            return;
        }
        
        mUdfpsDrawable = loadDrawable(udfpsRes, mUdfpsIcons[selectedIcon]);
    }

    public void onVisibilityChanged(boolean visible) {
        if (mIsDestroyed) return;
        
        mIsVisible = visible;
        if (mUdfpsDrawable instanceof AnimatedImageDrawable) {
            AnimatedImageDrawable anim = (AnimatedImageDrawable) mUdfpsDrawable;
            try {
                if (visible && !anim.isRunning()) {
                    anim.start();
                    if (DEBUG) Log.d(TAG, "Animation started - visibility changed");
                } else if (!visible && anim.isRunning()) {
                    anim.stop();
                    if (DEBUG) Log.d(TAG, "Animation stopped - visibility changed");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling animation visibility", e);
            }
        }
    }

    private void loadCustomIcon() {
        String path = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.UDFPS_CUSTOM_FP_ICON_PATH,
                UserHandle.USER_CURRENT);
        
        if (path == null || path.isEmpty()) {
            if (DEBUG) Log.d(TAG, "No custom icon path set");
            mUdfpsDrawable = null;
            return;
        }

        if (path.equals(mLastLoadedCustomPath) && mUdfpsDrawable != null) {
            if (DEBUG) Log.d(TAG, "Custom icon already loaded for path: " + path);
            return;
        }

        if (DEBUG) Log.d(TAG, "Loading custom icon from: " + path);
        
        File imageFile = new File(path);
        if (!isValidImageFile(imageFile)) {
            Log.w(TAG, "Custom icon file validation failed: " + path);
            mUdfpsDrawable = null;
            return;
        }

        mLastLoadedCustomPath = path;

        String extension = getFileExtension(path);
        boolean isAnimated = extension.matches("\\.(gif|webp)$");

        if (isAnimated) {
            if (loadAnimatedCustomIcon(imageFile, path)) {
                if (DEBUG) Log.d(TAG, "Successfully loaded animated custom icon");
                return;
            }
            if (DEBUG) Log.d(TAG, "Falling back to static image loading for: " + path);
        }
        
        loadStaticCustomIcon(path);
        if (mUdfpsDrawable != null) {
            if (DEBUG) Log.d(TAG, "Successfully loaded static custom icon");
        } else {
            Log.w(TAG, "Failed to load custom icon from: " + path);
            mLastLoadedCustomPath = null;
        }
    }

    private boolean isValidImageFile(File imageFile) {
        if (!imageFile.exists()) {
            Log.w(TAG, "Custom icon file does not exist: " + imageFile.getAbsolutePath());
            return false;
        }
        
        if (!imageFile.canRead()) {
            Log.w(TAG, "Custom icon file is not readable: " + imageFile.getAbsolutePath());
            return false;
        }
        
        long fileSize = imageFile.length();
        if (fileSize > MAX_IMAGE_SIZE) {
            Log.w(TAG, "Custom icon file too large: " + fileSize + " bytes");
            return false;
        }
        
        if (fileSize == 0) {
            Log.w(TAG, "Custom icon file is empty");
            return false;
        }
        
        String name = imageFile.getName().toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg") || 
               name.endsWith(".jpeg") || name.endsWith(".gif") || 
               name.endsWith(".webp");
    }

    private String getFileExtension(String path) {
        if (path == null) return "";
        String lower = path.toLowerCase();
        if (lower.endsWith(".gif")) return ".gif";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return ".jpg";
        return "";
    }

    private boolean loadAnimatedCustomIcon(File imageFile, String path) {
        try {
            android.graphics.ImageDecoder.Source source = 
                    android.graphics.ImageDecoder.createSource(imageFile);
            Drawable drawable = android.graphics.ImageDecoder.decodeDrawable(source);
            
            if (drawable == null) {
                Log.w(TAG, "ImageDecoder returned null drawable for: " + path);
                return false;
            }
            
            mUdfpsDrawable = drawable;
            
            if (drawable instanceof AnimatedImageDrawable) {
                AnimatedImageDrawable animDrawable = (AnimatedImageDrawable) drawable;
                animDrawable.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                
                if (mIsVisible && !animDrawable.isRunning()) {
                    animDrawable.start();
                    if (DEBUG) Log.d(TAG, "Animation started for custom icon: " + path);
                }
            }
            
            if (DEBUG) Log.d(TAG, "Animated custom icon loaded successfully: " + path);
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "IOException loading animated custom icon: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error loading animated custom icon: " + e.getMessage());
            return false;
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int maxSize) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        
        if (height > maxSize || width > maxSize) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            
            while ((halfHeight / inSampleSize) >= maxSize && (halfWidth / inSampleSize) >= maxSize) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private void loadStaticCustomIcon(String path) {
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.w(TAG, "Invalid custom icon dimensions");
                mUdfpsDrawable = null;
                return;
            }
            
            options.inSampleSize = calculateInSampleSize(options, MAX_DIMENSION);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inMutable = false;
            
            bitmap = BitmapFactory.decodeFile(path, options);
            if (bitmap == null || bitmap.isRecycled() || 
                bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
                Log.w(TAG, "Failed to decode custom icon bitmap");
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                mUdfpsDrawable = null;
                return;
            }
            
            Bitmap immutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (immutableBitmap == null) {
                Log.w(TAG, "Failed to create immutable copy of bitmap");
                if (!bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                mUdfpsDrawable = null;
                return;
            }
            
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
            
            mUdfpsDrawable = new BitmapDrawable(mContext.getResources(), immutableBitmap);
            if (DEBUG) Log.d(TAG, "Static custom icon loaded successfully: " + path);
            
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "OutOfMemoryError loading static custom icon: " + e.getMessage());
            if (bitmap != null && !bitmap.isRecycled()) {
                try {
                    bitmap.recycle();
                } catch (Exception ex) {
                }
            }
            mUdfpsDrawable = null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load static custom icon: " + e.getMessage());
            if (bitmap != null && !bitmap.isRecycled()) {
                try {
                    bitmap.recycle();
                } catch (Exception ex) {
                }
            }
            mUdfpsDrawable = null;
        }
    }

    private void cleanupDrawable(Drawable drawable) {
        if (drawable == null || drawable == mUdfpsDrawable) {
            return;
        }
        
        try {
            if (drawable instanceof AnimatedImageDrawable) {
                AnimatedImageDrawable animDrawable = (AnimatedImageDrawable) drawable;
                if (animDrawable.isRunning()) {
                    animDrawable.stop();
                }
            }
            
            drawable.setCallback(null);
            
            if (drawable instanceof BitmapDrawable) {
                BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
                Bitmap bitmap = bitmapDrawable.getBitmap();
                if (bitmap != null && !bitmap.isRecycled()) {
                    if (DEBUG) Log.d(TAG, "Bitmap cleanup delegated to GC");
                }
            }
        } catch (Exception e) {
            if (DEBUG) Log.e(TAG, "Error during drawable cleanup", e);
        }
    }

    public void destroy() {
        mIsDestroyed = true;
        
        try {
            Dependency.get(TunerService.class).removeTunable(mTunable);
        } catch (Exception e) {
            Log.e(TAG, "Error removing tunable: " + e.getMessage());
        }
        
        mMainHandler.removeCallbacksAndMessages(null);
        
        if (mUdfpsDrawable != null) {
            cleanupDrawable(mUdfpsDrawable);
            mUdfpsDrawable = null;
        }
    }

    @Override
    public void setAlpha(int alpha) {
        if (mUdfpsDrawable != null && !mIsDestroyed) {
            mUdfpsDrawable.setAlpha(alpha);
            invalidateSelf();
        }
    }

    Drawable getUdfpsDrawable() {
        if (mUdfpsDrawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) mUdfpsDrawable).getBitmap();
            if (bitmap == null || bitmap.isRecycled()) {
                if (DEBUG) Log.w(TAG, "Drawable has recycled bitmap, returning null");
                mUdfpsDrawable = null;
                return null;
            }
        }
        return mUdfpsDrawable;
    }

    private Drawable loadDrawable(Resources res, String resName) {
        if (res == null || resName == null) {
            return null;
        }
        int resId = res.getIdentifier(resName, "drawable", udfpsResourcesPackage);
        return resId != 0 ? ResourcesCompat.getDrawable(res, resId, null) : null;
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        super.setBounds(left, top, right, bottom);
        if (mUdfpsDrawable != null && !mIsDestroyed) {
            mUdfpsDrawable.setBounds(left, top, right, bottom);
        }
    }

    @Override
    public void setBounds(Rect bounds) {
        super.setBounds(bounds);
        if (mUdfpsDrawable != null && !mIsDestroyed) {
            mUdfpsDrawable.setBounds(bounds);
        }
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        if (mUdfpsDrawable != null && !mIsDestroyed) {
            mUdfpsDrawable.setBounds(bounds);
        }
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return 0;
    }
}