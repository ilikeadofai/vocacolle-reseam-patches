@file:JvmName("ExternalLyrics")

package io.github.ilikeadofai.vocacolle.core.lyrics

import android.os.Looper
import android.util.Log
import app.reseam.runtime.Export
import io.github.ilikeadofai.vocacolle.settings.LyricsSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Lyrics from public sources for when VocaColle has none to show: the song is matched by the
 * exact Niconico video id on VocaDB, then LRCLIB is asked for synced lyrics of that song whose
 * length matches, and VocaDB's own original lyrics are used, without timing, when it has none.
 *
 * Only used when the app's lyrics request ends in "not found" or the monthly limit; the official
 * lyrics, and the limit itself, are left as they are.
 */

private const val TAG = "VocaColleLyrics"
private const val USER_AGENT = "vocacolle-reseam-patches (https://github.com/ilikeadofai/vocacolle-reseam-patches)"
private const val MAX_BODY_BYTES = 512 * 1024
private const val WAIT_MS = 6_000L
private const val DURATION_TOLERANCE_S = 3.0
private val MEDIA_ID = Regex("^(sm|so|nm)[0-9]{1,12}$")
private val LRC_LINE = Regex("^\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]\\s?(.*)$")
// Every way the official request can fail, except having no connection, which the sources share.
private val NO_FALLBACK_REASONS = setOf("OFFLINE", "INVALID_PARAMETER")
private const val MAIN_THREAD_WAIT_MS = 3_000L
private val DEFAULT_PROVIDERS = listOf("lrclib", "netease", "kugou", "vocadb")

/** One line, with its start time when the lyrics are synced. */
private class Line(val startMs: Int?, val text: String)

private class Found(val lines: List<Line>, val timed: Boolean, val source: String)

private val NONE = Found(emptyList(), false, "")

private val memory: MutableMap<String, Found> = Collections.synchronizedMap(
    object : LinkedHashMap<String, Found>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Found>?) = size > 64
    },
)
private val pending: MutableMap<String, Future<*>> = Collections.synchronizedMap(HashMap())

private val lookups = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue(8), { runnable ->
    Thread(runnable, TAG).apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
}, ThreadPoolExecutor.DiscardPolicy()).apply { allowCoreThreadTimeOut(true) }

/** `LyricsSegment(lines, startMs, endMs)`, built by the patch, which knows the app's class. */
@Export
external fun newSegment(lines: List<String>, startMs: Int?, endMs: Int?): Any?

/** `LyricsWithMetadata(videoId, segments, hasTimeInformation)`, built by the patch. */
@Export
external fun newLyrics(videoId: String, segments: List<Any?>, timed: Boolean): Any?

// The loader is a suspend function: when it resumes after the network call, the video id it was
// called with is no longer a parameter. The last one it was called with stands in.
@Volatile private var lastRequested: String? = null

/** The app asked for [videoId]'s lyrics; looks it up alongside, in case they are missing. */
@Export
fun requested(videoId: String?) {
    if (videoId == null || !MEDIA_ID.matches(videoId)) return
    lastRequested = videoId
    if (LyricsSettings.externalLyrics.value) schedule(videoId)
}

/** Starts looking the track up while it plays, so its lyrics are ready when they are opened. */
@Export
fun prefetch(videoId: String?) {
    if (videoId == null || !MEDIA_ID.matches(videoId) || !LyricsSettings.externalLyrics.value) return
    schedule(videoId)
}

/**
 * Lyrics to show instead of the app's error [reason], or null to let the error through. Off the
 * main thread a lookup that is still running is waited for, a few seconds at most.
 */
