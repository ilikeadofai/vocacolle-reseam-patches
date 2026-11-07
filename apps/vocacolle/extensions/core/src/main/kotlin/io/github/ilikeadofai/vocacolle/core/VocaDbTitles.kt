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
 * Display titles from VocaDB for the exact Niconico video a player item shows.
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
private const val NOT_FOUND = ""
private val MEDIA_ID = Regex("^(sm|so|nm)[0-9]{1,12}$")

private class Entry(val title: String, val savedAt: Long)

@Volatile private var cacheDir: File? = null

/** videoId -> VocaDB title, or [NOT_FOUND] when VocaDB has no usable title. */
private val memory: MutableMap<String, String> = Collections.synchronizedMap(
    object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 512
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
    val known = memory[videoId]
    if (known != null) return if (known == NOT_FOUND) title else known
    if (inFlight.add(videoId)) {
        try {
            lookups.execute { load(videoId) }
        } catch (_: RuntimeException) {
            inFlight.remove(videoId)
        }
    }
    return title
}

private fun load(videoId: String) {
    try {
        val now = System.currentTimeMillis()
        val cached = readCache(videoId)
        if (cached != null && now - cached.savedAt < ttl(cached.title)) {
            memory[videoId] = cached.title
            return
        }
        val fetched = try {
            fetch(videoId)
        } catch (failure: IOException) {
            // Offline or VocaDB unreachable: an expired entry is still better than nothing, and
            // a network error is never remembered as "not found".
            cached?.let { memory[videoId] = it.title }
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

private fun ttl(title: String) = if (title == NOT_FOUND) NOT_FOUND_TTL_MS else FOUND_TTL_MS

/** VocaDB's English name for the song, else its romanized one, else [NOT_FOUND]. */
private fun fetch(videoId: String): String {
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
        return titleOf(song, videoId)
    } finally {
        connection.disconnect()
    }
}

private fun titleOf(song: JSONObject, videoId: String): String {
    // byPv can answer with a song whose Niconico PV was since disabled or replaced; only an
    // active PV with this exact id counts as a match.
    val pvs = song.optJSONArray("pvs") ?: return NOT_FOUND
    val exact = (0 until pvs.length()).map { pvs.getJSONObject(it) }.any {
        it.optString("service") == "NicoNicoDouga" && it.optString("pvId") == videoId && !it.optBoolean("disabled")
    }
    if (!exact) return NOT_FOUND
    val names = song.optJSONArray("names") ?: return NOT_FOUND
    fun name(language: String) = (0 until names.length()).map { names.getJSONObject(it) }
        .firstOrNull { it.optString("language") == language }
        ?.optString("value")?.trim()?.takeIf { it.isNotEmpty() }
    return name("English") ?: name("Romaji") ?: NOT_FOUND
}

private fun cacheFile(videoId: String) = cacheDir?.let { File(it, videoId) }

private fun readCache(videoId: String): Entry? {
    val file = cacheFile(videoId)?.takeIf { it.isFile } ?: return null
    return try {
        val text = file.readText(Charsets.UTF_8)
        val newline = text.indexOf('\n')
        if (newline < 0) null else Entry(text.substring(newline + 1), text.substring(0, newline).toLong())
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
        temp.writeText("${entry.savedAt}\n${entry.title}", Charsets.UTF_8)
        if (!temp.renameTo(file)) temp.delete()
    } catch (_: IOException) {
        // The title still shows for this session from memory.
    }
}
