# Xadarr — Claude Code Project Context

## What This Is

Xadarr (package `com.arflix.tv`) is an Android TV media hub — a fork of Arflix. It streams IPTV, Jellyfin, Plex, and Emby sources using ExoPlayer. Built with Kotlin + Jetpack Compose (TV material3), Hilt DI, Coroutines/Flow.

Joe is the sole developer. He works from a couch using an Android TV remote.

## Build & Deploy

```bash
# Build and sideload to TV
./gradlew :app:installSideloadDebug

# ADB device
adb connect 192.168.254.91:5555
```

## What We've Added (beyond upstream fork)

### 1. Progress Webhook (`ProgressWebhookRepository.kt`)
POSTs JSON playback events (start/pause/stop/progress) to a configurable URL. Debounced by interval. Used to integrate with Episeerr.

- Keys: `WEBHOOK_ENABLED_KEY`, `WEBHOOK_URL_KEY`, `WEBHOOK_INTERVAL_KEY` in `ProgressWebhookRepository.kt`
- Called from `PlayerViewModel` at start/pause/stop/periodic save

### 2. Server Session Reporting (`ServerSessionRepository.kt`)
Reports playback progress to the home media server (Jellyfin/Emby ticks, Plex timeline). One session UUID per item load.

- Called from `PlayerViewModel` alongside webhook calls

### 3. Watchlist API Server (`server/WatchlistApiServer.kt`)
NanoHTTPD server serving `GET /watchlist` as JSON over LAN so Episeerr can poll it. Default port 7979.

- Toggle + port live in **"stremio"** section (Plugins & Extensions), after the addon list
- Keys: `WATCHLIST_API_ENABLED_KEY`, `WATCHLIST_API_PORT_KEY` in `ProgressWebhookRepository.kt`

### 4. Settings Layout (no separate "integrations" section)
Settings are split across two existing sections:

**"accounts" section (TV) / "USER INFO & ACCOUNT" (mobile)** — indices 5–6:
- Episeerr URL (index 5)
- Restore from Episeerr (index 6)

**"stremio" section (TV) / "Plugins & Extensions" (mobile)** — after addon list (indices `addons.size+1` through `addons.size+5`):
- Progress webhook toggle (addons.size + 1)
- Webhook URL (addons.size + 2)
- Webhook interval (addons.size + 3)
- Watchlist API toggle (addons.size + 4)
- Watchlist API port (addons.size + 5)

**Key pattern:** The D-pad Enter key handler in `SettingsScreen` uses a `when { contentFocusIndex in range -> ... }` block for stremio (because addon count is dynamic), and `when (contentFocusIndex)` for accounts. Both must be kept in sync with `sectionMaxIndex`.

### 5. Episeerr Settings Sync (`CloudSyncRepository.kt`)
Cloud sync was originally Supabase-backed. Now routes through Episeerr:
- `PUT {episeerr_url}/api/integration/arvio/settings` — saves full settings JSON blob
- `GET {episeerr_url}/api/integration/arvio/settings` — loads it back

Episeerr URL stored under `EPISEERR_URL_KEY` in `ProgressWebhookRepository.kt` (shared keys file).

### 6. TMDB/Trakt Direct API Calls (`network/ApiProxyInterceptor.kt`)
Was routing through Supabase Edge Functions (all 404ing). Fixed to call APIs directly:
- TMDB: passes through with existing `api_key` query param from `BuildConfig.TMDB_API_KEY`
- Trakt: adds `trakt-api-key` and `trakt-api-version` headers directly (required by all Trakt endpoints)

### 7. Larger IPTV Text
Channel list text sizes increased for better TV readability.

### 8. Watchlist Home Row
Watchlist items appear as a browsable row on the home screen.

### 9. `serverItemId` on `StreamSource`
`StreamSource` data class has `serverItemId: String? = null` — populated from the home server item ID in `HomeServerRepository.buildStreamSources()`. Used by `ServerSessionRepository` to report to Jellyfin/Plex.

### 10. Live TV Mini-Player (`LiveTvPlayerViewModel`, `LiveTvMiniPlayerOverlay`)
Activity-scoped ExoPlayer keeps the IPTV stream alive when navigating away from the TV guide. A picture-in-picture tile appears in the top-right corner on the home screen and other non-TV screens.

- **ViewModel:** `LiveTvPlayerViewModel` — activity-scoped (above NavHost), owns ExoPlayer instance
- **Surface handoff:** `LiveTvScreen` attaches/detaches its surface; mini-player overlay uses the same player
- **Dismiss:** Back key on home screen (`onInterceptBack`), or VOD player opening (`dismiss()` — not just pause)
- **Channel switch:** `playFromHome()` calls `player.stop()` + `clearMediaItems()` before loading new stream (prepare() is a no-op on an already-READY player without stop first)

