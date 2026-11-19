/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.edgego;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;

import com.android.internal.util.lunaris.EdgeGoConfig;
import com.android.systemui.settings.UserFileManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class EdgeGoPrivateStore {
    private final Context context;
    private final UserFileManager files;
    private final EdgeGoActions.Host host;
    private ClipboardManager clipboard;
    private final ClipboardManager.OnPrimaryClipChangedListener listener = this::changed;
    private boolean listening;

    EdgeGoPrivateStore(Context context, UserFileManager files, EdgeGoActions.Host host) {
        this.context = context;
        this.files = files;
        this.host = host;
    }

    void update() {
        boolean enabled = host.canRun() && host.config().bool("side_panel_clipboard_history") && !host.locked();
        if (enabled == listening) return;
        listening = enabled;
        if (enabled) {
            clipboard = host.userContext().getSystemService(ClipboardManager.class);
            clipboard.addPrimaryClipChangedListener(listener);
        } else if (clipboard != null) clipboard.removePrimaryClipChangedListener(listener);
    }

    private SharedPreferences preferences() { return files.getSharedPreferences("edgego_private", Context.MODE_PRIVATE, host.userId()); }

    private void changed() {
        if (!listening || !host.canRun() || host.locked() || !host.config().bool("side_panel_clipboard_history")) return;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        ClipDescription description = clip.getDescription();
        if (host.config().bool("side_panel_skip_sensitive_clips") && description.getExtras() != null
                && description.getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)) return;
        CharSequence value = clip.getItemAt(0).getText();
        if (value == null || value.length() == 0 || value.length() > 4096) return;
        String text = value.toString();
        JSONArray previous = clips();
        JSONArray next = new JSONArray().put(text);
        int size = JSONObject.quote(text).length() + 2;
        int limit = host.config().integer("side_panel_clipboard_limit");
        for (int i = 0; i < previous.length() && next.length() < limit; i++) {
            String item = previous.optString(i);
            if (!item.equals(text)) {
                int length = JSONObject.quote(item).length() + 1;
                if (size + length > 524288) break;
                size += length; next.put(item);
            }
        }
        preferences().edit().putString("clips", next.toString()).apply();
    }

    JSONArray clips() {
        if (host.locked() || !host.config().bool("side_panel_clipboard_history")) return new JSONArray();
        return array("clips");
    }

    JSONArray notes() { return host.locked() ? new JSONArray() : array("notes"); }

    boolean saveNotes(JSONArray notes) {
        if (host.locked() || notes.length() > 32 || notes.toString().length() > 524288) return false;
        preferences().edit().putString("notes", notes.toString()).apply(); return true;
    }

    void clearClips() { preferences().edit().remove("clips").apply(); }
    void stop() { listening = false; if (clipboard != null) clipboard.removePrimaryClipChangedListener(listener); clipboard = null; }

    private JSONArray array(String key) {
        String value = preferences().getString(key, "[]");
        if (value == null || value.length() > 524288) return new JSONArray();
        try { return new JSONArray(value); }
        catch (JSONException e) { return new JSONArray(); }
    }
}
