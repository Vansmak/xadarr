# Xadarr — Claude Code Project Context

## Core Architecture Principle

xadarr-server is the single source of truth for all state — watchlist, catalogue configuration, settings, activity history, API keys. All clients (Android TV app, mobile, web UI) read from and write to xadarr-server. No client owns its own state independently. A change made on any device or surface is immediately reflected everywhere else. The experience is seamless regardless of which device you pick up.

## Web UI Design Principle

The xadarr-server web UI is not an admin panel — it is Xadarr in a browser. It should look and feel identical to the Android TV app: same dark theme, same card and catalogue row layout, same navigation structure (Home, Discover, Watchlist, TV, Cameras). A user switching from the TV to a browser should feel seamless, not like they opened a settings page. The web UI adapts for mouse/touch interaction but mirrors the TV app's visual design and structure exactly.

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

**After every successful build, always copy the APK to `/mnt/usbshare/`:**
```bash
cp app/build/outputs/apk/sideload/debug/app-sideload-debug.apk /mnt/usbshare/xadarr-latest.apk
```

## What We've Added (beyond upstream fork)

### 1. Progress Webhook (`ProgressWebhookRepository.kt`)
POSTs JSON playback events (start/pause/stop/progress) to a configurable URL. Debounced by interval. Used to integrate with Episeerr.

- Keys: `WEBHOOK_ENABLED_KEY`, `WEBHOOK_URL_KEY`, `WEBHOOK_INTERVAL_KEY` in `ProgressWebhookRepository.kt`
- Called from `PlayerViewModel` at start/pause/stop/periodic save

### 2. Server Session Reporting (`ServerSessionRepository.kt`)
Reports playback progress to the home media server (Jellyfin/Emby ticks, Plex timeline). One session UUID per item load.

- Called from `PlayerViewModel` alongside webhook calls

### 3. LAN Sync Server (`server/WatchlistApiServer.kt`)
HTTP server (NanoHTTPD, default port 7979) that enables automatic settings sync between Xadarr devices on the same network. Uses Android NSD (mDNS) — devices advertise as `_xadarr._tcp.` and discover each other automatically, no IP entry or pairing needed. When one device saves settings, changes are pushed to all discovered peers.

- Setting label: **"LAN Sync"** in Plugins & Extensions (was "Watchlist API" — do not revert)
- Toggle + port live in **"stremio"** section (Plugins & Extensions), after the addon list
- Keys: `WATCHLIST_API_ENABLED_KEY`, `WATCHLIST_API_PORT_KEY` in `WebhookRepository.kt`
- Discovery: `LanSyncService.kt` — NSD advertise + discover, validates peers via `/api/sync/status`
- Sync push: `LanSyncService.pushToPeers()` sends settings snapshot to all live peers on save
- Failure mode: mDNS blocked by router (AP isolation / guest network) — fallback is xadarr-server sync

### 4. Settings Layout (no separate "integrations" section)
Settings are split across two existing sections:

**"accounts" section (TV) / "USER INFO & ACCOUNT" (mobile)** — indices 5–6:
- Episeerr URL (index 5)
- Restore from Episeerr (index 6)

**"stremio" section (TV) / "Plugins & Extensions" (mobile)** — after addon list (indices `addons.size+1` through `addons.size+5`):
- Progress webhook toggle (addons.size + 1)
- Webhook URL (addons.size + 2)
- Webhook interval (addons.size + 3)
- LAN Sync toggle (addons.size + 4)
- LAN Sync port (addons.size + 5)

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

### 12. Cameras Screen (Frigate integration)
Frigate camera grid accessible via top nav Cameras tab. Snapshot thumbnails with LIVE badge. Tap → fullscreen HLS player (no mini-player for cameras). Frigate URL configured in Settings; Cameras tab hidden if not configured.

- **`FrigateRepository.kt`** — fetches camera list from Frigate `/api/config`, builds snapshot and stream URLs
- **`CamerasScreen.kt`** — grid of camera cards, nav bar stays visible and functional
- **`CameraPlayerScreen`** — fullscreen ExoPlayer for HLS streams, overlays entire screen including nav bar
- **Home row** — Cameras catalogue row on Home, same pattern as On Now row
- Stream URLs route through Frigate's go2rtc HLS endpoint (not raw RTSP) so TV can reach them

### 13. Catalogue Management
Catalogues have a visibility toggle (eye) instead of delete-only. Each catalogue has a placement setting: **Home** or **Discover**.

### 14. Discover Screen
Replaces the Watchlist tab in the top nav bar. Renders catalogue rows assigned to Discover placement, same format as Home. Watchlist is now a standard catalogue row assignable to Home or Discover.

### 15. Episeerr Integration
Full integration with Episeerr for media management awareness on the TV.

