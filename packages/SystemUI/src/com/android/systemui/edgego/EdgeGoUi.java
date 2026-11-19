/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.edgego;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.util.lunaris.EdgeGoConfig;

final class EdgeGoUi {
    static int contrastColor(int surface) {
        surface |= 0xff000000;
        return ColorUtils.calculateContrast(0xffffffff, surface) >= ColorUtils.calculateContrast(0xff000000, surface)
                ? 0xffffffff : 0xff000000;
    }

    static Button button(Context context, EdgeGoConfig config, int surface, int foreground, float radius) {
        ExpressiveButton button = new ExpressiveButton(context);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(foreground);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.feedback = new PressFeedback(button, config, surface, foreground, radius);
        return button;
    }

    static ImageButton iconButton(Context context, EdgeGoConfig config, int surface, int foreground, float radius) {
        ExpressiveIconButton button = new ExpressiveIconButton(context);
        button.setImageTintList(ColorStateList.valueOf(foreground));
        button.feedback = new PressFeedback(button, config, surface, foreground, radius);
        return button;
    }

    static void shape(ImageButton button, float radius) {
        if (button instanceof ExpressiveIconButton) {
            PressFeedback feedback = ((ExpressiveIconButton) button).feedback;
            feedback.radius = radius;
            feedback.update(button.isPressed());
        }
    }

    static LinearLayout cell(Context context, EdgeGoConfig config, int surface, int foreground, float radius) {
        ExpressiveCell cell = new ExpressiveCell(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.feedback = new PressFeedback(cell, config, surface, foreground, radius);
        return cell;
    }

    static void colors(View view, int surface, int foreground) {
        PressFeedback feedback = view instanceof ExpressiveCell ? ((ExpressiveCell) view).feedback
                : view instanceof ExpressiveButton ? ((ExpressiveButton) view).feedback
                : view instanceof ExpressiveIconButton ? ((ExpressiveIconButton) view).feedback : null;
        if (feedback == null) return;
        feedback.fill.setColor(surface);
        ((RippleDrawable) view.getBackground()).setColor(ColorStateList.valueOf((foreground & 0x00ffffff) | 0x24000000));
        if (view instanceof Button) ((Button) view).setTextColor(foreground);
        if (view instanceof ImageButton) ((ImageButton) view).setImageTintList(ColorStateList.valueOf(foreground));
    }

    private static final class ExpressiveCell extends LinearLayout {
        PressFeedback feedback;
        ExpressiveCell(Context context) { super(context); }
        @Override public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            if (feedback != null) feedback.update(pressed);
        }
        @Override protected void onDetachedFromWindow() {
            if (feedback != null) feedback.cancel();
            super.onDetachedFromWindow();
        }
    }

    private static final class ExpressiveButton extends Button {
        PressFeedback feedback;
        ExpressiveButton(Context context) { super(context); }
        @Override public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            if (feedback != null) feedback.update(pressed);
        }
        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            setAlpha(isEnabled() ? 1 : .38f);
        }
        @Override protected void onDetachedFromWindow() {
            if (feedback != null) feedback.cancel();
            super.onDetachedFromWindow();
        }
    }

    private static final class ExpressiveIconButton extends ImageButton {
        PressFeedback feedback;
        ExpressiveIconButton(Context context) { super(context); }
        @Override public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            if (feedback != null) feedback.update(pressed);
        }
        @Override protected void drawableStateChanged() {
            super.drawableStateChanged();
            setAlpha(isEnabled() ? 1 : .38f);
        }
        @Override protected void onDetachedFromWindow() {
            if (feedback != null) feedback.cancel();
            super.onDetachedFromWindow();
        }
    }

    private static final class PressFeedback {
        final View view;
        final GradientDrawable fill = new GradientDrawable(), mask = new GradientDrawable();
        final SpringAnimation scale, corners;
        float radius;

        PressFeedback(View view, EdgeGoConfig config, int surface, int foreground, float radius) {
            this.view = view;
            this.radius = radius;
            fill.setColor(surface);
            fill.setCornerRadius(radius);
            mask.setColor(0xffffffff);
            mask.setCornerRadius(radius);
            view.setBackground(new RippleDrawable(ColorStateList.valueOf((foreground & 0x00ffffff) | 0x24000000), fill, mask));
            view.setBackgroundTintList(null);
            view.setClipToOutline(true);
            view.setElevation(0);
            view.setStateListAnimator(null);
            view.setHapticFeedbackEnabled(config.bool("haptic_feedback"));
            scale = new SpringAnimation(view, SpringAnimation.SCALE_X);
            scale.addUpdateListener((animation, value, velocity) -> view.setScaleY(value));
            corners = new SpringAnimation(fill, new FloatPropertyCompat<GradientDrawable>("edgeGoButtonCorners") {
                @Override public float getValue(GradientDrawable drawable) { return drawable.getCornerRadius(); }
                @Override public void setValue(GradientDrawable drawable, float value) {
                    drawable.setCornerRadius(Math.max(0, value));
                    mask.setCornerRadius(Math.max(0, value));
                    view.invalidateOutline();
                }
            });
            scale.setSpring(spring(config, 1));
            corners.setSpring(spring(config, radius));
        }

        private SpringForce spring(EdgeGoConfig config, float value) {
            return new SpringForce(value).setStiffness(config.integer("animation_stiffness"))
                    .setDampingRatio(config.integer("animation_damping") / 100f);
        }

        void update(boolean pressed) {
            float target = pressed ? radius * .55f : radius;
            if (ValueAnimator.areAnimatorsEnabled()) {
                scale.animateToFinalPosition(pressed ? .94f : 1);
                corners.animateToFinalPosition(target);
            } else {
                cancel();
                view.setScaleX(1);
                view.setScaleY(1);
                fill.setCornerRadius(target);
                mask.setCornerRadius(target);
                view.invalidateOutline();
            }
        }

        void cancel() {
            scale.cancel(); corners.cancel();
            view.setScaleX(1); view.setScaleY(1);
            fill.setCornerRadius(radius); mask.setCornerRadius(radius);
            view.invalidateOutline();
        }
    }
}
