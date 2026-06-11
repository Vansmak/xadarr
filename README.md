# Xadarr

XADAR stands for X-Ray Detection and Ranging — a real detection technology in the same family as radar (radio), sonar (sound), and lidar (light). The *arr ecosystem has always played on that naming lineage. Where those tools locate things in the physical world, Xadarr locates your media: finding it across every source you own, surfacing it on any screen, and keeping everything in sync across every device you pick up.

It is not Sonarr or Radarr. It does not download, manage, or automate anything. It is a media hub — a single place to browse and play from Jellyfin, Emby, Plex, IPTV playlists, and streaming add-ons, with everything staying in sync across every device you pick up.

---

## What it does

- Browse and stream from **Jellyfin, Emby, Plex**, and IPTV (M3U, Xtream, Stalker)
- **Watchlist, continue watching, and settings sync** across all your devices — automatically, over your local network or via Google Drive
- **No cloud account required for sync.** LAN peer-to-peer and xadarr-server work with no account at all. Google Drive sync optionally uses a Google account already on the device.
- **Live TV** with a fullscreen EPG overlay guide, a slide-in category sidebar (D-pad Left to open, D-pad Right to dismiss), channel favourites with sort options, last-channel return (D-pad Right while watching), and a picture-in-picture mini-player that keeps your stream alive when you navigate away from the guide
- **Trakt integration** — watchlist and continue-watching per profile
- **Episeerr integration** — request, rule assignment, and activity toasts directly on the TV
- **Frigate camera grid** — snapshot thumbnails and live HLS streams from your Frigate instance
- **Webhook system** — POST playback and watchlist events to any HTTP endpoint (Episeerr, Home Assistant, n8n, anything)
- **Launcher mode** — with a launcher app such as Projectivity, Xadarr can replace your Android TV home screen entirely

---

## How it is different

Most Android TV media apps are single-device. You configure IPTV on one TV, set up your server on another, and nothing talks to anything else. Xadarr treats every device as equal: your watchlist, your IPTV favorites, your server connections, your catalogue layout, your settings — all of it follows you.

| | Xadarr | Typical media app |
|---|---|---|
| Sync across devices | Yes, automatic | No |
| Self-hosted sync server | Optional (not required) | N/A |
| LAN peer-to-peer sync | Yes | No |
| Cloud account required | No | Often yes |
| Multiple source types | Jellyfin, Emby, Plex, IPTV, add-ons | Usually one |
| Browser web UI | Yes (same layout as TV app) | Rarely |
| Launcher capable | Yes | No |

Xadarr does not compete with Sonarr, Radarr, or Jellyfin. It sits in front of them — the screen you actually use.

---

## Quick start

### No server, same network

Install the APK on two devices. They find each other automatically over Wi-Fi via LAN sync. Watchlist and settings stay in sync with no configuration.

### No server, different networks

In Settings → Accounts → Google Drive Sync, connect a Google account. Settings back up to your Drive app folder (private, not shared). Restore on a new device by connecting the same account.

### Self-hosted (recommended for power users)

Run the sync server. It stores your full settings and serves a browser UI.

```yaml
services:
  xadarr-server:
    build: ./xadarr-server
    container_name: xadarr-server
    restart: unless-stopped
    ports:
      - "7979:7979"
    volumes:
      - /your/config/path:/data
```

Then in the app: Settings → Accounts → Sync Server URL → enter `http://your-server:7979`. Everything restores in one step. New device setup takes about thirty seconds.

Web UI at `http://your-server:7979` — same Home, Discover, Cameras, and Settings layout as the TV app.

---

## Install

