package io.github.ilikeadofai.vocacolle.settings

import app.reseam.patch.settings.Choice
import app.reseam.patch.settings.SettingsGroup
import app.reseam.patch.settings.action
import app.reseam.patch.settings.choice
import app.reseam.patch.settings.text
import app.reseam.patch.settings.Vector
import app.reseam.patch.settings.toggle

/**
 * In-app switches. Keys are pinned so that renaming a property never drops a saved value.
 * Ad switches start on; each can be turned off in the Reseam patches screen.
 */
object AdSettings : SettingsGroup(VocaColleStrings) {
    val hideAppOpenAds by toggle(default = true, key = "ads.hide_app_open", icon = Vector.lucide("smartphone"))
    val hideDisplayAds by toggle(default = true, key = "ads.hide_display", icon = Vector.lucide("panels-top-left"))
    val skipAudioAds by toggle(default = true, key = "ads.skip_audio", icon = Vector.lucide("volume-x"))
    val hidePremiumPrompts by toggle(default = true, key = "ads.hide_premium_prompts", icon = Vector.lucide("badge-x"))
}

/** Metadata from VocaDB. Off by default, because turning it on sends video ids to vocadb.net. */
object MetadataSettings : SettingsGroup(VocaColleStrings) {
    val vocadbTitles by toggle(default = false, key = "metadata.vocadb_titles", icon = Vector.lucide("languages"))
    val shareYoutube by toggle(default = false, key = "metadata.share_youtube", icon = Vector.lucide("share-2"))
}

/**
 * Song-title translation: Google Translate's web endpoint by default, or any OpenAI-compatible
 * chat completions endpoint with the user's own key. The key is not a setting: it lives
 * encrypted with the Android Keystore, so it never ends up in a settings export.
 */
object AiSettings : SettingsGroup(VocaColleStrings) {
    val aiTitles by toggle(default = false, key = "ai.titles", icon = Vector.lucide("sparkles"))
    val aiDescriptions by toggle(default = false, key = "ai.descriptions", icon = Vector.lucide("file-text"))
    val aiTags by toggle(default = false, key = "ai.tags", icon = Vector.lucide("tag"))
    val aiLyricsRomaji by toggle(default = false, key = "ai.lyrics_romaji", icon = Vector.lucide("languages"))
    val aiLyricsTranslation by toggle(default = false, key = "ai.lyrics_translation", icon = Vector.lucide("captions"))
    val aiNotices by toggle(default = false, key = "ai.notices", icon = Vector.lucide("megaphone"))
    val aiLanguage by choice(
        default = "auto",
        key = "ai.language",
        choices = listOf(
            Choice("auto", VocaColleStrings.aiLanguageAuto),
            Choice("ko", VocaColleStrings.aiLanguageKo),
            Choice("en", VocaColleStrings.aiLanguageEn),
        ),
        icon = Vector.lucide("languages"),
    )
    val aiProvider by choice(
        default = "google",
        key = "ai.provider",
        choices = listOf(
            Choice("google", VocaColleStrings.aiProviderGoogle),
            Choice("openai", VocaColleStrings.aiProviderOpenai),
        ),
        icon = Vector.lucide("server"),
    )
    val aiBaseUrl by text(default = "https://api.openai.com/v1", key = "ai.base_url", icon = Vector.lucide("link"))
    val aiModel by text(default = "", key = "ai.model", icon = Vector.lucide("cpu"))
    val aiApiKey by action(key = "ai.api_key", icon = Vector.lucide("key-round"))
}

/** Lyrics from public sources. Off by default, because it sends video ids and song names out. */
object LyricsSettings : SettingsGroup(VocaColleStrings) {
    val externalLyrics by toggle(default = false, key = "lyrics.external", icon = Vector.lucide("mic-vocal"))
    val lyricsProviders by text(default = "lrclib,netease,kugou,vocadb", key = "lyrics.providers", icon = Vector.lucide("list-ordered"))
}

/** Rankings: the pages of a ranking past the first, which the app never asks for. */
object RankingSettings : SettingsGroup(VocaColleStrings) {
    val fullRanking by toggle(default = true, key = "ranking.full", icon = Vector.lucide("list-ordered"))
}
