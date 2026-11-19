/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 * Licensed under the Apache License, Version 2.0.
 */
package com.android.internal.util.lunaris;

import android.content.ContentResolver;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** @hide */
public final class EdgeGoConfig {
    public static final String ENABLED = "edgego_enabled";
    public static final String CONFIG = "edgego_config";
    public static final String PRESETS = "edgego_presets";
    public static final String STATE = "edgego_state";
    public static final String COMMAND = "edgego_command";
    public static final int VERSION = 1;
    public static final int MAX_JSON_LENGTH = 262144;
    // SettingsProvider's maximum length for a single setting value.
    public static final int MAX_SETTINGS_LENGTH = 32768;
    public static final int MAX_BACKUP_BYTES = 2097152;
    public static final int MAX_ITEMS = 48;
    public static final int MAX_MACRO_STEPS = 100;
    public static final String[] ZONES = {"left_z1", "left_z2", "right_z1", "right_z2",
            "top_z1", "bottom_z1", "bottom_navigation_s1", "bottom_navigation_s2",
            "bottom_navigation_s3"};
    public static final String[] GESTURES = {"tap", "double_tap", "triple_tap", "long_press",
            "swipe_up", "swipe_down", "swipe_up_and_hold", "swipe_down_and_hold",
            "slide_left", "slide_right", "slide_left_and_hold", "slide_right_and_hold",
            "pull_inward", "pull_inward_and_hold", "pull_diagonal_up",
            "pull_diagonal_up_and_hold", "pull_diagonal_down", "pull_diagonal_down_and_hold"};
    public static final String[] ACTIONS = {"none", "back", "home", "recents", "previous_app",
            "notifications", "quick_settings", "dismiss_notification_shade", "power_menu",
            "lock_screen", "screenshot", "partial_screenshot", "one_handed", "voice_command",
            "flashlight", "wifi", "bluetooth", "mobile_data", "location", "auto_rotate",
            "do_not_disturb", "internet_panel", "open_nfc", "open_accessibility",
            "current_app_info", "open_volume_panel", "volume_up", "volume_down",
            "ring_volume_up", "ring_volume_down", "notification_volume_up",
            "notification_volume_down", "mute_volume", "mute_media", "mute_ringtone",
            "mute_notifications", "mute_all", "volume_slider", "brightness_up",
            "brightness_down", "brightness_slider", "auto_brightness", "cycle_screen_timeout",
            "extra_dim", "screen_blackout", "media_play_pause", "media_next", "media_previous",
            "media_stop", "media_scrub", "media_controller", "notch_media", "song_search",
            "app_launcher", "show_quick_access", "quick_tools", "side_panel", "notch_apps",
            "notification_carousel", "preset_picker", "touch_pad", "remote_cursor",
            "drawing_pad", "page_scroll", "scroll_to_top", "scroll_to_bottom", "copy", "paste",
            "select_all_text", "text_picker", "tap_through", "notch_timer", "camera",
            "battery_saver", "screen_record", "split_screen", "close", "pause_edgego"};
    public static final String[] ACTION_LABELS = {"None", "Back", "Home", "Recent apps",
            "Previous app", "Notifications", "Quick Settings", "Close notification shade",
            "Power menu", "Lock screen", "Screenshot", "Partial screenshot", "One-handed mode",
            "Voice assistant", "Flashlight", "Wi-Fi", "Bluetooth", "Mobile data", "Location",
            "Auto-rotate", "Do Not Disturb", "Internet panel", "NFC settings",
            "Accessibility settings", "Current app info", "Volume panel", "Media volume up",
            "Media volume down", "Ringtone volume up", "Ringtone volume down",
            "Notification volume up", "Notification volume down", "Mute / restore volume",
            "Mute / restore media", "Mute / restore ringtone", "Mute / restore notifications",
            "Mute / restore all volume", "Volume slider", "Brightness up", "Brightness down",
            "Brightness slider", "Auto brightness", "Change screen timeout", "Extra dim",
            "Screen blackout", "Play / pause", "Next track", "Previous track", "Stop media",
            "Media seek", "Media controller", "Notch media", "Song search", "App launcher",
            "Pie control", "Quick tools", "Side panel", "Notch apps", "Notification carousel",
            "Preset picker", "Touch pad", "Remote cursor", "Drawing pad", "Page scroll",
            "Scroll to top", "Scroll to bottom", "Copy selected text", "Paste clipboard",
            "Select field text", "Text picker", "Tap through", "Notch timer", "Camera",
            "Battery saver", "Screen recording", "Split screen", "Close panel", "Pause EdgeGo"};

    public enum Kind { SWITCH, RANGE, CHOICE, COLOR, TIME }

    public static final class Option {
        public final String key, section, title, choices;
        public final Kind kind;
        public final Object initial;
        public final int min, max;

        private Option(String section, String key, String title, Kind kind, Object initial,
                int min, int max, String choices) {
            this.section = section;
            this.key = key;
            this.title = title;
            this.kind = kind;
            this.initial = initial;
            this.min = min;
            this.max = max;
            this.choices = choices;
        }
    }

