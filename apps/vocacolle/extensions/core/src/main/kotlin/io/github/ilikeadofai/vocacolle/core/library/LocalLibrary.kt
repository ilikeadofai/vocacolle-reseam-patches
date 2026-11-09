@file:JvmName("LocalLibrary")

package io.github.ilikeadofai.vocacolle.core.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import app.reseam.runtime.Export
import app.reseam.runtime.Reseam
import io.github.ilikeadofai.vocacolle.settings.LibrarySettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/*
 * The rest of the on-device library: a watchlist of creators, saved mylists, and the fallback
 * that keeps what the account's server-side limits refused. Nothing here changes the account:
 * when the server refuses a like, a mylist item, or a follow, the app still shows its own error,
 * and the item is only kept on the device.
 */

private const val TAG = "VocaColleLibrary"
private const val BUNDLE = "vocacolle-reseam-patches"
private const val PREFS = "vocacolle_reseam_library"
private const val KEY_CREATORS = "creators"
private const val KEY_MYLISTS = "mylists"
private const val FEED_PER_CREATOR = 10
private const val FEED_MAX_CREATORS = 30
private const val MAX_BODY_BYTES = 512 * 1024
private val VIDEO_ID = Regex("^[a-z]{2}\\d{1,12}$")
private val USER_OWNER = Regex("^User\\(userId=(\\d{1,12})\\)$")

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

// Names seen in the app ---------------------------------------------------------------------

private fun <V> lru(max: Int): MutableMap<String, V> = Collections.synchronizedMap(
    object : LinkedHashMap<String, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
    },
)

private val titles: MutableMap<String, String> = lru(2_000)
private val creatorNames: MutableMap<String, String> = lru(1_000)
private val mylistNames: MutableMap<String, String> = lru(500)

/** A video's title, from the player or a work's detail screen. */
@Export
fun rememberTitle(videoId: String?, title: String?) {
    if (videoId != null && !title.isNullOrBlank()) titles[videoId] = title
}

/** A mylist's name, from the app's mylist models. */
@Export
fun rememberMylist(mylistId: Long, name: String?) {
    if (!name.isNullOrBlank()) mylistNames[mylistId.toString()] = name
}

/** The user id of a work's owner, `User(userId=…)`, or null for a channel. */
internal fun ownerUserId(owner: Any?): String? = USER_OWNER.matchEntire(owner?.toString().orEmpty())?.groupValues?.get(1)

internal fun rememberCreator(userId: String, name: String?) {
    if (!name.isNullOrBlank()) creatorNames[userId] = name
}

internal fun titleOf(videoId: String): String = titles[videoId] ?: videoId

// Storage -----------------------------------------------------------------------------------

/** A creator or a mylist: an id and the name it was saved with. */
internal class Entry(val id: String, val name: String, val addedAt: Long)

private fun prefs(context: Context?) = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

internal fun loadEntries(context: Context?, key: String): List<Entry> = try {
    val array = JSONArray(prefs(context)?.getString(key, null) ?: "[]")
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").takeIf { it.matches(Regex("^\\d{1,12}$")) } ?: return@mapNotNull null
        Entry(id, item.optString("name").ifBlank { id }, item.optLong("addedAt"))
    }.distinctBy { it.id }
} catch (_: Exception) {
    emptyList()
}

internal fun saveEntries(context: Context?, key: String, entries: List<Entry>) {
    val array = JSONArray()
    entries.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("addedAt", it.addedAt)) }
    prefs(context)?.edit()?.putString(key, array.toString())?.apply()
}

internal fun creators(context: Context?) = loadEntries(context, KEY_CREATORS)

internal fun savedMylists(context: Context?) = loadEntries(context, KEY_MYLISTS)

/** Adds the entry first; whether it was new. */
private fun addEntry(context: Context?, key: String, id: String, name: String): Boolean {
    val entries = loadEntries(context, key)
    if (entries.any { it.id == id }) return false
    saveEntries(context, key, listOf(Entry(id, name, System.currentTimeMillis())) + entries)
    return true
}

internal fun watching(context: Context?, userId: String) = creators(context).any { it.id == userId }

/** Starts or stops watching the creator; whether they are watched now. */
internal fun toggleCreator(context: Context?, userId: String, name: String): Boolean {
    val entries = creators(context)
    return if (entries.any { it.id == userId }) {
        saveEntries(context, KEY_CREATORS, entries.filter { it.id != userId })
        false
    } else {
        addEntry(context, KEY_CREATORS, userId, name)
        true
    }
}

