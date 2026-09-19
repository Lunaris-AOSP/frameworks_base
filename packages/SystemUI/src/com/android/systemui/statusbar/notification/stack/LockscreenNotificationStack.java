/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.statusbar.notification.stack;

import android.content.res.Resources;
import android.util.MathUtils;

import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;

import java.util.ArrayList;


class LockscreenNotificationStack {
    private static final int VISIBLE_CARDS = 3;
    private static final float SCALE_STEP = 0.04f;
    private static final float ALPHA_STEP = 0.08f;

    private final ArrayList<ExpandableNotificationRow> mRows = new ArrayList<>();
    private float mPeek;
    private float mElevation;
    private float mBottomPadding;
    private float mAdditionalGap;
    private float mAvailableHeight;
    private float mBottom;
    private boolean mEnabled;
    private boolean mExpanded;
    private boolean mApplied;

    LockscreenNotificationStack(Resources resources) {
        updateResources(resources);
    }

    void updateResources(Resources resources) {
        mPeek = resources.getDimension(R.dimen.lockscreen_notification_stack_peek);
        mElevation = resources.getDimension(R.dimen.lockscreen_notification_stack_elevation);
        mBottomPadding = resources.getDimension(R.dimen.lockscreen_notification_stack_bottom_padding);
        mAdditionalGap = resources.getDimension(R.dimen.lockscreen_notification_stack_list_gap);
    }

    boolean setEnabled(boolean enabled) {
        if (mEnabled == enabled) {
            return false;
        }
        mEnabled = enabled;
        reset();
        return true;
    }

    boolean setAvailableHeight(float height) {
        height = Math.max(0f, height);
        if (mAvailableHeight == height) {
            return false;
        }
        mAvailableHeight = height;
        return true;
    }

    float getBottom() {
        return mBottom;
    }

    boolean isEnabled() {
        return mEnabled;
    }

    boolean isExpanded() {
        return mExpanded;
    }

    boolean isApplied() {
        return mApplied;
    }

    boolean setExpanded(boolean expanded) {
        if (mExpanded == expanded || (expanded && !mApplied)) {
            return false;
        }
        mExpanded = expanded;
        return true;
    }

    void reset() {
        mExpanded = false;
        mApplied = false;
        mBottom = 0f;
        mRows.clear();
    }

    private boolean isOnLockscreen(AmbientState ambientState) {
        return SceneContainerFlag.isEnabled()
                ? ambientState.isShowingStackOnLockscreen() : ambientState.isOnKeyguard();
    }

