package com.arflix.tv.data.repository.tvremote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.arflix.tv.remoteservice.proto.RemoteDirection
import com.arflix.tv.remoteservice.proto.RemoteKeyCode
import com.arflix.tv.util.settingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

val TV_REMOTE_PAIRED_HOSTS_KEY = stringSetPreferencesKey("tv_remote_paired_hosts")

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

    suspend fun isPaired(host: String): Boolean =
        context.settingsDataStore.data.first()[TV_REMOTE_PAIRED_HOSTS_KEY]?.contains(host) == true

    suspend fun markPaired(host: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[TV_REMOTE_PAIRED_HOSTS_KEY] ?: emptySet()
            prefs[TV_REMOTE_PAIRED_HOSTS_KEY] = current + host
        }
    }

    suspend fun forgetPairing(host: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[TV_REMOTE_PAIRED_HOSTS_KEY] ?: emptySet()
            prefs[TV_REMOTE_PAIRED_HOSTS_KEY] = current - host
        }
    }

    /** Starts a pairing session — caller holds onto the returned client to call finish() once
     * the user has typed in the code shown on the TV, then must call close() either way. */
    fun startPairing(): TvRemotePairingClient = TvRemotePairingClient(certManager, clientName)

    suspend fun onPairingFinished(host: String) {
        markPaired(host)
    }

    /**
     * Sends a single key press to an already-paired TV. Returns false (not throws) on any
     * failure — connection refused, TLS failure (not actually paired despite the stored flag,
     * e.g. the TV's own pairing storage was cleared), timeout, etc. — since this is called from
     * UI button taps where the only useful response is "did it work."
     */
    suspend fun sendKeyCode(host: String, keyCode: RemoteKeyCode, direction: RemoteDirection = RemoteDirection.SHORT): Boolean {
        if (!isPaired(host)) return false
        val client = TvRemoteControlClient(certManager, clientName)
        return try {
            client.connect(host)
            client.sendKeyCode(keyCode, direction)
            true
        } catch (e: Exception) {
            false
        } finally {
            client.disconnect()
        }
    }

    // Toggle, matching a physical remote's power button — there's no HTTP-path equivalent at
    // all (the old Remote Mode DPadKey enum has no power key), so this only ever works once
    // paired; the UI gates on isTvRemotePaired rather than silently falling back to a no-op.
    suspend fun sendPower(host: String): Boolean = sendKeyCode(host, RemoteKeyCode.KEYCODE_POWER)

    suspend fun sendVolumeUp(host: String): Boolean = sendKeyCode(host, RemoteKeyCode.KEYCODE_VOLUME_UP)
    suspend fun sendVolumeDown(host: String): Boolean = sendKeyCode(host, RemoteKeyCode.KEYCODE_VOLUME_DOWN)
}
