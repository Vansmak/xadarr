# Xadarr Web UI — Progress

## Status: v2.3 complete (2026-06-07)

---

## Completed

### v2.3 Base rebuild (session 1)
- **Full nav restructure** — permanent sidebar with Home/Discover/Cameras/Watchlist/History/Settings pages; no top bar
- **Watchlist synced to blob** — retired `watchlist.json`; reads/writes `watchlistByProfile[pid]` in settings blob (same key TV app writes)
- **Continue Watching row** — `/api/media/continue-watching` reads `localContinueWatchingByProfile[pid]`
- **Cameras tab** — `/api/cameras/list` proxies Frigate `/api/config`; `/api/cameras/snapshot/<name>` proxies latest.jpg; tap opens fullscreen player
- **Catalogue management** — GET/PUT `/api/catalogues` reads/writes `catalogsByProfile[pid]`; visibility toggle, placement selector (Home/Discover/Search/Hidden), sortOrder reorder with up/down arrows
- **Home server items** — `/api/media/server-items` queries first active Jellyfin/Emby for recent additions
- **Episeerr toast overlay** — SSE `episeerr` events trigger slide-in toast (4s auto-dismiss), colour-coded by event type
- **Theme system** — 4 themes (Midnight/Owl/Black&Gold/Neon) stored at `web_theme` in blob; CSS variables on `[data-theme]`
- **Hero backdrop** — cycles on hover/focus on Home and Discover; picks from watchlist + server items (not TMDB trending)
- **SSE watchlist broadcast** — `event: watchlist` sent to all SSE clients on add/remove
- **Frigate URL** — in Settings (stored in `frigate_url` in blob, same key as TV app)
- **Mobile sidebar** — auto-hide with "Xadarr" brand toggle; overlay closes on tap-outside; `@media (max-width: 768px)`

### v2.3 Catalogue corrections pass (session 1)
- **SEARCH placement** — added as 4th option; normalised on load
- **Reorder arrows** — up/down on every catalogue row; `moveCatalogueUp/Down` swap and auto-save
- **Services/Genres chip picker** — COLLECTION_RAIL rows get a chip picker; chips toggle `isHidden` on matching COLLECTION sub-items; title-matched via `_SERVICE_TITLES` / `_GENRE_TITLES` sets
- **saveCatalogues** — syncs `isHidden` from `placement === 'HIDDEN'`; writes `sortOrder` as current index
- **Search suggestions** — SEARCH-placed COLLECTION_RAIL catalogues appear as chip rows in Discover tab (no query active)
- **Snapshot auto-refresh (fullscreen)** — 3s interval while camera is open; clears on close
- **Rule picker from Home watchlist row** — clicking a pending card (amber border) opens Episeerr rule picker
- **Synthetic catalogues** — CW and Watchlist not in blob; managed in `server_config.json` under `web_row_visibility`; synthetic entries built at GET /api/catalogues
- **CSS order for home rows** — `.rows-container { display: flex; flex-direction: column }` + `:style="'order:'+catOrder(id)"` on each row; respects sortOrder without rewriting HTML

### v2.3 Bug-fix and feature pass (session 2, 2026-06-07)
- **Activity sidebar section** — defaults collapsed
- **CW visibility race condition fixed** — `loadCatalogues()` is now `await`ed in `init()` before `loadHome()` runs; `catalogueIsVisible()` returns `false` when `this.catalogues` is empty
- **CW x-if guard** — CW row uses `x-if` with inline check (`catalogues.find(c=>c.id==='continue_watching')?.placement !== 'HIDDEN'`) instead of `x-show` + function call, eliminating Alpine reactivity edge case
- **`Cache-Control: no-store`** — added to all `.js` and `.css` responses so browser always fetches fresh files after deploy
- **`HIDDEN` placement normalised** — `loadCatalogues` allowed list now includes `'HIDDEN'` so placement survives round-trip
- **Cameras row not cut off** — removed `overflow-y: auto` from `.main` (body scrolls naturally; sidebar is `position: fixed` so it stays put); increased `.rows-container` `padding-bottom` to 60px
- **Discover tab dynamic rows** — replaced hardcoded Trending Movies/Shows with `x-for` over all `catalogues.filter(c => c.placement === 'DISCOVER')`; `discoverRowItems(cat)` maps by title/ID keywords
- **Popular and upcoming TMDB endpoints** — `GET /api/media/popular` (movies + shows), `GET /api/media/upcoming` (movies); keyword mapping: "popular"/"top 10" → popular data, "coming soon"/"upcoming" → upcoming
- **Camera fullscreen HLS player** — restored `<video>` + HLS.js; `poster` attribute shows proxied snapshot while stream loads; snapshot refreshes every 3s as fallback; `lowLatencyMode: true`
- **go2rtc HLS** — streams at `{frigate_url with port 1984}/api/{name}/index.m3u8`; requires port 1984 exposed in Frigate docker-compose

