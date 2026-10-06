package com.arflix.tv.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time copy of watched marks made in Xadarr into Plex (Joe, 2026-10-05). Until Trakt was
 * switched off (TRAKT_ENABLED), Details/Home "Mark watched" only reached Trakt, which the
 * guide and Continue Watching never read, so those marks never counted. Xadarr kept its own
 * on-device copy (TraktRepository's local watched snapshot); this pushes it to Plex once.
 *
 * Only keys that name the show by TMDB id ("show_tmdb:<id>:<season>:<episode>") and movie
 * TMDB ids can be mapped. Keys downloaded from Trakt by Trakt's own episode id can't be
 * without Trakt, and are things actually watched, which Plex already has.
 */
@Singleton
class PlexWatchedMigration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val traktRepository: TraktRepository,
    private val homeServerRepository: HomeServerRepository,
    private val mediaRepository: MediaRepository,
) {
    private val prefs get() = context.getSharedPreferences("plex_watched_migration", Context.MODE_PRIVATE)

    /** True when it ran and marked something, so the guide should reload. */
    suspend fun runOnce(): Boolean {
        if (prefs.getBoolean(DONE_KEY, false)) return false
        traktRepository.initializeWatchedCache()
        val showKey = Regex("""^show_tmdb:(\d+):(\d+):(\d+)$""")
        val byShow = traktRepository.getWatchedEpisodesFromCache()
            .mapNotNull { showKey.matchEntire(it)?.destructured }
            .groupBy({ (show, _, _) -> show.toInt() }, { (_, s, e) -> s.toInt() to e.toInt() })
        val movies = traktRepository.getWatchedMoviesFromCache()

        var shows = 0
        var episodes = 0
        var missingFromPlex = 0
        for ((tmdbId, eps) in byShow) {
            val title = runCatching { mediaRepository.getTvDetails(tmdbId).title }.getOrNull() ?: continue
            val marked = homeServerRepository.markPlexEpisodesWatched(title, tmdbId, null, eps.toSet())
            if (marked < 0) missingFromPlex++ else { shows++; episodes += marked }
        }
        for (tmdbId in movies) {
            val movie = runCatching { mediaRepository.getMovieDetails(tmdbId) }.getOrNull() ?: continue
            runCatching { homeServerRepository.setPlexMovieWatched(tmdbId, movie.title, movie.year.take(4).toIntOrNull(), true) }
        }
        Log.i(TAG, "copied to Plex: $episodes episodes across $shows shows, ${movies.size} movies; $missingFromPlex shows not in Plex")
        // No Plex connection at all (every show "missing"): try again next launch.
        if (byShow.isNotEmpty() && shows == 0) return false
        prefs.edit().putBoolean(DONE_KEY, true).apply()
        return episodes > 0 || movies.isNotEmpty()
    }

    private companion object {
        const val TAG = "PlexWatchedMigration"
        const val DONE_KEY = "done_v1"
    }
}
