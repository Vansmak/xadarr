# Xadarr — User Guide

## Installation

**Sideload (APK)**
1. Enable "Install from unknown sources" in your device settings.
2. Copy the APK to a USB drive or transfer it over your network.
3. Open a file manager on your TV and tap the APK to install.
4. On Fire TV: use "Downloader" or "ES File Explorer" to install.

**In-app updates** (sideload build only): A banner appears on the home screen when a new version is available. Select it to download and install automatically.

---

## First-Time Setup

The fastest path is to restore from a sync server (xadarr-server or Episeerr). This pulls all settings, sources, addons, and playlists in one step.

1. **Create a profile** — on the profiles screen, add a name and optionally a PIN.
2. **Restore from server** (if you have one running):
   - Go to Settings → User Info & Account → **Connect to Server**.
   - Enter your server URL (e.g. `http://192.168.1.x:7979`) and select **Restore**.
   - All settings, playlists, and catalog connections are pulled down immediately.
3. **Add sources manually** if not restoring:
   - **IPTV**: Settings → Plugins & Extensions → add your M3U or Xtream URL.
   - **Home servers**: Settings → Sources → add Jellyfin, Emby, or Plex URL.
   - **Addons**: Settings → Plugins & Extensions → paste an addon or plugin URL.

---

## Home Screen

| Remote key | Action |
|------------|--------|
| D-pad | Move between rows and cards |
| OK / Enter | Open item |
| Back | Go back / close panel |

Rows shown (depending on your setup):
- Continue Watching (Trakt-based)
- Continue on *Server* (Jellyfin / Emby / Plex resume items)
- Watchlist
- Recent
- Catalog rows (movies, shows, by genre)

**Profiles**: select your avatar/icon at the top-right of the home screen to switch profiles.

---

## Live TV

### Opening the guide

- Navigate to **TV** in the top bar and press OK.
- The app opens with the guide visible, focused on your **Favorites** category.
- The video starts playing when you rest on a channel for half a second.

### Guide navigation

| Remote key | Action |
|------------|--------|
| Up / Down | Move between channels (plays channel after ~0.5s) |
| OK / Enter | Play selected channel immediately |
| D-pad Left | Open the **category / group panel** |
| Back | **Exit live TV** (return to home) |
| Back (while watching, guide closed) | Re-open the guide |

### Category panel

- Press **D-pad Left** from the channel list to slide the categories panel in from the left.
- Favorites, All, Recent, and any groups from your playlist are listed.
- Highlight a category and press OK to filter the channel list.
- Press **D-pad Right** to close the panel and return to the channel list.

### Channel guide (EPG)

- Press **D-pad Right** from a channel row to enter the program timeline.
- Highlight a past program with catchup available and press OK to play it.
- Press **Back** or **D-pad Left** from the timeline to return to the channel list.

### Favorites

- **Long-press OK** on any channel row to toggle it as a favorite.
- Favorites are saved per-profile and sync to your server.

---

## Video Player

### TV remote controls

| Remote key | Action |
|------------|--------|
| OK / Enter | Play / Pause |
| Back | Return to guide (live TV) or previous screen |
| D-pad Left / Right | Seek backward / forward |
| D-pad Up | Next audio track |
| D-pad Down | Next subtitle track |

### Player options

While something is playing, press **Menu** (☰) or long-press OK to open the options panel. From here you can:
- Switch stream source or quality
- Choose audio / subtitle track
- Toggle subtitles on/off

---

## Settings

Access settings from the left sidebar on the home screen or TV screen.

### Key sections

**User Info & Account**
- Connect to your sync server — enter its URL to restore all settings to a new device.
- Trakt.tv login for watchlist and continue-watching sync.
- Force sync — push local state to your server and pull the latest.

**Plugins & Extensions**
- Add / remove M3U and Xtream IPTV playlists.
- Add / remove third-party addon sources by URL.
- **Integration settings** (always visible at the bottom of this section):
  - Progress Webhook — toggle on/off, manage webhook URLs, set fire interval
  - Watchlist API — toggle the LAN JSON server on/off, set port
  - Watched Threshold — percentage at which playback counts as "watched" (50–99%)

**Sources**
- Add Jellyfin, Emby, or Plex server connections.
- Each connected server adds a "Continue on *Name*" row to your home screen.

**Player**
- Preferred audio language, subtitle language, decoder settings.

---

## Webhooks

Xadarr can POST playback and watchlist events to any HTTP endpoint. You can add multiple webhook URLs; each URL has its own event selection so different services receive only the events they need.

### Adding a webhook URL

1. Go to **Settings → Plugins & Extensions**.
2. Scroll to **Progress Webhook** and toggle it on.
3. Press the webhook URL row (or the **+ Add URL** row) to open the URL editor.
4. Enter the endpoint URL (e.g. `http://192.168.1.x:5002/api/integration/xadarr/webhook`).
5. Check or uncheck the events this URL should receive:
   - **Start / Pause / Resume / Stop** — playback lifecycle events
   - **Progress** — periodic heartbeat (fires every N seconds while playing)
   - **Watchlist Add / Watchlist Remove** — fires when items are added to or removed from the watchlist
6. Press **Save**. Repeat to add more URLs.

### Event notes

- **Progress** fires at the interval set in the *Progress interval* row (default 30 s). It is throttled to that interval even if multiple URLs subscribe to it.
- **Watchlist events** fire immediately when a watchlist change happens on any device — no polling needed.
- To send watchlist events to a different endpoint than playback events, add a second URL and enable only `watchlist.add` / `watchlist.remove` for that entry.
- Webhook delivery is best-effort. No retry on failure; check your endpoint logs if events are missing.

### Common endpoint examples

| Service | URL pattern |
|---------|-------------|
| Episeerr | `http://your-episeerr:5002/api/integration/xadarr/webhook` |
| Home Assistant webhook | `http://homeassistant.local:8123/api/webhook/your-webhook-id` |
| n8n webhook | `http://your-n8n:5678/webhook/your-path` |

---

## Troubleshooting

**Live TV shows "CHANNELS 0" on Favorites**
Your playlist hasn't finished loading yet, or you have no favorited channels. Long-press OK on any channel to add it to Favorites.

**Guide freezes or is slow to open**
This can happen on lower-powered devices (ONN, some Fire TV sticks). The EPG grid defers rendering by one frame to avoid blocking the UI — if it's still slow, consider reducing your playlist size or disabling EPG loading for large categories.

**Back button exits the app instead of going back a screen**
On Android TV, the system Back button behavior depends on which screen has focus. If you're on the home screen with no panel open, Back will exit the app. This is standard Android TV behavior.

**Can't connect to ADB for sideloading**
Enable ADB debugging in Developer Options on your TV. Some devices require you to confirm the connection on-screen the first time. On ONN TV: Settings → Device Preferences → About → Build (click 7 times) → Developer Options → USB Debugging / Network Debugging.
