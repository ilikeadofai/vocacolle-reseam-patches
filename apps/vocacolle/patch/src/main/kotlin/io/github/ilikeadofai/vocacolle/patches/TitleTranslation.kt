package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.Type
import app.reseam.patch.appEntry
import app.reseam.patch.klass
import app.reseam.patch.method
import app.reseam.patch.before
import app.reseam.patch.patch
import app.reseam.patch.settings.gate
import app.reseam.patch.settings.onAction
import app.reseam.patch.settings.section
import io.github.ilikeadofai.vocacolle.core.translate.bindings.TitleTranslator
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
        gate(AiSettings.aiNotices) {
            noticeInfoInit.before { param(0).assign(TitleTranslator.displayNoticeTitle(param(0))) }
            openNoticeArticle.before { param(1).assign(TitleTranslator.noticeArticleUrl(param(1))) }
        }
        gate(AiSettings.aiDescriptions) {
            mediaDetailDataInit.before { param(2).assign(TitleTranslator.displayDescription(param(2))) }
        }
    }
}
