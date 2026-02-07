/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.qs.tiles

import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.text.TextUtils
import android.text.format.Formatter
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.internal.logging.MetricsLogger
import com.android.settingslib.graph.SignalDrawable
import com.android.settingslib.net.DataUsageController
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfigProvider
import com.android.systemui.qs.tiles.base.shared.model.QSTileState
import com.android.systemui.qs.tiles.impl.wifi.domain.interactor.WifiTileDataInteractor
import com.android.systemui.qs.tiles.impl.wifi.domain.interactor.WifiTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.wifi.domain.model.WifiTileModel
import com.android.systemui.qs.tiles.impl.wifi.ui.mapper.WifiTileMapper
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.shared.ui.model.SignalIcon
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Quick settings tile: Wifi */
class WifiTile
@Inject
constructor(
    private val host: QSHost,
    private val uiEventLogger: QsEventLogger,
    @Background private val backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    private val falsingManager: FalsingManager,
    private val metricsLogger: MetricsLogger,
    private val statusBarStateController: StatusBarStateController,
    private val activityStarter: ActivityStarter,
    private val qsLogger: QSLogger,
    private val qsTileConfigProvider: QSTileConfigProvider,
    private val dataInteractor: WifiTileDataInteractor,
    private val tileMapper: WifiTileMapper,
    private val userActionInteractor: WifiTileUserActionInteractor,
) :
    QSTileImpl<QSTile.State?>(
        host,
        uiEventLogger,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger,
    ) {

    private lateinit var tileState: QSTileState
    private val config = qsTileConfigProvider.getConfig(TILE_SPEC)
    
    private val dataController: DataUsageController = DataUsageController(mContext)
    private var showDataUsage: Boolean = false
    private val settingsObserver: SettingsObserver = SettingsObserver(mainHandler)

    init {
        lifecycle.coroutineScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dataInteractor.tileData().collect { refreshState(it) }
            }
        }
        updateDataUsageSetting()
    }

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (listening) {
            settingsObserver.observe()
        } else {
            settingsObserver.unobserve()
        }
    }

    private fun updateDataUsageSetting() {
        showDataUsage = Settings.System.getIntForUser(
            mContext.contentResolver,
            Settings.System.QS_SHOW_DATA_USAGE_TILE,
            1,
            UserHandle.USER_CURRENT
        ) == 1
    }

    private fun getFormattedWifiDataUsage(): String {
        return try {
            var info = dataController.getWifiDailyDataUsageInfo(true)
            if (info == null) {
                info = dataController.getWifiDailyDataUsageInfo(false)
            }
            if (info != null && info.usageLevel >= 0) {
                val formattedSize = Formatter.formatFileSize(
                    mContext, 
                    info.usageLevel,
                    Formatter.FLAG_IEC_UNITS
                )
                "$formattedSize ${mContext.getString(R.string.usage_data)}"
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get WiFi data usage", e)
            ""
        }
    }

    override fun getTileLabel(): CharSequence = mContext.getString(config.uiConfig.labelRes)

    override fun newTileState(): QSTile.State? {
        return QSTile.State().apply { state = Tile.STATE_INACTIVE }
    }

    override fun handleClick(expandable: Expandable?) {
        lifecycle.coroutineScope.launch { userActionInteractor.handleClick(expandable) }
    }

    override fun handleSecondaryClick(expandable: Expandable?) {
        userActionInteractor.handleSecondaryClick(expandable)
    }

    override fun getLongClickIntent(): Intent = userActionInteractor.longClickIntent

    override fun handleUpdateState(state: QSTile.State?, arg: Any?) {
        val model = arg as? WifiTileModel ?: return
        tileState = tileMapper.map(config, model)

        state?.apply {
            this.state = tileState.activationState.legacyState
            icon =
                (tileState.icon as? Icon.Loaded)?.resId?.let { resId ->
                    maybeLoadResourceIcon(resId)
                } ?: SignalIcon(SignalDrawable.getState(0, 4, false))
            label = tileState.label
            
            secondaryLabel = if (this.state == Tile.STATE_ACTIVE) {
                if (showDataUsage) {
                    val dataUsage = getFormattedWifiDataUsage()
                    if (!TextUtils.isEmpty(dataUsage)) {
                        dataUsage
                    } else {
                        tileState.secondaryLabel
                    }
                } else {
                    tileState.secondaryLabel
                }
            } else {
                null
            }
            
            contentDescription = tileState.contentDescription
            expandedAccessibilityClassName = tileState.expandedAccessibilityClassName
            handlesSecondaryClick =
                tileState.supportedActions.contains(QSTileState.UserAction.TOGGLE_CLICK)
            handlesLongClick =
                tileState.supportedActions.contains(QSTileState.UserAction.LONG_CLICK)
        }
    }

    override fun isAvailable(): Boolean {
        return dataInteractor.isAvailable()
    }

    private inner class SettingsObserver(handler: Handler) : ContentObserver(handler) {
        fun observe() {
            mContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.QS_SHOW_DATA_USAGE_TILE),
                false,
                this,
                UserHandle.USER_ALL
            )
        }

        fun unobserve() {
            mContext.contentResolver.unregisterContentObserver(this)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            updateDataUsageSetting()
            refreshState()
        }
    }

    companion object {
        const val TILE_SPEC = "wifi"
    }
}
