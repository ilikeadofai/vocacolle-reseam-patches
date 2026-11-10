@file:JvmName("NativePlaylists")

package io.github.ilikeadofai.vocacolle.core.library

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.reseam.runtime.Export
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "VocaCollePlaylists"
private const val JOURNAL = "vocacolle_reseam_native_playlists"
private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, TAG).apply { isDaemon = true } }
private val busy = AtomicBoolean(false)
@Volatile private var repository: Any? = null
@Volatile private var picker: WeakReference<Any>? = null
@Volatile private var lastOwner = 0L
@Volatile private var lastAttempt = 0L

@Export external fun ownerId(repository: Any): Long
@Export external fun entries(repository: Any): List<Any>
@Export external fun entryId(entry: Any): Long
@Export external fun entryTitle(entry: Any): String
@Export external fun entryType(entry: Any): String
@Export external fun entryItems(entry: Any): List<Any>
@Export external fun itemVideoId(item: Any): String
@Export external fun runOperation(operation: Any): Any?
@Export external fun showPicker(state: Any, videoId: String)

/** The patch makes these implement the app's obfuscated suspend Function2 interface. */
@Export class CreateNativePlaylist(val repository: Any, val title: String)
@Export class AddNativePlaylistItem(val repository: Any, val playlistId: Long, val videoId: String)

internal fun hasNativePlaylists(): Boolean = repository != null

private class NativeDestination(private val target: Any) : PlaylistDestination {
    override fun lists(): List<NativeList> = entries(target).filter { entryType(it) == "LOCALLIST" }.map { entry ->
        NativeList(entryId(entry), entryTitle(entry), entryItems(entry).map(::itemVideoId).toSet())
    }
    override fun create(title: String): Long =
        (runOperation(CreateNativePlaylist(target, title)) as Number).toLong().also { check(it > 0) }
    override fun add(id: Long, videoId: String) { runOperation(AddNativePlaylistItem(target, id, videoId)) }
}

/** Each native library owner gets a separate journal; legacy playlist JSON is never rewritten. */
private class OwnerJournal(context: Context, owner: Long) : TransferJournal {
    private val prefs = context.getSharedPreferences(JOURNAL, Context.MODE_PRIVATE)
    private val key = "owner.$owner"
    private val data = JSONObject(prefs.getString(key, "{}") ?: "{}")
    private fun sourceKey(name: String) = MessageDigest.getInstance("SHA-256")
        .digest(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    override fun get(name: String): TransferCheckpoint? {
        val row = data.optJSONObject(sourceKey(name)) ?: return null
        val settled = row.optJSONArray("settled") ?: JSONArray()
        return TransferCheckpoint(row.getString("title"), if (row.has("id")) row.getLong("id") else null,
            row.optBoolean("complete"), (0 until settled.length()).map { settled.getString(it) }.toSet())
    }
    override fun put(name: String, checkpoint: TransferCheckpoint) {
        data.put(sourceKey(name), JSONObject().put("title", checkpoint.title).put("id", checkpoint.nativeId)
            .put("complete", checkpoint.complete).put("settled", JSONArray(checkpoint.settledVideos.toList())))
        save()
    }
    private fun save() {
        check(prefs.edit().putString(key, data.toString()).commit()) { "Playlist transfer journal could not be saved" }
    }
}

/** Called after the app returns its current repository, never from a database transaction. */
@Export
fun repositoryReady(value: Any?) {
    try {
        if (value == null || ownerId(value) == 0L) {
            repository = null
            return
        }
        repository = value
        transferIfNeeded()
    } catch (failure: Exception) {
        Log.w(TAG, "Native playlist repository unavailable: ${failure.javaClass.simpleName}")
    }
}

/** Explicit backup imports and limit saves may add songs after the initial copy finished. */
internal fun refreshNativeBackups(names: Set<String>) {
    if (names.isEmpty()) return
    worker.execute {
        lastAttempt = 0
        transferIfNeeded()
    }
}

internal fun transferIfNeeded() {
    val target = repository ?: return
    val context = libraryContext() ?: return
    val owner = ownerId(target)
    val now = SystemClock.elapsedRealtime()
    if (owner == lastOwner && now - lastAttempt < 10_000) return
    if (!busy.compareAndSet(false, true)) return
    lastOwner = owner
    lastAttempt = now
    worker.execute {
        try {
            val backups = legacyPlaylistBackups()
            if (backups.isNotEmpty()) {
                val result = transferPlaylists(backups, NativeDestination(target), OwnerJournal(context, owner))
                Log.i(TAG, "Native playlist transfer: copied=${result.copied}, pending=${result.pending}")
            }
        } catch (failure: Exception) {
            Log.w(TAG, "Native playlist transfer pending: ${failure.javaClass.simpleName}")
        } finally {
            busy.set(false)
            if (target !== repository) transferIfNeeded()
        }
    }
}

@Export fun rememberPicker(state: Any?) { picker = state?.let { WeakReference(it) } }
@Export fun clearPicker() { picker = null }

/** Reuses the player's real Compose bottom-sheet host, request, and callbacks. */
internal fun openNativePlaylistPicker(videoId: String): Boolean {
    if (!hasNativePlaylists()) return false
    val state = picker?.get() ?: return false
    return try {
        transferIfNeeded()
        showPicker(state, videoId)
        true
    } catch (failure: Exception) {
        Log.w(TAG, "Native playlist picker unavailable: ${failure.javaClass.simpleName}")
        false
    }
}