    private static final Map<String, Option> OPTIONS;
    static {
        Map<String, Option> options = new LinkedHashMap<>();
        toggle(options, "controls", "split_zones_enabled", "Two zones on each side", false);
        toggle(options, "controls", "edge_actions_synced", "Sync left and right actions", false);
        choice(options, "controls", "edge_action_sync_direction", "Copy actions from", "right_to_left",
                "right_to_left|left_to_right");
        toggle(options, "controls", "touch_priority_enabled", "Give configured side zones priority", true);
        toggle(options, "controls", "respect_app_exclusion", "Respect app gesture exclusions", true);
        toggle(options, "controls", "keep_edges_over_keyboard", "Keep side edges above keyboard", false);
        toggle(options, "controls", "disable_on_lock_screen", "Pause on lock screen", true);
        toggle(options, "controls", "disable_on_home_screen", "Pause on launcher", false);
        toggle(options, "controls", "disable_in_fullscreen", "Pause in fullscreen apps", false);
        toggle(options, "controls", "disable_in_gaming_mode", "Pause in GameSpace", true);
        toggle(options, "controls", "disable_with_touch_exploration", "Pause with touch exploration", true);
        toggle(options, "controls", "edge_bottom_navigation_replacement_enabled",
                "Three bottom shortcut zones", false);
        toggle(options, "controls", "hold_actions_on_release", "Run hold actions on release", false);
        toggle(options, "controls", "cancel_hold_on_return_to_start", "Return to edge to cancel", true);
        toggle(options, "sliders", "continuous_swipe_enabled", "Continuously adjust while swiping", true);
        range(options, "sliders", "continuous_adjustment_interval_ms", "Continuous adjustment speed", 180, 80, 500);
        range(options, "controls", "swipe_threshold_dp", "Swipe distance", 48, 24, 160);
        range(options, "controls", "extended_threshold_dp", "Extended swipe distance", 128, 64, 300);
        range(options, "controls", "hold_timeout_ms", "Hold delay", 450, 200, 1000);
        range(options, "controls", "hold_settle_ms", "Pause before a swipe hold", 160, 80, 500);
        range(options, "controls", "multi_tap_interval_ms", "Multiple tap interval", 280, 150, 500);
        range(options, "controls", "tap_slop_dp", "Tap movement tolerance", 10, 4, 24);
        range(options, "controls", "direction_hysteresis_deg", "Direction stability", 9, 3, 20);
        range(options, "controls", "gesture_timeout_ms", "Gesture time limit", 3500, 1000, 10000);
        range(options, "controls", "gesture_cooldown_ms", "Accidental repeat protection", 180, 0, 1000);
        range(options, "controls", "tap_through_duration_ms", "Tap through duration", 3000, 500, 15000);
        toggle(options, "appearance", "show_indicators", "Show edge indicators", true);
        choice(options, "appearance", "edge_indicator_style", "Indicator style", "soft_fade",
                "soft_fade|clear_bounds|pill");
        range(options, "appearance", "overlay_opacity", "Indicator opacity", 45, 0, 100);
        color(options, "appearance", "overlay_color_left_rgb", "Left edge color", 0xff8d7cff);
        color(options, "appearance", "overlay_color_right_rgb", "Right edge color", 0xff8d7cff);
        color(options, "appearance", "overlay_color_top_rgb", "Top edge color", 0xff8d7cff);
        color(options, "appearance", "overlay_color_bottom_rgb", "Bottom edge color", 0xff8d7cff);
        toggle(options, "appearance", "auto_hide_enabled", "Hide idle indicators", false);
        range(options, "appearance", "auto_hide_delay", "Indicator idle delay", 5000, 1000, 30000);
        toggle(options, "appearance", "hide_indicators_in_fullscreen", "Hide indicators in fullscreen", false);
        toggle(options, "appearance", "material_you_accent_enabled", "Use system accent", true);
        toggle(options, "appearance", "material_you_surface_tint_enabled", "Tint panel surfaces", true);
        toggle(options, "appearance", "material_you_sync_sliders", "Sync slider colors", true);
        toggle(options, "appearance", "material_you_sync_edges", "Sync edge colors", true);
        toggle(options, "appearance", "material_you_sync_wave", "Sync wave color", true);
        toggle(options, "appearance", "material_you_sync_bubble", "Sync bubble color", true);
        toggle(options, "appearance", "material_you_sync_quick_access_background", "Sync pie surface", true);
        toggle(options, "appearance", "material_you_sync_quick_tools", "Sync Quick Tools accents", true);
        choice(options, "appearance", "ui_theme_mode", "Panel theme", "system", "system|light|dark");
        color(options, "appearance", "ui_accent_light_rgb", "Light theme accent", 0xff6750a4);
        color(options, "appearance", "ui_accent_dark_rgb", "Dark theme accent", 0xffd0bcff);
        toggle(options, "animation", "pull_animation_enabled", "Gesture animation", true);
        choice(options, "animation", "pull_animation_style", "Animation style", "wave", "wave|bubble|minimal");
        color(options, "animation", "pull_animation_wave_color_rgb", "Wave color", 0xff292432);
        color(options, "animation", "pull_animation_bubble_color_rgb", "Bubble color", 0xff292432);
        color(options, "animation", "pull_animation_arrow_color_rgb", "Action icon color", 0xffece6f0);
        range(options, "animation", "pull_animation_wave_opacity", "Wave opacity", 90, 0, 100);
        range(options, "animation", "pull_animation_bubble_arrow_opacity", "Bubble opacity", 100, 0, 100);
        range(options, "animation", "pull_animation_thickness_dp", "Gesture bubble size", 23, 16, 40);
        toggle(options, "animation", "pull_animation_sync_arrow_with_accent", "Accent action icon", true);
        toggle(options, "animation", "pull_animation_show_action_icon", "Show action icons", true);
        toggle(options, "animation", "pull_animation_show_launch_app_icon", "Show app icons", true);
        toggle(options, "animation", "show_action_label", "Show selected action", true);
        range(options, "animation", "animation_stiffness", "Spring responsiveness", 500, 200, 1000);
        range(options, "animation", "animation_damping", "Spring damping", 85, 50, 100);
        toggle(options, "animation", "haptic_feedback", "Gesture vibration", true);
        choice(options, "animation", "haptic_feedback_strength", "Vibration strength", "balanced",
                "light|balanced|strong");
        choice(options, "animation", "haptic_feedback_pattern", "Vibration pattern", "single", "single|double");
        toggle(options, "animation", "sector_haptics", "Feedback when direction changes", true);
        toggle(options, "sectors", "custom_pull_use_sector_colors", "Use sector colors while pulling", false);
        toggle(options, "sectors", "custom_pull_show_sector_number", "Show sector number while pulling", false);
        color(options, "sectors", "custom_pull_sector_1_color_rgb", "Sector 1 color", 0xff6750a4);
        color(options, "sectors", "custom_pull_sector_2_color_rgb", "Sector 2 color", 0xff386a20);
        color(options, "sectors", "custom_pull_sector_3_color_rgb", "Sector 3 color", 0xff006a6a);
        color(options, "sectors", "custom_pull_sector_4_color_rgb", "Sector 4 color", 0xff0061a4);
        color(options, "sectors", "custom_pull_sector_5_color_rgb", "Sector 5 color", 0xff984061);
        color(options, "sectors", "custom_pull_sector_6_color_rgb", "Sector 6 color", 0xffa23e32);
        color(options, "sectors", "custom_pull_sector_7_color_rgb", "Sector 7 color", 0xff7d5700);
        toggle(options, "panels", "floating_panel_remember_position", "Remember panel positions", true);
        toggle(options, "panels", "floating_panel_pass_through_outside_taps", "Pass outside taps through", false);
        range(options, "panels", "floating_panel_timeout", "Panel idle timeout", 15, 0, 120);
        range(options, "panels", "floating_panel_scale", "Panel size", 100, 70, 130);
        toggle(options, "panels", "floating_panel_blur", "Blur behind panels", true);
        range(options, "panels", "floating_panel_corner_radius", "Panel corner radius", 28, 12, 48);
        choice(options, "sliders", "volume_slider_style", "Volume slider style", "system_pill",
                "edge_rail|minimal|system_pill");
        toggle(options, "sliders", "volume_slider_follow_active_audio", "Follow call and ringing volume", true);
        toggle(options, "sliders", "volume_slider_use_accent_color", "Accent volume slider", true);
        color(options, "sliders", "volume_slider_color_rgb", "Volume slider color", 0xffd0bcff);
        choice(options, "sliders", "brightness_slider_style", "Brightness slider style", "system_pill",
                "edge_rail|minimal|system_pill");
        toggle(options, "sliders", "brightness_slider_use_accent_color", "Accent brightness slider", true);
        color(options, "sliders", "brightness_slider_color_rgb", "Brightness slider color", 0xffffd36f);
        choice(options, "sliders", "slider_orientation", "Slider direction", "edge", "edge|vertical|horizontal");
        range(options, "sliders", "slider_sensitivity", "Slider sensitivity", 100, 25, 200);
        range(options, "sliders", "brightness_step", "Brightness step", 10, 1, 25);
        choice(options, "sliders", "audio_mute_target", "Mute action controls", "all",
                "all|media|ringtone|notifications");
        color(options, "sliders", "continuous_gesture_overlay_background_color_rgb", "Control background", 0xff292432);
        color(options, "sliders", "continuous_gesture_overlay_foreground_color_rgb", "Control text", 0xffece6f0);
        toggle(options, "sliders", "continuous_gesture_overlay_use_accent_color", "Accent controls", true);
        toggle(options, "media", "media_controller_show_progress", "Media progress", true);
        toggle(options, "media", "media_controller_artwork_shading_enabled", "Show shaded media artwork", true);
        toggle(options, "media", "media_controller_pass_through_outside_taps", "Pass outside media panel taps through", false);
        range(options, "media", "media_controller_opacity", "Media panel opacity", 95, 25, 100);
        choice(options, "media", "media_controller_theme", "Media theme", "system", "system|light|dark");
        color(options, "media", "media_scrub_overlay_background_color_rgb", "Media seek background", 0xff292432);
        color(options, "media", "media_scrub_overlay_foreground_color_rgb", "Media seek text", 0xffece6f0);
        toggle(options, "media", "media_scrub_overlay_use_accent_color", "Accent media seek", true);
        range(options, "media", "media_scrub_sensitivity", "Seek sensitivity", 100, 25, 200);
        range(options, "launcher", "app_launcher_opacity", "Launcher opacity", 95, 25, 100);
        toggle(options, "launcher", "app_launcher_show_pinned", "Show pinned apps and shortcuts", true);
        toggle(options, "launcher", "app_launcher_show_only_pinned", "Only show pinned items", false);
        toggle(options, "launcher", "app_launcher_disable_search", "Hide launcher search", false);
        toggle(options, "launcher", "app_launcher_hide_labels", "Hide app labels", false);
        toggle(options, "launcher", "app_launcher_open_keyboard_on_show", "Open keyboard with launcher", false);
        toggle(options, "launcher", "app_launcher_keep_open_on_action", "Keep launcher open after actions", false);
        toggle(options, "launcher", "app_launcher_pass_through_outside_taps", "Pass outside launcher taps through", false);
        toggle(options, "launcher", "include_package_in_picker_search", "Search package names", true);
        range(options, "launcher", "app_launcher_columns", "App columns", 4, 3, 6);
        choice(options, "pie", "quick_access_style", "Pie style", "glass", "glass|classic");
        choice(options, "pie", "quick_access_open_mode", "Pie opening behavior", "slide", "slide|tap");
        range(options, "pie", "quick_access_radius", "Pie reach distance", 120, 72, 200);
        range(options, "pie", "quick_access_icon_size", "Pie icon size", 36, 24, 56);
        range(options, "pie", "quick_access_opacity", "Pie opacity", 90, 25, 100);
        range(options, "pie", "quick_access_arc_degrees", "Pie arc angle", 180, 60, 180);
        range(options, "pie", "quick_access_arc_background_opacity", "Arc background opacity", 100, 0, 100);
        toggle(options, "pie", "quick_access_blur_background", "Blur behind pie", true);
        toggle(options, "pie", "quick_access_pass_through_outside_taps", "Pass outside pie taps through", false);
        toggle(options, "pie", "quick_access_drawer_enabled", "Add app drawer control", false);
        toggle(options, "pie", "quick_access_recents_enabled", "Add recent apps control", false);
        toggle(options, "pie", "quick_access_slider_volume", "Add volume control", false);
        toggle(options, "pie", "quick_access_slider_brightness", "Add brightness control", false);
        range(options, "pie", "quick_access_hold_timeout", "Pie item hold delay", 450, 200, 1000);
        range(options, "pie", "quick_access_group_delay", "Group opening delay", 280, 120, 800);
        toggle(options, "pie", "quick_access_show_arc_background", "Show arc background", true);
        toggle(options, "pie", "quick_access_show_guide_line", "Show arc guide line", true);
        toggle(options, "pie", "quick_access_show_icon_background", "Show icon backgrounds", true);
        toggle(options, "pie", "quick_access_keep_open_on_action", "Keep pie open after actions", false);
        color(options, "pie", "quick_access_arc_color_rgb", "Pie surface color", 0xff292432);
        range(options, "tools", "quick_tools_panel_opacity", "Quick tools opacity", 95, 25, 100);
        color(options, "tools", "quick_tools_panel_color_rgb", "Quick tools surface", 0xff292432);
        color(options, "tools", "quick_tools_icon_color_rgb", "Quick tools icons", 0xffece6f0);
        color(options, "tools", "quick_tools_slider_color_rgb", "Quick tools sliders", 0xffd0bcff);
        color(options, "tools", "quick_tools_selected_button_color_rgb", "Selected tool color", 0xffd0bcff);
        toggle(options, "tools", "quick_tools_show_media_progress", "Quick tools media progress", true);
        toggle(options, "tools", "quick_tools_show_media_controls", "Quick tools media controls", true);
        toggle(options, "tools", "quick_tools_pass_through_outside_taps", "Pass outside quick tools taps through", false);
        range(options, "side_panel", "side_panel_opacity", "Side panel opacity", 95, 25, 100);
        choice(options, "side_panel", "side_panel_theme", "Side panel theme", "system", "system|light|dark");
        toggle(options, "side_panel", "side_panel_clipboard_history", "Clipboard history", false);
        toggle(options, "side_panel", "side_panel_clipboard_enabled", "Show clipboard tab", true);
        toggle(options, "side_panel", "side_panel_notes_enabled", "Show notes tab", true);
        toggle(options, "side_panel", "side_panel_actions_enabled", "Show actions tab", true);
        toggle(options, "side_panel", "side_panel_remember_page", "Remember side panel tab", true);
        toggle(options, "side_panel", "side_panel_tap_to_paste", "Tap a clip to paste", false);
        toggle(options, "side_panel", "side_panel_skip_sensitive_clips", "Skip clips marked sensitive", true);
        toggle(options, "side_panel", "side_panel_pass_through_outside_taps", "Pass outside side panel taps through", false);
        range(options, "side_panel", "side_panel_clipboard_limit", "Clipboard history limit", 30, 10, 100);
        toggle(options, "side_panel", "side_panel_show_recent_apps", "Show recent apps", true);
        range(options, "notifications", "notification_carousel_opacity", "Notification panel opacity", 95, 25, 100);
        choice(options, "notifications", "notification_carousel_theme", "Notification theme", "system", "system|light|dark");
        toggle(options, "notifications", "notification_carousel_show_app_filter", "Show notification app filters", true);
        toggle(options, "notifications", "notification_carousel_show_ongoing", "Show ongoing notifications", true);
        toggle(options, "notifications", "notification_carousel_show_scrim", "Shade behind notification cards", true);
        toggle(options, "notifications", "notification_carousel_pass_through_outside_taps", "Pass outside notification taps through", false);
        choice(options, "notifications", "notification_carousel_snooze_minutes", "Snooze duration", "60",
                "off|15|30|60|120|180|480");
        range(options, "touch_pad", "touch_pad_opacity", "Touch pad opacity", 80, 25, 100);
        range(options, "touch_pad", "touch_pad_size_dp", "Touch pad width", 230, 96, 360);
        color(options, "touch_pad", "touch_pad_cursor_color_rgb", "Touch pad cursor color", 0xffece6f0);
        range(options, "touch_pad", "touch_pad_cursor_sensitivity", "Touch pad speed", 100, 25, 250);
        range(options, "touch_pad", "touch_pad_double_tap_timeout_ms", "Touch pad double tap interval", 280, 150, 500);
        toggle(options, "touch_pad", "touch_pad_hold_enabled", "Touch pad long press", true);
        range(options, "touch_pad", "touch_pad_hold_delay_ms", "Touch pad hold delay", 450, 200, 1000);
        range(options, "touch_pad", "touch_pad_hold_duration_ms", "Replayed hold duration", 600, 400, 2000);
        range(options, "touch_pad", "touch_pad_slide_duration_ms", "Replayed swipe duration", 350, 150, 1000);
        toggle(options, "touch_pad", "touch_pad_pass_through_outside_taps", "Touch pad outside taps pass through", true);
        range(options, "cursor", "remote_cursor_opacity", "Cursor and pad opacity", 80, 30, 100);
        color(options, "cursor", "remote_cursor_base_color_rgb", "Cursor and pad color", 0xffd0bcff);
        toggle(options, "cursor", "remote_cursor_use_accent_color", "Accent cursor", true);
        range(options, "cursor", "remote_cursor_cursor_size_dp", "Cursor size", 28, 16, 48);
        range(options, "cursor", "remote_cursor_pad_size_dp", "Thumb pad size", 88, 64, 144);
        range(options, "cursor", "remote_cursor_speed", "Cursor speed", 100, 25, 250);
        range(options, "cursor", "remote_cursor_long_press_delay_ms", "Cursor hold delay", 350, 200, 1000);
        choice(options, "cursor", "remote_cursor_hold_action", "Cursor long press", "long_click",
                "long_click|record_swipe|none|custom");
        choice(options, "cursor", "remote_cursor_double_tap_action", "Cursor double tap", "double_tap",
                "double_tap|touch_pad_slide|dismiss|none|custom");
        toggle(options, "cursor", "remote_cursor_triple_tap_reset", "Triple tap resets cursor", true);
        toggle(options, "cursor", "remote_cursor_outside_dismiss", "Close cursor on outside touch", true);
        toggle(options, "cursor", "remote_cursor_outside_only_dismiss", "Keep cursor open until dismissed", false);
        toggle(options, "cursor", "remote_cursor_throw_dismiss_enabled", "Flick pad to close", false);
        range(options, "cursor", "remote_cursor_inactivity_timeout", "Cursor idle timeout", 15, 3, 120);
        toggle(options, "cursor", "remote_cursor_top_edge_shortcut", "Cursor top edge shortcut", false);
        choice(options, "scroll", "page_scroll_variant", "Page scroll behavior", "auto_scroll", "auto_scroll|hold_drag");
        range(options, "scroll", "page_scroll_auto_speed_level", "Auto scroll speed", 3, 1, 7);
        color(options, "scroll", "page_scroll_overlay_background_color_rgb", "Scroll control background", 0xff292432);
        color(options, "scroll", "page_scroll_overlay_foreground_color_rgb", "Scroll control text", 0xffece6f0);
        toggle(options, "scroll", "page_scroll_overlay_use_accent_color", "Accent scroll controls", true);
        range(options, "drawing", "drawing_pad_timeout", "Drawing pad timeout", 8, 3, 30);
        range(options, "drawing", "drawing_confidence", "Drawing match confidence", 60, 35, 90);
        toggle(options, "drawing", "drawing_orientation_sensitive", "Match drawing direction", true);
        toggle(options, "dim", "extra_dim_schedule_start_enabled", "Schedule extra dim on", false);
        toggle(options, "dim", "extra_dim_schedule_stop_enabled", "Schedule extra dim off", false);
        time(options, "dim", "extra_dim_schedule_start_minute_of_day", "Extra dim start", 1320);
        time(options, "dim", "extra_dim_schedule_stop_minute_of_day", "Extra dim end", 420);
        range(options, "dim", "extra_dim_intensity_percent", "Extra dim strength", 65, 0, 90);
        range(options, "dim", "extra_dim_warmth_percent", "Extra dim warmth", 0, 0, 100);
        choice(options, "notch", "notch_timer_theme_mode", "Notch timer theme", "system", "system|light|dark");
        range(options, "notch", "notch_timer_opacity", "Notch timer opacity", 95, 25, 100);
        range(options, "notch", "notch_apps_opacity", "Notch apps opacity", 95, 25, 100);
        color(options, "notch", "notch_apps_panel_color_rgb", "Notch apps surface", 0xff292432);
        color(options, "notch", "notch_apps_action_color_rgb", "Notch apps icons", 0xffece6f0);
        toggle(options, "notch", "notch_apps_sync_quick_access_items", "Sync notch apps with pie", true);
        range(options, "notch", "edge_top_vertical_offset_dp", "Top edge offset", 0, 0, 72);
        range(options, "presets", "preset_picker_opacity", "Preset picker opacity", 95, 25, 100);
        range(options, "presets", "preset_picker_radius", "Preset picker reach", 120, 72, 200);
        toggle(options, "presets", "preset_picker_show_name", "Show preset names", true);
        toggle(options, "presets", "preset_picker_pass_through_outside_taps", "Pass outside preset picker taps through", false);
        range(options, "sectors", "custom_pull_angle_slot_count", "Number of pull sectors", 3, 1, 7);
        toggle(options, "sectors", "custom_pull_angles_enabled", "Custom pull sectors", false);
        range(options, "sectors", "custom_pull_upper_limit", "Upward pull limit", 65, 10, 85);
        range(options, "sectors", "custom_pull_lower_limit", "Downward pull limit", 65, 10, 85);
        OPTIONS = Collections.unmodifiableMap(options);
    }

