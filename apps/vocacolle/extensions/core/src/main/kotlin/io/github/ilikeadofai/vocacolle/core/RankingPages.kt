@file:JvmName("RankingPages")

package io.github.ilikeadofai.vocacolle.core

import android.util.Log
import app.reseam.runtime.Export
import io.github.ilikeadofai.vocacolle.settings.RankingSettings

private const val TAG = "VocaColleRanking"

/** The ranking service serves three pages of 100 and answers `hasNext = false` on the last. */
private const val MAX_PAGES = 3

/** One page of a ranking, from the app's own ranking client: `(service, session, id, page, pageSize, sensitive)`. */
@Export
external fun fetchPage(service: Any?, session: Any?, rankingId: Int, page: Int?, pageSize: Int?, sensitive: Any?): Any?

@Export
external fun videos(ranking: Any?): List<Any?>?

@Export
external fun hasNext(ranking: Any?): Boolean

/** The ranking with [videos] in place of its own, and [hasNext]. */
@Export
external fun withVideos(ranking: Any?, videos: List<Any?>, hasNext: Boolean): Any?

/** `NvVideo(videoId=sm…, …)`: a data class, so its id leads its string. */
private fun videoId(video: Any?): String? =
    video?.toString()?.substringAfter("videoId=", "")?.substringBefore(',')?.takeIf { it.isNotEmpty() }

/**
 * The ranking screen's first page, [ranking], with the pages after it appended. The app asks
 * for page 1 only and never reads `hasNext`. Runs where the app's own request ran, a background
 * coroutine, so the extra pages are fetched the same way. A page that fails ends the list there.
 */
@Export
fun extend(ranking: Any?, service: Any?, session: Any?, rankingId: Int, page: Int?, pageSize: Int?, sensitive: Any?): Any? {
    if (ranking == null || !RankingSettings.fullRanking.value) return ranking
    if ((page ?: 1) != 1 || !hasNext(ranking)) return ranking
    val all = ArrayList<Any?>(videos(ranking) ?: return ranking)
    // Ranks move between requests; a video that moved down a page shows only once.
    val seen = all.mapNotNullTo(HashSet()) { videoId(it) }
    var more = true
    var next = 2
    while (more && next <= MAX_PAGES) {
        val result = try {
            fetchPage(service, session, rankingId, next, pageSize, sensitive)
        } catch (failure: Throwable) {
            Log.d(TAG, "ranking $rankingId page $next failed: ${failure.javaClass.simpleName}")
            null
        } ?: break
        videos(result)?.forEach { video -> if (videoId(video)?.let { seen.add(it) } != false) all += video }
        more = hasNext(result)
        next++
    }
    Log.i(TAG, "ranking $rankingId: ${all.size} videos from ${next - 1} pages")
    return withVideos(ranking, all, more)
}
