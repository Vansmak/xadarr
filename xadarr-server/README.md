# Xadarr Sync Server

Self-hosted setup portal and settings sync server for the Xadarr Android TV app.

Open the web UI from any browser on your LAN to configure everything before touching the TV. Or just use it as a headless sync endpoint — the TV app's **Connect to Server** flow works the same either way.

## Quick start

```bash
git clone https://github.com/Vansmak/Xadarr
cd Xadarr/sync-server
docker compose up -d
```

The server listens on port **7979** by default.

- **Web UI**: open `http://<your-server-ip>:7979` in a browser
- **TV app**: Settings → User Info & Account → Connect to Server → enter `http://<your-server-ip>:7979`

## What it does

**Settings sync** — stores a single `data/xadarr_settings.json` containing your full Xadarr settings blob: profiles, addons, home server connections, IPTV config, Trakt tokens, watchlist, and all preferences. The TV app pulls this on first connect and pushes on every change.

**Web setup UI** — a browser interface with four tabs:
- **Settings** — TMDB / Trakt API keys, server name
- **Servers** — add Jellyfin, Emby, or Plex connections
- **IPTV** — set M3U and EPG URLs
- **Plugins** — add addon sources and integration plugins (e.g. Episeerr)

**Plugin detection** — when you paste a URL into the plugin input, the server probes it. If it recognises a supported integration (currently Episeerr), it registers it as a plugin and pre-fills relevant settings (webhook URL, etc.). Plugin settings appear inline in the plugin card.

## Sync endpoints (compatible with the TV app)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/integration/xadarr/status` | Health check |
| `GET` | `/api/integration/xadarr/settings` | Pull full settings blob |
| `PUT` | `/api/integration/xadarr/settings` | Push full settings blob |

These are the same routes Episeerr exposes, so the TV app works identically against either server.

## Dashboard endpoints (web UI)

| Method | Path | Description |
|--------|------|-------------|
| `GET/POST` | `/api/server/config` | Server name and config |
| `GET/POST` | `/api/settings` | Flat settings (TMDB key, webhook, watchlist port, etc.) |
| `GET/POST` | `/api/setup/profile` | Profile name |
| `GET/POST` | `/api/setup/iptv` | IPTV M3U / EPG URLs |
| `GET` | `/api/setup/servers` | List connected home servers |
| `POST` | `/api/setup/servers/connect` | Add Jellyfin / Emby / Plex |
| `DELETE` | `/api/setup/servers/:id` | Remove a server |
| `GET` | `/api/setup/addons` | List plugins / addons |
| `POST` | `/api/setup/addons` | Add plugin or addon by URL |
| `DELETE` | `/api/setup/addons/:id` | Remove plugin or addon |
| `GET` | `/api/media/watchlist` | Watchlist items |
| `GET` | `/api/player/events` | SSE stream of player state |

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `7979` | HTTP port |
| `DATA_FILE` | `/data/xadarr_settings.json` | Settings file path |

Change the host port in `docker-compose.yml` if 7979 conflicts with something else.

## Data

Everything is stored in `data/` (mounted as a Docker volume). No database required. Back it up with a file copy.
