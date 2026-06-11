# Changelog

All notable changes to this project are documented in this file.

## [2.5] - 2026-06-11

### Added
- **Three-tier sync** — settings, watchlist, and IPTV favourites now sync across three independent backends in priority order: xadarr-server (full sync, existing behaviour), LAN peer-to-peer (same Wi-Fi, automatic, no server required), and Google Drive (cross-network, new device setup). All three can be active simultaneously; the first available backend wins on pull.
- **LAN peer-to-peer sync** (`LanSyncService.kt`) — devices on the same network advertise and discover each other via mDNS (`_xadarr._tcp`). Full settings snapshot pushed and pulled with no configuration. Two devices, same Wi-Fi, no server needed.
- **Google Drive sync** (`DriveSyncRepository.kt`) — connect a Google account in Settings → Accounts → Google Drive Sync. Settings back up to the Drive app-data folder (private, not visible to other apps or shareable). Restoring on a new device connects the same account and pulls the snapshot automatically. Consent handled via Android AccountManager.
- **Drive account picker** — if multiple Google accounts are on the device, a D-pad-navigable picker appears on connect. Single-account devices connect directly.
- **Credential-safe Drive backups** — IPTV playlist URLs (M3U, Xtream credentials), home server connections, and server passwords are stripped before upload. IPTV favourites, groups, and session state are kept. On restore, absent credential fields are skipped — existing local credentials are never overwritten with blank values.
- **xadarr-server sync endpoints** — `/api/sync/status`, `GET /api/sync/snapshot`, `PUT /api/sync/snapshot` added to the LAN-facing web server for peer exchange.
- **Sync on startup without server** — `pullFromCloud()` is called unconditionally on app start; Drive and LAN peers are tried when no xadarr-server URL is configured.

### Changed
- **DiscoverScreen focus** — entering from the Home tab no longer loses focus; the first row's content is focused directly on arrival.
- `WebAppServer` always starts on app launch regardless of watchlist API toggle; LAN sync requires it.
- `CloudSyncCoordinator` scheduleFlush no longer gated on Supabase auth state.

## [2.4] - 2026-06-09

### Added
- **Launcher Mode** — new toggle in Settings → Appearance makes Xadarr appear in the system "Choose home app" picker. Enabling opens Android Home Settings immediately so the user can set Xadarr as default without navigating manually. A dedicated `LauncherActivity` (disabled by default) carries the HOME intent-filter so install never disturbs the existing launcher.
- **All Apps screen** — alphabetical grid of all installed apps, accessible via a new "All Apps" tile at the end of the Apps home row. (`AllAppsScreen.kt`)
- **xadarr-server: Search tab discover rows** — Search tab now shows curated TMDB rows (Trending, Popular This Year, Top Rated, New Releases, Hidden Gems) when no query is typed. Rows update live when type filter (All / Movies / TV) or genre chip changes.
- **xadarr-server: Black & Gold theme** — pure black background with rich gold accents, matching the Xadarr TV app theme. Available in the sidebar theme switcher.

### Changed
- **Discover screen focus** — initial focus now targets the first row's lazy row directly instead of the outer `LazyColumn` focus group, preventing the left-shift / scroll-cascade bug on screen entry.
- **Cloud sync: Episeerr URL** — Episeerr URL is now included in the settings blob so it syncs across devices. Saving the URL also triggers an immediate background push.
- **Auto-restore on first server URL** — entering the xadarr-server URL on a fresh device automatically pulls the full settings blob without requiring a manual "Restore from server" tap.
- **Back key in Launcher Mode** — pressing Back on the home screen when Launcher Mode is enabled is a no-op (home apps must not be exitable).
- **Internal naming aligned with app identity** — all skin, theme, focus, and component classes, resource names, SharedPreferences keys, KeyStore aliases, and client identifiers throughout the codebase now use the Xadarr name consistently.

## [2.3] - 2026-06-09

### Added
- **xadarr-server web UI** — full browser client at port 7979. Home, Discover, Cameras, History, and Settings pages matching the TV app layout. Real-time Episeerr toast notifications and watchlist sync via SSE. Sidebar shows live player state and recent activity. Four themes: Midnight, Owl, Black & Gold, Neon.

## [2.2] - 2026-06-09

### Added
- **User-configurable API keys** — TMDB API key and Trakt Client ID/Secret settable in Settings → Accounts (TV and mobile). Runtime key update via `OkHttpProvider.setUserApiKeys()`; falls back to build-time keys if unset.
- **Mobile Discover tab** — Watchlist replaced with Discover (catalogue rows) in the mobile bottom navigation bar.
- **Mobile catalogue management** — touch-friendly two-row cards in Settings → Catalogs: title row (tap to rename, long-press to reorder) + action strip (layout toggle, visibility, placement, delete). `MobileCatalogChip` composable.
- **Mobile Frigate URL** — Frigate URL dialog wired up in mobile Settings.

### Changed
- `ProgressWebhookRepository.kt` renamed to `WebhookRepository.kt`; class renamed to `WebhookRepository`. All injection sites updated.

## [2.1] - 2026-06-05

### Added
- **Catalog placement system** — each catalog row can be independently assigned to Home, Discover, or Search tab via a chip in Settings → Catalogs. Rows only appear on their assigned screen.
- **Discover tab** — new navigation destination that shows any catalog rows assigned to Discover placement, plus Continue Watching and Watchlist if routed there.
- **Services picker** — COLLECTION_RAIL rows (Streaming Services, Genres, etc.) now have a manage button that opens a picker to show/hide individual service tiles (Netflix, Prime, Shudder, etc.).
- **Continue Watching controls** — visibility toggle (eye chip) and Up/Down position arrows in Settings → Catalogs; position persists across sessions.
- **Watchlist controls** — visibility toggle and placement chip in Settings → Catalogs; Watchlist can be moved to Home, Discover, or Search.
- **Labeled placement chips** — placement chips in Settings → Catalogs show text ("Home" / "Discover" / "Search") alongside the icon for readability.
- **Long-press D-pad Up** — holding Up from anywhere (content rows, search results, settings content, cameras grid) jumps directly to the top navigation bar in one gesture.
- **Clear image cache** — new action in Settings → Accounts wipes Coil disk and memory cache and forces a fresh home data reload. Useful when artwork or catalog data appears stale.
- **Show series status** — toggle in Settings → Interface displays "Returning Series", "Ended", or "Canceled" on the home hero banner for TV shows.
- **Search: collection tile navigation** — pressing D-pad OK on a streaming service or genre tile in the Search browse section now navigates to that collection correctly (was always calling onNavigateToDetails regardless of tile type).

