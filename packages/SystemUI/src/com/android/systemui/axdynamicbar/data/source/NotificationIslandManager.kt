package com.android.systemui.axdynamicbar.data.source

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/** AxDynamicBar owns a separate session of the shared event source. */
@SysUISingleton
class NotificationIslandManager @Inject constructor(
    @Application context: Context,
    @Application applicationScope: CoroutineScope,
) :
    com.android.systemui.islandevents.data.source.NotificationIslandManager(
        context, applicationScope
    )
