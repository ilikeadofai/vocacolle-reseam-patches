@file:JvmName("CacheCleaner")

package io.github.ilikeadofai.vocacolle.core

import android.app.Activity
import android.text.format.Formatter
import android.widget.Toast
import app.reseam.runtime.Export
import app.reseam.runtime.Reseam
import io.github.ilikeadofai.vocacolle.core.lyrics.lyricsCacheBytes
import io.github.ilikeadofai.vocacolle.core.lyrics.clearLyricsCache
import io.github.ilikeadofai.vocacolle.core.translate.clearTranslationCache
import io.github.ilikeadofai.vocacolle.core.AppStrings
import java.io.File

private const val BUNDLE = "vocacolle-reseam-patches"

/**
 * What the patches keep to save requests: VocaDB answers, translations, and lyrics, in memory
 * and on disk. Each can be cleared on its own; the favorites and playlists are not a cache and stay.
 */
@Export
fun open(activity: Activity?) {
    if (activity == null) return
    val vocadb = treeSize(File(activity.cacheDir, "vocacolle-reseam/vocadb"))
    val translations = treeSize(File(activity.cacheDir, "vocacolle-reseam/ai"))
    val lyrics = lyricsCacheBytes()
    fun size(bytes: Long) = Formatter.formatShortFileSize(activity, bytes)
    val labels = arrayOf(
        String.format(AppStrings.cacheVocadb, size(vocadb)),
        String.format(AppStrings.cacheTranslations, size(translations)),
        String.format(AppStrings.cacheLyrics, size(lyrics)),
    )
    Reseam.bundle(BUNDLE).ui(activity).dialog()
        .title(AppStrings.clearCaches)
        .message(AppStrings.clearCachesSummary)
        .choices(labels, -1) { index ->
            val freed = when (index) {
                0 -> clearVocaDbCache(activity)
                1 -> clearTranslationCache(activity)
                else -> clearLyricsCache()
            }
            done(activity, freed)
        }
        .dismissAction(AppStrings.close)
        .action(AppStrings.clearAll) {
            val freed = clearVocaDbCache(activity) + clearTranslationCache(activity) + clearLyricsCache()
            done(activity, freed)
        }
        .show()
}

private fun done(activity: Activity, freed: Long) {
    Toast.makeText(activity, String.format(AppStrings.cacheCleared, Formatter.formatShortFileSize(activity, freed)), Toast.LENGTH_SHORT).show()
}
