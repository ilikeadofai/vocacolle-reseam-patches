@file:JvmName("LocalFavorites")

package io.github.ilikeadofai.vocacolle.core.library

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import app.reseam.runtime.Export
import app.reseam.runtime.Reseam
import io.github.ilikeadofai.vocacolle.core.AppStrings
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/*
 * Favorites and playlists kept on the device only: no like, mylist, or follow is sent, so none
 * of the account's server-side limits apply, and the real Niconico state is never touched. A
 * list plays through VocaColle's own playlist deep link, `nicobox://playlist?vid=…`.
 */

private const val TAG = "VocaColleLibrary"
private const val BUNDLE = "vocacolle-reseam-patches"
private const val PREFS = "vocacolle_reseam_library"
private const val KEY_FAVORITES = "favorites"
private const val KEY_PLAYLISTS = "playlists"
private const val EXPORT_FORMAT = "vocacolle-reseam-favorites"
private const val MAX_NAME_CHARS = 60
private val VIDEO_ID = Regex("^[a-z]{2}\\d{1,12}$")

private class Song(val id: String, val title: String, val addedAt: Long)

/** A named playlist; [name] is null for the favorites. */
private class Playlist(val name: String?, val songs: List<Song>) {
    val label: String get() = name ?: AppStrings.favoritesTitle
}

@Volatile private var appContext: Context? = null
@Volatile private var currentActivity: WeakReference<Activity>? = null

@Export
fun init(context: Context?) {
    val application = context?.applicationContext as? Application ?: return
    if (appContext != null) return
    appContext = application
    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            currentActivity = WeakReference(activity)
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    })
}

// The work detail screen --------------------------------------------------------------------

/** The id and title of each `MediaDetailData` the app built, for the buttons drawn with it. */
private val details: MutableMap<Any, Array<String?>> = Collections.synchronizedMap(WeakHashMap())

/** Each work's id, title, and owner: `owner` is `User(userId=…)` or a channel. */
@Export
fun rememberDetail(detail: Any?, id: String?, title: String?, owner: Any?, ownerName: String?) {
    if (detail == null || id == null) return
    val userId = ownerUserId(owner)
    details[detail] = arrayOf(id, title ?: id, userId, ownerName)
    rememberTitle(id, title)
    if (userId != null) rememberCreator(userId, ownerName)
}

internal fun libraryContext(): Context? = appContext

internal fun changedLibrary() = changed()

/** Adds the song to the favorites ([name] null) or a playlist, made if needed, unless it is there. */
internal fun addToList(name: String?, id: String, title: String) {
    val context = appContext ?: return
    if (name != null) createPlaylist(context, name)
    if (list(name, context).songs.none { it.id == id }) toggleIn(name, id, title, context)
}

internal fun playSongs(activity: Activity, title: String, songs: List<Pair<String, String>>) =
    play(activity, title, songs.map { Song(it.first, it.second, 0) }, 0)

/**
 * The MediaDetailData the detail screen is drawing, set where the screen reads it, just before
 * the description and the buttons. Composition runs on the main thread, one screen at a time.
 */
private var composing: Any? = null

@Export
fun composingDetail(detail: Any?) {
    composing = detail
}

@Export
fun composedDetail(): Any? = composing

/** A Compose state the buttons read, so their labels follow the lists. */
@Export
external fun newState(value: Any?): Any?

@Export
external fun readState(state: Any?): Any?

@Export
external fun writeState(state: Any?, value: Any?)

private val revision: Any? by lazy { newState(0) }

private fun changed() {
    if (appContext == null) return
    val state = revision ?: return
    writeState(state, ((readState(state) as? Int) ?: 0) + 1)
}

/** The favorite button's label for the work [detail]; read while drawing it. */
@Export
fun buttonLabel(detail: Any?): String {
    revision?.let { readState(it) }
    val id = details[detail ?: return AppStrings.favoriteAdd]?.get(0)
    return if (id != null && favorites().songs.any { it.id == id }) AppStrings.favoriteRemove else AppStrings.favoriteAdd
}

