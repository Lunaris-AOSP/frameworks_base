package com.android.systemui.axdynamicbar.data.source

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject

/** AxDynamicBar owns a separate session of the shared event source. */
@SysUISingleton
class AppTrackingIslandManager @Inject constructor(@Application context: Context) :
    com.android.systemui.islandevents.data.source.AppTrackingIslandManager(
        context
    )
