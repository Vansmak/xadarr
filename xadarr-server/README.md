# xadarr-server

Self-hosted sync server and web UI for [Xadarr](https://github.com/Vansmak/xadarr) — the Android TV media hub.

The web UI is not an admin panel. It is Xadarr in a browser: same dark theme, same catalogue row layout, same navigation (Home, Discover, Cameras, History). A user switching from the TV to a browser should feel seamless.

## Architecture

xadarr-server is the single source of truth for all state — watchlist, catalogue configuration, settings, activity history. All clients (Android TV app, mobile, web UI) read from and write to xadarr-server. A change made on any device is immediately reflected everywhere else.

Settings are stored in a single JSON blob (`xadarr_settings.json`) that the TV app pushes on every change and pulls on new-device restore. The web UI reads and writes the same blob.

## Quick start

Add xadarr-server to your `docker-compose.yml`:

```yaml
services:
  xadarr-server:
    build: ./xadarr-server      # or use the Docker Hub image
    container_name: xadarr-server
    restart: unless-stopped
    ports:
      - "7979:7979"
    volumes:
      - /path/to/data:/data
```

Then open `http://your-server-ip:7979` in a browser.

**Connect the TV app:** Settings → User Info & Account → Connect to Server → enter `http://your-server-ip:7979`

## Web UI features

### Home
- Hero backdrop from your library (watchlist + server items)
- Catalogue rows in your configured order: Continue Watching, Watchlist, server library rows, Cameras
- Row visibility and placement respect your catalogue settings (same settings the TV app reads)

### Discover
- All catalogues you've placed in **Discover** rendered as rows, in your configured order
- Content mapped by catalogue title: Trending → TMDB trending; Popular/Top 10 → TMDB popular; Coming Soon → TMDB upcoming
- Search suggestions from SEARCH-placed catalogues as chip rows

### Cameras (requires Frigate)
- Live camera grid with snapshot thumbnails
- Tap any camera for fullscreen; HLS live stream via go2rtc (see [Camera setup](#camera-setup) below)
- Frigate URL configured in Settings

### History
- Activity feed from Episeerr webhook events (grabbed, ready, rule triggered, watched, deleted)
- Timestamps, colour-coded event chips

### Settings
- TMDB API key, server name
- Jellyfin / Emby / Plex server connections
- IPTV M3U + EPG URLs
- Addon sources
- Frigate URL
- Trakt connection status
- Catalogue placement and order (changes sync to TV on next app open)

### Sidebar
- Persistent on desktop, slide-in on mobile (tap "Xadarr" to toggle)
- Live player state (SSE) — shows what's playing on the TV right now
- Recent activity feed (collapsed by default)
- Theme switcher: Midnight · Owl · Black & Gold · Neon

## Catalogue placement and sync

Catalogue placement (Home / Discover / Hidden) and order are stored in the settings blob and **shared across all devices**. A placement change in the web UI takes effect on the TV app at its next launch.

Two rows are **web-local only** (not in the blob): Continue Watching position and Cameras home row position. These are stored in `server_config.json` because the TV app manages them natively.

## Camera setup

Snapshot thumbnails work out of the box — they are proxied through xadarr-server so no extra ports are needed.

For **live video** when you tap a camera, go2rtc (which runs inside Frigate) must be reachable from the browser. Expose port 1984 in your Frigate compose:

```yaml
services:
  frigate:
    ports:
      - "1984:1984"    # go2rtc HLS
```

Then restart Frigate. The web UI will stream HLS from `http://your-frigate-ip:1984/api/{camera-name}/index.m3u8`. Without this port, fullscreen falls back to a snapshot refreshing every 3 seconds.

## Data files

All data lives in `/data/` inside the container (mount a host path or named volume):

| File | Contents |
|------|----------|
| `xadarr_settings.json` | Full settings blob — pushed by TV app; source of truth for all settings, watchlist, catalogue config |
| `server_config.json` | TMDB key, Episeerr URL, web-local row visibility, web theme |
| `webhook_log.json` | Raw incoming webhook events |
| `history.json` | Processed activity history shown in History tab |

`/data/` and the source tree `~/projects/xadarr/xadarr-server/` are completely separate. Data files are on the host volume. `docker cp` goes to `/app/` (code).

## API endpoints

### Sync (TV app compatible)
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/integration/xadarr/status` | Health check |
| `GET` | `/api/integration/xadarr/settings` | Pull full settings blob |
| `PUT` | `/api/integration/xadarr/settings` | Push full settings blob |

### Media
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/media/watchlist` | Watchlist items (from blob) |
| `POST` | `/api/media/watchlist` | Add to watchlist |
| `DELETE` | `/api/media/watchlist/:type/:id` | Remove from watchlist |
| `GET` | `/api/media/continue-watching` | Continue watching items (from blob) |
| `GET` | `/api/media/server-items` | Recent additions from Jellyfin/Emby |
| `GET` | `/api/media/trending` | TMDB trending movies + shows |
| `GET` | `/api/media/popular` | TMDB popular movies + shows |
| `GET` | `/api/media/upcoming` | TMDB upcoming movies |
| `GET` | `/api/media/search?q=` | TMDB multi-search |
| `GET` | `/api/media/history` | Activity history |

### Catalogues
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/catalogues` | All catalogues with placement + sortOrder |
| `PUT` | `/api/catalogues` | Save catalogue placement + order |

### Cameras
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/cameras/list` | Camera list from Frigate |
| `GET` | `/api/cameras/snapshot/:name` | Latest snapshot (proxied from Frigate) |

### Setup / SSE
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/settings` | Flat settings read |
| `PUT` | `/api/settings` | Save a single setting key |
| `GET` | `/api/setup/servers` | List connected home servers |
| `POST` | `/api/setup/servers/connect` | Add Jellyfin / Emby / Plex |
| `DELETE` | `/api/setup/servers/:id` | Remove a server |
| `GET` | `/api/events` | SSE stream — player state, Episeerr events, watchlist updates |

## Development deploy

```bash
for f in server.py web/index.html web/app.js web/style.css; do
  docker cp ~/projects/xadarr/xadarr-server/$f xadarr-server:/app/$f
done
docker restart xadarr-server
```

Full rebuild:
```bash
cd /docker/media/compose
docker compose build xadarr-server && docker compose up -d xadarr-server
```
