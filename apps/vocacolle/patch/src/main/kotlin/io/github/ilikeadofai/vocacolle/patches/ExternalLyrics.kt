package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.dex.Opcode
import app.reseam.patch.Type
import app.reseam.patch.before
import app.reseam.patch.klass
import app.reseam.patch.method
import app.reseam.patch.patch
import app.reseam.patch.points
import app.reseam.patch.settings.gate
import app.reseam.patch.settings.section
import io.github.ilikeadofai.vocacolle.core.lyrics.bindings.ExternalLyrics
import io.github.ilikeadofai.vocacolle.settings.LyricsSettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings

private val lyricsSegment = klass("lyricsSegment") { strings("LyricsSegment(lines=") }
private val lyricsWithMetadata = klass("lyricsWithMetadata") { strings("LyricsWithMetadata(videoId=") }
private val lyricsWithCacheState = klass("lyricsWithCacheState") { strings("LyricsWithCacheState(lyrics=") }

/** `LyricsLoader.Error.Reason`: why the app couldn't load a song's lyrics. */
private val lyricsErrorReason = klass("lyricsLoader.Error.Reason") {
    enumConstants("OFFLINE", "LYRICS_ACCESS_LIMIT_EXCEEDED", "NOT_FOUND", "MAINTENANCE")
}

/** `LyricsLoader.load(videoId)`, which throws `LyricsLoader.Error(reason)` when there are none. */
private val lyricsLoad = method("lyricsLoader.load") {
    accesses { owner(lyricsErrorReason.descriptor) }
    param(0, Type.String)
    paramCount(2)
    returns(Type.Object)
    custom { classDef.sourceFile == "LyricsLoader.kt" }
}

@Suppress("unused")
val externalLyrics = patch("External lyrics") {
    description(
        """
        Shows synced lyrics from LRCLIB, or VocaDB's original lyrics, when VocaColle has none for
        a song or the monthly lyrics limit is reached. Songs are matched by the exact Niconico
        video on VocaDB. Off until turned on in the Reseam patches screen.
        """,
    )
    compatibleWith(VOCACOLLE)
    enabledByDefault(false)
    settings(vocacolleSettings, section(VocaColleStrings.sectionLyrics, LyricsSettings.externalLyrics))

    execute {
        ExternalLyrics.newSegment.implement {
            returnValue(
                newInstance(lyricsSegment.descriptor, "(Ljava/util/List;Ljava/lang/Integer;Ljava/lang/Integer;)V", param(0), param(1), param(2)),
            )
        }
        ExternalLyrics.newLyrics.implement {
            returnValue(newInstance(lyricsWithMetadata.descriptor, "(Ljava/lang/String;Ljava/util/List;Z)V", param(0), param(1), param(2)))
        }
        // The error class is the reason enum's outer class.
        val error = lyricsErrorReason.descriptor.substringBeforeLast('$') + ";"
        gate(LyricsSettings.externalLyrics) {
            playerVideoInfoInit.before { ExternalLyrics.prefetch(param(0)) }
            lyricsLoad.before { ExternalLyrics.requested(param(0)) }
            lyricsLoad.points("lyricsError") { invokeDirect { owner(error); name("<init>") } }.forEach {
                captureArgumentAs("reason", 1).next { opcode(Opcode.THROW) }.before {
                    val reason = capture("reason").callVirtual("java.lang.Enum", "name", "()Ljava/lang/String;")
                    val lyrics = ExternalLyrics.fallback(param(0), reason)
                    whenNotNull(lyrics) {
                        returnValue(
                            newInstance(
                                lyricsWithCacheState.descriptor,
                                "(${lyricsWithMetadata.descriptor}Z)V",
                                lyrics.cast(lyricsWithMetadata.descriptor),
                                bool(false),
                            ),
                        )
                    }
                }
            }
        }
    }
}
