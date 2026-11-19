/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.edgego;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.text.TextPaint;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.util.lunaris.EdgeGoConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.Executor;

final class EdgeGoFeedbackWindow {
    private final Context context;
    private final WindowManager windows;
    private final Handler handler;
    private final FeedbackView view;
    private final EdgeGoIcons icons;
    private final EdgeGoTapWindow taps;
    private final WindowManager.LayoutParams layout;
    private final SpringAnimation spring;
    private ValueAnimator swipeFade;
    private float swipeAlphaTarget, finishVelocity;
    private boolean finishAfterFadeIn;
    private final Map<EdgeGoGestureController.Zone, Shader> fades = new IdentityHashMap<>();
    private final int[] sectorColors = new int[7];
    private int accent;
    private final Runnable idle = this::hideIdleIndicators;
    private EdgeGoConfig config = new EdgeGoConfig(false, null);
    private List<EdgeGoGestureController.Zone> zones = new ArrayList<>();
    private Region exclusions = new Region();
    private boolean showing, fullscreen;
    private long lastHaptic;

    EdgeGoFeedbackWindow(Context context, WindowManager windows, Handler handler, Executor background, Consumer<WindowInsets> insets) {
        this.context = context;
        this.windows = windows;
        this.handler = handler;
        icons = new EdgeGoIcons(context, handler, background, true);
        taps = new EdgeGoTapWindow(context, windows);
        view = new FeedbackView(context);
        view.setOnApplyWindowInsetsListener((v, value) -> { insets.accept(value); return value; });
        layout = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        layout.setTitle("EdgeGo Gesture");
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        layout.setFitInsetsTypes(0);
        layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        layout.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY
                | WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION;
        spring = new SpringAnimation(view, new FloatPropertyCompat<FeedbackView>("edgeGoProgress") {
            @Override public float getValue(FeedbackView view) { return view.progress; }
            @Override public void setValue(FeedbackView view, float value) { view.progress = value; view.invalidate(); }
        });
    }

    void show(EdgeGoConfig config, List<EdgeGoGestureController.Zone> zones, boolean injecting) {
        boolean settingsChanged = this.config != config;
        this.config = config;
        this.zones = zones;
        accent = icons.accent(config);
        for (int i = 0; i < sectorColors.length; i++) sectorColors[i] = config.integer("custom_pull_sector_" + (i + 1) + "_color_rgb") | 0xff000000;
        fades.clear();
        for (EdgeGoGestureController.Zone zone : zones) {
            RectF bounds = new RectF(zone.bounds);
            int color = config.bool("material_you_sync_edges") ? accent : zone.color | 0xff000000;
            boolean reverse = zone.edge.equals("right") || zone.edge.equals("bottom");
            fades.put(zone, zone.side() ? new LinearGradient(bounds.left, bounds.centerY(), bounds.right, bounds.centerY(),
                    reverse ? Color.TRANSPARENT : color, reverse ? color : Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    : new LinearGradient(bounds.centerX(), bounds.top, bounds.centerX(), bounds.bottom,
                            reverse ? Color.TRANSPARENT : color, reverse ? color : Color.TRANSPARENT, Shader.TileMode.CLAMP));
        }
        taps.show(zones, config.bool("respect_app_exclusion") ? exclusions : new Region(), !injecting);
        try {
            if (!showing) {
                windows.addView(view, layout);
                showing = true;
                view.indicatorAlpha = 1;
                view.post(() -> {
                    if (view.getViewRootImpl() != null) {
                        SurfaceControl surface = view.getViewRootImpl().getSurfaceControl();
                        if (surface != null && surface.isValid()) {
                            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                                transaction.setSkipScreenshot(surface, true).apply();
                            }
                        }
                    }
                });
            } else windows.updateViewLayout(view, layout);
            if (settingsChanged || !config.bool("auto_hide_enabled")) {
                handler.removeCallbacks(idle);
                view.indicatorAlpha = 1;
                if (config.bool("auto_hide_enabled")) handler.postDelayed(idle, config.integer("auto_hide_delay"));
            }
            view.requestLayout();
            view.invalidate();
        } catch (RuntimeException e) { Log.w("EdgeGoGesture", "Cannot show gesture feedback", e); showing = false; }
    }

