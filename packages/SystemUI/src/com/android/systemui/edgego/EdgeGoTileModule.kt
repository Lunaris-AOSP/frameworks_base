/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.edgego

import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
abstract class EdgeGoTileModule {
    @Binds
    @IntoMap
    @StringKey(EdgeGoTile.TILE_SPEC)
    abstract fun bindEdgeGoTile(tile: EdgeGoTile): QSTileImpl<*>

    companion object {
        @Provides
        @IntoMap
        @StringKey(EdgeGoTile.TILE_SPEC)
        fun provideEdgeGoTileConfig(logger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(EdgeGoTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_edgego_gesture,
                    labelRes = R.string.edgego_title,
                ),
                instanceId = logger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )
    }
}