/** The playlist button's label: how many of the playlists hold the work [detail]. */
@Export
fun playlistLabel(detail: Any?): String {
    revision?.let { readState(it) }
    val id = details[detail ?: return AppStrings.playlistAdd]?.get(0) ?: return AppStrings.playlistAdd
    val count = playlists().count { list -> list.songs.any { it.id == id } } + if (isDownloaded(appContext, id)) 1 else 0
    return if (count == 0) AppStrings.playlistAdd else String.format(AppStrings.playlistIn, count)
}

/** Adds the work [detail] to the favorites, or removes it if it is there. */
@Export
fun toggle(detail: Any?) {
    val info = details[detail ?: return] ?: return
    val id = info[0] ?: return
    val removed = toggleIn(null, id, info[1] ?: id)
    appContext?.let {
        Toast.makeText(it, if (removed) AppStrings.favoriteRemoved else AppStrings.favoriteAdded, Toast.LENGTH_SHORT).show()
    }
}

/** Opens the chooser that puts the work [detail] in playlists or takes it out. */
@Export
fun choosePlaylists(detail: Any?) {
    val info = details[detail ?: return] ?: return
    chooseFor(info[0] ?: return, info[1], info[2], info[3])
}

/** The save chooser for a song: download, playlists, a new playlist, and its creator if known. */
private fun chooseFor(id: String, songTitle: String?, creatorId: String?, ownerName: String?) {
    val title = songTitle ?: id
    val creatorName = ownerName ?: creatorId
    val activity = currentActivity?.get()?.takeUnless { it.isFinishing } ?: return
    val lists = playlists()
    // First the offline download, then each playlist, a new one, and the creator.
    val downloaded = isDownloaded(activity, id)
    val labels = listOf(if (downloaded) AppStrings.downloadDone else AppStrings.downloadAdd) +
        lists.map { list -> (if (list.songs.any { it.id == id }) "✓ " else "") + "${list.name} (${list.songs.size})" } +
        AppStrings.playlistNew +
        listOfNotNull(creatorId?.let { creatorChoiceLabel(activity, it, creatorName ?: it) })
    Reseam.bundle(BUNDLE).ui(activity).dialog()
        .title(title)
        .choices(labels.toTypedArray(), -1) { choice ->
            if (choice == 0) {
                toggleDownload(activity, id, title)
                changed()
                return@choices
            }
            val index = choice - 1
            if (index == lists.size + 1 && creatorId != null) {
                val watched = toggleCreator(activity, creatorId, creatorName ?: creatorId)
                changed()
                Toast.makeText(
                    activity,
                    String.format(if (watched) AppStrings.creatorWatched else AppStrings.creatorUnwatched, creatorName),
                    Toast.LENGTH_SHORT,
                ).show()
            } else if (index < lists.size) {
                val removed = toggleIn(lists[index].name, id, title)
                Toast.makeText(
                    activity,
                    String.format(if (removed) AppStrings.playlistRemoved else AppStrings.playlistAdded, lists[index].name),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                askName(activity, AppStrings.playlistNew, "") { name ->
                    createPlaylist(activity, name)
                    toggleIn(name, id, title)
                    Toast.makeText(activity, String.format(AppStrings.playlistAdded, name), Toast.LENGTH_SHORT).show()
                }
            }
        }
        .dismissAction(AppStrings.close)
        .show()
}


// The player's ⋮ menu -------------------------------------------------------------------------

/** The song the player's ⋮ menu was opened for: id, title, creator id, creator name. */
@Volatile private var menuSong: Array<String?>? = null

/** VocaColle built the menu's request: `(mediaId, title, mediaDetailData, …)`. */
@Export
fun menuOpened(mediaId: String?, title: String?, detail: Any?) {
    if (mediaId == null) return
    val info = detail?.let { details[it] }
    menuSong = arrayOf(mediaId, title ?: info?.get(1), info?.get(2), info?.get(3))
    rememberTitle(mediaId, title)
}

/** The menu's favorite row: "♡ Favorite" or "♥ Remove from favorites"; read while drawing it. */
@Export
fun menuFavoriteLabel(): String {
    revision?.let { readState(it) }
    val id = menuSong?.get(0) ?: return AppStrings.menuFavoriteAdd
    return if (favorites().songs.any { it.id == id }) AppStrings.menuFavoriteRemove else AppStrings.menuFavoriteAdd
}

@Export
fun menuSaveLabel(): String = AppStrings.menuSave

@Export
fun menuToggleFavorite() {
    val song = menuSong ?: return
    val id = song[0] ?: return
    val removed = toggleIn(null, id, song[1] ?: id)
    appContext?.let {
        Toast.makeText(it, if (removed) AppStrings.favoriteRemoved else AppStrings.favoriteAdded, Toast.LENGTH_SHORT).show()
    }
}

@Export
fun menuChoose() {
    val song = menuSong ?: return
    chooseFor(song[0] ?: return, song[1], song[2], song[3])
}

/** The clicks of the menu's rows: Function0s the patch makes these classes implement. */
@Export
class MenuFavoriteClick

@Export
class MenuSaveClick

/** The click of the "My library" row in VocaColle's Settings. */
@Export
class OpenLibraryClick

/** The title of that row. */
@Export
fun libraryRowTitle(): String = AppStrings.libraryRow

// Storage -----------------------------------------------------------------------------------

private fun prefs(context: Context? = appContext) = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

private fun parse(array: JSONArray?): List<Song> = if (array == null) emptyList() else (0 until array.length()).mapNotNull { index ->
    val item = array.optJSONObject(index) ?: return@mapNotNull null
    val id = item.optString("id").trim()
    if (!VIDEO_ID.matches(id)) return@mapNotNull null
    Song(id, item.optString("title").ifBlank { id }, item.optLong("addedAt"))
}.distinctBy { it.id }

private fun toJson(songs: List<Song>) = JSONArray().apply {
    songs.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("addedAt", it.addedAt)) }
}

