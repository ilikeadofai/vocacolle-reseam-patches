package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.Type
import app.reseam.patch.appEntry
import app.reseam.patch.before
import app.reseam.patch.klass
import app.reseam.patch.method
import app.reseam.patch.patch
import app.reseam.patch.settings.gate
import app.reseam.patch.settings.section
import io.github.ilikeadofai.vocacolle.core.bindings.VocaDbTitles
import io.github.ilikeadofai.vocacolle.settings.MetadataSettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings

/** `PlayerViewModel.VideoInfo(videoId, title, ownerName, thumbnail, largeThumbnail, masked)`. */
private val playerVideoInfo = klass("playerVideoInfo") {
    strings("VideoInfo(videoId=")
}
private val playerVideoInfoInit = method("playerVideoInfo.<init>") {
    inClass(playerVideoInfo)
    custom { name == "<init>" && parameterTypes.size == 6 && parameterTypes[0] == Type.String && parameterTypes[1] == Type.String }
}

@Suppress("unused")
val vocadbTitles = patch("VocaDB song titles") {
    description(
        """
        Shows the English or romanized title VocaDB lists for the exact Niconico video in the
        player. Off until turned on in the Reseam patches screen; when on, the player's video ids
        are sent to vocadb.net.
        """,
    )
    compatibleWith(VOCACOLLE)
    enabledByDefault(false)
    settings(vocacolleSettings, section(VocaColleStrings.sectionMetadata, MetadataSettings.vocadbTitles))

    execute {
        appEntry { VocaDbTitles.init(application) }
        gate(MetadataSettings.vocadbTitles) {
            playerVideoInfoInit.before { param(1).assign(VocaDbTitles.displayTitle(param(0), param(1))) }
        }
    }
}