---

## Architecture decisions

| Decision | Choice | Why |
|---|---|---|
| Watchlist key | `watchlistByProfile[pid]` | Same key TV app writes; blob is source of truth |
| Watchlist item ID | `tmdbId` (not `id`) | TV app format; `id` is web alias mapped from `tmdbId` |
| Camera snapshots | Server-proxied `/api/cameras/snapshot/<name>` | Avoids CORS from browser → Frigate |
| Camera HLS | Direct go2rtc URL `{frigateBase:1984}/api/{name}/index.m3u8` | go2rtc is already in Frigate; just needs port 1984 exposed |
| Catalogue placement | `catalogsByProfile[pid][i].placement` | Values: HOME/DISCOVER/SEARCH/HIDDEN; shared with TV app via blob |
| Synthetic rows (CW, Cameras) | `server_config.json` `web_row_visibility` | Not in blob; web-UI-only concept |
| Theme | `web_theme` in blob | Web-specific; TV handles its own themes |
| Discover content | TMDB popular/upcoming/trending per catalogue keyword | Addon content needs external fetches — out of scope |

### Shared vs web-local catalogue state
- **Shared** (blob `catalogsByProfile[pid]`): placement, sortOrder, isHidden — TV app reads these on next launch
- **Web-local** (`server_config.json`): CW row position/visibility, Cameras row position/visibility — TV manages these natively in-app

---

## Data structures (from actual blob)

**Watchlist item** (`watchlistByProfile[pid][]`):
```json
{"addedAt": 1779767051000, "backdropPath": "https://...", "mediaType": "tv",
 "posterPath": "https://...", "sourceOrder": 0, "title": "...", "tmdbId": 270476}
```

**Continue Watching item** (`localContinueWatchingByProfile[pid][]`):
```json
{"backdropPath": "...", "durationSeconds": 2822, "episode": 2, "episodeTitle": "...",
 "id": 1104, "mediaType": "TV", "posterPath": "...", "progress": 58,
 "resumePositionSeconds": 1652, "season": 1, "title": "..."}
```

**Catalogue item** (`catalogsByProfile[pid][]`):
```json
{"id": "favorite_tv", "title": "Favorite TV", "placement": null, "isHidden": null,
 "kind": "STANDARD", "sourceType": "PREINSTALLED", "sortOrder": null}
```
`placement` null/missing = HOME. Valid: `"HOME"`, `"DISCOVER"`, `"SEARCH"`, `"HIDDEN"`.
`kind='COLLECTION_RAIL'` = Services or Genres aggregator row (chip picker in settings).
`kind='COLLECTION'` = individual service/genre/franchise item (managed via chip picker).

---

## Remaining / future work

- [ ] **Camera live stream** — requires `1984:1984` in Frigate docker-compose ports; HLS player code is ready and waiting
- [ ] **Home server catalogue rows** — fetch actual items from Jellyfin/Plex per catalogue (needs per-catalogue library mapping)
- [ ] **Addon catalogue rows** — fetch from Stremio-compatible addon manifest (cinemeta/etc.)
- [ ] **IPTV channel browser / TV Guide** in web
- [ ] **Trakt auth flow** in web (currently web uses watchlist from blob, not live Trakt)
- [ ] **Multi-profile support** (currently always uses `activeProfileId`)
- [ ] **`just_added` Discover row** — map to server recent additions (currently no keyword match)

---

## Deployment

```bash
for f in server.py web/index.html web/app.js web/style.css; do
  docker cp ~/projects/xadarr/xadarr-server/$f xadarr-server:/app/$f
done
docker restart xadarr-server
```

## Files changed

| File | Role |
|---|---|
| `server.py` | Flask server — blob watchlist, catalogues, CW, cameras, Frigate proxy, SSE, popular/upcoming TMDB endpoints, `Cache-Control: no-store` on JS/CSS |
| `web/style.css` | Theme variables, catalogue rows, camera grid, toast, hero; body scrolls (no overflow-y on .main) |
| `web/index.html` | Sidebar nav, Home/Discover/Cameras tabs, Settings; CW as `x-if`; dynamic Discover x-for; HLS camera fullscreen |
| `web/app.js` | Full data model; `await loadCatalogues` in init; `discoverRowItems` mapper; popular/upcoming fetch; HLS player open/close |