### Changed
- Home screen filters out catalogs assigned to Discover or Search placement.
- Discover and Search screens each filter to only show their assigned catalogs (no cross-contamination).
- Search quick filter chips: removed Japanese, Korean, and Hindi language options.

### Fixed
- Services picker eye icon now visually reflects toggle state immediately after changing a service's visibility.
- Settings catalog observer now updates `allCatalogsForPicker` when COLLECTION items change, not just when the visible catalog list changes.

### Removed
- **Settings → Playback**: Quality Regex Filters row removed (device-specific stream exclusion patterns).
- **Settings → Accounts**: Privacy and data deletion row replaced with Clear image cache.
- **Settings → Interface**: Show Budget on Home replaced with Show Series Status.

## [1.4] - 2026-06-02

### Fixed
- **TV guide: category focus on sidebar open** — D-pad Left from the channel list now focuses the currently selected category in the sidebar (not the Search field). Focus waits for the slide-in animation to complete before landing.
- **TV guide: startup category** — entering the TV guide now always resets to Favorites if the user has any, instead of persisting the last-used category across sessions.
- **TV guide: Back navigation** — Back from the channel list goes to the top bar (Home/Search/Watchlist) in one press; Back from the sidebar exits to the home screen. D-pad Up from the top channel also reaches the top bar directly.
- **TV guide: top bar access** — D-pad Down from the top bar goes directly to the channel list; D-pad Up from the first channel jumps to the top bar.

## [1.3] - 2026-06-01

### Added
- **TV guide: auto-hide sidebar** — category panel is hidden by default; D-pad Left from the channel list slides it in (TiVimate-style), D-pad Right or Back dismisses it. Channel list and EPG now use the full width.
- **TV guide: favorites sort** — when the Favorites category is selected and the sidebar is open, a Sort row appears below it; pressing OK cycles through Date Added → A→Z → By # and persists across sessions.
- **TV guide: EPG prefetch on favorites load** — program data for favorited channels is fetched as soon as the favorites list is known, before channel enrichment completes, so the guide column is populated when the user first enters the channel list.

## [1.2] - 2026-05-30

### Added
- **Last-channel return** — press D-pad Right while watching fullscreen to jump back to the previously playing channel; press again to toggle back. Tracks channel switches from guide taps, Up/Down zapping, and search overlay picks.

## [1.1] - 2026-05-30

### Added
- **Apps home row** — installed apps appear as a browsable row on the home screen; row order is configurable

## [1.0] - 2026-05-30

### Added
- Initial Xadarr release — forked from Arvio (Apache 2.0), itself a fork of Arflix. Xadarr identity, icons, and self-hosted sync direction established from this point.

## [2.0.20] - 2026-05-29

### Changed
- **Episeerr: xadarr integration simplified** — webhook handler now focuses on playback events and rule processing only; watchlist sync complexity removed (Trakt handles watchlist natively)
- **Episeerr: Trakt device code auth** — authenticate with Trakt directly from the Episeerr setup page without needing a separate browser OAuth redirect; tokens saved automatically
- **Episeerr: Trakt dashboard watchlist** — watchlist widget now shows poster cards with status badges and X button (removes from Trakt); matches Plex widget style
- **Episeerr: Trakt token preservation** — re-saving Trakt settings no longer wipes stored tokens when password fields are left blank
- **sync-server: history filter** — playback history logs only events at or above the completion threshold (50%); start/low-progress events skipped to reduce noise
- **sync-server: watchlist posters** — watchlist items now display poster images (posterPath field normalised to image on read)
- **sync-server: history tab** — history loads on demand when the History tab is opened, not eagerly on page load

## [2.0.18] - 2026-05-28

### Changed
- **Bidirectional watchlist sync** — on startup, local watchlist is replaced with server state (xadarr-server is source of truth); items not present on server are dropped, items missing locally are added
- **sync-server: TMDB poster fetch** — poster is fetched from TMDB when an item is added without a posterPath
- **sync-server: media type normalisation** — DELETE endpoint now normalises tv/show/series so removals from Episeerr land correctly

## [2.0.17] - 2026-05-28

### Changed
- CLAUDE.md updated with On Now row, mini-player, and sync-server architecture documentation

## [2.0.16] - 2026-05-28

### Added
- **Persistent Live TV mini-player** — stream continues playing in a top-right corner tile when navigating away from the TV guide. Tap tile to return to full-screen guide; Back dismisses.
- **On Now home row** — favorited IPTV channels appear as a dedicated row on the home screen, showing current program name, progress bar, and time remaining.
- **Reactive IPTV row builder** — On Now row is built reactively once the IPTV cache is warm (disk or network), not during the initial home load. Survives navigation events and catalog reloads.
- **Colored channel fallback** — channels without a logo show a deterministic color background (hashed from channel name) with the channel initial, instead of a black card.
- **On Now card interactions** — single press starts the channel in the mini-player; long press opens a menu with "Play Full Screen" (full TV guide) and "TV Guide" options.
- **Hero backdrop frozen on On Now row** — browsing the On Now row no longer updates the home screen backdrop.