// Limits ------------------------------------------------------------------------------------

/** What each request was about, per thread: the request and its error run on the same one. */
private val attempts = ThreadLocal<Array<String?>>()

@Export
fun attemptLike(videoId: String?) {
    attempts.set(arrayOf("like", videoId, null))
}

@Export
fun attemptMylistItem(mylistId: Long, itemId: String?) {
    attempts.set(arrayOf("mylist", itemId, mylistId.toString()))
}

@Export
fun attemptFollowMylist(mylistId: Long) {
    attempts.set(arrayOf("followMylist", mylistId.toString(), null))
}

@Export
fun attemptFollowUser(userId: Long) {
    attempts.set(arrayOf("followUser", userId.toString(), null))
}

/** The server's error code of an API exception the app threw, by the patch, which knows the classes. */
@Export
external fun errorCode(error: Any?): String?

private val LIKE_LIMITS = setOf("DAILY_LIMIT_EXCEEDED")
private val MYLIST_LIMITS = setOf(
    "REACHED_LIMIT_BY_MYLIST", "REACHED_LIMIT_BY_USER", "LIMIT_EXCEEDED", "USER_LIMIT_EXCEEDED", "REGULAR_USER_LIMIT_EXCEEDED",
)
private val FOLLOW_MYLIST_LIMITS = setOf(
    "FOLLOWING_LIMIT_EXCEEDED", "LIMIT_EXCEEDED", "REACHED_LIMIT_BY_USER", "USER_LIMIT_EXCEEDED", "REGULAR_USER_LIMIT_EXCEEDED",
)
private val FOLLOW_USER_LIMITS = setOf("LIMIT_EXCEEDED")

/**
 * The request on this thread is about to throw [error]. When that is the account's limit, the
 * item is kept on the device instead, and a toast says so; the app's own error still shows.
 */
@Export
fun failed(error: Any?) {
    val attempt = attempts.get() ?: return
    attempts.remove()
    if (!LibrarySettings.saveOnLimit.value) return
    val context = libraryContext() ?: return
    val code = try {
        errorCode(error)
    } catch (_: Throwable) {
        null
    } ?: return
    val (kind, id, extra) = attempt
    if (id == null) return
    val message = when (kind) {
        "like" -> if (code in LIKE_LIMITS && VIDEO_ID.matches(id)) {
            addToList(null, id, titleOf(id))
            VocaColleStrings.limitSavedFavorite
        } else {
            null
        }
        "mylist" -> if (code in MYLIST_LIMITS && VIDEO_ID.matches(id)) {
            val name = mylistNames[extra] ?: String.format(VocaColleStrings.limitMylistName, extra)
            addToList(name, id, titleOf(id))
            String.format(VocaColleStrings.limitSavedPlaylist, name)
        } else {
            null
        }
        "followMylist" -> if (code in FOLLOW_MYLIST_LIMITS) {
            addEntry(context, KEY_MYLISTS, id, mylistNames[id] ?: String.format(VocaColleStrings.limitMylistName, id))
            VocaColleStrings.limitSavedMylist
        } else {
            null
        }
        "followUser" -> if (code in FOLLOW_USER_LIMITS) {
            addEntry(context, KEY_CREATORS, id, creatorNames[id] ?: String.format(VocaColleStrings.creatorUnknown, id))
            VocaColleStrings.limitSavedCreator
        } else {
            null
        }
        else -> null
    } ?: return
    Log.i(TAG, "$kind $id refused with $code; kept on the device")
    mainHandler.post {
        changedLibrary()
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

// Screens -----------------------------------------------------------------------------------

private fun openUri(activity: Activity, uri: Uri) {
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(activity.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (failure: Exception) {
        Log.w(TAG, "couldn't open $uri: ${failure.javaClass.simpleName}")
        Toast.makeText(activity, VocaColleStrings.favoritesOpenFailed, Toast.LENGTH_LONG).show()
    }
}

internal fun openCreators(activity: Activity) {
    val list = creators(activity)
    val ui = Reseam.bundle(BUNDLE).ui(activity)
    val dialog = ui.dialog().title("${VocaColleStrings.creatorsTitle} (${list.size})")
    if (list.isEmpty()) {
        dialog.message(VocaColleStrings.creatorsEmpty)
    } else {
        dialog.choices(list.map { it.name }.toTypedArray(), -1) { index ->
            val creator = list[index]
            ui.dialog()
                .title(creator.name)
                .action(VocaColleStrings.remove) {
                    saveEntries(activity, KEY_CREATORS, creators(activity).filter { it.id != creator.id })
                    openCreators(activity)
                }
                .action(VocaColleStrings.creatorOpen) {
                    openUri(activity, Uri.parse("nicobox://user?uid=${creator.id}"))
                }
                .show()
        }
        dialog.action(VocaColleStrings.creatorsFeed) { playFeed(activity, list) }
    }
    dialog.show()
}

internal fun openSavedMylists(activity: Activity) {
    val list = savedMylists(activity)
    val ui = Reseam.bundle(BUNDLE).ui(activity)
    val dialog = ui.dialog().title("${VocaColleStrings.savedMylistsTitle} (${list.size})")
    if (list.isEmpty()) {
        dialog.message(VocaColleStrings.savedMylistsEmpty)
    } else {
        dialog.choices(list.map { it.name }.toTypedArray(), -1) { index ->
            val mylist = list[index]
            ui.dialog()
                .title(mylist.name)
                .action(VocaColleStrings.remove) {
                    saveEntries(activity, KEY_MYLISTS, savedMylists(activity).filter { it.id != mylist.id })
                    openSavedMylists(activity)
                }
                .action(VocaColleStrings.savedMylistOpen) {
                    openUri(activity, Uri.parse("nicobox://mylistDetail?mylistId=${mylist.id}"))
                }
                .show()
        }
    }
    dialog.show()
}

// The creators' latest uploads --------------------------------------------------------------

private class Upload(val id: String, val title: String, val registeredAt: String)

private fun get(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        // The public web client's id, as nicovideo.jp's own pages send it.
        connection.setRequestProperty("X-Frontend-Id", "6")
        connection.setRequestProperty("X-Frontend-Version", "0")
        val status = connection.responseCode
        if (status !in 200..299) throw IOException("HTTP $status")
        return connection.inputStream.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > MAX_BODY_BYTES) throw IOException("response too large")
            String(bytes, Charsets.UTF_8)
        }
    } finally {
        connection.disconnect()
    }
}

