package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.Type
import app.reseam.patch.after
import app.reseam.patch.appEntry
import app.reseam.patch.klass
import app.reseam.patch.method
import app.reseam.patch.patch
import app.reseam.patch.settings.gate
import app.reseam.patch.settings.section
import io.github.ilikeadofai.vocacolle.core.bindings.VocaDbTitles
import io.github.ilikeadofai.vocacolle.settings.MetadataSettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings

/** The `SnsShareText` interface: one method returning the text the share sheet gets. */
private val snsShareText = klass("snsShareText") { sourceFile("SnsShareText.kt") }

/** `SnsMediaShareText`, the text for sharing one work. */
private val snsMediaShareText = klass("snsMediaShareText") { sourceFile("SnsMediaShareText.kt") }

private val mediaShareTextGetter = method("snsMediaShareText.getShareText") {
    inClass(snsMediaShareText)
    params()
    returns(Type.String)
    custom { name == snsShareText.classDef.methods.single().name }
}

@Suppress("unused")
val youtubeShareLinks = patch("YouTube share links") {
    description(
        """
        When sharing a work, uses the YouTube original that VocaDB lists for the exact Niconico
        video instead of the Niconico link. Off until turned on in the Reseam patches screen; when
        on, the shared video's id is sent to vocadb.net.
        """,
    )
    compatibleWith(VOCACOLLE)
    enabledByDefault(false)
    settings(vocacolleSettings, section(VocaColleStrings.sectionMetadata, MetadataSettings.shareYoutube))

    execute {
        appEntry { VocaDbTitles.init(application) }
        gate(MetadataSettings.shareYoutube) {
            mediaShareTextGetter.after { returnValue(VocaDbTitles.shareText(result)) }
        }
    }
}
