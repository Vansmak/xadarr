function arvio() {
  return {
    tabs: [
      { id: 'settings', label: 'Settings' },
      { id: 'media',    label: 'Media' },
      { id: 'livetv',   label: 'Live TV' },
      { id: 'player',   label: 'Player' },
    ],
    activeTab: 'settings',

    // Settings
    settings: {},
    saveStatus: '',

    // Media
    watchlistItems: [],
    homeItems: [],
    trendingItems: [],
    searchQuery: '',
    searchResults: [],
    selectedItem: null,

    // Live TV
    channels: [],
    channelGroups: [],
    selectedGroup: 'All',
    epg: {},
    activeChannel: null,
    liveHls: null,

    // Player
    playerState: { isPlaying: false, isPaused: false, title: '', episodeTitle: '', overview: '', positionMs: 0, durationMs: 0, streamUrl: '', isLive: false },
    currentStreamUrl: null,
    currentStreamTitle: '',
    mainHls: null,

    // SSE (Server-Sent Events)
    sse: null,
    wsConnected: false,

    get filteredChannels() {
      if (this.selectedGroup === 'All') return this.channels;
      return this.channels.filter(ch => ch.group === this.selectedGroup);
    },

    get liveProgramNow() {
      if (!this.activeChannel) return '';
      const entry = this.epg[this.activeChannel.epgId] || this.epg[this.activeChannel.id];
      return entry?.now?.title || '';
    },

    get liveProgramNext() {
      if (!this.activeChannel) return '';
      const entry = this.epg[this.activeChannel.epgId] || this.epg[this.activeChannel.id];
      return entry?.next?.title || '';
    },

    async init() {
      await this.loadSettings();
      this.loadChannels();
      this.loadWatchlist();
      this.loadHome();
      this.loadTrending();
      this.connectSse();
    },

    // ── Settings ──────────────────────────────────────────────────────────────

    async loadSettings() {
      try {
        const res = await fetch('/api/settings');
        this.settings = await res.json();
      } catch (e) {
        console.error('loadSettings:', e);
      }
    },

    async saveSettings() {
      try {
        await fetch('/api/settings', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(this.settings),
        });
        this.saveStatus = 'Saved!';
        setTimeout(() => { this.saveStatus = ''; }, 2000);
      } catch (e) {
        this.saveStatus = 'Error saving';
        setTimeout(() => { this.saveStatus = ''; }, 3000);
      }
    },

    // ── Media ────────────────────────────────────────────────────────────────

    async loadWatchlist() {
      try {
        const res = await fetch('/api/media/watchlist');
        this.watchlistItems = await res.json();
      } catch (e) {
        console.error('loadWatchlist:', e);
      }
    },

    async loadHome() {
      try {
        const res = await fetch('/api/media/home');
        this.homeItems = await res.json();
      } catch (e) {
        console.error('loadHome:', e);
      }
    },

    async loadTrending() {
      try {
        const res = await fetch('/api/media/trending');
        this.trendingItems = await res.json();
      } catch (e) {
        console.error('loadTrending:', e);
      }
    },

    async onSearchInput() {
      const q = this.searchQuery.trim();
      if (q.length < 2) { this.searchResults = []; return; }
      try {
        const res = await fetch('/api/media/search?q=' + encodeURIComponent(q));
        this.searchResults = await res.json();
      } catch (e) {
        console.error('search:', e);
      }
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

    openHomeDetail(item) {
      this.selectedItem = item;
    },

    playHomeItem(item) {
      if (!item) return;
      this.selectedItem = null;
      // Home server items don't have a raw stream URL we can play directly.
      // Show info and note – full stream resolution requires the TV app.
      alert(`"${item.title}" is a home server item.\n\nStream resolution requires the TV app. Use the TV remote to resume, or stream the item directly if you know the URL.`);
    },

    // ── Live TV ───────────────────────────────────────────────────────────────

    async loadChannels() {
      try {
        const res = await fetch('/api/iptv/channels');
        const data = await res.json();
        this.channels = data.channels || [];
        this.channelGroups = data.groups || [];
        this.loadEpg();
      } catch (e) {
        console.error('loadChannels:', e);
      }
    },

    async loadEpg() {
      try {
        const res = await fetch('/api/iptv/epg');
        this.epg = await res.json();
      } catch (e) {
        console.error('loadEpg:', e);
      }
    },

    playChannel(ch) {
      this.activeChannel = ch;
      this.$nextTick(() => {
        this._playUrl('livePlayer', ch.streamUrl, ch.name, /* live */ true, 'liveHls', 'liveHls');
      });
    },

    // ── Player ────────────────────────────────────────────────────────────────

    playStream(url, title) {
      this.currentStreamUrl = url;
      this.currentStreamTitle = title || '';
      this.activeTab = 'player';
      this.$nextTick(() => {
        this._playUrl('mainPlayer', url, title, url.includes('.m3u8') || url.includes('m3u8'), 'mainHls', 'mainHls');
      });
    },

    _playUrl(videoId, url, title, isHls, hlsKey) {
      const video = document.getElementById(videoId);
      if (!video) return;

      // Destroy existing HLS instance if any
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
        hls.on(Hls.Events.MANIFEST_PARSED, () => {
          video.play().catch(() => {});
        });
        hls.on(Hls.Events.ERROR, (event, data) => {
          if (data.fatal) {
            switch (data.type) {
              case Hls.ErrorTypes.NETWORK_ERROR:
                hls.startLoad();
                break;
              case Hls.ErrorTypes.MEDIA_ERROR:
                hls.recoverMediaError();
                break;
              default:
                hls.destroy();
                this[hlsKey] = null;
            }
          }
        });
      } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
        video.src = url;
        video.play().catch(() => {});
      } else {
        video.src = url;
        video.play().catch(() => {});
      }
    },

    // ── SSE (Server-Sent Events) ──────────────────────────────────────────────

    connectSse() {
      try {
        const es = new EventSource('/api/player/events');
        this.sse = es;
        es.onopen = () => { this.wsConnected = true; };
        es.onmessage = (evt) => {
          try { this.playerState = JSON.parse(evt.data); } catch (_) {}
        };
        es.onerror = () => {
          this.wsConnected = false;
          // EventSource reconnects automatically; update indicator
        };
      } catch (e) {
        setTimeout(() => this.connectSse(), 5000);
      }
    },

    // ── Helpers ───────────────────────────────────────────────────────────────

    formatMs(ms) {
      if (!ms || ms <= 0) return '0:00';
      const totalSec = Math.floor(ms / 1000);
      const h = Math.floor(totalSec / 3600);
      const m = Math.floor((totalSec % 3600) / 60);
      const s = totalSec % 60;
      if (h > 0) return `${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`;
      return `${m}:${String(s).padStart(2,'0')}`;
    },
  };
}

// Alpine auto-initializes via defer CDN tag; no manual start needed.
