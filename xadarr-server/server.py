"""
Xadarr Sync Server — standalone Flask server for Xadarr Android TV app.

Provides:
  - Sync API  (/api/integration/xadarr/*)
  - Dashboard API  (same surface as WebAppServer on the TV device)
  - Web UI at /
  - SSE player-state endpoint (for dashboard live updates)

Data lives in /data/ (mount as a Docker volume).
TMDB key and other server config are stored in /data/server_config.json.
"""

import json
import os
import time
import queue
import threading
import uuid
import requests
from collections import deque
from datetime import datetime
from pathlib import Path
from flask import Flask, request, jsonify, send_from_directory, Response, stream_with_context, redirect

# ── Paths ─────────────────────────────────────────────────────────────────────

DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
DATA_DIR.mkdir(parents=True, exist_ok=True)

SETTINGS_FILE      = DATA_DIR / "xadarr_settings.json"
WATCHLIST_FILE     = DATA_DIR / "watchlist.json"   # legacy — kept for migration only
HISTORY_FILE       = DATA_DIR / "history.json"
WEBHOOK_LOG_FILE   = DATA_DIR / "webhook_log.json"
SERVER_CONFIG_FILE = DATA_DIR / "server_config.json"

WEB_DIR = Path(__file__).parent / "web"

# ── App ───────────────────────────────────────────────────────────────────────

app = Flask(__name__, static_folder=str(WEB_DIR))
app.config["JSON_SORT_KEYS"] = False

# ── SSE broadcast ──────────────────────────────────────────────────────────────

_sse_queues: list[queue.Queue] = []
_sse_lock = threading.Lock()

_player_state: dict = {
    "isPlaying": False,
    "isPaused": False,
    "title": "",
    "episodeTitle": "",
    "overview": "",
    "positionMs": 0,
    "durationMs": 0,
    "streamUrl": "",
    "isLive": False,
}

_EPISEERR_EVENTS = frozenset({
    "episode.grabbed", "episode.ready",
    "rule.triggered", "rule.assigned",
    "watchlist.requested",
})


def _sse_push(payload: str):
    with _sse_lock:
        dead = []
        for q in _sse_queues:
            try:
                q.put_nowait(payload)
            except queue.Full:
                dead.append(q)
        for q in dead:
            _sse_queues.remove(q)


def _broadcast_player_state():
    _sse_push("data: " + json.dumps(_player_state) + "\n\n")


def _broadcast_episeerr_event(entry: dict):
    _sse_push("event: episeerr\ndata: " + json.dumps(entry) + "\n\n")


def _broadcast_watchlist():
    blob = _load_json(SETTINGS_FILE, {})
    count = len(_get_watchlist(blob))
    _sse_push("event: watchlist\ndata: " + json.dumps({"count": count}) + "\n\n")


# ── Notification queue ────────────────────────────────────────────────────────

_notifications: deque = deque(maxlen=100)
_notifications_lock = threading.Lock()


def _store_notification(entry: dict):
    with _notifications_lock:
        _notifications.appendleft(entry)
    _sse_push("event: notification\ndata: " + json.dumps(entry) + "\n\n")


def _make_notification(source: str, title: str, message: str | None, notif_type: str) -> dict:
    return {
        "id": str(uuid.uuid4()),
        "timestamp": datetime.utcnow().isoformat() + "Z",
        "source": source,
        "title": title,
        "message": message or "",
        "type": notif_type,
    }


def _parse_arr_webhook(data: dict) -> dict | None:
    """Normalise a Sonarr or Radarr native webhook payload to a notification dict.
    Returns None if the payload is not an arr webhook or is a test/ignored event."""
    event_type = data.get("eventType", "")
    if not event_type:
        return None

    # Map arr eventType → our type
    type_map = {
        "Grab":            "grab",
        "Download":        "ready",
        "HealthIssue":     "error",
        "HealthRestored":  "info",
        "ApplicationUpdate": "info",
        "Test":            "info",
        "MovieAdded":      "info",
        "SeriesAdd":       "info",
    }
    notif_type = type_map.get(event_type, "info")

    if "series" in data:
        source = "Sonarr"
        series = data.get("series") or {}
        title = series.get("title") or "Unknown"
        episodes = data.get("episodes") or []
        if episodes:
            ep = episodes[0]
            s_num = ep.get("seasonNumber", 0)
            e_num = ep.get("episodeNumber", 0)
            ep_title = ep.get("title", "")
            message = f"S{s_num:02d}E{e_num:02d}" + (f" – {ep_title}" if ep_title else "")
        else:
            message = event_type
    elif "movie" in data:
        source = "Radarr"
        movie = data.get("movie") or {}
        title = movie.get("title") or "Unknown"
        message = event_type
    else:
        return None

    return _make_notification(source, title, message, notif_type)


# ── Helpers ───────────────────────────────────────────────────────────────────

def _load_json(path: Path, default):
    try:
        return json.loads(path.read_text()) if path.exists() else default
    except Exception:
        return default


def _save_json(path: Path, data):
    path.write_text(json.dumps(data, indent=2))


def _load_server_config() -> dict:
    defaults = {
        "server_name": "Xadarr Server",
        "port": int(os.environ.get("PORT", 7979)),
        "episeerr_url": "",
    }
    cfg = _load_json(SERVER_CONFIG_FILE, {})
    return {**defaults, **cfg}


def _get_episeerr_url() -> str:
    # Settings blob first (written by TV app), fall back to server_config
    blob = _load_json(SETTINGS_FILE, {})
    url = blob.get("episeerr_url", "")
    if not url:
        url = _load_server_config().get("episeerr_url", "")
    return url.rstrip("/")


def _append_webhook_log(entry: dict, max_entries: int = 100):
    log = _load_json(WEBHOOK_LOG_FILE, [])
    log.insert(0, entry)
    _save_json(WEBHOOK_LOG_FILE, log[:max_entries])


# ── Settings blob helpers ─────────────────────────────────────────────────────

SETUP_PROFILE_ID = "default"


def _active_profile_id(blob: dict) -> str:
    return blob.get("activeProfileId") or SETUP_PROFILE_ID


def _get_blob() -> dict:
    blob = _load_json(SETTINGS_FILE, {})
    profiles = blob.get("profiles", [])
    if not any(p.get("id") == SETUP_PROFILE_ID for p in profiles):
        profiles.insert(0, {"id": SETUP_PROFILE_ID, "name": "Default",
                             "avatarColor": 4294901760, "avatarId": 1})
        blob["profiles"] = profiles
        blob.setdefault("activeProfileId", SETUP_PROFILE_ID)
    return blob


def _save_blob(blob: dict):
    _save_json(SETTINGS_FILE, blob)


def _get_connections(blob: dict) -> list:
    try:
        pid = _active_profile_id(blob)
        json_str = (blob.get("profileSettingsById", {})
                       .get(pid, {})
                       .get("homeServerConnectionJson", ""))
        return json.loads(json_str).get("connections", []) if json_str else []
    except Exception:
        return []


def _set_connections(blob: dict, connections: list):
    pid = _active_profile_id(blob)
    blob.setdefault("profileSettingsById", {}).setdefault(pid, {})
    blob["profileSettingsById"][pid]["homeServerConnectionJson"] = \
        json.dumps({"connections": connections})


def _get_iptv(blob: dict) -> dict:
    pid = _active_profile_id(blob)
    return blob.get("iptvByProfile", {}).get(pid, {"m3uUrl": "", "epgUrl": ""})


def _set_iptv(blob: dict, m3u_url: str, epg_url: str):
    pid = _active_profile_id(blob)
    blob.setdefault("iptvByProfile", {})[pid] = {"m3uUrl": m3u_url, "epgUrl": epg_url}
    blob["iptvM3uUrl"] = m3u_url
    blob["iptvEpgUrl"] = epg_url


