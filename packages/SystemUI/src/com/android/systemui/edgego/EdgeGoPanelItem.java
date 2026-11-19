/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.edgego;

import com.android.internal.util.lunaris.EdgeGoConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class EdgeGoPanelItem {
    final String label, action, holdAction, module;
    final List<EdgeGoPanelItem> children;
    final int layer;
    EdgeGoPanelItem(String label, String action) { this(label, action, "none", "", new ArrayList<>()); }
    EdgeGoPanelItem(String label, String action, String hold, String module, List<EdgeGoPanelItem> children) {
        this(label, action, hold, module, children, -1);
    }
    EdgeGoPanelItem(String label, String action, String hold, String module, List<EdgeGoPanelItem> children, int layer) {
        this.label = label;
        this.action = EdgeGoConfig.normalizeAction(action);
        this.holdAction = EdgeGoConfig.normalizeAction(hold);
        this.module = module;
        this.children = children;
        this.layer = layer;
    }
    static List<EdgeGoPanelItem> decode(JSONArray array, EdgeGoActions actions) { return decode(array, actions, 0); }
    private static List<EdgeGoPanelItem> decode(JSONArray array, EdgeGoActions actions, int depth) {
        List<EdgeGoPanelItem> items = new ArrayList<>();
        if (array == null || depth > 2) return items;
        for (int i = 0; i < Math.min(array.length(), EdgeGoConfig.MAX_ITEMS); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "action");
            String action = item.optString("actionId", "none");
            if (type.equals("app")) {
                String activity = item.optString("activityName");
                action = activity.isEmpty() ? "launch_app:" + item.optString("packageName")
                        : "launch_app_activity:" + item.optString("packageName") + "|" + activity;
            } else if (type.equals("shortcut")) {
                action = item.optString("intentUri").isEmpty() ? "shortcut:" + item.optString("packageName") + "|" + item.optString("shortcutId")
                        : "intent:" + android.net.Uri.encode(item.optString("intentUri"));
            }
            String module = item.isNull("moduleId") ? item.optString("module", "") : item.optString("moduleId", "");
            if (module.equals("null")) module = "";
            if (!module.isEmpty() && action.equals("none")) {
                action = module.equals("volume") ? "volume_slider" : module.equals("brightness") ? "brightness_slider"
                        : module.equals("recent_apps") ? "recents" : module.equals("current_app") ? "current_app_info"
                        : module.equals("now_playing") ? "media_controller" : "app_launcher";
            }
            List<EdgeGoPanelItem> children = decode(item.optJSONArray("children"), actions, depth + 1);
            String label = item.optString("label", item.optString("name", actions.label(action)));
            items.add(new EdgeGoPanelItem(label, action, item.optString("holdActionId", "none"), module, children,
                    item.has("layer") ? EdgeGoConfig.clamp(item.optInt("layer"), 0, 2) : -1));
        }
        return items;
    }
}
