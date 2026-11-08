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
private const val BUTTON_WAIT_MS = 2_500L
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

// A full queue refuses new work outright, so a refused track is never left marked as pending.
private val lookups = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue(16), { runnable ->
    Thread(runnable, TAG).apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
}, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }

/** The Niconico title and length of tracks that started playing, for songs VocaDB doesn't know. */
private val trackInfo: MutableMap<String, Pair<String, Int>> = Collections.synchronizedMap(
    object : LinkedHashMap<String, Pair<String, Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Int>>?) = size > 64
    },
)

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

/** `PlayerViewModel`'s lyrics LiveData: shows a lyrics result, built by the patch. */
@Export
external fun showLyrics(target: Any?, lyrics: Any?)

/** The lyrics screen that got an error while its track's lookup was still running. */
@Volatile private var waitingScreen: Pair<String, Any>? = null

private val mainHandler by lazy { android.os.Handler(Looper.getMainLooper()) }

/**
 * The player is about to show an error for its lyrics. When that is because the lookup hadn't
 * answered yet, remember the screen, and show the lyrics there once it does.
 */
@Export
fun lyricsFailed(target: Any?) {
    val videoId = lastRequested ?: return
    if (target == null || !LyricsSettings.externalLyrics.value) return
    waitingScreen = if (memory[videoId] == null && pending.containsKey(videoId)) videoId to target else null
}

private fun deliverLate(videoId: String, found: Found) {
    val (waitingId, target) = waitingScreen ?: return
    if (waitingId != videoId) return
    waitingScreen = null
    mainHandler.post {
        // Only while the screen still shows that track.
        if (lastRequested != videoId) return@post
        try {
            showLyrics(target, build(videoId, found))
            Log.i(TAG, "showing ${found.source} lyrics for $videoId after the lookup finished")
        } catch (failure: Throwable) {
            Log.w(TAG, "couldn't show late lyrics for $videoId", failure)
        }
    }
}

