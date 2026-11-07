@file:JvmName("ServerLabels")

package io.github.ilikeadofai.vocacolle.core

import app.reseam.runtime.Export

/**
 * Display-only translations for the few labels VocaColle receives from its server.
 *
 * Only exact, known values are translated; anything else is returned unchanged, and the original
 * values stay in the app's models, so requests, caches, and search keep using them.
 */

/** The language the "Translate UI" patch was run with: `ko` or `en`. The patch writes the body. */
@Export
external fun language(): String

private val rankingGenres = mapOf(
    "音楽・サウンド" to ("음악·사운드" to "Music & Sound"),
    "ニコニコインディーズ" to ("니코니코 인디즈" to "Niconico Indies"),
    "演奏してみた" to ("연주해 보았다" to "Played It"),
    "歌ってみた" to ("불러 보았다" to "Sang It"),
    "音楽" to ("음악" to "Music"),
    "東方" to ("동방" to "Touhou"),
    "ラジオ" to ("라디오" to "Radio"),
    "総合" to ("종합" to "All"),
)

private val pushTopics = mapOf(
    "vocacolle_all" to ("푸시 알림 허용" to "Allow push notifications"),
    "vocacolle_info" to ("운영팀 공지" to "News from the team"),
    "vocacolle_feature" to ("보카코레 기능과 팁" to "VocaColle features and tips"),
    "vocacolle_recommend" to ("추천 작품" to "Recommended works"),
)

private fun pick(pair: Pair<String, String>): String = if (language() == "en") pair.second else pair.first

/** A ranking tab's genre label. */
@Export
fun ranking(label: String?): String? {
    if (label == null) return null
    val key = label.replace("​", "").replace("﻿", "").trim()
    return rankingGenres[key]?.let(::pick) ?: label
}

/** A push-notification topic's title, chosen by the topic's stable server name. */
@Export
fun pushTitle(name: String?, title: String?): String? {
    if (name == null || title == null) return title
    return pushTopics[name]?.let(::pick) ?: title
}
