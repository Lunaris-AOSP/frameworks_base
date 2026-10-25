/*
 * Copyright (C) 2026 The PenguinOS Project
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

package com.android.systemui.media.remedia.motionart

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

object LunarisMotionArt {
    enum class Kind(val key: String, val targetWidth: Int, val suffix: String) {
        SQUARE("motionDetailSquare", 768, ""),
        TALL("motionDetailTall", 830, "-tall"),
    }

    private const val TAG = "LunarisMotionArt"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/138.0 Mobile Safari/537.36"
    private const val MAX_BYTES = 24L * 1024 * 1024
    private const val MAX_CACHED = 40
    private const val MAX_CACHE_BYTES = 96L * 1024 * 1024
    private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
    private const val MISS_TTL_MS = 60L * 60 * 1000
    private const val MAX_LOOKUPS = 256

    private data class Lookup(val albumId: String?, val time: Long)

    private val lock = Mutex()
    private val albums = LinkedHashMap<String, Lookup>(MAX_LOOKUPS, 0.75f, true)

    suspend fun find(
        context: Context,
        artist: String,
        title: String,
        kind: Kind = Kind.SQUARE,
    ): File? =
        withContext(Dispatchers.IO) {
            if (normalise(artist).isEmpty() || normalise(title).isEmpty()) return@withContext null
            lock.withLock {
                currentCoroutineContext().ensureActive()
                val dir = File(context.createDeviceProtectedStorageContext().cacheDir, "motion_art")
                val key = "$dir\u0000$artist\u0000$title\u0000$kind"
                val now = SystemClock.elapsedRealtime()
                val known = albums[key]
                if (known != null) {
                    if (known.albumId == null && now - known.time < MISS_TTL_MS)
                        return@withLock null
                    val cached = known.albumId?.let { File(dir, "$it${kind.suffix}.mp4") }
                    if (cached != null && cached.isFile && cached.length() in 1..MAX_BYTES) {
                        cached.setLastModified(System.currentTimeMillis())
                        return@withLock cached
                    }
                }
                try {
                    val album = findAlbum(artist, title)
                    if (album == null) {
                        rememberLookup(key, Lookup(null, now))
                        return@withLock null
                    }
                    val (id, page) = album
                    val cached = File(dir, "$id${kind.suffix}.mp4")
                    if (
                        (!cached.isFile || cached.length() !in 1..MAX_BYTES) &&
                            !download(page, kind, cached)
                    ) {
                        rememberLookup(key, Lookup(null, now))
                        return@withLock null
                    }
                    rememberLookup(key, Lookup(id, now))
                    cached.setLastModified(System.currentTimeMillis())
                    trim(dir)
                    cached
                } catch (e: IOException) {
                    Log.w(TAG, "Motion art lookup failed", e)
                    null
                } catch (e: JSONException) {
                    Log.w(TAG, "Invalid motion art response", e)
                    null
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Invalid motion art URL", e)
                    null
                }
            }
        }

    private fun rememberLookup(key: String, lookup: Lookup) {
        albums[key] = lookup
        if (albums.size > MAX_LOOKUPS) albums.remove(albums.keys.first())
    }

    private suspend fun findAlbum(artist: String, title: String): Pair<String, String>? {
        val country = Locale.getDefault().country.lowercase(Locale.ROOT).ifEmpty { "us" }
        val term = URLEncoder.encode("$artist $title", "UTF-8")
        return parseAlbum(
            fetch(
                "https://itunes.apple.com/search?term=$term&entity=song&limit=10&country=$country"
            ),
            artist,
            title,
        )
    }

    internal fun parseAlbum(search: String, artist: String, title: String): Pair<String, String>? {
        val results = JSONObject(search).optJSONArray("results") ?: return null
        val wantedArtist = normalise(artist)
        val wantedTitle = normalise(title)
        if (wantedArtist.isEmpty() || wantedTitle.isEmpty()) return null
        for (i in 0 until results.length()) {
            val song = results.optJSONObject(i) ?: continue
            val songArtist = normalise(song.optString("artistName"))
            val songTitle = normalise(song.optString("trackName"))
            if (songArtist.isEmpty() || songTitle.isEmpty()) continue
            val artistMatches =
                songArtist.contains(wantedArtist) || wantedArtist.contains(songArtist)
            val titleMatches =
                songTitle.startsWith(wantedTitle) || wantedTitle.startsWith(songTitle)
            if (!artistMatches || !titleMatches) continue
            val id = song.optLong("collectionId").takeIf { it > 0 } ?: continue
            val page = song.optString("collectionViewUrl").substringBefore('?')
            if (page.startsWith("https://")) return id.toString() to page
        }
        return null
    }

    private fun normalise(text: String) =
        text
            .lowercase(Locale.ROOT)
            .replace(Regex("\\(.*?\\)|\\[.*?]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private suspend fun download(page: String, kind: Kind, target: File): Boolean {
        val html = fetch(page)
        val key = "\"${kind.key}\""
        val start = html.indexOf(key)
        if (start < 0) return false
        val valueStart = html.indexOf(':', start + key.length)
        if (valueStart < 0) return false
        val detail =
            JSONTokener(html.substring(valueStart + 1)).nextValue() as? JSONObject ?: return false
        val video = detail.optString("video")
        if (video.isBlank() || !URL(video).path.endsWith(".m3u8")) return false
        val best = selectVariant(fetch(video), video, kind) ?: return false
        val map = completeMap(fetch(best), best) ?: return false
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        try {
            val connection = open(map)
            try {
                if (
                    connection.responseCode != HttpURLConnection.HTTP_OK ||
                        connection.contentLengthLong > MAX_BYTES
                )
                    return false
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_BYTES)
                                throw IOException("Motion art exceeds cache limit")
                            output.write(buffer, 0, count)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            currentCoroutineContext().ensureActive()
            return partial.length() in 1..MAX_BYTES && partial.renameTo(target)
        } finally {
            partial.delete()
        }
    }

    internal fun selectVariant(playlist: String, video: String, kind: Kind): String? {
        val master = playlist.lines()
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (i in master.indices) {
            val line = master[i]
            if (!line.startsWith("#EXT-X-STREAM-INF") || !line.contains("avc1")) continue
            val width =
                Regex("RESOLUTION=(\\d+)x").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    ?: continue
            val uri = master.getOrNull(i + 1)?.trim().orEmpty()
            if (uri.isEmpty() || uri.startsWith("#")) continue
            val distance = abs(width - kind.targetWidth)
            if (distance < bestDistance) {
                best = URL(URL(video), uri).toString()
                bestDistance = distance
            }
        }
        return best
    }

    internal fun completeMap(playlist: String, variant: String): String? {
        val line =
            playlist.lineSequence().firstOrNull { it.startsWith("#EXT-X-MAP:") } ?: return null
        val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: return null
        val map = URL(URL(variant), uri).toString()
        if (line.contains("BYTERANGE=")) {
            val segments =
                playlist
                    .lineSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            if (segments.isEmpty() || segments.any { URL(URL(variant), it).toString() != map }) {
                return null
            }
        }
        return map
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { file -> file.name.endsWith(".mp4") } ?: return
        var bytes = 0L
        files
            .sortedByDescending { it.lastModified() }
            .forEachIndexed { index, file ->
                bytes += file.length()
                if (index >= MAX_CACHED || bytes > MAX_CACHE_BYTES) file.delete()
            }
    }

    private fun open(url: String): HttpURLConnection {
        val parsed = URL(url)
        if (parsed.protocol != "https") throw IOException("Unsupported motion art URL")
        return (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", USER_AGENT)
        }
    }

    private suspend fun fetch(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) {
                throw IOException("Motion art response exceeds limit")
            }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > MAX_RESPONSE_BYTES) {
                        throw IOException("Motion art response exceeds limit")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }
}