def _get_addons(blob: dict) -> list:
    pid = _active_profile_id(blob)
    return blob.get("addonsByProfile", {}).get(pid, [])


def _set_addons(blob: dict, addons: list):
    pid = _active_profile_id(blob)
    blob.setdefault("addonsByProfile", {})[pid] = addons


def _get_frigate_url(blob: dict = None) -> str:
    if blob is None:
        blob = _load_json(SETTINGS_FILE, {})
    return blob.get("frigate_url", "").rstrip("/")


def _normalize_media_type(mt: str) -> str:
    if mt.lower() in ("tv", "series", "show"):
        return "show"
    return "movie"


# ── Watchlist blob helpers ────────────────────────────────────────────────────

def _get_watchlist(blob: dict) -> list:
    pid = _active_profile_id(blob)
    return blob.get("watchlistByProfile", {}).get(pid, [])


def _set_watchlist(blob: dict, items: list):
    pid = _active_profile_id(blob)
    blob.setdefault("watchlistByProfile", {})[pid] = items


def _migrate_watchlist_to_blob(blob: dict) -> bool:
    """One-time migration from watchlist.json → blob. Returns True if migration ran."""
    if not WATCHLIST_FILE.exists():
        return False
    if _get_watchlist(blob):
        return False  # blob already has data
    legacy = _load_json(WATCHLIST_FILE, [])
    if not legacy:
        return False
    converted = []
    for item in legacy:
        tmdb_id = item.get("id") or item.get("tmdbId")
        if not tmdb_id:
            continue
        converted.append({
            "tmdbId": int(tmdb_id),
            "title": item.get("title", ""),
            "mediaType": _normalize_media_type(item.get("mediaType", "movie")),
            "posterPath": item.get("posterPath") or item.get("image", ""),
            "backdropPath": item.get("backdropPath") or item.get("backdropUrl", ""),
            "addedAt": int(time.time() * 1000),
            "sourceOrder": 0,
        })
    _set_watchlist(blob, converted)
    return True


def _watchlist_to_web(items: list) -> list:
    """Map blob watchlist format → web UI format."""
    result = []
    for item in items:
        tmdb_id = item.get("tmdbId") or item.get("id")
        result.append({
            "id": tmdb_id,
            "tmdbId": tmdb_id,
            "title": item.get("title", ""),
            "mediaType": _normalize_media_type(item.get("mediaType", "movie")),
            "image": item.get("posterPath", ""),
            "posterPath": item.get("posterPath", ""),
            "backdropUrl": item.get("backdropPath", ""),
            "addedAt": item.get("addedAt"),
            "inWatchlist": True,
        })
    return result


# ── Catalogue blob helpers ────────────────────────────────────────────────────

def _get_catalogues(blob: dict) -> list:
    pid = _active_profile_id(blob)
    return blob.get("catalogsByProfile", {}).get(pid, [])


def _set_catalogues(blob: dict, catalogues: list):
    pid = _active_profile_id(blob)
    blob.setdefault("catalogsByProfile", {})[pid] = catalogues


# Synthetic catalogue rows managed by the web server (not in the TV blob)
_SYNTHETIC_CATS = [
    {"id": "continue_watching", "title": "Continue Watching", "kind": "STANDARD", "sourceType": "PREINSTALLED"},
    {"id": "watchlist",         "title": "Watchlist",         "kind": "STANDARD", "sourceType": "PREINSTALLED"},
]
_SYNTHETIC_IDS = {c["id"] for c in _SYNTHETIC_CATS}


def _get_web_row_visibility() -> dict:
    return _load_server_config().get("web_row_visibility", {})


def _save_web_row_visibility(visibility: dict):
    cfg = _load_server_config()
    cfg["web_row_visibility"] = visibility
    _save_json(SERVER_CONFIG_FILE, cfg)


# ── Home server auth helpers ──────────────────────────────────────────────────

def _detect_server_kind(server_url: str) -> str:
    try:
        r = requests.get(server_url.rstrip("/") + "/System/Info/Public", timeout=6)
        if r.ok:
            name = r.json().get("ProductName", "")
            return "EMBY" if "emby" in name.lower() else "JELLYFIN"
    except Exception:
        pass
    return "UNKNOWN"


def _auth_jellyfin_emby(server_url: str, username: str, password: str) -> dict:
    url = server_url.rstrip("/") + "/Users/AuthenticateByName"
    headers = {
        "Content-Type": "application/json",
        "X-Emby-Authorization": (
            'MediaBrowser Client="Xadarr", Device="XadarrServer", '
            'DeviceId="xadarr-server-001", Version="1.0"'
        ),
    }
    r = requests.post(url, json={"Username": username, "Pw": password},
                      headers=headers, timeout=10)
    r.raise_for_status()
    data = r.json()
    try:
        info = requests.get(server_url.rstrip("/") + "/System/Info/Public", timeout=6).json()
        server_name = info.get("ServerName", "")
    except Exception:
        server_name = ""
    return {
        "accessToken": data["AccessToken"],
        "userId": data["User"]["Id"],
        "userName": data["User"].get("Name", username),
        "serverName": server_name,
        "serverId": data.get("ServerId", ""),
    }


def _test_plex(server_url: str, token: str) -> dict:
    r = requests.get(
        server_url.rstrip("/") + "/identity",
        headers={"X-Plex-Token": token, "Accept": "application/json"},
        timeout=6,
    )
    r.raise_for_status()
    data = r.json().get("MediaContainer", {})
    return {
        "serverName": data.get("friendlyName", "Plex"),
        "serverId": data.get("machineIdentifier", ""),
    }


def _tmdb_get(path: str, params: dict | None = None) -> dict | None:
    key = (_load_json(SETTINGS_FILE, {}).get("tmdb_api_key") or
           os.environ.get("TMDB_API_KEY", ""))
    if not key:
        return None
    base_params = {"api_key": key}
    if params:
        base_params.update(params)
    try:
        r = requests.get(f"https://api.themoviedb.org/3{path}", params=base_params, timeout=10)
        r.raise_for_status()
        return r.json()
    except Exception:
        return None


def _cors(response: Response) -> Response:
    response.headers["Access-Control-Allow-Origin"] = "*"
    response.headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, OPTIONS"
    response.headers["Access-Control-Allow-Headers"] = "Content-Type"
    return response


@app.after_request
def after_request(response):
    return _cors(response)


@app.route("/", defaults={"path": ""})
@app.route("/<path:path>", methods=["GET"])
def serve_static(path):
    if path and (WEB_DIR / path).exists():
        resp = send_from_directory(str(WEB_DIR), path)
        if path.endswith((".js", ".css")):
            resp.headers["Cache-Control"] = "no-store"
        return resp
    return send_from_directory(str(WEB_DIR), "index.html")


# ── OPTIONS pre-flight ────────────────────────────────────────────────────────

@app.route("/api/<path:path>", methods=["OPTIONS"])
def options_handler(path):
    return _cors(Response("", 204))


# ── Server config (TMDB key, name, etc.) ─────────────────────────────────────

@app.route("/api/server/config", methods=["GET"])
def get_server_config():
    return jsonify(_load_server_config())


@app.route("/api/server/config", methods=["POST"])
def save_server_config():
    cfg = _load_server_config()
    cfg.update(request.get_json(force=True) or {})
    _save_json(SERVER_CONFIG_FILE, cfg)
    return jsonify({"ok": True})


# ── Sync API ──────────────────────────────────────────────────────────────────

@app.route("/api/integration/xadarr/settings", methods=["GET"])
def sync_get_settings():
    return jsonify(_load_json(SETTINGS_FILE, {}))


@app.route("/api/integration/xadarr/settings", methods=["PUT"])
def sync_put_settings():
    data = request.get_json(force=True) or {}
    _save_json(SETTINGS_FILE, data)
    return jsonify({"ok": True})


