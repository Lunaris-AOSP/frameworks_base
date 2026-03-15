/*
 * Copyright (C) 2026 MistOS
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
package com.android.systemui.statusbar.notification;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

public class IosStyleNotificationHelper {
    
    private static final String IOS_STYLE_NOTIFICATIONS_KEY = "ios_style_notifications";
    private static final float REVEAL_THRESHOLD = 100f; // pixels to swipe up
    
    private final Context mContext;
    private ViewGroup mNotificationContainer;
    private boolean mEnabled;
    private float mStartY;
    private float mCurrentTranslation;
    private boolean mIsRevealed;
    private SettingsObserver mSettingsObserver;
    
    public IosStyleNotificationHelper(Context context) {
        mContext = context;
        mSettingsObserver = new SettingsObserver(new Handler(Looper.getMainLooper()));
        mSettingsObserver.observe();
        updateEnabled();
    }
    
    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }
        
        void observe() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(IOS_STYLE_NOTIFICATIONS_KEY),
                    false, this);
        }
        
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateEnabled();
        }
    }
    
    public void setNotificationContainer(ViewGroup container) {
        mNotificationContainer = container;
        if (mEnabled) {
            hideNotifications();
        }
    }
    
    public void updateEnabled() {
        boolean wasEnabled = mEnabled;
        mEnabled = Settings.System.getInt(mContext.getContentResolver(),
                 "ios_style_notifications", 0) == 1;
        
        // If state changed, update visibility immediately
        if (wasEnabled != mEnabled && mNotificationContainer != null) {
            if (mEnabled) {
                hideNotifications();
            } else {
                showNotifications();
            }
        }
    }
    
    public boolean isEnabled() {
        return mEnabled;
    }
    
    public void onKeyguardStateChanged(boolean showing) {
        if (!mEnabled || mNotificationContainer == null) return;
        
        if (showing) {
            hideNotifications();
        } else {
            showNotifications();
        }
    }
    
    public boolean onTouchEvent(MotionEvent event) {
        if (!mEnabled || mNotificationContainer == null || mIsRevealed) {
            return false;
        }
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mStartY = event.getRawY();
                return false;
                
            case MotionEvent.ACTION_MOVE:
                float deltaY = mStartY - event.getRawY();
                if (deltaY > 0) { // Swiping up
                    mCurrentTranslation = Math.max(0, mNotificationContainer.getHeight() - deltaY);
                    mNotificationContainer.setTranslationY(mCurrentTranslation);
                    mNotificationContainer.setAlpha(1f - (mCurrentTranslation / mNotificationContainer.getHeight()));
                    return true;
                }
                return false;
                
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float totalDelta = mStartY - event.getRawY();
                if (totalDelta > REVEAL_THRESHOLD) {
                    revealNotifications();
                } else {
                    hideNotifications();
                }
                return true;
        }
        return false;
    }
    
    private void hideNotifications() {
        if (mNotificationContainer == null) return;
        mIsRevealed = false;
        mNotificationContainer.animate()
                .translationY(mNotificationContainer.getHeight())
                .alpha(0f)
                .setDuration(200)
                .start();
    }
    
    private void revealNotifications() {
        if (mNotificationContainer == null) return;
        mIsRevealed = true;
        mNotificationContainer.animate()
                .translationY(0)
                .alpha(1f)
                .setDuration(200)
                .start();
    }
    
    private void showNotifications() {
        if (mNotificationContainer == null) return;
        mIsRevealed = true;
        mNotificationContainer.setTranslationY(0);
        mNotificationContainer.setAlpha(1f);
    }
}
