@file:JvmName("LyricLayers")

package io.github.ilikeadofai.vocacolle.core.translate

import android.util.Log
import app.reseam.runtime.Export
import io.github.ilikeadofai.vocacolle.settings.AiSettings
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.LinkedBlockingDeque

/*
 * Lyrics with a reading and a translation under each line. The player draws every string of a
 * lyrics segment as its own row, highlighted with the segment, so the layers are extra strings
 * in the segment's list, added where a row is composed: whatever lyrics the player shows, the
 * app's own or another source's, get them, and nothing the app stores or sends changes.
 * The original shows at once; the layers appear as their batch arrives.
 */

private const val MAX_LINE_CHARS = 300
private const val BATCH_LINES = 40
private const val ROMAJI_ENDPOINT = "https://clients5.google.com/translate_a/single"

/** Joins lines for one romanization request; Google keeps it as it is between the readings. */
private const val SEPARATOR = " ◆ "

/** Which layers a line asks for, so a batch fetches only what is missing. */
private class LineJob(val source: String, val config: Config?, val romanize: Boolean)

private val lineQueue = LinkedBlockingDeque<LineJob>(1_000)
private val queued: MutableSet<String> = Collections.synchronizedSet(HashSet())

// Lines already fetched once this session: a line a service can't answer isn't asked again on
// every redraw.
private val attempted: MutableSet<String> = Collections.synchronizedSet(HashSet())

private val lineWorker: Thread by lazy {
    Thread({
        while (true) {
            val batch = mutableListOf(lineQueue.takeFirst())
            while (batch.size < BATCH_LINES) batch += lineQueue.pollFirst() ?: break
            batch.forEach { attempted += it.source }
            try {
                fill(batch)
            } catch (failure: Exception) {
                Log.d(TAG, "lyrics layer batch failed: ${failure.javaClass.simpleName}: ${failure.message}")
            } finally {
                batch.forEach { queued.remove(it.source) }
            }
            bumpRevision()
        }
    }, "$TAG-lyrics").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
}

/** A Compose state the lyric rows read while drawing, so they redraw when layers arrive. */
private val revision: Any? by lazy { newState(0) }

private fun bumpRevision() {
    mainHandler.post {
        val state = revision ?: return@post
        writeState(state, ((readState(state) as? Int) ?: 0) + 1)
    }
}

private fun romajiKey(source: String): String = "romaji-" + cacheKey(ROMAJI_CONFIG, source)

/** Romanization doesn't depend on the target language or the translation service. */
private val ROMAJI_CONFIG = Config(PROVIDER_GOOGLE, ROMAJI_ENDPOINT, "rm", "ja-Latn", "")

/** Reads a layer from memory, else from the disk cache into memory. */
private fun cached(key: String): String? =
    memory[key] ?: cacheFile(key)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.also { memory[key] = it }

/**
 * The strings a lyrics row shows: each line of [lines], followed by its reading and its
 * translation once known. Called while composing the row.
 */
@Export
fun lines(lines: List<String>?): List<String>? {
    if (lines == null) return null
    val romanize = AiSettings.aiLyricsRomaji.value
    val translate = AiSettings.aiLyricsTranslation.value
    if (!romanize && !translate) return lines
    revision?.let { readState(it) }
    val config = if (translate) config() else null
    if (!romanize && config == null) return lines
    val hangul = romanize && when (AiSettings.aiLyricsScript.value) {
        "hangul" -> true
        "romaji" -> false
        else -> targetLanguage() == "ko"
    }
    val shown = ArrayList<String>(lines.size * 3)
    for (line in lines) {
        val source = line.trim()
        if (source.isEmpty() || source.length > MAX_LINE_CHARS || !JAPANESE.containsMatchIn(source)) {
            shown += line
            continue
        }
        val reading = if (romanize) memory[romajiKey(source)] else null
        val translation = config?.let { memory[cacheKey(it, source)] }
        val layers = listOfNotNull(
            reading?.let { if (hangul) romajiToHangul(it, source) else it },
            translation?.takeIf { it != source },
        )
        // Marked for the row: the line keeps its size with a small gap under it, its layers are
        // drawn smaller and close together, and the last one keeps the app's gap to the next line.
        shown += if (layers.isEmpty()) line else MARK_LINE + line
        layers.forEachIndexed { index, layer -> shown += (if (index == layers.lastIndex) MARK_LAST else MARK_LAYER) + layer }
        if ((romanize && reading == null) || (config != null && translation == null)) want(source, config, romanize)
    }
    return shown
}

// Invisible marks at the start of a row's text, read and removed by the row itself.
private const val MARK_LINE = '\u2062'
private const val MARK_LAYER = '\u2063'
private const val MARK_LAST = '\u2064'

/** The gaps under rows, in dp: under a line with layers, between layers, and the app's own. */
private const val GAP_UNDER_LINE = 4f
private const val GAP_BETWEEN_LAYERS = 2f

/** What the row being composed is: 0 a plain line, else its mark. Composition is single-threaded. */
private var rowMark: Char = 0.toChar()

/** The text a lyrics row shows: its mark removed, and remembered for the style and the gap. */
@Export
fun rowText(text: String?): String? {
    val first = text?.firstOrNull()
    rowMark = if (first == MARK_LINE || first == MARK_LAYER || first == MARK_LAST) first else 0.toChar()
    return if (rowMark == 0.toChar()) text else text!!.substring(1)
}