@app.route("/api/integration/xadarr/settings/backup", methods=["GET"])
def sync_backup_settings():
    settings = _load_json(SETTINGS_FILE, {})
    resp = Response(
        json.dumps(settings, indent=2),
        mimetype="application/json",
        headers={"Content-Disposition": "attachment; filename=xadarr_settings_backup.json"},
    )
    return resp


@app.route("/api/integration/xadarr/settings/backup", methods=["POST"])
def sync_restore_settings():
    data = request.get_json(force=True) or {}
    _save_json(SETTINGS_FILE, data)
    return jsonify({"ok": True, "restored": True})


@app.route("/api/integration/xadarr/webhook", methods=["POST"])
def sync_webhook():
    event = request.get_json(force=True) or {}
    history = _load_json(HISTORY_FILE, [])

    entry_event = event.get("event", "unknown")
    is_episeerr = entry_event in _EPISEERR_EVENTS
    entry = {
        "timestamp": datetime.utcnow().isoformat() + "Z",
        "event": entry_event,
        "title": event.get("title", ""),
        "episodeTitle": event.get("episodeTitle") or event.get("episode_title", ""),
        "mediaType": event.get("mediaType") or event.get("media_type", ""),
        "tmdbId": event.get("tmdbId") or event.get("tmdb_id"),
        "positionMs": event.get("positionMs", 0),
        "durationMs": event.get("durationMs", 0),
        "streamUrl": event.get("streamUrl", ""),
        "season": event.get("season"),
        "episode": event.get("episode"),
        "rule": event.get("rule"),
        "poster": event.get("poster"),
        "source": "episeerr" if is_episeerr else "xadarr",
    }
    progress_pct = float(event.get("progress_percent") or 0)
    is_watchlist = entry_event.startswith("watchlist.")
    if is_episeerr or is_watchlist or progress_pct >= 50:
        history.insert(0, entry)
        history = history[:500]
        _save_json(HISTORY_FILE, history)
    if is_episeerr:
        _broadcast_episeerr_event(entry)
        _episeerr_type_map = {
            "episode.grabbed": "grab", "episode.ready": "ready",
            "rule.triggered": "info", "rule.assigned": "info",
            "watchlist.requested": "info",
        }
        _store_notification(_make_notification(
            source="Episeerr",
            title=entry["title"],
            message=entry.get("rule") or (
                f"S{entry['season']:02d}E{entry['episode']:02d}"
                if entry.get("season") and entry.get("episode") else None
            ),
            notif_type=_episeerr_type_map.get(entry_event, "info"),
        ))

    # Auto-add to watchlist when episeerr requests a pending series
    if entry_event == "watchlist.requested":
        tmdb_id = event.get("tmdb_id") or event.get("tmdbId")
        title    = event.get("title", "")
        if tmdb_id and title:
            blob = _get_blob()
            _migrate_watchlist_to_blob(blob)
            wl = _get_watchlist(blob)
            already = any(str(w.get("tmdbId") or w.get("id")) == str(tmdb_id) for w in wl)
            if not already:
                new_item = {
                    "tmdbId": int(tmdb_id),
                    "title": title,
                    "mediaType": "tv",
                    "addedAt": int(time.time() * 1000),
                    "sourceOrder": 0,
                    "posterPath": "",
                    "backdropPath": "",
                }
                tmdb_data = _tmdb_get(f"/tv/{tmdb_id}")
                if tmdb_data and tmdb_data.get("poster_path"):
                    new_item["posterPath"] = "https://image.tmdb.org/t/p/w342" + tmdb_data["poster_path"]
                if tmdb_data and tmdb_data.get("backdrop_path"):
                    new_item["backdropPath"] = "https://image.tmdb.org/t/p/w780" + tmdb_data["backdrop_path"]
                wl.insert(0, new_item)
                _set_watchlist(blob, wl)
                _save_blob(blob)
                _broadcast_watchlist()

    _append_webhook_log({
        "timestamp": entry["timestamp"],
        "event": entry["event"],
        "url": "inbound",
        "status_code": 200,
        "success": True,
        "error": None,
        "title": entry["title"],
        "direction": "inbound",
    })

    global _player_state
    if entry_event in ("start", "progress"):
        _player_state = {
            "isPlaying": True,
            "isPaused": False,
            "title": entry["title"],
            "episodeTitle": entry["episodeTitle"],
            "overview": event.get("overview", ""),
            "positionMs": entry["positionMs"],
            "durationMs": entry["durationMs"],
            "streamUrl": entry["streamUrl"],
            "isLive": event.get("isLive", False),
        }
    elif entry_event == "pause":
        _player_state["isPaused"] = True
        _player_state["isPlaying"] = False
    elif entry_event in ("stop", "finish"):
        _player_state = {**_player_state, "isPlaying": False, "isPaused": False}

    _broadcast_player_state()
    return jsonify({"ok": True})


@app.route("/api/integration/xadarr/status", methods=["GET"])
def sync_status():
    cfg = _load_server_config()
    blob = _get_blob()
    _migrate_watchlist_to_blob(blob)
    return jsonify({
        "server": cfg.get("server_name", "Xadarr Server"),
        "version": "1.0.0",
        "watchlist_count": len(_get_watchlist(blob)),
        "history_count": len(_load_json(HISTORY_FILE, [])),
        "tmdb_configured": bool(_load_json(SETTINGS_FILE, {}).get("tmdb_api_key") or os.environ.get("TMDB_API_KEY")),
    })


# ── Webhook: test + log ───────────────────────────────────────────────────────

@app.route("/api/webhook/test", methods=["POST"])
def webhook_test():
    blob = _load_json(SETTINGS_FILE, {})
    headers_dict = blob.get("webhook_headers") or {}
    req_headers = {"Content-Type": "application/json", **headers_dict}

    body = request.get_json(silent=True) or {}
    target_url = body.get("url", "").strip()
    events = body.get("events") or _ALL_WEBHOOK_EVENTS

    if not target_url:
        return jsonify({"ok": False, "error": "No URL specified"}), 400

    event_name = events[0] if events else "start"
    if event_name in ("watchlist.add", "watchlist.remove"):
        payload = {
            "event": event_name,
            "title": "Test Item",
            "tmdb_id": 1,
            "media_type": "show",
        }
    else:
        payload = {
            "event": event_name,
            "title": "Test Event",
            "media_type": "episode",
            "progress_percent": 0,
        }

    try:
        r = requests.post(target_url, json=payload, headers=req_headers, timeout=5)
        _append_webhook_log({
            "timestamp": datetime.utcnow().isoformat() + "Z",
            "event": event_name,
            "url": target_url,
            "status_code": r.status_code,
            "success": r.ok,
            "error": None,
        })
        return jsonify({"ok": r.ok, "status_code": r.status_code})
    except Exception as e:
        _append_webhook_log({
            "timestamp": datetime.utcnow().isoformat() + "Z",
            "event": event_name,
            "url": target_url,
            "status_code": None,
            "success": False,
            "error": str(e),
        })
        return jsonify({"ok": False, "error": str(e)})


@app.route("/api/webhook/log", methods=["GET"])
def webhook_log():
    log = _load_json(WEBHOOK_LOG_FILE, [])
    return jsonify(log[:20])


# ── Episeerr proxy endpoints ───────────────────────────────────────────────────

def _episeerr_proxy(path: str, method: str = "GET", body: dict | None = None, timeout: int = 15):
    base = _get_episeerr_url()
    if not base:
        return jsonify({"error": "Episeerr URL not configured"}), 503
    try:
        url = f"{base}{path}"
        if method == "GET":
            r = requests.get(url, timeout=timeout)
        else:
            r = requests.post(url, json=body, timeout=timeout)
        r.raise_for_status()
        return jsonify(r.json())
    except requests.RequestException as exc:
        return jsonify({"error": str(exc)}), 502