### Changed
- **Mini-player fully dismissed on VOD launch** — opening a VOD player stops and clears the IPTV stream entirely. No background audio, no auto-resume when returning from VOD.
- **Continue on Server rows removed** — "Continue on \<Server\>" home rows removed; library rows sorted by recent cover the same content.
- **Mini-player channel switch** — switching channels from the On Now row now correctly stops the previous stream before starting the new one.

### Fixed
- **On Now context menu focus** — long-press menu on On Now cards is now properly focusable and navigable with the TV remote.
- **On Now card D-pad navigation** — removed system focus from channel cards to prevent conflict with the custom D-pad system (was causing stuck navigation).

## [2.0.13] - 2026-05-27

### Added
- **Generic webhook system** — multiple webhook URLs, each with its own event selection. Different endpoints can subscribe to different events.
- **Webhook event types:** Playback Start, Pause, Resume, Stop, Progress, Watchlist Add, Watchlist Remove.
- **Watchlist webhooks** — adding or removing a watchlist item (on any device) fires `watchlist.add` / `watchlist.remove` to all subscribed URLs.
- `WebhookUrlConfig` data class replaces bare URL strings; includes URL + `events: Set<String>` for per-URL filtering.
- **LAN watchlist API server** — configurable port (default 7979); toggle and port visible in Plugins & Extensions.
- **Sync-server watchlist sync** — watchlist add/remove notifies the sync server in real time; watchlist is merged from the sync server on settings restore.
- **JF / Emby / Plex session reporting** — `ServerSessionRepository` reports playback progress to the connected home server during playback (ticks for Jellyfin/Emby, timeline for Plex).
- **TV guide focus** — guide opens on the Favorites/All category row instead of the search bar.
- **IPTV guide text** — channel list and guide text sizes increased for couch viewing.

### Changed
- **Self-hosted sync server** replaces Xadarr Cloud / Supabase. Connect to Server on the profile screen pulls all settings from your own xadarr-server instance. No cloud account required.
- **TMDB and Trakt direct API calls** — the Supabase Edge Function proxy is no longer used. TMDB requests pass through with a direct API key; Trakt adds its required headers directly.
- **Episeerr-specific plugin model removed** — webhook system is now generic and works with any HTTP endpoint. Episeerr can still receive webhooks via the standard URL config.
- **Integration settings** always visible in Plugins & Extensions — no longer gated on any plugin being installed.

## [2.0.12] - 2026-05-26

### Changed
- Integration settings (progress webhook toggle, URL, interval; watchlist API toggle, port; watched threshold) moved to **Plugins & Extensions** — always visible, no longer gated on Episeerr being installed
- Episeerr is now added as a standard plugin via addon URL (e.g. `http://ip:5002`) — xadarr-server auto-detects it and pre-fills webhook/watchlist settings automatically
- Removed dedicated "Episeerr URL" and "Restore from Episeerr" rows from User Info & Account
- Web UI: Episeerr plugin card shows its 6 integration settings inline when added
- xadarr-server web UI `saveSettings()` now only pushes flat setting keys — no longer risks overwriting addon or profile data on save
- Jellyfin server display name now fetched from `/System/Info/Public` for accurate naming

### Added
- **Watched threshold** is now user-configurable (50–99%) from both the TV app and web UI, replacing the hardcoded 90% value
- Settings round-trip: TV app now exports integration settings (webhook URL/enabled/interval, watchlist port/enabled, Episeerr URL, completion %) back to the server on sync push, so they survive a full pull-push cycle

## [2.0.11] - 2026-05-25

### Fixed
- Restored original side-by-side TV guide layout (CategorySidebar + EpgGrid) — the fullscreen overlay guide introduced in 2.0.8 was the root cause of guide unresponsiveness on Shield and all TV devices with channels configured

## [2.0.10] - 2026-05-25

### Fixed
- Reverted focus changes that broke guide interactivity on Shield/working IPTV setups; kept only the informational empty-state messages

## [2.0.9] - 2026-05-25

### Fixed
- TV guide now shows "No IPTV playlist configured — go to Settings" when no playlist is set up, instead of the misleading "Loading channels…"

## [2.0.8] - 2026-05-25

### Fixed
- Fixed TV guide showing blank "CHANNELS 0" and being unresponsive when IPTV channels haven't loaded yet — now shows "Loading channels…" and holds focus so the remote works (Back navigates away)

## [1.9.99] - 2026-05-24

### Fixed
- Fixed Episeerr watchlist push always silently skipping — if only the cloud sync URL was configured (not the separate Episeerr URL field), the push read a blank URL and bailed. Now falls back to the sync server URL.

## [1.9.98] - 2026-05-24

### Fixed
- Fixed Episeerr watchlist push not firing when watchlist changes come from Trakt sync — Trakt-driven updates now push to Episeerr the same as manual add/remove.

## [1.9.97] - 2026-05-24

### Fixed
- Fixed Episeerr watchlist push sending wrong media type for TV shows (`tv` → `show`) — shows were being skipped by Episeerr's Sonarr submission and not appearing on the dashboard.

## [1.9.96] - 2026-05-24

### Fixed
- Fixed TV guide D-pad down being intermittently unresponsive or sticky after navigating into the channel list — pending focus guard is now cleared if all retry attempts fail, and navigation from an unanchored state correctly starts from the first channel.
- Fixed back button doing nothing when pressed from the TV guide category/search sidebar — now navigates back to home in all cases.
- Fixed home screen crash on profile selection when a connected Jellyfin or Plex server returns multiple in-progress episodes for the same series — the "Continue on Server" row now deduplicates entries by series so no duplicate LazyList keys are produced.
- Fixed Trakt device-code auth timing out immediately on transient errors — polling now only stops on permanent OAuth failures (404 invalid code, 409 already used, 410 expired, 418 denied) and continues through network errors, 5xx responses, and other transient conditions.

