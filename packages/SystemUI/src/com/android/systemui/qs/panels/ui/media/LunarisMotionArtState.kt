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

package com.android.systemui.qs.panels.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.media.remedia.motionart.LunarisMotionArt
import java.io.File

@Composable
internal fun rememberLunarisMotionArtFile(
    artist: String?,
    title: String?,
    enabled: Boolean,
): File? {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var file by remember(context, artist, title) { mutableStateOf<File?>(null) }
    LaunchedEffect(context, lifecycleOwner, artist, title, enabled) {
        file = null
        if (!enabled || artist.isNullOrBlank() || title.isNullOrBlank()) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            file = LunarisMotionArt.find(context, artist, title)
        }
    }
    return if (enabled) file else null
}
