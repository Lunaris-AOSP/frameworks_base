/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.CallSuper;

import com.android.internal.widget.LockscreenCredential;
import com.android.systemui.res.R;

/**
 * Base class for PIN and password unlock screens.
 */
public abstract class KeyguardAbsKeyInputView extends KeyguardInputView {
    protected View mEcaView;

    // To avoid accidental lockout due to events while the device in the pocket, ignore
    // any passwords with length less than or equal to this length.
    protected static final int MINIMUM_PASSWORD_LENGTH_BEFORE_REPORT = 3;
    private KeyDownListener mKeyDownListener;
    private SettingsObserver mSettingsObserver;

    public KeyguardAbsKeyInputView(Context context) {
        this(context, null);
    }

    public KeyguardAbsKeyInputView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    protected abstract int getPasswordTextViewId();
    protected abstract void resetState();

    @Override
    @CallSuper
    protected void onFinishInflate() {
        super.onFinishInflate();
        mEcaView = findViewById(R.id.keyguard_selector_fade_container);
        
        updateEmergencyButtonVisibility();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(new Handler(Looper.getMainLooper()));
            mSettingsObserver.observe();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mSettingsObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
            mSettingsObserver = null;
        }
    }

    private void updateEmergencyButtonVisibility() {
        if (mEcaView != null) {
            boolean showButton = Settings.Secure.getInt(
                    mContext.getContentResolver(),
                    Settings.Secure.LOCKSCREEN_SHOW_EMERGENCY_BUTTON,
                    1) == 1;
            mEcaView.setVisibility(showButton ? View.VISIBLE : View.GONE);
        }
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.LOCKSCREEN_SHOW_EMERGENCY_BUTTON),
                    false, this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateEmergencyButtonVisibility();
        }
    }

    /*
     * Override this if you have a different string for "wrong password"
     *
     * Note that PIN/PUK have their own implementation of verifyPasswordAndUnlock and so don't need this
     */
    protected int getWrongPasswordStringId() {
        return R.string.kg_wrong_password;
    }

    protected abstract void resetPasswordText(boolean animate, boolean announce);
    protected abstract LockscreenCredential getEnteredCredential();
    protected abstract void setPasswordEntryEnabled(boolean enabled);
    protected abstract void setPasswordEntryInputEnabled(boolean enabled);

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mKeyDownListener != null && mKeyDownListener.onKeyDown(keyCode, event);
    }

    protected abstract int getPromptReasonStringRes(int reason);

    // Cause a VIRTUAL_KEY vibration
    public void doHapticKeyClick() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
    }

    public void setKeyDownListener(KeyDownListener keyDownListener) {
        mKeyDownListener = keyDownListener;
    }

    public interface KeyDownListener {
        boolean onKeyDown(int keyCode, KeyEvent keyEvent);
    }
}