    void apply(StackScrollAlgorithm.StackScrollAlgorithmState algorithmState,
            AmbientState ambientState) {
        mApplied = false;
        mBottom = 0f;
        mRows.clear();
        if (!mEnabled || !isOnLockscreen(ambientState) || ambientState.isPulsing()
                || ambientState.isPulseExpanding()) {
            mExpanded = false;
            return;
        }
        if (ambientState.getDozeAmount() == 1f) {
            mExpanded = false;
        }

        final float amount = (1f - MathUtils.saturate(ambientState.getFractionToShade()))
                * (1f - MathUtils.saturate(ambientState.getDozeAmount()));
        if (amount == 0f) {
            return;
        }

        boolean hasExpandedGroup = false;
        for (ExpandableView child : algorithmState.visibleChildren) {
            ExpandableViewState state = child.getViewState();
            if (state.gone || state.hidden || state.inShelf || state.getAlpha() == 0f) {
                continue;
            }
            if (!(child instanceof ExpandableNotificationRow row)) {
                if (!mRows.isEmpty()) {
                    return;
                }
                continue;
            }
            if (row.isHeadsUpState() || row.isHeadsUpAnimatingAway() || row.showingPulsing()
                    || row.isExpandAnimationRunning() || row.hasExpandingChild()
                    || row.areGutsExposed() || row.isUserLocked()) {
                return;
            }
            hasExpandedGroup |= row.areChildrenExpanded();
            mRows.add(row);
        }

        if (hasExpandedGroup) {
            mExpanded = true;
        }
        if (mRows.size() < 2) {
            mExpanded = false;
        }
        if (mRows.isEmpty()) {
            return;
        }

        ExpandableViewState firstState = mRows.get(0).getViewState();
        ExpandableViewState lastState = mRows.get(mRows.size() - 1).getViewState();
        final float listTop = firstState.getYTranslation();
        final float listBottom = lastState.getYTranslation() + lastState.height;
        final int height = firstState.height;
        NotificationShelf shelf = ambientState.getShelf();
        ExpandableViewState shelfState = shelf != null ? shelf.getViewState() : null;
        final boolean showShelf = shelfState != null && !shelfState.hidden;
        final float naturalBottom = showShelf
                ? shelfState.getYTranslation() + shelfState.height : listBottom;

        final float stackTop = SceneContainerFlag.isEnabled()
                ? ambientState.getStackTop() : ambientState.getStackY();
        float bottom = mAvailableHeight > 0f
                ? Math.max(naturalBottom, stackTop + mAvailableHeight - mBottomPadding)
                : naturalBottom;
        if (SceneContainerFlag.isEnabled()) {
            bottom = Math.min(bottom, ambientState.getStackCutoff());
        }
        final float shelfGap = showShelf
                ? Math.min(mPeek, Math.max(0f, bottom - shelfState.height - listBottom)) : 0f;
        final float shelfHeight = showShelf ? shelfState.height + shelfGap : 0f;
        final float cardsBottom = bottom - shelfHeight;
        if (height <= 0 || cardsBottom < listTop + height) {
            return;
        }

        final boolean collapsed = !mExpanded && mRows.size() > 1;
        final int peekingCards = Math.min(mRows.size() - 1, VISIBLE_CARDS - 1);
        final float peek = peekingCards == 0 ? 0f
                : Math.min(mPeek, (cardsBottom - listTop - height) / peekingCards);
        final float deckTop = cardsBottom - height - peekingCards * peek;
        final float extraGap = mRows.size() > 1
                ? Math.min(mAdditionalGap,
                        Math.max(0f, cardsBottom - listBottom) / (mRows.size() - 1)) : 0f;
        final float listOffset = Math.max(0f,
                cardsBottom - listBottom - extraGap * (mRows.size() - 1));
        mApplied = collapsed;
        mBottom = MathUtils.lerp(naturalBottom, bottom, amount);

        for (int i = 0; i < mRows.size(); i++) {
            ExpandableViewState state = mRows.get(i).getViewState();
            final int depth = Math.min(i, VISIBLE_CARDS - 1);
            final float targetY = collapsed ? deckTop + depth * peek
                    : state.getYTranslation() + listOffset + i * extraGap;
            state.setYTranslation(MathUtils.lerp(state.getYTranslation(), targetY,
                    amount));
            state.lockscreenStackRoundness = amount;
            if (!collapsed) {
                continue;
            }
            state.height = Math.round(MathUtils.lerp(state.height, height, amount));
            state.setScaleX(MathUtils.lerp(1f, 1f - depth * SCALE_STEP, amount));
            state.lockscreenStackScaleApplied = true;
            state.setZTranslation(MathUtils.lerp(state.getZTranslation(),
                    ambientState.getBaseZHeight() + (VISIBLE_CARDS - depth) * mElevation, amount));
            state.lockscreenStackIndex = amount == 1f ? i : -1;
            if (i > 0) {
                state.lockscreenStackContentAlpha = 1f - amount;
                if (amount == 1f) {
                    state.location = i < VISIBLE_CARDS
                            ? ExpandableViewState.LOCATION_BOTTOM_STACK_PEEKING
                            : ExpandableViewState.LOCATION_BOTTOM_STACK_HIDDEN;
                }
            }
            if (i >= VISIBLE_CARDS) {
                state.setAlpha(state.getAlpha() * (1f - amount));
                state.hidden = amount == 1f;
            } else {
                state.setAlpha(state.getAlpha() * (1f - depth * ALPHA_STEP * amount));
            }
        }

        if (shelfState != null) {
            final float shelfY = shelfState.getYTranslation();
            final float targetShelfY = showShelf
                    ? bottom - shelfState.height : shelfY + bottom - naturalBottom;
            final float newShelfY = MathUtils.lerp(shelfY, targetShelfY, amount);
            shelfState.setYTranslation(newShelfY);
            for (ExpandableView child : algorithmState.visibleChildren) {
                ExpandableViewState state = child.getViewState();
                if (state.inShelf) {
                    state.setYTranslation(state.getYTranslation() + newShelfY - shelfY);
                }
            }
        }
    }
}
