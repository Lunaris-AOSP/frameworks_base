/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.lunaris;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.AbsListView;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** @hide */
public final class EdgeGoWindowActions {
    public static final int COPY = 1;
    public static final int PASTE = 2;
    public static final int SELECT_ALL = 3;
    public static final int SCROLL_UP = 4;
    public static final int SCROLL_DOWN = 5;
    public static final int SCROLL_TOP = 6;
    public static final int SCROLL_BOTTOM = 7;
    public static final int GET_TEXT = 8;
    private static final int MAX_NODES = 512;
    private static final int MAX_TEXT_LENGTH = 16384;

    public static boolean supported(int action) { return action >= COPY && action <= GET_TEXT; }

    public static void perform(View root, int action, RemoteCallback callback) {
        Bundle result = new Bundle();
        try {
            if (root == null || !root.isAttachedToWindow() || !root.hasWindowFocus()
                    || !supported(action)) return;
            List<Target> targets = collect(root);
            if (action == GET_TEXT) {
                ArrayList<Bundle> texts = new ArrayList<>();
                int remaining = MAX_TEXT_LENGTH;
                for (Target target : targets) {
                    AccessibilityNodeInfo node = target.node;
                    if (!readable(node) || node.getText() == null) continue;
                    String text = node.getText().toString();
                    if (text.isBlank() || text.length() > remaining) continue;
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (bounds.isEmpty()) continue;
                    Bundle item = new Bundle();
                    item.putString("text", text);
                    item.putParcelable("bounds", bounds);
                    texts.add(item);
                    remaining -= text.length();
                    if (texts.size() == 128 || remaining == 0) break;
                }
                result.putParcelableArrayList("texts", texts);
                result.putBoolean("performed", !texts.isEmpty());
                return;
            }
            Target focused = null;
            for (Target target : targets) if (readable(target.node) && target.node.isFocused()) {
                focused = target;
                if (target.virtualId != AccessibilityNodeProvider.HOST_VIEW_ID) break;
            }
            if (action <= SELECT_ALL) {
                if (focused == null) return;
                int nativeAction = action == COPY ? AccessibilityNodeInfo.ACTION_COPY
                        : action == PASTE ? AccessibilityNodeInfo.ACTION_PASTE
                        : AccessibilityNodeInfo.ACTION_SET_SELECTION;
                Bundle args = null;
                if (action == SELECT_ALL) {
                    CharSequence text = focused.node.getText();
                    if (text == null) return;
                    args = new Bundle();
                    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
                    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length());
                }
                boolean performed = focused.perform(nativeAction, args);
                if (!performed && focused.view instanceof TextView
                        && focused.virtualId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                    performed = ((TextView) focused.view).onTextContextMenuItem(action == COPY
                            ? android.R.id.copy : action == PASTE ? android.R.id.paste : android.R.id.selectAll);
                }
                result.putBoolean("performed", performed);
                return;
            }
            Target scroll = findScrollTarget(targets, focused);
            if (scroll == null) return;
            boolean up = action == SCROLL_UP || action == SCROLL_TOP;
            boolean boundary = action == SCROLL_TOP || action == SCROLL_BOTTOM;
            boolean performed = false;
            if (boundary) {
                View view = scroll.view;
                if (scroll.virtualId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                    if (view instanceof ScrollView) {
                        performed = ((ScrollView) view).fullScroll(up ? View.FOCUS_UP : View.FOCUS_DOWN);
                    } else if (view instanceof HorizontalScrollView) {
                        performed = ((HorizontalScrollView) view).fullScroll(up ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                    } else if (view instanceof AbsListView) {
                        AbsListView list = (AbsListView) view;
                        if (list.getCount() > 0) {
                            list.setSelection(up ? 0 : list.getCount() - 1);
                            performed = true;
                        }
                    } else if (view instanceof android.webkit.WebView) {
                        performed = up ? ((android.webkit.WebView) view).pageUp(true) : ((android.webkit.WebView) view).pageDown(true);
                    }
                }
                if (!performed && scroll.node.getCollectionInfo() != null) {
                    AccessibilityNodeInfo.CollectionInfo info = scroll.node.getCollectionInfo();
                    Bundle args = new Bundle();
                    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT,
                            up ? 0 : Math.max(0, info.getRowCount() - 1));
                    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT,
                            up ? 0 : Math.max(0, info.getColumnCount() - 1));
                    performed = scroll.perform(AccessibilityNodeInfo.AccessibilityAction
                            .ACTION_SCROLL_TO_POSITION.getId(), args);
                }
            } else {
                if (scroll.virtualId != AccessibilityNodeProvider.HOST_VIEW_ID
                        || scroll.view.canScrollVertically(up ? -1 : 1) || scroll.view.canScrollHorizontally(up ? -1 : 1)) {
                    performed = scroll.perform(up ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null);
                }
            }
            result.putBoolean("performed", performed);
        } catch (RuntimeException ignored) {
            result.putBoolean("performed", false);
        } finally {
            if (callback != null) callback.sendResult(result);
        }
    }

    private static Target findScrollTarget(List<Target> targets, Target focused) {
        Target best = null;
        long bestScore = -1;
        for (Target target : targets) {
            if (!readable(target.node) || !target.node.isScrollable()) continue;
            Rect bounds = new Rect();
            target.node.getBoundsInScreen(bounds);
            long score = (long) Math.max(0, bounds.width()) * Math.max(0, bounds.height());
            if (focused != null && descendantOf(focused.view, target.view)) score += 1L << 40;
            if (score > bestScore) { bestScore = score; best = target; }
        }
        return best;
    }

    private static boolean descendantOf(View child, View ancestor) {
        for (View view = child; view != null;) {
            if (view == ancestor) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    private static boolean readable(AccessibilityNodeInfo node) {
        return node != null && node.isVisibleToUser() && !node.isPassword()
                && !node.isAccessibilityDataSensitive();
    }

    private static List<Target> collect(View root) {
        List<Target> result = new ArrayList<>();
        ArrayDeque<View> views = new ArrayDeque<>();
        views.add(root);
        int visited = 0, queued = 1;
        while (!views.isEmpty() && visited++ < 2048 && result.size() < MAX_NODES) {
            View view = views.removeFirst();
            if (!view.isShown()) continue;
            AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
            if (provider != null) {
                ArrayDeque<Integer> ids = new ArrayDeque<>();
                Set<Integer> seen = new HashSet<>();
                ids.add(AccessibilityNodeProvider.HOST_VIEW_ID);
                while (!ids.isEmpty() && result.size() < MAX_NODES && seen.size() < 256) {
                    int id = ids.removeFirst();
                    if (!seen.add(id)) continue;
                    AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(id);
                    if (node == null) continue;
                    result.add(new Target(view, node, id));
                    if (node.isPassword() || node.isAccessibilityDataSensitive()) continue;
                    for (int i = 0; i < Math.min(node.getChildCount(), 128); i++) {
                        ids.add(AccessibilityNodeInfo.getVirtualDescendantId(node.getChildId(i)));
                    }
                }
            } else {
                AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
                if (node != null) {
                    result.add(new Target(view, node, AccessibilityNodeProvider.HOST_VIEW_ID));
                    if (node.isPassword() || node.isAccessibilityDataSensitive()) continue;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < Math.min(group.getChildCount(), 512) && queued < 2048; i++) {
                    views.add(group.getChildAt(i));
                    queued++;
                }
            }
        }
        return result;
    }

    private static final class Target {
        final View view;
        final AccessibilityNodeInfo node;
        final int virtualId;
        Target(View view, AccessibilityNodeInfo node, int virtualId) {
            this.view = view;
            this.node = node;
            this.virtualId = virtualId;
        }
        boolean perform(int action, Bundle args) {
            AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
            return virtualId == AccessibilityNodeProvider.HOST_VIEW_ID
                    ? view.performAccessibilityAction(action, args)
                    : provider != null && provider.performAction(virtualId, action, args);
        }
    }

    private EdgeGoWindowActions() { }
}
