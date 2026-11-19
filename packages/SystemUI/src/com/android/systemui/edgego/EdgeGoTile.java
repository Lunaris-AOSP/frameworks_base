/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.edgego;

import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.lunaris.EdgeGoConfig;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.UserSettingObserver;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

public final class EdgeGoTile extends QSTileImpl<BooleanState> {
    public static final String TILE_SPEC = "edgego";
    private final UserSettingObserver setting;
    private final ContentObserver observer;
    private int user;
    private boolean observing;

    @Inject
    public EdgeGoTile(QSHost host, QsEventLogger logger, @Background Looper looper, @Main Handler main,
            FalsingManager falsing, MetricsLogger metrics, StatusBarStateController statusBar,
            ActivityStarter starter, QSLogger qsLogger, UserTracker users, SecureSettings secure) {
        super(host, logger, looper, main, falsing, metrics, statusBar, starter, qsLogger);
        user = users.getUserId();
        setting = new UserSettingObserver(secure, mHandler, EdgeGoConfig.ENABLED, user) {
            @Override protected void handleValueChanged(int value, boolean changed) { handleRefreshState(value); }
        };
        observer = new ContentObserver(mHandler) {
            @Override public void onChange(boolean selfChange) { refreshState(); }
        };
    }

    @Override public BooleanState newTileState() { return new BooleanState(); }
    @Override protected void handleClick(Expandable expandable) { setting.setValue(setting.getValue() == 0 ? 1 : 0); }
    @Override public CharSequence getTileLabel() { return mContext.getString(R.string.edgego_title); }
    @Override public int getMetricsCategory() { return 0; }
    @Override public Intent getLongClickIntent() {
        Bundle args = new Bundle(); args.putBoolean("edgego_open", true);
        return new Intent().setClassName("com.android.settings", "com.android.settings.Settings$LunarisSettingsActivity")
                .putExtra(":settings:show_fragment_args", args);
    }
    @Override public void handleSetListening(boolean listening) {
        super.handleSetListening(listening); setting.setListening(listening);
        if (listening && !observing) {
            for (String key : new String[]{EdgeGoConfig.CONFIG, EdgeGoGestureController.GAME_ACTIVE}) {
                mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key), false, observer, UserHandle.USER_ALL);
            }
            observing = true;
        } else if (!listening && observing) { mContext.getContentResolver().unregisterContentObserver(observer); observing = false; }
    }
    @Override protected void handleUserSwitch(int user) { this.user = user; setting.setUserId(user); refreshState(); }
    @Override protected void handleDestroy() { handleSetListening(false); super.handleDestroy(); }
    @Override protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = setting.getValue() != 0;
        state.label = getTileLabel(); state.icon = maybeLoadResourceIcon(R.drawable.ic_edgego_gesture);
        EdgeGoConfig config = new EdgeGoConfig(mContext.getContentResolver(), user);
        boolean gaming = config.bool("disable_in_gaming_mode") && Settings.Secure.getIntForUser(
                mContext.getContentResolver(), EdgeGoGestureController.GAME_ACTIVE, 0, user) == 1;
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.secondaryLabel = state.value && gaming ? mContext.getString(R.string.edgego_paused_gamespace) : null;
        state.contentDescription = state.label;
        state.expandedAccessibilityClassName = Switch.class.getName();
    }
}
