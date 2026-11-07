package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.appEntry
import app.reseam.patch.before
import app.reseam.patch.patch
import app.reseam.patch.settings.gate
import app.reseam.patch.settings.onAction
import app.reseam.patch.settings.section
import io.github.ilikeadofai.vocacolle.core.translate.bindings.TitleTranslator
import io.github.ilikeadofai.vocacolle.settings.AiSettings
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings

@Suppress("unused")
val titleTranslation = patch("Title translation") {
    description(
        """
        Translates Japanese song titles in the player into Korean or English, with Google
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
        }
    }
}
