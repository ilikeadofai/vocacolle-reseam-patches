package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.MethodTarget
import app.reseam.patch.PatchRuntime
import app.reseam.patch.Type
import app.reseam.patch.addMethod
import app.reseam.patch.after
import app.reseam.patch.methods
import app.reseam.patch.points
import app.reseam.patch.appEntry
import app.reseam.patch.klass
import app.reseam.patch.method
import app.reseam.patch.before
import app.reseam.patch.patch
import app.reseam.patch.settings.gate
import app.reseam.patch.settings.onAction
import app.reseam.patch.settings.section
import io.github.ilikeadofai.vocacolle.core.translate.bindings.TitleTranslator
import io.github.ilikeadofai.vocacolle.core.translate.bindings.TranslateDescriptionClick
import io.github.ilikeadofai.vocacolle.settings.AiSettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings

/** `MediaDetailData(mediaId, title, description, …)`, what the work detail screen shows. */
private val mediaDetailData = klass("mediaDetailData") { strings("MediaDetailData(mediaId=") }
private val mediaDetailDataInit = method("mediaDetailData.<init>") {
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
private val mediaDetailScreen = methods("mediaDetailScreen") {
    stringsStartingWith("jp.nicovideo.nicobox.")
    custom { classDef.sourceFile == "MediaDetailScreen.kt" }
}

/** The description: the app's expandable text, called with 3 visible lines. */
private val descriptionText = mediaDetailScreen.points("descriptionText") {
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

/** Compose's `MutableState` interface, with `getValue()` and `setValue(value)`. */
private val mutableState = klass("MutableState") {
    sourceFile("SnapshotState.kt")
    custom { isInterface && methods.any { it.name == "setValue" } && methods.any { it.name == "getValue" } }
}

/** The app's text button: `(modifier, onClick, enabled, color, …, text, …, composer, changed, defaults)`. */
private val appButton = method("appButton") {
    stringsStartingWith("jp.nicovideo.nicobox.")
    custom {
        classDef.sourceFile == "Buttons.kt" && parameterTypes.size == 13 &&
            parameterTypes[3] == Type.Long && parameterTypes[7] == Type.String
    }
}

// Defaults mask: modifier, enabled, the second color, and the last two style arguments.
private const val BUTTON_DEFAULTS = 789

/** The button's text color: VocaColle's cyan, as a packed sRGB Compose color. */
private const val BUTTON_COLOR = (0xFF00A4E4L shl 32)

private fun PatchRuntime.addTranslateButton() {
    val description = descriptionText.single()
    val button = appButton
    val clickType = bytecode.findClass(button.parameterTypes[1]) ?: error("the app's button has no click type")
    val invoke = clickType.virtualMethods.singleOrNull()
    check(clickType.isInterface && invoke != null && invoke.returnType == Type.Object) {
        "the app's button no longer takes a Function0 click"
    }
    TranslateDescriptionClick.target.classDef.addInterface(clickType.descriptor)
    TranslateDescriptionClick.target.addMethod(invoke.name, invoke.proto) {
        TitleTranslator.showDescriptionTranslation()
        returnNull()
    }
    // The description's own expanded flag is a Compose state made by the app's mutableStateOf, in
    // the same screen; that call gives the factory and the state type for the translation's state.
    val stateFactory = mediaDetailScreen.points("mutableStateOf") {
        invokeStatic { paramCount(4); returns(mutableState.descriptor) }
    }.all.firstOrNull()?.callee("mutableStateOf") ?: error("the detail screen makes no Compose state")
    TitleTranslator.newState.implement { returnValue(call(stateFactory, param(0), nullObject, int(2), nullObject)) }
    TitleTranslator.readState.implement {
        returnValue(param(0).cast(mutableState.descriptor).callInterface(mutableState.descriptor, "getValue", "()Ljava/lang/Object;"))
    }
    TitleTranslator.writeState.implement {
        param(0).cast(mutableState.descriptor).callInterface(mutableState.descriptor, "setValue", "(Ljava/lang/Object;)V", param(1))
        returnVoid()
    }
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
val titleTranslation = patch("Content translation") {
    description(
        """
        Translates Japanese song titles, work descriptions, and notices into Korean or English, with Google
        Translate (no key) or an OpenAI-compatible API and your own key. Off until turned on in
        the Reseam patches screen; titles are then sent to the chosen service.
        """,
    )
    compatibleWith(VOCACOLLE)
    enabledByDefault(false)
    settings(
        vocacolleSettings,
        section(
            VocaColleStrings.sectionAi,
            AiSettings.aiTitles,
            AiSettings.aiDescriptions,
            AiSettings.aiNotices,
            AiSettings.aiLanguage,
            AiSettings.aiProvider,
            AiSettings.aiBaseUrl,
            AiSettings.aiModel,
            AiSettings.aiApiKey,
        ),
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
    }
}