    void setExclusions(Region exclusions) { this.exclusions = new Region(exclusions); }
    void setFullscreen(boolean fullscreen) { this.fullscreen = fullscreen; }
    void setUserContext(Context userContext) { icons.setUserContext(userContext); }
    private void hideIdleIndicators() {
        if (view.activeZone != null) {
            handler.postDelayed(idle, config.integer("auto_hide_delay"));
            return;
        }
        view.indicatorAlpha = 0; view.invalidate();
    }

    void touch(EdgeGoGestureController.Zone zone) {
        spring.cancel();
        cancelSwipeFade();
        view.clearTracking();
        view.activeZone = zone;
        view.indicatorAlpha = 1;
        handler.removeCallbacks(idle);
        if (config.bool("auto_hide_enabled")) handler.postDelayed(idle, config.integer("auto_hide_delay"));
        view.invalidate();
    }

    void track(EdgeGoGestureController.Zone zone, String action, String label, float startX,
            float startY, float x, float y, float progress, boolean held, int sector) {
        spring.cancel();
        finishAfterFadeIn = false;
        view.activeZone = zone;
        view.startX = startX;
        view.startY = startY;
        view.x = x;
        view.y = y;
        view.progress = progress;
        view.held = held;
        view.level = -1;
        view.setSector(sector);
        view.setLabel(label);
        if (!view.action.equals(action)) {
            view.action = action;
            view.icon = icons.get(action, config, icon -> {
                if (view.action.equals(action)) { view.icon = icon; view.invalidate(); }
            });
        }
        if (progress > .005f) fadeSwipe(1);
        view.invalidate();
    }

    void control(String action, String label, float value, EdgeGoGestureController.Zone zone, float x, float y) {
        spring.cancel();
        finishAfterFadeIn = false;
        view.activeZone = zone;
        view.action = action;
        view.setLabel(label + " " + Math.round(value * 100) + "%");
        view.level = value;
        view.setSector(-1);
        view.x = x;
        view.y = y;
        view.progress = 1;
        fadeSwipe(1);
        view.invalidate();
    }

    void finish(boolean committed, float velocity) {
        if (!showing || view.activeZone == null) return;
        handler.removeCallbacks(idle);
        if (config.bool("auto_hide_enabled")) handler.postDelayed(idle, config.integer("auto_hide_delay"));
        if (!ValueAnimator.areAnimatorsEnabled() || view.progress <= .005f) {
            hideAnimation();
            return;
        }
        if (swipeFade != null && swipeAlphaTarget == 1) {
            finishAfterFadeIn = true;
            finishVelocity = velocity;
            return;
        }
        finishAfterFadeIn = false;
        view.releaseOpacity = Math.min(1, Math.max(0, view.progress));
        spring.setSpring(new SpringForce(0).setStiffness(config.integer("animation_stiffness"))
                .setDampingRatio(config.integer("animation_damping") / 100f));
        spring.setStartVelocity(-Math.min(5, velocity / 2000f));
        spring.animateToFinalPosition(0);
        fadeSwipe(0);
    }

    private void fadeSwipe(float target) {
        if (swipeAlphaTarget == target && (swipeFade != null || view.swipeAlpha == target)) return;
        cancelSwipeFade();
        swipeAlphaTarget = target;
        if (!ValueAnimator.areAnimatorsEnabled() || view.swipeAlpha == target) {
            view.swipeAlpha = target;
            if (target == 0) { spring.cancel(); view.clearTracking(); }
            view.invalidate();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(view.swipeAlpha, target);
        swipeFade = animator;
        animator.setDuration(target == 1 ? 120 : 180);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(value -> {
            view.swipeAlpha = (float) value.getAnimatedValue();
            view.invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (swipeFade != animator) return;
                swipeFade = null;
                if (target == 0) { spring.cancel(); view.clearTracking(); }
                else if (finishAfterFadeIn) finish(false, finishVelocity);
            }
        });
        animator.start();
    }

    private void cancelSwipeFade() {
        ValueAnimator animator = swipeFade;
        swipeFade = null;
        if (animator != null) animator.cancel();
    }

    void hideAnimation() { spring.cancel(); cancelSwipeFade(); view.clearTracking(); }

