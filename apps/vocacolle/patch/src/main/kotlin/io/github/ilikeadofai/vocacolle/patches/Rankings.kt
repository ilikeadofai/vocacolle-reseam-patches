package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.Type
import app.reseam.patch.klass
import app.reseam.patch.method
import app.reseam.patch.methods
import app.reseam.patch.patch
import app.reseam.patch.points
import app.reseam.patch.settings.gate
import app.reseam.patch.settings.section
import io.github.ilikeadofai.vocacolle.core.bindings.RankingPages
import io.github.ilikeadofai.vocacolle.settings.RankingSettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings

/** `NicoBoxRanking(setting, videos, hasNext)`: one page of a ranking. */
private val rankingPage = klass("nicoBoxRanking") { strings("NicoBoxRanking(setting=") }

/** The ranking client's `(session, rankingId, page, pageSize, sensitiveContents) -> NicoBoxRanking`. */
private val rankingRequest = method("rankingRequest") {
    strings("/v1/ranking/nicobox/%d")
    paramCount(5)
}

/** The ranking screen's loader: it asks for page 1 of 100 and shows only that. */
private val rankingScreenRequest = methods("defaultRankingDataLoader") {
    custom { classDef.sourceFile == "DefaultRankingDataLoader.kt" }
}.points("rankingScreenRequest") { calls(rankingRequest) }

@Suppress("unused")
val fullRankings = patch("Full rankings") {
    description(
        """
        Shows every page of a ranking, up to 300 songs, instead of only the top 100. The extra
        pages come from VocaColle's own ranking service. Can be turned off in the Reseam patches
        screen.
        """,
    )
    compatibleWith(VOCACOLLE)
    settings(vocacolleSettings, section(VocaColleStrings.sectionRanking, RankingSettings.fullRanking))

    execute {
        val page = rankingPage.descriptor
        check(rankingRequest.returnType == page) { "the ranking request no longer returns NicoBoxRanking" }
        val pageClass = bytecode.findClass(page) ?: error("NicoBoxRanking isn't in the app")
        val settingType = pageClass.instanceFields.map { it.fieldType }
            .singleOrNull { it != "Ljava/util/List;" && it != Type.Boolean }
            ?: error("NicoBoxRanking no longer holds one setting, the videos, and hasNext")
        val (session, _, _, _, sensitive) = rankingRequest.parameterTypes

        RankingPages.fetchPage.implement {
            returnValue(
                param(0).cast(rankingRequest.owner).call(
                    rankingRequest, param(1).cast(session), param(2), param(3), param(4), param(5).cast(sensitive),
                ),
            )
        }
        RankingPages.videos.implement { returnValue(param(0).cast(page).fieldOfType("Ljava/util/List;")) }
        RankingPages.hasNext.implement { returnValue(param(0).cast(page).fieldOfType(Type.Boolean)) }
        RankingPages.withVideos.implement {
            returnValue(
                newInstance(
                    page, "($settingType" + "Ljava/util/List;Z)V",
                    param(0).cast(page).fieldOfType(settingType), param(1), param(2),
                ),
            )
        }

        gate(RankingSettings.fullRanking) {
            rankingScreenRequest.single()
                .captureArgumentAs("service", 0)
                .captureArgumentAs("session", 1)
                .captureArgumentAs("rankingId", 2)
                .captureArgumentAs("page", 3)
                .captureArgumentAs("pageSize", 4)
                .captureArgumentAs("sensitive", 5)
                .next { resultOf(page) }
                .captureAs("ranking")
                .after {
                    capture("ranking").assign(
                        RankingPages.extend(
                            capture("ranking"), capture("service"), capture("session"), capture("rankingId"),
                            capture("page"), capture("pageSize"), capture("sensitive"),
                        ).cast(page),
                    )
                }
        }
    }
}
