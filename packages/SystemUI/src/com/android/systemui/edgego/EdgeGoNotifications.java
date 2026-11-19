/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.edgego;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.SystemClock;
import android.service.notification.NotificationStats;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.view.KeyEvent;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController;
import com.axion.applocker.AxAppLockerHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import javax.inject.Inject;

@SysUISingleton
public final class EdgeGoNotifications {
    private final Context context;
    private final NotifPipeline pipeline;
    private final NotifCollection collection;
    private final NotificationVisibilityProvider visibility;
    private final NotificationLockscreenUserManager lockscreen;
    private final KeyguardNotificationVisibilityProvider keyguardVisibility;
    private final SensitiveNotificationProtectionController protection;
    private final AxAppLockerHelper appLocker;
    private final KeyguardStateController keyguard;
    private final NotificationListener listener;
    private final ActivityStarter starter;
    private final IStatusBarService statusBar;
    private final UserManager users;
    private final Map<Runnable, NotificationLockscreenUserManager.NotificationStateChangedListener> privacyListeners = new HashMap<>();

    @Inject
    public EdgeGoNotifications(Context context, NotifPipeline pipeline, NotifCollection collection,
            NotificationVisibilityProvider visibility, NotificationLockscreenUserManager lockscreen,
            KeyguardNotificationVisibilityProvider keyguardVisibility, SensitiveNotificationProtectionController protection,
            AxAppLockerHelper appLocker, KeyguardStateController keyguard, NotificationListener listener,
            ActivityStarter starter, IStatusBarService statusBar) {
        this.context = context;
        this.pipeline = pipeline;
        this.collection = collection;
        this.visibility = visibility;
        this.lockscreen = lockscreen;
        this.keyguardVisibility = keyguardVisibility;
        this.protection = protection;
        this.appLocker = appLocker;
        this.keyguard = keyguard;
        this.listener = listener;
        this.starter = starter;
        this.statusBar = statusBar;
        users = context.getSystemService(UserManager.class);
    }

    List<NotificationEntry> entries(int user) {
        List<NotificationEntry> entries = new ArrayList<>();
        for (NotificationEntry entry : pipeline.getAllNotifs()) if (eligible(entry, user)) entries.add(entry);
        entries.sort(Comparator.comparingInt(entry -> entry.getRanking().getRank()));
        return entries;
    }

    private boolean eligible(NotificationEntry entry, int user) {
        if (user != lockscreen.getCurrentUserId()) return false;
        int profile = entry.getSbn().getUserId() == UserHandle.USER_ALL ? user : entry.getSbn().getUserId();
        return lockscreen.isCurrentProfile(profile) && lockscreen.isProfileAvailable(profile)
                && users.isUserUnlocked(UserHandle.of(profile)) && !users.isQuietModeEnabled(UserHandle.of(profile))
                && entry.getDismissState() == NotificationEntry.DismissState.NOT_DISMISSED
                && !entry.getRanking().isSuspended() && !keyguardVisibility.shouldHideNotification(entry);
    }

    boolean redacted(NotificationEntry entry) {
        int user = entry.getSbn().getUserId() == UserHandle.USER_ALL ? lockscreen.getCurrentUserId() : entry.getSbn().getUserId();
        return protection.shouldProtectNotification(entry) || appLocker.needsAuth(entry.getSbn().getPackageName(), user)
                || lockscreen.needsSeparateWorkChallenge(user) && lockscreen.isLockscreenPublicMode(user)
                || (keyguard.isShowing() || lockscreen.isLockscreenPublicMode(user))
                && lockscreen.getRedactionType(entry) != NotificationLockscreenUserManager.REDACTION_TYPE_NONE;
    }

    String title(NotificationEntry entry) {
        if (redacted(entry)) return context.getString(R.string.edgego_notification_hidden);
        CharSequence title = entry.getSbn().getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
        return title == null ? entry.getSbn().getPackageName() : title.toString();
    }

