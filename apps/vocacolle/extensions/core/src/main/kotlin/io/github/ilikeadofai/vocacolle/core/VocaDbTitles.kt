@file:JvmName("VocaDbTitles")

package io.github.ilikeadofai.vocacolle.core

import android.content.Context
import android.util.Log
import app.reseam.runtime.Export
import io.github.ilikeadofai.vocacolle.settings.MetadataSettings
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Display titles, and YouTube links for sharing, from VocaDB for the exact Niconico video a
 * player item shows.
 *
 * The player builds a fresh item list on every queue or track change. A title that is already
 * known is shown at once; an unknown one is looked up in the background and shows from the next
 * rebuild. Nothing here blocks the caller, and every failure leaves the original title.
 */

private const val TAG = "VocaColleVocaDb"
private const val ENDPOINT = "https://vocadb.net/api/songs/byPv"
private const val MAX_BODY_BYTES = 256 * 1024
private const val FOUND_TTL_MS = 7L * 24 * 60 * 60 * 1000
private const val NOT_FOUND_TTL_MS = 6L * 60 * 60 * 1000
private const val CACHE_FORMAT = "v2"
private val MEDIA_ID = Regex("^(sm|so|nm)[0-9]{1,12}$")
private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{11}$")

/** What VocaDB knows about one video. An empty [title] and no [youtubeIds] means "not on VocaDB". */
private class Song(val title: String, val youtubeIds: List<String>) {
    val isEmpty get() = title.isEmpty() && youtubeIds.isEmpty()
}

private val NOT_FOUND = Song("", emptyList())

private class Entry(val song: Song, val savedAt: Long)

@Volatile private var cacheDir: File? = null

/** videoId -> what VocaDB knows, [NOT_FOUND] included. */
private val memory: MutableMap<String, Song> = Collections.synchronizedMap(
    object : LinkedHashMap<String, Song>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?) = size > 512
    },
)

private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())

// One thread, a short queue, and dropped work when it is full: a long queue only fetches titles
// for tracks the user has already skipped.
private val lookups = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue(32), { runnable ->
    Thread(runnable, TAG).apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
}, ThreadPoolExecutor.DiscardPolicy()).apply { allowCoreThreadTimeOut(true) }

@Export
fun init(context: Context?) {
    cacheDir = context?.applicationContext?.cacheDir?.let { File(it, "vocacolle-reseam/vocadb") }
}

/** The title to show for [videoId]; the original [title] until a VocaDB title is known. */
@Export
fun displayTitle(videoId: String?, title: String?): String? {
    if (videoId == null || title.isNullOrBlank() || !MEDIA_ID.matches(videoId)) return title
    if (!MetadataSettings.vocadbTitles.value) return title
    return known(videoId)?.title?.takeIf { it.isNotEmpty() } ?: title
}

/**
 * The text of the app's "share this work" sheet, `<title> <niconico url> #<id> …`, with the
 * niconico link swapped for the YouTube original when VocaDB lists one for that exact video.
 * Unknown videos keep the niconico link, and are looked up for the next share.
 */
@Export
fun shareText(text: String?): String? {
    if (text == null || !MetadataSettings.shareYoutube.value) return text
    val tokens = text.split(' ')
    val videoId = tokens.firstNotNullOfOrNull { token -> token.removePrefix("#").takeIf { MEDIA_ID.matches(it) } }
        ?: return text
    val youtube = known(videoId)?.youtubeIds?.firstOrNull() ?: return text
    val linkAt = tokens.indexOfFirst { it.startsWith("https://") && it.contains(videoId) }
    if (linkAt < 0) return text
    return tokens.toMutableList().apply { this[linkAt] = "https://youtu.be/$youtube" }.joinToString(" ")
}

/** Whether VocaDB titles are on and one is already known for [videoId]; read by the AI translator. */
internal fun hasVocaDbTitle(videoId: String): Boolean =
    MetadataSettings.vocadbTitles.value && memory[videoId]?.title?.isNotEmpty() == true