@Export
fun fallback(requestedId: String?, reason: String?): Any? {
    val videoId = requestedId ?: lastRequested
    if (videoId == null || reason == null || reason in NO_FALLBACK_REASONS || !MEDIA_ID.matches(videoId)) return null
    if (!LyricsSettings.externalLyrics.value) return null
    val found = memory[videoId] ?: run {
        val future = schedule(videoId)
        if (future != null) {
            // The loader may run on the main thread; the lookup itself never does, and the wait
            // there is short, since the user is looking at a spinner either way.
            val wait = if (Looper.myLooper() == Looper.getMainLooper()) MAIN_THREAD_WAIT_MS else WAIT_MS
            try {
                future.get(wait, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                // Too slow or failed: the app shows its own message.
            }
        }
        memory[videoId]
    } ?: return null
    if (found.lines.isEmpty()) {
        Log.i(TAG, "no external lyrics for $videoId (app said $reason)")
        return null
    }
    Log.i(TAG, "showing ${found.source} lyrics for $videoId (app said $reason)")
    return build(videoId, found)
}

/**
 * Whether the lyrics button should open: the app's own answer, or yes once a source has lyrics
 * for the track. The lookup started when the track did, so it is usually done by then.
 */
@Export
fun hasLyrics(videoId: String?, original: Boolean): Boolean {
    if (original || videoId == null || !LyricsSettings.externalLyrics.value || !MEDIA_ID.matches(videoId)) return original
    val found = memory[videoId] ?: run {
        schedule(videoId)
        return false
    }
    return found.lines.isNotEmpty()
}

private fun schedule(videoId: String): Future<*>? {
    if (memory.containsKey(videoId)) return null
    synchronized(pending) {
        pending[videoId]?.let { return it }
        return try {
            lookups.submit {
                try {
                    memory[videoId] = load(videoId)
                } catch (failure: Exception) {
                    // A network failure isn't remembered, so the next try can still succeed.
                    Log.d(TAG, "lookup failed for $videoId: ${failure.javaClass.simpleName}")
                } finally {
                    pending.remove(videoId)
                }
            }.also { pending[videoId] = it }
        } catch (_: RuntimeException) {
            null
        }
    }
}

private fun build(videoId: String, found: Found): Any? {
    val credit = "♪ ${found.source}"
    val segments = ArrayList<Any?>()
    if (!found.timed) {
        segments += newSegment(found.lines.map { it.text } + listOf("", credit), null, null)
        return newLyrics(videoId, segments, false)
    }
    val first = found.lines.first().startMs ?: 0
    if (first > 0) segments += newSegment(listOf(credit), 0, first)
    found.lines.forEachIndexed { index, line ->
        val start = line.startMs ?: return@forEachIndexed
        val end = found.lines.getOrNull(index + 1)?.startMs
        segments += newSegment(listOf(line.text), start, end)
    }
    return newLyrics(videoId, segments, true)
}

private fun load(videoId: String): Found {
    val song = vocadbSong(videoId) ?: return NONE
    val name = song.optString("defaultName").ifBlank { song.optString("name") }.trim()
    val producer = song.optString("artistString").substringBefore(" feat.").substringBefore(",").trim()
    val length = song.optInt("lengthSeconds", 0)
    val order = LyricsSettings.lyricsProviders.value.split(',', ' ')
        .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.ifEmpty { DEFAULT_PROVIDERS }
    for (provider in order) {
        val found = try {
            when (provider) {
                "lrclib" -> if (name.isNotEmpty() && length > 0) lrclib(name, length) else null
                "netease" -> if (name.isNotEmpty() && length > 0) netease(name, producer, length) else null
                "kugou" -> if (name.isNotEmpty() && length > 0) kugou(name, producer, length) else null
                "vocadb" -> vocadbLyrics(song)
                else -> null
            }
        } catch (failure: Exception) {
            Log.d(TAG, "$provider failed for $videoId: ${failure.javaClass.simpleName}")
            null
        }
        if (found != null && found.lines.isNotEmpty()) return found
    }
    return NONE
}

private fun sameTitle(a: String, b: String): Boolean {
    fun normal(value: String) = value.lowercase().replace(Regex("[\\s\u3000・\\-_~～!！?？「」『』()（）\\[\\]]"), "")
    return normal(a) == normal(b)
}

/** Timed lines of an LRC file, without its tags and credit lines. */
private fun parseLrc(text: String): List<Line> = text.lines().mapNotNull { raw ->
    val parts = LRC_LINE.find(raw.trim()) ?: return@mapNotNull null
    val (minutes, seconds, fraction, line) = parts.destructured
    val ms = when (fraction.length) {
        0 -> 0
        1 -> fraction.toInt() * 100
        2 -> fraction.toInt() * 10
        else -> fraction.take(3).toInt()
    }
    Line((minutes.toInt() * 60 + seconds.toInt()) * 1000 + ms, line.trim())
}.filterNot { CREDIT_LINE.containsMatchIn(it.text) }.sortedBy { it.startMs }

private val CREDIT_LINE = Regex("^(作词|作詞|作曲|编曲|編曲|词|曲|ti|ar|al)\\s*[:：]")

/** NetEase Cloud Music: search by title and producer, then its LRC for the one of the same length. */
private fun netease(name: String, producer: String, length: Int): Found? {
    val query = URLEncoder.encode("$name $producer".trim(), "UTF-8")
    val songs = JSONObject(get("https://music.163.com/api/search/get?s=$query&type=1&limit=10", NETEASE_HEADERS))
        .optJSONObject("result")?.optJSONArray("songs") ?: return null
    val match = (0 until songs.length()).map { songs.getJSONObject(it) }
        .filter { sameTitle(it.optString("name"), name) }
        .minByOrNull { Math.abs(it.optLong("duration") / 1000.0 - length) }
        ?.takeIf { Math.abs(it.optLong("duration") / 1000.0 - length) <= DURATION_TOLERANCE_S }
        ?: return null
    val lyric = JSONObject(get("https://music.163.com/api/song/lyric?id=${match.optLong("id")}&lv=1&tv=-1", NETEASE_HEADERS))
        .optJSONObject("lrc")?.optString("lyric").orEmpty()
    val lines = parseLrc(lyric)
    return Found(lines, true, "NetEase Cloud Music").takeIf { lines.size > 2 }
}

private val NETEASE_HEADERS = mapOf("Referer" to "https://music.163.com")

/** KuGou: its lyric search matches by keyword and length, then the LRC comes base64-encoded. */
private fun kugou(name: String, producer: String, length: Int): Found? {
    val query = URLEncoder.encode("$producer $name".trim(), "UTF-8")
    val candidates = JSONObject(
        get("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$query&duration=${length * 1000}&hash="),
    ).optJSONArray("candidates") ?: return null
    val match = (0 until candidates.length()).map { candidates.getJSONObject(it) }
        .filter { sameTitle(it.optString("song"), name) }
        .minByOrNull { Math.abs(it.optLong("duration") / 1000.0 - length) }
        ?.takeIf { Math.abs(it.optLong("duration") / 1000.0 - length) <= DURATION_TOLERANCE_S }
        ?: return null
    val download = JSONObject(
        get(
            "https://lyrics.kugou.com/download?ver=1&client=pc&id=${match.optString("id")}" +
                "&accesskey=${match.optString("accesskey")}&fmt=lrc&charset=utf8",
        ),
    )
    val content = download.optString("content").takeIf { it.isNotEmpty() } ?: return null
    val text = String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), Charsets.UTF_8)
    // KuGou's first line repeats the title and singer.
    val lines = parseLrc(text).filterNot { it.startMs == 0 && it.text.contains(" - ") }
    return Found(lines, true, "KuGou").takeIf { lines.size > 2 }
}