Download the latest APK from [Releases](https://github.com/Vansmak/xadarr/releases) and sideload to your TV or Fire TV device.

Downloader code (for installing via the Downloader app): check the latest release notes.

The app checks for updates itself. Settings → Accounts → App Update.

---

## Source setup

After installing, go to Settings and connect your sources:

**Home servers** (Settings → Home Server)
Add your Jellyfin, Emby, or Plex server. Library rows, continue watching, and session progress reporting all work out of the box.

**IPTV** (Settings → IPTV)
Add an M3U playlist URL or Xtream credentials. Up to three playlists. EPG is loaded automatically if your provider supplies it.

**Add-ons** (Settings → Plugins & Extensions)
Stremio-compatible add-on URLs. Add as many as you need.

**Trakt** (Settings → Accounts)
Device-code auth. Once connected, watchlist and continue watching sync through Trakt per profile.

---

## Launcher mode

Xadarr can become your Android TV home screen.

1. Settings → Appearance → Launcher Mode — toggle on. The app opens Android's home app picker immediately.
2. Set Xadarr as default.
3. The All Apps tile at the end of the Apps row gives access to everything else installed.

Works best alongside a launcher app (Projectivity, etc.) for the system home button. Xadarr handles the media side; the launcher handles the rest.

The HOME intent-filter lives on a disabled `activity-alias`, not on the main activity. This means installing or updating Xadarr never clears your existing home app — it only becomes a home candidate after you explicitly enable Launcher Mode. The two-step process (toggle in app, then TV Settings → Apps → Default apps → Home app) is intentional.

---

## Sync in detail

Three tiers, in priority order:

**xadarr-server** — full sync including IPTV credentials and server connections. Requires running the server container. This is the path if you have Docker already.

**LAN peer-to-peer** — devices on the same Wi-Fi find each other automatically via mDNS (`_xadarr._tcp`). Full sync, no configuration, no server needed.

**Google Drive** — syncs watchlist, catalogues, settings, and IPTV favourites. IPTV playlist URLs, server credentials, and passwords are intentionally excluded from Drive backups — they stay on device. Useful for new device setup when you are not on your home network.

---

## Webhook system

POST playback and watchlist events to any URL.

**Events:** `start` · `pause` · `resume` · `stop` · `progress` · `watchlist.add` · `watchlist.remove`

Configure in Settings → Plugins & Extensions. Multiple URLs, each with independent event selection.

| Service | URL pattern |
|---------|-------------|
| Episeerr | `http://your-episeerr:5002/api/integration/xadarr/webhook` |
| Home Assistant | `http://homeassistant.local:8123/api/webhook/your-id` |
| n8n | `http://your-n8n:5678/webhook/your-path` |

`progress` fires at a configurable interval (default 30 s). No retry on failure.

---

## Build from source

Requirements: Android Studio or SDK command-line tools, JDK 17, Android SDK 35.

```bash
./gradlew :app:assembleSideloadDebug
./gradlew :app:installSideloadDebug

# Network ADB
adb connect <device-ip>:5555
adb install -r app/build/outputs/apk/sideload/debug/app-sideload-debug.apk
```

Copy `secrets.defaults.properties` to `secrets.properties` and add your TMDB and Trakt API keys. For signed release builds, copy `keystore.properties.template` to `keystore.properties`. Neither file is committed.

Build variants: `sideload` (APK with self-update), `play` (Play Store, self-update disabled).

---

## Screenshots

### Home and navigation

| Home screen | Discover tab |
|-------------|--------------|
| ![Home](screenshots/Screenshot_20260606-191615.png) | ![Discover](screenshots/Screenshot_20260606-191702.png) |

| Cameras row (Frigate) | Details page |
|-----------------------|--------------|
| ![Cameras](screenshots/Screenshot_20260606-191636.png) | ![Details](screenshots/details_v190.png) |

### Live TV

| EPG guide overlay | Category sidebar |
|-------------------|------------------|
| ![EPG](screenshots/Screenshot_20260606-191737.png) | ![Sidebar](screenshots/Screenshot_20260606-191751.png) |

| Mini-player (PiP) | Long-press context menu |
|-------------------|-------------------------|
| ![Mini-player](screenshots/Screenshot_20260606-191806.png) | ![Context menu](screenshots/Screenshot_20260606-192048.png) |

### Settings

| Webhook configuration | Catalogue management |
|-----------------------|----------------------|
| ![Webhooks](screenshots/Screenshot_20260606-191855.png) | ![Catalogues](screenshots/Screenshot_20260606-191925.png) |

### Mobile

| Mobile home | Mobile details |
|-------------|----------------|
| ![Mobile home](screenshots/mobile_home.webp) | ![Mobile details](screenshots/mobile_details.webp) |

---

## Credits

Xadarr is forked from [Arvio](https://github.com/arvio-app/arvio), released under the Apache 2.0 license. Xadarr's self-hosted sync, Episeerr integration, Frigate cameras, three-tier sync, and all other features documented here were built on top of that foundation.

---

## Policy

Xadarr is a media browser and player for user-configured sources. It does not host, distribute, or link to third-party media. Users supply their own services, playlists, add-ons, and URLs and are solely responsible for complying with applicable law.

Contributors must not submit copyrighted media, credentials, private keys, or links intended to enable unauthorised access to content.

See [PRIVACY.md](PRIVACY.md) for the privacy policy.

---

## License

Apache License 2.0. See [LICENSE](LICENSE).

---

## AI disclosure

This application was developed with significant AI assistance. Contributions should be reviewed, tested, and treated as normal source code changes.
