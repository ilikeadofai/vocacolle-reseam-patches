package io.github.ilikeadofai.vocacolle.core.library

private class MemoryJournal : TransferJournal {
    val records = mutableMapOf<String, TransferCheckpoint>()
    var failIdSave = false
    var failReservation = false
    override fun get(name: String) = records[name]
    override fun put(name: String, checkpoint: TransferCheckpoint) {
        if (failReservation && checkpoint.nativeId == null) error("journal storage unavailable")
        if (failIdSave && checkpoint.nativeId != null) { failIdSave = false; error("interrupted after create") }
        records[name] = checkpoint
    }
}
private class NativeStore : PlaylistDestination {
    val rows = linkedMapOf<Long, Pair<String, LinkedHashSet<String>>>()
    val added = mutableListOf<String>()
    private var nextId = 0L
    var failAfterAdd = false
    var failBeforeAdd = false
    override fun lists() = rows.map { (id, row) -> NativeList(id, row.first, row.second.toSet()) }
    override fun create(title: String): Long {
        val id = ++nextId
        rows[id] = title to linkedSetOf()
        return id
    }
    override fun add(id: Long, videoId: String) {
        if (failBeforeAdd) error("offline")
        check(rows.getValue(id).second.add(videoId)) { "duplicate native addition" }
        added += videoId
        if (failAfterAdd) { failAfterAdd = false; error("interrupted after add") }
    }
}

fun main() {
    val source = listOf(PlaylistBackup("A", listOf("sm3", "sm2", "sm1", "sm2")), PlaylistBackup("Empty", emptyList()))
    run {
        val db = NativeStore(); val journal = MemoryJournal()
        val result = transferPlaylists(source, db, journal)
        check(result == TransferResult(2, 0))
        check(db.rows.values.map { it.first } == listOf("A", "Empty"))
        check(db.added == listOf("sm1", "sm2", "sm3")) // Original newest-first order after native insertion.
        transferPlaylists(source, db, journal)
        check(db.rows.size == 2 && db.added.size == 3)
        db.rows.clear() // User deletes/renames native copies after completion: do not resurrect them.
        transferPlaylists(source, db, journal)
        check(db.rows.isEmpty())
    }
    run {
        val db = NativeStore(); val journal = MemoryJournal()
        val old = db.create("A"); db.add(old, "sm99")
        transferPlaylists(source.take(1), db, journal)
        check(db.rows[old]?.second == setOf("sm99"))
        check(db.rows.values.last().first == "A (2)")
        check(db.rows.values.last().second == setOf("sm1", "sm2", "sm3"))
    }
    run {
        val db = NativeStore(); val journal = MemoryJournal(); journal.failIdSave = true
        check(transferPlaylists(source.take(1), db, journal).pending == 1)
        check(db.rows.size == 1 && journal.get("A")?.nativeId == null)
        check(transferPlaylists(source.take(1), db, journal).pending == 0)
        check(db.rows.size == 1 && db.added.size == 3)
    }
    run {
        val db = NativeStore(); val journal = MemoryJournal(); db.failAfterAdd = true
        check(transferPlaylists(source.take(1), db, journal).pending == 1)
        check(transferPlaylists(source.take(1), db, journal).pending == 0)
        check(db.added == listOf("sm1", "sm2", "sm3"))
    }
    run {
        val db = NativeStore(); val journal = MemoryJournal(); db.failBeforeAdd = true
        check(transferPlaylists(source, db, journal) == TransferResult(1, 1))
        check(journal.get("A")?.complete == false && journal.get("Empty")?.complete == true)
        db.failBeforeAdd = false
        check(transferPlaylists(source, db, journal) == TransferResult(1, 0))
        val otherOwner = NativeStore(); val otherJournal = MemoryJournal()
        check(transferPlaylists(source, otherOwner, otherJournal) == TransferResult(2, 0))
    }
    run {
        val db = NativeStore(); val journal = MemoryJournal(); journal.failReservation = true
        check(transferPlaylists(source.take(1), db, journal) == TransferResult(0, 1))
        check(db.rows.isEmpty()) // Never create native data without a durable reservation.
    }
    run {
        val db = NativeStore(); val journal = MemoryJournal(); db.failBeforeAdd = true
        transferPlaylists(source.take(1), db, journal)
        db.rows.clear() // Native deletion while a transfer is still pending is authoritative too.
        db.failBeforeAdd = false
        check(transferPlaylists(source.take(1), db, journal) == TransferResult(0, 0))
        check(db.rows.isEmpty() && journal.get("A")?.complete == true)
    }
    run {
        val db = NativeStore(); val journal = MemoryJournal()
        transferPlaylists(source.take(1), db, journal)
        db.rows.values.first().second.remove("sm2") // An edit made in the native UI.
        // A later import/limit save must be discovered even if the repository was unavailable then.
        transferPlaylists(listOf(PlaylistBackup("A", listOf("sm4", "sm3", "sm2", "sm1"))), db, journal)
        check(db.rows.values.first().second == setOf("sm1", "sm3", "sm4"))
        check(db.added == listOf("sm1", "sm2", "sm3", "sm4"))
        db.rows.clear()
        val unrelated = db.create("A"); db.add(unrelated, "sm99")
        transferPlaylists(listOf(PlaylistBackup("A", listOf("sm5", "sm4", "sm3", "sm2", "sm1"))), db, journal)
        check(db.rows.values.first().second == setOf("sm99"))
        check(db.rows.values.last().first == "A (2)" && db.rows.values.last().second == setOf("sm5"))
    }
    println("PASS: copy, empty lists, order, duplicate IDs, idempotency, deletion, collision, interrupted creation/addition, offline retry, independent journals, reservation failure, pending deletion, incremental copy preserves native edits")
}