private fun vocadbSong(videoId: String): JSONObject? {
    val body = get(
        "https://vocadb.net/api/songs/byPv?pvService=NicoNicoDouga&pvId=" + URLEncoder.encode(videoId, "UTF-8") +
            "&fields=Lyrics,PVs",
    )
    if (body == "null") return null
    val song = JSONObject(body)
    val pvs = song.optJSONArray("pvs") ?: return null
    val exact = (0 until pvs.length()).map { pvs.getJSONObject(it) }.any {
        it.optString("service") == "NicoNicoDouga" && it.optString("pvId") == videoId && !it.optBoolean("disabled")
    }
    return song.takeIf { exact }
}

/** LRCLIB's synced lyrics for [name] whose recording is within a few seconds of [length]. */
private fun lrclib(name: String, length: Int): Found? {
    val results = JSONArray(get("https://lrclib.net/api/search?track_name=" + URLEncoder.encode(name, "UTF-8")))
    val match = (0 until results.length()).map { results.getJSONObject(it) }
        .filter { sameTitle(it.optString("trackName"), name) && !it.optBoolean("instrumental") }
        .filter { it.optString("syncedLyrics").isNotBlank() }
        .minByOrNull { Math.abs(it.optDouble("duration", 0.0) - length) }
        ?.takeIf { Math.abs(it.optDouble("duration", 0.0) - length) <= DURATION_TOLERANCE_S }
        ?: return null
    val lines = parseLrc(match.optString("syncedLyrics"))
    return Found(lines, true, "LRCLIB").takeIf { lines.isNotEmpty() }
}

/** VocaDB's original-language lyrics, as plain lines. */
private fun vocadbLyrics(song: JSONObject): Found? {
    val lyrics = song.optJSONArray("lyrics") ?: return null
    val original = (0 until lyrics.length()).map { lyrics.getJSONObject(it) }
        .firstOrNull { it.optString("translationType") == "Original" && it.optString("value").isNotBlank() }
        ?: return null
    val lines = original.optString("value").replace("\r\n", "\n").lines().map { Line(null, it.trimEnd()) }
    val source = original.optString("source").takeIf { it.isNotBlank() }?.let { "VocaDB ($it)" } ?: "VocaDB"
    return Found(lines.dropLastWhile { it.text.isEmpty() }, false, source)
}

private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("HTTP $status")
        return connection.inputStream.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_BODY_BYTES) throw IOException("response too large")
            String(bytes, Charsets.UTF_8).trim()
        }
    } finally {
        connection.disconnect()
    }
}
