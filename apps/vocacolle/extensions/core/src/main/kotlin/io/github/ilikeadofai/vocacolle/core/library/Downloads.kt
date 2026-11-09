@file:JvmName("Downloads")

package io.github.ilikeadofai.vocacolle.core.library

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import app.reseam.runtime.Export
import app.reseam.runtime.Reseam
import io.github.ilikeadofai.vocacolle.core.AppStrings
import org.json.JSONArray
import org.json.JSONObject

/*
 * Single songs saved for offline play with VocaColle's own cache, which the app offers only
 * for whole playlists. The songs are queued the way a playlist's cache switch queues them, under
 * a playlist id no real playlist uses, so the app's downloader, its Wi-Fi and size settings, its
 * notification, and its "Delete cached content" all work on them as on any cached song.
 */

private const val TAG = "VocaColleLibrary"
private const val BUNDLE = "vocacolle-reseam-patches"
private const val PREFS = "vocacolle_reseam_library"
private const val KEY_DOWNLOADS = "downloads"

/** Above any id the app's own playlist table hands out. */
internal const val DOWNLOADS_PLAYLIST_ID = 0x5245_5345_414DL

/** VocaColle's cache service, `cache(mediaIds, playlistId)`, called by the patch. */
@Export
external fun cacheSongs(mediaIds: List<String>, playlistId: Long)

/** VocaColle's cache service, `removeCache(mediaIds, playlistId, null)`, called by the patch. */
@Export
external fun uncacheSongs(mediaIds: List<String>, playlistId: Long)

private fun prefs(context: Context?) = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

internal class Download(val id: String, val title: String)

internal fun downloads(context: Context?): List<Download> = try {
    val array = JSONArray(prefs(context)?.getString(KEY_DOWNLOADS, null) ?: "[]")
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Download(id, item.optString("title").ifBlank { id })
    }.distinctBy { it.id }
} catch (_: Exception) {
    emptyList()
}

private fun save(context: Context?, list: List<Download>) {
    val array = JSONArray()
    list.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title)) }
    prefs(context)?.edit()?.putString(KEY_DOWNLOADS, array.toString())?.apply()
}

internal fun isDownloaded(context: Context?, id: String) = downloads(context).any { it.id == id }

/** Downloads the song, or removes its download; whether it is downloaded now. */
internal fun toggleDownload(context: Context, id: String, title: String): Boolean {
    val list = downloads(context)
    return try {
        if (list.any { it.id == id }) {
            uncacheSongs(listOf(id), DOWNLOADS_PLAYLIST_ID)
            save(context, list.filter { it.id != id })
            Toast.makeText(context, AppStrings.downloadRemoved, Toast.LENGTH_SHORT).show()
            false
        } else {
            cacheSongs(listOf(id), DOWNLOADS_PLAYLIST_ID)
            save(context, listOf(Download(id, title)) + list)
            Toast.makeText(context, AppStrings.downloadStarted, Toast.LENGTH_LONG).show()
            true
        }
    } catch (failure: Throwable) {
        Log.w(TAG, "download toggle failed for $id", failure)
        Toast.makeText(context, AppStrings.downloadFailed, Toast.LENGTH_LONG).show()
        list.any { it.id == id }
    }
}

/** The downloads, playable as a list, each removable. */
internal fun openDownloads(activity: Activity) {
    val list = downloads(activity)
    val ui = Reseam.bundle(BUNDLE).ui(activity)
    val dialog = ui.dialog().title("${AppStrings.downloadsTitle} (${list.size})")
    if (list.isEmpty()) {
        dialog.message(AppStrings.downloadsEmpty)
    } else {
        dialog.choices(list.map { it.title }.toTypedArray(), -1) { index ->
            val download = list[index]
            ui.dialog()
                .title(download.title)
                .message(AppStrings.downloadsItemSummary)
                .action(AppStrings.downloadRemove) {
                    toggleDownload(activity, download.id, download.title)
                    openDownloads(activity)
                }
                .action(AppStrings.favoritesPlayFromHere) {
                    playSongs(activity, AppStrings.downloadsTitle, (list.drop(index) + list.take(index)).map { it.id to it.title })
                }
                .show()
        }
        dialog.action(AppStrings.favoritesPlayAll) { playSongs(activity, AppStrings.downloadsTitle, list.map { it.id to it.title }) }
    }
    dialog.show()
}
