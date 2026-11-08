@file:JvmName("LocalFavorites")

package io.github.ilikeadofai.vocacolle.core.library

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import app.reseam.runtime.Export
import app.reseam.runtime.Reseam
import io.github.ilikeadofai.vocacolle.settings.LibrarySettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap

/*
 * Favorites kept on the device only: no like, mylist, or follow is sent, so none of the
 * account's server-side limits apply, and the real Niconico state is never touched. The list
 * plays through VocaColle's own playlist deep link, `nicobox://playlist?vid=…`.
 */

private const val TAG = "VocaColleLibrary"
private const val BUNDLE = "vocacolle-reseam-patches"
private const val PREFS = "vocacolle_reseam_library"
private const val KEY_FAVORITES = "favorites"
private const val EXPORT_FORMAT = "vocacolle-reseam-favorites"
private val VIDEO_ID = Regex("^[a-z]{2}\\d{1,12}$")

private class Favorite(val id: String, val title: String, val addedAt: Long)

@Volatile private var appContext: Context? = null

@Export
fun init(context: Context?) {
    if (appContext == null) appContext = context?.applicationContext
}

// The work detail screen --------------------------------------------------------------------

/** The id and title of each `MediaDetailData` the app built, for the button drawn with it. */
private val details: MutableMap<Any, Array<String>> = Collections.synchronizedMap(WeakHashMap())

@Export
fun rememberDetail(detail: Any?, id: String?, title: String?) {
    if (detail != null && id != null) details[detail] = arrayOf(id, title ?: id)
}

/**
 * The MediaDetailData the detail screen is drawing, set where the screen reads it, just before
 * the description and the button. Composition runs on the main thread, one screen at a time.
 */
private var composing: Any? = null

@Export
fun composingDetail(detail: Any?) {
    composing = detail
}

@Export
fun composedDetail(): Any? = composing

/** A Compose state the button reads, so its label follows the list. */
@Export
external fun newState(value: Any?): Any?

@Export
external fun readState(state: Any?): Any?

@Export
external fun writeState(state: Any?, value: Any?)

private val revision: Any? by lazy { newState(0) }

private fun changed() {
    val state = revision ?: return
    writeState(state, ((readState(state) as? Int) ?: 0) + 1)
}

/** The favorite button's label for the work [detail]; read while drawing it. */
@Export
fun buttonLabel(detail: Any?): String {
    revision?.let { readState(it) }
    val id = details[detail ?: return VocaColleStrings.favoriteAdd]?.get(0)
    return if (id != null && load().any { it.id == id }) VocaColleStrings.favoriteRemove else VocaColleStrings.favoriteAdd
}

/** Adds the work [detail] to the favorites, or removes it if it is there. */
@Export
fun toggle(detail: Any?) {
    val (id, title) = details[detail ?: return] ?: return
    val list = load().toMutableList()
    val removed = list.removeAll { it.id == id }
    if (!removed) list.add(0, Favorite(id, title, System.currentTimeMillis()))
    save(list)
    changed()
    appContext?.let {
        Toast.makeText(it, if (removed) VocaColleStrings.favoriteRemoved else VocaColleStrings.favoriteAdded, Toast.LENGTH_SHORT).show()
    }
}

/** The click of the button: a Function0 the patch makes this class implement. */
@Export
class FavoriteClick(@Export @JvmField val detail: Any?)

// Storage -----------------------------------------------------------------------------------

private fun prefs(context: Context? = appContext) = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

private fun load(context: Context? = appContext): List<Favorite> {
    val raw = prefs(context)?.getString(KEY_FAVORITES, null) ?: return emptyList()
    return try {
        parse(JSONArray(raw))
    } catch (failure: Exception) {
        Log.w(TAG, "favorites unreadable: ${failure.javaClass.simpleName}")
        emptyList()
    }
}

private fun parse(array: JSONArray): List<Favorite> = (0 until array.length()).mapNotNull { index ->
    val item = array.optJSONObject(index) ?: return@mapNotNull null
    val id = item.optString("id").trim()
    if (!VIDEO_ID.matches(id)) return@mapNotNull null
    Favorite(id, item.optString("title").ifBlank { id }, item.optLong("addedAt"))
}.distinctBy { it.id }

private fun toJson(list: List<Favorite>) = JSONArray().apply {
    list.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("addedAt", it.addedAt)) }
}

