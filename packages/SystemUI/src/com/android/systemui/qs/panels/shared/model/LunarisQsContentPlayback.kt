/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.qs.panels.shared.model

internal fun shouldPlayQsContent(
    layout: AxQsLayout,
    panelExpansion: Float,
    qsExpansion: Float,
    qsExpanded: Boolean,
    visible: Boolean,
    transitioning: Boolean,
): Boolean =
    visible &&
        !transitioning &&
        panelExpansion >= 1f &&
        when (layout) {
            AxQsLayout.QQS -> qsExpansion <= 0f && !qsExpanded
            AxQsLayout.QS,
            AxQsLayout.SPLIT_SHADE -> qsExpansion >= 1f && qsExpanded
        }
