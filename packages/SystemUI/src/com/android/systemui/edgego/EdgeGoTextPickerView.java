/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.edgego;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.util.lunaris.EdgeGoConfig;
import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

final class EdgeGoTextPickerView extends View {
    private final ArrayList<Bundle> texts;
    private final Set<Integer> selected = new LinkedHashSet<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Consumer<String> picked;
    private final EdgeGoConfig config;
    private final EdgeGoIcons icons;
    private final RectF area = new RectF();
    private boolean touching;

    EdgeGoTextPickerView(Context context, ArrayList<Bundle> texts, EdgeGoConfig config, EdgeGoIcons icons, Consumer<String> picked) {
        super(context);
        this.texts = texts;
        this.picked = picked;
        this.config = config;
        this.icons = icons;
        setContentDescription(context.getString(R.string.edgego_text_picker_hint));
        setFocusable(true);
        setHapticFeedbackEnabled(config.bool("haptic_feedback"));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { touching = true; selected.clear(); }
        if (event.getPointerCount() > 1 || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            touching = false; selected.clear(); invalidate(); return true;
        }
        if (!touching) return true;
        for (int i = 0; i < texts.size(); i++) {
            Rect bounds = texts.get(i).getParcelable("bounds", Rect.class);
            if (bounds != null && bounds.contains((int) event.getX(), (int) event.getY()) && selected.add(i)) {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            touching = false;
            performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
            StringBuilder result = new StringBuilder();
            for (int index : selected) {
                if (result.length() > 0) result.append('\n');
                result.append(texts.get(index).getString("text", ""));
            }
            picked.accept(result.toString());
        }
        invalidate();
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor((icons.surface(config, "continuous_gesture_overlay_background_color_rgb") & 0x00ffffff) | 0x18000000);
        float dp = getResources().getDisplayMetrics().density;
        int accent = icons.accent(config);
        for (int i = 0; i < texts.size(); i++) {
            Rect bounds = texts.get(i).getParcelable("bounds", Rect.class);
            if (bounds == null) continue;
            paint.setColor((accent & 0x00ffffff) | (selected.contains(i) ? 0x60000000 : 0x20000000));
            paint.setStyle(Paint.Style.FILL);
            area.set(bounds);
            canvas.drawRoundRect(area, 6 * dp, 6 * dp, paint);
            paint.setColor(accent); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(selected.contains(i) ? 2 * dp : dp);
            canvas.drawRoundRect(area, 6 * dp, 6 * dp, paint);
        }
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.getId()) { picked.accept(""); return true; }
        return super.performAccessibilityAction(action, arguments);
    }
}
