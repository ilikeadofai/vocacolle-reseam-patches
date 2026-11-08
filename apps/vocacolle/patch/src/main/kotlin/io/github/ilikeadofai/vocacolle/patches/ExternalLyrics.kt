package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.dex.Opcode
import app.reseam.patch.MethodTarget
import app.reseam.patch.Type
import app.reseam.patch.methods
import app.reseam.patch.after
import app.reseam.patch.before
import app.reseam.patch.enumConstant
import app.reseam.patch.point
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

/** Why the lyrics button can't open; its "no lyrics registered" values lead to the check. */
private val lyricsUnavailableReason = klass("lyricsUnavailableReason") {
    enumConstants("NO_LYRICS_CHANNEL_VIDEO", "NO_LYRICS_USER_VIDEO", "INVALID_PLAY_MODE")
}

/** The PlayerViewModel checks that refuse a video whose metadata says it has no lyrics. */
private val openLyricsChecks = methods("playerViewModel.openLyrics") {
    accesses { owner(lyricsUnavailableReason.descriptor) }
    custom { classDef.sourceFile == "PlayerViewModel.kt" }
}

/** `VideoWatchMetadata.getHasLyrics()`, read just before "no lyrics registered" is chosen. */
private fun hasLyricsGetter(): MethodTarget {
    val noLyrics = lyricsUnavailableReason.enumConstant("NO_LYRICS_USER_VIDEO")
    val getters = openLyricsChecks.points("noLyricsUserVideo") {
        field { owner(lyricsUnavailableReason.descriptor); name(noLyrics.name) }
    }.all.map { it.previous { invokeVirtual { returns(Type.Boolean); paramCount(0) } }.callee("videoWatchMetadata.hasLyrics") }
    return getters.distinctBy { it.descriptor }.singleOrNull()
        ?: error("expected one hasLyrics getter, found ${getters.map { it.descriptor }.distinct()}")
}

private val videoWatchMetadata = klass("videoWatchMetadata") { strings("VideoWatchMetadata(id=") }

/** The metadata's video id: the first field its constructor sets, from its first parameter. */
private val videoWatchMetadataInit = method("videoWatchMetadata.<init>") {
    inClass(videoWatchMetadata)
    custom {
        name == "<init>" && parameterTypes.size > 5 && parameterTypes[0] == Type.String &&
            parameterTypes[1] == Type.String && parameterTypes[2] == Type.Long
    }
    first()
}

private val videoWatchMetadataId = videoWatchMetadataInit.point("videoWatchMetadata.id") {
    opcode(Opcode.IPUT_OBJECT)
    field { owner(videoWatchMetadata.descriptor); type(Type.String) }
}.field("videoWatchMetadata.id")

/**
 * In `PlayerViewModel`'s lyrics coroutine, the failure branch:
 * `_lyricsResult.setValue(Result.box(Result.failure(error)))`, after it logs "loadLyrics failed: ".
 */
private val lyricsFailurePublish = method("playerViewModel.loadLyrics") {
    strings("loadLyrics failed: ")
}.point("loadLyricsFailed") { string("loadLyrics failed: ") }
    .next { invokeVirtual { paramCount(1); returns(Type.Void) } }

@Suppress("unused")
val externalLyrics = patch("External lyrics") {
    description(
        """
        Shows lyrics from LRCLIB, NetEase Cloud Music, KuGou, or VocaDB when VocaColle has none
        for a song or can't show them, and lets the lyrics button open for those songs. Songs are
        matched by the exact Niconico video on VocaDB. Off until turned on in the Reseam patches
        screen.
        """,
    )
    compatibleWith(VOCACOLLE)
    enabledByDefault(false)
    settings(vocacolleSettings, section(VocaColleStrings.sectionLyrics, LyricsSettings.externalLyrics, LyricsSettings.lyricsProviders))

    execute {
        ExternalLyrics.newSegment.implement {
            returnValue(
                newInstance(lyricsSegment.descriptor, "(Ljava/util/List;Ljava/lang/Integer;Ljava/lang/Integer;)V", param(0), param(1), param(2)),
            )
        }
        ExternalLyrics.newLyrics.implement {
            returnValue(newInstance(lyricsWithMetadata.descriptor, "(Ljava/lang/String;Ljava/util/List;Z)V", param(0), param(1), param(2)))
        }
        // The same LiveData, given a success instead: `setValue(Result.box(lyrics))`, where
        // `Result.success(x)` is x itself.
        val publish = lyricsFailurePublish.callee("lyricsResult.setValue")
        val boxResult = lyricsFailurePublish.previous { invokeStatic { paramCount(1) } }
            .callee("Result.box")
        ExternalLyrics.showLyrics.implement {
            param(0).cast(publish.owner).call(publish, call(boxResult, param(1)))
            returnVoid()
        }
        // The error class is the reason enum's outer class.
        val error = lyricsErrorReason.descriptor.substringBeforeLast('$') + ";"
        gate(LyricsSettings.externalLyrics) {
            // The watch info of the track that is starting: (id, title, duration, …).
            videoWatchMetadataInit.after { ExternalLyrics.prefetch(param(0), param(1), param(2)) }
            hasLyricsGetter().after {
                returnValue(ExternalLyrics.hasLyrics(thisObject.field(videoWatchMetadataId), result))
            }
            lyricsLoad.before { ExternalLyrics.requested(param(0)) }
            lyricsFailurePublish.captureArgumentAs("lyricsResult", 0).before {
                ExternalLyrics.lyricsFailed(capture("lyricsResult"))
            }
            lyricsLoad.points("lyricsError") { invokeDirect { owner(error); name("<init>") } }.forEach {
                captureArgumentAs("reason", 1).next { opcode(Opcode.THROW) }.before {
                    val reason = capture("reason").callVirtual("java.lang.Enum", "name", "()Ljava/lang/String;")
                    // By here Kotlin has reused the video id's register for the reason or the
                    // exception, so the id the request started with stands in.
                    val lyrics = ExternalLyrics.fallback(nullObject, reason)
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