### 11. On Now Home Row (`HomeViewModel.launchOnNowRowObserver`)
Favorited IPTV channels appear as a dedicated "On Now" row showing current program, progress bar, and time remaining.

- **Builder:** `launchOnNowRowObserver()` — reactive, independent of `loadHomeData()`. Calls `warmupFromCacheOnly()` then observes `observeFavoriteChannels()`. Backoff retry at 3s/8s/20s/60s for cold starts.
- **Never removed by `loadHomeData()`** — only preserves the row; never calls `buildFavoriteTvCategory()` or removes `favorite_tv` from the category list
- **Card:** `LiveTvChannelCard.kt` — `.focusProperties { canFocus = false }` prevents system focus conflicts with the custom D-pad system
- **D-pad press:** short press → mini-player; long press → `LiveTvContextMenu` (Play Full Screen / TV Guide)
- **Hero backdrop:** frozen when focused on On Now row — does not update hero background for IPTV items
- **Logo fallback:** channels without `channel.logo` show a colored background (hash of channel name) + initial letter

## Key Files

| File | Purpose |
|------|---------|
| `data/repository/ProgressWebhookRepository.kt` | Webhook + shared DataStore keys (WEBHOOK_*, WATCHLIST_API_*, EPISEERR_URL_KEY) |
| `data/repository/ServerSessionRepository.kt` | Home server session progress reporting |
| `data/repository/CloudSyncRepository.kt` | Settings sync via Episeerr (replaced Supabase) |
| `network/ApiProxyInterceptor.kt` | TMDB/Trakt direct API (no more Supabase proxy) |
| `server/WatchlistApiServer.kt` | LAN watchlist HTTP server (NanoHTTPD) |
| `ui/screens/settings/SettingsScreen.kt` | Settings UI — Episeerr in "accounts", webhook/watchlist in "stremio" |
| `ui/screens/settings/SettingsViewModel.kt` | Settings state + save fns: `saveWebhookUrl`, `saveEpiseerrUrl`, etc. |
| `ui/screens/player/PlayerViewModel.kt` | Triggers webhook + session calls at playback events |
| `data/model/Models.kt` | `StreamSource.serverItemId` field |
| `data/repository/HomeServerRepository.kt` | Populates `serverItemId` in `buildStreamSources()` |
| `ui/screens/home/LiveTvChannelCard.kt` | On Now row card — logo, EPG progress, LIVE badge |
| `ui/screens/tv/live/LiveTvPlayerViewModel.kt` | Activity-scoped IPTV player for mini-player |
| `ui/screens/tv/live/LiveTvMiniPlayerOverlay.kt` | Floating PiP tile shown on non-TV screens |
| `ui/components/AppTopBar.kt` | Top nav bar — accent-color animated focus border on all focused items |

## Settings Navigation Pattern

Settings uses a zone/index system:
- `Zone.SIDEBAR` → `Zone.SECTION` → `Zone.CONTENT`
- `contentFocusIndex` (0-based) tracks which row is focused
- `sectionMaxIndex(section)` caps navigation (must equal max valid index)
- **Enter key**: `when (currentSection)` block dispatches actions by `contentFocusIndex`
- Rows use `Modifier.settingsFocusSlot(index)` for scroll-into-view
- Visual focus: `isFocused = focusedIndex == N` passed into each row composable

When adding new rows to a section:
1. Increment `sectionMaxIndex` for that section
2. Add the row to the composable with the next index
3. Add the index case to the Enter key handler `when (currentSection) { "section" -> when (contentFocusIndex) { N -> ... } }`

## TV Guide Layout (LiveTvScreen.kt)

TV is always fullscreen — video fills the screen, no mini-player. The guide is an overlay:

- **Any key press** (up/down/OK) → guide slides up from bottom (covers 60% of screen height)
- **Guide closed** → `FullscreenHud` shows channel/program info, auto-hides
- **Guide open** → channel list + EPG timeline; video visible above the guide
- **D-pad left** from channel list → category panel slides in from left (`guideGroupsVisible`)
- **D-pad right** from categories → categories hide, focus returns to channel list
- **Select channel / Back** → guide closes, video fullscreen again

Touch devices still use the old mini-player + side-by-side EPG layout (`useTouchRail` or `else Row` path).

Key state: `isGuideOpen`, `guideGroupsVisible` in `LiveTvScreen`. Helpers: `openGuide()`, `closeGuide()`, `openSidebar()`.

### Focus & layout patterns (already fixed — do not revert)

- **`focusSelectedChannelSignal`** — monotonic counter watched by `EpgGrid` to scroll to and highlight the current channel. EpgGrid **skips if value is 0**, so the startup `LaunchedEffect` increments it (`focusSelectedChannelSignal += 1`) before calling `epgFocus.requestFocus()`. Without this, D-pad OK on first entry always activated row 0 instead of the last-played channel.

