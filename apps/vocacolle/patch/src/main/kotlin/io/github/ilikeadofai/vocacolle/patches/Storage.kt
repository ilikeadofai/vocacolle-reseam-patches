package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.patch
import app.reseam.patch.settings.onAction
import app.reseam.patch.settings.section
import io.github.ilikeadofai.vocacolle.core.bindings.CacheCleaner
import io.github.ilikeadofai.vocacolle.settings.StorageSettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings

@Suppress("unused")
val clearCaches = patch("Clear caches") {
    description(
        """
        Adds a Clear caches row to the Reseam patches screen: VocaDB titles and links,
        translations and readings, and the lyrics found this session, each with its size,
        cleared one at a time or all together. Favorites and playlists are kept.
        """,
    )
    compatibleWith(VOCACOLLE)
    settings(vocacolleSettings, section(VocaColleStrings.sectionStorage, StorageSettings.clearCaches))

    execute {
        onAction(StorageSettings.clearCaches) { CacheCleaner.open(activity) }
    }
}
