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
import requests
from datetime import datetime
from pathlib import Path
from flask import Flask, request, jsonify, send_from_directory, Response, stream_with_context

# ── Paths ─────────────────────────────────────────────────────────────────────

DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
DATA_DIR.mkdir(parents=True, exist_ok=True)

SETTINGS_FILE      = DATA_DIR / "xadarr_settings.json"
WATCHLIST_FILE     = DATA_DIR / "watchlist.json"
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

# Episeerr-sourced events to store in history and forward to SSE clients
_EPISEERR_EVENTS = frozenset({
    "episode.grabbed", "episode.ready",
    "rule.triggered", "rule.assigned",
    "watchlist.requested",
})


def _broadcast_player_state():
    payload = "data: " + json.dumps(_player_state) + "\n\n"
    with _sse_lock:
        dead = []
        for q in _sse_queues:
            try:
                q.put_nowait(payload)
            except queue.Full:
                dead.append(q)
        for q in dead:
            _sse_queues.remove(q)


def _broadcast_episeerr_event(entry: dict):
    """Push an episeerr activity event to all SSE subscribers as a named 'episeerr' event."""
    payload = "event: episeerr\ndata: " + json.dumps(entry) + "\n\n"
    with _sse_lock:
        dead = []
        for q in _sse_queues:
            try:
                q.put_nowait(payload)
            except queue.Full:
                dead.append(q)
        for q in dead:
            _sse_queues.remove(q)


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
    return _load_server_config().get("episeerr_url", "").rstrip("/")


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
        return send_from_directory(str(WEB_DIR), path)
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

    # Append inbound event to webhook log
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

    # Update live player state
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
    return jsonify({
        "server": cfg.get("server_name", "Xadarr Server"),
        "version": "1.0.0",
        "watchlist_count": len(_load_json(WATCHLIST_FILE, [])),
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

    # Build a payload that matches the first configured event for this URL so the
    # receiving endpoint (e.g. Episeerr watchlist) gets a recognisable payload.
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

def _episeerr_proxy(path: str, method: str = "GET", body: dict | None = None):
    """Helper: forward a request to the configured Episeerr instance."""
    base = _get_episeerr_url()
    if not base:
        return jsonify({"error": "Episeerr URL not configured"}), 503
    try:
        url = f"{base}{path}"
        if method == "GET":
            r = requests.get(url, timeout=10)
        else:
            r = requests.post(url, json=body, timeout=10)
        r.raise_for_status()
        return jsonify(r.json())
    except requests.RequestException as exc:
        return jsonify({"error": str(exc)}), 502


@app.route("/api/episeerr/pending", methods=["GET"])
def episeerr_pending():
    """Proxy to Episeerr's /api/integration/xadarr/pending endpoint."""
    return _episeerr_proxy("/api/integration/xadarr/pending")


@app.route("/api/episeerr/rules", methods=["GET"])
def episeerr_rules():
    """Proxy to Episeerr's /api/rules-list endpoint."""
    return _episeerr_proxy("/api/rules-list")


@app.route("/api/episeerr/assign", methods=["POST"])
def episeerr_assign():
    """Proxy rule assignment to Episeerr's /api/assign-pending-rule endpoint."""
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
    """Return list of URL strings from webhook_urls config, optionally filtered by event."""
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
    """Fire watchlist.add or watchlist.remove to subscribed URLs in background."""
    blob = _load_json(SETTINGS_FILE, {})
    if not blob.get("webhook_enabled", False):
        return
    urls = _resolve_webhook_urls(blob, event_filter=event_name)
    if not urls:
        return
    headers_dict = blob.get("webhook_headers") or {}
    req_headers = {"Content-Type": "application/json", **headers_dict}
    payload = {
        "event": event_name,
        "title": item.get("title", ""),
        "tmdb_id": item.get("id") or item.get("tmdb_id"),
        "media_type": "movie" if item.get("mediaType") == "movie" else "tv",
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


# ── Dashboard: watchlist ──────────────────────────────────────────────────────

@app.route("/api/media/watchlist", methods=["GET"])
def get_watchlist():
    items = _load_json(WATCHLIST_FILE, [])
    for item in items:
        if not item.get('image') and item.get('posterPath'):
            item['image'] = item['posterPath']
    return jsonify(items)


@app.route("/api/media/watchlist", methods=["POST"])
def add_to_watchlist():
    item = request.get_json(force=True) or {}
    if not item.get("id"):
        return jsonify({"error": "missing id"}), 400

    # Fetch poster from TMDB if not provided
    if not item.get("posterPath") and not item.get("image"):
        tmdb_id = item.get("id")
        mt = (item.get("mediaType") or "").lower()
        path = "/movie/" if mt == "movie" else "/tv/"
        tmdb_data = _tmdb_get(path + str(tmdb_id))
        if tmdb_data and tmdb_data.get("poster_path"):
            item["posterPath"] = "https://image.tmdb.org/t/p/w342" + tmdb_data["poster_path"]
        if tmdb_data and not item.get("title"):
            item["title"] = tmdb_data.get("title") or tmdb_data.get("name") or ""

    watchlist = _load_json(WATCHLIST_FILE, [])
    exists = any(str(w.get("id")) == str(item["id"]) and w.get("mediaType") == item.get("mediaType") for w in watchlist)
    if not exists:
        item["inWatchlist"] = True
        item["addedAt"] = datetime.utcnow().isoformat() + "Z"
        watchlist.insert(0, item)
        _save_json(WATCHLIST_FILE, watchlist)
        _fire_watchlist_webhook("watchlist.add", item)
    return jsonify({"ok": True})


@app.route("/api/media/watchlist/<media_type>/<int:item_id>", methods=["DELETE"])
def remove_from_watchlist(media_type, item_id):
    _TV_TYPES = {"tv", "show", "series"}

    def _matches(w):
        if str(w.get("id")) != str(item_id):
            return False
        wmt = w.get("mediaType", "")
        return wmt == media_type or (media_type in _TV_TYPES and wmt in _TV_TYPES)

    watchlist = _load_json(WATCHLIST_FILE, [])
    removed = [w for w in watchlist if _matches(w)]
    watchlist = [w for w in watchlist if not _matches(w)]
    _save_json(WATCHLIST_FILE, watchlist)
    if removed:
        removed[0]["mediaType"] = media_type
        _fire_watchlist_webhook("watchlist.remove", removed[0])
    return jsonify({"ok": True})


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
    watchlist = _load_json(WATCHLIST_FILE, [])
    wl_keys = {(str(w["id"]), w.get("mediaType", "movie")) for w in watchlist}
    for item in items:
        item["inWatchlist"] = (str(item["id"]), item.get("mediaType", "movie")) in wl_keys
    return items


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


# ── Legacy watchlist endpoint ─────────────────────────────────────────────────

@app.route("/watchlist", methods=["GET"])
def legacy_watchlist():
    return jsonify(_load_json(WATCHLIST_FILE, []))


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 7979))
    app.run(host="0.0.0.0", port=port, threaded=True)
