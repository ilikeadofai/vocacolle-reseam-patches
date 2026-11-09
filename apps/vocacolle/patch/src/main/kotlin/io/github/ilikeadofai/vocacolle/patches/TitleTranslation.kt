package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.ExtMethod
import app.reseam.patch.MethodTarget
import app.reseam.patch.PatchRuntime
import app.reseam.patch.Type
import app.reseam.patch.addMethod
import app.reseam.patch.after
import app.reseam.patch.methods
import app.reseam.patch.points
import app.reseam.patch.appEntry
import app.reseam.patch.dex.ref
import app.reseam.patch.fieldTarget
import app.reseam.patch.klass
import app.reseam.patch.method
import app.reseam.patch.before
import app.reseam.patch.patch
import app.reseam.patch.settings.gate
import app.reseam.patch.settings.onAction
import app.reseam.patch.settings.section
import io.github.ilikeadofai.vocacolle.core.translate.bindings.LyricLayers
import io.github.ilikeadofai.vocacolle.core.translate.bindings.SearchTranslator
import io.github.ilikeadofai.vocacolle.core.translate.bindings.TitleTranslator
import io.github.ilikeadofai.vocacolle.core.translate.bindings.TranslateDescriptionClick
import io.github.ilikeadofai.vocacolle.settings.AiSettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings

/** `MediaDetailData(mediaId, title, description, …)`, what the work detail screen shows. */
internal val mediaDetailData = klass("mediaDetailData") { strings("MediaDetailData(mediaId=") }
internal val mediaDetailDataInit = method("mediaDetailData.<init>") {
    inClass(mediaDetailData)
    custom {
        name == "<init>" && parameterTypes.size == classDef.instanceFields.size &&
            parameterTypes.take(3) == listOf(Type.String, Type.String, Type.String)
    }
}

/** `VocacolleNiconicoInfo(title, articleUrl, updateTime)`: one notice in Settings. */
private val noticeInfo = klass("vocacolleNiconicoInfo") { strings("VocacolleNiconicoInfo(title=") }
private val noticeInfoInit = method("vocacolleNiconicoInfo.<init>") {
    inClass(noticeInfo)
    custom { name == "<init>" && parameterTypes.size == 3 && parameterTypes[0] == Type.String }
}

/** The Settings click handler that opens a notice's article: `(fragment, url) -> open(Uri.parse(url))`. */
private val openNoticeArticle = method("openNoticeArticle") {
    inClass(klass("jp.nicovideo.nicobox.ui.setting.SettingFragment"))
    params("jp.nicovideo.nicobox.ui.setting.SettingFragment", Type.String)
    calls { owner("android.net.Uri"); name("parse") }
}

/**
 * The app's media models, from the data layer to list items, by the start of their `toString()`.
 * Each is `(id, title, …)`. Play-history records are left out, because they are written to the
 * app's database, where a translated title would outlive the setting.
 */
private val TITLED_MODELS = listOf(
    "Media(id=", "Video(id=", "VideoItem(id=", "DefaultItem(mediaId=", "Single(mediaId=",
    "MiniPlayerSummary(mediaId=", "WakutkoolMylistVideo(id=", "VideoInfoDetail(videoId=",
)

/** Every constructor of those models that takes the id and then the title. */
private fun PatchRuntime.titledModelConstructors(): List<MethodTarget> {
    val owners = TITLED_MODELS.flatMap { prefix ->
        methods("titledModel $prefix") {
            strings(prefix, ", title=")
            name("toString")
        }.all.map { it.owner }
    }.distinct()
    check(owners.size >= TITLED_MODELS.size) { "only found ${owners.size} titled media models" }
    return owners.flatMap { owner ->
        methods("titledModel.<init> $owner") {
            inClass(klass(owner))
            custom { name == "<init>" && parameterTypes.size >= 2 && parameterTypes[0] == Type.String && parameterTypes[1] == Type.String }
        }.all
    }
}

/** The work detail screen's composable, found by its source file and Compose's trace strings. */
internal val mediaDetailScreen = methods("mediaDetailScreen") {
    stringsStartingWith("jp.nicovideo.nicobox.")
    custom { classDef.sourceFile == "MediaDetailScreen.kt" }
}

/** The description: the app's expandable text, called with 3 visible lines. */
internal val descriptionText = mediaDetailScreen.points("descriptionText") {
    invokeStatic { paramCount(10); returns(Type.Void) }
    argument(2) { literal(3) }
}

/**
 * The update block of `DetailDescriptionText`, an AndroidView around a TextView whose text is set
 * once, by its factory: `(…, context, …, view) -> Unit`, run on every change the block reads.
 */
