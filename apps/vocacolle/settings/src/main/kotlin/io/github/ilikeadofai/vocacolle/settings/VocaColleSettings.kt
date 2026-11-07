package io.github.ilikeadofai.vocacolle.settings

import app.reseam.patch.settings.SettingsGroup
import app.reseam.patch.settings.Vector
import app.reseam.patch.settings.toggle

/**
 * In-app switches. Keys are pinned so that renaming a property never drops a saved value.
 * Every switch starts off: the patches only change the app after the user opts in here.
 */
object AdSettings : SettingsGroup(VocaColleStrings) {
    val hideAppOpenAds by toggle(default = false, key = "ads.hide_app_open", icon = Vector.lucide("smartphone"))
    val hideDisplayAds by toggle(default = false, key = "ads.hide_display", icon = Vector.lucide("panels-top-left"))
    val skipAudioAds by toggle(default = false, key = "ads.skip_audio", icon = Vector.lucide("volume-x"))
    val hidePremiumPrompts by toggle(default = false, key = "ads.hide_premium_prompts", icon = Vector.lucide("badge-x"))
}
