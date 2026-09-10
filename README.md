# Xadarr

> **This is a personal build, not a product. It is not meant for anyone else to install.**
>
> Xadarr is wired directly into my own infrastructure: **Episeerr** for rules, pending
> selections and watch tracking, **Dispatcharr** for IPTV, **Frigate** for cameras, **Home
> Assistant** for the smart-home and remote screens, and an **HDHomeRun** for OTA locals.
> Entire features assume those services exist, at my addresses, with my channel numbering, my
> lineup, and my hardcoded stream IDs. Nothing here is designed to degrade gracefully without
> them — several screens simply return errors.
>
> There are no supported releases, no install support, and no issue tracker I'm watching. I'm
> not taking bug reports or feature requests.
>
> The repo is public because the code is Apache 2.0 and forked from Arvio — not because it's
> something to adopt. Read it, copy from it, fork it, but expect to rewrite the integration
> layer for your own setup. **Everything below is documentation written for me.**

---

XADAR stands for X-Ray Detection and Ranging — a real detection technology in the same family as radar (radio), sonar (sound), and lidar (light). The *arr ecosystem has always played on that naming lineage. Where those tools locate things in the physical world, Xadarr locates your media: finding it across every source you own, surfacing it on any screen, and keeping everything in sync across every device you pick up.

In practice it is **a TiviMate-style live TV experience with my own Plex libraries built in
where the add-on VOD used to be, plus a find-and-add tool on the front.** Live TV is not a
section of the app — it *is* the app's home screen. Films and shows come from my own server
rather than scraped streaming catalogues. And anything I can't already watch, I can search for
and add from the couch: to the channel lineup, or to the library.

It is not Sonarr or Radarr — it doesn't download or manage anything itself. It's the screen in
front of them.

---

## What it does

**Live TV first.** The home screen is the guide, the way TiviMate does it: video fullscreen
underneath, EPG sliding up over it, category sidebar on a D-pad Left. Favourites, last-channel
return, hold-to-scroll through long channel lists, wrap-around at the ends, and a
picture-in-picture tile that keeps the stream alive when you walk off to another screen.
Channel favourites are anchored to names rather than numbers, so a lineup renumber can't
repoint them at something else.

**Plex is the VOD layer.** Instead of add-on catalogues, the Movies and Shows rows are my own
Plex libraries — poster grids, Continue Watching, New Episodes, Premiering, and Upcoming rows
built from what Episeerr and Sonarr actually know about my library, not from a public trending
feed. Watchlist reads the real Plex watchlist. Jellyfin and Emby still work as sources; Plex is
just what I point it at.

**Find — search and add.** One search across TMDB and my own library: play it if I have it,
add it if I don't. Movies go straight to Radarr on my 4K profile; shows go through an Episeerr
rule picker so I choose what gets grabbed. Adding never happens silently — watchlisting queues
a pending selection instead, so I stay in control of what lands on disk. The guide has its own
separate search for EPG and channels, including programmes that aren't in the lineup yet.

**Everything else it grew into:** Frigate camera grid with live HLS, a Home Assistant smart-home
screen, a universal remote that can drive another Xadarr device or a TV over HA, Episeerr toasts
(episode grabbed, ready, rule triggered, stream failover) anywhere in the app, a home-screen
activity widget, and launcher mode so it can replace the Android TV home screen outright.

**Sync across every surface** — TV, mobile, and web all share one settings blob, over the LAN
or through the sync server. No cloud account involved.

---

## Sync model

Every surface is equal: TV APK, mobile APK, xadarr-server web UI, and the Episeerr-embedded
web UI all share one settings blob. Watchlist, IPTV favourites, server connections, catalogue
layout — a change on any one shows up on the others.

### No server, same network

Install the APK on two devices. Enable **LAN Sync** in Settings → Network on each one. They find each other automatically over Wi-Fi — watchlist and settings stay in sync with no further configuration.

### No server, different networks

In Settings → Accounts → Google Drive Sync, connect a Google account. Settings back up to your Drive app folder (private, not shared). Restore on a new device by connecting the same account.

### Self-hosted

My own setup uses **Episeerr** as the sync server, not xadarr-server — same routes under
`/api/integration/xadarr/`. xadarr-server is the standalone alternative and is what this
compose block runs.

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

My own path — build, then copy to the share the TV boxes read from:

```bash
./gradlew :app:installSideloadDebug
cp app/build/outputs/apk/sideload/debug/app-sideload-debug.apk /mnt/usbshare/xadarr-latest.apk
```

The app has a self-update check at Settings → Accounts → App Update, pointed at this repo's
releases. Releases are for my own devices; they assume Episeerr, Dispatcharr and the rest are
reachable, and are not built or tested for anyone else's setup.

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
Device-code auth, still present, but **not what I use** — my watchlist comes from Plex via
Episeerr, and the Trakt reconcile path only runs when Plex isn't configured.

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

**LAN Sync** — peer-to-peer sync over Wi-Fi with no server required.

- Enable in Settings → Network → **LAN Sync** on each device that should participate. A device with LAN Sync off is completely independent — it won't sync with anything on the network.
- Discovery is automatic via mDNS (`_xadarr._tcp`). No IP addresses, no pairing prompts. When a new peer appears, settings are pushed immediately without waiting for a manual change.
- **Conflict resolution** — two modes:
  - **No master** (default, recommended): last change wins. Each snapshot is timestamped; whichever device made a change most recently takes precedence. Works well for most setups — just make changes on any device and they propagate everywhere.
  - **Master**: designate exactly one device as master (Settings → Network → LAN Sync Master). That device always wins — any peer that connects will adopt its settings regardless of when it last changed. Only set this on one device. If two devices are both set to master, whichever push arrives last wins and the master flag loses its meaning.