private val descriptionViewUpdate = method("detailDescriptionText.update") {
    calls { name("setMovementMethod") }
    custom { classDef.sourceFile == "DetailDescriptionText.kt" && parameterTypes.size == 9 && parameterTypes[2] == "Landroid/content/Context;" }
}

/** Compose's `Text(text, modifier, color, …, style, composer, changed, changed1, defaults)`. */
private const val TEXT_PARAMS = 22

/**
 * A work's tag chips on the detail screen: the tag is the loop's element (`Iterator.next()`), shown by a
 * Text and passed, untouched, to the chip's click, which searches it.
 */
private val detailTagText = mediaDetailScreen.points("detailTagText") {
    invokeStatic { paramCount(TEXT_PARAMS); returns(Type.Void) }
    argument(0) { resultOf(Type.Object) }
}

/** The name on a popular or featured tag tile in Search; the click passes the tile's item. */
private val searchTagText = methods("searchTopTagContentItem") {
    stringsStartingWith("jp.nicovideo.nicobox.")
    custom { classDef.sourceFile == "SearchTopTagContentItem.kt" }
}.points("searchTagText") {
    invokeStatic { paramCount(TEXT_PARAMS); returns(Type.Void) }
    argument(0) { resultOf(Type.String) }
}

/** `LyricsSegment(lines, startMs, endMs)`: one timed group of lyric lines. */
private val lyricsSegmentModel = klass("lyricsSegmentModel") { strings("LyricsSegment(lines=") }

/** `LyricsWithMetadata(videoId, lyricSegments, hasTimeInformation)`: the lyrics the player shows. */
private val lyricsModel = klass("lyricsWithMetadata") { strings("LyricsWithMetadata(videoId=") }

/**
 * The lyrics list's row: it draws each string of the segment's `lines` as its own row,
 * highlighted with the segment, so layers added to that list are drawn like lyrics.
 */
private fun PatchRuntime.addLyricLayers() {
    val segment = lyricsSegmentModel.descriptor
    val lyrics = lyricsModel.descriptor
    // The row is an `itemsIndexed` item lambda, inlined from Compose: its class keeps Compose's
    // trace string and source file (LazyDsl.kt), and holds the lyrics it draws.
    val rowLines = methods("playerLyricsRow") {
        stringsStartingWith("androidx.compose.foundation.lazy.itemsIndexed.")
        custom { classDef.instanceFields.any { it.fieldType == lyrics } }
    }.points("lyricsRowLines") {
        invokeVirtual { owner(segment); returns("Ljava/util/List;") }
        then(within = 2) { invokeInterface { name("iterator") } }
    }.single()
    // The match is the loop's iterator() call; its receiver is the segment's lines. Two
    // switches decide the layers, so the extension reads them instead of one gate.
    rowLines.captureArgumentAs("lines", 0).before {
        capture("lines").assign(LyricLayers.lines(capture("lines")))
    }
    styleLyricLayers()
}

/** `LyricsLine(modifier, text, highlighted, onClick)`: one row, a Text and a 16 dp spacer. */
private val lyricsLine = methods("lyricsLine") { stringsStartingWith("jp.nicovideo.nicobox.ui.player.LyricsLine (") }

/** The spacer's 16 dp, as the float bits the app loads. */
private const val ROW_GAP_BITS = 0x41800000L

/**
 * Readings and translations drawn smaller and close to their line: the row's Text gets the
 * theme's 14 sp `normalStrong` style instead of its 22 sp `titleNormal`, and the spacer under
 * it a smaller height, both decided by the mark the extension put on the row's text.
 */
