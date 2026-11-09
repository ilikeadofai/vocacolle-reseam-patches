package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.PatchRuntime
import app.reseam.patch.Type
import app.reseam.patch.after
import app.reseam.patch.before
import app.reseam.patch.klass
import app.reseam.patch.method
import app.reseam.patch.patch
import app.reseam.patch.points
import io.github.ilikeadofai.vocacolle.core.bindings.ServerLabels

private const val LANGUAGE_KOREAN = "ko"
private const val LANGUAGE_ENGLISH = "en"

/**
 * One reviewed row of the catalog: a string resource, the Japanese text VocaColle 7.48.0 ships for
 * it, and its translations.
 */
private class CatalogRow(val name: String, val japanese: String, val korean: String, val english: String) {
    fun text(language: String) = if (language == LANGUAGE_ENGLISH) english else korean
}

private object Catalog

private fun unescape(field: String): String {
    if ('\\' !in field) return field
    val out = StringBuilder(field.length)
    var i = 0
    while (i < field.length) {
        val c = field[i]
        if (c == '\\' && i + 1 < field.length) {
            out.append(
                when (val next = field[i + 1]) {
                    'n' -> '\n'
                    't' -> '\t'
                    else -> next
                },
            )
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

private fun loadCatalog(): List<CatalogRow> {
    val stream = Catalog::class.java.getResourceAsStream("/vocacolle/strings.tsv")
        ?: error("the translation catalog is missing from the patch jar")
    return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val fields = line.split('\t')
                check(fields.size == 4) { "malformed catalog row: ${line.take(80)}" }
                CatalogRow(fields[0], unescape(fields[1]), unescape(fields[2]), unescape(fields[3]))
            }
            .toList()
    }
}

/** Japanese literals compiled into the app's code, with their translations (ko, en). */
private val hardcodedLiterals = mapOf(
    "リンク先に遷移する" to ("외부 링크로 이동" to "Open link"),
    "ダウンロード" to ("다운로드" to "Download"),
    "初音ミクも登場する新作リズムゲーム" to ("하츠네 미쿠도 등장하는 신작 리듬 게임" to "A new rhythm game featuring Hatsune Miku"),
    "プロジェクトセカイ カラフルステージ！ feat. 初音ミク" to
        ("프로젝트 세카이 컬러풀 스테이지! feat. 하츠네 미쿠" to "Project SEKAI COLORFUL STAGE! feat. Hatsune Miku"),
    "「ロキ」「シャルル」「Tell Your World」「ハッピーシンセサイザ」などの名曲を3DMV付きで多数収録！" +
        "SEGA×Colorful Paletteが贈る、誰でもかんたんに楽しめるリズムゲーム。" to (
        "‘로키’, ‘샤를’, ‘Tell Your World’, ‘해피 신시사이저’ 등의 명곡을 3D MV와 함께 다수 수록! " +
            "SEGA×Colorful Palette가 선사하는 누구나 쉽고 재미있게 즐길 수 있는 리듬 게임." to
            "Featuring many hit songs, including “Roki,” “Charles,” “Tell Your World,” and “Happy Synthesizer,” " +
            "with 3D music videos! A rhythm game from SEGA × Colorful Palette that anyone can easily enjoy."
        ),
)

/** The WebView "open this link?" dialog, whose buttons are English literals. */
private val externalLinkDialog = method("externalLinkDialog") {
    strings("リンク先に遷移する", "OK", "Cancel")
    params("android.webkit.WebView", Type.String)
    returns(Type.Boolean)
}

/** `RankingSettingManager.labelAt(position, context)`: the title of each ranking tab. */
private val rankingSettings = klass("rankingSettingManager") {
    strings("RankingSettingManager(settings=")
}
private val rankingTabLabel = method("rankingTabLabel") {
    inClass(rankingSettings)
    params(Type.Int, "android.content.Context")
    returns(Type.String)
}

/** `NicoPushTopic(title, description, category, name)`: a push-notification topic. */
private val pushTopic = klass("nicoPushTopic") {
    sourceFile("NicoPushTopic.kt")
    custom { instanceFields.size == 4 && instanceFields.all { it.fieldType == Type.String } }
}
private val pushTopicInit = pushTopic.method("<init>") {
    params(Type.String, Type.String, Type.String, Type.String)
}

private fun PatchRuntime.translateResources(language: String) {
    var translated = 0
    val stale = mutableListOf<String>()
    for (row in loadCatalog()) {
        val id = resources.id("string", row.name)
        if (id == null) {
            stale += row.name
            continue
        }
        // A string whose Japanese changed in an app update keeps its new Japanese text rather
        // than showing a translation of what it used to say.
        if (resources.getString(id) != row.japanese) {
            stale += row.name
            continue
        }
        // A fresh pool entry per resource: aapt shares one pool string between resources with the
        // same text, and changing the shared string would translate all of them at once.
        val index = resources.poolAdd(row.text(language)) ?: error("couldn't add ${row.name} to the string pool")
        resources.replaceEntry(id, index)
        translated++
    }
    if (language == LANGUAGE_KOREAN && resources.exists("array", "exo_controls_playback_speeds")) {
        val speeds = resources.getArray("exo_controls_playback_speeds")
        resources.setStringArray("exo_controls_playback_speeds", speeds.map { if (it == "Normal") "기본" else it })
    }
    log.info("Translated $translated string resources; left ${stale.size} unchanged")
    if (stale.isNotEmpty()) log.debug("Unchanged: ${stale.joinToString()}")
}

private fun PatchRuntime.translateCode(language: String) {
    // The buttons are generic English words, so they're only replaced inside this one dialog,
    // before the dialog's Japanese title is replaced app-wide and stops identifying it.
    val (ok, cancel) = if (language == LANGUAGE_ENGLISH) "OK" to "Cancel" else "확인" to "취소"
    externalLinkDialog.points { string("OK") }.forEach {
        captureAs("ok").after { capture("ok").assign(string(ok)) }
    }
    externalLinkDialog.points { string("Cancel") }.forEach {
        captureAs("cancel").after { capture("cancel").assign(string(cancel)) }
    }
    for ((japanese, translations) in hardcodedLiterals) {
        val text = if (language == LANGUAGE_ENGLISH) translations.second else translations.first
        val replaced = bytecode.replaceAllStrings(japanese, text)
        check(replaced > 0) { "the literal \"${japanese.take(20)}\" is no longer in the app" }
    }
}

private fun PatchRuntime.translateServerLabels(language: String) {
    ServerLabels.language.implement { returnValue(string(language)) }
    rankingTabLabel.after { returnValue(ServerLabels.ranking(result)) }
    pushTopicInit.before { param(0).assign(ServerLabels.pushTitle(param(3), param(0))) }
}

@Suppress("unused")
val translateUi = patch("Translate UI") {
    description(
        """
        Shows VocaColle in Korean or English (the language option). Song titles and anything sent to
        the server stay in Japanese.
        """,
    )
    compatibleWith(VOCACOLLE)
    val language = stringOption(
        "language",
        title = "Language",
        description = "ko for Korean, en for English.",
        default = LANGUAGE_KOREAN,
        validValues = listOf(LANGUAGE_KOREAN, LANGUAGE_ENGLISH),
    )

    execute {
        val target = options[language]
        translateResources(target)
        translateCode(target)
        translateServerLabels(target)
    }
}