private fun favorites(context: Context? = appContext): Playlist {
    val raw = prefs(context)?.getString(KEY_FAVORITES, null)
    return Playlist(null, try { parse(raw?.let { JSONArray(it) }) } catch (_: Exception) { emptyList() })
}

/** The named playlists, in the order they were made. */
private fun playlists(context: Context? = appContext): List<Playlist> {
    val raw = prefs(context)?.getString(KEY_PLAYLISTS, null) ?: return emptyList()
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = cleanName(item.optString("name")) ?: return@mapNotNull null
            Playlist(name, parse(item.optJSONArray("songs")))
        }.distinctBy { it.name }
    } catch (failure: Exception) {
        Log.w(TAG, "playlists unreadable: ${failure.javaClass.simpleName}")
        emptyList()
    }
}

private fun cleanName(name: String?): String? = name?.trim()?.take(MAX_NAME_CHARS)?.takeIf { it.isNotEmpty() }

private fun saveFavorites(songs: List<Song>, context: Context? = appContext) {
    prefs(context)?.edit()?.putString(KEY_FAVORITES, toJson(songs).toString())?.apply()
}

private fun savePlaylists(lists: List<Playlist>, context: Context? = appContext) {
    val array = JSONArray()
    lists.forEach { array.put(JSONObject().put("name", it.name).put("songs", toJson(it.songs))) }
    prefs(context)?.edit()?.putString(KEY_PLAYLISTS, array.toString())?.apply()
}

private fun list(name: String?, context: Context? = appContext): Playlist =
    if (name == null) favorites(context) else playlists(context).firstOrNull { it.name == name } ?: Playlist(name, emptyList())

/** Replaces the songs of the list [name] (null: the favorites). */
private fun saveSongs(name: String?, songs: List<Song>, context: Context? = appContext) {
    if (name == null) {
        saveFavorites(songs, context)
    } else {
        savePlaylists(playlists(context).map { if (it.name == name) Playlist(name, songs) else it }, context)
    }
    changed()
}

/** Whether the song was taken out of the list [name]; else it was added, first. */
private fun toggleIn(name: String?, id: String, title: String, context: Context? = appContext): Boolean {
    val songs = list(name, context).songs.toMutableList()
    val removed = songs.removeAll { it.id == id }
    if (!removed) songs.add(0, Song(id, title, System.currentTimeMillis()))
    saveSongs(name, songs, context)
    return removed
}

private fun createPlaylist(context: Context, name: String) {
    val lists = playlists(context)
    if (lists.none { it.name == name }) savePlaylists(lists + Playlist(name, emptyList()), context)
    changed()
}