### Added
- Watchlist changes (add or remove) on any device now push automatically to Episeerr without requiring a manual force sync.

## [1.9.92] - 2026-05-11

### Home server sources and catalogs
- Added Home Server source support for user-owned Jellyfin, Emby, and Plex libraries.
- Added Home Server catalog import so personal server collections can appear as Xadarr catalogs.
- Added distinct server labels in sources so multiple connected servers can be identified clearly.
- Improved Home Server matching speed, source labels, playback readiness, and autoplay behavior.
- Improved Plex authentication discovery and matching reliability.

### TV, IPTV, and VOD
- Improved full IPTV EPG backfill coverage so more channels receive guide data.
- Improved live TV category context actions, category reorder behavior, and left-navigation focus.
- Improved channel logo loading performance in the TV page.
- Fixed South Africa country labeling in TV categories.
- Improved IPTV VOD quality handling for episodes and sources.

### Details, search, and navigation
- Added TMDB movie collections to details pages and moved collection rows above More Like This.
- Fixed duplicate "Collection" naming in details pages.
- Fixed several details-page spacing, cast-row, collection-row, and poster-clipping issues.
- Fixed details page cast focus jumps and vertical focus skips.
- Fixed search genre filters, search keyboard activation, and search/filter focus indicators.
- Improved home hero syncing so focused cards drive the displayed metadata more reliably.
- Improved focus border behavior and added focus-border color support.

### Continue Watching, profiles, and cloud
- Added continue-watching card enhancements, including clearer season/episode progress badges.
- Fixed false "continue at" resume times on new or unwatched upcoming episodes.
- Refreshed Continue Watching after cloud restore so cloud login restores visible progress sooner.
- Added synced custom profile avatars and fixed avatar preservation during cloud sync.
- Fixed season unwatch and batch season-watch behavior to avoid unnecessary duplicate Trakt writes.
- Fixed Trakt-connected watchlist add/remove failures after token refresh by using the secured Trakt auth proxy.

### Player, subtitles, and accessibility
- Fixed remote selection for the next-episode prompt and routed up-next remote keys correctly.
- Added AI subtitles support and upgraded Media3/ExoPlayer to 1.9.0.
- Added AI subtitle settings on mobile.
- Fixed manual subtitle selection being overwritten by default subtitle rules.
- Fixed subtitle language filtering and subtitle sorting behavior.
- Added subtitle offset and subtitle style settings.
- Added spoiler blur support and Android TV 10 fallback behavior.
- Added trailer sound controls and improved trailer setting behavior on mobile.

### Policy and cleanup
- Removed the non-working CloudStream integration path from the app for this build.
- Tightened Play/GitHub policy wording, README content, and source disclosure.
- Removed Advertising ID usage from the Play build path and clarified privacy/account deletion documentation.

### Contributors
- Sage Gavin Davids: search focus/filter fixes, details collection visibility, continue-watching cards, poster episode badges, subtitle/trailer/spoiler settings, and TV layout fixes.
- EierkopZA: spoiler blur fallback, focus border color support, TV details poster clipping, search filter focus borders, and collection/watchlist focus fixes.
- Himanth Reddy: regex/performance optimization work, codebase optimization, README maintenance, and catalog/settings stability work.
- silentbil: AI subtitles, subtitle scoring/sorting, subtitle settings fixes, and mobile AI settings visibility.

## [1.9.91] - 2026-05-01

### IPTV and TV page
- Reworked IPTV category handling so provider playlist groups can stay in the same order users configured in their IPTV list.
- Added the expandable All Channels grouping for automatically matched categories.
- Added category context actions for hiding and restoring IPTV groups.
- Removed extra playlist-name clutter from channel rows.
- Improved mobile and tablet TV playback fullscreen behavior so the bottom navigation bar no longer remains visible.
- Changed the mobile top navigation label from TV Shows to TV.
- Improved IPTV VOD source handling so multiple available qualities can appear instead of only one VOD quality.

### Watchlist and Continue Watching
- Fixed Trakt watchlist order so items better follow the latest-added order.
- Fixed Trakt watchlist matching so the app is less likely to choose the wrong remake or wrong year.
- Fixed a regression where the watchlist could briefly load and then disappear into an empty state.
- Fixed stale local watchlist data on TV after switching accounts or profiles.
- Improved Continue Watching startup so cached items appear faster on the home screen.
- Improved Continue Watching behavior with and without Trakt so profile-specific progress is used more consistently.

### Playback and sources
- Improved source switching reliability in the player. Contributor: EierkopZA.
- Improved source loading from fast Search-to-Details navigation. Contributor: EierkopZA.
- Improved player back behavior and playback navigation. Contributor: Himanth Reddy.
- Improved stream startup behavior for selected sources.
- Improved trailer and service video behavior.
- Fixed loading clearlogo flicker in the player. Contributor: EierkopZA.
- Improved Android TV stability on lower-memory devices by reducing image-cache pressure during catalog scrolling and before stream playback starts.

### Catalogs and discovery
- Added Discover Catalogs search for public Trakt and MDBList lists.
- Improved Discover Catalogs TV focus outlines and navigation.
- Improved Discover Catalogs mobile layout.
- Made catalog list adding a one-click action, with Added state feedback.
- Improved catalog rename and dialog language handling.
- Fixed catalog layout controls and focus behavior. Contributor: Himanth Reddy.
- Improved catalog navigation restoration. Contributor: silentbil.

### Details, anime, and metadata
- Fixed anime episode source matching for multi-season anime.
- Fixed details page metadata behavior. Contributor: EierkopZA.
- Improved details/source reliability by waiting for IMDb ID where needed. Contributor: EierkopZA.
- Fixed several details layout and focus regressions.
- Added and refined Crunchyroll assets. Contributor: Himanth Reddy.

