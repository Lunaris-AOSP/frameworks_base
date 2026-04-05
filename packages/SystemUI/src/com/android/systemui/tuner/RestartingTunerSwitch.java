/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.AttributeSet;

import com.android.systemui.SysuiRestartReceiver;
import com.android.systemui.res.R;

/** A tuner switch that confirms and then restarts SystemUI after persisting a changed value. */
public class RestartingTunerSwitch extends TunerSwitch {

    public RestartingTunerSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onClick() {
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.tuner_restart_systemui_dialog_title)
                .setMessage(R.string.tuner_restart_systemui_dialog_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> confirmToggle())
                .show();
    }

    private void confirmToggle() {
        final boolean wasChecked = isChecked();
        super.onClick();
        if (wasChecked != isChecked()) {
            restartSystemUi();
        }
    }

    private void restartSystemUi() {
        final Context context = getContext();
        final String packageName = context.getPackageName();
        final Intent intent = new Intent(SysuiRestartReceiver.ACTION)
                .setComponent(new ComponentName(context, SysuiRestartReceiver.class))
                .setData(Uri.parse("package:" + packageName));
        context.sendBroadcast(intent);
    }
}
