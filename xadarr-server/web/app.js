function xadarr() {
  const ALL_WEBHOOK_EVENTS = ['start','pause','resume','stop','progress','watchlist.add','watchlist.remove'];

  return {
    tabs: [
      { id: 'settings', label: 'Settings' },
      { id: 'media',    label: 'Media' },
      { id: 'history',  label: 'History' },
      { id: 'player',   label: 'Player' },
    ],
    activeTab: 'settings',

    // Server
    serverName: '',
    serverConfig: { server_name: '' },
    serverSaveStatus: '',
    origin: window.location.origin,
    tmdbConfigured: false,

    // Settings (API keys + raw blob)
    settings: {},
    saveStatus: '',
    restoreStatus: '',

    // Home servers
    homeServers: [],
    newServer: { kind: '', url: '', username: '', password: '', token: '' },
    serverConnecting: false,
    serverError: '',
    serverSuccess: '',

    // Profile
    profileName: '',
    profileSaveStatus: '',

    // IPTV
    iptv: { m3uUrl: '', epgUrl: '' },
    iptvStatus: '',

    // Webhook settings
    webhook: {
      enabled: false,
      urls: [],     // [{url: string, events: string[]}]
      intervalSeconds: '30',
      completionPercent: 80,
      headers: [],  // [{key, value}] — serialised to/from object for storage
    },
    newWebhookUrl: '',
    webhookSaveStatus: '',
    webhookUrlTestResults: {},  // keyed by idx → {text, ok}
    webhookLog: [],
    webhookLastFired: null,
    newHeaderKey: '',
    newHeaderValue: '',

    // Watchlist API settings
    watchlistApi: { enabled: false, port: '7979' },
    watchlistSaveStatus: '',

    // Addons
    addons: [],
    newAddonUrl: '',
    addonAdding: false,
    addonStatus: '',

    // Media
    watchlistItems: [],
    trendingItems: [],
    searchQuery: '',
    searchResults: [],
    selectedItem: null,

    // Episeerr integration
    episeerrPending: [],    // [{id, seriesId, title, tmdbId, poster}]
    episeerrRules: [],      // [{name, display_name, series_count}]
    episeerrManagedIds: {}, // tmdbId -> rule_name for rule badges
    rulePickerItem: null,
    ruleAssignStatus: null,

    // History
    historyItems: [],

    // Player
    playerState: { isPlaying: false, isPaused: false, title: '', episodeTitle: '', overview: '', positionMs: 0, durationMs: 0, streamUrl: '', isLive: false },
    manualStreamUrl: '',
    currentStreamUrl: null,
    currentStreamTitle: '',
    mainHls: null,

    // SSE
    sse: null,
    wsConnected: false,

    async init() {
      await this.loadServerConfig();
      await this.loadSettings();
      this.loadProfile();
      this.loadWatchlist();
      this.loadTrending();
      this.loadHomeServers();
      this.loadIptv();
      this.loadAddons();
      if (this.activeTab === 'history') this.loadHistory();
      this.connectSse();
      if (this.serverConfig.episeerr_url) {
        this.loadEpiseerrPending();
        this.loadEpiseerrRules();
      }
    },

    // ── Server config ──────────────────────────────────────────────────────────

    async loadServerConfig() {
      try {
        const res = await fetch('/api/server/config');
        this.serverConfig = await res.json();
        this.serverName = this.serverConfig.server_name || 'Xadarr Server';
      } catch (e) {
        console.error('loadServerConfig:', e);
      }
    },

    async saveServerConfig() {
      try {
        await fetch('/api/server/config', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(this.serverConfig),
        });
        this.serverName = this.serverConfig.server_name || 'Xadarr Server';
        this.serverSaveStatus = 'Saved!';
        setTimeout(() => { this.serverSaveStatus = ''; }, 2000);
        if (this.serverConfig.episeerr_url) {
          this.loadEpiseerrPending();
          this.loadEpiseerrRules();
        }
      } catch (e) {
        this.serverSaveStatus = 'Error saving';
        setTimeout(() => { this.serverSaveStatus = ''; }, 3000);
      }
    },

    // ── Episeerr ───────────────────────────────────────────────────────────────

    async loadEpiseerrPending() {
      try {
        const res = await fetch('/api/episeerr/pending');
        if (!res.ok) return;
        const data = await res.json();
        this.episeerrPending = Array.isArray(data) ? data : (data.requests || []);
      } catch (e) { /* Episeerr not reachable — silent */ }
    },

    async loadEpiseerrRules() {
      // Fetch rule names for the rule picker modal
      try {
        const res = await fetch('/api/episeerr/rules');
        if (res.ok) {
          const data = await res.json();
          const rules = data.rules || data;
          this.episeerrRules = Array.isArray(rules) ? rules : [];
        }
      } catch (e) { /* silent */ }

      // Fetch managed series to build tmdbId → rule badge map
      try {
        const res = await fetch('/api/episeerr/managed-series');
        if (!res.ok) return;
        const items = await res.json();
        const map = {};
        for (const item of (Array.isArray(items) ? items : [])) {
          if (item.tmdbId) map[String(item.tmdbId)] = item.rule;
        }
        this.episeerrManagedIds = map;
      } catch (e) { /* silent */ }
    },

    isPending(item) {
      if (!item || !this.episeerrPending.length) return false;
      const id = String(item.id || '');
      return this.episeerrPending.some(p => String(p.tmdbId || '') === id);
    },

    getRule(item) {
      if (!item) return null;
      return this.episeerrManagedIds[String(item.id)] || null;
    },

    getPendingPoster(item) {
      if (!item) return null;
      const p = this.episeerrPending.find(p => String(p.tmdbId || '') === String(item?.id || ''));
      return p?.poster || null;
    },

    openRulePicker(item) {
      this.rulePickerItem = item;
      this.ruleAssignStatus = null;
      if (!this.episeerrRules.length) this.loadEpiseerrRules();
    },

    async assignRule(item, ruleName) {
      if (!item) return;
      try {
        this.ruleAssignStatus = { ok: null, text: 'Assigning…' };
        const res = await fetch('/api/episeerr/assign', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ tmdb_id: String(item.id), rule_name: ruleName }),
        });
        const data = await res.json();
        if (data.success || data.ok) {
          this.ruleAssignStatus = { ok: true, text: `Assigned: ${ruleName}` };
          this.episeerrPending = this.episeerrPending.filter(p => String(p.tmdbId) !== String(item.id));
          this.episeerrManagedIds = { ...this.episeerrManagedIds, [String(item.id)]: ruleName };
          setTimeout(() => { this.rulePickerItem = null; this.ruleAssignStatus = null; }, 1200);
        } else {
          this.ruleAssignStatus = { ok: false, text: data.error || 'Assignment failed' };
        }
      } catch (e) {
        this.ruleAssignStatus = { ok: false, text: e.message || 'Error' };
      }
    },

    // ── App Settings ───────────────────────────────────────────────────────────

    async loadSettings() {
      try {
        const res = await fetch('/api/settings');
        this.settings = await res.json();
        this.tmdbConfigured = !!this.settings.tmdb_api_key;

        // Populate webhook state from flat settings blob
        this.webhook.enabled           = !!this.settings.webhook_enabled;
        // Load webhook_urls — normalize to [{url, events}], migrate from legacy string array or single URL
        const rawUrls = this.settings.webhook_urls;
        if (Array.isArray(rawUrls) && rawUrls.length > 0) {
          this.webhook.urls = rawUrls.map(u =>
            typeof u === 'string'
              ? { url: u, events: [...ALL_WEBHOOK_EVENTS] }
              : { url: u.url || '', events: Array.isArray(u.events) ? [...u.events] : [...ALL_WEBHOOK_EVENTS] }
          );
        } else if (this.settings.webhook_url) {
          this.webhook.urls = [{ url: this.settings.webhook_url, events: [...ALL_WEBHOOK_EVENTS] }];
        } else {
          this.webhook.urls = [];
        }
        this.webhook.intervalSeconds   = this.settings.webhook_interval_seconds || '30';
        this.webhook.completionPercent = this.settings.webhook_completion_percent ?? 80;
        const hobj = this.settings.webhook_headers || {};
        this.webhook.headers = Object.entries(hobj).map(([key, value]) => ({ key, value }));

        // Populate watchlist API state
        this.watchlistApi.enabled = !!this.settings.watchlist_api_enabled;
        this.watchlistApi.port    = this.settings.watchlist_api_port || '7979';

        await this.loadWebhookLog();
      } catch (e) {
        console.error('loadSettings:', e);
      }
    },

    async saveApiKeys() {
      const payload = {
        tmdb_api_key:        this.settings.tmdb_api_key        || '',
        trakt_client_id:     this.settings.trakt_client_id     || '',
        trakt_client_secret: this.settings.trakt_client_secret || '',
      };
      try {
        await fetch('/api/settings', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        });
        const hadTmdb = this.tmdbConfigured;
        this.tmdbConfigured = !!this.settings.tmdb_api_key;
        if (!hadTmdb && this.tmdbConfigured) this.loadTrending();
        this.saveStatus = 'Saved!';
        setTimeout(() => { this.saveStatus = ''; }, 2000);
      } catch (e) {
        this.saveStatus = 'Error saving';
        setTimeout(() => { this.saveStatus = ''; }, 3000);
      }
    },

    async saveWebhookSettings() {
      const headersObj = {};
      for (const h of this.webhook.headers) {
        if (h.key.trim()) headersObj[h.key.trim()] = h.value;
      }
      const payload = {
        webhook_enabled:            this.webhook.enabled,
        webhook_urls:               this.webhook.urls.filter(u => u.url.trim()),
        webhook_interval_seconds:   String(this.webhook.intervalSeconds || '30'),
        webhook_completion_percent: parseInt(this.webhook.completionPercent) || 80,
        webhook_headers:            headersObj,
      };
      try {
        await fetch('/api/settings', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        });
        this.webhookSaveStatus = 'Saved!';
        setTimeout(() => { this.webhookSaveStatus = ''; }, 2000);
      } catch (e) {
        this.webhookSaveStatus = 'Error saving';
        setTimeout(() => { this.webhookSaveStatus = ''; }, 3000);
      }
    },

    async saveWatchlistSettings() {
      const payload = {
        watchlist_api_enabled: this.watchlistApi.enabled,
        watchlist_api_port:    String(this.watchlistApi.port || '7979'),
      };
      try {
        await fetch('/api/settings', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload),
        });
        this.watchlistSaveStatus = 'Saved!';
        setTimeout(() => { this.watchlistSaveStatus = ''; }, 2000);
      } catch (e) {
        this.watchlistSaveStatus = 'Error saving';
        setTimeout(() => { this.watchlistSaveStatus = ''; }, 3000);
      }
    },

    addWebhookUrl() {
      const u = this.newWebhookUrl.trim();
      if (u) { this.webhook.urls.push({ url: u, events: [...ALL_WEBHOOK_EVENTS] }); this.newWebhookUrl = ''; }
    },

    removeWebhookUrl(idx) {
      this.webhook.urls.splice(idx, 1);
    },

    addWebhookHeader() {
      this.webhook.headers.push({ key: this.newHeaderKey.trim(), value: this.newHeaderValue });
      this.newHeaderKey = '';
      this.newHeaderValue = '';
    },

    removeWebhookHeader(idx) {
      this.webhook.headers.splice(idx, 1);
    },

    async testWebhookUrl(idx, entry) {
      this.webhookUrlTestResults = { ...this.webhookUrlTestResults, [idx]: { text: '…', ok: null } };
      try {
        const res = await fetch('/api/webhook/test', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url: entry.url, events: entry.events }),
        });
        const data = await res.json();
        const text = data.ok ? `HTTP ${data.status_code}` : (data.error || `HTTP ${data.status_code}`);
        this.webhookUrlTestResults = { ...this.webhookUrlTestResults, [idx]: { text, ok: data.ok } };
        await this.loadWebhookLog();
      } catch (e) {
        this.webhookUrlTestResults = { ...this.webhookUrlTestResults, [idx]: { text: e.message || 'Failed', ok: false } };
      }
      setTimeout(() => {
        const r = { ...this.webhookUrlTestResults };
        delete r[idx];
        this.webhookUrlTestResults = r;
      }, 5000);
    },

    async loadWebhookLog() {
      try {
        const res = await fetch('/api/webhook/log');
        this.webhookLog = await res.json();
        this.webhookLastFired = this.webhookLog.length > 0 ? this.webhookLog[0] : null;
      } catch (e) { console.error('loadWebhookLog:', e); }
    },

    async restoreBackup(event) {
      const file = event.target.files[0];
      if (!file) return;
      try {
        const text = await file.text();
        const data = JSON.parse(text);
        await fetch('/api/integration/xadarr/settings/backup', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(data),
        });
        this.settings = data;
        this.restoreStatus = 'Restored!';
        setTimeout(() => { this.restoreStatus = ''; }, 3000);
      } catch (e) {
        this.restoreStatus = 'Error restoring';
        setTimeout(() => { this.restoreStatus = ''; }, 3000);
      }
    },

    // ── Profile ────────────────────────────────────────────────────────────────

    async loadProfile() {
      try {
        const res = await fetch('/api/setup/profile');
        const data = await res.json();
        this.profileName = data.name || '';
      } catch (e) { console.error('loadProfile:', e); }
    },

    async saveProfile() {
      try {
        await fetch('/api/setup/profile', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name: this.profileName }),
        });
        this.profileSaveStatus = 'Saved!';
        setTimeout(() => { this.profileSaveStatus = ''; }, 2000);
      } catch (e) {
        this.profileSaveStatus = 'Error saving';
        setTimeout(() => { this.profileSaveStatus = ''; }, 3000);
      }
    },

    // ── Home Servers ───────────────────────────────────────────────────────────

    async loadHomeServers() {
      try {
        const res = await fetch('/api/setup/servers');
        this.homeServers = await res.json();
      } catch (e) { console.error('loadHomeServers:', e); }
    },

    async connectServer() {
      if (!this.newServer.url) return;
      this.serverConnecting = true;
      this.serverError = '';
      this.serverSuccess = '';
      try {
        const res = await fetch('/api/setup/servers/connect', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(this.newServer),
        });
        const data = await res.json();
        if (!res.ok) { this.serverError = data.error || 'Connection failed'; return; }
        this.serverSuccess = `Connected: ${data.connection.displayName}`;
        this.newServer = { kind: '', url: '', username: '', password: '', token: '' };
        await this.loadHomeServers();
        setTimeout(() => { this.serverSuccess = ''; }, 3000);
      } catch (e) {
        this.serverError = e.message || 'Connection failed';
      } finally {
        this.serverConnecting = false;
      }
    },

    async deleteServer(connectionId) {
      try {
        await fetch('/api/setup/servers/' + encodeURIComponent(connectionId), { method: 'DELETE' });
        await this.loadHomeServers();
      } catch (e) { console.error('deleteServer:', e); }
    },

    // ── IPTV ───────────────────────────────────────────────────────────────────

    async loadIptv() {
      try {
        const res = await fetch('/api/setup/iptv');
        this.iptv = await res.json();
      } catch (e) { console.error('loadIptv:', e); }
    },

    async saveIptv() {
      try {
        await fetch('/api/setup/iptv', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(this.iptv),
        });
        this.iptvStatus = 'Saved!';
        setTimeout(() => { this.iptvStatus = ''; }, 2000);
      } catch (e) { console.error('saveIptv:', e); }
    },

    // ── Addons ─────────────────────────────────────────────────────────────────

    async loadAddons() {
      try {
        const res = await fetch('/api/setup/addons');
        this.addons = await res.json();
      } catch (e) { console.error('loadAddons:', e); }
    },

    async addAddon() {
      const url = this.newAddonUrl.trim();
      if (!url) return;
      this.addonAdding = true;
      this.addonStatus = '';
      try {
        const res = await fetch('/api/setup/addons', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url }),
        });
        const data = await res.json();
        if (!res.ok) { this.addonStatus = 'Error: ' + (data.error || 'Failed'); return; }
        this.addonStatus = `Added: ${data.addon.name}`;
        this.newAddonUrl = '';
        await this.loadAddons();
        setTimeout(() => { this.addonStatus = ''; }, 3000);
      } catch (e) {
        this.addonStatus = 'Error: ' + e.message;
      } finally {
        this.addonAdding = false;
      }
    },

    async deleteAddon(addonId) {
      try {
        await fetch('/api/setup/addons/' + encodeURIComponent(addonId), { method: 'DELETE' });
        await this.loadAddons();
      } catch (e) { console.error('deleteAddon:', e); }
    },

    // ── Media ──────────────────────────────────────────────────────────────────

    async loadWatchlist() {
      try {
        const res = await fetch('/api/media/watchlist');
        this.watchlistItems = await res.json();
      } catch (e) { console.error('loadWatchlist:', e); }
    },

    async loadTrending() {
      if (!this.tmdbConfigured) return;
      try {
        const res = await fetch('/api/media/trending');
        this.trendingItems = await res.json();
      } catch (e) { console.error('loadTrending:', e); }
    },

    async onSearchInput() {
      const q = this.searchQuery.trim();
      if (q.length < 2) { this.searchResults = []; return; }
      if (!this.tmdbConfigured) return;
      try {
        const res = await fetch('/api/media/search?q=' + encodeURIComponent(q));
        this.searchResults = await res.json();
      } catch (e) { console.error('search:', e); }
    },

    async toggleWatchlist(item) {
      if (!item) return;
      if (item.inWatchlist) {
        await this.removeFromWatchlist(item);
      } else {
        await this.addToWatchlist(item);
      }
    },

    async addToWatchlist(item) {
      try {
        await fetch('/api/media/watchlist', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(item),
        });
        item.inWatchlist = true;
        this.loadWatchlist();
      } catch (e) { console.error('addToWatchlist:', e); }
    },

    async removeFromWatchlist(item) {
      const type = item.mediaType === 'show' ? 'show' : 'movie';
      try {
        await fetch(`/api/media/watchlist/${type}/${item.id}`, { method: 'DELETE' });
        item.inWatchlist = false;
        this.watchlistItems = this.watchlistItems.filter(w => !(w.id === item.id && w.mediaType === item.mediaType));
      } catch (e) { console.error('removeFromWatchlist:', e); }
    },

    openMediaDetail(item) {
      this.selectedItem = item;
    },

    // ── History ────────────────────────────────────────────────────────────────

    async loadHistory() {
      try {
        const res = await fetch('/api/media/history?limit=200');
        this.historyItems = await res.json();
      } catch (e) { console.error('loadHistory:', e); }
    },

    async clearHistory() {
      if (!confirm('Clear all playback history?')) return;
      try {
        await fetch('/api/media/history', { method: 'DELETE' });
        this.historyItems = [];
      } catch (e) { console.error('clearHistory:', e); }
    },

    // ── Player ─────────────────────────────────────────────────────────────────

    playManualStream() {
      const url = this.manualStreamUrl.trim();
      if (!url) return;
      this.currentStreamUrl = url;
      this.currentStreamTitle = '';
      this.$nextTick(() => {
        this._playUrl('mainPlayer', url, '', url.includes('.m3u8'), 'mainHls');
      });
    },

    _playUrl(videoId, url, title, isHls, hlsKey) {
      const video = document.getElementById(videoId);
      if (!video) return;

      if (this[hlsKey]) {
        try { this[hlsKey].destroy(); } catch (_) {}
        this[hlsKey] = null;
      }
      video.src = '';
      video.load();

      const useHls = isHls || url.includes('.m3u8') || url.toLowerCase().includes('m3u8');

      if (useHls && typeof Hls !== 'undefined' && Hls.isSupported()) {
        const hls = new Hls({ enableWorker: false, lowLatencyMode: true });
        this[hlsKey] = hls;
        hls.loadSource(url);
        hls.attachMedia(video);
        hls.on(Hls.Events.MANIFEST_PARSED, () => { video.play().catch(() => {}); });
        hls.on(Hls.Events.ERROR, (event, data) => {
          if (data.fatal) {
            if (data.type === Hls.ErrorTypes.NETWORK_ERROR) hls.startLoad();
            else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) hls.recoverMediaError();
            else { hls.destroy(); this[hlsKey] = null; }
          }
        });
      } else {
        video.src = url;
        video.play().catch(() => {});
      }
    },

    // ── SSE ────────────────────────────────────────────────────────────────────

    connectSse() {
      try {
        const es = new EventSource('/api/player/events');
        this.sse = es;
        es.onopen = () => { this.wsConnected = true; };
        es.onmessage = (evt) => {
          try { this.playerState = JSON.parse(evt.data); } catch (_) {}
        };
        es.addEventListener('episeerr', (evt) => {
          try {
            const entry = JSON.parse(evt.data);
            // Prepend to history feed if visible
            this.historyItems = [entry, ...this.historyItems].slice(0, 500);
            // Refresh pending list on watchlist.requested or rule.assigned
            if (entry.event === 'watchlist.requested' || entry.event === 'rule.assigned') {
              this.loadEpiseerrPending();
              if (entry.event === 'rule.assigned') this.loadEpiseerrRules();
            }
          } catch (_) {}
        });
        es.onerror = () => { this.wsConnected = false; };
      } catch (e) {
        setTimeout(() => this.connectSse(), 5000);
      }
    },

    // ── Helpers ────────────────────────────────────────────────────────────────

    eventBadgeClass(event) {
      if (!event) return 'bg-gray-700 text-gray-300';
      if (event === 'start') return 'bg-green-900 text-green-300';
      if (event === 'pause') return 'bg-yellow-900 text-yellow-300';
      if (event === 'stop' || event === 'finish') return 'bg-gray-700 text-gray-300';
      if (event === 'progress') return 'bg-blue-900 text-blue-300';
      if (event === 'episode.grabbed') return 'bg-orange-900 text-orange-300';
      if (event === 'episode.ready') return 'bg-teal-900 text-teal-300';
      if (event === 'rule.assigned' || event === 'rule.triggered') return 'bg-purple-900 text-purple-300';
      if (event.startsWith('watchlist.')) return 'bg-indigo-900 text-indigo-300';
      return 'bg-gray-700 text-gray-300';
    },

    formatMs(ms) {
      if (!ms || ms <= 0) return '0:00';
      const totalSec = Math.floor(ms / 1000);
      const h = Math.floor(totalSec / 3600);
      const m = Math.floor((totalSec % 3600) / 60);
      const s = totalSec % 60;
      if (h > 0) return `${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`;
      return `${m}:${String(s).padStart(2,'0')}`;
    },

    formatDate(iso) {
      if (!iso) return '';
      try {
        const d = new Date(iso);
        return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) + ' ' +
               d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
      } catch (_) {
        return iso.slice(0, 16).replace('T', ' ');
      }
    },
  };
}
