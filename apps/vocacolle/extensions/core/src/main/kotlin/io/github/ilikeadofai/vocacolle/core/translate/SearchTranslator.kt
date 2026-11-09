@file:JvmName("SearchTranslator")

package io.github.ilikeadofai.vocacolle.core.translate

import android.os.Looper
import android.util.Log
import android.widget.Toast
import app.reseam.runtime.Export
import io.github.ilikeadofai.vocacolle.settings.AiSettings
import io.github.ilikeadofai.vocacolle.core.AppStrings
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Collections

/*
 * Korean and English searches, turned into the Japanese Niconico indexes, only in the request:
 * the search box and the search history keep what was typed.
 *
 * 1. VocaDB, for song, producer, and singer names: it lists English, romanized, and often Korean
 *    names beside the Japanese one. Only an exact name match counts, songs before artists.
 * 2. Google Translate, for Korean, and for English phrases of two or more words.
 * 3. A single English word VocaDB doesn't know (remix, MMD, a tag) is searched as typed.
 */

private const val SEARCH_TAG = "VocaColleSearch"
private const val USER_AGENT = "vocacolle-reseam-patches (https://github.com/ilikeadofai/vocacolle-reseam-patches)"
private const val MAX_QUERY_CHARS = 100
private const val MAX_REPLY_BYTES = 256 * 1024
private val HANGUL = Regex("[\\uAC00-\\uD7A3\\u3131-\\u318E]")
private val LATIN = Regex("[A-Za-z]")

/** What each typed query became; the query itself when it stays as typed. */
private val resolved: MutableMap<String, String> = Collections.synchronizedMap(
    object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    },
)

/** The last query a toast was shown for, so paging through results doesn't repeat it. */
@Volatile private var announced: String? = null

private fun vocadbGet(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("HTTP $status")
        return connection.inputStream.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_REPLY_BYTES) throw IOException("response too large")
            String(bytes, Charsets.UTF_8)
        }
    } finally {
        connection.disconnect()
    }
}

/** Case, width, spacing, and punctuation folded, so "rolling girl" equals "Rolling Girl". */
private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().replace(Regex("[\\s\\p{P}\\p{S}]+"), "")

/** The Japanese name of the VocaDB song or artist one of whose names is exactly [query]. */
private fun vocadbName(kind: String, query: String): String? {
    val q = URLEncoder.encode(query, "UTF-8")
    val sort = if (kind == "songs") "RatingScore" else "FollowerCount"
    val reply = JSONObject(
        vocadbGet("https://vocadb.net/api/$kind?query=$q&nameMatchMode=Auto&maxResults=10&fields=Names&lang=Japanese&sort=$sort"),
    )
    val wanted = fold(query)
    val items = reply.optJSONArray("items") ?: return null
    for (index in 0 until items.length()) {
        val item = items.optJSONObject(index) ?: continue
        val names = item.optJSONArray("names") ?: JSONArray()
        val matches = (0 until names.length()).any { fold(names.optJSONObject(it)?.optString("value").orEmpty()) == wanted }
        // A Japanese name that is the query itself (ReMIX for "remix") changes nothing.
        if (matches) return item.optString("name").takeIf { it.isNotBlank() && fold(it) != wanted }
    }
    return null
}

private fun googleJapanese(query: String): String? {
    val q = URLEncoder.encode(query, "UTF-8")
    val reply = JSONArray(get("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=ja&q=$q"))
    val first = reply.opt(0)
    val text = (if (first is JSONArray) first.optString(0) else first?.toString()).orEmpty().trim()
    return text.takeIf { it.isNotEmpty() && it != query && JAPANESE.containsMatchIn(it) && it.length <= query.length * 4 + 20 }
}

private fun resolve(query: String): String {
    val vocadb = try {
        vocadbName("songs", query) ?: vocadbName("artists", query)
    } catch (failure: Exception) {
        Log.d(SEARCH_TAG, "VocaDB failed: ${failure.javaClass.simpleName}")
        null
    }
    if (vocadb != null) return vocadb
    val korean = HANGUL.containsMatchIn(query)
    val phrase = query.trim().split(Regex("\\s+")).size >= 2
    if (!korean && !phrase) return query
    return try {
        googleJapanese(query) ?: query
    } catch (failure: Exception) {
        Log.d(SEARCH_TAG, "Google failed: ${failure.javaClass.simpleName}")
        query
    }
}

/**
 * The keyword the app is about to send to a Niconico search: in Japanese when it was typed in
 * Korean or English and a match is found, else as it is. Called on the request's own thread.
 */
@Export
fun searchKeyword(keyword: String?): String? {
    if (keyword == null || !AiSettings.aiSearch.value) return keyword
    val query = keyword.trim()
    if (query.isEmpty() || query.length > MAX_QUERY_CHARS || JAPANESE.containsMatchIn(query)) return keyword
    if (!HANGUL.containsMatchIn(query) && !LATIN.containsMatchIn(query)) return keyword
    val japanese = resolved[query] ?: run {
        // The request runs off the main thread; there, never wait on the network.
        if (Looper.myLooper() == Looper.getMainLooper()) return keyword
        resolve(query).also { resolved[query] = it }
    }
    if (japanese == query) return keyword
    Log.i(SEARCH_TAG, "searching \"$japanese\" for \"$query\"")
    if (announced != query) {
        announced = query
        val context = translationContext() ?: return japanese
        mainHandler.post {
            Toast.makeText(context, String.format(AppStrings.searchTranslated, japanese), Toast.LENGTH_SHORT).show()
        }
    }
    return japanese
}
