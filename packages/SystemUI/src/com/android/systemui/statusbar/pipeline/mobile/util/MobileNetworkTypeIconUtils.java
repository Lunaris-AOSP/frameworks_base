/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.systemui.statusbar.pipeline.mobile.util;

import androidx.annotation.Nullable;

import com.android.settingslib.R;
import com.android.settingslib.SignalIcon.MobileIconGroup;
import com.android.settingslib.mobile.TelephonyIcons;

public final class MobileNetworkTypeIconUtils {
    private MobileNetworkTypeIconUtils() {}

    @Nullable
    public static MobileIconGroup get5gIconGroup(int contentDescription) {
        if (contentDescription == R.string.data_connection_4g
                || contentDescription == R.string.data_connection_lte
                || contentDescription == R.string.data_connection_4g_lte) {
            return TelephonyIcons.NR_5G;
        }
        if (contentDescription == R.string.data_connection_4g_plus
                || contentDescription == R.string.data_connection_lte_plus
                || contentDescription == R.string.data_connection_4g_lte_plus) {
            return TelephonyIcons.NR_5G_PLUS;
        }
        return null;
    }
}