_managed_series_cache: list = []
_managed_series_cache_ts: float = 0.0
_MANAGED_SERIES_TTL = 300


@app.route("/api/episeerr/managed-series", methods=["GET"])
def episeerr_managed_series():
    global _managed_series_cache, _managed_series_cache_ts
    now = time.time()
    if now - _managed_series_cache_ts < _MANAGED_SERIES_TTL and _managed_series_cache:
        return jsonify(_managed_series_cache)
    base = _get_episeerr_url()
    if not base:
        return jsonify({"error": "Episeerr URL not configured"}), 503
    try:
        r = requests.get(f"{base}/api/managed-series", timeout=45)
        r.raise_for_status()
        data = r.json()
        _managed_series_cache = data
        _managed_series_cache_ts = now
        return jsonify(data)
    except requests.RequestException as exc:
        if _managed_series_cache:
            return jsonify(_managed_series_cache)
        return jsonify({"error": str(exc)}), 502


@app.route("/api/episeerr/pending", methods=["GET"])
def episeerr_pending():
    return _episeerr_proxy("/api/integration/xadarr/pending")


@app.route("/api/episeerr/rules", methods=["GET"])
def episeerr_rules():
    return _episeerr_proxy("/api/rules-list")


@app.route("/api/episeerr/assign", methods=["POST"])
def episeerr_assign():
    body = request.get_json(force=True) or {}
    return _episeerr_proxy("/api/assign-pending-rule", method="POST", body=body)


# ── Setup: home servers ───────────────────────────────────────────────────────

@app.route("/api/setup/servers", methods=["GET"])
def setup_get_servers():
    blob = _get_blob()
    servers = _get_connections(blob)
    safe = [{k: v for k, v in s.items() if k not in ("accessToken", "accountToken")}
            for s in servers]
    return jsonify(safe)


@app.route("/api/setup/servers/connect", methods=["POST"])
def setup_connect_server():
    body = request.get_json(force=True) or {}
    kind = body.get("kind", "").upper()
    server_url = body.get("url", "").rstrip("/")
    display_name = body.get("displayName", "")

    if not server_url:
        return jsonify({"error": "url required"}), 400

    try:
        if kind in ("JELLYFIN", "EMBY", ""):
            username = body.get("username", "")
            password = body.get("password", "")
            if not kind:
                kind = _detect_server_kind(server_url)
            if kind == "UNKNOWN":
                return jsonify({"error": "Could not detect server type. Specify kind."}), 400
            auth = _auth_jellyfin_emby(server_url, username, password)
            conn = {
                "enabled": True,
                "connectionId": f"{kind}:{server_url}:{auth['userId']}",
                "serverUrl": server_url,
                "displayName": display_name or auth["serverName"] or server_url,
                "serverName": auth["serverName"],
                "serverKind": kind,
                "serverId": auth["serverId"],
                "userId": auth["userId"],
                "userName": auth["userName"],
                "accessToken": auth["accessToken"],
                "accountToken": "",
                "collections": [],
                "lastConnectedAt": int(datetime.utcnow().timestamp() * 1000),
            }
        elif kind == "PLEX":
            token = body.get("token", "")
            if not token:
                return jsonify({"error": "token required for Plex"}), 400
            info = _test_plex(server_url, token)
            conn = {
                "enabled": True,
                "connectionId": f"PLEX:{server_url}",
                "serverUrl": server_url,
                "displayName": display_name or info["serverName"] or server_url,
                "serverName": info["serverName"],
                "serverKind": "PLEX",
                "serverId": info["serverId"],
                "userId": "",
                "userName": "",
                "accessToken": token,
                "accountToken": token,
                "collections": [],
                "lastConnectedAt": int(datetime.utcnow().timestamp() * 1000),
            }
        else:
            return jsonify({"error": f"Unknown kind: {kind}"}), 400

        blob = _get_blob()
        connections = _get_connections(blob)
        connections = [c for c in connections if c.get("connectionId") != conn["connectionId"]]
        connections.append(conn)
        _set_connections(blob, connections)
        _save_blob(blob)

        safe = {k: v for k, v in conn.items() if k not in ("accessToken", "accountToken")}
        return jsonify({"ok": True, "connection": safe})

    except requests.HTTPError as e:
        return jsonify({"error": f"Auth failed: {e.response.status_code}"}), 400
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route("/api/setup/servers/<connection_id>", methods=["DELETE"])
def setup_delete_server(connection_id):
    blob = _get_blob()
    connections = [c for c in _get_connections(blob)
                   if c.get("connectionId") != connection_id]
    _set_connections(blob, connections)
    _save_blob(blob)
    return jsonify({"ok": True})


# ── Setup: IPTV ───────────────────────────────────────────────────────────────

@app.route("/api/setup/iptv", methods=["GET"])
def setup_get_iptv():
    return jsonify(_get_iptv(_get_blob()))


@app.route("/api/setup/iptv", methods=["POST"])
def setup_save_iptv():
    body = request.get_json(force=True) or {}
    blob = _get_blob()
    _set_iptv(blob, body.get("m3uUrl", ""), body.get("epgUrl", ""))
    _save_blob(blob)
    return jsonify({"ok": True})


# ── Setup: addons ─────────────────────────────────────────────────────────────

@app.route("/api/setup/addons", methods=["GET"])
def setup_get_addons():
    return jsonify(_get_addons(_get_blob()))


@app.route("/api/setup/addons", methods=["POST"])
def setup_add_addon():
    body = request.get_json(force=True) or {}
    manifest_url = body.get("url", "").strip().rstrip("/")
    if not manifest_url:
        return jsonify({"error": "url required"}), 400

    base_url = manifest_url.rstrip("/")
    manifest_fetch_url = base_url if base_url.endswith("manifest.json") else base_url + "/manifest.json"

    try:
        r = requests.get(manifest_fetch_url, timeout=10)
        r.raise_for_status()
        manifest = r.json()
    except Exception as e:
        return jsonify({"error": f"Could not fetch manifest: {e}"}), 400

    addon = {
        "id": manifest.get("id", manifest_url),
        "name": manifest.get("name", "Unknown"),
        "version": manifest.get("version", "0.0.1"),
        "description": manifest.get("description", ""),
        "isInstalled": True,
        "isEnabled": True,
        "type": "COMMUNITY",
        "runtimeKind": "STREMIO",
        "installSource": "DIRECT_URL",
        "url": base_url,
        "logo": manifest.get("logo"),
        "transportUrl": base_url,
    }

    blob = _get_blob()
    addons = _get_addons(blob)
    addons = [a for a in addons if a.get("id") != addon["id"]]
    addons.append(addon)
    _set_addons(blob, addons)
    _save_blob(blob)
    return jsonify({"ok": True, "addon": addon})


@app.route("/api/setup/addons/<path:addon_id>", methods=["DELETE"])
def setup_delete_addon(addon_id):
    blob = _get_blob()
    addons = [a for a in _get_addons(blob) if a.get("id") != addon_id]
    _set_addons(blob, addons)
    _save_blob(blob)
    return jsonify({"ok": True})


# ── Setup: profile name ───────────────────────────────────────────────────────

@app.route("/api/setup/profile", methods=["GET"])
def setup_get_profile():
    blob = _get_blob()
    profiles = blob.get("profiles", [])
    profile = next((p for p in profiles if p.get("id") == SETUP_PROFILE_ID), {})
    return jsonify({"name": profile.get("name", "Default")})