// The library screens -----------------------------------------------------------------------

/** Plays [songs] as a VocaColle playlist titled [title], from [start]. */
private fun play(activity: Activity, title: String, songs: List<Song>, start: Int) {
    if (songs.isEmpty()) return
    val uri = Uri.Builder().scheme("nicobox").authority("playlist")
        .appendQueryParameter("title", title)
        .appendQueryParameter("vid", songs.joinToString(",") { it.id })
        .appendQueryParameter("current", (start + 1).toString())
        .build()
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(activity.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (failure: Exception) {
        Log.w(TAG, "couldn't open the playlist: ${failure.javaClass.simpleName}")
        Toast.makeText(activity, AppStrings.favoritesOpenFailed, Toast.LENGTH_LONG).show()
    }
}

/** The library from VocaColle's own Settings row: over the screen the user is on. */
@Export
fun openFromApp() {
    currentActivity?.get()?.takeUnless { it.isFinishing }?.let { open(it) }
}

/** The library: the favorites, then each playlist. A dialog over the Reseam patches screen. */
@Export
fun open(activity: Activity?) {
    if (activity == null) return
    init(activity)
    val lists = listOf(favorites(activity)) + playlists(activity)
    val labels = lists.map { "${it.label} (${it.songs.size})" } + listOf(
        "${AppStrings.creatorsTitle} (${creators(activity).size})",
        "${AppStrings.savedMylistsTitle} (${savedMylists(activity).size})",
        "${AppStrings.downloadsTitle} (${downloads(activity).size})",
    )
    Reseam.bundle(BUNDLE).ui(activity).dialog()
        .title(AppStrings.localFavorites)
        .choices(labels.toTypedArray(), -1) { index ->
            when (index) {
                lists.size -> openCreators(activity)
                lists.size + 1 -> openSavedMylists(activity)
                lists.size + 2 -> openDownloads(activity)
                else -> openList(activity, lists[index].name)
            }
        }
        .action(AppStrings.favoritesMore) { more(activity) }
        .action(AppStrings.playlistNew) {
            askName(activity, AppStrings.playlistNew, "") { name ->
                createPlaylist(activity, name)
                open(activity)
            }
        }
        .show()
}

private fun openList(activity: Activity, name: String?) {
    val playlist = list(name, activity)
    val dialog = Reseam.bundle(BUNDLE).ui(activity).dialog().title("${playlist.label} (${playlist.songs.size})")
    if (playlist.songs.isEmpty()) {
        dialog.message(if (name == null) AppStrings.favoritesEmpty else AppStrings.playlistEmpty)
    } else {
        dialog.choices(playlist.songs.map { it.title }.toTypedArray(), -1) { index -> song(activity, playlist, index) }
    }
    if (name != null) dialog.action(AppStrings.playlistEdit) { editList(activity, name) }
    if (playlist.songs.isNotEmpty()) dialog.action(AppStrings.favoritesPlayAll) { play(activity, playlist.label, playlist.songs, 0) }
    dialog.show()
}

private fun song(activity: Activity, playlist: Playlist, index: Int) {
    val song = playlist.songs[index]
    Reseam.bundle(BUNDLE).ui(activity).dialog()
        .title(song.title)
        .message(song.id)
        .action(AppStrings.remove) {
            saveSongs(playlist.name, list(playlist.name, activity).songs.filter { it.id != song.id }, activity)
            openList(activity, playlist.name)
        }
        .action(AppStrings.favoritesPlayFromHere) { play(activity, playlist.label, playlist.songs, index) }
        .show()
}

private fun editList(activity: Activity, name: String) {
    Reseam.bundle(BUNDLE).ui(activity).dialog()
        .title(name)
        .action(AppStrings.playlistDelete) {
            Reseam.bundle(BUNDLE).ui(activity).dialog()
                .title(name)
                .message(AppStrings.playlistDeleteConfirm)
                .dismissAction(AppStrings.cancel)
                .action(AppStrings.playlistDelete) {
                    savePlaylists(playlists(activity).filter { it.name != name }, activity)
                    changed()
                    open(activity)
                }
                .show()
        }
        .action(AppStrings.playlistRename) {
            askName(activity, AppStrings.playlistRename, name) { renamed ->
                val lists = playlists(activity)
                if (lists.any { it.name == renamed }) {
                    Toast.makeText(activity, AppStrings.playlistExists, Toast.LENGTH_SHORT).show()
                } else {
                    savePlaylists(lists.map { if (it.name == name) Playlist(renamed, it.songs) else it }, activity)
                    changed()
                }
                openList(activity, if (lists.any { it.name == renamed }) name else renamed)
            }
        }
        .show()
}

private fun askName(activity: Activity, title: String, current: String, then: (String) -> Unit) {
    val ui = Reseam.bundle(BUNDLE).ui(activity)
    val field = ui.textField(AppStrings.playlistName)
    field.field.setText(current)
    ui.dialog()
        .title(title)
        .view(field)
        .dismissAction(AppStrings.cancel)
        .action(AppStrings.save) { cleanName(field.text)?.let(then) }
        .show()
}

/** Export, import. */
private fun more(activity: Activity) {
    Reseam.bundle(BUNDLE).ui(activity).dialog()
        .title(AppStrings.favoritesMore)
        .message(AppStrings.favoritesMoreSummary)
        .action(AppStrings.favoritesImport) { import(activity) }
        .action(AppStrings.favoritesExport) { export(activity) }
        .show()
}

private fun export(activity: Activity) {
    val lists = JSONArray()
    playlists(activity).forEach { lists.put(JSONObject().put("name", it.name).put("songs", toJson(it.songs))) }
    val text = JSONObject().put("format", EXPORT_FORMAT).put("version", 2)
        .put("favorites", toJson(favorites(activity).songs)).put("playlists", lists).toString(2)
    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(AppStrings.favoritesTitle, text))
    try {
        activity.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), AppStrings.favoritesExport),
        )
    } catch (_: Exception) {
        Toast.makeText(activity, AppStrings.favoritesCopied, Toast.LENGTH_SHORT).show()
    }
}