- **`EpiseerrRepository.kt`** — `getPendingItems()`, `getRules()`, `assignRule()`, `getRecentEpiseerrEvents()`
- **`EpiseerrPollManager`** — singleton, 60s polling loop, exposes `pendingTmdbIds: StateFlow` and `toastEvents: SharedFlow`
- **Toast notifications** — `EpiseerrActivityToast` overlay in `ArflixApp`, slides in from top, auto-dismisses 4s, colour-coded by event type. Shows anywhere in app, not just during playback. Events: `episode.grabbed`, `episode.ready`, `rule.triggered`, `rule.assigned`, `watchlist.requested`
- **Watchlist pending badge** — `LocalEpiseerrPendingIds` CompositionLocal; `isPending` param on `MediaCard` shows amber stripe + ring border for items awaiting rule selection in Episeerr
- **Rule picker** — `RulePickerScreen` full-screen D-pad composable with poster, rules list, assign + Advanced (webview to Episeerr) buttons; tapping a pending watchlist card opens it; `RulePickerViewModel` for Hilt injection
- **Server catalogue rule badge** — JF/Plex/Emby catalogue row cards show assigned Episeerr rule name as small badge
- **Webview** — `EpiseerrWebviewScreen` full-screen overlay with back button, used for deep links into Episeerr
- Episeerr URL configured in Settings (`EPISEERR_URL_KEY`); all integration silently disabled if not set

### 16. Theme System
Multiple selectable themes persisted in DataStore, applied on app start without restart. All composables use `MaterialTheme.colorScheme.*` tokens — no hardcoded colors. Themes: **Midnight** (default, navy/blue), **Owl** (warm dark, amber/gold), **Black & Gold** (pure black, rich gold), **Neon** (pure black, electric cyan/green).

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
| `data/repository/FrigateRepository.kt` | Fetches camera list and builds stream/snapshot URLs |
| `ui/screens/cameras/CamerasScreen.kt` | Camera grid with nav bar, snapshot cards |
| `ui/screens/cameras/CameraPlayerScreen.kt` | Fullscreen HLS camera player |
| `data/repository/EpiseerrRepository.kt` | Episeerr API calls — pending, rules, assign |
| `data/repository/EpiseerrPollManager.kt` | 60s poll loop, toast events, pending state |
| `ui/screens/episeerr/RulePickerScreen.kt` | D-pad rule picker for pending watchlist items |
| `ui/screens/episeerr/EpiseerrWebviewScreen.kt` | Full-screen webview for Episeerr deep links |

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
| Deploy | `docker cp <file> episeerr:/app/<file>` | `./promote_dev.sh <version>` → Docker Hub |
| Container | `episeerr` (port 5002) | same image, different build |

**Always edit `episeerr_custom`, deploy via `docker cp` to `episeerr` container.**
`docker cp` changes survive `docker restart` but NOT container recreate. Run `./release_custom.sh <version>` from `episeerr_custom/` to bake into the Docker Hub image.

**Never rebuild the running `episeerr` container from `episeerr_dev`.**

### Xadarr integration blueprint
File: `episeerr_custom/integrations/xadarr.py`, URL prefix `/api/integration/xadarr`.

Routes:
- `POST /webhook` — playback events (start/pause/stop/progress); triggers Sonarr rule processing at completion threshold
- `GET /status` — health check; Xadarr pings this to verify Episeerr is reachable
- `GET /settings` — return full settings blob (used on new-device restore)
- `PUT /settings` — save full settings blob pushed by TV app
- `GET/DELETE /history` — playback event log (progress events filtered; only ≥50% threshold events logged)
- `GET /dashboard/player/state` — current player state snapshot
- `GET /dashboard/player/events` — SSE stream of player state updates

Watchlist sync was removed from arvio.py in 2.0.20. Trakt handles watchlist natively; arvio.py is webhook-only.

### Episeerr xadarr webhook integration
File: `episeerr_custom/integrations/xadarr.py`

- `fire_xadarr_webhook()` — background thread helper, derives URL from stored xadarr service URL
- `/api/integration/xadarr/pending` — returns `episeerr_select` pending items with TMDB poster
- Fires to Xadarr webhook system on: `episode.grabbed`, `episode.ready`, `rule.triggered`, `rule.assigned`, `watchlist.requested`
- `episeerr_select` tagged series in Sonarr → auto-added to xadarr-server watchlist

## xadarr-server

Separate container for the sync server web UI (port 7979). The web UI is Xadarr in a browser — same dark theme, same catalogue row layout, same navigation. Not an admin panel.

```
container_name: xadarr-server
build:    ~/projects/xadarr/xadarr-server/
data:     /home/joe/config/xadarr-server/data  →  /data/  inside container
ports:    7979:7979
compose:  /docker/media/compose
```

**Code deploy:** `for f in server.py web/index.html web/app.js web/style.css; do docker cp ~/projects/xadarr/xadarr-server/$f xadarr-server:/app/$f; done && docker restart xadarr-server`