@app.route("/api/setup/profile", methods=["POST"])
def setup_save_profile():
    body = request.get_json(force=True) or {}
    name = body.get("name", "").strip() or "Default"
    blob = _get_blob()
    profiles = blob.get("profiles", [])
    updated = False
    for p in profiles:
        if p.get("id") == SETUP_PROFILE_ID:
            p["name"] = name
            updated = True
    if not updated:
        profiles.insert(0, {"id": SETUP_PROFILE_ID, "name": name,
                             "avatarColor": 4294901760, "avatarId": 1})
        blob["profiles"] = profiles
    _save_blob(blob)
    return jsonify({"ok": True, "name": name})


# ── Dashboard: settings (Xadarr app settings blob) ────────────────────────────

_ALL_WEBHOOK_EVENTS = ["start", "pause", "resume", "stop", "progress", "watchlist.add", "watchlist.remove"]

_WEBHOOK_DEFAULTS = {
    "webhook_enabled": False,
    "webhook_urls": [],
    "webhook_interval_seconds": "30",
    "webhook_completion_percent": 80,
    "webhook_headers": {},
    "watchlist_api_enabled": False,
    "watchlist_api_port": "7979",
}


def _resolve_webhook_urls(blob, event_filter=None):
    raw = blob.get("webhook_urls") or []
    if not raw:
        legacy = blob.get("webhook_url", "").strip()
        if legacy:
            raw = [legacy]
    result = []
    for entry in raw:
        if isinstance(entry, str):
            url = entry.strip()
            events = _ALL_WEBHOOK_EVENTS
        else:
            url = entry.get("url", "").strip()
            events = entry.get("events", _ALL_WEBHOOK_EVENTS)
        if url and (event_filter is None or event_filter in events):
            result.append(url)
    return result


def _fire_watchlist_webhook(event_name, item):
    blob = _load_json(SETTINGS_FILE, {})
    if not blob.get("webhook_enabled", False):
        return
    urls = _resolve_webhook_urls(blob, event_filter=event_name)
    if not urls:
        return
    headers_dict = blob.get("webhook_headers") or {}
    req_headers = {"Content-Type": "application/json", **headers_dict}
    tmdb_id = item.get("tmdbId") or item.get("id") or item.get("tmdb_id")
    payload = {
        "event": event_name,
        "title": item.get("title", ""),
        "tmdb_id": tmdb_id,
        "media_type": "movie" if _normalize_media_type(item.get("mediaType", "")) == "movie" else "tv",
    }

    def _fire():
        for url in urls:
            try:
                r = requests.post(url, json=payload, headers=req_headers, timeout=5)
                _append_webhook_log({
                    "timestamp": datetime.utcnow().isoformat() + "Z",
                    "event": event_name,
                    "url": url,
                    "status_code": r.status_code,
                    "success": r.ok,
                    "error": None,
                })
            except Exception as e:
                _append_webhook_log({
                    "timestamp": datetime.utcnow().isoformat() + "Z",
                    "event": event_name,
                    "url": url,
                    "status_code": None,
                    "success": False,
                    "error": str(e),
                })

    threading.Thread(target=_fire, daemon=True).start()


@app.route("/api/settings", methods=["GET"])
def get_settings():
    blob = _load_json(SETTINGS_FILE, {})
    for k, v in _WEBHOOK_DEFAULTS.items():
        blob.setdefault(k, v)
    return jsonify(blob)


@app.route("/api/settings", methods=["POST"])
def post_settings():
    data = request.get_json(force=True) or {}
    existing = _load_json(SETTINGS_FILE, {})
    existing.update(data)
    _save_json(SETTINGS_FILE, existing)
    return jsonify({"ok": True})


# ── Dashboard: watchlist (blob-backed) ──────────────────────────────────────

@app.route("/api/media/watchlist", methods=["GET"])
def get_watchlist():
    blob = _get_blob()
    _migrate_watchlist_to_blob(blob)
    items = _get_watchlist(blob)
    return jsonify(_watchlist_to_web(items))


@app.route("/api/media/watchlist", methods=["POST"])
def add_to_watchlist():
    item = request.get_json(force=True) or {}
    tmdb_id = item.get("id") or item.get("tmdbId")
    if not tmdb_id:
        return jsonify({"error": "missing id"}), 400
    tmdb_id = int(tmdb_id)

    media_type = _normalize_media_type(item.get("mediaType") or "movie")
    poster_path = item.get("posterPath") or item.get("image") or ""
    backdrop_path = item.get("backdropPath") or item.get("backdropUrl") or ""
    title = item.get("title", "")

    # Fetch from TMDB if poster not provided
    if not poster_path:
        endpoint = "/movie/" if media_type == "movie" else "/tv/"
        tmdb_data = _tmdb_get(endpoint + str(tmdb_id))
        if tmdb_data:
            if tmdb_data.get("poster_path"):
                poster_path = "https://image.tmdb.org/t/p/w342" + tmdb_data["poster_path"]
            if tmdb_data.get("backdrop_path"):
                backdrop_path = "https://image.tmdb.org/t/p/w780" + tmdb_data["backdrop_path"]
            if not title:
                title = tmdb_data.get("title") or tmdb_data.get("name") or ""

    blob = _get_blob()
    _migrate_watchlist_to_blob(blob)
    wl = _get_watchlist(blob)
    exists = any(int(w.get("tmdbId") or w.get("id") or 0) == tmdb_id for w in wl)
    if not exists:
        new_item = {
            "tmdbId": tmdb_id,
            "title": title,
            "mediaType": media_type,
            "posterPath": poster_path,
            "backdropPath": backdrop_path,
            "addedAt": int(time.time() * 1000),
            "sourceOrder": 0,
        }
        wl.insert(0, new_item)
        _set_watchlist(blob, wl)
        _save_blob(blob)
        _fire_watchlist_webhook("watchlist.add", new_item)
        _broadcast_watchlist()
    return jsonify({"ok": True})


@app.route("/api/media/watchlist/<media_type>/<int:item_id>", methods=["DELETE"])
def remove_from_watchlist(media_type, item_id):
    blob = _get_blob()
    _migrate_watchlist_to_blob(blob)
    wl = _get_watchlist(blob)
    removed = [w for w in wl if int(w.get("tmdbId") or w.get("id") or 0) == item_id]
    wl = [w for w in wl if int(w.get("tmdbId") or w.get("id") or 0) != item_id]
    _set_watchlist(blob, wl)
    _save_blob(blob)
    if removed:
        removed[0]["mediaType"] = media_type
        _fire_watchlist_webhook("watchlist.remove", removed[0])
    _broadcast_watchlist()
    return jsonify({"ok": True})


# ── Dashboard: continue watching ─────────────────────────────────────────────

@app.route("/api/media/continue-watching", methods=["GET"])
def get_continue_watching():
    blob = _get_blob()
    pid = _active_profile_id(blob)
    items = blob.get("localContinueWatchingByProfile", {}).get(pid, [])
    result = []
    for item in items:
        result.append({
            "id": item.get("id"),
            "title": item.get("title", ""),
            "episode": item.get("episode"),
            "episodeTitle": item.get("episodeTitle", ""),
            "season": item.get("season"),
            "mediaType": item.get("mediaType", ""),
            "image": item.get("posterPath", ""),
            "backdropUrl": item.get("backdropPath", ""),
            "progress": item.get("progress", 0),
            "durationSeconds": item.get("durationSeconds", 0),
            "resumePositionSeconds": item.get("resumePositionSeconds", 0),
        })
    return jsonify(result)


# ── Dashboard: history ────────────────────────────────────────────────────────

@app.route("/api/media/history", methods=["GET"])
def get_history():
    history = _load_json(HISTORY_FILE, [])
    limit = int(request.args.get("limit", 50))
    return jsonify(history[:limit])


@app.route("/api/media/history", methods=["DELETE"])
def clear_history():
    _save_json(HISTORY_FILE, [])
    return jsonify({"ok": True})


# ── Dashboard: TMDB search + trending ────────────────────────────────────────