/** Songs in [incoming] that [current] lacks, after it: what is here stays, in its place. */
private fun merge(current: List<Song>, incoming: List<Song>): Pair<List<Song>, Int> {
    val known = current.mapTo(HashSet()) { it.id }
    val added = incoming.filter { it.id !in known }
    return (current + added) to added.size
}

private fun import(activity: Activity) {
    val ui = Reseam.bundle(BUNDLE).ui(activity)
    val field = ui.textField(AppStrings.favoritesImportHint)
    ui.dialog()
        .title(AppStrings.favoritesImport)
        .view(field)
        .dismissAction(AppStrings.cancel)
        .action(AppStrings.favoritesImport) {
            var added = 0
            val ok = try {
                val text = field.text.trim()
                if (text.startsWith("[")) {
                    val (songs, count) = merge(favorites(activity).songs, parse(JSONArray(text)))
                    saveFavorites(songs, activity)
                    added += count
                } else {
                    val root = JSONObject(text)
                    if (!root.has("favorites") && !root.has("playlists")) throw IllegalArgumentException()
                    val (songs, count) = merge(favorites(activity).songs, parse(root.optJSONArray("favorites")))
                    saveFavorites(songs, activity)
                    added += count
                    val incoming = root.optJSONArray("playlists") ?: JSONArray()
                    var lists = playlists(activity)
                    for (index in 0 until incoming.length()) {
                        val item = incoming.optJSONObject(index) ?: continue
                        val name = cleanName(item.optString("name")) ?: continue
                        val existing = lists.firstOrNull { it.name == name }?.songs ?: emptyList()
                        val (merged, n) = merge(existing, parse(item.optJSONArray("songs")))
                        added += n
                        lists = if (lists.any { it.name == name }) {
                            lists.map { if (it.name == name) Playlist(name, merged) else it }
                        } else {
                            lists + Playlist(name, merged)
                        }
                    }
                    savePlaylists(lists, activity)
                }
                true
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                Toast.makeText(activity, AppStrings.favoritesImportFailed, Toast.LENGTH_LONG).show()
            } else {
                changed()
                Toast.makeText(activity, String.format(AppStrings.favoritesImported, added), Toast.LENGTH_SHORT).show()
                open(activity)
            }
        }
        .show()
}
