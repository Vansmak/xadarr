package com.arflix.tv.data.repository

/**
 * Trakt is off (Joe, 2026-10-05: "nuke trakt, I hate it and it keeps getting in the way").
 * Every Trakt token read answers "not signed in", so no watched/scrobble/watchlist call reaches
 * Trakt. Mark-watched goes to Plex instead -- the source the guide and Continue Watching read.
 * TraktRepository's local caches stay: they're also Xadarr's own on-device watched memory.
 */
const val TRAKT_ENABLED = false
