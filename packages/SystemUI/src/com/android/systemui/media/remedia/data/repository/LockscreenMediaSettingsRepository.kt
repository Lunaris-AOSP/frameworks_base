/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.media.remedia.data.repository

import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.controls.shared.MediaSeekBarStyle
import com.android.systemui.media.remedia.shared.model.LockscreenMediaStyle
import com.android.systemui.shared.settings.data.repository.SystemSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class LockscreenMediaSettingsRepository
@Inject
constructor(
    systemSettingsRepository: SystemSettingsRepository,
    @Application applicationScope: CoroutineScope,
) {
    val style =
        systemSettingsRepository
            .intSetting(LockscreenMediaStyle.SETTING, LockscreenMediaStyle.DEFAULT.settingValue)
            .map(LockscreenMediaStyle::fromSetting)
            .stateIn(applicationScope, SharingStarted.Eagerly, LockscreenMediaStyle.DEFAULT)

    val seekBarStyle =
        systemSettingsRepository
            .intSetting(Settings.System.MEDIA_WAVEFORM_SEEKBAR, MediaSeekBarStyle.DEFAULT.value)
            .map(MediaSeekBarStyle::fromValue)
            .stateIn(applicationScope, SharingStarted.Eagerly, MediaSeekBarStyle.DEFAULT)
}
