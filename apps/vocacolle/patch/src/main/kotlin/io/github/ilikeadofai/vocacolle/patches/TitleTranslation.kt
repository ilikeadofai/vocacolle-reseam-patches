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

@Suppress("unused")
val titleTranslation = patch("Title and description translation") {
    description(
        """
        Translates Japanese song titles in the player and work descriptions into Korean or English, with Google
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
        gate(AiSettings.aiDescriptions) {
            mediaDetailDataInit.before { param(2).assign(TitleTranslator.displayDescription(param(2))) }
        }
    }
}