def _map_tmdb_item(item: dict, media_type: str | None = None) -> dict:
    mt = media_type or item.get("media_type", "movie")
    return {
        "id": item["id"],
        "title": item.get("title") or item.get("name", ""),
        "overview": item.get("overview", ""),
        "image": "https://image.tmdb.org/t/p/w342" + item["poster_path"] if item.get("poster_path") else "",
        "backdropUrl": "https://image.tmdb.org/t/p/w780" + item["backdrop_path"] if item.get("backdrop_path") else "",
        "mediaType": "show" if mt == "tv" else "movie",
        "year": (item.get("release_date") or item.get("first_air_date") or "")[:4],
        "rating": item.get("vote_average", 0),
        "popularity": item.get("popularity", 0),
        "inWatchlist": False,
    }


def _mark_watchlist(items: list[dict]) -> list[dict]:
    blob = _get_blob()
    _migrate_watchlist_to_blob(blob)
    wl = _get_watchlist(blob)
    wl_ids = {str(w.get("tmdbId") or w.get("id") or "") for w in wl}
    for item in items:
        item["inWatchlist"] = str(item.get("id", "")) in wl_ids
    return items


@app.route("/api/media/detail", methods=["GET"])
def media_detail():
    tmdb_id = request.args.get("id", "").strip()
    media_type = request.args.get("type", "movie").strip()
    if not tmdb_id:
        return jsonify({"error": "id required"}), 400
    endpoint = f"/tv/{tmdb_id}" if media_type == "tv" else f"/movie/{tmdb_id}"
    data = _tmdb_get(endpoint, {"append_to_response": "credits"})
    if not data:
        return jsonify({"error": "not found"}), 404
    item = _map_tmdb_item(data, media_type)
    item["genres"] = [g["name"] for g in data.get("genres", [])]
    item["runtime"] = data.get("runtime") or (data.get("episode_run_time") or [None])[0]
    item["tagline"] = data.get("tagline", "")
    item["status"] = data.get("status", "")
    cast = data.get("credits", {}).get("cast", [])
    item["cast"] = [c["name"] for c in cast[:6]]
    return jsonify(_mark_watchlist([item])[0])


@app.route("/api/media/search", methods=["GET"])
def search_media():
    q = request.args.get("q", "").strip()
    if len(q) < 2:
        return jsonify([])
    data = _tmdb_get("/search/multi", {"query": q, "page": 1})
    if not data:
        return jsonify({"error": "TMDB API key not configured"}), 503
    items = [
        _map_tmdb_item(r)
        for r in data.get("results", [])
        if r.get("media_type") in ("movie", "tv") and r.get("poster_path")
    ]
    return jsonify(_mark_watchlist(items))


_FRANCHISE_KEYWORDS = {
    "marvel": "7153", "dc universe": "9714", "star wars": "1562",
    "james bond": "83", "harry potter": "116", "jurassic park": "803",
    "mission impossible": "585", "john wick": "199879", "the matrix": "133",
    "alien vs predator": "283", "pirates of the caribbean": "270",
    "terminator": "50969", "lord of the rings": "2382", "x-men": "7194",
    "hunger games": "8374", "avatar": "186574", "dune": "11166",
    "indiana jones": "695", "the godfather": "256", "transformers": "5765",
}

@app.route("/api/media/discover", methods=["GET"])
def discover_by_filter():
    provider_id  = request.args.get("provider_id",  "").strip()
    genre_id     = request.args.get("genre_id",     "").strip()
    year_start   = request.args.get("year_start",   "").strip()
    year_end     = request.args.get("year_end",     "").strip()
    franchise    = request.args.get("franchise",    "").strip().lower()
    if not provider_id and not genre_id and not year_start and not franchise:
        return jsonify({"movies": [], "shows": []})

    if franchise:
        keyword_id = _FRANCHISE_KEYWORDS.get(franchise)
        if not keyword_id:
            return jsonify({"movies": [], "shows": []})
        data = _tmdb_get("/discover/movie", {
            "with_keywords": keyword_id,
            "sort_by": "popularity.desc",
            "page": 1,
        }) or {}
        movies = [_map_tmdb_item(r, "movie") for r in data.get("results", []) if r.get("poster_path")][:20]
        return jsonify({"movies": _mark_watchlist(movies), "shows": []})
    tv_genre_map = {
        "28": "10759", "14": "10765", "878": "10765", "10752": "10768",
    }
    base_movie = {"sort_by": "popularity.desc", "vote_count.gte": 20}
    base_tv    = {"sort_by": "popularity.desc", "vote_count.gte": 20}
    if provider_id:
        base_movie.update({"with_watch_providers": provider_id, "watch_region": "US"})
        base_tv.update({"with_watch_providers": provider_id, "watch_region": "US"})
    if genre_id:
        base_movie["with_genres"] = genre_id
        base_tv["with_genres"]    = tv_genre_map.get(genre_id, genre_id)
    if year_start and year_end:
        base_movie.update({
            "primary_release_date.gte": f"{year_start}-01-01",
            "primary_release_date.lte": f"{year_end}-12-31",
        })
        base_tv.update({
            "first_air_date.gte": f"{year_start}-01-01",
            "first_air_date.lte": f"{year_end}-12-31",
        })

    def fetch_pages(endpoint, params, pages=3):
        seen_ids = set()
        results = []
        for p in range(1, pages + 1):
            data = _tmdb_get(endpoint, {**params, "page": p}) or {}
            for r in data.get("results", []):
                if r.get("poster_path") and r["id"] not in seen_ids:
                    seen_ids.add(r["id"])
                    results.append(r)
            if p >= (data.get("total_pages") or 1):
                break
        return results

    raw_movies = fetch_pages("/discover/movie", base_movie)
    raw_shows  = fetch_pages("/discover/tv",    base_tv)
    movies = [_map_tmdb_item(r, "movie") for r in raw_movies]
    shows  = [_map_tmdb_item(r, "tv")    for r in raw_shows]
    return jsonify({
        "movies": _mark_watchlist(movies),
        "shows":  _mark_watchlist(shows),
    })