private fun PatchRuntime.styleLyricLayers() {
    val text = lyricsLine.points("lyricsLine.text") { invokeStatic { paramCount(TEXT_PARAMS); returns(Type.Void) } }.single()
    val textCall = text.callee("Text")
    val styleType = textCall.parameterTypes[17]
    val composerType = textCall.parameterTypes[18]
    // The theme's styles: `NicoBoxTheme.textStyles` is read just before, then its titleNormal.
    val titleGetter = lyricsLine.points("lyricsLine.titleStyle") {
        invokeVirtual { returns(styleType); paramCount(0) }
    }.single().callee("titleNormal")
    val styles = bytecode.findClass(titleGetter.owner) ?: error("no text styles class")
    val theme = lyricsLine.points("lyricsLine.textStyles") { invokeVirtual { returns(titleGetter.owner) } }.single().callee("textStyles")
    // R8 named the eight style getters in their properties' alphabetical order: middle,
    // middleStrong, normal, normalStrong, small, smallStrong, titleLarge, titleNormal.
    val getters = styles.virtualMethods.filter { it.returnType == styleType && it.parameterTypes.isEmpty() }.map { it.name }.sorted()
    check(getters.size == 8 && getters[7] == titleGetter.name) { "the text styles changed: $getters" }
    val normalStrongName = getters[3]
    val normalStrong = method("textStyles.normalStrong") {
        inClass(klass(titleGetter.owner))
        name(normalStrongName)
        paramCount(0)
    }
    val themeInstance = fieldTarget("NicoBoxTheme.INSTANCE") {
        val owner = bytecode.findClass(theme.owner) ?: error("no theme class")
        owner.staticFields.singleOrNull { it.fieldType == theme.owner }?.ref ?: error("the theme isn't an object")
    }
    LyricLayers.layerStyle.implement {
        returnValue(staticField(themeInstance).call(theme, param(0).cast(composerType), int(6)).call(normalStrong))
    }
    text.captureArgumentAs("text", 0).captureArgumentAs("style", 17).captureArgumentAs("composer", 18).before {
        capture("text").assign(LyricLayers.rowText(capture("text")))
        capture("style").assign(LyricLayers.rowStyle(capture("style"), capture("composer")).cast(styleType))
    }
    lyricsLine.points("lyricsLine.gap") {
        invokeStatic { paramCount(1); returns(Type.Float) }
        argument(0) { literal(ROW_GAP_BITS) }
    }.single().captureArgumentAs("gap", 0).before {
        capture("gap").assign(LyricLayers.rowGap(capture("gap")))
    }
}

/**
 * Where Niconico searches get their keyword: the video search (`/v2/search/video`) and the
 * playlist of its results (`/v1/playlist/search`), each `params.put("keyword", keyword)`.
 */
private val searchKeywords = methods("nicoSearchRequests") {
    strings("keyword")
    custom { classDef.sourceFile == "DefaultSearchService.kt" || classDef.sourceFile == "DefaultNvPlaylistService.kt" }
}.points("searchKeyword") {
    invokeVirtual { paramCount(2) }
    argument(1) { string("keyword") }
}

/** Korean and English searches go out in Japanese; the search box and history keep the original. */
private fun PatchRuntime.translateSearches() {
    val points = searchKeywords.all
    check(points.size == 2) { "expected the video and playlist search keywords, found ${points.size}" }
    gate(AiSettings.aiSearch) {
        points.forEach { point ->
            point.captureArgumentAs("keyword", 2).before {
                capture("keyword").assign(SearchTranslator.searchKeyword(capture("keyword")))
            }
        }
    }
}

private fun PatchRuntime.translateTags() {
    val detail = detailTagText.single()
    val search = searchTagText.single()
    check(detail.callee("detailText").descriptor == search.callee("searchText").descriptor) {
        "the tag chips no longer use the same Text composable"
    }
    gate(AiSettings.aiTags) {
        listOf(detail, search).forEach { text ->
            text.captureArgumentAs("text", 0).before {
                capture("text").assign(TitleTranslator.displayTag(capture("text")))
            }
        }
    }
}

/** Compose's `MutableState` interface, with `getValue()` and `setValue(value)`. */
internal val mutableState = klass("MutableState") {
    sourceFile("SnapshotState.kt")
    custom { isInterface && methods.any { it.name == "setValue" } && methods.any { it.name == "getValue" } }
}

/** The app's text button: `(modifier, onClick, enabled, color, …, text, …, composer, changed, defaults)`. */
internal val appButton = method("appButton") {
    stringsStartingWith("jp.nicovideo.nicobox.")
    custom {
        classDef.sourceFile == "Buttons.kt" && parameterTypes.size == 13 &&
            parameterTypes[3] == Type.Long && parameterTypes[7] == Type.String
    }
}

// Defaults mask: modifier, enabled, the second color, and the last two style arguments.
internal const val BUTTON_DEFAULTS = 789

/** The button's text color: VocaColle's cyan, as a packed sRGB Compose color. */
internal const val BUTTON_COLOR = (0xFF00A4E4L shl 32)

/**
 * Implements an extension's `newState(value)`, `readState(state)`, and `writeState(state, value)`
 * with Compose's `MutableState`, made by the app's own `mutableStateOf`: the description's
 * expanded flag is one, in the detail screen, and that call gives the factory and the type.
 */
