/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.clockmaker

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ClockArchive {
    const val MIME_TYPE = "application/zip"
    const val EXTENSION = ".clock.zip"
    const val MAX_FONT_BYTES = 10 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 128 * 1024
    private const val MAX_TOTAL_BYTES = ClockImages.MAX_STORAGE_BYTES + ClockDesign.MAX_ELEMENTS * MAX_FONT_BYTES.toLong()
    private const val MANIFEST = "clock.json"
    private const val FORMAT = "org.lunaris.clockmaker"
    private val assetPath = Regex("assets/[0-9]{1,2}\\.(ttf|otf|img|json)")
    private val fontReference = Regex("(import:[a-f0-9-]{36}\\.(ttf|otf)|bundled:[A-Za-z0-9_-]+)")

    enum class Kind { FONT, IMAGE }

    data class Asset(val kind: Kind, val reference: String, val name: String, val path: String) {
        val maxBytes: Int get() = if (kind == Kind.FONT) MAX_FONT_BYTES else ClockImages.MAX_FILE_BYTES
        fun toJson() = JSONObject().put("kind", kind.name).put("reference", reference)
            .put("name", name).put("path", path)
    }

    data class Contents(val clock: JSONObject, val assets: List<Asset>)

    fun write(output: OutputStream, clock: JSONObject, assets: List<Asset>,
        checkActive: () -> Unit = {}, open: (Asset) -> InputStream) {
        checkActive()
        validate(clock, assets)
        val manifest = JSONObject().put("format", FORMAT).put("version", 1).put("clock", clock)
            .put("assets", JSONArray().also { array -> assets.forEach { array.put(it.toJson()) } })
            .toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST_BYTES)
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest)
            zip.closeEntry()
            var total = 0L
            assets.forEach { asset ->
                zip.putNextEntry(ZipEntry(asset.path))
                checkActive()
                total += open(asset).use { copy(it, zip, minOf(asset.maxBytes.toLong(), MAX_TOTAL_BYTES - total), checkActive) }
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream, directory: File, checkActive: () -> Unit = {}): Contents {
        checkActive()
        require(directory.isDirectory && directory.listFiles()?.isEmpty() == true)
        ZipInputStream(input.buffered()).use { zip ->
            val first = requireNotNull(zip.nextEntry)
            require(first.name == MANIFEST && !first.isDirectory)
            val bytes = zip.readNBytes(MAX_MANIFEST_BYTES + 1)
            require(bytes.isNotEmpty() && bytes.size <= MAX_MANIFEST_BYTES)
            val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
            require(manifest.getString("format") == FORMAT && manifest.getInt("version") == 1)
            val array = manifest.getJSONArray("assets")
            require(array.length() <= ClockDesign.MAX_ELEMENTS * 2)
            val assets = List(array.length()) { index ->
                val value = array.getJSONObject(index)
                Asset(Kind.valueOf(value.getString("kind")), value.getString("reference"),
                    value.getString("name"), value.getString("path"))
            }
            val clock = manifest.getJSONObject("clock")
            validate(clock, assets)
            val remaining = assets.associateBy { it.path }.toMutableMap()
            var total = 0L
            while (true) {
                checkActive()
                val entry = zip.nextEntry ?: break
                val asset = requireNotNull(remaining.remove(entry.name))
                require(!entry.isDirectory && entry.size <= asset.maxBytes)
                val target = File(directory, asset.path.substringAfter('/'))
                target.outputStream().use { output ->
                    total += copy(zip, output, minOf(asset.maxBytes.toLong(), MAX_TOTAL_BYTES - total), checkActive)
                }
                zip.closeEntry()
            }
            require(remaining.isEmpty())
            return Contents(clock, assets)
        }
    }

    private fun validate(clock: JSONObject, assets: List<Asset>) {
        require(clock.getInt("face") == ClockDesign.STYLE)
        clock.getJSONObject("tuning")
        val json = clock.getJSONObject("design")
        require(json.toString().length <= ClockDesign.MAX_JSON_LENGTH)
        val design = ClockDesign.fromJson(json)
        require(design.elements.isNotEmpty())
        val rawElements = json.getJSONArray("elements")
        design.elements.forEachIndexed { index, element ->
            val raw = rawElements.getJSONObject(index)
            require(raw.getString("kind") == element.kind.name)
            require(raw.optString("image") == element.image && raw.getString("font") == element.font)
        }
        require(assets.size <= ClockDesign.MAX_ELEMENTS * 2)
        require(assets.map { it.path }.distinct().size == assets.size)
        require(assets.map { it.kind to it.reference }.distinct().size == assets.size)
        assets.forEach { asset ->
            require(assetPath.matches(asset.path) && asset.name.length in 1..60)
            val extension = asset.path.substringAfterLast('.')
            when (asset.kind) {
                Kind.FONT -> require(fontReference.matches(asset.reference) && extension in listOf("ttf", "otf"))
                Kind.IMAGE -> require(ClockImages.validId(asset.reference) &&
                    extension == asset.reference.substringAfterLast('.'))
            }
        }
        val fonts = design.elements.map { it.font }.filter { it !in ClockFonts.families }.toSet()
        val images = design.elements.map { it.image }.filter { it.isNotEmpty() }.toSet()
        require(assets.filter { it.kind == Kind.FONT }.map { it.reference }.toSet() == fonts)
        require(assets.filter { it.kind == Kind.IMAGE }.map { it.reference }.toSet() == images)
    }

    private fun copy(input: InputStream, output: OutputStream, limit: Long, checkActive: () -> Unit): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit)
            output.write(buffer, 0, count)
        }
        require(total > 0)
        return total
    }
}
