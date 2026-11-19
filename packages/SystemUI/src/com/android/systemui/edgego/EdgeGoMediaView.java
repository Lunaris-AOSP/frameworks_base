/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.systemui.edgego;

import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;

import androidx.core.graphics.ColorUtils;

import com.android.internal.util.lunaris.EdgeGoConfig;
import com.android.systemui.res.R;

import java.util.Objects;
import java.util.function.BooleanSupplier;

final class EdgeGoMediaView extends LinearLayout {
    private final BooleanSupplier canRun;
    private final Runnable touched;
    private final FrameLayout cover;
    private final ImageView artwork;
    private final TextView title, artist, elapsed, total;
    private final ImageButton previous, playPause, next;
    private final EdgeGoLevelView progress;
    private final boolean showArtwork;
    private Button rewind, forward;
    private final float dp;
    private MediaController media, scrubbingMedia;
    private Bitmap bitmap;
    private String track;
    private long duration, lastSeek;
    private float requestedLevel;
    private boolean playing, scrubbing;

    EdgeGoMediaView(Context context, EdgeGoConfig config, EdgeGoIcons icons,
            boolean seekOnly, boolean compact, BooleanSupplier canRun, Runnable touched) {
        super(context);
        this.canRun = canRun;
        this.touched = touched;
        showArtwork = !seekOnly && !compact;
        dp = getResources().getDisplayMetrics().density * config.integer("floating_panel_scale") / 100f;
        setOrientation(VERTICAL);
        if (ValueAnimator.areAnimatorsEnabled()) {
            LayoutTransition transition = new LayoutTransition();
            transition.setDuration(220);
            transition.setAnimateParentHierarchy(false);
            setLayoutTransition(transition);
        }
        int foreground = icons.text(config), surface = icons.container(config);
        cover = new FrameLayout(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(surface);
        shape.setCornerRadius(24 * dp);
        cover.setBackground(shape);
        cover.setClipToOutline(true);
        artwork = new ImageView(context);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        cover.addView(artwork, new FrameLayout.LayoutParams(-1, -1));
        if (config.bool("media_controller_artwork_shading_enabled")) {
            View shade = new View(context);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, (surface & 0x00ffffff) | 0x66000000}));
            cover.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        }
        if (showArtwork) addView(cover, new LayoutParams(-1, (int) (152 * dp)));
        cover.setVisibility(GONE);
        title = label(compact ? 18 : 22, foreground);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        title.setPadding(0, (int) (12 * dp), 0, (int) (2 * dp));
        addView(title, new LayoutParams(-1, -2));
        artist = label(14, ColorUtils.blendARGB(foreground, surface, .3f));
        artist.setMaxLines(1);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        addView(artist, new LayoutParams(-1, -2));
        LinearLayout controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, (int) (12 * dp), 0, (int) (8 * dp));
        previous = control(config, surface, foreground, R.drawable.ic_skip_previous_filled,
                R.string.edgego_previous, () -> media.getTransportControls().skipToPrevious());
        next = control(config, surface, foreground, R.drawable.ic_skip_next_filled,
                R.string.edgego_next, () -> media.getTransportControls().skipToNext());
        playPause = control(config, icons.accent(config), icons.onAccent(config), R.drawable.ic_media_play,
                R.string.edgego_play, () -> {
                    if (playing) media.getTransportControls().pause(); else media.getTransportControls().play();
                });
        float density = getResources().getDisplayMetrics().density;
        int small = (int) Math.max(48 * density, (compact ? 48 : 56) * dp);
        controls.addView(previous, new LayoutParams(small, small));
        LayoutParams primary = new LayoutParams((int) Math.max(64 * density, (compact ? 80 : 88) * dp), (int) Math.max(56 * density, 64 * dp));
        primary.setMargins((int) (8 * dp), 0, (int) (8 * dp), 0);
        controls.addView(playPause, primary);
        controls.addView(next, new LayoutParams(small, small));
        addView(controls, new LayoutParams(-1, -2));
        boolean showProgress = seekOnly || config.bool(compact ? "quick_tools_show_media_progress" : "media_controller_show_progress");
        progress = new EdgeGoLevelView(context, false, "system_pill", icons.accent(config), surface,
                0, value -> seek(value, false));
        progress.setContentDescription(context.getString(R.string.edgego_seek));
        progress.setHapticFeedbackEnabled(config.bool("haptic_feedback"));
        progress.setTrackingListener(active -> {
            scrubbing = active;
            if (active) scrubbingMedia = media;
            else { seek(progress.level(), true); scrubbingMedia = null; }
            touched.run();
        });
        if (showProgress) addView(progress, new LayoutParams(-1, (int) Math.max(56 * density, 56 * dp)));
        elapsed = label(12, foreground);
        total = label(12, foreground);
        total.setGravity(Gravity.END);
        LinearLayout times = new LinearLayout(context);
        times.addView(elapsed, new LayoutParams(0, -2, 1));
        times.addView(total, new LayoutParams(0, -2, 1));
        times.setPadding((int) (12 * dp), 0, (int) (12 * dp), (int) (8 * dp));
        if (showProgress) addView(times, new LayoutParams(-1, -2));
        if (compact) {
            LinearLayout seekControls = new LinearLayout(context);
            rewind = seekControl(config, icons, R.string.edgego_back_15_short, R.string.edgego_rewind_15, -15000);
            forward = seekControl(config, icons, R.string.edgego_forward_15_short, R.string.edgego_forward_15, 15000);
            LayoutParams first = new LayoutParams(0, (int) Math.max(48 * density, 48 * dp), 1);
            first.setMargins(0, (int) (4 * dp), (int) (4 * dp), 0);
            seekControls.addView(rewind, first);
            LayoutParams second = new LayoutParams(0, (int) Math.max(48 * density, 48 * dp), 1);
            second.setMargins((int) (4 * dp), (int) (4 * dp), 0, 0);
            seekControls.addView(forward, second);
            addView(seekControls, new LayoutParams(-1, -2));
        }
    }

    private TextView label(int size, int color) {
        TextView view = new TextView(getContext());
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private ImageButton control(EdgeGoConfig config, int surface, int foreground, int icon,
            int description, Runnable action) {
        ImageButton button = EdgeGoUi.iconButton(getContext(), config, surface, foreground, 24 * dp);
        button.setImageResource(icon);
        button.setPadding((int) (14 * dp), (int) (14 * dp), (int) (14 * dp), (int) (14 * dp));
        button.setContentDescription(getContext().getString(description));
        button.setOnClickListener(view -> {
            if (media == null || !canRun.getAsBoolean()) return;
            try {
                action.run();
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            } catch (RuntimeException ignored) { }
            touched.run();
        });
        return button;
    }

    private Button seekControl(EdgeGoConfig config, EdgeGoIcons icons, int label, int description, long delta) {
        Button button = EdgeGoUi.button(getContext(), config, icons.container(config), icons.text(config), 24 * dp);
        button.setText(label);
        button.setContentDescription(getContext().getString(description));
        button.setOnClickListener(view -> {
            if (media == null || duration <= 0 || !canRun.getAsBoolean()) return;
            PlaybackState state = media.getPlaybackState();
            if (state == null || (state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0) return;
            long position = state.getPosition();
            if (state.getState() == PlaybackState.STATE_PLAYING) position += Math.round(
                    (SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
            seek(Math.max(0, Math.min(1, (position + delta) / (float) duration)), true);
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        });
        return button;
    }

    void update(MediaController controller, boolean showMetadata) {
        MediaMetadata metadata = controller == null || !showMetadata ? null : controller.getMetadata();
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        long nextDuration = metadata == null ? 0 : Math.max(0, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        String nextTrack = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                + "|" + metadata.getString(MediaMetadata.METADATA_KEY_TITLE) + "|" + nextDuration;
        if (!Objects.equals(track, nextTrack) || !sameSession(media, controller)) {
            scrubbingMedia = null;
            lastSeek = 0;
            progress.setEnabled(false);
        }
        track = nextTrack;
        media = controller;
        duration = nextDuration;
        CharSequence name = metadata == null ? null : metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
        setText(title, name == null ? getContext().getString(controller != null && !showMetadata
                ? R.string.edgego_media_details_hidden : R.string.edgego_no_media) : name);
        CharSequence by = metadata == null ? null : metadata.getText(MediaMetadata.METADATA_KEY_ARTIST);
        setText(artist, TextUtils.isEmpty(by) ? "" : by);
        artist.setVisibility(TextUtils.isEmpty(by) ? GONE : VISIBLE);
        Bitmap art = metadata == null || !showArtwork ? null : metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null && metadata != null && showArtwork) art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art != bitmap) { bitmap = art; artwork.setImageBitmap(art); }
        cover.setVisibility(art == null ? GONE : VISIBLE);
        boolean active = state != null && (state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_BUFFERING);
        if (playing != active) {
            playing = active;
            playPause.setImageResource(active ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
            playPause.setContentDescription(getContext().getString(active ? R.string.edgego_pause : R.string.edgego_play));
            EdgeGoUi.shape(playPause, (active ? 32 : 20) * dp);
        }
        long supported = state == null ? 0 : state.getActions();
        previous.setEnabled((supported & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0);
        next.setEnabled((supported & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);
        playPause.setEnabled((supported & (PlaybackState.ACTION_PLAY_PAUSE
                | (playing ? PlaybackState.ACTION_PAUSE : PlaybackState.ACTION_PLAY))) != 0);
        progress.setEnabled(duration > 0 && (supported & PlaybackState.ACTION_SEEK_TO) != 0);
        if (rewind != null) rewind.setEnabled(progress.isEnabled());
        if (forward != null) forward.setEnabled(progress.isEnabled());
        long position = state == null ? 0 : state.getPosition();
        if (state != null && state.getState() == PlaybackState.STATE_PLAYING) position += Math.round(
                (SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        position = Math.max(0, Math.min(duration, position));
        if (scrubbing || SystemClock.uptimeMillis() - lastSeek < 350) position = (long) (progress.level() * duration);
        else progress.setLevel(duration == 0 ? 0 : position / (float) duration);
        elapsed.setText(DateUtils.formatElapsedTime(position / 1000));
        total.setText(DateUtils.formatElapsedTime(duration / 1000));
    }

    private void seek(float value, boolean commit) {
        requestedLevel = value;
        if (media == null || !canRun.getAsBoolean() || duration <= 0 || !progress.isEnabled()
                || scrubbingMedia != null && !sameSession(media, scrubbingMedia)) return;
        PlaybackState state = media.getPlaybackState();
        if (state == null || (state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0) return;
        long now = SystemClock.uptimeMillis();
        if (!commit && now - lastSeek < 50) return;
        lastSeek = now;
        if (!scrubbing) progress.setLevel(requestedLevel);
        try { media.getTransportControls().seekTo((long) (requestedLevel * duration)); }
        catch (RuntimeException ignored) { }
        elapsed.setText(DateUtils.formatElapsedTime((long) (requestedLevel * duration) / 1000));
        touched.run();
    }

    private static boolean sameSession(MediaController first, MediaController second) {
        return first == second || first != null && second != null && first.getSessionToken().equals(second.getSessionToken());
    }

    @Override protected void onDetachedFromWindow() {
        title.animate().cancel();
        artist.animate().cancel();
        media = scrubbingMedia = null;
        bitmap = null;
        artwork.setImageDrawable(null);
        super.onDetachedFromWindow();
    }

    private void setText(TextView view, CharSequence value) {
        if (TextUtils.equals(view.getText(), value)) return;
        view.animate().cancel();
        view.setText(value);
        if (ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow()) {
            view.setAlpha(0);
            view.setTranslationY(4 * dp);
            view.animate().alpha(1).translationY(0).setDuration(180)
                    .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1)).start();
        } else { view.setAlpha(1); view.setTranslationY(0); }
    }
}
