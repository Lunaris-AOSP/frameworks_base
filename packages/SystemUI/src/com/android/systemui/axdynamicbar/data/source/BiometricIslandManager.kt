package com.android.systemui.axdynamicbar.data.source

import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** AxDynamicBar owns a separate session of the shared event source. */
@SysUISingleton
class BiometricIslandManager @Inject constructor(keyguardUpdateMonitor: KeyguardUpdateMonitor) :
    com.android.systemui.islandevents.data.source.BiometricIslandManager(
        keyguardUpdateMonitor
    )
