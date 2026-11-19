/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.edgego;

import com.android.systemui.CoreStartable;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

@Module(includes = EdgeGoTileModule.class)
public abstract class EdgeGoModule {
    @Binds
    @IntoMap
    @ClassKey(EdgeGoGestureController.class)
    abstract CoreStartable bindEdgeGoGestureController(EdgeGoGestureController controller);
}
