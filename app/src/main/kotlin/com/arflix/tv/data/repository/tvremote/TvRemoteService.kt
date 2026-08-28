package com.arflix.tv.data.repository.tvremote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.arflix.tv.data.repository.LanPeer
import com.arflix.tv.remoteservice.proto.RemoteDirection
import com.arflix.tv.remoteservice.proto.RemoteKeyCode
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

val TV_REMOTE_PAIRED_HOSTS_KEY = stringSetPreferencesKey("tv_remote_paired_hosts")

/** Long enough that it never lands near an in-flight key press (see scheduleIdleDisconnect). */
private const val IDLE_DISCONNECT_MS = 5 * 60 * 1000L

// LanSyncService's own advertised port — reused as the default when reconstructing a synthetic
// LanPeer for a paired-but-currently-unreachable device (see pairedPeers() below). Only matters
// if some other Remote Mode action (dpad/tune, which go over this HTTP port) is attempted against
// a device that's actually offline; it'll just fail cleanly like any unreachable peer does today.
private const val DEFAULT_XADARR_PORT = 7979

/**
 * Orchestrates the Android TV Remote Service client — pairing state (which hosts we've
 * successfully paired with, persisted so it survives app restarts — the AndroidKeyStore-backed
 * client cert itself already does, so re-pairing is only needed if this set is cleared or the
 * key alias is lost, e.g. app data wipe) and sending key commands.
 *
 * v1 scope: connects fresh for each key send rather than keeping a persistent control-channel
 * connection alive. Simpler and more robust for a first cut (no stale-connection/reconnect state
 * machine to get wrong) at the cost of a TLS handshake's worth of latency (~100-300ms) per key
 * press. Worth revisiting once this is confirmed working on real hardware — see the plan doc.
 */
@Singleton
class TvRemoteService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val certManager: TvRemoteCertManager,
) {
    private val clientName: String get() = "Xadarr"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionLock = Mutex()
    private var activeClient: TvRemoteControlClient? = null
    private var activeHost: String? = null
    private var idleJob: Job? = null

    // Entries are "$host::$deviceName" rather than bare hosts, so a paired device can still be
    // shown (and selected, e.g. to send a power-on) via pairedPeers() below even once it's dropped
    // out of LanSyncService's live peers list — which happens more readily than you'd expect once
    // a TV actually goes to sleep, see project_remote_mode_device_visibility_2026-08-28 memory.
    private fun entryFor(host: String, current: Set<String>): String? =
        current.firstOrNull { it.substringBefore("::") == host }

    suspend fun isPaired(host: String): Boolean =
        entryFor(host, context.settingsDataStore.data.first()[TV_REMOTE_PAIRED_HOSTS_KEY] ?: emptySet()) != null

    suspend fun markPaired(host: String, deviceName: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[TV_REMOTE_PAIRED_HOSTS_KEY] ?: emptySet()
            val withoutHost = current.filterNot { it.substringBefore("::") == host }.toSet()
            prefs[TV_REMOTE_PAIRED_HOSTS_KEY] = withoutHost + "$host::${deviceName.ifBlank { host }}"
        }
    }

    suspend fun forgetPairing(host: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[TV_REMOTE_PAIRED_HOSTS_KEY] ?: emptySet()
            prefs[TV_REMOTE_PAIRED_HOSTS_KEY] = current.filterNot { it.substringBefore("::") == host }.toSet()
        }
        if (activeHost == host) connectionLock.withLock { closeActive() }
    }

    /** Paired devices as selectable LanPeers, independent of whether LanSyncService currently
     * sees them live — the whole point being a device you can't currently reach (asleep, screen
     * off) must still show up so you can send it a power command in the first place. */
    val pairedPeers = context.settingsDataStore.data.map { prefs ->
        (prefs[TV_REMOTE_PAIRED_HOSTS_KEY] ?: emptySet()).map { entry ->
            val host = entry.substringBefore("::")
            val name = entry.substringAfter("::", host)
            LanPeer(host = host, port = DEFAULT_XADARR_PORT, deviceName = name)
        }
    }

    /** Starts a pairing session — caller holds onto the returned client to call finish() once
     * the user has typed in the code shown on the TV, then must call close() either way. */
    fun startPairing(): TvRemotePairingClient = TvRemotePairingClient(certManager, clientName)

    suspend fun onPairingFinished(host: String, deviceName: String) {
        markPaired(host, deviceName)
    }

    /**
     * Sends a single key press to an already-paired TV. Returns false (not throws) on any
     * failure — connection refused, TLS failure (not actually paired despite the stored flag,
     * e.g. the TV's own pairing storage was cleared), timeout, etc. — since this is called from
     * UI button taps where the only useful response is "did it work."
     */
    suspend fun sendKeyCode(host: String, keyCode: RemoteKeyCode, direction: RemoteDirection = RemoteDirection.SHORT): Boolean {
        if (!isPaired(host)) return false
        return connectionLock.withLock {
            try {
                try {
                    clientFor(host).sendKeyCode(keyCode, direction)
                } catch (e: Exception) {
                    // Most likely a connection the TV had already dropped (idle timeout, standby,
                    // network blip) — rebuild it once and retry before reporting failure.
                    closeActive()
                    clientFor(host).sendKeyCode(keyCode, direction)
                }
                scheduleIdleDisconnect()
                true
            } catch (e: Exception) {
                closeActive()
                false
            }
        }
    }

    private suspend fun clientFor(host: String): TvRemoteControlClient {
        val existing = activeClient
        if (existing != null && activeHost == host && existing.isConnected) return existing
        closeActive()
        val client = TvRemoteControlClient(certManager, clientName)
        client.connect(host)
        activeClient = client
        activeHost = host
        return client
    }

    private fun closeActive() {
        idleJob?.cancel()
        idleJob = null
        runCatching { activeClient?.disconnect() }
        activeClient = null
        activeHost = null
    }

    /**
     * Drops the connection only after a long quiet period, never right after a key press.
     *
     * Disconnecting immediately after sending a key crashed a real Shield's `system_server`
     * outright (NPE in `PhoneWindowManager.deviceSupportsVolumeMuteHotkey` — an AOSP missing
     * null check): closing the control channel tears down the remote service's virtual input
     * device while the key is still queued in the accessibility `KeyboardInterceptor`, so the
     * device lookup for the in-flight event returns null. Holding the connection open is also
     * simply what a real remote does — Unimote keeps its channel up for the whole session — and
     * it avoids a TLS handshake's latency on every single press.
     */
    private fun scheduleIdleDisconnect() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_DISCONNECT_MS)
            connectionLock.withLock { closeActive() }
        }
    }

    // Toggle, matching a physical remote's power button — there's no HTTP-path equivalent at
    // all (the old Remote Mode DPadKey enum has no power key), so this only ever works once
    // paired; the UI gates on isTvRemotePaired rather than silently falling back to a no-op.
    suspend fun sendPower(host: String): Boolean = sendKeyCode(host, RemoteKeyCode.KEYCODE_POWER)

    suspend fun sendVolumeUp(host: String): Boolean = sendKeyCode(host, RemoteKeyCode.KEYCODE_VOLUME_UP)
    suspend fun sendVolumeDown(host: String): Boolean = sendKeyCode(host, RemoteKeyCode.KEYCODE_VOLUME_DOWN)
}
