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

package com.android.systemui.tuner;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.preference.DropDownPreference;

import com.android.systemui.Dependency;
import com.android.systemui.tuner.TunerService.Tunable;

public class TunerDropDownPreference extends DropDownPreference implements Tunable {

    public TunerDropDownPreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public TunerDropDownPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public TunerDropDownPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onAttached() {
        super.onAttached();
        Dependency.get(TunerService.class).addTunable(this, getKey().split(","));
    }

    @Override
    public void onDetached() {
        Dependency.get(TunerService.class).removeTunable(this);
        super.onDetached();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        String defaultVal = (getEntryValues() != null && getEntryValues().length > 0)
                ? getEntryValues()[0].toString() : "0";
        String val = newValue != null ? newValue : defaultVal;
        if (!TextUtils.equals(getValue(), val)) {
            setValue(val);
        }
    }

    @Override
    protected boolean persistString(String value) {
        TunerService tunerService = Dependency.get(TunerService.class);
        for (String key : getKey().split(",")) {
            String current = tunerService.getValue(key);
            if (!TextUtils.equals(current, value)) {
                tunerService.setValue(key, value);
            }
        }
        return true;
    }
}