### Settings, language, profiles, and cloud
- Improved app language resources. Contributor: silentbil.
- Added subtitle language filtering UI. Contributor: silentbil.
- Fixed DNS persistence. Contributor: Himanth Reddy.
- Fixed cloud login/startup language restore.
- Improved profile loading and profile creation behavior.
- Improved profile and player focus fixes. Contributor: silentbil.

## [1.9.9] - 2026-04-28

### Android TV / IPTV overhaul
- Reworked the TV page for very large IPTV lists, including lists with 50,000+ channels.
- Improved channel loading, first EPG appearance, favorites, recent channels, and startup behavior.
- Fixed major DPAD focus and navigation issues across IPTV rows and channel lists.

### Smoother TV navigation
- Improved rail scrolling, focus behavior, and animation timing across the home, details, watchlist, collections, and TV pages.
- Reduced jank in heavy catalog sections such as genres, franchises, Top 10, and recently added rows.
- Fixed multiple focus cropping and blinking issues without lowering artwork quality or removing video previews.

### Playback and source loading
- Improved source discovery speed and reliability for HTTP, VOD, IPTV VOD, and debrid sources.
- Restored and improved MP4/service video playback behavior.
- Improved autoplay selection so higher-quality and larger sources are preferred while keeping startup faster.
- Added frame-rate matching before playback to reduce stutter.

### Trakt, watchlist, and continue watching
- Reworked Trakt watchlist ordering and matching so items better follow the newest-added order from Trakt.
- Improved matching by title, year, and type to avoid wrong versions, such as older remakes or unrelated entries.
- Fixed continue watching logic so it uses real in-progress Trakt data instead of everything that was ever left unfinished.
- Improved profile isolation for Trakt data, watch history, watchlist, and continue watching.

### Profile isolation and cloud sync
- Profiles now have isolated settings, catalogs, Trakt connections, history, watchlists, and continue watching.
- Addons and IPTV can still be shared where intended.
- Improved real-time cloud sync behavior across profiles and devices.
- Fixed force cloud sync and subtitle preference persistence.
- Added and refined profile PIN support, including fixes for mobile profile creation.

### Mobile and settings improvements
- Reworked the mobile settings layout and naming, including renaming "Stremio" to "Addons".
- Improved profile creation and editing on mobile, including keyboard handling and avatar picker scaling.
- Added app-wide language coverage for the languages listed in app settings.
- Added better catalog and IPTV management controls on mobile.

### Collections, catalogs, and metadata
- Fixed several genre, service, franchise, and Top 10 catalog issues.
- Top 10 Movies and Top 10 Shows are now capped correctly.
- Removed unwanted Favorite TV catalog behavior from the homescreen.
- Improved metadata logos and IMDb SVG rating display on home and details pages.
- Removed MAL score display.
- Added cleaner provider logos, including Netflix, HBO Max, Disney+, Prime Video, Hulu, Paramount+, Peacock, Apple TV+, IMDb, and others.

### Contributors
Thank you to everyone who helped with this release, including:
- EierkopZA
- Himanth Reddy
- chrishudson918
- mrtxiv
- And many more people who contributed smaller fixes, ideas, testing, and feedback. Thank you.

### Sources
- Metadata and discovery: TMDB, IMDb metadata/logo assets, Trakt.
- Sync/auth: Supabase and Xadarr Cloud.
- Playback/addons: IPTV M3U/Xtream/Stalker sources, Stremio-compatible addons, and community HTTP sources.
- Smoothness references: Android TV device traces and public Android TV performance research.

## [1.9.8] - 2026-04-10

### Added
- Premium source picker overhaul shared between Details and Player, with richer source cards, improved metadata chips, better sorting, and clearer quality/release/audio/provider presentation.
- Clock format setting in Settings (`12-hour` / `24-hour`) with app-wide top bar clock support.
- Volume Boost setting using Android `LoudnessEnhancer`.
- MAL score badge on anime details pages.
- Mobile-visible back button on deep screens.
- Post-episode "Up Next" prompt that respects auto-play-next.
- Fire TV / Bluetooth media remote support (play/pause, stop, rewind/fast-forward, next/previous episode).
- Multiple named IPTV playlist backend support (up to 3 lists) with enabled/disabled state.

### Improved
- Top navigation bar redesigned: centered nav items, settings gear on the right, avatar-only profile entry, cleaner visual hierarchy.
- Home screen startup speed: categories cached to disk for near-instant relaunch, Continue Watching fetch decoupled from `loadHomeData` so it can complete independently.
- Image loading and perceived loading speed improved via dedicated Coil client, larger disk/memory caches, DNS warm-up, better preload behavior, and empty-image-url guards.
- Player controls, top bar focus, screen transitions, row emphasis, and card interactions feel smoother and more premium.
- Tablet player controls are larger, better centered, and more readable on bright content.
- Source picker labeling refined so torrent/cached/VOD are surfaced more accurately and without noisy HTTP/Direct badges.
- Top 10 rows redesigned to use normal cards with gold rank badges instead of oversized background numerals.
- TV page EPG now loads up front when stale/missing instead of trickling in after page open.

### Fixed
- In-app updater downloads but never installs (missing PackageInstaller broadcast receiver / confirmation flow).
- Profile dialog focus flow and input handling.
- Deleted catalogs flashing back on home load.
- Player crash when switching audio language.
- Details page now focuses the first unwatched episode by default.
- Custom subtitle addons like Wizdom/Ktuvit now install and resolve correctly.
- IMAX badge added; Dolby Vision badge false positives fixed.
- Cross-device cloud sync timing improved with ON_RESUME pull, watch-history realtime updates, token refresh, and dirty-push retry behavior.
- Continue Watching / Trakt logic substantially reworked to reduce stale and incorrect items, better handle new episode premieres, and improve refresh timing.
- Home focus/row stability improved across startup and catalog updates.
- Trailer button / trailer behavior and details-page asset prefetching improved to reduce clearlogo and episode-load lag.
- Mobile watchlist/details/search/settings responsiveness improved, including reduced first-press dead time and faster activation.
- Poster rows no longer use internal bottom gradients.
- Top 10 badges now stay visible when cards are focused.
- Normal login flow now performs full cloud restore (not just addons), so catalogs, IPTV favorites, and other cloud-backed state restore after login.
- `main` pushed to GitLab and GitLab repo prepared as the active remote while GitHub remains suspended.

