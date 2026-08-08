/*
 * Copyright (C) 2024-2025 Lunaris AOSP
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
package com.android.systemui.statusbar.phone;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.media.MediaSessionManager;
import com.android.systemui.scrim.ScrimView;
import com.android.systemui.util.ScrimUtils;

public class MediaArtScrimController implements MediaSessionManager.MediaDataListener,
        ScrimUtils.ScrimEventListener {
    
    private static final String TAG = "MediaArtScrimController";
    private static final float BLUR_RADIUS = 130f;
    private static final float MIN_QS_EXPANSION_FOR_MEDIA_ART = 0.15f;
    private static final float MIN_QS_EXPANSION_FOR_REMOVAL = 0.05f;
    private static final int MAX_BITMAP_SIZE = 400;
    private static final int DEFAULT_DIM_AMOUNT = 10;
    
    private static final long ARTWORK_UPDATE_DEBOUNCE_MS = 200L;
    
    private static final int CROSSFADE_DURATION_MS = 300;
    private static final boolean ENABLE_CROSSFADE = true;
    
    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final Handler mHandler;
    private final MediaSessionManager mMediaSessionManager;
    
    private ScrimView mNotificationsScrim;
    private ScrimView mScrimBehind;
    
    private boolean mMediaArtScrimEnabled = false;
    private int mMediaArtDimAmount = DEFAULT_DIM_AMOUNT;
    private Drawable mCurrentMediaArtwork;
    private boolean mHasActiveMedia = false;
    private RenderEffect mBlurEffect;
    private boolean mListening = false;
    private boolean mIsApplied = false;
    
    private float mOriginalBehindAlpha = -1f;
    private Drawable mOriginalScrimBackground = null;
    private int mOriginalTint = -1;
    private boolean mKeyguardShowing = false;
    private boolean mBouncerShowing = false;
    private boolean mKeyguardGoingAway = false;
    private float mQsExpansion = 0f;
    private float mLastAppliedExpansion = 0f;
    private Bitmap mCurrentBitmap = null;
    private boolean mBrightnessMirrorShowing = false;
    
    private Runnable mPendingStateUpdate = null;
    private static final long STATE_UPDATE_DELAY_MS = 30;
    
    private Runnable mPendingArtworkUpdate = null;
    private Drawable mPendingArtwork = null;
    
    private Drawable mLastAppliedArtwork = null;

    private final ContentObserver mSettingsObserver = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    };
    
    public MediaArtScrimController(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();
        mHandler = new Handler(Looper.getMainLooper());
        
        mMediaSessionManager = MediaSessionManager.Companion.get();
        
        mBlurEffect = RenderEffect.createBlurEffect(
                BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.MIRROR);
        
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.QS_MEDIA_ART_SCRIM_ENABLED),
                false,
                mSettingsObserver,
                UserHandle.USER_ALL
        );
        
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.QS_MEDIA_ART_DIM_AMOUNT),
                false,
                mSettingsObserver,
                UserHandle.USER_ALL
        );
        
        updateSettings();
    }
    
    public void attachViews(ScrimView notificationsScrim, ScrimView scrimBehind) {
        mNotificationsScrim = notificationsScrim;
        mScrimBehind = scrimBehind;
        saveOriginalScrimState();
    }
    
    private void saveOriginalScrimState() {
        if (mNotificationsScrim != null && mOriginalTint == -1) {
            mOriginalTint = mNotificationsScrim.getTint();
            mOriginalScrimBackground = mNotificationsScrim.getBackground();
        }
        if (mScrimBehind != null && mOriginalBehindAlpha < 0) {
            mOriginalBehindAlpha = mScrimBehind.getViewAlpha();
        }
    }
    
    private void updateSettings() {
        boolean enabled = Settings.System.getIntForUser(
                mContentResolver,
                Settings.System.QS_MEDIA_ART_SCRIM_ENABLED,
                0,
                UserHandle.USER_CURRENT
        ) == 1;
        
        int dimAmount = Settings.System.getIntForUser(
                mContentResolver,
                Settings.System.QS_MEDIA_ART_DIM_AMOUNT,
                DEFAULT_DIM_AMOUNT,
                UserHandle.USER_CURRENT
        );
        
        boolean enabledChanged = mMediaArtScrimEnabled != enabled;
        boolean dimChanged = mMediaArtDimAmount != dimAmount;
        
        mMediaArtScrimEnabled = enabled;
        mMediaArtDimAmount = dimAmount;
        
        if (enabledChanged) {
            if (enabled && !mListening) {
                mMediaSessionManager.addListener(this);
                ScrimUtils.get().addListener(this);
                mListening = true;
            } else if (!enabled && mListening) {
                mMediaSessionManager.removeListener(this);
                ScrimUtils.get().removeListener(this);
                mListening = false;
                restoreRegularScrim();
            }
            
            scheduleStateUpdate();
        } else if (dimChanged && mIsApplied) {
            mIsApplied = false;
            scheduleStateUpdate();
        }
    }
    
    private boolean shouldShowMediaArt() {
        if (!mMediaArtScrimEnabled) return false;
        if (mCurrentMediaArtwork == null || !mHasActiveMedia) return false;
        if (mKeyguardShowing) return false;
        if (mBouncerShowing) return false;
        if (mKeyguardGoingAway) return false;
        if (mBrightnessMirrorShowing) return true;
        if (mQsExpansion < MIN_QS_EXPANSION_FOR_MEDIA_ART) return false;
        return true;
    }
    
    private boolean canShowMediaArt() {
        return mMediaArtScrimEnabled 
            && mCurrentMediaArtwork != null 
            && mHasActiveMedia
            && !mKeyguardShowing
            && !mBouncerShowing
            && !mKeyguardGoingAway;
    }
    
    @Override
    public void onAlbumArtChanged(Drawable drawable) {
        if (mPendingArtworkUpdate != null) {
            mHandler.removeCallbacks(mPendingArtworkUpdate);
            mPendingArtworkUpdate = null;
        }
        
        mPendingArtwork = drawable;
        
        if (isSameArtwork(mPendingArtwork, mCurrentMediaArtwork)) {
            return;
        }
        
        mPendingArtworkUpdate = () -> {
            mCurrentMediaArtwork = mPendingArtwork;
            mPendingArtworkUpdate = null;
            
            if (mMediaArtScrimEnabled) {
                if (mIsApplied) {
                    if (mPendingStateUpdate != null) {
                        mHandler.removeCallbacks(mPendingStateUpdate);
                    }
                    
                    mPendingStateUpdate = () -> {
                        mPendingStateUpdate = null;
                        mIsApplied = false;
                        updateScrimState();
                    };
                    
                    mHandler.postDelayed(mPendingStateUpdate, 150);
                } else {
                    scheduleStateUpdate();
                }
            }
        };
        
        mHandler.postDelayed(mPendingArtworkUpdate, ARTWORK_UPDATE_DEBOUNCE_MS);
    }
    
    private boolean isSameArtwork(Drawable drawable1, Drawable drawable2) {
        if (drawable1 == drawable2) return true;
        if (drawable1 == null || drawable2 == null) return false;
        
        if (drawable1.getIntrinsicWidth() != drawable2.getIntrinsicWidth() ||
            drawable1.getIntrinsicHeight() != drawable2.getIntrinsicHeight()) {
            return false;
        }
        
        if (drawable1 instanceof BitmapDrawable && drawable2 instanceof BitmapDrawable) {
            Bitmap bitmap1 = ((BitmapDrawable) drawable1).getBitmap();
            Bitmap bitmap2 = ((BitmapDrawable) drawable2).getBitmap();
            return bitmap1 != null && bitmap1.sameAs(bitmap2);
        }
        
        return false;
    }
    
    @Override
    public void onPlaybackStateChanged(int state) {
        boolean wasActive = mHasActiveMedia;
        mHasActiveMedia = (state == PlaybackState.STATE_PLAYING);
        
        if (!mHasActiveMedia) {
            mCurrentMediaArtwork = null;
            mLastAppliedArtwork = null;
            if (mIsApplied) {
                mHandler.post(() -> {
                    restoreRegularScrimImmediate();
                });
            } else {
                cleanupBitmap();
            }
            
            if (mPendingArtworkUpdate != null) {
                mHandler.removeCallbacks(mPendingArtworkUpdate);
                mPendingArtworkUpdate = null;
            }
        }
        
        if (mMediaArtScrimEnabled && wasActive != mHasActiveMedia) {
            scheduleStateUpdate();
        }
    }
    
    @Override
    public void onMediaColorsChanged(int color) {
    }
    
    @Override
    public void onMetadataChanged(String track, String artist) {
    }
    
    public void setBrightnessMirrorShowing(boolean showing) {
        Log.d(TAG, "setBrightnessMirrorShowing: " + showing + ", mIsApplied: " + mIsApplied);
        
        boolean wasShowing = mBrightnessMirrorShowing;
        mBrightnessMirrorShowing = showing;
        
        if (showing && mIsApplied) {
            if (mNotificationsScrim != null) {
                mNotificationsScrim.setAlpha(0f);
            }
        } else if (!showing && wasShowing && mIsApplied) {
            if (mNotificationsScrim != null) {
                mNotificationsScrim.setRenderEffect(mBlurEffect);
                mNotificationsScrim.setViewAlpha(mQsExpansion);
                mNotificationsScrim.setAlpha(mQsExpansion);
            }
        } else if (!showing && !mIsApplied && canShowMediaArt()) {
            scheduleStateUpdate();
        }
    }
    
    public void onPanelExpansionChanged(float expansion) {
        if (!mMediaArtScrimEnabled || mNotificationsScrim == null) {
            return;
        }
        
        float oldExpansion = mQsExpansion;
        mQsExpansion = expansion;
        
        if (mBrightnessMirrorShowing) {
            return;
        }
        
        if (!canShowMediaArt() && mIsApplied) {
            restoreRegularScrimImmediate();
            return;
        }
        
        boolean wasAboveThreshold = oldExpansion >= MIN_QS_EXPANSION_FOR_MEDIA_ART;
        boolean isAboveThreshold = expansion >= MIN_QS_EXPANSION_FOR_MEDIA_ART;
        
        if (expansion < MIN_QS_EXPANSION_FOR_REMOVAL && mIsApplied) {
            restoreRegularScrimImmediate();
            return;
        }
        
        if (wasAboveThreshold != isAboveThreshold) {
            if (mPendingStateUpdate != null) {
                mHandler.removeCallbacks(mPendingStateUpdate);
                mPendingStateUpdate = null;
            }
            updateScrimState();
        } else if (mIsApplied && shouldShowMediaArt()) {
            mNotificationsScrim.setAlpha(expansion);
            mLastAppliedExpansion = expansion;
        } else if (mIsApplied && !shouldShowMediaArt()) {
            restoreRegularScrimImmediate();
        }
    }

    private void scheduleStateUpdate() {
        if (mPendingStateUpdate != null) {
            mHandler.removeCallbacks(mPendingStateUpdate);
        }
        
        mPendingStateUpdate = () -> {
            mPendingStateUpdate = null;
            updateScrimState();
        };
        
        mHandler.postDelayed(mPendingStateUpdate, STATE_UPDATE_DELAY_MS);
    }
    
    private void updateScrimState() {
        if (mNotificationsScrim == null) {
            return;
        }
        
        if (shouldShowMediaArt()) {
            applyMediaArt();
        } else {
            restoreRegularScrimImmediate();
        }
    }
    
    private void applyMediaArt() {
        if (!shouldShowMediaArt()) {
            if (mIsApplied) {
                restoreRegularScrimImmediate();
            }
            return;
        }
        
        boolean isReapplying = mIsApplied;
        
        if (mOriginalTint == -1) {
            saveOriginalScrimState();
        }
        
        Bitmap bitmap = drawableToBitmap(mCurrentMediaArtwork);
        if (bitmap != null) {
            final Bitmap oldBitmapToRecycle = mCurrentBitmap;

            mCurrentBitmap = applyDimToBitmap(bitmap);
            BitmapDrawable newDrawable = new BitmapDrawable(
                    mContext.getResources(), mCurrentBitmap);

            if (ENABLE_CROSSFADE && isReapplying 
                    && mLastAppliedArtwork != null 
                    && mNotificationsScrim.getBackground() != null
                    && !mBrightnessMirrorShowing) {
                applyCrossfadeTransition(newDrawable);
                if (oldBitmapToRecycle != null) {
                    mHandler.postDelayed(() -> recycleBitmapSafely(oldBitmapToRecycle),
                            CROSSFADE_DURATION_MS);
                }
            } else {
                mNotificationsScrim.setBackground(newDrawable);
                recycleBitmapSafely(oldBitmapToRecycle);
            }
            
            mLastAppliedArtwork = mCurrentMediaArtwork;
            
            mNotificationsScrim.setMediaArtApplied(true);
            mNotificationsScrim.setRenderEffect(mBlurEffect);
            mNotificationsScrim.setTint(android.graphics.Color.TRANSPARENT);
            mNotificationsScrim.setViewAlpha(mQsExpansion);
            
            mIsApplied = true;
            mLastAppliedExpansion = mQsExpansion;
            
            Log.d(TAG, "Applied media art to notifications scrim with dim amount: " 
                + mMediaArtDimAmount + (isReapplying && ENABLE_CROSSFADE ? " (with crossfade)" : ""));
        } else {
            Log.e(TAG, "Failed to create bitmap from artwork");
        }
    }
    
    private void applyCrossfadeTransition(Drawable newDrawable) {
        try {
            Drawable currentBackground = mNotificationsScrim.getBackground();
            
            if (currentBackground != null && currentBackground instanceof BitmapDrawable) {
                BitmapDrawable oldDrawable = (BitmapDrawable) currentBackground;
                Bitmap oldBitmap = oldDrawable.getBitmap();
                
                if (oldBitmap != null && !oldBitmap.isRecycled()) {
                    Drawable[] layers = new Drawable[] {
                        new BitmapDrawable(mContext.getResources(), oldBitmap),
                        newDrawable
                    };
                    
                    TransitionDrawable transition = new TransitionDrawable(layers);
                    transition.setCrossFadeEnabled(true);
                    
                    mNotificationsScrim.setBackground(transition);
                    
                    transition.startTransition(CROSSFADE_DURATION_MS);
                    
                    Log.d(TAG, "Started crossfade transition (" + CROSSFADE_DURATION_MS + "ms)");
                    return;
                }
            }
            
            mNotificationsScrim.setBackground(newDrawable);
        } catch (Exception e) {
            Log.e(TAG, "Error during crossfade transition, falling back to direct set", e);
            try {
                mNotificationsScrim.setBackground(newDrawable);
            } catch (Exception ex) {
                Log.e(TAG, "Critical error setting background", ex);
            }
        }
    }
    
    private Bitmap applyDimToBitmap(Bitmap source) {
        if (source == null || mMediaArtDimAmount <= 0) {
            return source;
        }
        
        try {
            Bitmap dimmedBitmap = source.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(dimmedBitmap);
            
            float dimFactor = mMediaArtDimAmount / 100f;
            int overlayAlpha = (int) (dimFactor * 200);
            
            canvas.drawColor(Color.argb(overlayAlpha, 0, 0, 0));
            
            Log.d(TAG, "Applied dim factor: " + dimFactor + " (alpha: " + overlayAlpha + ")");
            return dimmedBitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error applying dim to bitmap", e);
            return source;
        }
    }
    
    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable == null) return null;
        
        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null && !bitmap.isRecycled()) {
                try {
                    return scaleBitmapSafely(bitmap);
                } catch (Exception e) {
                    Log.e(TAG, "Error copying bitmap", e);
                    return null;
                }
            }
        }
        
        try {
            int width = drawable.getIntrinsicWidth();
            int height = drawable.getIntrinsicHeight();
            
            if (width <= 0 || height <= 0) {
                width = MAX_BITMAP_SIZE;
                height = MAX_BITMAP_SIZE;
            }
            
            if (width > MAX_BITMAP_SIZE || height > MAX_BITMAP_SIZE) {
                float scale = Math.min(
                    (float) MAX_BITMAP_SIZE / width,
                    (float) MAX_BITMAP_SIZE / height
                );
                width = (int) (width * scale);
                height = (int) (height * scale);
            }
            
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error converting drawable to bitmap", e);
            return null;
        }
    }
    
    private Bitmap scaleBitmapSafely(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        
        int width = source.getWidth();
        int height = source.getHeight();
        
        if (width <= MAX_BITMAP_SIZE && height <= MAX_BITMAP_SIZE) {
            return source.copy(Bitmap.Config.ARGB_8888, false);
        }
        
        float scale = Math.min(
            (float) MAX_BITMAP_SIZE / width,
            (float) MAX_BITMAP_SIZE / height
        );
        
        int scaledWidth = (int) (width * scale);
        int scaledHeight = (int) (height * scale);
        
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(
                source, scaledWidth, scaledHeight, true);
            
            if (scaled == source) {
                return source.copy(Bitmap.Config.ARGB_8888, false);
            }
            
            Log.d(TAG, "Scaled bitmap from " + width + "x" + height 
                + " to " + scaledWidth + "x" + scaledHeight);
            return scaled;
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Out of memory scaling bitmap", e);
            try {
                int smallWidth = Math.min(width / 2, MAX_BITMAP_SIZE);
                int smallHeight = Math.min(height / 2, MAX_BITMAP_SIZE);
                return Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to create fallback bitmap", ex);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error scaling bitmap", e);
            return null;
        }
    }

    private void recycleBitmapSafely(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        try {
            bitmap.recycle();
        } catch (Exception e) {
            Log.e(TAG, "Error recycling old artwork bitmap", e);
        }
    }
    
    private void restoreRegularScrimImmediate() {
        if (mNotificationsScrim == null || !mIsApplied) {
            return;
        }
        
        if (mBrightnessMirrorShowing) {
            return;
        }
        
        if (mPendingStateUpdate != null) {
            mHandler.removeCallbacks(mPendingStateUpdate);
            mPendingStateUpdate = null;
        }
        
        mNotificationsScrim.setBackground(null);
        mNotificationsScrim.setRenderEffect(null);
        mNotificationsScrim.setMediaArtApplied(false);
        
        mIsApplied = false;
        mLastAppliedExpansion = 0f;
        mLastAppliedArtwork = null;
        
        cleanupBitmap();
        
        mOriginalTint = -1;
        mOriginalScrimBackground = null;
        mOriginalBehindAlpha = -1f;
        
        Log.d(TAG, "Restored regular scrim immediately");
    }
    
    private void restoreRegularScrim() {
        restoreRegularScrimImmediate();
    }

    private void cleanupBitmap() {
        if (mCurrentBitmap != null) {
            final int width = mCurrentBitmap.getWidth();
            final int height = mCurrentBitmap.getHeight();
            final boolean wasRecycled = mCurrentBitmap.isRecycled();
            if (!wasRecycled) {
                try {
                    mCurrentBitmap.recycle();
                    Log.d(TAG, "Recycled bitmap: " + width + "x" + height 
                        + " (~" + (width * height * 4 / 1024) + "KB)");
                } catch (Exception e) {
                    Log.e(TAG, "Error recycling bitmap", e);
                }
            } else {
                Log.w(TAG, "Attempted to recycle already recycled bitmap");
            }
            mCurrentBitmap = null;
        }
    }
    
    @Override
    public void onKeyguardShowingChanged(boolean showing) {
        mKeyguardShowing = showing;
        if (showing) {
            mHandler.post(this::restoreRegularScrimImmediate);
        } else {
            scheduleStateUpdate();
        }
    }
    
    @Override
    public void onPrimaryBouncerShowingChanged(boolean showing) {
        mBouncerShowing = showing;
        if (showing) {
            mHandler.post(this::restoreRegularScrimImmediate);
        } else {
            scheduleStateUpdate();
        }
    }
    
    @Override
    public void onKeyguardGoingAwayChanged(boolean goingAway) {
        mKeyguardGoingAway = goingAway;
        if (goingAway) {
            mHandler.post(this::restoreRegularScrimImmediate);
        } else {
            scheduleStateUpdate();
        }
    }
    
    @Override
    public void onKeyguardFadingAwayChanged(boolean fadingAway) {
        if (fadingAway) {
            mHandler.post(this::restoreRegularScrimImmediate);
        }
    }
    
    @Override
    public void onDozingChanged(boolean dozing) {
    }
    
    @Override
    public void setPulsing(boolean pulsing) {
    }
    
    @Override
    public void onExpandedFractionChanged(float expandedFraction) {
    }
    
    @Override
    public void onBarStateChanged(int state) {
    }
    
    @Override
    public void onQsVisibilityChanged(boolean visible) {
        if (!visible) {
            mHandler.post(this::restoreRegularScrimImmediate);
        }
    }
    
    @Override
    public void onScreenTurnedOff() {
        mHandler.post(this::restoreRegularScrimImmediate);
    }
    
    @Override
    public void onStartedWakingUp() {
        scheduleStateUpdate();
    }
    
    public boolean isEnabled() {
        return mMediaArtScrimEnabled;
    }
    
    public boolean isMediaArtApplied() {
        return mIsApplied;
    }
    
    public int getDimAmount() {
        return mMediaArtDimAmount;
    }
    
    public boolean shouldSkipNotificationsScrimUpdate() {
        return mIsApplied;
    }
    
    public void destroy() {
        if (mPendingStateUpdate != null) {
            mHandler.removeCallbacks(mPendingStateUpdate);
            mPendingStateUpdate = null;
        }
        
        if (mPendingArtworkUpdate != null) {
            mHandler.removeCallbacks(mPendingArtworkUpdate);
            mPendingArtworkUpdate = null;
        }
        
        mContentResolver.unregisterContentObserver(mSettingsObserver);
        if (mListening) {
            mMediaSessionManager.removeListener(this);
            ScrimUtils.get().removeListener(this);
            mListening = false;
        }
        restoreRegularScrim();
        cleanupBitmap();
        mCurrentMediaArtwork = null;
        mLastAppliedArtwork = null;
        mPendingArtwork = null;
        mOriginalScrimBackground = null;
        mBlurEffect = null;
    }
}