@app.route("/api/media/search-discover", methods=["GET"])
def search_discover():
    from datetime import datetime, timedelta

    type_filter = request.args.get("type", "all").strip()   # all | movies | tv
    genre_id    = request.args.get("genre_id", "").strip()

    today             = datetime.now().strftime("%Y-%m-%d")
    one_year_ago      = (datetime.now() - timedelta(days=365)).strftime("%Y-%m-%d")
    three_months_ago  = (datetime.now() - timedelta(days=90)).strftime("%Y-%m-%d")

    # Map movie genre IDs to TV genre equivalents (used when type=all)
    _TV_GENRE_MAP = {
        "28": "10759",    # Action → Action & Adventure
        "14": "10765",    # Fantasy → Sci-Fi & Fantasy
        "878": "10765",   # Sci-Fi → Sci-Fi & Fantasy
        "10752": "10768", # War → War & Politics
    }
    movie_genre = genre_id or None
    tv_genre    = _TV_GENRE_MAP.get(genre_id, genre_id) if genre_id else None

    def _fetch(endpoint, sort_by, extra):
        params = {"sort_by": sort_by, "page": 1, **extra}
        data = _tmdb_get(endpoint, params) or {}
        return [r for r in data.get("results", []) if r.get("poster_path")][:20]

    def fetch_m(sort_by, extra):
        if movie_genre:
            extra = {**extra, "with_genres": movie_genre}
        return _fetch("/discover/movie", sort_by, extra)

    def fetch_t(sort_by, extra):
        if tv_genre:
            extra = {**extra, "with_genres": tv_genre}
        return _fetch("/discover/tv", sort_by, extra)

    def interleave(m_raw, t_raw):
        m = [_map_tmdb_item(r, "movie") for r in m_raw]
        t = [_map_tmdb_item(r, "tv")    for r in t_raw]
        out = []
        for i in range(max(len(m), len(t))):
            if i < len(m): out.append(m[i])
            if i < len(t): out.append(t[i])
        return out[:20]

    def build_row(sort_by, m_extra, t_extra):
        if type_filter == "movies":
            return [_map_tmdb_item(r, "movie") for r in fetch_m(sort_by, m_extra)]
        elif type_filter == "tv":
            return [_map_tmdb_item(r, "tv")    for r in fetch_t(sort_by, t_extra)]
        else:
            return interleave(fetch_m(sort_by, m_extra), fetch_t(sort_by, t_extra))

    trending     = build_row("popularity.desc",
                             {"vote_count.gte": 50,   "primary_release_date.lte": today},
                             {"vote_count.gte": 50,   "first_air_date.lte": today})
    popular_year = build_row("popularity.desc",
                             {"vote_count.gte": 20,   "primary_release_date.gte": one_year_ago, "primary_release_date.lte": today},
                             {"vote_count.gte": 20,   "first_air_date.gte": one_year_ago,        "first_air_date.lte": today})
    top_rated    = build_row("vote_average.desc",
                             {"vote_count.gte": 1000, "primary_release_date.lte": today},
                             {"vote_count.gte": 1000, "first_air_date.lte": today})
    new_releases = build_row("popularity.desc",
                             {"vote_count.gte": 10,   "primary_release_date.gte": three_months_ago, "primary_release_date.lte": today},
                             {"vote_count.gte": 10,   "first_air_date.gte": three_months_ago,        "first_air_date.lte": today})
    hidden_gems  = build_row("vote_average.desc",
                             {"vote_count.gte": 200, "vote_count.lte": 5000, "primary_release_date.lte": today},
                             {"vote_count.gte": 200, "vote_count.lte": 5000, "first_air_date.lte": today})

    return jsonify({
        "trending":          _mark_watchlist(trending),
        "popular_this_year": _mark_watchlist(popular_year),
        "top_rated":         _mark_watchlist(top_rated),
        "new_releases":      _mark_watchlist(new_releases),
        "hidden_gems":       _mark_watchlist(hidden_gems),
    })


@app.route("/api/media/trending", methods=["GET"])
def get_trending():
    movies = _tmdb_get("/trending/movie/week") or {}
    shows = _tmdb_get("/trending/tv/week") or {}
    items = (
        [_map_tmdb_item(r, "movie") for r in movies.get("results", []) if r.get("poster_path")]
        + [_map_tmdb_item(r, "tv") for r in shows.get("results", []) if r.get("poster_path")]
    )
    items.sort(key=lambda x: x["popularity"], reverse=True)
    return jsonify(_mark_watchlist(items[:40]))


@app.route("/api/media/popular", methods=["GET"])
def get_popular():
    movies = _tmdb_get("/movie/popular") or {}
    shows = _tmdb_get("/tv/popular") or {}
    return jsonify({
        "movies": _mark_watchlist([_map_tmdb_item(r, "movie") for r in movies.get("results", []) if r.get("poster_path")]),
        "shows":  _mark_watchlist([_map_tmdb_item(r, "tv")    for r in shows.get("results",  []) if r.get("poster_path")]),
    })


@app.route("/api/media/upcoming", methods=["GET"])
def get_upcoming():
    data = _tmdb_get("/movie/upcoming") or {}
    items = [_map_tmdb_item(r, "movie") for r in data.get("results", []) if r.get("poster_path")]
    return jsonify(_mark_watchlist(items))


# ── Dashboard: home server recent items ──────────────────────────────────────

@app.route("/api/media/server-items", methods=["GET"])
def get_server_items():
    blob = _get_blob()
    connections = [c for c in _get_connections(blob) if c.get("enabled")]
    if not connections:
        return jsonify([])

    conn = connections[0]
    kind = conn.get("serverKind", "JELLYFIN")
    server_url = conn.get("serverUrl", "").rstrip("/")
    token = conn.get("accessToken", "")
    user_id = conn.get("userId", "")

    if kind in ("JELLYFIN", "EMBY"):
        headers = {
            "X-Emby-Token": token,
            "Accept": "application/json",
        }
        try:
            r = requests.get(
                f"{server_url}/Users/{user_id}/Items",
                params={
                    "SortBy": "DateCreated",
                    "SortOrder": "Descending",
                    "IncludeItemTypes": "Movie,Series",
                    "Recursive": "true",
                    "Fields": "Overview,ProviderIds,PrimaryImageAspectRatio",
                    "ImageTypeLimit": "1",
                    "EnableImageTypes": "Primary,Backdrop",
                    "Limit": "20",
                },
                headers=headers,
                timeout=10,
            )
            r.raise_for_status()
            items = []
            for it in r.json().get("Items", []):
                item_id = it.get("Id", "")
                tmdb_id = it.get("ProviderIds", {}).get("Tmdb") or item_id
                items.append({
                    "id": tmdb_id,
                    "title": it.get("Name", ""),
                    "mediaType": "movie" if it.get("Type") == "Movie" else "show",
                    "image": f"/api/media/jf-image/{item_id}/Primary" if item_id else "",
                    "backdropUrl": f"/api/media/jf-image/{item_id}/Backdrop" if item_id else "",
                    "overview": it.get("Overview", ""),
                    "year": it.get("ProductionYear", ""),
                    "inWatchlist": False,
                })
            return jsonify(_mark_watchlist(items))
        except Exception as e:
            return jsonify({"error": str(e)}), 502

    elif kind == "PLEX":
        try:
            r = requests.get(
                f"{server_url}/library/recentlyAdded",
                params={"X-Plex-Token": token},
                headers={"Accept": "application/json"},
                timeout=10,
            )
            r.raise_for_status()
            media_list = r.json().get("MediaContainer", {}).get("Metadata", [])[:20]
            items = []
            for it in media_list:
                thumb = it.get("thumb", "")
                art = it.get("art", "")
                guids = it.get("Guid", [])
                tmdb_id = next((g["id"].split("//")[-1] for g in guids if "tmdb" in g.get("id", "")), it.get("ratingKey", ""))
                items.append({
                    "id": tmdb_id,
                    "title": it.get("title", ""),
                    "mediaType": "movie" if it.get("type") == "movie" else "show",
                    "image": f"{server_url}{thumb}?X-Plex-Token={token}" if thumb else "",
                    "backdropUrl": f"{server_url}{art}?X-Plex-Token={token}" if art else "",
                    "overview": it.get("summary", ""),
                    "year": it.get("year", ""),
                    "inWatchlist": False,
                })
            return jsonify(_mark_watchlist(items))
        except Exception as e:
            return jsonify({"error": str(e)}), 502

    return jsonify([])


# ── Cameras (Frigate) ─────────────────────────────────────────────────────────

@app.route("/api/cameras/list", methods=["GET"])
def cameras_list():
    blob = _get_blob()
    frigate_url = _get_frigate_url(blob)
    if not frigate_url:
        return jsonify([])
    try:
        r = requests.get(f"{frigate_url}/api/config", timeout=8)
        r.raise_for_status()
        cameras_config = r.json().get("cameras", {})
        cameras = [
            {"name": name, "snapshotUrl": f"/api/cameras/snapshot/{name}"}
            for name in cameras_config.keys()
        ]
        return jsonify(cameras)
    except Exception as e:
        return jsonify({"error": str(e)}), 502