    void hide() {
        taps.hide();
        spring.cancel();
        cancelSwipeFade();
        handler.removeCallbacks(idle);
        view.clearTracking();
        zones = java.util.Collections.emptyList();
        fades.clear();
        icons.cancelPending();
        config = new EdgeGoConfig(false, null);
        if (showing) {
            try { windows.removeViewImmediate(view); }
            catch (IllegalArgumentException ignored) { }
            showing = false;
        }
    }

    void haptic(EdgeGoConfig config, boolean held) {
        if (view.activeZone != null && !view.activeZone.vibrate) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastHaptic < 60) return;
        lastHaptic = now;
        Vibrator vibrator = context.getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        int strength = config.string("haptic_feedback_strength").equals("light")
                ? VibrationEffect.EFFECT_STRENGTH_LIGHT
                : config.string("haptic_feedback_strength").equals("strong")
                        ? VibrationEffect.EFFECT_STRENGTH_STRONG : VibrationEffect.EFFECT_STRENGTH_MEDIUM;
        boolean twice = config.string("haptic_feedback_pattern").equals("double");
        int effectId = twice ? VibrationEffect.EFFECT_DOUBLE_CLICK
                : held ? VibrationEffect.EFFECT_HEAVY_CLICK : VibrationEffect.EFFECT_CLICK;
        VibrationEffect effect = VibrationEffect.createPredefined(effectId).applyEffectStrength(strength);
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
    }

    private final class FeedbackView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Path wave = new Path();
        final RectF rect = new RectF();
        EdgeGoGestureController.Zone activeZone;
        Drawable icon;
        String label = "", action = "none";
        CharSequence visibleLabel = "";
        float labelWidth;
        float startX, startY, x, y, progress, swipeAlpha, releaseOpacity, indicatorAlpha = 1, level = -1;
        int sector = -1;
        String sectorNumber = "";
        boolean held;

        FeedbackView(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        void setSector(int value) {
            if (sector == value) return;
            sector = value;
            sectorNumber = value < 0 ? "" : Integer.toString(value + 1);
        }

        void setLabel(String value) {
            float dp = getResources().getDisplayMetrics().density;
            float size = 12 * dp * getResources().getConfiguration().fontScale;
            if (label.equals(value) && textPaint.getTextSize() == size) return;
            label = value;
            textPaint.setTextSize(size);
            visibleLabel = TextUtils.ellipsize(value, textPaint, 150 * dp, TextUtils.TruncateAt.END);
            labelWidth = textPaint.measureText(visibleLabel, 0, visibleLabel.length());
        }

        void clearTracking() {
            activeZone = null;
            progress = 0;
            swipeAlpha = swipeAlphaTarget = 0;
            finishAfterFadeIn = false;
            releaseOpacity = 0;
            level = -1;
            action = "none";
            icon = null;
            setSector(-1);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float dp = getResources().getDisplayMetrics().density;
            boolean indicators = config.bool("show_indicators")
                    && !(fullscreen && config.bool("hide_indicators_in_fullscreen"));
            if (indicators && indicatorAlpha > 0) {
                for (EdgeGoGestureController.Zone zone : zones) {
                    if (!zone.indicator) continue;
                    paint.setShader(null);
                    paint.setColor(config.bool("material_you_sync_edges") ? accent : zone.color | 0xff000000);
                    paint.setAlpha(Math.round(config.integer("overlay_opacity") * 2.55f * indicatorAlpha));
                    rect.set(zone.bounds);
                    if (config.string("edge_indicator_style").equals("pill")) {
                        if (zone.side()) {
                            float center = rect.centerX(); rect.left = center - 2 * dp; rect.right = center + 2 * dp;
                        } else {
                            float center = rect.centerY(); rect.top = center - 2 * dp; rect.bottom = center + 2 * dp;
                        }
                    } else if (config.string("edge_indicator_style").equals("soft_fade")) {
                        paint.setShader(fades.get(zone));
                    }
                    canvas.drawRoundRect(rect, 4 * dp, 4 * dp, paint);
                }
            }
            paint.setShader(null);
            boolean fadingOut = swipeAlphaTarget == 0;
            if (activeZone == null || swipeAlpha <= 0 || !fadingOut && progress <= .005f
                    || level < 0 && !config.bool("pull_animation_enabled")) return;
            float amount = Math.min(1.1f, Math.max(0, progress));
            float shapeOpacity = Math.min(1, amount) * swipeAlpha;
            float opacity = fadingOut ? releaseOpacity * swipeAlpha : shapeOpacity;
            float reach = (held ? 52 : 42) * dp * amount;
            float anchorX = activeZone.edge.equals("left") ? activeZone.bounds.left
                    : activeZone.edge.equals("right") ? activeZone.bounds.right : startX;
            float anchorY = activeZone.edge.equals("top") ? activeZone.bounds.top
                    : activeZone.edge.equals("bottom") ? activeZone.bounds.bottom : startY;
            float centerX = anchorX, centerY = anchorY;
            if (activeZone.edge.equals("left")) centerX += reach;
            else if (activeZone.edge.equals("right")) centerX -= reach;
            else if (activeZone.edge.equals("top")) centerY += reach;
            else centerY -= reach;
            centerX = Math.max(26 * dp, Math.min(getWidth() - 26 * dp, centerX));
            centerY = Math.max(26 * dp, Math.min(getHeight() - 26 * dp, centerY));
            if (level >= 0) {
                drawControl(canvas, centerX, centerY, opacity, dp);
                return;
            }
            String style = config.string("pull_animation_style");
            boolean sectorTint = sector >= 0 && config.bool("custom_pull_use_sector_colors");
            int waveColor = sectorTint ? sectorColors[sector] : config.bool("material_you_sync_wave")
                    ? accent : config.integer("pull_animation_wave_color_rgb") | 0xff000000;
            int bubbleColor = sectorTint ? sectorColors[sector] : config.bool("material_you_sync_bubble")
                    ? accent : config.integer("pull_animation_bubble_color_rgb") | 0xff000000;
            int save = canvas.save();
            canvas.translate(anchorX, anchorY);
            if (activeZone.edge.equals("right")) canvas.scale(-1, 1);
            else if (activeZone.edge.equals("top")) canvas.rotate(90);
            else if (activeZone.edge.equals("bottom")) canvas.rotate(-90);
            if (style.equals("wave")) {
                paint.setColor(waveColor);
                paint.setAlpha(Math.round(config.integer("pull_animation_wave_opacity") * 2.55f * shapeOpacity));
                wave.reset();
                wave.moveTo(-2 * dp, -90 * dp * amount);
                wave.cubicTo(0, -50 * dp, reach + 28 * dp, -36 * dp, reach + 28 * dp, 0);
                wave.cubicTo(reach + 28 * dp, 36 * dp, 0, 50 * dp, -2 * dp, 90 * dp * amount);
                wave.close();
                canvas.drawPath(wave, paint);
            }
            canvas.restoreToCount(save);
            paint.setColor(bubbleColor);
            paint.setAlpha(Math.round(config.integer("pull_animation_bubble_arrow_opacity") * 2.55f * shapeOpacity));
            if (!style.equals("minimal")) {
                float size = config.integer("pull_animation_thickness_dp") * dp * amount;
                float radius = held ? size * .65f : size;
                canvas.drawRoundRect(centerX - size, centerY - size, centerX + size, centerY + size, radius, radius, paint);
            }
            if (sector >= 0 && config.bool("custom_pull_show_sector_number")) {
                paint.setColor(EdgeGoUi.contrastColor(bubbleColor));
                paint.setAlpha(Math.round(opacity * 255));
                paint.setTextSize(18 * dp); paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(sectorNumber, centerX, centerY + 6 * dp, paint);
                paint.setTextAlign(Paint.Align.LEFT);
            } else if (icon != null && config.bool("pull_animation_show_action_icon")) {
                boolean appIcon = config.bool("pull_animation_show_launch_app_icon")
                        && (action.startsWith("launch_app") || action.startsWith("shortcut:")
                                || action.startsWith("intent:"));
                if (!appIcon || EdgeGoIcons.isGlyph(icon)) icon.setTint(EdgeGoUi.contrastColor(bubbleColor));
                icon.setBounds((int) (centerX - 12 * dp), (int) (centerY - 12 * dp),
                        (int) (centerX + 12 * dp), (int) (centerY + 12 * dp));
                icon.setAlpha(Math.round(opacity * 255));
                icon.draw(canvas);
            }
            if (config.bool("show_action_label") && !label.isEmpty()) {
                drawLabel(canvas, centerX, centerY + 44 * dp, dp,
                        config.integer("pull_animation_arrow_color_rgb") | 0xff000000, opacity);
            }
        }

        private void drawControl(Canvas canvas, float centerX, float centerY, float opacity, float dp) {
            String prefix = action.equals("media_scrub") ? "media_scrub_overlay" : action.equals("page_scroll")
                    ? "page_scroll_overlay" : "continuous_gesture_overlay";
            int background = icons.surface(config, prefix + "_background_color_rgb");
            int foreground = config.bool(prefix + "_use_accent_color") ? icons.accent(config)
                    : config.integer(prefix + "_foreground_color_rgb") | 0xff000000;
            boolean slider = action.equals("volume_slider") || action.equals("brightness_slider");
            String style = slider ? config.string(action + "_style") : "system_pill";
            if (slider) foreground = config.bool("material_you_sync_sliders") || config.bool(action + "_use_accent_color")
                    ? icons.accent(config) : config.integer(action + "_color_rgb") | 0xff000000;
            String orientation = config.string("slider_orientation");
            boolean vertical = !action.equals("media_scrub") && (orientation.equals("vertical")
                    || orientation.equals("edge") && activeZone.side());
            float thickness = (style.equals("minimal") ? 3 : style.equals("edge_rail") ? 8 : 32) * dp;
            WindowInsets insets = getRootWindowInsets();
            android.graphics.Insets safe = insets == null ? android.graphics.Insets.NONE : insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.mandatorySystemGestures());
            float reach = Math.min(180 * dp, (vertical ? getHeight() : getWidth()) - 32 * dp);
            if (vertical) {
                centerY = Math.max(safe.top + reach / 2 + 16 * dp, Math.min(getHeight() - safe.bottom - reach / 2 - 48 * dp, centerY));
                rect.set(centerX - thickness / 2, centerY - reach / 2, centerX + thickness / 2, centerY + reach / 2);
            } else {
                centerY = Math.max(safe.top + 24 * dp, Math.min(getHeight() - safe.bottom - 64 * dp, centerY));
                centerX = Math.max(reach / 2 + 16 * dp, Math.min(getWidth() - reach / 2 - 16 * dp, centerX));
                rect.set(centerX - reach / 2, centerY - thickness / 2, centerX + reach / 2, centerY + thickness / 2);
            }
            float labelLeft = Math.max(8 * dp, Math.min(getWidth() - labelWidth - 8 * dp, centerX - labelWidth / 2));
            int save = canvas.saveLayerAlpha(Math.min(rect.left, labelLeft) - 16 * dp, rect.top - 4 * dp,
                    Math.max(rect.right, labelLeft + labelWidth) + 16 * dp, Math.min(getHeight(), rect.bottom + 40 * dp),
                    Math.round(opacity * 255));
            EdgeGoLevelView.drawLevel(canvas, paint, rect, level, vertical, foreground, background);
            drawLabel(canvas, centerX, rect.bottom + 24 * dp, dp,
                    config.integer(prefix + "_foreground_color_rgb") | 0xff000000, 1);
            canvas.restoreToCount(save);
        }

        private void drawLabel(Canvas canvas, float centerX, float y, float dp, int color, float amount) {
            textPaint.setColor(config.bool("material_you_surface_tint_enabled") ? icons.text(config) : color);
            textPaint.setAlpha(Math.round(Math.min(1, amount) * 255));
            float textX = Math.max(20 * dp, Math.min(getWidth() - labelWidth - 20 * dp, centerX - labelWidth / 2));
            WindowInsets insets = getRootWindowInsets();
            int bottom = insets == null ? 0 : insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.mandatorySystemGestures()).bottom;
            float baseline = Math.min(getHeight() - bottom - 16 * dp, y);
            paint.setColor(icons.surface(config, "continuous_gesture_overlay_background_color_rgb"));
            paint.setAlpha(Math.round(Math.min(1, amount) * 240));
            rect.set(textX - 12 * dp, baseline + textPaint.ascent() - 8 * dp,
                    textX + labelWidth + 12 * dp, baseline + textPaint.descent() + 8 * dp);
            canvas.drawRoundRect(rect, 18 * dp, 18 * dp, paint);
            canvas.drawText(visibleLabel, 0, visibleLabel.length(), textX, baseline, textPaint);
        }
    }
}
