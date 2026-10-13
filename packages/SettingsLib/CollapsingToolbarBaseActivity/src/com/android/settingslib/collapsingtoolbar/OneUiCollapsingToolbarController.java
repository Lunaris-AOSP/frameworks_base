/*
 * Copyright (C) 2026 Lunaris OS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.settingslib.collapsingtoolbar;

import android.animation.StateListAnimator;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;

final class OneUiCollapsingToolbarController implements View.OnAttachStateChangeListener,
        View.OnLayoutChangeListener, AppBarLayout.OnOffsetChangedListener {
    private static final String SETTING = "oneui_collapsing_toolbar";
    private static final long SCRIM_ANIMATION_DURATION_MS = 150;

    private final CollapsingToolbarLayout mToolbar;
    private final AppBarLayout mAppBar;
    private final ContentResolver mResolver;
    private final ContentObserver mObserver;
    private final int mOriginalHeight;
    private final int mOriginalExpandedGravity;
    private final int mOriginalCollapsedGravity;
    private final int mOriginalMarginStart;
    private final int mOriginalMarginTop;
    private final int mOriginalMarginEnd;
    private final int mOriginalMarginBottom;
    private final float mOriginalExpandedSize;
    private final float mOriginalCollapsedSize;
    private final int mOriginalMaxLines;
    private final int mOriginalCollapseMode;
    private final int mOriginalScrimTrigger;
    private final long mOriginalScrimDuration;
    private final Drawable mOriginalContentScrim;
    private final float mOriginalElevation;
    private final StateListAnimator mOriginalStateListAnimator;
    private final boolean mOriginalLiftOnScroll;
    private final float mMaxElevation;

    private boolean mEnabled;
    private boolean mObserving;

    static void attach(CollapsingToolbarLayout toolbar, AppBarLayout appBar) {
        if (toolbar.getTag(R.id.settingslib_oneui_toolbar_controller) != null) {
            return;
        }
        OneUiCollapsingToolbarController controller =
                new OneUiCollapsingToolbarController(toolbar, appBar);
        toolbar.setTag(R.id.settingslib_oneui_toolbar_controller, controller);
        toolbar.addOnAttachStateChangeListener(controller);
        toolbar.addOnLayoutChangeListener(controller);
        if (toolbar.isAttachedToWindow()) {
            controller.onViewAttachedToWindow(toolbar);
        }
    }

    private OneUiCollapsingToolbarController(CollapsingToolbarLayout toolbar, AppBarLayout appBar) {
        mToolbar = toolbar;
        mAppBar = appBar;
        mResolver = toolbar.getContext().getContentResolver();
        mOriginalHeight = toolbar.getLayoutParams().height;
        mOriginalExpandedGravity = toolbar.getExpandedTitleGravity();
        mOriginalCollapsedGravity = toolbar.getCollapsedTitleGravity();
        mOriginalMarginStart = toolbar.getExpandedTitleMarginStart();
        mOriginalMarginTop = toolbar.getExpandedTitleMarginTop();
        mOriginalMarginEnd = toolbar.getExpandedTitleMarginEnd();
        mOriginalMarginBottom = toolbar.getExpandedTitleMarginBottom();
        mOriginalExpandedSize = toolbar.getExpandedTitleTextSize();
        mOriginalCollapsedSize = toolbar.getCollapsedTitleTextSize();
        mOriginalMaxLines = toolbar.getMaxLines();
        mOriginalCollapseMode = toolbar.getTitleCollapseMode();
        mOriginalScrimTrigger = toolbar.getScrimVisibleHeightTrigger();
        mOriginalScrimDuration = toolbar.getScrimAnimationDuration();
        mOriginalContentScrim = toolbar.getContentScrim();
        mOriginalElevation = appBar.getElevation();
        mOriginalStateListAnimator = appBar.getStateListAnimator();
        mOriginalLiftOnScroll = appBar.isLiftOnScroll();
        mMaxElevation = toolbar.getResources().getDimension(
                R.dimen.settingslib_oneui_max_elevation);
        mObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                if (mObserving) {
                    updateStyle();
                }
            }
        };
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        if (mObserving) {
            return;
        }
        mResolver.registerContentObserver(Settings.System.getUriFor(SETTING), false, mObserver);
        mObserving = true;
        updateStyle();
        if (mEnabled) {
            mAppBar.addOnOffsetChangedListener(this);
        }
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        if (mObserving) {
            mResolver.unregisterContentObserver(mObserver);
            mObserving = false;
        }
        mAppBar.removeOnOffsetChangedListener(this);
    }

    @Override
    public void onLayoutChange(View view, int left, int top, int right, int bottom,
            int oldLeft, int oldTop, int oldRight, int oldBottom) {
        if (!mEnabled) {
            return;
        }
        final int height = mToolbar.getResources().getDimensionPixelSize(
                R.dimen.settingslib_oneui_toolbar_height);
        final ViewGroup.LayoutParams params = mToolbar.getLayoutParams();
        if (params.height != height) {
            params.height = height;
            mToolbar.setLayoutParams(params);
            mAppBar.setExpanded(false, false);
        }
    }

    private void updateStyle() {
        final boolean enabled = Settings.System.getInt(mResolver, SETTING, 0) == 1;
        if (mEnabled == enabled) {
            return;
        }
        mEnabled = enabled;
        final Resources resources = mToolbar.getResources();
        final ViewGroup.LayoutParams params = mToolbar.getLayoutParams();
        if (enabled) {
            params.height = resources.getDimensionPixelSize(R.dimen.settingslib_oneui_toolbar_height);
            final int margin = resources.getDimensionPixelSize(R.dimen.settingslib_oneui_title_margin);
            mToolbar.setExpandedTitleMargin(margin, margin, margin, margin);
            mToolbar.setExpandedTitleGravity(Gravity.CENTER);
            mToolbar.setCollapsedTitleGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            mToolbar.setExpandedTitleTextSize(
                    resources.getDimension(R.dimen.settingslib_oneui_expanded_title_size));
            mToolbar.setCollapsedTitleTextSize(
                    resources.getDimension(R.dimen.settingslib_oneui_collapsed_title_size));
            mToolbar.setMaxLines(3);
            mToolbar.setTitleCollapseMode(CollapsingToolbarLayout.TITLE_COLLAPSE_MODE_FADE);
            mToolbar.setScrimVisibleHeightTrigger(
                    resources.getDimensionPixelSize(R.dimen.settingslib_oneui_scrim_trigger));
            mToolbar.setScrimAnimationDuration(SCRIM_ANIMATION_DURATION_MS);
            mAppBar.setStateListAnimator(null);
            mAppBar.setElevation(0f);
            mAppBar.addOnOffsetChangedListener(this);
        } else {
            mAppBar.removeOnOffsetChangedListener(this);
            params.height = mOriginalHeight;
            mToolbar.setExpandedTitleMargin(mOriginalMarginStart, mOriginalMarginTop,
                    mOriginalMarginEnd, mOriginalMarginBottom);
            mToolbar.setExpandedTitleGravity(mOriginalExpandedGravity);
            mToolbar.setCollapsedTitleGravity(mOriginalCollapsedGravity);
            mToolbar.setExpandedTitleTextSize(mOriginalExpandedSize);
            mToolbar.setCollapsedTitleTextSize(mOriginalCollapsedSize);
            mToolbar.setMaxLines(mOriginalMaxLines);
            mToolbar.setTitleCollapseMode(mOriginalCollapseMode);
            mToolbar.setScrimVisibleHeightTrigger(mOriginalScrimTrigger);
            mToolbar.setScrimAnimationDuration(mOriginalScrimDuration);
            mToolbar.setContentScrim(mOriginalContentScrim);
            mAppBar.setLiftOnScroll(mOriginalLiftOnScroll);
            mAppBar.setElevation(mOriginalElevation);
            mAppBar.setStateListAnimator(mOriginalStateListAnimator);
        }
        mToolbar.setLayoutParams(params);
        mAppBar.setExpanded(enabled, false);
    }

    @Override
    public void onOffsetChanged(AppBarLayout appBar, int verticalOffset) {
        if (!mEnabled) {
            return;
        }
        final int scrollRange = appBar.getTotalScrollRange();
        final float fraction = scrollRange > 0
                ? Math.min(1f, Math.abs((float) verticalOffset) / scrollRange) : 0f;
        appBar.setElevation(mMaxElevation * fraction);
    }
}
