/*
 * Copyright (C) 2025-2026 AxionOS
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

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.common.shared.model.Icon as IconModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable internal data class AxMediaArtwork(val songKey: String?, val artwork: IconModel?)

@Composable
internal fun rememberAxMediaArtwork(artwork: IconModel?, songKey: String? = null): AxMediaArtwork {
    val resources = LocalContext.current.resources
    val requested = remember(artwork, songKey) { AxMediaArtwork(songKey, artwork) }
    val bitmap = ((artwork as? IconModel.Loaded)?.drawable as? BitmapDrawable)?.bitmap
    val needsScaling =
        bitmap != null &&
            !bitmap.isRecycled &&
            maxOf(bitmap.width, bitmap.height) > AxMediaArtProcessor.MAX_DIMENSION
    val generationId = bitmap?.takeUnless { it.isRecycled }?.generationId
    val prepared by
        produceState(
            initialValue = requested.copy(artwork = null),
            requested,
            generationId,
            resources,
        ) {
            value =
                if (needsScaling && bitmap != null && artwork is IconModel.Loaded) {
                    val scaled =
                        withContext(Dispatchers.Default) { AxMediaArtProcessor.resize(bitmap) }
                    if (scaled === bitmap) requested
                    else
                        requested.copy(
                            artwork =
                                IconModel.Loaded(
                                    BitmapDrawable(resources, scaled),
                                    artwork.contentDescription,
                                )
                        )
                } else requested
        }
    return if (needsScaling) prepared else requested
}

private object AxMediaArtProcessor {
    const val MAX_DIMENSION = 1024
    private const val CACHE_BYTES = 8 * 1024 * 1024

    private data class ArtworkKey(val identity: Int, val generation: Int)

    private val cache =
        object : LruCache<ArtworkKey, Bitmap>(CACHE_BYTES) {
            override fun sizeOf(key: ArtworkKey, value: Bitmap): Int = value.allocationByteCount
        }

    fun resize(bitmap: Bitmap): Bitmap {
        if (bitmap.isRecycled) return bitmap
        val key = ArtworkKey(System.identityHashCode(bitmap), bitmap.generationId)
        cache.get(key)?.let {
            return it
        }
        val scale = MAX_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val resized =
            try {
                Bitmap.createScaledBitmap(bitmap, width, height, true)
            } catch (_: IllegalArgumentException) {
                return bitmap
            }
        cache.put(key, resized)
        return resized
    }
}