**Rebuild:** `cd /docker/media/compose && docker compose build xadarr-server && docker compose up -d xadarr-server`

**Data files** (inside container at `/data/`, host at `/home/joe/config/xadarr-server/data/`):
- `xadarr_settings.json` — full settings blob from TV app (watchlist, catalogues, all settings)
- `server_config.json` — TMDB key, Episeerr URL, web-local row visibility, web theme
- `webhook_log.json`, `history.json`

Note: `watchlist.json` is retired — watchlist is now read/written directly from `watchlistByProfile[pid]` in the settings blob, same as the TV app.

**Critical:** `/data/` and the source tree `~/projects/xadarr/xadarr-server/` are completely separate. Data files are on the host volume. `docker cp` goes to `/app/` (code), not `/data/` (data).

### xadarr-server web UI features
- **Home** — hero backdrop (from watchlist/library), catalogue rows in configured order (CW, Watchlist, server library, Cameras)
- **Discover** — all DISCOVER-placed catalogues rendered dynamically; content mapped by title keyword (trending/popular/upcoming via TMDB)
- **Cameras** — Frigate snapshot grid; fullscreen HLS via go2rtc (port 1984 must be exposed in Frigate compose)
- **History** — activity feed from Episeerr webhook events with timestamps and colour-coded chips
- **Settings** — Jellyfin/Emby/Plex connections, IPTV, addons, Frigate URL, Trakt status, catalogue placement/order
- **Sidebar** — live player state (SSE), recent activity (collapsed by default), 4-theme switcher
- **SSE** — real-time Episeerr toasts, watchlist sync across browser tabs, player state

### Catalogue placement sync model
Catalogue placement (Home/Discover/Hidden) and sort order are stored in the blob (`catalogsByProfile[pid]`) and **shared across TV app and web UI**. Web changes take effect on the TV at next app launch.

**Web-local only** (in `server_config.json`, not the blob): Continue Watching row position/visibility and Cameras home row position — the TV manages these natively in-app.

### Camera live stream requirement
Snapshot thumbnails proxy through xadarr-server (no extra ports). For fullscreen live video, Frigate's go2rtc must be reachable on port 1984:
```yaml
# Frigate docker-compose
ports:
  - "1984:1984"
```
Without this, fullscreen falls back to a snapshot refreshing every 3 seconds.

### Service enable/disable (Episeerr)
Episeerr services table has `enabled BOOLEAN DEFAULT 1`. `get_service()` filters `WHERE enabled = 1`, so:
- Setting `enabled = 0` causes `get_service()` to return `None` for that service
- All API calls gated on `get_service()` (dashboard stats, config fetches) are automatically skipped
- Toggle endpoint: `POST /api/toggle-service/<service>` with `{"enabled": true/false}`
- UI: enable/disable switch in Services setup page, first element in each card header

**Do NOT use `config is not None` as the widget enabled check** — that also hides services with no DB row (env-var configured services like Radarr, Sonos, SABnzbd). Instead query `SELECT enabled FROM services WHERE ...` and only set `widget['enabled'] = False` when `row is not None and not row[0]`.

## Current Version

**v2.6.2** — Fix Jellyfin/Plex credentials wiped by cloud sync.

Changes since v2.6.1:
- `homeServerConnectionJson` is now stripped from all outgoing sync payloads (xadarr-server, LAN, Drive already did this). Home server tokens are device-specific (Android KeyStore) and can't be decrypted on another device or re-imported — doing so silently blanked the token, making the connection unusable and requiring re-entry of credentials.
- Apply path in `CloudSyncRepository` also ignores `homeServerConnectionJson` from incoming payloads, so stale values already stored on xadarr-server can't cause a recurrence.
- Key invariant: **home server connections are always local-only**. Never sync `homeServerConnectionJson` anywhere.

Changes since v2.6:
- v2.6.1: Network section added to mobile/tablet settings (LAN Sync rows 38/39/40).
- v2.6: LAN Sync overhaul: timing fix, conflict resolution (master/timestamp), live status in Settings, moved to Network section.

## TODO

- **Camera live stream (infra)** — web UI HLS player is ready; requires exposing port `1984:1984` in the Frigate docker-compose so go2rtc is reachable from the browser. Without it, fullscreen falls back to 3s snapshot refresh.
- **Contribute features to arvio-fork** — port Xadarr's new features (webhook, cameras, themes, TV guide fixes, catalogue placement, Discover tab, etc.) to `arvio-fork` repo (separate from xadarr). Must use Arvio naming throughout (`ArvioSkin`, `ArvioTheme`, etc. — NOT Xadarr names). Strip all Episeerr-specific code. Reference commit `9a22108` on xadarr main — that is the last commit before the Xadarr rename, so the code still uses Arvio naming and is the cleanest starting point for porting.