    public final boolean enabled;
    private final JSONObject values;

    public EdgeGoConfig(ContentResolver resolver, int user) {
        this(Settings.Secure.getIntForUser(resolver, ENABLED, 0, user) == 1,
                Settings.Secure.getStringForUser(resolver, CONFIG, user));
    }

    public EdgeGoConfig(boolean enabled, String raw) {
        this.enabled = enabled;
        JSONObject root = object(raw);
        values = root.optInt("version", VERSION) == VERSION && root.optJSONObject("values") != null
                ? root.optJSONObject("values") : new JSONObject();
    }

    public static List<Option> options(String section) {
        List<Option> result = new ArrayList<>();
        for (Option option : OPTIONS.values()) if (section == null || option.section.equals(section)) {
            result.add(option);
        }
        return result;
    }

    public static Option option(String key) { return OPTIONS.get(key); }

    public boolean bool(String key) {
        Option option = OPTIONS.get(key);
        boolean fallback = option != null && Boolean.TRUE.equals(option.initial);
        return values.optBoolean(key, fallback);
    }

    public int integer(String key) {
        Option option = OPTIONS.get(key);
        int fallback = option != null && option.initial instanceof Number
                ? ((Number) option.initial).intValue() : 0;
        int value = values.optInt(key, fallback);
        return option == null || option.kind == Kind.COLOR ? value
                : clamp(value, option.min, option.max);
    }

