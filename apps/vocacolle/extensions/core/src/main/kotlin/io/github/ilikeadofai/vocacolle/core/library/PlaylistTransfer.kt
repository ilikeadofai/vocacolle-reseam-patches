package io.github.ilikeadofai.vocacolle.core.library

internal data class PlaylistBackup(val name: String, val videos: List<String>)
internal data class NativeList(val id: Long, val name: String, val videos: Set<String>)
internal data class TransferCheckpoint(
    val title: String,
    val nativeId: Long? = null,
    val complete: Boolean = false,
    val settledVideos: Set<String> = emptySet(),
)
internal interface PlaylistDestination {
    fun lists(): List<NativeList>
    fun create(title: String): Long
    fun add(id: Long, videoId: String)
}
internal interface TransferJournal {
    fun get(name: String): TransferCheckpoint?
    fun put(name: String, checkpoint: TransferCheckpoint)
}
internal data class TransferResult(val copied: Int, val pending: Int)

private fun availableTitle(name: String, existing: List<NativeList>): String {
    val names = existing.map { it.name }.toSet()
    var title = name.take(60)
    var suffix = 2
    while (title in names) {
        val tail = " (${suffix++})"
        title = name.take(60 - tail.length) + tail
    }
    return title
}

/** Only new backup IDs may be copied again; native removal of earlier IDs remains authoritative. */
internal fun reopenTransfer(checkpoint: TransferCheckpoint, existing: List<NativeList>): TransferCheckpoint =
    if (checkpoint.nativeId != null && existing.none { it.id == checkpoint.nativeId }) {
        checkpoint.copy(title = availableTitle(checkpoint.title, existing), nativeId = null, complete = false)
    } else checkpoint.copy(complete = false)

internal fun transferPlaylists(backups: List<PlaylistBackup>, destination: PlaylistDestination, journal: TransferJournal): TransferResult {
    var copied = 0
    var pending = 0
    for (backup in backups) {
        try {
            var checkpoint = journal.get(backup.name)
            if (checkpoint?.complete == true && backup.videos.all { it in checkpoint.settledVideos }) continue
            val existing = destination.lists()
            if (checkpoint?.complete == true) {
                // Persisted backup changes can arrive before the native repository is ready.
                checkpoint = reopenTransfer(checkpoint, existing)
                journal.put(backup.name, checkpoint)
            }
            if (checkpoint == null) {
                checkpoint = TransferCheckpoint(availableTitle(backup.name, existing))
                // A reserved title survives a crash between native creation and saving its ID.
                journal.put(backup.name, checkpoint)
            }
            val row = if (checkpoint.nativeId != null) existing.firstOrNull { it.id == checkpoint.nativeId }
                else existing.firstOrNull { it.name == checkpoint.title }
            if (checkpoint.nativeId != null && row == null) {
                // The native copy was removed while pending. Respect that removal too.
                journal.put(backup.name, checkpoint.copy(complete = true, settledVideos = checkpoint.settledVideos + backup.videos))
                continue
            }
            val id = row?.id ?: destination.create(checkpoint.title)
            checkpoint = checkpoint.copy(nativeId = id)
            journal.put(backup.name, checkpoint)
            val present = row?.videos.orEmpty().toMutableSet()
            // Legacy storage is newest first; native additions assign their own timestamps.
            for (video in backup.videos.distinct().asReversed()) {
                if (video !in checkpoint.settledVideos && video !in present) {
                    destination.add(id, video)
                    present.add(video)
                }
            }
            journal.put(backup.name, checkpoint.copy(complete = true, settledVideos = checkpoint.settledVideos + backup.videos))
            copied++
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            pending++
        }
    }
    return TransferResult(copied, pending)
}