- The LAN Sync row in Settings shows live status: number of discovered peers and time since last sync.

**Google Drive** — syncs watchlist, catalogues, settings, and IPTV favourites. IPTV playlist URLs, server credentials, and passwords are intentionally excluded from Drive backups — they stay on device. Useful for new device setup when you are not on your home network.

---

## Webhook system

POST playback and watchlist events to any URL.

**Events:** `start` · `pause` · `resume` · `stop` · `progress` · `watchlist.add` · `watchlist.remove`

Configure in Settings → Plugins & Extensions. Multiple URLs, each with independent event selection.

| Service | URL pattern |
|---------|-------------|
| Sonarr / Radarr | `http://your-xadarr-server:7979/api/notify` |
| Home Assistant | `http://homeassistant.local:8123/api/webhook/your-id` |
| n8n | `http://your-n8n:5678/webhook/your-path` |

**Adding to the library:** in my setup this goes through Episeerr, not Trakt — Find's `+` calls
Radarr directly for films and hands shows to an Episeerr rule, and watchlisting queues a pending
selection rather than grabbing anything. Trakt-watchlist monitoring in Radarr/Sonarr is the
alternative if you have no Episeerr.

`progress` fires at a configurable interval (default 30 s). No retry on failure.

---

## Changelog

### v2.10
- **All Shows library browser sort** — now orders by newest episode added first, then by how recently you watched, instead of alphabetical.
- **Fix: Live TV guide stuck loading** — a rare timing issue where a background EPG refresh could cancel channel list processing before it finished, leaving the guide stuck on "Loading channels…" even though the data had loaded.
- **Fix: pending watchlist items** — tapping a watchlist item awaiting a rule selection now reliably opens the picker instead of sometimes opening details.
- **Fix: watchlist changes lost when Trakt is unreachable** — adds/removes now queue and retry automatically instead of staying local-only forever.
- **Fix: channel logos** — logos with transparent padding no longer show placeholder text bleeding through underneath.

### v2.7
- **IPTV group management** — three-state per group: Show / Hide / Remove. Long-press "Edit groups" in the TV guide category sidebar to manage all groups. New groups that appear in an M3U sync are automatically hidden and badged "NEW" until you explicitly show them.
- **Dispatcharr integration** — install the Dispatcharr Bridge addon (`http://your-xadarr-server:7979/dispatcharr-bridge`) to unlock group removal. Groups marked Remove are written to a blacklist file that Dispatcharr's maintenance script reads on the next sync run.
- **Empty catalogue rows stay visible** — addon and home-server rows no longer silently disappear when the addon is temporarily unreachable or returns no results. The row stays in place so you know it exists and can tell when it comes back.
- **Consistent APK signing** — sideload builds now use a stable release keystore so updates install over existing copies without forcing an uninstall.
- **SecureStorage decrypt fix** — decrypting a value no longer silently generates a new key, which would make the ciphertext permanently unreadable. Decrypt now returns null when the original key is absent.

### v2.6.2
- **Fix: home server credentials wiped on sync** — Jellyfin/Plex connection tokens are device-specific and can't be transferred. They are now stripped from all outgoing sync payloads (xadarr-server, LAN, Drive) and ignored on incoming ones. Previously, syncing a settings blob from another device silently blanked the token, requiring re-entry of server credentials.

### v2.6.1
- Network section added to mobile/tablet Settings (LAN Sync rows).

### v2.6
- **LAN Sync overhaul** — fixed a timing bug where the startup sync pull fired before mDNS peer discovery completed, causing initial sync to silently fail. Devices now push to peers immediately when a new peer is discovered, with no manual trigger needed.
- **LAN Sync conflict resolution** — two modes: **Master** (this device always wins; set in Settings → Network → LAN Sync Master) and **last-change-wins** (default, no master set). Each snapshot is timestamped; the most recently changed snapshot wins when no master is designated. Prevents a freshly installed device from overwriting an established device's settings.
- **LAN Sync status indicator** — the LAN Sync row in Settings → Network shows live peer count and time since last successful sync.
- **LAN Sync settings moved** — from Plugins & Extensions to the Network section, where they belong.
- LAN Sync toggle now actually starts/stops the peer-to-peer service; previously the toggle was cosmetic and the service ran regardless.

### v2.5
- Remote settings button navigates directly to Settings screen
- Android Settings row added to Settings screen

### v2.2
- User-configurable TMDB API key and Trakt Client ID/Secret
- Mobile Discover tab (replaces Watchlist in bottom nav)
- Mobile catalogue management redesigned as touch-friendly cards

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

### Web UI (xadarr-server)

| Home | Discover |
|------|----------|
| ![Web home](screenshots/home%20Xadarr-server.png) | ![Web discover](screenshots/home%20Xadarr-discover.png) |

| Search | Settings |
|--------|----------|
| ![Web search](screenshots/home%20Xadarr-search.png) | ![Web settings](screenshots/home%20Xadarr-settings.png) |

### Mobile

| Mobile home | Mobile details |
|-------------|----------------|
| ![Mobile home](screenshots/mobile_home.webp) | ![Mobile details](screenshots/mobile_details.webp) |

---

## Support

There isn't any. This is a personal build for my own house, and I'm not maintaining it as a
project for other people — no install help, no bug reports, no feature requests, no promises
that any commit leaves it in a working state.

Built with significant AI assistance: I designed the architecture and features, AI wrote much
of the code.

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
