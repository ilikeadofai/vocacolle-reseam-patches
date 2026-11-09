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
        Adds a button to clear the lyrics, translations, and VocaDB answers the patches keep.
        """,
    )
    compatibleWith(VOCACOLLE)
    settings(vocacolleSettings, section(toolsPage, VocaColleStrings.sectionStorage, StorageSettings.clearCaches))

    execute {
        onAction(StorageSettings.clearCaches) { CacheCleaner.open(activity) }
    }
}
