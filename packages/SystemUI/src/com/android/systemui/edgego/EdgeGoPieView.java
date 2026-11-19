/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.edgego;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.util.lunaris.EdgeGoConfig;

import java.util.ArrayList;
import java.util.List;

final class EdgeGoPieView extends View {
    interface Listener {
        void select(EdgeGoPanelItem item, boolean held);
        void close();
        void module(EdgeGoPanelItem item, java.util.function.Consumer<List<EdgeGoPanelItem>> ready);
    }
    private final Handler handler;
    private final EdgeGoConfig config;
    private final EdgeGoIcons icons;
    private final EdgeGoActions actions;
    private final Listener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final List<Drawable> drawables = new ArrayList<>();
    private final SpringAnimation selection;
    private List<EdgeGoPanelItem> items;
    private final List<List<Integer>> ringItems = new ArrayList<>();
    private int[] itemRings = new int[0], itemSlots = new int[0];
    private int selected = -1, previousSelected = -1, generation;
    private float selectionProgress = 1;
    private float centerX, centerY, radius, angleOffset, originY, sliderValue, trackedLevel, lastY;
    private String slider = "";
    private boolean tracking, fired, preset;
    private final Runnable group = this::openGroup;

    EdgeGoPieView(Context context, Handler handler, EdgeGoConfig config, EdgeGoIcons icons,
            EdgeGoActions actions, List<EdgeGoPanelItem> items, EdgeGoGestureController.Zone zone,
            float x, float y, boolean tracking, boolean preset, Listener listener) {
        super(context);
        this.handler = handler;
        this.config = config;
        this.icons = icons;
        this.actions = actions;
        this.items = items;
        this.tracking = tracking;
        this.preset = preset;
        this.listener = listener;
        selection = new SpringAnimation(this, new FloatPropertyCompat<EdgeGoPieView>("edgeGoPieSelection") {
            @Override public float getValue(EdgeGoPieView view) { return view.selectionProgress; }
            @Override public void setValue(EdgeGoPieView view, float value) { view.selectionProgress = value; view.invalidate(); }
        });
        selection.setSpring(new SpringForce(1).setStiffness(config.integer("animation_stiffness"))
                .setDampingRatio(config.integer("animation_damping") / 100f));
        float dp = getResources().getDisplayMetrics().density;
        radius = config.integer(preset ? "preset_picker_radius" : "quick_access_radius") * dp;
        angleOffset = zone != null && zone.edge.equals("right") ? 180
                : zone != null && zone.edge.equals("bottom") ? 270 : zone != null && zone.edge.equals("top") ? 90 : 0;
        centerX = x;
        centerY = y;
        lastY = y;
        reloadIcons();
        setFocusableInTouchMode(true);
        setContentDescription(actions.label(preset ? "preset_picker" : "show_quick_access"));
    }

    private void reloadIcons() {
        generation++;
        previousSelected = -1;
        selectionProgress = 1;
        selection.cancel();
        buildRings();
        drawables.clear();
        for (EdgeGoPanelItem item : items) {
            int index = drawables.size();
            drawables.add(icons.get(item.action, config, drawable -> {
                if (index < drawables.size() && index < items.size() && items.get(index) == item) {
                    drawables.set(index, drawable);
                    invalidate();
                }
            }));
        }
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float margin = 12 * getResources().getDisplayMetrics().density;
        radius = Math.min(radius, (angleOffset == 0 || angleOffset == 180 ? height : width) / 2f - margin);
        if (angleOffset == 0) centerX = Math.max(margin, Math.min(centerX, width - radius - margin));
        else if (angleOffset == 180) centerX = Math.min(width - margin, Math.max(centerX, radius + margin));
        else centerX = Math.max(radius + margin, Math.min(width - radius - margin, centerX));
        if (angleOffset == 90) centerY = Math.max(margin, Math.min(centerY, height - radius - margin));
        else if (angleOffset == 270) centerY = Math.min(height - margin, Math.max(centerY, radius + margin));
        else centerY = Math.max(radius + margin, Math.min(height - radius - margin, centerY));
    }

    Rect touchBounds() {
        float margin = 32 * getResources().getDisplayMetrics().density;
        return new Rect((int) Math.max(0, centerX - radius - margin),
                (int) Math.max(0, centerY - radius - margin), (int) Math.min(getWidth(), centerX + radius + margin),
                (int) Math.min(getHeight(), centerY + radius + margin));
    }