    public String string(String key) {
        Option option = OPTIONS.get(key);
        String fallback = option != null && option.initial instanceof String
                ? (String) option.initial : "";
        String value = values.optString(key, fallback);
        if (option != null && option.kind == Kind.CHOICE
                && !Arrays.asList(option.choices.split("\\|")).contains(value)) return fallback;
        return value.length() <= 16384 ? value : fallback;
    }

    public JSONArray array(String key) {
        JSONArray array = values.optJSONArray(key);
        if (array != null) return array;
        if (!values.has(key) && (key.equals("quick_access_items") || key.equals("quick_tools_items"))) {
            JSONArray defaults = new JSONArray();
            for (String action : new String[]{"back", "home", "recents", "notifications", "quick_settings", "flashlight"}) {
                JSONObject item = new JSONObject(); put(item, "type", "action"); put(item, "actionId", action);
                defaults.put(item);
            }
            return defaults;
        }
        try { return new JSONArray(values.optString(key, "[]")); }
        catch (JSONException ignored) { return new JSONArray(); }
    }

    public JSONObject snapshot() { return object(values.toString()); }
    public boolean contains(String key) { return values.has(key); }

    public boolean zoneEnabled(String zone) {
        if (!Arrays.asList(ZONES).contains(zone)) return false;
        if (zone.endsWith("z2") && !bool("split_zones_enabled")) return false;
        boolean splitBottom = bool("edge_bottom_navigation_replacement_enabled");
        if (zone.startsWith("bottom_navigation")) return splitBottom && values.optBoolean("edge_" + zone + "_enabled", true);
        if (zone.equals("bottom_z1") && splitBottom) return false;
        return values.optBoolean("edge_" + zone + "_enabled", zone.equals("right_z1"));
    }