/** The app's smaller text style, from the theme being composed; built by the patch. */
@Export
external fun layerStyle(composer: Any?): Any?

/** The row's style: the app's own for lines, the smaller one for a reading or translation. */
@Export
fun rowStyle(style: Any?, composer: Any?): Any? =
    if (rowMark == MARK_LAYER || rowMark == MARK_LAST) layerStyle(composer) ?: style else style

/** The gap under the row, in dp: the app's [gap], or a smaller one inside a line's group. */
@Export
fun rowGap(gap: Float): Float = when (rowMark) {
    MARK_LINE -> GAP_UNDER_LINE
    MARK_LAYER -> GAP_BETWEEN_LAYERS
    else -> gap
}

/** Fetches the layers of [lines] ahead of time, for lyrics kept for offline use. */
internal fun prefetchLayers(lines: List<String>) {
    val romanize = AiSettings.aiLyricsRomaji.value
    val config = if (AiSettings.aiLyricsTranslation.value) config() else null
    if (!romanize && config == null) return
    for (line in lines) {
        val source = line.trim()
        if (source.isEmpty() || source.length > MAX_LINE_CHARS || !JAPANESE.containsMatchIn(source)) continue
        val reading = if (romanize) cached(romajiKey(source)) else ""
        val translation = config?.let { cached(cacheKey(it, source)) } ?: if (config == null) "" else null
        if (reading == null || translation == null) want(source, config, romanize)
    }
}

private fun want(source: String, config: Config?, romanize: Boolean) {
    if (source in attempted || !queued.add(source)) return
    lineWorker
    val job = LineJob(source, config, romanize)
    // Newest first: the rows on screen now matter more than ones scrolled past.
    while (!lineQueue.offerFirst(job)) lineQueue.pollLast()?.let { queued.remove(it.source) }
}

private fun fill(batch: List<LineJob>) {
    val readings = batch.filter { it.romanize }.map { it.source }.filter { cached(romajiKey(it)) == null }
    if (readings.isNotEmpty()) romanize(readings.distinct())
    val translations = batch.mapNotNull { job -> job.config?.let { job.source to it } }
        .filter { (source, config) -> cached(cacheKey(config, source)) == null }
    if (translations.isNotEmpty()) {
        val config = translations.first().second
        translate(config, translations.filter { it.second.language == config.language }.map { it.first }.distinct())
    }
}

/** Google's romanization of [sources], several per request, matched back by the separator. */
private fun romanize(sources: List<String>) {
    var chunk = ArrayList<String>()
    var length = 0
    fun flush() {
        if (chunk.isEmpty()) return
        val parts = chunk.filter { '◆' !in it }
        // A line holding the separator itself goes alone.
        (chunk - parts.toSet()).forEach { romanizeJoined(listOf(it)) }
        if (parts.isNotEmpty() && !romanizeJoined(parts) && parts.size > 1) parts.forEach { romanizeJoined(listOf(it)) }
        chunk = ArrayList()
        length = 0
    }
    for (source in sources) {
        val cost = URLEncoder.encode(source + SEPARATOR, "UTF-8").length
        if (length + cost > MAX_BATCH_QUERY_CHARS) flush()
        chunk += source
        length += cost
    }
    flush()
}

/** Whether the readings of [sources] came back one per line. */
private fun romanizeJoined(sources: List<String>): Boolean {
    val q = URLEncoder.encode(sources.joinToString(SEPARATOR), "UTF-8")
    val reply = try {
        JSONArray(get("$ROMAJI_ENDPOINT?client=dict-chrome-ex&sl=ja&tl=en&dt=rm&q=$q"))
    } catch (failure: Exception) {
        Log.d(TAG, "romanization failed: ${failure.javaClass.simpleName}")
        return false
    }
    val sentences = reply.optJSONArray(0) ?: return false
    val reading = (0 until sentences.length())
        .mapNotNull { sentences.optJSONArray(it)?.takeIf { s -> s.length() > 3 }?.optString(3) }
        .firstOrNull { it.isNotBlank() && it != "null" } ?: return false
    val parts = if (sources.size == 1) listOf(reading) else reading.split('◆').map { it.trim() }
    if (parts.size != sources.size) return false
    sources.zip(parts).forEach { (source, part) ->
        val clean = part.trim()
        if (clean.isNotEmpty() && clean.length <= source.length * 6 + 20) store(romajiKey(source), clean)
    }
    return true
}

private fun translate(config: Config, sources: List<String>) {
    val keys = sources.associateWith { cacheKey(config, it) }
    if (config.provider == PROVIDER_GOOGLE) {
        val result = requestGoogleBatch(sources.map { TitleJob(config, keys.getValue(it), it) }, Kind.LYRICS)
        result.forEach { (key, text) -> store(key, text) }
        return
    }
    // One chat request for the whole batch, kept only when every line comes back.
    val joined = sources.joinToString("\n")
    val reply = try {
        requestOpenAi(config, joined, Kind.LYRICS)
    } catch (failure: Exception) {
        Log.d(TAG, "lyrics translation failed: ${failure.javaClass.simpleName}: ${failure.message}")
        null
    } ?: return
    val lines = reply.split('\n')
    if (lines.size != sources.size) return
    sources.zip(lines).forEach { (source, line) -> line.trim().takeIf { it.isNotEmpty() }?.let { store(keys.getValue(source), it) } }
}