## [1.9.74] - 2026-04-03

### Fixed
- Fixed unreadable white-on-white buttons throughout the app
- Fixed cloud sign-in failing with misleading "expired" error
- Fixed app startup crash on certain TV devices
- Fixed Play Store builds not connecting to cloud services
- Fixed APK signing key mismatch causing "app in conflict" install errors

## [1.9.7] - 2026-04-01

### Added
- Trakt watchlist two-way sync: items added in Xadarr sync to Trakt and vice versa
- Clearlogo overlays on watchlist cards
- Clearlogo repositioned to bottom-left corner on all landscape cards for a cleaner look
- Watchlist preloads on app startup for instant display
- Home screen categories cached for instant re-navigation
- Automated release pipeline (GitHub Actions: build, GitHub Release, Play Store, Discord)

### Improved
- Player buttons: focused state now shows white filled circle with black icon
- Subtitle system: only the selected subtitle is loaded instead of all 30+, significantly faster playback startup
- Non-English subtitles (OpenSubtitles) now work reliably across all languages
- Poster cards 10% larger on home screen with proper row spacing
- Watchlist poster cards sized consistently with home screen
- Watchlist grid columns optimized for poster layout (6-8 columns)
- Home screen card titles removed (clearlogo on card is sufficient)
- Real-time cloud sync fixed: WebSocket now authenticates with user JWT for instant cross-device updates
- Addon input modal: D-pad navigation fully working after typing/pasting URL
- Addon save reliability: fixed race condition where addon showed as added but wasn't persisted

### Fixed
- Continue Watching showing episodes/seasons that don't exist (e.g., S2E1 for a 1-season show)
- Watchlist page: left D-pad navigation to sidebar now works correctly
- Watchlist/sidebar: selecting Home/TV/Settings no longer accidentally opens a details page
- Subtitle rebuild loop removed: no more flickering or infinite re-preparing during playback

## [1.9.2] - 2026-03-19

### Added
- Full mobile/tablet support: same APK now works on phones, tablets, and TV with adaptive UI.
- Mobile bottom navigation bar replacing TV topbar on touch devices.
- Mobile Home: swipeable hero carousel with clearlogo, IMDb badge, auto-scroll, page indicators.
- Mobile Details: vertical scroll layout with backdrop gradient, labeled action buttons, touch-scrollable sections.
- Mobile Settings: single-column layout with horizontal tab chips.
- Mobile Search: soft keyboard with OutlinedTextField, touch-scrollable result rows.
- Mobile Player: touch controls overlay (tap to toggle, drag to seek, tap play/pause).
- Mobile Sources page: full-width single-column stream selector with tappable cards.
- Mobile Subtitle/Audio menu: bottom-sheet style with tappable track items.
- Mobile Context menus: bottom-sheet style with slide-up animation and touch items.
- Mobile Live TV: optimized vertical layout with smaller fonts and touch-friendly channel rows.
- Mobile Cast/Person modal: vertical scrollable layout with centered photo and biography.
- Mobile Profile page: scrollable LazyRow for 4+ profiles, smaller avatars on phone.
- Long-press context menu on mobile Home cards (via combinedClickable).
- Cloud connect button on profile selection page (opens QR on TV, email/password on mobile).
- Default home launcher intent filter so Xadarr can be set as default launcher.
- Background logo prefetch for all Home categories on mobile (not just first 2 rows).
- Frame rate matching: real display mode switching via Display.Mode API with stabilization polling.

### Improved
- Playback startup speed through progressive source loading, background prefetch, and smart autoplay window.
- Player buffering tuned for large debrid files (80MB byte cap, cache bypass for heavy streams).
- Subtitle ordering: embedded subtitles appear first and are auto-selected over addon subtitles.
- In-app updater: marks installed tag as ignored to prevent re-prompt loop, clears on actual version upgrade.
- IPTV error messages: stripped HTML/CSS from provider error responses, human-readable messages for common HTTP errors.
- Dialogs responsive on mobile: CloudPair, AppUpdate, UnknownSources, InputModal, SubtitlePicker all adapt width.
- Live TV fullscreen EPG overlay: smaller fonts and tighter layout on mobile.
- Top gradient behind topbar for readability over backdrops.
- Collapsible category rail in Live TV when browsing channels.
- Bottom bar visual upgrade: top border, larger icons, pill highlight, indicator dot.

### Fixed
- Major play-button crash from SimpleCache folder lock conflict (singleton fix).
- Mobile-only crash after profile selection from TV launcher channel provider on non-TV devices.
- Continue Watching wrong resume time: removed ALL stale position leak paths (Supabase history cleanup, CW cache purge, zero-value placeholders).
- Edit Profile delete button rendering as thin white stripe (missing weight/fillMaxWidth).
- Player error buttons not clickable when source/subtitle menu was open.
- Player error on fast-forward/rewind with mid-playback recovery (light seek first, then re-prepare).
- Player select/enter key now always toggles play/pause.
- Subtitle/source menu focus broken by touch clickable modifier on player container.
- Live TV sound continuing when switching to another app (lifecycle-aware pause/resume).
- Live TV fullscreen black screen (postDelayed player attachment with requestLayout/invalidate).
- Trakt list catalogs disappearing from Home (merge filtering and DataStore race fixes).
- IPTV config cloud sync timing for non-primary profiles.
- Text overflow/vertical wrapping across player, settings, details, bottom bar (maxLines + ellipsis).
- Profile page TV focus restored (Surface for TV, Box+clickable for mobile).
- Profile dialog focus: Create/Save button gets initial focus on TV.

