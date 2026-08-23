/*
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2017 The LineageOS Project
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

package com.android.systemui.qs.tiles;

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SystemSettings;
import com.android.systemui.util.settings.SettingObserver;

import javax.inject.Inject;

/** Quick settings tile: Heads up **/
public class HeadsUpTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "heads_up";

    @Nullable
    private Icon mIcon = null;

    private static final Intent NOTIFICATION_SETTINGS =
            new Intent("android.settings.NOTIFICATION_SETTINGS");

    private final SettingObserver mSetting;
    private final SettingObserver mLessBoringSetting;

    @Inject
    public HeadsUpTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            GlobalSettings globalSettings,
            SystemSettings systemSettings,
            UserTracker userTracker
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mSetting = new SettingObserver(globalSettings, mHandler,
                Global.HEADS_UP_NOTIFICATIONS_ENABLED, userTracker.getUserId()) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };

        mLessBoringSetting = new SettingObserver(systemSettings, mHandler,
                Settings.System.LESS_BORING_HEADS_UP, userTracker.getUserId()) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        final boolean headsUpEnabled = mSetting.getValue() != 0;
        final boolean lessBoring = mLessBoringSetting.getValue() != 0;

        if (!headsUpEnabled) {
            setEnabled(true);
            setLessBoring(false);
        } else if (!lessBoring) {
            setEnabled(true);
            setLessBoring(true);
        } else {
            setEnabled(false);
            setLessBoring(false);
        }
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return NOTIFICATION_SETTINGS;
    }

    private void setEnabled(boolean enabled) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                enabled ? 1 : 0);
    }

    private void setLessBoring(boolean enabled) {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.LESS_BORING_HEADS_UP,
                enabled ? 1 : 0, UserHandle.USER_CURRENT);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean headsUp = mSetting.getValue() != 0;
        final boolean lessBoring = mLessBoringSetting.getValue() != 0;
        state.value = headsUp;
        state.label = mContext.getString(R.string.quick_settings_heads_up_label);
        if (mIcon == null) {
            mIcon = maybeLoadResourceIcon(R.drawable.ic_qs_heads_up);
        }
        state.icon = mIcon;
        if (headsUp && lessBoring) {
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_heads_up_less_boring);
            state.secondaryLabel = mContext.getString(
                    R.string.quick_settings_heads_up_less_boring_label);
            state.state = Tile.STATE_ACTIVE;
        } else if (headsUp) {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_heads_up_on);
            state.secondaryLabel = null;
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_heads_up_off);
            state.secondaryLabel = null;
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_heads_up_label);
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    @Override
    public void handleSetListening(boolean listening) {
        // Do nothing
    }
}