/** A creator's latest uploads, newest first. */
private fun uploads(userId: String): List<Upload> {
    val reply = JSONObject(
        get("https://nvapi.nicovideo.jp/v3/users/$userId/videos?sortKey=registeredAt&sortOrder=desc&pageSize=$FEED_PER_CREATOR&page=1"),
    )
    val items = reply.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
    return (0 until items.length()).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val video = item.optJSONObject("essential") ?: item.optJSONObject("content") ?: item
        val id = video.optString("id").ifBlank { item.optString("watchId") }
        if (!VIDEO_ID.matches(id)) return@mapNotNull null
        Upload(id, video.optString("title").ifBlank { id }, video.optString("registeredAt"))
    }
}

private fun playFeed(activity: Activity, list: List<Entry>) {
    Toast.makeText(activity, VocaColleStrings.creatorsFeedLoading, Toast.LENGTH_SHORT).show()
    Thread({
        var failures = 0
        val all = list.take(FEED_MAX_CREATORS).flatMap { creator ->
            try {
                uploads(creator.id)
            } catch (failure: Exception) {
                failures++
                Log.d(TAG, "uploads of ${creator.id} failed: ${failure.javaClass.simpleName}")
                emptyList()
            }
        }
        // nicovideo.jp gives every time in +09:00, so the strings sort as the times do.
        val newest = all.distinctBy { it.id }.sortedByDescending { it.registeredAt }
        newest.forEach { rememberTitle(it.id, it.title) }
        activity.runOnUiThread {
            if (newest.isEmpty()) {
                Toast.makeText(activity, VocaColleStrings.creatorsFeedFailed, Toast.LENGTH_LONG).show()
            } else {
                playSongs(activity, VocaColleStrings.creatorsFeed, newest.map { it.id to it.title })
            }
        }
        if (failures > 0) Log.i(TAG, "feed: $failures of ${list.size} creators failed")
    }, "$TAG-feed").apply { isDaemon = true }.start()
}

/** The playlist chooser's row for watching the work's creator. */
internal fun creatorChoiceLabel(context: Context?, userId: String, name: String): String =
    (if (watching(context, userId)) "✓ " else "") + String.format(VocaColleStrings.creatorWatch, name)
