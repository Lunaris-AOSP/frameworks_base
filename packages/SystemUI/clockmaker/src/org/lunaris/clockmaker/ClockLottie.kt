/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.util.Base64
import com.airbnb.lottie.FontAssetDelegate
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer

object ClockLottie {
    private const val MAX_PRECOMP_DEPTH = 64
    private const val LAYER_PRECOMP = 0
    private const val LAYER_IMAGE = 2

    fun read(input: InputStream): LottieComposition {
        val bytes = input.readNBytes(ClockImages.MAX_FILE_BYTES + 1)
        require(bytes.isNotEmpty() && bytes.size <= ClockImages.MAX_FILE_BYTES)
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val json = JSONObject(text)
        requireSize(json.getInt("w"), json.getInt("h"))
        val start = json.getDouble("ip")
        val end = json.getDouble("op")
        val rate = json.getDouble("fr")
        require(start.isFinite() && end.isFinite() && end > start && rate.isFinite() && rate > 0)
        require(json.getJSONArray("layers").length() > 0)
        validateLayers(json)
        val result = LottieCompositionFactory.fromJsonStringSync(json.toString(), null)
        val composition = result.value ?: throw IllegalArgumentException("Invalid Lottie animation", result.exception)
        require(composition.startFrame.isFinite() && composition.endFrame.isFinite() &&
            composition.durationFrames.isFinite() && composition.durationFrames > 0)
        require(composition.duration.isFinite() && composition.duration > 0 &&
            composition.frameRate.isFinite() && composition.frameRate > 0)
        require(composition.bounds.width() > 0 && composition.bounds.height() > 0)
        var pixels = 0L
        composition.images.values.forEach { asset ->
            requireSize(asset.width, asset.height)
            val source = asset.fileName
            val separator = source.indexOf(";base64,")
            require(source.startsWith("data:image/") && separator > 0)
            val data = Base64.decode(source.substring(separator + 8), Base64.DEFAULT)
            asset.bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { decoder, info, _ ->
                pixels += info.size.width.toLong() * info.size.height
                require(pixels <= ClockImages.MAX_IMAGE_PIXELS)
                ClockImages.configureDecoder(decoder, info)
            }
        }
        return composition
    }

    internal fun drawable(composition: LottieComposition) = LottieDrawable().apply {
        setSafeMode(true)
        setMaintainOriginalImageBounds(true)
        setFontAssetDelegate(object : FontAssetDelegate() {
            override fun fetchFont(fontFamily: String): Typeface = Typeface.create(fontFamily, Typeface.NORMAL)
        })
        setComposition(composition)
        repeatCount = LottieDrawable.INFINITE
    }

    private fun requireSize(width: Int, height: Int) {
        require(width > 0 && height > 0 && width.toLong() * height <= ClockImages.MAX_IMAGE_PIXELS)
    }

    private fun validateLayers(json: JSONObject) {
        val images = mutableSetOf<String>()
        val precomps = mutableMapOf<String, JSONArray>()
        val assets = json.optJSONArray("assets") ?: JSONArray()
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val id = asset.getString("id")
            require(id !in images && id !in precomps)
            if (asset.has("p")) images += id
            else precomps[id] = asset.optJSONArray("layers") ?: JSONArray()
        }
        val visiting = mutableSetOf<String>()
        val depths = mutableMapOf<String, Int>()
        fun visit(layers: JSONArray): Int {
            val byId = (0 until layers.length()).map { layers.getJSONObject(it) }.associateBy { it.optInt("ind") }
            require(byId.size == layers.length())
            val checkedParents = mutableSetOf<Int>()
            var depth = 0
            byId.values.forEach { layer ->
                val path = mutableSetOf<Int>()
                var parent: JSONObject? = layer
                while (parent != null && parent.optInt("ind") !in checkedParents) {
                    require(path.add(parent.optInt("ind")))
                    parent = byId[parent.optInt("parent", -1)]
                }
                checkedParents += path
                when (layer.optInt("ty", -1)) {
                    LAYER_IMAGE -> require(layer.getString("refId") in images)
                    LAYER_PRECOMP -> {
                        val id = layer.getString("refId")
                        require(id !in visiting && visiting.size < MAX_PRECOMP_DEPTH)
                        val childDepth = depths[id] ?: run {
                            visiting += id
                            val result = visit(requireNotNull(precomps[id]))
                            visiting -= id
                            depths[id] = result
                            result
                        }
                        depth = maxOf(depth, childDepth + 1)
                        require(depth <= MAX_PRECOMP_DEPTH)
                    }
                    else -> Unit
                }
            }
            return depth
        }
        visit(json.getJSONArray("layers"))
    }
}