- **`epgStartOffset`** — `animateDpAsState` that animates `EpgGrid`'s `padding(start)` from `0.dp` → `LiveDims.SidebarExpanded` (240dp) when `guideGroupsVisible = true`. The sidebar is an overlay; this offset shifts the grid right so the channel name column stays visible. Do **not** replace with a `Row` layout — it breaks the overlay model.

- **`openSidebar()`** — sets `guideGroupsVisible = true`, increments `focusActiveCategorySignal`, and immediately calls `runCatching { sidebarFocus.requestFocus() }`. The `requestFocus()` call is required; relying solely on `focusActiveCategorySignal`'s 200ms-delayed focus inside `CategorySidebar` silently fails when the sidebar is already open.

## AppTopBar Focus Style (`ui/components/AppTopBar.kt`)

All three interactive items — `TopBarNavChip`, `TopBarSettingsGear`, `TopBarProfileAvatar` — show an animated accent-color border when focused:

```kotlin
val borderColor by animateColorAsState(
    targetValue = if (isFocused) LiveColors.Accent else Color.Transparent, ...
)
// applied before .clip():
.border(LiveDims.FocusBorder, borderColor, <shape>)
.clip(<shape>)
```

`LiveColors.Accent = Color(0xFF4F7FB0)`, `LiveDims.FocusBorder = 2.dp`.

## Episeerr Integration

Episeerr is Joe's own Python/Flask media management app. **Two separate directories:**

| | episeerr_custom | episeerr_dev |
|---|---|---|
| Purpose | **Production running instance** | Upstream source / future releases |
| Deploy | `docker cp <file> episeerr:/app/<file>` | `./release_dev.sh custom` → Docker Hub |
| Container | `episeerr` (port 5002) | same image, different build |

**Always edit `episeerr_custom`, deploy via `docker cp` to `episeerr` container.**
`docker cp` changes survive `docker restart` but NOT container recreate. Run `./release_dev.sh custom` from `episeerr_custom/` to bake into the Docker Hub image.

### Xadarr integration blueprint
File: `episeerr_custom/integrations/arvio.py`, URL prefix `/api/integration/arvio`.

Routes:
- `POST /webhook` — playback events (start/pause/stop/progress); triggers Sonarr rule processing at completion threshold
- `GET /status` — health check; Xadarr pings this to verify Episeerr is reachable
- `GET /settings` — return full settings blob (used on new-device restore)
- `PUT /settings` — save full settings blob pushed by TV app
- `GET/DELETE /history` — playback event log (progress events filtered; only ≥50% threshold events logged)
- `GET /dashboard/player/state` — current player state snapshot
- `GET /dashboard/player/events` — SSE stream of player state updates

Watchlist sync was removed from arvio.py in 2.0.20. Trakt handles watchlist natively; arvio.py is webhook-only.

## xadarr-server

Separate container for the sync server web UI (port 7979).

```
container_name: xadarr-server
build:    ~/projects/arvio/sync-server/
data:     /home/joe/config/xadarr-server/data  →  /data/  inside container
ports:    7979:7979
```

**Code deploy:** `docker cp ~/projects/arvio/sync-server/<file> xadarr-server:/app/<file> && docker restart xadarr-server`

**Data files** (inside container at `/data/`, host at `/home/joe/config/xadarr-server/data/`):
- `xadarr_settings.json` — full settings blob from TV app
- `watchlist.json` — web UI managed watchlist (separate from TV app's watchlist)
- `server_config.json` — TMDB key and server-level config
- `webhook_log.json`, `history.json`

**Critical:** `/data/` and the source tree `~/projects/arvio/sync-server/` are completely separate. Data files are on the host volume. `docker cp` goes to `/app/` (code), not `/data/` (data).

### Service enable/disable (Episeerr)
Episeerr services table has `enabled BOOLEAN DEFAULT 1`. `get_service()` filters `WHERE enabled = 1`, so:
- Setting `enabled = 0` causes `get_service()` to return `None` for that service
- All API calls gated on `get_service()` (dashboard stats, config fetches) are automatically skipped
- Toggle endpoint: `POST /api/toggle-service/<service>` with `{"enabled": true/false}`
- UI: enable/disable switch in Services setup page, first element in each card header

**Do NOT use `config is not None` as the widget enabled check** — that also hides services with no DB row (env-var configured services like Radarr, Sonos, SABnzbd). Instead query `SELECT enabled FROM services WHERE ...` and only set `widget['enabled'] = False` when `row is not None and not row[0]`.

## TODO

- **User-configurable API keys in Settings** — Move TMDB API key and Trakt Client ID/Secret from `BuildConfig` (baked into APK, extractable via decompilation) to user-editable settings stored in DataStore. Add fields to the "accounts" section in SettingsScreen. Fall back to `BuildConfig` values if the user hasn't entered their own. This eliminates the key-in-APK exposure for public repo distributions.
