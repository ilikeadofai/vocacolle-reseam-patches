@file:JvmName("CacheCleaner")

package io.github.ilikeadofai.vocacolle.core

import android.app.Activity
import android.text.format.Formatter
import android.widget.Toast
import app.reseam.runtime.Export
import app.reseam.runtime.Reseam
import io.github.ilikeadofai.vocacolle.core.lyrics.cachedLyricsCount
import io.github.ilikeadofai.vocacolle.core.lyrics.clearLyricsCache
import io.github.ilikeadofai.vocacolle.core.translate.clearTranslationCache
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings
import java.io.File

private const val BUNDLE = "vocacolle-reseam-patches"

/**
 * What the patches keep to save requests: VocaDB answers and translations on disk, lyrics in
 * memory. Each can be cleared on its own; the favorites and playlists are not a cache and stay.
 */
@Export
fun open(activity: Activity?) {
    if (activity == null) return
    val vocadb = treeSize(File(activity.cacheDir, "vocacolle-reseam/vocadb"))
    val translations = treeSize(File(activity.cacheDir, "vocacolle-reseam/ai"))
    val lyrics = cachedLyricsCount()
    fun size(bytes: Long) = Formatter.formatShortFileSize(activity, bytes)
    val labels = arrayOf(
        String.format(VocaColleStrings.cacheVocadb, size(vocadb)),
        String.format(VocaColleStrings.cacheTranslations, size(translations)),
        String.format(VocaColleStrings.cacheLyrics, lyrics),
    )
    Reseam.bundle(BUNDLE).ui(activity).dialog()
        .title(VocaColleStrings.clearCaches)
        .message(VocaColleStrings.clearCachesSummary)
        .choices(labels, -1) { index ->
            val freed = when (index) {
                0 -> clearVocaDbCache(activity)
                1 -> clearTranslationCache(activity)
                else -> 0L.also { clearLyricsCache() }
            }
            done(activity, freed)
        }
        .dismissAction(VocaColleStrings.close)
        .action(VocaColleStrings.clearAll) {
            val freed = clearVocaDbCache(activity) + clearTranslationCache(activity)
            clearLyricsCache()
            done(activity, freed)
        }
        .show()
}

private fun done(activity: Activity, freed: Long) {
    Toast.makeText(activity, String.format(VocaColleStrings.cacheCleared, Formatter.formatShortFileSize(activity, freed)), Toast.LENGTH_SHORT).show()
}