    public boolean zoneBoolean(String zone, String key, boolean fallback) {
        return values.optBoolean("edge_" + zone + "_" + key, fallback);
    }

    public int zoneInt(String zone, String key, int fallback, int min, int max) {
        return clamp(values.optInt("edge_" + zone + "_" + key, fallback), min, max);
    }

    public String zoneString(String zone, String key, String fallback) {
        if (key.equals("orientation_mode") && !values.has("edge_" + zone + "_orientation_mode")
                && zoneBoolean(zone, "landscape_only", false)) return "landscape";
        return values.optString("edge_" + zone + "_" + key, fallback);
    }

    public String actionZone(String zone) {
        if (bool("edge_actions_synced") && (zone.startsWith("left") || zone.startsWith("right"))) {
            return (string("edge_action_sync_direction").equals("right_to_left") ? "right" : "left")
                    + zone.substring(zone.indexOf('_'));
        }
        return zone;
    }

    public String action(String zone, String gesture) {
        zone = actionZone(zone);
        String fallback = "none";
        if (zone.endsWith("z1") && (zone.startsWith("left") || zone.startsWith("right"))) {
            switch (gesture) {
                case "pull_inward": fallback = "back"; break;
                case "pull_inward_and_hold": fallback = "recents"; break;
                case "pull_diagonal_up": fallback = "notifications"; break;
                case "pull_diagonal_down": fallback = "quick_settings"; break;
                case "swipe_up": fallback = "home"; break;
            }
        }
        if (zone.startsWith("bottom_navigation")) {
            if (gesture.equals("tap") || gesture.equals("pull_inward")) {
                fallback = zone.endsWith("1") ? "back" : zone.endsWith("2") ? "home" : "recents";
            }
        }
        String raw = values.optString("gesture_" + zone + "_" + gesture, fallback);
        if (raw.equals("scroll_to_top_bottom")) raw = values.optString("scroll_boundary_direction_" + zone + "_" + gesture, "scroll_top");
        String action = normalizeAction(raw);
        if ((action.equals("show_quick_access") || action.equals("notch_apps"))
                && values.optInt("quick_access_slot_" + zone + "_" + gesture, 1) == 2) action += ":2";
        return action;
    }

    public boolean capturesTaps(String zone) {
        return !action(zone, "tap").equals("none") || !action(zone, "double_tap").equals("none")
                || !action(zone, "triple_tap").equals("none")
                || !action(zone, "long_press").equals("none");
    }

    public boolean hasActions(String zone) {
        for (String gesture : gestures(zone)) if (!action(zone, gesture).equals("none")) return true;
        return !drawings(zone).isEmpty();
    }

    public List<String> gestures(String zone) {
        List<String> result = new ArrayList<>(Arrays.asList("tap", "double_tap", "triple_tap", "long_press"));
        boolean side = zone.startsWith("left") || zone.startsWith("right");
        List<String> directions = side
                ? new ArrayList<>(Arrays.asList("swipe_up", "swipe_down"))
                : new ArrayList<>(Arrays.asList("slide_left", "slide_right", "pull_inward"));
        if (side && bool("custom_pull_angles_enabled")) {
            for (int i = 1; i <= integer("custom_pull_angle_slot_count"); i++) directions.add("custom_pull_" + i);
        } else if (side) {
            directions.addAll(Arrays.asList("pull_diagonal_up", "pull_inward", "pull_diagonal_down"));
        }
        for (String direction : directions) {
            result.add(direction);
            result.add(direction + "_and_hold");
            result.add(direction + "_extended");
        }
        return result;
    }

