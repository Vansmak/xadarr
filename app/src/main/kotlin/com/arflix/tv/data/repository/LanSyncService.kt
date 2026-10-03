package com.arflix.tv.data.repository

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class LanPeer(val host: String, val port: Int, val deviceName: String = "") {
    val baseUrl: String get() = "http://$host:$port"
    val displayName: String get() = deviceName.ifBlank { host }
}

@Singleton
class LanSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "LanSyncService"
        private const val SERVICE_TYPE = "_xadarr._tcp."
        private const val SERVICE_NAME = "Xadarr"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }

    // Separate, short-timeout client for LAN calls. The shared okHttpClient is tuned for real
    // internet calls (30s+ timeouts) -- reusing it here meant a single unreachable LAN peer (one
    // that's asleep, or just rebooted) blocked pushToPeers() for a full 30 seconds each, and with
    // pushes running sequentially, two dead peers alone cost a full minute. A LAN round trip that
    // takes more than a few seconds isn't going to succeed anyway.
    private val lanHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    val peers: StateFlow<List<LanPeer>> = _peers

    private val knownPeers = ConcurrentHashMap<String, LanPeer>()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var advertisedPort: Int = 0

    // A push cycle already touching every peer means a second, concurrent cycle can only ever
    // duplicate that work and pile up behind the same slow/dead peers. Confirmed live 2026-09-28:
    // repeated overlapping push cycles to the same peers, response times climbing (1s -> 18s) as
    // they stacked up, which is what actually made IPTV loads look "stuck" -- the fetch itself
    // finished in ~1s, the LAN push pile-up ran for minutes afterward.
    private val pushMutex = Mutex()

    // This device's own LAN addresses, so NSD's self-discovery (a device commonly discovers its
    // own advertised service back on this network) doesn't add itself as a peer. Confirmed live:
    // .91 was PUTting its own settings snapshot to http://192.168.254.91:7979 -- to itself.
    private fun localAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    fun start(port: Int) {
        if (advertisedPort == port && registrationListener != null) return
        stop()
        advertisedPort = port
        advertise(port)
        discover()
    }

    fun stop() {
        runCatching { registrationListener?.let { nsdManager.unregisterService(it) } }
        runCatching { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
    }

    private fun advertise(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                Log.w(TAG, "NSD registration failed: $code")
            }
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            override fun onServiceRegistered(i: NsdServiceInfo) {
                Log.i(TAG, "NSD advertised: ${i.serviceName} port=$port")
            }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "NSD register failed: ${it.message}") }
    }

    private fun discover() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { Log.i(TAG, "NSD discovery started") }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "NSD discovery failed: $code")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) {
                        Log.w(TAG, "NSD resolve failed: $code")
                    }
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val host = i.host?.hostAddress ?: return
                        scope.launch { validateAndAdd(LanPeer(host, i.port)) }
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                // No host info at loss time — stale peers are pruned on failed push
            }
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "NSD discover failed: ${it.message}") }
    }

    private suspend fun validateAndAdd(peer: LanPeer) {
        if (peer.host in localAddresses() && peer.port == advertisedPort) {
            Log.i(TAG, "Ignoring self-discovered NSD service: ${peer.host}:${peer.port}")
            return
        }
        // deviceName rides along on the same status check every peer already gets — no extra
        // round trip, no NSD TXT-record re-registration on every name edit.
        val deviceName = runCatching {
            val req = Request.Builder().url("${peer.baseUrl}/api/sync/status").get().build()
            lanHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                runCatching { org.json.JSONObject(body).optString("deviceName") }.getOrNull()
            }
        }.getOrNull()
        if (deviceName != null) {
            val key = "${peer.host}:${peer.port}"
            knownPeers[key] = peer.copy(deviceName = deviceName)
            _peers.value = knownPeers.values.toList()
            Log.i(TAG, "LAN peer validated: $key ($deviceName)")
        }
    }

    suspend fun pushToPeers(payload: String): Int {
        // If a push cycle is already running, a second one can only duplicate work and queue
        // up behind the same slow/unreachable peers the first is already waiting on -- skip
        // rather than stack. The in-flight cycle already covers whatever this call would push.
        if (pushMutex.isLocked) {
            Log.i(TAG, "Skipping pushToPeers — a push cycle is already in flight")
            return 0
        }
        // Hard ceiling on the whole call, lock included. A mutex a hung call never releases is a
        // permanent freeze for every future caller -- confirmed live 2026-09-28 on a device that
        // deadlocked at 0% CPU (a real hang, not a busy loop; a thread dump was captured but not
        // readable without root) shortly after this lock was introduced. lanHttpClient itself
        // already times out any single HTTP call by ~8s; this bounds the whole cycle (lock wait +
        // every peer's call) so a stuck lock degrades to "this push was dropped," never "this
        // device is frozen." Joe, 2026-09-28: "so this will happen on any new device install?"
        return withTimeoutOrNull(15_000L) {
            pushMutex.withLock {
                val current = _peers.value
                if (current.isEmpty()) return@withLock 0
                val body = payload.toRequestBody("application/json".toMediaType())
                withContext(Dispatchers.IO) {
                    current.map { peer ->
                        async {
                            runCatching {
                                val req = Request.Builder()
                                    .url("${peer.baseUrl}/api/sync/snapshot")
                                    .put(body)
                                    .build()
                                lanHttpClient.newCall(req).execute().use { resp ->
                                    if (resp.isSuccessful) true else { prune(peer); false }
                                }
                            }.getOrElse { prune(peer); false }
                        }
                    }.awaitAll().count { it }
                }
            }
        } ?: run {
            Log.w(TAG, "pushToPeers timed out after 15s -- dropped, not frozen")
            0
        }
    }

    suspend fun pullFromPeer(peer: LanPeer): String? = runCatching {
        val req = Request.Builder().url("${peer.baseUrl}/api/sync/snapshot").get().build()
        lanHttpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }.getOrNull()

    private fun prune(peer: LanPeer) {
        knownPeers.remove("${peer.host}:${peer.port}")
        _peers.value = knownPeers.values.toList()
    }
}
