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

package com.android.systemui.keyguard.domain.interactor

import android.os.UserHandle
import android.provider.Settings.Secure.DOZE_FP_ONLY
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class AodFpOnlyInteractor
@Inject
constructor(
    secureSettings: SecureSettings,
    userRepository: UserRepository,
    udfpsInteractor: DeviceEntryUdfpsInteractor,
    keyguardInteractor: KeyguardInteractor,
    @Background backgroundDispatcher: CoroutineDispatcher,
    @Application applicationScope: CoroutineScope,
) {
    private val isEnabled =
        combine(
                userRepository.selectedUserInfo,
                udfpsInteractor.isUdfpsSupported,
                secureSettings.observerFlow(UserHandle.USER_ALL, DOZE_FP_ONLY).onStart { emit(Unit) },
            ) { user, supportsUdfps, _ ->
                supportsUdfps && secureSettings.getIntForUser(DOZE_FP_ONLY, 0, user.id) == 1
            }
            .flowOn(backgroundDispatcher)
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    val isActive =
        combine(isEnabled, keyguardInteractor.isDozing) { enabled, dozing -> enabled && dozing }
            .stateIn(applicationScope, SharingStarted.Eagerly, false)
}