    void track(float x, float y) {
        if (!tracking) return;
        lastY = y;
        if (!slider.isEmpty()) {
            trackedLevel = Math.max(0, Math.min(1, sliderValue + (originY - y) / (220 * getResources().getDisplayMetrics().density)));
            if (slider.equals("brightness")) actions.setBrightness(trackedLevel, true);
            else actions.setVolume(actions.volumeStream(), trackedLevel);
            if (trackedLevel == 0 || trackedLevel == 1) { originY = y; sliderValue = trackedLevel; }
            invalidate();
            return;
        }
        float dx = x - centerX, dy = y - centerY;
        float distance = (float) Math.hypot(dx, dy);
        double angle = (Math.toDegrees(Math.atan2(dy, dx)) - angleOffset + 540) % 360 - 180;
        int next = -1;
        int rings = rings();
        float inner = innerRadius(), band = (radius - inner) / rings;
        float arc = config.integer("quick_access_arc_degrees");
        if (distance > inner && distance < radius + 16 * getResources().getDisplayMetrics().density
                && Math.abs(angle) < arc / 2) {
            int ring = Math.min(rings - 1, Math.max(0, (int) ((distance - inner) / band)));
            int count = ringSize(ring);
            if (count > 0) next = ringItems.get(ring).get(Math.min(count - 1, Math.max(0, (int) ((angle + arc / 2) / arc * count))));
        }
        if (next == selected) return;
        handler.removeCallbacks(group);
        previousSelected = selected;
        selected = next;
        selection.cancel();
        selectionProgress = 0;
        if (ValueAnimator.areAnimatorsEnabled()) selection.animateToFinalPosition(1);
        else selectionProgress = 1;
        if (selected >= 0 && selected < items.size()) {
            EdgeGoPanelItem item = items.get(selected);
            if (!item.children.isEmpty() || !item.module.isEmpty()) handler.postDelayed(group, config.integer("quick_access_group_delay"));
            else if (!item.holdAction.equals("none")) handler.postDelayed(group, config.integer("quick_access_hold_timeout"));
            if (config.bool("haptic_feedback")) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            announceForAccessibility(item.label);
        }
        invalidate();
    }

    private void openGroup() {
        if (selected < 0 || selected >= items.size()) return;
        EdgeGoPanelItem item = items.get(selected);
        if (!item.children.isEmpty()) {
            items = item.children;
            selected = -1;
            reloadIcons();
        } else if (item.module.equals("volume") || item.module.equals("brightness")) {
            slider = item.module;
            originY = lastY;
            trackedLevel = sliderValue = slider.equals("brightness") ? actions.brightness() : actions.volume(actions.volumeStream());
            invalidate();
        } else if (!item.module.isEmpty()) {
            int token = generation;
            listener.module(item, children -> {
                if ((tracking || config.string("quick_access_open_mode").equals("tap") && isAttachedToWindow())
                        && !fired && token == generation && !children.isEmpty()) { items = children; selected = -1; reloadIcons(); }
            });
        } else if (!item.holdAction.equals("none")) {
            fired = true;
            listener.select(item, true);
        }
    }

