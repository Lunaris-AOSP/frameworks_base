/*
 * Copyright (C) 2026 Project ASCP
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

package com.android.systemui.biometrics.udfps.quicklaunch;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.List;

public class QuickLaunchContainer extends FrameLayout {

    private static final String TAG = "QuickLaunchContainer";

    private static final PathInterpolator ENTER_INTERPOLATOR =
            new PathInterpolator(0.0f, 0.0f, 0.1f, 1.0f);
    private static final PathInterpolator OPTIONAL_INTERPOLATOR =
            new PathInterpolator(0.3f, 0.0f, 0.1f, 1.0f);
    private static final PathInterpolator OPENED_INTERPOLATOR =
            new PathInterpolator(0.33f, 0.0f, 0.67f, 1.0f);

    private static final int MAX_BLUR_RADIUS = 180;
    private static final int SCRIM_COLOR = 0xB3000000; 

    public interface OnQuickLaunchActionListener {
        void onItemLaunched(QuickLaunchItem item, int slotIndex);
        void onEditRequested();
        void onDismissed();
    }

    private final Context mContext;
    private OnQuickLaunchActionListener mListener;

    private QuickLaunchRoundIcon mRoundIcon;
    private QuickLaunchEditView mEditView;
    private LinearLayout mTextLayout;
    private TextView mNameTextView;
    private TextView mLabelTextView;

    private final List<QuickLaunchItem> mItemList = new ArrayList<>();
    private final List<FrameLayout> mSlotViews = new ArrayList<>();

    private int mSensorCenterX;
    private int mSensorCenterY;
    private int mMoveEnd;
    private int mMoveStart;
    private int mIconLayoutSize;

    private int mLastSelectedIndex = -1;
    private boolean mCanSelect = false;
    private boolean mIsAllEmpty = false;

    private float mLastPointerX = -1f;
    private float mLastPointerY = -1f;

    private ValueAnimator mScrimAnimator;

    public QuickLaunchContainer(Context context) {
        this(context, null);
    }

    public QuickLaunchContainer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QuickLaunchContainer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mContext = context;
        initDimensions();
        initViews();
    }

    private void initDimensions() {
        mMoveEnd = mContext.getResources().getDimensionPixelSize(R.dimen.kgd_fp_ql_image_move_end);
        mMoveStart = mMoveEnd - mContext.getResources().getDimensionPixelSize(R.dimen.kgd_fp_ql_image_move_distance);
        mIconLayoutSize = mContext.getResources().getDimensionPixelSize(R.dimen.kgd_fp_ql_image_layout_size);
    }

    private void initViews() {
        setBackgroundColor(Color.TRANSPARENT);

        int roundIconSize = mContext.getResources().getDimensionPixelSize(R.dimen.kgd_fp_ql_round_icon_size);
        mRoundIcon = new QuickLaunchRoundIcon(mContext);
        FrameLayout.LayoutParams roundLp = new FrameLayout.LayoutParams(roundIconSize, roundIconSize);
        roundLp.gravity = Gravity.TOP | Gravity.START;
        addView(mRoundIcon, roundLp);

        LayoutInflater inflater = LayoutInflater.from(mContext);
        int editSize = mContext.getResources().getDimensionPixelSize(R.dimen.kgd_fp_ql_edit_layout_size);
        mEditView = (QuickLaunchEditView) inflater.inflate(R.layout.kgd_fp_ql_edit_layout, this, false);
        FrameLayout.LayoutParams editLp = new FrameLayout.LayoutParams(editSize, editSize);
        editLp.gravity = Gravity.TOP | Gravity.START;
        addView(mEditView, editLp);

        mTextLayout = new LinearLayout(mContext);
        mTextLayout.setOrientation(LinearLayout.VERTICAL);
        mTextLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        float density = mContext.getResources().getDisplayMetrics().density;
        int horizontalPadding = (int) (24 * density);
        mTextLayout.setPaddingRelative(horizontalPadding, 0, horizontalPadding, 0);

        int nameTextSizePx = mContext.getResources().getDimensionPixelSize(
                R.dimen.kgd_fp_ql_app_or_shortcuts_name_text_size);
        mNameTextView = new TextView(mContext);
        mNameTextView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, nameTextSizePx);
        mNameTextView.setTextColor(Color.WHITE);
        mNameTextView.setAlpha(0.85f);
        mNameTextView.setGravity(Gravity.CENTER);
        mNameTextView.setMaxLines(1);
        mNameTextView.setEllipsize(TextUtils.TruncateAt.END);
        mTextLayout.addView(mNameTextView, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        int labelTextSizePx = mContext.getResources().getDimensionPixelSize(
                R.dimen.kgd_fp_ql_shortcuts_label_text_size);
        mLabelTextView = new TextView(mContext);
        mLabelTextView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, labelTextSizePx);
        mLabelTextView.setTextColor(Color.WHITE);
        mLabelTextView.setAlpha(0.45f);
        mLabelTextView.setGravity(Gravity.CENTER);
        mLabelTextView.setMaxLines(1);
        mLabelTextView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = mContext.getResources().getDimensionPixelSize(R.dimen.kgd_fp_ql_shortcuts_label_margin_top);
        mTextLayout.addView(mLabelTextView, labelLp);

        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        textLp.gravity = Gravity.TOP | Gravity.START;
        addView(mTextLayout, textLp);

                for (int i = 0; i < QuickLaunchItem.MAX_ITEMS; i++) {
            FrameLayout slotView = (FrameLayout) inflater.inflate(R.layout.kgd_fp_ql_image_layout, this, false);
            slotView.setVisibility(View.GONE);
            FrameLayout.LayoutParams slotLp = new FrameLayout.LayoutParams(mIconLayoutSize, mIconLayoutSize);
            slotLp.gravity = Gravity.TOP | Gravity.START;
            addView(slotView, slotLp);
            mSlotViews.add(slotView);
        }
    }

    public void setActionListener(OnQuickLaunchActionListener listener) {
        mListener = listener;
    }

    public void setSensorCenter(Point sensorCenter) {
        if (sensorCenter != null) {
            mSensorCenterX = sensorCenter.x;
            mSensorCenterY = sensorCenter.y;
        } else {
                        int width = mContext.getResources().getDisplayMetrics().widthPixels;
            int height = mContext.getResources().getDisplayMetrics().heightPixels;
            mSensorCenterX = width / 2;
            mSensorCenterY = (int) (height * 0.81f);
        }
        updatePositions();
    }

    private void updatePositions() {
        int roundIconSize = mRoundIcon.getLayoutParams().width;
        mRoundIcon.setX(mSensorCenterX - roundIconSize / 2f);
        mRoundIcon.setY(mSensorCenterY - roundIconSize / 2f);

        float density = mContext.getResources().getDisplayMetrics().density;
        int editSize = mEditView.getLayoutParams().width;
        mEditView.setX(mSensorCenterX - editSize / 2f);
        mEditView.setY(mSensorCenterY + (int) (45 * density));

        adjustTextPosition();
    }

    private void adjustTextPosition() {
        if (mTextLayout == null) return;
        float density = mContext.getResources().getDisplayMetrics().density;
                int topSlotEdge = (int) (mSensorCenterY - mMoveEnd - (mIconLayoutSize / 2f));
                int textGap = (int) (28 * density);

        int width = getWidth() > 0 ? getWidth() : mContext.getResources().getDisplayMetrics().widthPixels;
        mTextLayout.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int textHeight = mTextLayout.getMeasuredHeight();
        if (textHeight <= 0) {
            textHeight = (int) (55 * density);
        }

        mTextLayout.setY(topSlotEdge - textGap - textHeight);
    }

    public void setItems(List<QuickLaunchItem> items) {
        mItemList.clear();
        if (items != null) {
            mItemList.addAll(items);
        }
        while (mItemList.size() < QuickLaunchItem.MAX_ITEMS) {
            mItemList.add(new QuickLaunchItem(QuickLaunchItem.VIEW_TYPE_EMPTY, mItemList.size()));
        }

        mIsAllEmpty = true;
        for (int i = 0; i < QuickLaunchItem.MAX_ITEMS; i++) {
            QuickLaunchItem item = mItemList.get(i);
            FrameLayout slotView = mSlotViews.get(i);
            item.setImageLayout(slotView);

            ImageView mainIcon = slotView.findViewById(R.id.image_item);
            ImageView subIcon = slotView.findViewById(R.id.image_app);
            item.setMainImageView(mainIcon);
            item.setSecondImageView(subIcon);

            if (!item.isItemEmpty()) {
                mIsAllEmpty = false;
            }

            if (item.getIcon() != null) {
                mainIcon.setImageDrawable(item.getIcon());
            } else {
                mainIcon.setImageDrawable(ContextCompat.getDrawable(mContext,
                        R.drawable.kgd_fp_quick_launch_empty_icon));
            }

            if (item.getSubIcon() != null) {
                subIcon.setImageDrawable(item.getSubIcon());
                subIcon.setVisibility(View.VISIBLE);
            } else {
                subIcon.setVisibility(View.GONE);
            }
        }
    }

    public void startLongPressAnim() {
        reset();
        updatePositions();
        updateMessage(-1);

        mRoundIcon.startLongPressAnim(null, new QuickLaunchRoundIcon.OnSpreadThresholdListener() {
            @Override
            public void onSpreadThresholdReached() {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                bloomRadialMenu();
            }
        });
    }

    private void bloomRadialMenu() {
        mCanSelect = true;
        animateBackgroundScrim(true);
        mEditView.animateFadeIn();

                                                        for (int i = 0; i < QuickLaunchItem.MAX_ITEMS; i++) {
            FrameLayout slotView = mSlotViews.get(i);
            slotView.setVisibility(View.VISIBLE);
            slotView.setScaleX(1.0f);
            slotView.setScaleY(1.0f);
            slotView.setAlpha(0.0f);

            double angleDeg = 180.0 - (i * 45.0);
            double angleRad = Math.toRadians(angleDeg);

                        float startX = (float) (mSensorCenterX + mMoveStart * Math.cos(angleRad) - mIconLayoutSize / 2f);
            float startY = (float) (mSensorCenterY - mMoveStart * Math.sin(angleRad) - mIconLayoutSize / 2f);
            float endX = (float) (mSensorCenterX + mMoveEnd * Math.cos(angleRad) - mIconLayoutSize / 2f);
            float endY = (float) (mSensorCenterY - mMoveEnd * Math.sin(angleRad) - mIconLayoutSize / 2f);

            slotView.setX(startX);
            slotView.setY(startY);

            slotView.animate().cancel();
            slotView.animate()
                    .x(endX)
                    .y(endY)
                    .alpha(1.0f)
                    .setDuration(417)
                    .setInterpolator(ENTER_INTERPOLATOR)
                    .start();
        }

        updateMessage(100);

        if (mLastPointerX >= 0 && mLastPointerY >= 0) {
            onPointerMove(mLastPointerX, mLastPointerY);
        }
    }

    private void animateBackgroundScrim(boolean show) {
        if (mScrimAnimator != null && mScrimAnimator.isRunning()) {
            mScrimAnimator.cancel();
        }
        int fromColor = show ? Color.TRANSPARENT : SCRIM_COLOR;
        int toColor = show ? SCRIM_COLOR : Color.TRANSPARENT;
        int fromBlur = show ? 0 : MAX_BLUR_RADIUS;
        int toBlur = show ? MAX_BLUR_RADIUS : 0;

        mScrimAnimator = ValueAnimator.ofFloat(0f, 1f);
        mScrimAnimator.setDuration(show ? 333 : 180);
        mScrimAnimator.addUpdateListener(animation -> {
            float frac = animation.getAnimatedFraction();
            setBackgroundColor(interpolateColor(fromColor, toColor, frac));
            int blur = (int) (fromBlur + (toBlur - fromBlur) * frac);
            setBlurRadius(blur);
        });
        mScrimAnimator.start();
    }

    private int interpolateColor(int a, int b, float fraction) {
        int alpha = (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * fraction);
        int red = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * fraction);
        int green = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * fraction);
        int blue = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * fraction);
        return Color.argb(alpha, red, green, blue);
    }

    private void setBlurRadius(int radius) {
        ViewRootImpl root = getViewRootImpl();
        SurfaceControl sc = root != null ? root.getSurfaceControl() : null;
        if (sc != null && sc.isValid()) {
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                t.setBackgroundBlurRadius(sc, radius).apply();
            }
        }
    }

    public void onPointerMove(float x, float y) {
        mLastPointerX = x;
        mLastPointerY = y;

        if (!mCanSelect) {
            double dist = Math.hypot(x - mSensorCenterX, y - mSensorCenterY);
            float density = mContext.getResources().getDisplayMetrics().density;
            if (dist > 25 * density) {
                mRoundIcon.cancelAnim();
                bloomRadialMenu();
            } else {
                return;
            }
        }

        int selectedIndex = hitTest(x, y);
        if (selectedIndex != mLastSelectedIndex) {
            mLastSelectedIndex = selectedIndex;
            highlightItem(selectedIndex);
            updateMessage(selectedIndex);
            if (selectedIndex != -1) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }
    }

    private int hitTest(float x, float y) {
        float density = mContext.getResources().getDisplayMetrics().density;

        if (y > mSensorCenterY + (30 * density)) {
            if (Math.abs(x - mSensorCenterX) <= (60 * density)) {
                return 101;
            }
            return -1;
        }

        double dist = Math.hypot(x - mSensorCenterX, y - mSensorCenterY);
        if (dist < (25 * density)) {
            return -1;
        }

        double dx = x - mSensorCenterX;
        double dy = mSensorCenterY - y; 

        double angle = Math.toDegrees(Math.atan2(dy, dx));
        if (angle < 0) angle += 360;

        if (angle >= 345) {
            return 4;
        } else if (angle >= 0 && angle <= 195) {
            if (angle > 180) angle = 180;
            int slot = 4 - (int) (angle / 36.0);
            return Math.max(0, Math.min(4, slot));
        }

        return -1;
    }

    private void highlightItem(int selectedIndex) {
        for (int i = 0; i < QuickLaunchItem.MAX_ITEMS; i++) {
            FrameLayout slotView = mSlotViews.get(i);
            slotView.animate().cancel();
            if (selectedIndex == i) {
                                slotView.animate()
                        .scaleX(1.136f)
                        .scaleY(1.136f)
                        .alpha(1.0f)
                        .setDuration(250)
                        .setInterpolator(OPTIONAL_INTERPOLATOR)
                        .start();
            } else if (selectedIndex != -1) {
                                slotView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(0.3f)
                        .setDuration(250)
                        .setInterpolator(OPTIONAL_INTERPOLATOR)
                        .start();
            } else {
                                slotView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(1.0f)
                        .setDuration(250)
                        .setInterpolator(OPTIONAL_INTERPOLATOR)
                        .start();
            }
        }

        if (selectedIndex == 101) {
            mEditView.animateChosen();
        } else if (mCanSelect) {
            mEditView.animateFadeIn();
        }
    }

    private void updateMessage(int index) {
        if (!mCanSelect) {
            mNameTextView.setText("");
            mLabelTextView.setText("");
            return;
        }

        if (index == 101) {
            mNameTextView.setText(mContext.getString(R.string.fingerprint_ql_edit_message));
            mLabelTextView.setText("");
        } else if (index >= 0 && index < QuickLaunchItem.MAX_ITEMS) {
            QuickLaunchItem item = mItemList.get(index);
            if (!item.isItemEmpty()) {
                mNameTextView.setText(item.getTitle());
                mLabelTextView.setText(!TextUtils.isEmpty(item.getSubTitle()) ? item.getSubTitle() : "");
            } else {
                mNameTextView.setText(mContext.getString(R.string.fingerprint_ql_recommend_message));
                mLabelTextView.setText(mContext.getString(R.string.fingerprint_ql_recommend_sub_message));
            }
        } else {
            mNameTextView.setText(mContext.getString(R.string.fingerprint_ql_guide_glide_message));
            mLabelTextView.setText("");
        }
        adjustTextPosition();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        float x = event.getRawX();
        float y = event.getRawY();
        if (action == MotionEvent.ACTION_DOWN) {
            onPointerDown(x, y);
            return true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            onPointerMove(x, y);
            return true;
        } else if (action == MotionEvent.ACTION_UP) {
            onPointerUp(x, y);
            return true;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            if (mListener != null) {
                mListener.onDismissed();
            }
            return true;
        }
        return true;
    }

    public void onPointerDown(float x, float y) {
        mLastPointerX = x;
        mLastPointerY = y;
        if (!mCanSelect) return;
        int selectedIndex = hitTest(x, y);
        if (selectedIndex != mLastSelectedIndex) {
            mLastSelectedIndex = selectedIndex;
            highlightItem(selectedIndex);
            updateMessage(selectedIndex);
            if (selectedIndex != -1) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }
    }

    public void onPointerUp(float x, float y) {
        if (!mCanSelect) {
            mRoundIcon.startLongPressRevokeAnim();
            if (mListener != null) {
                mListener.onDismissed();
            }
            return;
        }

        int selectedIndex = hitTest(x, y);
        if (selectedIndex >= 0 && selectedIndex < QuickLaunchItem.MAX_ITEMS) {
            QuickLaunchItem item = mItemList.get(selectedIndex);
            if (!item.isItemEmpty()) {
                                animateLaunchItem(selectedIndex, () -> {
                    if (mListener != null) {
                        mListener.onItemLaunched(item, selectedIndex);
                    }
                });
                return;
            }
        } else if (selectedIndex == 101) {
                        animateExit(() -> {
                if (mListener != null) {
                    mListener.onEditRequested();
                }
            });
            return;
        }

                animateExit(() -> {
            if (mListener != null) {
                mListener.onDismissed();
            }
        });
    }

    private void animateLaunchItem(int selectedIndex, Runnable onComplete) {
        animateBackgroundScrim(false);
        mEditView.animateFadeOut();
        mNameTextView.setText("");
        mLabelTextView.setText("");

        for (int i = 0; i < QuickLaunchItem.MAX_ITEMS; i++) {
            FrameLayout slotView = mSlotViews.get(i);
            slotView.animate().cancel();
            if (i == selectedIndex) {
                slotView.animate()
                        .scaleX(1.6f)
                        .scaleY(1.6f)
                        .alpha(0.0f)
                        .setDuration(250)
                        .setInterpolator(OPENED_INTERPOLATOR)
                        .withEndAction(onComplete != null ? onComplete : () -> {})
                        .start();
            } else {
                slotView.animate()
                        .alpha(0.0f)
                        .setDuration(167)
                        .setInterpolator(OPENED_INTERPOLATOR)
                        .start();
            }
        }
    }

    private void animateExit(Runnable onComplete) {
        animateBackgroundScrim(false);
        mEditView.animateFadeOut();
        mNameTextView.setText("");
        mLabelTextView.setText("");

        for (FrameLayout slotView : mSlotViews) {
            slotView.animate().cancel();
            slotView.animate()
                    .alpha(0.0f)
                    .setDuration(167)
                    .setInterpolator(OPENED_INTERPOLATOR)
                    .start();
        }

        postDelayed(onComplete != null ? onComplete : () -> {}, 180);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        reset();
        if (mScrimAnimator != null) {
            mScrimAnimator.cancel();
        }
    }

    public void reset() {
        mCanSelect = false;
        mLastSelectedIndex = -1;
        mLastPointerX = -1f;
        mLastPointerY = -1f;
        mRoundIcon.cancelAnim();
        mEditView.reset();
        for (FrameLayout slotView : mSlotViews) {
            slotView.animate().cancel();
            slotView.setVisibility(View.GONE);
            slotView.setAlpha(0.0f);
            slotView.setScaleX(1.0f);
            slotView.setScaleY(1.0f);
        }
        setBackgroundColor(Color.TRANSPARENT);
        setBlurRadius(0);
        mNameTextView.setText("");
        mLabelTextView.setText("");
    }
}