    public List<JSONObject> drawings(String zone) {
        List<JSONObject> result = new ArrayList<>();
        JSONArray drawings = array(zone == null ? "custom_gestures_json" : "custom_gestures_direct_json");
        if (zone != null) zone = actionZone(zone);
        for (int i = 0; i < Math.min(drawings.length(), 32); i++) {
            JSONObject drawing = drawings.optJSONObject(i);
            if (drawing != null && (zone == null || zone.substring(0, zone.indexOf('_'))
                    .equals(drawing.optString("edge")))) result.add(drawing);
        }
        return result;
    }

    public String drawingAction(String zone, JSONObject drawing) {
        String mapped = zone == null ? "" : string("gesture_" + actionZone(zone) + "_drawn_" + drawing.optString("id"));
        String action = normalizeAction(mapped.isEmpty() ? drawing.optString("actionId", "none") : mapped);
        if (action.equals("show_quick_access") || action.equals("notch_apps")) action += ":" + clamp(drawing.optInt("quickAccessSlot", 1), 1, 2);
        if (action.equals("page_scroll")) action += drawing.optString("scrollDirection").equals("up") ? ":up" : ":down";
        return action;
    }

    public JSONObject copiedActions(String from, String target) {
        if (!Arrays.asList(ZONES).contains(from) || !Arrays.asList(ZONES).contains(target)) {
            throw new IllegalArgumentException("Unknown gesture zone");
        }
        from = actionZone(from);
        target = actionZone(target);
        JSONObject copied = new JSONObject();
        List<String> targetGestures = gestures(target);
        for (String gesture : gestures(from)) {
            String destinationGesture = copiedGesture(gesture, from, target);
            if (!targetGestures.contains(destinationGesture)) continue;
            String mapped = action(from, gesture);
            if (mapped.equals("show_quick_access") || mapped.equals("notch_apps")) mapped += ":1";
            put(copied, "gesture_" + target + "_" + destinationGesture, mapped);
        }
        String sourceEdge = from.substring(0, from.indexOf('_'));
        String targetEdge = target.substring(0, target.indexOf('_'));
        JSONArray entries = new JSONArray();
        JSONArray existing = array("custom_gestures_direct_json");
        for (int i = 0; i < existing.length(); i++) {
            JSONObject entry = existing.optJSONObject(i);
            if (entry != null) entries.put(object(entry.toString()));
        }
        for (JSONObject drawing : drawings(from)) {
            String targetId = drawing.optString("id");
            if (!sourceEdge.equals(targetEdge)) {
                String sourceId = drawing.optString("copySourceId", targetId);
                JSONObject destination = null;
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i);
                    if (entry != null && targetEdge.equals(entry.optString("edge"))
                            && (sourceId.equals(entry.optString("id")) || sourceId.equals(entry.optString("copySourceId")))) {
                        destination = entry;
                        break;
                    }
                }
                if (destination == null) {
                    if (entries.length() >= 32) throw new IllegalArgumentException("Too many edge drawings");
                    destination = EdgeGoDrawings.reorientedDrawing(drawing, targetEdge);
                    put(destination, "id", UUID.randomUUID().toString());
                    put(destination, "copySourceId", sourceId);
                    put(destination, "edge", targetEdge);
                    entries.put(destination);
                } else {
                    JSONObject converted = EdgeGoDrawings.reorientedDrawing(drawing, targetEdge);
                    put(destination, "samples", converted.optJSONArray("samples"));
                    destination.remove("points");
                    put(destination, "fingers", drawing.optInt("fingers", drawing.optInt("fingerCount", 1)));
                    put(destination, "holdAtEnd", drawing.optBoolean("holdAtEnd"));
                }
                targetId = destination.optString("id");
            }
            put(copied, "gesture_" + target + "_drawn_" + targetId, drawingAction(from, drawing));
        }
        if (!sourceEdge.equals(targetEdge)) put(copied, "custom_gestures_direct_json", entries);
        return copied;
    }

    private String copiedGesture(String gesture, String from, String target) {
        boolean sourceSide = from.startsWith("left") || from.startsWith("right");
        boolean targetSide = target.startsWith("left") || target.startsWith("right");
        if (sourceSide == targetSide) return gesture;
        String[][] directions = {
                {sourceSide ? "swipe_up" : "slide_left", targetSide ? "swipe_up" : "slide_left"},
                {sourceSide ? "swipe_down" : "slide_right", targetSide ? "swipe_down" : "slide_right"},
                {inwardGesture(from), inwardGesture(target)}
        };
        for (String[] direction : directions) {
            if (gesture.equals(direction[0]) || gesture.startsWith(direction[0] + "_")) {
                return direction[1] + gesture.substring(direction[0].length());
            }
        }
        return gesture;
    }

    private String inwardGesture(String zone) {
        if (!(zone.startsWith("left") || zone.startsWith("right"))
                || !bool("custom_pull_angles_enabled")) return "pull_inward";
        double[] dividers = sectorDividers();
        int sector = 1;
        while (sector < dividers.length - 1 && dividers[sector] < 0) sector++;
        return "custom_pull_" + sector;
    }

    public double[] sectorDividers() {
        int count = bool("custom_pull_angles_enabled") ? integer("custom_pull_angle_slot_count") : 3;
        double upper = integer("custom_pull_upper_limit");
        double lower = integer("custom_pull_lower_limit");
        double[] dividers = new double[count + 1];
        JSONArray custom = array("custom_pull_sector_dividers");
        dividers[0] = -upper;
        dividers[count] = lower;
        double gap = Math.min(4, (upper + lower) / (count + 1));
        for (int i = 1; i < count; i++) {
            double value = custom.optDouble(i - 1, -upper + (upper + lower) * i / count);
            double max = lower - (count - i) * gap;
            dividers[i] = Math.max(dividers[i - 1] + gap,
                    Math.min(Double.isFinite(value) ? value : 0, max));
        }
        return dividers;
    }

    public boolean paused(String pkg, String edge, boolean home, boolean lockscreen) {
        if (lockscreen && bool("disable_on_lock_screen")
                && edgeIn(array("disabled_lock_screen_edges"), edge, !contains("disabled_lock_screen_edges"))) return true;
        if (home && bool("disable_on_home_screen")
                && edgeIn(array("disabled_home_screen_edges"), edge, !contains("disabled_home_screen_edges"))) return true;
        JSONArray packages = array("disabled_app_packages");
        for (int i = 0; i < packages.length(); i++) if (pkg.equals(packages.optString(i))) return true;
        JSONArray rules = array("disabled_app_edge_rules");
        for (int i = 0; i < Math.min(rules.length(), 256); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule != null && pkg.equals(rule.optString("packageName"))
                    && edgeIn(rule.optJSONArray("edges"), edge, false)) return true;
        }
        return false;
    }

    private static boolean edgeIn(JSONArray edges, String edge, boolean emptyMeansAll) {
        if (edges == null || edges.length() == 0) return emptyMeansAll;
        for (int i = 0; i < edges.length(); i++) {
            String name = edges.optString(i);
            if (name.equals("all") || edge.equals(name) || edge.startsWith(name + "_")) return true;
        }
        return false;
    }

    public static String normalizeAction(String action) {
        if (action == null || action.length() > 16384) return "none";
        switch (action) {
            case "show_app_launcher": return "app_launcher";
            case "show_quick_tools": return "quick_tools";
            case "show_side_panel": return "side_panel";
            case "show_media_controller": return "media_controller";
            case "show_notification_carousel": return "notification_carousel";
            case "show_preset_picker": return "preset_picker";
            case "show_touch_pad": return "touch_pad";
            case "show_remote_cursor": return "remote_cursor";
            case "toggle_brightness_mode": return "auto_brightness";
            case "media_volume_up": return "volume_up";
            case "media_volume_down": return "volume_down";
            case "start_notch_timer": return "notch_timer";
            case "toggle_wifi": return "wifi";
            case "toggle_bluetooth": return "bluetooth";
            case "toggle_mobile_data": return "mobile_data";
            case "toggle_location": return "location";
            case "toggle_auto_rotate": return "auto_rotate";
            case "toggle_do_not_disturb": return "do_not_disturb";
            case "screen_timeout_cycle": return "cycle_screen_timeout";
            case "scroll_top": return "scroll_to_top";
            case "scroll_bottom": return "scroll_to_bottom";
            case "scroll_to_top_bottom": return "scroll_to_top";
        }
        String base = action.split(":", 2)[0];
        if (Arrays.asList(ACTIONS).contains(base)) return action;
        if (Arrays.asList("launch_app", "launch_app_activity", "launch_app_quick", "launch_app_recent", "shortcut",
                "intent", "run_macro", "apply_preset").contains(base) && action.contains(":")) return action;
        return "none";
    }

    public static boolean continuous(String action) {
        String base = action.split(":", 2)[0];
        return adjustable(action) || base.equals("show_quick_access") || base.equals("remote_cursor");
    }

    public static boolean adjustable(String action) {
        return incremental(action) || Arrays.asList("volume_slider", "brightness_slider",
                "media_scrub", "page_scroll").contains(action.split(":", 2)[0]);
    }

    public static boolean incremental(String action) {
        return Arrays.asList("volume_up", "volume_down", "ring_volume_up", "ring_volume_down",
                "notification_volume_up", "notification_volume_down", "brightness_up",
                "brightness_down").contains(action.split(":", 2)[0]);
    }

    public static boolean macroAction(String action) {
        String base = normalizeAction(action).split(":", 2)[0];
        return Arrays.asList(ACTIONS).contains(base) && !Arrays.asList("none", "app_launcher", "show_quick_access", "quick_tools",
                "side_panel", "notch_apps", "notification_carousel", "preset_picker", "touch_pad", "remote_cursor", "drawing_pad",
                "text_picker", "notch_timer", "screen_blackout", "page_scroll", "volume_slider", "brightness_slider", "media_scrub", "pause_edgego").contains(base);
    }

    public static JSONObject object(String raw) {
        if (raw == null || raw.length() > MAX_JSON_LENGTH) return new JSONObject();
        try { return new JSONObject(raw); }
        catch (JSONException ignored) { return new JSONObject(); }
    }

    public static String encode(JSONObject values) {
        try { return new JSONObject().put("version", VERSION).put("values", values).toString(); }
        catch (JSONException e) { throw new IllegalArgumentException(e); }
    }

    public static boolean edit(ContentResolver resolver, int user, Consumer<JSONObject> edit) {
        JSONObject values = new EdgeGoConfig(resolver, user).snapshot();
        edit.accept(values);
        String encoded = encode(values);
        if (encoded.length() > MAX_SETTINGS_LENGTH) return false;
        try {
            return Settings.Secure.putStringForUser(resolver, CONFIG, encoded, user);
        } catch (IllegalArgumentException | SecurityException e) {
            return false;
        }
    }

    public static void put(JSONObject values, String key, Object value) {
        try { values.put(key, value); }
        catch (JSONException e) { throw new IllegalArgumentException(e); }
    }

    public static JSONObject importValues(String raw) throws JSONException {
        if (raw == null || raw.length() > MAX_BACKUP_BYTES) throw new JSONException("Backup is too large");
        JSONObject root = new JSONObject(raw);
        if (root.has("version") && root.optInt("version") != VERSION) throw new JSONException("Unsupported backup version");
        JSONObject values = root.optJSONObject("values");
        if (values == null) {
            JSONObject settings = root.getJSONObject("settings");
            values = new JSONObject();
            for (Iterator<String> keys = settings.keys(); keys.hasNext();) {
                String key = keys.next();
                JSONObject typed = settings.optJSONObject(key);
                if (typed != null && key.length() <= 160) values.put(key, typed.opt("value"));
            }
        }
        JSONObject clean = new JSONObject();
        for (Iterator<String> keys = values.keys(); keys.hasNext();) {
            String key = keys.next();
            if (supportedKey(key)) clean.put(key, values.get(key));
        }
        for (Iterator<String> keys = clean.keys(); keys.hasNext();) {
            String key = keys.next();
            Object value = clean.opt(key);
            if (value instanceof String && ((String) value).stripLeading().startsWith("[")
                    && (key.endsWith("_json") || key.endsWith("_items") || key.endsWith("_items_2")
                    || key.startsWith("disabled_") || key.equals("quick_tools_section_order"))) {
                clean.put(key, new JSONArray((String) value));
            }
        }
        alias(values, clean, "quick_access_radius_dp", "quick_access_radius", false);
        alias(values, clean, "remote_cursor_base_speed_percent", "remote_cursor_speed", false);
        alias(values, clean, "remote_cursor_follow_app_accent", "remote_cursor_use_accent_color", false);
        alias(values, clean, "remote_cursor_inactivity_timeout_ms", "remote_cursor_inactivity_timeout", true);
        alias(values, clean, "remote_cursor_outside_dismiss_enabled", "remote_cursor_outside_dismiss", false);
        alias(values, clean, "remote_cursor_top_edge_shortcut_enabled", "remote_cursor_top_edge_shortcut", false);
        alias(values, clean, "remote_cursor_triple_tap_reset_enabled", "remote_cursor_triple_tap_reset", false);
        alias(values, clean, "pull_animation_color_rgb", "pull_animation_wave_color_rgb", false);
        alias(values, clean, "pull_animation_opacity", "pull_animation_wave_opacity", false);
        alias(values, clean, "material_you_sync_arrow", "pull_animation_sync_arrow_with_accent", false);
        alias(values, clean, "material_you_custom_accent_light_rgb", "ui_accent_light_rgb", false);
        alias(values, clean, "material_you_custom_accent_dark_rgb", "ui_accent_dark_rgb", false);
        alias(values, clean, "custom_pull_max_angle", "custom_pull_upper_limit", false);
        alias(values, clean, "custom_pull_max_angle", "custom_pull_lower_limit", false);
        alias(values, clean, "overlay_color_rgb", "overlay_color_left_rgb", false);
        alias(values, clean, "overlay_color_rgb", "overlay_color_right_rgb", false);
        alias(values, clean, "overlay_color_rgb", "overlay_color_top_rgb", false);
        alias(values, clean, "overlay_color_rgb", "overlay_color_bottom_rgb", false);
        if (!clean.has("app_launcher_pinned_items") && values.has("app_launcher_pinned_apps")) {
            JSONArray packages = values.optJSONArray("app_launcher_pinned_apps");
            if (packages == null) packages = new JSONArray(values.optString("app_launcher_pinned_apps", "[]"));
            JSONArray items = new JSONArray();
            for (int i = 0; i < Math.min(packages.length(), 24); i++) items.put(new JSONObject().put("type", "app")
                    .put("packageName", packages.optString(i)));
            clean.put("app_launcher_pinned_items", items);
        }
        if (clean.length() == 0 && !root.has("values")) throw new JSONException("Backup has no gesture settings");
        if (encode(clean).length() > MAX_JSON_LENGTH) throw new JSONException("Gesture settings are too large");
        return clean;
    }

    private static void alias(JSONObject source, JSONObject target, String before, String after, boolean milliseconds) throws JSONException {
        if (!source.has(after) && source.has(before)) target.put(after, milliseconds ? source.optLong(before) / 1000 : source.opt(before));
    }

    private static boolean supportedKey(String key) {
        if (OPTIONS.containsKey(key)) return true;
        for (String prefix : new String[]{"edge_", "gesture_", "custom_gestures_", "quick_access_",
                "quick_tools_", "notch_apps_", "app_launcher_pinned_", "remote_cursor_", "scroll_boundary_direction_"}) {
            if (key.startsWith(prefix) && key.length() <= 160) return true;
        }
        return Arrays.asList("custom_pull_sector_dividers", "macros_v2_json", "side_panel_items",
                "disabled_app_packages", "disabled_app_edge_rules", "disabled_home_screen_edges",
                "disabled_lock_screen_edges", "app_icon_pack_package").contains(key);
    }

    public String drawingLayout(String zone) {
        StringBuilder result = new StringBuilder();
        for (JSONObject drawing : drawings(zone)) {
            result.append(drawing.optString("id")).append('\u0000')
                    .append(drawing.optString("name")).append('\u0000')
                    .append(drawing.optString("actionId")).append('\u0000')
                    .append(drawing.optBoolean("holdAtEnd")).append('\u0000');
        }
        return result.toString();
    }

    public static JSONObject importPresets(JSONArray source) throws JSONException {
        if (source.length() > 24) throw new JSONException("Too many presets");
        JSONArray presets = new JSONArray();
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject preset = source.getJSONObject(i);
            String id = preset.getString("id"), name = preset.getString("name");
            if (id.isEmpty() || id.length() > 160 || name.isBlank() || name.length() > 80 || !ids.add(id)) {
                throw new JSONException("Invalid preset");
            }
            JSONObject clean = new JSONObject();
            clean.put("id", id);
            clean.put("name", name);
            clean.put("values", importValues(encode(preset.getJSONObject("values"))));
            presets.put(clean);
        }
        JSONObject store = new JSONObject().put("presets", presets);
        if (store.toString().length() > MAX_JSON_LENGTH) throw new JSONException("Presets are too large");
        return store;
    }

    public static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    public static JSONObject presetStore(ContentResolver resolver, int user) {
        String stored = Settings.Secure.getStringForUser(resolver, PRESETS, user);
        if (stored != null) return object(stored);
        JSONArray presets = new JSONArray();
        for (boolean developer : new boolean[]{false, true}) {
            JSONObject values = new JSONObject(), preset = new JSONObject();
            put(values, "disable_in_gaming_mode", true);
            put(values, "edge_right_z1_enabled", true);
            put(values, "respect_app_exclusion", true);
            if (developer) {
                put(values, "edge_right_z1_length", 32);
                put(values, "gesture_right_z1_pull_inward", "back");
                put(values, "gesture_right_z1_pull_inward_and_hold", "show_quick_access");
                put(values, "gesture_right_z1_swipe_down", "app_launcher");
                put(values, "gesture_right_z1_swipe_up_and_hold", "volume_slider");
                put(values, "gesture_right_z1_pull_diagonal_up_and_hold", "brightness_slider");
                put(values, "gesture_right_z1_pull_diagonal_down_and_hold", "quick_tools");
            }
            put(preset, "id", developer ? "edgego_developer" : "edgego_starter");
            put(preset, "name", developer ? "Developer" : "Starter");
            put(preset, "values", values); presets.put(preset);
        }
        JSONObject store = new JSONObject(); put(store, "presets", presets);
        Settings.Secure.putStringForUser(resolver, PRESETS, store.toString(), user);
        return store;
    }

    private static void toggle(Map<String, Option> options, String section, String key,
            String title, boolean value) {
        options.put(key, new Option(section, key, title, Kind.SWITCH, value, 0, 1, ""));
    }
    private static void range(Map<String, Option> options, String section, String key,
            String title, int value, int min, int max) {
        options.put(key, new Option(section, key, title, Kind.RANGE, value, min, max, ""));
    }
    private static void choice(Map<String, Option> options, String section, String key,
            String title, String value, String choices) {
        options.put(key, new Option(section, key, title, Kind.CHOICE, value, 0, 0, choices));
    }
    private static void color(Map<String, Option> options, String section, String key,
            String title, int value) {
        options.put(key, new Option(section, key, title, Kind.COLOR, value, 0, 0, ""));
    }
    private static void time(Map<String, Option> options, String section, String key,
            String title, int value) {
        options.put(key, new Option(section, key, title, Kind.TIME, value, 0, 1439, ""));
    }

    private EdgeGoConfig() { throw new AssertionError(); }
}