    String text(NotificationEntry entry) {
        if (redacted(entry)) return "";
        Bundle extras = entry.getSbn().getNotification().extras;
        CharSequence text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT, extras.getCharSequence(Notification.EXTRA_TEXT));
        return text == null ? "" : text.toString();
    }

    void open(String key, int user) {
        NotificationEntry entry = pipeline.getEntry(key);
        if (entry == null || !eligible(entry, user)) return;
        Notification notification = entry.getSbn().getNotification();
        PendingIntent intent = notification.contentIntent == null ? notification.fullScreenIntent : notification.contentIntent;
        if (intent != null) starter.startPendingIntentDismissingKeyguard(intent);
    }

    void dismiss(String key, int user) {
        NotificationEntry entry = pipeline.getEntry(key);
        if (entry == null || !eligible(entry, user) || !entry.getSbn().isClearable()) return;
        collection.dismissNotification(entry, new DismissedByUserStats(NotificationStats.DISMISSAL_OTHER,
                NotificationStats.DISMISS_SENTIMENT_NEUTRAL, visibility.obtain(entry, true)));
    }

    void snooze(String key, int user, int minutes) {
        NotificationEntry entry = pipeline.getEntry(key);
        if (minutes >= 15 && minutes <= 480 && entry != null && eligible(entry, user)
                && entry.getSbn().isClearable()) listener.snoozeNotification(key, minutes * 60000L);
    }

    void mediaKey(String key, int user, int keyCode) {
        MediaController controller = mediaController(key, user);
        if (controller == null) return;
        try {
            long now = SystemClock.uptimeMillis();
            controller.dispatchMediaButtonEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            controller.dispatchMediaButtonEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (RuntimeException ignored) { }
    }

    MediaController mediaController(String key, int user) {
        NotificationEntry entry = pipeline.getEntry(key);
        if (entry == null || !eligible(entry, user) || redacted(entry)) return null;
        try {
            MediaSession.Token token = entry.getSbn().getNotification().extras.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION, MediaSession.Token.class);
            return token == null ? null : new MediaController(context, token);
        } catch (RuntimeException ignored) { return null; }
    }

    boolean canReply(NotificationEntry entry) { return !keyguard.isShowing() && !redacted(entry) && replyAction(entry) != null; }

    private Notification.Action replyAction(NotificationEntry entry) {
        Notification.Action[] actions = entry.getSbn().getNotification().actions;
        if (actions == null) return null;
        for (Notification.Action action : actions) {
            if (action.actionIntent == null || action.getRemoteInputs() == null) continue;
            for (RemoteInput input : action.getRemoteInputs()) if (input.getAllowFreeFormInput()) return action;
        }
        return null;
    }

    boolean reply(String key, String text, int user) {
        NotificationEntry entry = pipeline.getEntry(key);
        if (entry == null || !eligible(entry, user) || !canReply(entry) || text.isBlank() || text.length() > 4096) return false;
        Notification.Action action = replyAction(entry);
        RemoteInput[] inputs = action.getRemoteInputs();
        Bundle results = new Bundle();
        for (RemoteInput input : inputs) if (input.getAllowFreeFormInput()) results.putCharSequence(input.getResultKey(), text);
        Intent fill = new Intent();
        RemoteInput.addResultsToIntent(inputs, fill, results);
        RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT);
        try {
            action.actionIntent.send(context, 0, fill);
            entry.remoteInputText = text;
            entry.setHasSentReply();
            statusBar.onNotificationDirectReplied(key);
            return true;
        } catch (Exception e) { return false; }
    }

    NotifPipeline pipeline() { return pipeline; }

    void addPrivacyListener(Runnable listener) {
        if (privacyListeners.containsKey(listener)) return;
        NotificationLockscreenUserManager.NotificationStateChangedListener stateListener = listener::run;
        privacyListeners.put(listener, stateListener);
        lockscreen.addNotificationStateChangedListener(stateListener);
        protection.registerSensitiveStateListener(listener);
    }

    void removePrivacyListener(Runnable listener) {
        NotificationLockscreenUserManager.NotificationStateChangedListener stateListener = privacyListeners.remove(listener);
        if (stateListener != null) lockscreen.removeNotificationStateChangedListener(stateListener);
        protection.unregisterSensitiveStateListener(listener);
    }

    boolean canShowMedia(String pkg, int user) {
        if (appLocker.needsAuth(pkg, user) || protection.isSensitiveStateActive()) return false;
        for (NotificationEntry entry : pipeline.getAllNotifs()) if (entry.getSbn().getPackageName().equals(pkg)
                && entry.getSbn().getNotification().isMediaNotification() && (!eligible(entry, user) || redacted(entry))) return false;
        return !keyguard.isShowing() || lockscreen.shouldShowLockscreenNotifications()
                && lockscreen.userAllowsNotificationsInPublic(user) && lockscreen.userAllowsPrivateNotificationsInPublic(user);
    }
}