private fun save(list: List<Favorite>, context: Context? = appContext) {
    prefs(context)?.edit()?.putString(KEY_FAVORITES, toJson(list).toString())?.apply()
}

// The list ------------------------------------------------------------------------------------

/** Plays [list] as a VocaColle playlist, from [start]. */
private fun play(activity: Activity, list: List<Favorite>, start: Int) {
    if (list.isEmpty()) return
    val uri = Uri.Builder().scheme("nicobox").authority("playlist")
        .appendQueryParameter("title", VocaColleStrings.favoritesTitle)
        .appendQueryParameter("vid", list.joinToString(",") { it.id })
        .appendQueryParameter("current", (start + 1).toString())
        .build()
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(activity.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (failure: Exception) {
        Log.w(TAG, "couldn't open the playlist: ${failure.javaClass.simpleName}")
        Toast.makeText(activity, VocaColleStrings.favoritesOpenFailed, Toast.LENGTH_LONG).show()
    }
}

/** The favorites screen, a dialog over the Reseam patches screen. */
@Export
fun open(activity: Activity?) {
    if (activity == null) return
    init(activity)
    val list = load(activity)
    val ui = Reseam.bundle(BUNDLE).ui(activity)
    val dialog = ui.dialog().title("${VocaColleStrings.favoritesTitle} (${list.size})")
    if (list.isEmpty()) {
        dialog.message(VocaColleStrings.favoritesEmpty)
    } else {
        dialog.choices(list.map { it.title }.toTypedArray(), -1) { index -> item(activity, list, index) }
    }
    dialog.action(VocaColleStrings.favoritesMore) { more(activity) }
    if (list.isNotEmpty()) dialog.action(VocaColleStrings.favoritesPlayAll) { play(activity, list, 0) }
    dialog.show()
}

private fun item(activity: Activity, list: List<Favorite>, index: Int) {
    val favorite = list[index]
    Reseam.bundle(BUNDLE).ui(activity).dialog()
        .title(favorite.title)
        .message(favorite.id)
        .action(VocaColleStrings.favoriteRemove) {
            save(load(activity).filter { it.id != favorite.id }, activity)
            if (appContext != null) changed()
            open(activity)
        }
        .action(VocaColleStrings.favoritesPlayFromHere) { play(activity, list, index) }
        .show()
}

/** Export, import, and clear. */
private fun more(activity: Activity) {
    val ui = Reseam.bundle(BUNDLE).ui(activity)
    ui.dialog()
        .title(VocaColleStrings.favoritesTitle)
        .message(VocaColleStrings.favoritesMoreSummary)
        .action(VocaColleStrings.favoritesImport) { import(activity) }
        .action(VocaColleStrings.favoritesExport) { export(activity) }
        .show()
}

private fun export(activity: Activity) {
    val text = JSONObject().put("format", EXPORT_FORMAT).put("version", 1).put("favorites", toJson(load(activity))).toString(2)
    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(VocaColleStrings.favoritesTitle, text))
    try {
        activity.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), VocaColleStrings.favoritesExport),
        )
    } catch (_: Exception) {
        Toast.makeText(activity, VocaColleStrings.favoritesCopied, Toast.LENGTH_SHORT).show()
    }
}

private fun import(activity: Activity) {
    val ui = Reseam.bundle(BUNDLE).ui(activity)
    val field = ui.textField(VocaColleStrings.favoritesImportHint)
    ui.dialog()
        .title(VocaColleStrings.favoritesImport)
        .view(field)
        .dismissAction(VocaColleStrings.cancel)
        .action(VocaColleStrings.favoritesImport) {
            val incoming = try {
                val text = field.text.trim()
                val array = if (text.startsWith("[")) JSONArray(text) else JSONObject(text).getJSONArray("favorites")
                parse(array)
            } catch (_: Exception) {
                null
            }
            if (incoming == null) {
                Toast.makeText(activity, VocaColleStrings.favoritesImportFailed, Toast.LENGTH_LONG).show()
            } else {
                // Merged: what is here stays, in its place, and new ids follow it.
                val current = load(activity)
                val known = current.mapTo(HashSet()) { it.id }
                save(current + incoming.filter { it.id !in known }, activity)
                if (appContext != null) changed()
                Toast.makeText(activity, String.format(VocaColleStrings.favoritesImported, incoming.count { it.id !in known }), Toast.LENGTH_SHORT).show()
                open(activity)
            }
        }
        .show()
}