## [1.9.1] - 2026-03-14

### Improved
- Playback startup speed improved ~300% through progressive source loading and background stream prefetching on Details page open.
- Smart autoplay: when stream cache is warm (prefetched), playback starts instantly. When cold, a 3.5s collection window ensures the best source is selected from all responding addons.
- Player buffering reduced with larger buffer window, 256MB disk media cache, and stronger connection reuse for large files.
- Live TV mini-player no longer switches channel on focus change; first click previews, second click opens fullscreen.
- Search page layout tightened so Movies and TV Shows rows are fully visible and readable under the topbar.
- Non-English subtitle selection with OpenSubtitles now matches correctly using normalized language tokens.
- Details action buttons (Sources, Trailer, etc.) now render instantly without waiting for external IDs to load.

### Fixed
- Major crash when pressing play caused by SimpleCache folder lock conflict when re-entering the player. Fixed with a process-wide singleton cache.
- Intermittent crash from ExoPlayer race conditions during rapid navigation and force-unwrap on nullable season/episode fields.
- Continue Watching showing wrong resume time on unwatched next episodes (e.g. "Continue S2E2 33:02" after finishing S2E1).
- Trakt list catalogs disappearing from Homepage after initial load due to premature merge filtering and DataStore re-trigger race.
- IPTV config for non-primary profiles not persisting to cloud due to cloud push timing before DataStore flush.
- ExoPlayer onPlayerError listener crash after player release during back navigation.
- AudioManager unsafe cast crash on non-standard Android TV firmware.
- Home left-scroll viewport not following focus on first left move.
- Live TV timeout popup after extended watching caused by insufficient OkHttp read timeout and small buffer window for IPTV streams.

## [1.9] - 2026-03-13

### Added
- GitHub Releases in-app updater for non-Play installs, including download, installer handoff, and unknown-sources guidance.
- Android TV / launcher Continue Watching publishing support for launcher channels and Watch Next style surfaces.
- Cloud backup/restore coverage for non-Trakt local watched state and local Continue Watching across profiles.
- Downloader code `3366110` documented for direct-install users.

### Changed
- App version updated to `1.9` (`versionCode 190`) and Settings version label now reads from `BuildConfig`.
- Home / Details navigation, focus ownership, and topbar entry were reworked so topbar is entered via `Up` instead of left-edge drift.
- Home and Details metadata/description layout was refined for more stable hero placement and clearer text hierarchy.
- Live TV layout was tightened under the topbar, with denser guide rows, a smaller preview block, and more compact category typography.
- IPTV group/category ordering now preserves playlist-provided order instead of forcing alphabetical sorting.

### Fixed
- Home open-item crash paths caused by placeholder Continue Watching entries and invalid hero/logo fetches.
- Continue Watching now refreshes more reliably across Trakt and non-Trakt profiles, including remove/dismiss persistence and next-episode advancement.
- Details now keeps the correct Continue Watching target and watched markers when opening into a resumed episode/season path.
- Home context menu focus, overlay layering, and back handling regressions.
- Xadarr Cloud TV pairing fallback/verification flow and missing release-build Supabase host configuration.
- Live TV guide viewport/focus polish, including more visible channels and cleaner spacing.
- Startup crash caused by restricted TV provider channel selection query in launcher integration.

## [1.8.4] - 2026-03-04

### Added
- Player binge-group-aware next-episode preference handoff for more consistent source continuity.
- TMDB watch-provider data support in repository layer (used for details enrichment and future UI extensions).

### Changed
- App version label and package version updated to `1.8.4` (`versionCode 184`).
- Home vertical catalog navigation tuned for smoother up/down transitions and lower frame-skip risk.
- Home focus retention now survives category/custom-catalog list updates more reliably.
- Custom catalog incremental load starts earlier to reduce time-to-visible after entering Home.
- Details page layout overhauled for cleaner hierarchy (actions -> seasons -> episodes) with larger, richer episode cards.
- Home and Details metadata/description spacing and typography refined for improved readability.

### Fixed
- Focus could drift off-screen on some pages when navigating back across rows/lists; viewport correction logic now clamps and recenters focus targets.
- Source-switch flow hardened in Player to reduce black/stuck states during stream changes.
- Subtitle switching no longer requires full media-source rebuild in normal track-switch cases.
- Home hero metadata (time/budget/rating) now appears much faster when focus changes.
- Cross-screen focus loss regressions when custom catalogs finished loading on Home.

### Removed
- Search suggestions/typeahead flow from Search screen, including D-pad suggestion navigation and inline suggestion list.

## [1.8.2] - 2026-03-02

### Changed
- Cross-device cloud sync (IPTV, addons, catalogs, watchlist, settings) now triggers on every profile selection instead of only on first app launch.
- Playback starts significantly faster — removed redundant startup buffer gate and lowered initial buffer threshold.

### Fixed
- Continue Watching no longer shows a 60-second empty gap when auto-playing the next episode.
- "Mark as Watched" from the context menu now correctly removes the item from Continue Watching.
- "Mark as Watched" now automatically adds the next episode to Continue Watching.
- Watched status now loads from Xadarr Cloud for non-Trakt profiles, so badges appear without a Trakt account.
- Continue Watching now syncs across devices for non-Trakt profiles using profile name instead of device-local UUID.
- Legacy Continue Watching entries no longer leak across profiles.
- Fixed duplicate key crash ("Key was already used") in Continue Watching row when the same show appeared twice.
- Watched badges now appear on initial Details page load without needing to navigate away and back.
- Xadarr Cloud watched data queries now paginate correctly for large libraries (previously capped at 1,000 rows).
- Hero clear logo now loads immediately on startup when selecting a profile, instead of requiring a focus change.
- When no streaming addons are configured, the app now shows a friendly setup guide instead of a playback error.
- Source selector shows setup instructions instead of generic "No sources found" when no addons are installed.
- Next auto-played episode no longer starts at 01:00 — correctly starts at 00:01.

