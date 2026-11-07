package io.github.ilikeadofai.vocacolle.settings

import app.reseam.patch.settings.SettingsGroup
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