@app.route("/api/media/jf-image/<item_id>/<image_type>", methods=["GET"])
def jellyfin_image(item_id, image_type):
    blob = _get_blob()
    conn = next((c for c in _get_connections(blob) if c.get("enabled") and c.get("serverKind") in ("JELLYFIN", "EMBY")), None)
    if not conn:
        return "No server", 404
    server_url = conn.get("serverUrl", "").rstrip("/")
    token = conn.get("accessToken", "")
    size = "maxHeight=300" if image_type == "Primary" else "maxHeight=500"
    url = f"{server_url}/Items/{item_id}/Images/{image_type}?{size}&api_key={token}"
    try:
        r = requests.get(url, timeout=8)
        r.raise_for_status()
        resp = Response(r.content, content_type=r.headers.get("Content-Type", "image/jpeg"))
        resp.headers["Cache-Control"] = "public, max-age=86400"
        return resp
    except Exception:
        return "Image not found", 404


@app.route("/api/cameras/snapshot/<camera_name>", methods=["GET"])
def camera_snapshot(camera_name):
    blob = _get_blob()
    frigate_url = _get_frigate_url(blob)
    if not frigate_url:
        return "Frigate not configured", 503
    try:
        url = f"{frigate_url}/api/{camera_name}/latest.jpg"
        r = requests.get(url, timeout=5)
        r.raise_for_status()
        return Response(r.content, content_type=r.headers.get("Content-Type", "image/jpeg"))
    except Exception as e:
        return str(e), 502


# ── Catalogue management ──────────────────────────────────────────────────────

@app.route("/api/catalogues", methods=["GET"])
def get_catalogues():
    blob = _get_blob()
    cats = _get_catalogues(blob)
    visibility = _get_web_row_visibility()
    # Build synthetic entries with stored sortOrder
    synthetic = []
    for i, sc in enumerate(_SYNTHETIC_CATS):
        hidden = visibility.get(sc["id"], False)
        sort_order = visibility.get(f"{sc['id']}_sort", i - len(_SYNTHETIC_CATS))
        synthetic.append({
            **sc,
            "isHidden": hidden,
            "placement": "HIDDEN" if hidden else "HOME",
            "sortOrder": sort_order,
        })
    # Fill missing sortOrder on real cats
    for i, c in enumerate(cats):
        if c.get("sortOrder") is None:
            c["sortOrder"] = i
    # Merge and sort by sortOrder so user-defined position is respected
    merged = synthetic + cats
    merged.sort(key=lambda c: (c.get("sortOrder") or 0))
    return jsonify(merged)


@app.route("/api/catalogues", methods=["PUT"])
def put_catalogues():
    cats = request.get_json(force=True) or []
    synthetic = [c for c in cats if c.get("id") in _SYNTHETIC_IDS]
    real = [c for c in cats if c.get("id") not in _SYNTHETIC_IDS]
    if synthetic:
        visibility = _get_web_row_visibility()
        for sc in synthetic:
            visibility[sc["id"]] = sc.get("placement") == "HIDDEN" or bool(sc.get("isHidden"))
            visibility[f"{sc['id']}_sort"] = sc.get("sortOrder", 0)
        _save_web_row_visibility(visibility)
    blob = _get_blob()
    _set_catalogues(blob, real)
    _save_blob(blob)
    return jsonify({"ok": True})


# ── Trakt OAuth ───────────────────────────────────────────────────────────────

@app.route("/api/trakt/status", methods=["GET"])
def trakt_status():
    blob = _get_blob()
    tokens = (blob.get("traktTokens") or {}).get(SETUP_PROFILE_ID, {})
    client_id = blob.get("trakt_client_id", "")
    connected = bool(tokens.get("accessToken"))
    return jsonify({
        "connected": connected,
        "hasClientId": bool(client_id),
        "clientIdHint": (client_id[:6] + "…") if client_id else "",
    })


@app.route("/api/trakt/connect", methods=["GET"])
def trakt_connect():
    blob = _get_blob()
    client_id = blob.get("trakt_client_id", "")
    if not client_id:
        return "No Trakt Client ID configured — add it in Settings first", 400
    redirect_uri = request.host_url.rstrip("/") + "/api/trakt/callback"
    auth_url = (
        f"https://trakt.tv/oauth/authorize"
        f"?response_type=code&client_id={client_id}&redirect_uri={redirect_uri}"
    )
    return redirect(auth_url)


@app.route("/api/trakt/callback", methods=["GET"])
def trakt_callback():
    code = request.args.get("code", "")
    if not code:
        return "No code received from Trakt", 400
    blob = _get_blob()
    client_id = blob.get("trakt_client_id", "")
    client_secret = blob.get("trakt_client_secret", "")
    redirect_uri = request.host_url.rstrip("/") + "/api/trakt/callback"
    try:
        r = requests.post(
            "https://api.trakt.tv/oauth/token",
            json={
                "code": code,
                "client_id": client_id,
                "client_secret": client_secret,
                "redirect_uri": redirect_uri,
                "grant_type": "authorization_code",
            },
            headers={"Content-Type": "application/json"},
            timeout=15,
        )
        r.raise_for_status()
        tok = r.json()
        blob.setdefault("traktTokens", {})[SETUP_PROFILE_ID] = {
            "accessToken": tok.get("access_token"),
            "refreshToken": tok.get("refresh_token"),
            "expiresAt": int(time.time() * 1000) + tok.get("expires_in", 0) * 1000,
        }
        blob["traktLinked"] = True
        _save_blob(blob)
        return redirect("/?trakt=connected")
    except Exception as e:
        return f"Trakt token exchange failed: {e}", 502


@app.route("/api/trakt/disconnect", methods=["POST"])
def trakt_disconnect():
    blob = _get_blob()
    blob.setdefault("traktTokens", {})[SETUP_PROFILE_ID] = {}
    blob["traktLinked"] = False
    _save_blob(blob)
    return jsonify({"ok": True})


# ── Dashboard: player state + SSE ────────────────────────────────────────────

@app.route("/api/player/state", methods=["GET"])
def get_player_state():
    return jsonify(_player_state)


@app.route("/api/player/events", methods=["GET"])
def player_events():
    q: queue.Queue = queue.Queue(maxsize=10)
    with _sse_lock:
        _sse_queues.append(q)

    def generate():
        yield "data: " + json.dumps(_player_state) + "\n\n"
        try:
            while True:
                try:
                    msg = q.get(timeout=30)
                    yield msg
                except queue.Empty:
                    yield ": keepalive\n\n"
        except GeneratorExit:
            pass
        finally:
            with _sse_lock:
                if q in _sse_queues:
                    _sse_queues.remove(q)

    return Response(
        stream_with_context(generate()),
        mimetype="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


# ── Generic notification endpoint ────────────────────────────────────────────

@app.route("/api/notify", methods=["POST"])
def post_notify():
    data = request.get_json(force=True, silent=True) or {}

    # Try to parse as a Sonarr/Radarr native webhook first
    entry = _parse_arr_webhook(data)

    if entry is None:
        # Generic format: { title, message, type, source }
        title = str(data.get("title") or "").strip()
        if not title:
            return jsonify({"ok": False, "error": "title required"}), 400
        entry = _make_notification(
            source=str(data.get("source") or "Unknown").strip(),
            title=title,
            message=str(data.get("message") or "").strip() or None,
            notif_type=str(data.get("type") or "info").strip(),
        )

    _store_notification(entry)
    return jsonify({"ok": True, "id": entry["id"]})


@app.route("/api/notify/recent", methods=["GET"])
def get_notify_recent():
    limit = min(int(request.args.get("limit", 20)), 100)
    since = request.args.get("since", "")  # ISO timestamp — return only newer entries
    with _notifications_lock:
        items = list(_notifications)
    if since:
        items = [n for n in items if n.get("timestamp", "") > since]
    return jsonify(items[:limit])


# ── Legacy watchlist endpoint (blob-backed) ───────────────────────────────────

@app.route("/watchlist", methods=["GET"])
def legacy_watchlist():
    blob = _get_blob()
    _migrate_watchlist_to_blob(blob)
    return jsonify(_watchlist_to_web(_get_watchlist(blob)))


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 7979))
    app.run(host="0.0.0.0", port=port, threaded=True)