/** Starts looking the track up while it plays, so its lyrics are ready when they are opened. */
@Export
fun prefetch(videoId: String?, title: String?, duration: Long) {
    if (videoId == null || !MEDIA_ID.matches(videoId) || !LyricsSettings.externalLyrics.value) return
    if (!title.isNullOrBlank() && duration > 0) {
        // The watch API gives seconds; anything this long is milliseconds.
        val seconds = if (duration > 100_000) (duration / 1000).toInt() else duration.toInt()
        trackInfo[videoId] = title to seconds
    }
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
    // Still looking: wait a moment for the answer, so a song no source has gets the app's own
    // "no lyrics" message every time, instead of the lyrics screen first and the message later.
    val found = memory[videoId] ?: run {
        val lookup = schedule(videoId)
        try {
            lookup?.get(BUTTON_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // Too slow: let the button open, and the lyrics request wait for the answer.
        }
        memory[videoId] ?: return true
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
                    val found = load(videoId)
                    memory[videoId] = found
                    Log.i(TAG, if (found.lines.isEmpty()) "no lyrics found for $videoId" else "${found.source} has lyrics for $videoId")
                    if (found.lines.isNotEmpty()) deliverLate(videoId, found)
                } catch (failure: Exception) {
                    // A network failure isn't remembered, so the next try can still succeed.
                    Log.d(TAG, "lookup failed for $videoId: ${failure.javaClass.simpleName}")
                } finally {
                    pending.remove(videoId)
                }
            }.also { pending[videoId] = it }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
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
    val song = try {
        vocadbSong(videoId)
    } catch (failure: Exception) {
        Log.d(TAG, "VocaDB failed for $videoId: ${failure.javaClass.simpleName}")
        null
    }
    val track = trackInfo[videoId]
    if (song == null && track == null) return NONE
    // Without a VocaDB entry, the Niconico title stands in: "【…】Title / Producer feat. …".
    val nicoTitle = track?.first.orEmpty().replace(Regex("【[^】]*】|［[^］]*］|\\[[^\\]]*]"), " ").trim()
    // Covers and many uploads name the song in quotes: 『デビットビット』歌ってみた, …「1996」.
    val quoted = QUOTED_NAME.find(track?.first.orEmpty())?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
    val name = song?.let { it.optString("defaultName").ifBlank { it.optString("name") }.trim() }
        ?: quoted?.trim()
        ?: nicoTitle.substringBefore(" / ").substringBefore("／").trim()
    val producer = song?.optString("artistString")?.substringBefore(" feat.")?.substringBefore(",")?.trim()
        ?: nicoTitle.substringAfter(" / ", "").substringAfter("／", "").substringBefore(" feat").trim()
    val length = song?.optInt("lengthSeconds", 0)?.takeIf { it > 0 } ?: track?.second ?: 0
    val order = LyricsSettings.lyricsProviders.value.split(',', ' ')
        .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.ifEmpty { DEFAULT_PROVIDERS }
    for (provider in order) {
        val found = try {
            when (provider) {
                "lrclib" -> if (name.isNotEmpty() && length > 0) lrclib(name, length) else null
                "netease" -> if (name.isNotEmpty() && length > 0) netease(name, producer, length) else null
                "kugou" -> if (name.isNotEmpty() && length > 0) kugou(name, producer, length) else null
                "vocadb" -> song?.let(::vocadbLyrics)
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

private val QUOTED_NAME = Regex("『([^』]+)』|「([^」]+)」")

/** A source's title without what sources add after it: " / artist", "(feat. …)", "【…】". */
private fun baseTitle(value: String) = value
    .substringBefore(" / ").substringBefore("／")
    .replace(Regex("\\s*[(（\\[【][^)）\\]】]*[)）\\]】]\\s*$"), "")
    .substringBefore(" feat.").substringBefore(" ft.")

private fun sameTitle(source: String, wanted: String): Boolean =
    exactTitle(source, wanted) || exactTitle(baseTitle(source), baseTitle(wanted))

private fun exactTitle(a: String, b: String): Boolean {
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
    return Found(lines, true, "NetEase Cloud Music").takeIf { lines.size > 2 && inSongLanguage(name, lines) }
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
    return Found(lines, true, "KuGou").takeIf { lines.size > 2 && inSongLanguage(name, lines) }
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
    // Exact titles first, then the closest length. Entries in another language than the song's,
    // such as an English translation filed under the original title, are skipped.
    val match = (0 until results.length()).map { results.getJSONObject(it) }
        .filter { sameTitle(it.optString("trackName"), name) && !it.optBoolean("instrumental") }
        .filter { it.optString("syncedLyrics").isNotBlank() }
        .filter { Math.abs(it.optDouble("duration", 0.0) - length) <= DURATION_TOLERANCE_S }
        .filter { inSongLanguage(name, parseLrc(it.optString("syncedLyrics"))) }
        .sortedWith(
            compareBy<JSONObject>(
                { if (exactTitle(it.optString("trackName"), name)) 0 else 1 },
                { Math.abs(it.optDouble("duration", 0.0) - length) },
            ),
        )
        .firstOrNull() ?: return null
    val lines = parseLrc(match.optString("syncedLyrics"))
    return Found(lines, true, "LRCLIB").takeIf { lines.isNotEmpty() }
}

/**
 * Whether [lines] are in the song's own language: for a Japanese title, a fair share of the
 * lines must have Japanese in them. Translations are often filed under the original title.
 */
private fun inSongLanguage(name: String, lines: List<Line>): Boolean {
    if (!JAPANESE_TEXT.containsMatchIn(name)) return true
    val texts = lines.map { it.text }.filter { it.isNotBlank() }
    if (texts.isEmpty()) return false
    return texts.count { JAPANESE_TEXT.containsMatchIn(it) } * 10 >= texts.size * 3
}

private val JAPANESE_TEXT = Regex("[\\u3040-\\u30FF\\u3400-\\u9FFF]")

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
        // Short: the user may be looking at the lyrics screen, and other sources can still answer.
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
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

/** How many songs' lyrics this session holds; they live in memory only. */
internal fun cachedLyricsCount(): Int = memory.size

/** Forgets the lyrics found this session, so the next look-up asks the sources again. */
internal fun clearLyricsCache() {
    memory.clear()
}
