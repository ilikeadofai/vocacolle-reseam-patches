package io.github.ilikeadofai.vocacolle.patches

import app.reseam.patch.settings.SettingsPage
import app.reseam.patch.settings.Vector
import io.github.ilikeadofai.vocacolle.settings.VocaColleStrings

/*
 * The Reseam patches screen: My library and Ads on the screen itself, everything else on one
 * page per topic, so each page holds a handful of switches. A page shows only when a patch that
 * ran puts something on it.
 */

val translationPage = SettingsPage(
    "translation", VocaColleStrings.pageTranslation, order = 10,
    summary = VocaColleStrings.pageTranslationSummary, icon = Vector.lucide("languages"),
)

val lyricsPage = SettingsPage(
    "lyrics", VocaColleStrings.pageLyrics, order = 20,
    summary = VocaColleStrings.pageLyricsSummary, icon = Vector.lucide("mic-vocal"),
)

val songInfoPage = SettingsPage(
    "song_info", VocaColleStrings.pageSongInfo, order = 30,
    summary = VocaColleStrings.pageSongInfoSummary, icon = Vector.lucide("info"),
)

val toolsPage = SettingsPage(
    "tools", VocaColleStrings.pageTools, order = 40,
    summary = VocaColleStrings.pageToolsSummary, icon = Vector.lucide("wrench"),
)

val adsPage = SettingsPage(
    "ads", VocaColleStrings.pageAds, order = 5,
    summary = VocaColleStrings.pageAdsSummary, icon = Vector.lucide("badge-x"),
)