    void endTracking(boolean canceled) {
        if (!tracking) return;
        tracking = false;
        handler.removeCallbacks(group);
        if (!slider.isEmpty()) {
            if (slider.equals("brightness")) {
                try { if (!canceled) actions.setBrightness(trackedLevel, false); }
                finally { actions.clearTemporaryBrightness(); }
            }
            slider = "";
            if (!config.bool("quick_access_keep_open_on_action")) listener.close();
            return;
        }
        if (!canceled && !fired && selected >= 0 && selected < items.size()) {
            EdgeGoPanelItem item = items.get(selected);
            if (config.string("quick_access_open_mode").equals("tap") && (!item.children.isEmpty() || !item.module.isEmpty())) {
                openGroup();
                return;
            }
            listener.select(item, false);
        }
        else if (!fired) listener.close();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            tracking = true; fired = false;
            if (!slider.isEmpty()) { originY = event.getY(); sliderValue = trackedLevel; }
        }
        if (event.getPointerCount() > 1) { endTracking(true); return true; }
        track(event.getX(), event.getY());
        if (event.getActionMasked() == MotionEvent.ACTION_UP) endTracking(false);
        else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) endTracking(true);
        return true;
    }

    @Override protected void onDetachedFromWindow() { cancelPending(); super.onDetachedFromWindow(); }

    void cancelPending() {
        generation++;
        selection.cancel();
        handler.removeCallbacks(group); tracking = false; fired = true;
        if (slider.equals("brightness")) actions.clearTemporaryBrightness();
        slider = "";
    }

    private int rings() { return Math.max(1, ringItems.size()); }
    private float innerRadius() { return Math.min(radius * .4f, 28 * getResources().getDisplayMetrics().density); }
    private int ringSize(int ring) { return ringItems.get(ring).size(); }
    private void buildRings() {
        ringItems.clear();
        itemRings = new int[items.size()]; itemSlots = new int[items.size()];
        boolean explicit = items.stream().anyMatch(item -> item.layer >= 0);
        int count = explicit ? items.stream().mapToInt(item -> Math.max(0, item.layer)).max().orElse(0) + 1
                : Math.max(1, Math.min(3, (items.size() + 7) / 8));
        for (int i = 0; i < count; i++) ringItems.add(new ArrayList<>());
        int weight = count * (count + 1) / 2;
        for (int i = 0; i < items.size(); i++) {
            int ring = 0;
            if (explicit) ring = Math.max(0, items.get(i).layer);
            else while (ring < count - 1 && i >= items.size() * (ring + 1) * (ring + 2) / 2 / weight) ring++;
            itemRings[i] = ring; itemSlots[i] = ringItems.get(ring).size(); ringItems.get(ring).add(i);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        float dp = getResources().getDisplayMetrics().density;
        float arc = config.integer("quick_access_arc_degrees");
        float opacity = config.integer(preset ? "preset_picker_opacity" : "quick_access_opacity") / 100f;
        paint.setColor(icons.surface(config, "quick_access_arc_color_rgb"));
        paint.setAlpha(Math.round(config.integer("quick_access_arc_background_opacity") * 2.55f * opacity));
        oval.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        boolean glass = config.string("quick_access_style").equals("glass");
        if (config.bool("quick_access_show_arc_background")) {
            if (glass) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
                canvas.drawArc(oval, angleOffset - arc / 2, arc, false, paint); paint.setStyle(Paint.Style.FILL);
            } else canvas.drawArc(oval, angleOffset - arc / 2, arc, true, paint);
        }
        if (!slider.isEmpty()) {
            oval.set(Math.max(12 * dp, centerX - 12 * dp), Math.max(24 * dp, centerY - 90 * dp),
                    Math.max(36 * dp, centerX + 12 * dp), Math.min(getHeight() - 24 * dp, centerY + 90 * dp));
            EdgeGoLevelView.drawLevel(canvas, paint, oval, trackedLevel, true, icons.accent(config),
                    icons.surface(config, "continuous_gesture_overlay_background_color_rgb"));
            return;
        }
        int rings = rings();
        float inner = innerRadius(), band = (radius - inner) / rings;
        for (int index = 0; index < items.size(); index++) {
            int ring = itemRings[index];
            int count = ringSize(ring);
            double angle = Math.toRadians(angleOffset - arc / 2 + (itemSlots[index] + .5) * arc / count);
            float reach = inner + band * (ring + .5f);
            float x = centerX + (float) Math.cos(angle) * reach, y = centerY + (float) Math.sin(angle) * reach;
            float spacing = Math.min(band, (float) Math.toRadians(arc) * reach / count);
            float iconSize = Math.min(config.integer("quick_access_icon_size") * dp * .5f, spacing * .36f);
            float highlight = index == selected ? Math.max(0, Math.min(1, selectionProgress))
                    : index == previousSelected ? Math.max(0, 1 - selectionProgress) : 0;
            float scale = 1 + .08f * highlight;
            if (config.bool("quick_access_show_icon_background") || highlight > 0) {
                paint.setColor(ColorUtils.blendARGB(icons.surface(config, "quick_access_arc_color_rgb"), icons.accent(config), highlight));
                paint.setAlpha(Math.round((225 + 30 * highlight) * opacity));
                float size = Math.min(spacing * .48f, iconSize + (glass ? 7 : 5) * dp) * scale;
                float corner = size * (1 - .45f * highlight);
                canvas.drawRoundRect(x - size, y - size, x + size, y + size, corner, corner, paint);
            }
            Drawable drawable = drawables.get(index);
            String action = items.get(index).action;
            if (!action.startsWith("launch_app") && !action.startsWith("shortcut:") && !action.startsWith("intent:")) {
                drawable.setTint(highlight > .5f ? icons.onAccent(config) : icons.text(config));
            }
            drawable.setBounds((int) (x - iconSize * scale), (int) (y - iconSize * scale), (int) (x + iconSize * scale), (int) (y + iconSize * scale));
            drawable.setAlpha(Math.round(255 * opacity));
            drawable.draw(canvas);
            if (preset && config.bool("preset_picker_show_name")) {
                paint.setColor(icons.text(config)); paint.setAlpha(255); paint.setTextSize(10 * dp);
                canvas.drawText(items.get(index).label, x - paint.measureText(items.get(index).label) / 2, y + iconSize + 12 * dp, paint);
            }
        }
        if (config.bool("quick_access_show_guide_line")) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp); paint.setColor(icons.accent(config)); paint.setAlpha(90);
            for (int ring = 1; ring <= rings; ring++) {
                float reach = inner + band * ring;
                oval.set(centerX - reach, centerY - reach, centerX + reach, centerY + reach);
                canvas.drawArc(oval, angleOffset - arc / 2, arc, false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
