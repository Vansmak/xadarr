# Xadarr — Setup Guide

## Install APK

1. Enable **Install from unknown sources** in your device settings.
2. Transfer the APK to your device — USB drive, network share, or the Downloader app on Fire TV.
3. Open a file manager on your TV and tap the APK to install.

**ONN / Fire TV:** Downloader app is the easiest path. Point it at the GitHub release URL or a local address.

**In-app updates** (sideload build only): a banner appears on the home screen when a newer version is available. Select it to download and install.

---

## Sync Server

The sync server stores your settings and makes new device setup instant — connect and restore everything in one step.

**Clone the repo and start the server:**

```bash
git clone https://github.com/Vansmak/xadarr
cd xadarr/sync-server
docker compose up -d
```

Or add it to an existing `docker-compose.yml`:

```yaml
services:
  xadarr-server:
    build: ./sync-server        # path to the sync-server directory
    container_name: xadarr-server
    restart: unless-stopped
    ports:
      - "7979:7979"
    volumes:
      - ./sync-server/data:/data
    environment:
      - PORT=7979
      # - TMDB_API_KEY=your_key_here   # optional, can be set in the web UI
```

Web UI is at `http://your-server:7979`. Open it to verify the server is running and optionally set a TMDB API key.

---

## First-Time Setup

### Restore from sync server (recommended)

1. Open Xadarr and create a profile.
2. Go to **Settings → User Info & Account → Connect to Server**.
3. Enter `http://your-server:7979` and tap **Restore**.

All settings, playlists, and connections are pulled down immediately.

### Manual setup

If you don't have a sync server, configure everything by hand in Settings:

- **Home servers** → Sources
- **IPTV** → Plugins & Extensions
- **Webhooks** → Plugins & Extensions → Progress Webhook

---

## Home Server (Jellyfin / Emby / Plex)

1. Go to **Settings → Sources**.
2. Tap **Add** and choose the server type.
3. Enter the server URL and your API key (Jellyfin/Emby) or Plex token.
4. Press **Test**, then **Save**.

A **Continue on *Name*** row appears on the home screen once connected. Playback progress is reported to the server in real time during playback.

---

## IPTV

### M3U playlist

1. **Settings → Plugins & Extensions → Add IPTV Source → M3U URL**.
2. Paste your M3U URL and save.

### Xtream Codes

1. **Settings → Plugins & Extensions → Add IPTV Source → Xtream**.
2. Enter your server URL, username, and password.

### EPG (program guide)

After adding a playlist, tap the entry and set the **EPG URL** field to an XMLTV URL from your IPTV provider.

### Favorites and On Now row

Long-press **OK** on any channel in the guide to toggle it as a favorite. Favorited channels appear on the **On Now** home row showing the current program, progress bar, and time remaining. Short press opens the mini-player; long press shows full-screen or guide options.

---

## Webhooks

Xadarr can POST playback and watchlist events to any HTTP endpoint. Each URL has its own event selection.

### Add a webhook URL

1. **Settings → Plugins & Extensions → Progress Webhook** — toggle on.
2. Tap **+ Add URL**.
3. Enter the endpoint URL.
4. Select the events this URL should receive:
   - **Start / Pause / Resume / Stop** — playback lifecycle
   - **Progress** — periodic heartbeat while playing (configurable interval, default 30 s)
   - **Watchlist Add / Watchlist Remove** — fires immediately on watchlist changes
5. Tap **Save**. Repeat to add more URLs.

Different URLs can subscribe to different events — e.g. send playback events to Episeerr and watchlist events to a separate automation endpoint.

### Common endpoints

| Service | URL |
|---------|-----|
| Episeerr | `http://your-episeerr:5002/api/integration/xadarr/webhook` |
| Home Assistant | `http://homeassistant.local:8123/api/webhook/your-id` |
| n8n | `http://your-n8n:5678/webhook/your-path` |

No retry on delivery failure — check your endpoint logs if events are missing.

---

## Troubleshooting

**Live TV shows "CHANNELS 0" on Favorites**
No favorited channels yet. Long-press OK on any channel in the guide to add one.

**Guide is slow on low-powered devices (ONN, Fire TV Stick)**
Reduce playlist size or disable EPG loading for large categories.

**Settings not syncing to server**
Open `http://your-server:7979` in a browser to confirm the server is reachable. Force a sync via Settings → User Info & Account → Force Sync.

**Can't connect via ADB for sideloading**
Enable Network Debugging in Developer Options. On ONN TV: Settings → Device Preferences → About → Build number (tap 7×) → Developer Options → Network Debugging.

**Back button exits the app**
On the home screen with no panel open, Back exits — standard Android TV behavior.
