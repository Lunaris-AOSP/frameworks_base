/* Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.widgets;

import com.android.systemui.qs.panels.widgets.ILunarisQsWidgetListener;

oneway interface ILunarisQsWidgetHost {
    void connect(String layout, int widgetId, ILunarisQsWidgetListener listener);
    void setActive(ILunarisQsWidgetListener listener, boolean active);
    void resize(ILunarisQsWidgetListener listener, float width, float height);
    void disconnect(ILunarisQsWidgetListener listener);
}
