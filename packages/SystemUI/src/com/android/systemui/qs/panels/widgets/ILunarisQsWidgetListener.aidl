/* Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.widgets;

import android.appwidget.AppWidgetProviderInfo;
import android.widget.RemoteViews;

oneway interface ILunarisQsWidgetListener {
    void onUpdate(in @nullable AppWidgetProviderInfo info, in @nullable RemoteViews views);
    void onDataChanged(int viewId);
    void onRemoved();
}