/** What VocaDB knows about [videoId] so far; starts a background lookup when that is nothing. */
private fun known(videoId: String): Song? {
    memory[videoId]?.let { return it }
    if (inFlight.add(videoId)) {
        try {
            lookups.execute { load(videoId) }
        } catch (_: RuntimeException) {
            inFlight.remove(videoId)
        }
    }
    return null
}

private fun load(videoId: String) {
    try {
        val now = System.currentTimeMillis()
        val cached = readCache(videoId)
        if (cached != null && now - cached.savedAt < ttl(cached.song)) {
            memory[videoId] = cached.song
            return
        }
        val fetched = try {
            fetch(videoId)
        } catch (failure: IOException) {
            // Offline or VocaDB unreachable: an expired entry is still better than nothing, and
            // a network error is never remembered as "not found".
            cached?.let { memory[videoId] = it.song }
            Log.d(TAG, "lookup failed for $videoId: ${failure.message}")
            return
        }
        memory[videoId] = fetched
        writeCache(videoId, Entry(fetched, now))
    } catch (failure: RuntimeException) {
        Log.w(TAG, "lookup failed for $videoId", failure)
    } finally {
        inFlight.remove(videoId)
    }
}

private fun ttl(song: Song) = if (song.isEmpty) NOT_FOUND_TTL_MS else FOUND_TTL_MS

private fun fetch(videoId: String): Song {
    val query = "pvService=NicoNicoDouga&pvId=" + URLEncoder.encode(videoId, "UTF-8") + "&fields=Names,PVs"
    val connection = URL("$ENDPOINT?$query").openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "vocacolle-reseam-patches")
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("HTTP $status")
        val body = connection.inputStream.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_BODY_BYTES) throw IOException("response too large")
            String(bytes, Charsets.UTF_8).trim()
        }
        if (body == "null") return NOT_FOUND
        val song = try {
            JSONObject(body)
        } catch (failure: org.json.JSONException) {
            throw IOException("malformed response", failure)
        }
        return songOf(song, videoId)
    } finally {
        connection.disconnect()
    }
}

/**
 * The song's English name, else its romanized one, and its active YouTube original PVs.
 * byPv can answer with a song whose Niconico PV was since disabled or replaced; only an active PV
 * with this exact id counts as a match.
 */
private fun songOf(song: JSONObject, videoId: String): Song {
    val pvs = song.optJSONArray("pvs")?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
        ?: return NOT_FOUND
    val active = pvs.filter { !it.optBoolean("disabled") }
    if (active.none { it.optString("service") == "NicoNicoDouga" && it.optString("pvId") == videoId }) return NOT_FOUND
    val youtube = active
        .filter { it.optString("service") == "Youtube" && it.optString("pvType") == "Original" }
        .map { it.optString("pvId").trim() }
        .filter { YOUTUBE_ID.matches(it) }
        .distinct()
    val names = song.optJSONArray("names")?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } }.orEmpty()
    fun name(language: String) = names.firstOrNull { it.optString("language") == language }
        ?.optString("value")?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it }
    return Song(name("English") ?: name("Romaji") ?: "", youtube)
}

private fun cacheFile(videoId: String) = cacheDir?.let { File(it, videoId) }

private fun readCache(videoId: String): Entry? {
    val file = cacheFile(videoId)?.takeIf { it.isFile } ?: return null
    return try {
        val lines = file.readText(Charsets.UTF_8).split('\n')
        // Older entries held only a title; treat them as expired so they are fetched again.
        if (lines.size != 4 || lines[0] != CACHE_FORMAT) return null
        val youtube = lines[3].split(',').filter { YOUTUBE_ID.matches(it) }
        Entry(Song(lines[2], youtube), lines[1].toLong())
    } catch (_: Exception) {
        file.delete()
        null
    }
}

private fun writeCache(videoId: String, entry: Entry) {
    val file = cacheFile(videoId) ?: return
    try {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "$videoId.tmp")
        temp.writeText(
            "$CACHE_FORMAT\n${entry.savedAt}\n${entry.song.title}\n${entry.song.youtubeIds.joinToString(",")}",
            Charsets.UTF_8,
        )
        if (!temp.renameTo(file)) temp.delete()
    } catch (_: IOException) {
        // The title still shows for this session from memory.
    }
}