## [1.6.0] - 2026-02-22

### Added
- Extended Live TV EPG timeline model to support multiple upcoming programs per channel (beyond now/next).
- Per-profile cloud snapshot payload maps for settings, addons, catalogs, IPTV config/favorites, and watchlist.
- Repository helpers for profile-specific export/import of addons, catalogs, IPTV config, and watchlist state.
- Expanded HTTP/HTTPS playback compatibility path for stream sources and header handling.
- IPTV VOD support for both movies and TV shows integrated into source resolution flows.
- Card layout mode toggle for switching between landscape and poster styles.
- Default audio language option in Settings with profile-scoped persistence.

### Changed
- App version updated to `1.6.0` (`versionCode 160`) and Settings label updated to `Xadarr V1.6`.
- Live TV EPG lane now uses real upcoming program blocks and only shows filler when timeline data is genuinely unavailable.
- IPTV loading/retry strategy tuned to reduce multi-minute startup delays and improve responsiveness.
- Playback startup buffering strategy rebalanced for movie/TV streams (larger startup gate + safer initial buffer thresholds).
- External subtitle injection timing adjusted to avoid immediate post-start media-item rebuilds.
- Profile boot flow now starts IPTV warm/load earlier after profile selection for faster Live TV readiness.
- Live TV and Settings surfaces received additional UI polish and focus/navigation refinements for Android TV remote use.

### Fixed
- IPTV Refresh action could fail with cancellation errors (`StandaloneCoroutine was canceled`) and not reload channels.
- Live TV timeline third/fourth blocks incorrectly showing `No EPG data` despite available EPG entries.
- Cross-profile leakage risk where addon sets could appear across profiles due to account-wide startup sync behavior.
- Profile isolation gaps by moving remaining global settings storage (`card layout mode`) to profile scope.
- Multiple IPTV EPG parsing paths now keep consistent upcoming-program selection across pull-parser and SAX fallbacks.
- Improved Dolby Vision startup compatibility with automatic codec fallback path (DV -> HEVC -> AVC) before source failover.

## [1.5.0] - 2026-02-17

### Added
- Xadarr Cloud TV pairing flow via QR sign-in/register and direct account linking.
- VOD sources available inside source selection for playback.
- Skip Intro integration in player with dedicated button and backend wiring.
- QR rendering component for in-app pairing.
- IPTV support now includes Xtream Codes connections.

### Changed
- App version bumped to `1.5.0` (`versionCode 150`).
- Updated Downloader install code to `5955104`.
- Catalog limits increased from `20` to `40` entries for built-in catalogs and added Trakt/MDBList catalogs.
- Improved player startup and stream handling to reduce delays before playback starts.
- Better Android TV keyboard and remote handling in settings/addon/list flows.
- Improved compatibility for Fire TV / Firestick class devices.
- Android 7 (API 24/25) support enabled by lowering app minimum SDK requirement.
- Framerate matching behavior refined in playback flow.

### Fixed
- Source discovery regression where results became very slow or stalled after initial successful loads.
- Autoplay/source fallback behavior that switched too aggressively across sources.
- Playback start issues at `00:00` for some streams and large files.
- Large 4K stream handling and retention so high-size sources are given a fair start window.
- VOD source visibility and matching reliability, including TV-show catalog flow improvements.
- Subtitle menu back-navigation behavior (back now closes subtitle layer correctly instead of exiting playback flow).
- Xadarr Cloud account pairing reliability between app and web sign-in path.
- TV remote navigation issues in settings forms/addon-list sections.
- EPG reliability and parser flow issues affecting guide behavior.

## [1.4.0] - 2026-02-14

### Added
- Optional `Xadarr Cloud` account connection in Settings for syncing profiles, addons, catalogs, and IPTV settings.
- Supabase migration and edge functions for TV device auth flow: `tv-auth-start`, `tv-auth-status`, `tv-auth-complete`.

### Fixed
- Trakt connect now displays activation URL and code while authorization is pending.
- Cloud sign-in/sign-up modal D-pad navigation (Down/Up/Left/Right) is now consistent on Android TV remotes.

## [1.3.0] - 2026-02-11

### Added
- IPTV settings now include a dedicated `Delete M3U Playlist` action to remove configured M3U/EPG and IPTV favorites.
- Updated release screenshots for Catalogs and Live TV (`v1.3`).

### Changed
- Player controls overlay no longer adds a dark background scrim behind play/pause controls.
- Sidebar focus visibility and section handoff behavior improved for clearer TV remote navigation.
- Continue Watching cards show resume timestamp and a subtle progress track.

### Fixed
- Resume metadata flow to keep Continue Watching playback start position aligned with player start.
- Multiple focus/scroll consistency issues across Home/Settings/TV surfaces.

## [1.2.0] - 2026-02-10

### Added
- Live TV page in sidebar with IPTV support.
- M3U playlist configuration in Settings.
- Catalogs tab in Settings for custom Trakt and MDBList URLs.
- Catalog ordering controls (up/down) and deletion for custom catalogs.
- Live TV mini-player flow and expanded TV navigation support.
- New screenshots for Live TV and Catalogs in README.

### Changed
- Home and catalog loading behavior across profiles.
- Focus and scroll behavior improvements across Home, Details, Search, Watchlist, and TV surfaces.
- Player/stream handling refinements for smoother transitions.
- App release version updated to `1.2.0`.

### Fixed
- Continue Watching visibility and persistence regressions.
- Custom catalog rows not appearing on Home in some profile states.
- IPTV and mini-player stability issues including focus restore and state persistence.
- Multiple UI alignment and layout consistency issues in Settings and TV screens.
