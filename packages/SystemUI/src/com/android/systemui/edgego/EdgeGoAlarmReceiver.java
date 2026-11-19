/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.edgego;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.UserHandle;

import com.android.internal.util.lunaris.EdgeGoConfig;
import com.android.systemui.res.R;

import org.json.JSONObject;

public final class EdgeGoAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int user = intent.getIntExtra("user", -1);
        if (user < 0 || user != ActivityManager.getCurrentUser()) return;
        EdgeGoConfig config = new EdgeGoConfig(context.getContentResolver(), user);
        if (!config.enabled) return;
        JSONObject state = EdgeGoSchedules.state(context, user);
        if (!state.optString("alarmToken").equals(intent.getStringExtra("token"))) return;
        int type = intent.getIntExtra("type", 0);
        if (type == EdgeGoSchedules.DIM_ON || type == EdgeGoSchedules.DIM_OFF) {
            if (!config.bool(type == EdgeGoSchedules.DIM_ON ? "extra_dim_schedule_start_enabled"
                    : "extra_dim_schedule_stop_enabled")) return;
            EdgeGoSchedules.applyDim(context, config, user, type == EdgeGoSchedules.DIM_ON);
            EdgeGoSchedules.scheduleDim(context, config, user);
            return;
        }
        if (type != EdgeGoSchedules.TIMER) return;
        JSONObject timer = state.optJSONObject("timer");
        if (timer == null || !timer.optString("status").equals("running")
                || EdgeGoSchedules.remaining(context, timer) > 1000) return;
        EdgeGoConfig.put(timer, "status", "finished");
        EdgeGoConfig.put(timer, "remaining", 0);
        EdgeGoSchedules.save(context, user, state);
        Context userContext = context.createContextAsUser(UserHandle.of(user), 0);
        NotificationManager manager = userContext.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel("edgego_timer", context.getString(R.string.edgego_timer),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
        PendingIntent clock = PendingIntent.getActivityAsUser(context, 2000 + user,
                new Intent("android.intent.action.SHOW_TIMERS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, null, UserHandle.of(user));
        Notification notification = new Notification.Builder(userContext, "edgego_timer")
                .setSmallIcon(R.drawable.ic_edgego_gesture).setContentTitle(context.getString(R.string.edgego_timer_finished))
                .setCategory(Notification.CATEGORY_ALARM).setContentIntent(clock).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setTimeoutAfter(60000).build();
        manager.notifyAsUser("EdgeGoTimer", 1, notification, UserHandle.of(user));
    }
}
