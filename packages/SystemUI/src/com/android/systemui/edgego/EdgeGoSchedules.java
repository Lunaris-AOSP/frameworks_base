/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.edgego;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.ColorDisplayManager;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.util.lunaris.EdgeGoConfig;
import com.android.systemui.qs.ReduceBrightColorsController;

import org.json.JSONObject;

import java.time.ZonedDateTime;
import java.util.UUID;

final class EdgeGoSchedules {
    static final int DIM_ON = 1, DIM_OFF = 2, TIMER = 3;
    private final Context context;
    private final AlarmManager alarms;
    private final EdgeGoActions.Host host;
    private final ReduceBrightColorsController dim;
    private int scheduledUser;

    EdgeGoSchedules(Context context, Handler handler, EdgeGoActions.Host host, ReduceBrightColorsController dim) {
        this.context = context;
        this.host = host;
        this.dim = dim;
        alarms = context.getSystemService(AlarmManager.class);
        scheduledUser = host.userId();
        IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { update(); }
        }, filter, null, handler, Context.RECEIVER_NOT_EXPORTED);
        dim.addCallback(new ReduceBrightColorsController.Listener() {
            @Override public void onActivated(boolean activated) {
                if (!activated && Settings.Secure.getIntForUser(context.getContentResolver(),
                        Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED, 0, host.userId()) == 0
                        && state(context, host.userId()).optBoolean("dimOwned")) {
                    applyDim(context, host.config(), host.userId(), false);
                }
            }
        });
    }

    void update() {
        scheduledUser = host.userId();
        EdgeGoConfig config = host.config();
        cancelDimAlarms(context, scheduledUser);
        if (!config.enabled) {
            if (timerStatus().equals("running")) pauseTimer();
            cancelOperation(context, scheduledUser, TIMER);
            if (state(context, scheduledUser).optBoolean("dimOwned")) applyDim(context, config, scheduledUser, false);
            return;
        }
        scheduleDim(context, config, scheduledUser);
        JSONObject state = state(context, scheduledUser);
        if (state.optBoolean("dimOwned")) applyDim(context, config, scheduledUser,
                Settings.Secure.getIntForUser(context.getContentResolver(), Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED, 0, scheduledUser) == 1);
        JSONObject timer = state.optJSONObject("timer");
        if (timer != null && timer.optString("status").equals("running")) {
            long end = System.currentTimeMillis() + remaining(context, timer);
            if (Math.abs(end - timer.optLong("endAt")) > 1000) {
                EdgeGoConfig.put(timer, "endAt", end);
                save(context, scheduledUser, state);
            }
            PendingIntent show = PendingIntent.getActivityAsUser(context, 1000 + scheduledUser,
                    new Intent("android.intent.action.SHOW_TIMERS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, null, UserHandle.of(scheduledUser));
            alarms.setAlarmClock(new AlarmManager.AlarmClockInfo(Math.max(System.currentTimeMillis(), end), show),
                    operation(context, scheduledUser, TIMER));
        }
    }

    void cancel() {
        cancelDimAlarms(context, scheduledUser);
        cancelOperation(context, scheduledUser, TIMER);
    }

    static JSONObject state(Context context, int user) {
        return EdgeGoConfig.object(Settings.Secure.getStringForUser(context.getContentResolver(), EdgeGoConfig.STATE, user));
    }

    static void save(Context context, int user, JSONObject state) {
        if (state.toString().length() <= EdgeGoConfig.MAX_JSON_LENGTH) Settings.Secure.putStringForUser(
                context.getContentResolver(), EdgeGoConfig.STATE, state.toString(), user);
    }

    static PendingIntent operation(Context context, int user, int type) {
        return operation(context, user, type, true);
    }

    private static PendingIntent operation(Context context, int user, int type, boolean create) {
        JSONObject state = state(context, user);
        String token = state.optString("alarmToken");
        if (token.isEmpty()) {
            if (!create) return null;
            token = UUID.randomUUID().toString();
            EdgeGoConfig.put(state, "alarmToken", token);
            save(context, user, state);
        }
        Intent intent = new Intent(context, EdgeGoAlarmReceiver.class).setAction("com.android.systemui.edgego.ALARM")
                .putExtra("user", user).putExtra("type", type).putExtra("token", token);
        return PendingIntent.getBroadcast(context, user * 16 + type, intent,
                (create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE) | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancelOperation(Context context, int user, int type) {
        PendingIntent operation = operation(context, user, type, false);
        if (operation != null) context.getSystemService(AlarmManager.class).cancel(operation);
    }

    static void cancelDimAlarms(Context context, int user) {
        cancelOperation(context, user, DIM_ON);
        cancelOperation(context, user, DIM_OFF);
    }

    static void scheduleDim(Context context, EdgeGoConfig config, int user) {
        if (!config.enabled) return;
        int start = config.integer("extra_dim_schedule_start_minute_of_day");
        int end = config.integer("extra_dim_schedule_stop_minute_of_day");
        boolean on = config.bool("extra_dim_schedule_start_enabled"), off = config.bool("extra_dim_schedule_stop_enabled");
        if (on && off && start == end) return;
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (on) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next(start), operation(context, user, DIM_ON));
        if (off) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next(end), operation(context, user, DIM_OFF));
    }

    private static long next(int minute) {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = now.withHour(minute / 60).withMinute(minute % 60).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        return next.toInstant().toEpochMilli();
    }

    void toggleDim() { applyDim(context, host.config(), host.userId(), !dim.isReduceBrightColorsActivated()); }

    static void applyDim(Context context, EdgeGoConfig config, int user, boolean active) {
        JSONObject state = state(context, user);
        boolean owned = state.optBoolean("dimOwned");
        if (active && !owned) {
            EdgeGoConfig.put(state, "dimPreviousStrength", Settings.Secure.getIntForUser(context.getContentResolver(),
                    Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL, 50, user));
            state.remove("nightReleased");
        }
        if (active) {
            int strength = config.integer("extra_dim_intensity_percent");
            if (!owned || strength != state.optInt("dimConfiguredStrength", -1)) {
                Settings.Secure.putIntForUser(context.getContentResolver(), Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL, strength, user);
                EdgeGoConfig.put(state, "dimAppliedStrength", strength);
            }
            EdgeGoConfig.put(state, "dimConfiguredStrength", strength);
            int warmth = config.integer("extra_dim_warmth_percent");
            int currentActive = Settings.Secure.getIntForUser(context.getContentResolver(), Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 0, user);
            int currentTemperature = Settings.Secure.getIntForUser(context.getContentResolver(), Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE, 4500, user);
            boolean nightOwned = state.optBoolean("nightOwned");
            if (nightOwned && (currentActive != 1 || currentTemperature != state.optInt("nightAppliedTemperature"))) {
                EdgeGoConfig.put(state, "nightOwned", false); EdgeGoConfig.put(state, "nightReleased", true); nightOwned = false;
            }
            if (warmth == 0 && nightOwned) releaseNight(context, user, state);
            else if (warmth > 0 && (!state.optBoolean("nightReleased") || warmth != state.optInt("dimConfiguredWarmth", -1))) {
                if (!nightOwned) {
                    EdgeGoConfig.put(state, "nightPreviousActive", currentActive);
                    EdgeGoConfig.put(state, "nightPreviousTemperature", currentTemperature);
                }
                int min = ColorDisplayManager.getMinimumColorTemperature(context), max = ColorDisplayManager.getMaximumColorTemperature(context);
                int temperature = max - (max - min) * warmth / 100;
                Settings.Secure.putIntForUser(context.getContentResolver(), Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE,
                        temperature, user);
                Settings.Secure.putIntForUser(context.getContentResolver(), Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 1, user);
                EdgeGoConfig.put(state, "nightOwned", true);
                EdgeGoConfig.put(state, "nightAppliedTemperature", temperature);
                EdgeGoConfig.put(state, "nightReleased", false);
            }
            EdgeGoConfig.put(state, "dimConfiguredWarmth", warmth);
        } else if (owned) {
            if (Settings.Secure.getIntForUser(context.getContentResolver(), Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL, 50, user)
                    == state.optInt("dimAppliedStrength")) {
                Settings.Secure.putIntForUser(context.getContentResolver(), Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL,
                        state.optInt("dimPreviousStrength", 50), user);
            }
            releaseNight(context, user, state);
        }
        Settings.Secure.putIntForUser(context.getContentResolver(), Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED,
                active ? 1 : 0, user);
        EdgeGoConfig.put(state, "dimOwned", active);
        save(context, user, state);
    }

    private static void releaseNight(Context context, int user, JSONObject state) {
        if (!state.optBoolean("nightOwned")) return;
        int active = Settings.Secure.getIntForUser(context.getContentResolver(), Settings.Secure.NIGHT_DISPLAY_ACTIVATED, 0, user);
        int temperature = Settings.Secure.getIntForUser(context.getContentResolver(), Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE, 4500, user);
        if (active == 1 && temperature == state.optInt("nightAppliedTemperature")) {
            Settings.Secure.putIntForUser(context.getContentResolver(), Settings.Secure.NIGHT_DISPLAY_ACTIVATED,
                    state.optInt("nightPreviousActive"), user);
            Settings.Secure.putIntForUser(context.getContentResolver(), Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE,
                    state.optInt("nightPreviousTemperature", 4500), user);
        }
        EdgeGoConfig.put(state, "nightOwned", false);
    }

    void startTimer(long duration) {
        if (duration < 1000 || duration > 86400000) return;
        JSONObject timer = new JSONObject();
        EdgeGoConfig.put(timer, "status", "running");
        EdgeGoConfig.put(timer, "endAt", System.currentTimeMillis() + duration);
        EdgeGoConfig.put(timer, "endElapsed", SystemClock.elapsedRealtime() + duration);
        EdgeGoConfig.put(timer, "bootCount", Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0));
        writeTimer(timer);
        update();
    }

    void pauseTimer() {
        long remaining = remaining();
        JSONObject timer = new JSONObject();
        EdgeGoConfig.put(timer, "status", remaining > 0 ? "paused" : "finished");
        EdgeGoConfig.put(timer, "remaining", remaining);
        writeTimer(timer);
        cancelOperation(context, host.userId(), TIMER);
    }

    void resumeTimer() { startTimer(remaining()); }

    void cancelTimer() {
        JSONObject timer = new JSONObject();
        EdgeGoConfig.put(timer, "status", "stopped");
        writeTimer(timer);
        cancelOperation(context, host.userId(), TIMER);
    }

    String timerStatus() {
        JSONObject timer = state(context, host.userId()).optJSONObject("timer");
        return timer == null ? "stopped" : timer.optString("status", "stopped");
    }

    long remaining() {
        JSONObject timer = state(context, host.userId()).optJSONObject("timer");
        if (timer == null) return 0;
        return remaining(context, timer);
    }

    static long remaining(Context context, JSONObject timer) {
        long remaining = timer.optLong("remaining");
        if (timer.optString("status").equals("running")) {
            boolean sameBoot = timer.has("endElapsed") && timer.optInt("bootCount", -1)
                    == Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
            remaining = sameBoot ? timer.optLong("endElapsed") - SystemClock.elapsedRealtime()
                    : timer.optLong("endAt") - System.currentTimeMillis();
        }
        return Math.max(0, Math.min(86400000, remaining));
    }

    private void writeTimer(JSONObject timer) {
        JSONObject state = state(context, host.userId());
        EdgeGoConfig.put(state, "timer", timer);
        save(context, host.userId(), state);
    }
}
