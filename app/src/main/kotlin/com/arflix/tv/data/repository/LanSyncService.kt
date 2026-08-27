package com.arflix.tv.data.repository

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
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

    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    val peers: StateFlow<List<LanPeer>> = _peers

    private val knownPeers = ConcurrentHashMap<String, LanPeer>()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var advertisedPort: Int = 0

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
        // deviceName rides along on the same status check every peer already gets — no extra
        // round trip, no NSD TXT-record re-registration on every name edit.
        val deviceName = runCatching {
            val req = Request.Builder().url("${peer.baseUrl}/api/sync/status").get().build()
            okHttpClient.newCall(req).execute().use { resp ->
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
        val current = _peers.value
        if (current.isEmpty()) return 0
        val body = payload.toRequestBody("application/json".toMediaType())
        var count = 0
        for (peer in current) {
            runCatching {
                val req = Request.Builder()
                    .url("${peer.baseUrl}/api/sync/snapshot")
                    .put(body)
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) count++ else prune(peer)
                }
            }.onFailure { prune(peer) }
        }
        return count
    }

    suspend fun pullFromPeer(peer: LanPeer): String? = runCatching {
        val req = Request.Builder().url("${peer.baseUrl}/api/sync/snapshot").get().build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }.getOrNull()

    private fun prune(peer: LanPeer) {
        knownPeers.remove("${peer.host}:${peer.port}")
        _peers.value = knownPeers.values.toList()
    }
}