internal fun PatchRuntime.implementComposeState(new: ExtMethod, read: ExtMethod, write: ExtMethod) {
    val stateFactory = mediaDetailScreen.points("mutableStateOf") {
        invokeStatic { paramCount(4); returns(mutableState.descriptor) }
    }.all.firstOrNull()?.callee("mutableStateOf") ?: error("the detail screen makes no Compose state")
    new.implement { returnValue(call(stateFactory, param(0), nullObject, int(2), nullObject)) }
    read.implement {
        returnValue(param(0).cast(mutableState.descriptor).callInterface(mutableState.descriptor, "getValue", "()Ljava/lang/Object;"))
    }
    write.implement {
        param(0).cast(mutableState.descriptor).callInterface(mutableState.descriptor, "setValue", "(Ljava/lang/Object;)V", param(1))
        returnVoid()
    }
}

/** The click type of the app's button: an R8-renamed `Function0`, and its `invoke`. */
internal fun PatchRuntime.buttonClick(): Pair<String, app.reseam.patch.dex.Method> {
    val clickType = bytecode.findClass(appButton.parameterTypes[1]) ?: error("the app's button has no click type")
    val invoke = clickType.virtualMethods.singleOrNull()
    check(clickType.isInterface && invoke != null && invoke.returnType == Type.Object) {
        "the app's button no longer takes a Function0 click"
    }
    return clickType.descriptor to invoke
}

private fun PatchRuntime.addTranslateButton() {
    val description = descriptionText.single()
    val button = appButton
    val (clickType, invoke) = buttonClick()
    TranslateDescriptionClick.target.classDef.addInterface(clickType)
    TranslateDescriptionClick.target.addMethod(invoke.name, invoke.proto) {
        TitleTranslator.showDescriptionTranslation()
        returnNull()
    }
    implementComposeState(TitleTranslator.newState, TitleTranslator.readState, TitleTranslator.writeState)
    gate(AiSettings.aiDescriptions) {
        descriptionViewUpdate.before { TitleTranslator.updateDescriptionView(param(8)) }
        // Reading the state while drawing makes the screen redraw when the translation arrives.
        description.captureArgumentAs("text", 1).before {
            capture("text").assign(TitleTranslator.shownDescription(capture("text")))
        }
        description.captureArgumentAs("composer", 7).after {
            call(
                button,
                nullObject, newInstance(TranslateDescriptionClick.descriptor), bool(true), long(BUTTON_COLOR), long(0),
                nullObject, nullObject, TitleTranslator.translateLabel(), nullObject, nullObject,
                capture("composer"), int(0), int(BUTTON_DEFAULTS),
            )
        }
    }
}

@Suppress("unused")
val titleTranslation = patch("Translate songs, tags, and lyrics") {
    description(
        """
        Translates song titles, tags, descriptions, notices, and lyrics into Korean or English, with
        Google Translate or your own AI key. Off until you turn each part on in the app.
        """,
    )
    compatibleWith(VOCACOLLE)
    settings(
        vocacolleSettings,
        section(translationPage, VocaColleStrings.sectionTranslateWhat, AiSettings.aiTitles, AiSettings.aiTags, AiSettings.aiDescriptions, AiSettings.aiNotices),
        section(
            translationPage, VocaColleStrings.sectionTranslateService,
            AiSettings.aiLanguage, AiSettings.aiProvider, AiSettings.aiBaseUrl, AiSettings.aiModel, AiSettings.aiApiKey,
        ),
        section(translationPage, VocaColleStrings.sectionSearch, AiSettings.aiSearch),
        section(lyricsPage, VocaColleStrings.sectionLyricsLayers, AiSettings.aiLyricsRomaji, AiSettings.aiLyricsScript, AiSettings.aiLyricsTranslation),
    )

    execute {
        appEntry { TitleTranslator.init(application) }
        onAction(AiSettings.aiApiKey) { TitleTranslator.editApiKey(activity) }
        gate(AiSettings.aiTitles) {
            playerVideoInfoInit.before { param(1).assign(TitleTranslator.displayTitle(param(0), param(1))) }
            mediaDetailDataInit.before { param(1).assign(TitleTranslator.displayTitle(param(0), param(1))) }
        }
        val models = titledModelConstructors()
        log.info("Translating titles in ${models.size} media model constructors")
        gate(AiSettings.aiTitles) {
            models.forEach { it.before { param(1).assign(TitleTranslator.displayTitle(param(0), param(1))) } }
        }
        gate(AiSettings.aiNotices) {
            noticeInfoInit.before { param(0).assign(TitleTranslator.displayNoticeTitle(param(0))) }
            openNoticeArticle.before { param(1).assign(TitleTranslator.noticeArticleUrl(param(1))) }
        }
        gate(AiSettings.aiDescriptions) {
            mediaDetailDataInit.before { TitleTranslator.rememberDescription(param(2)) }
        }
        addTranslateButton()
        translateTags()
        translateSearches()
        addLyricLayers()
    }
}
