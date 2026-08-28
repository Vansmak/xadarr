package com.arflix.tv.data.repository.tvremote

import com.arflix.tv.remoteservice.proto.RemoteConfigure
import com.arflix.tv.remoteservice.proto.RemoteDeviceInfo
import com.arflix.tv.remoteservice.proto.RemoteDirection
import com.arflix.tv.remoteservice.proto.RemoteKeyCode
import com.arflix.tv.remoteservice.proto.RemoteKeyInject
import com.arflix.tv.remoteservice.proto.RemoteMessage
import com.arflix.tv.remoteservice.proto.RemotePingResponse
import com.arflix.tv.remoteservice.proto.RemoteSetActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.net.ssl.SSLSocket

class TvRemoteControlException(message: String, cause: Throwable? = null) : Exception(message, cause)

// From the protocol's Feature bitmask (remote.py) — only the bits we actually use.
private const val FEATURE_PING = 1 shl 0
private const val FEATURE_KEY = 1 shl 1
private const val FEATURE_POWER = 1 shl 5
private const val FEATURE_VOLUME = 1 shl 6
private const val ACTIVE_FEATURES = FEATURE_PING or FEATURE_KEY or FEATURE_POWER or FEATURE_VOLUME

/**
 * Persistent control-channel connection to an already-paired Android TV (port 6466). Handles the
 * RemoteConfigure handshake, responds to the server's periodic pings (required — the TV closes
 * the connection after ~16s with no traffic), and lets the caller inject key events. One instance
 * per active connection; call [connect] once, then [sendKeyCode] as needed, [disconnect] when done
 * (e.g. Remote Mode target changed or the panel closed).
 */
class TvRemoteControlClient(
    private val certManager: TvRemoteCertManager,
    private val clientName: String,
) {
    private var socket: SSLSocket? = null
    private val writeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readLoopJob: Job? = null

    /** Whether this connection is still usable — the TV drops us after ~16s without a ping
     * response, and the read loop ends when that happens, so check both. */
    val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true && readLoopJob?.isActive == true

    suspend fun connect(host: String, port: Int = 6466) {
        withContext(Dispatchers.IO) {
            certManager.ensureKeyPair(clientName)
            val sslContext = certManager.buildSslContext()
            val s = sslContext.socketFactory.createSocket(host, port) as SSLSocket
            // See TvRemotePairingClient's identical comment — do NOT force TLS 1.2, it broke
            // pairing against a real Shield's pairing server (no cert for the resulting RSA
            // cipher suite). Left unset so it negotiates TLS 1.3 like the server actually wants.
            try {
                s.startHandshake()
            } catch (e: Exception) {
                throw TvRemoteControlException("TLS handshake failed — this device may not be paired yet", e)
            }
            socket = s

            // First message from the TV is always remote_configure; respond with our own, then
            // it follows with remote_set_active (echo back) and remote_start (connection ready).
            val configure = readOne(s)
            if (!configure.hasRemoteConfigure()) {
                throw TvRemoteControlException("Expected remote_configure, got: $configure")
            }
            writeOne(
                s,
                remoteMessage {
                    setRemoteConfigure(
                        RemoteConfigure.newBuilder()
                            .setCode1(ACTIVE_FEATURES)
                            .setDeviceInfo(
                                RemoteDeviceInfo.newBuilder()
                                    .setPackageName("com.arflix.tv")
                                    .setAppVersion("1.0.0")
                            )
                    )
                }
            )

            val setActive = readOne(s)
            if (setActive.hasRemoteSetActive()) {
                writeOne(s, remoteMessage { setRemoteSetActive(RemoteSetActive.newBuilder().setActive(ACTIVE_FEATURES)) })
            }

            // remote_start confirms the TV is ready to receive key commands.
            var started = setActive.hasRemoteStart()
            if (!started) {
                val startMsg = readOne(s)
                started = startMsg.hasRemoteStart()
            }
            if (!started) {
                throw TvRemoteControlException("TV never sent remote_start")
            }
        }
        readLoopJob = scope.launch { readLoop() }
    }

    suspend fun sendKeyCode(keyCode: RemoteKeyCode, direction: RemoteDirection = RemoteDirection.SHORT) {
        val s = socket ?: throw TvRemoteControlException("Not connected")
        writeOne(
            s,
            remoteMessage {
                setRemoteKeyInject(RemoteKeyInject.newBuilder().setKeyCode(keyCode).setDirection(direction))
            }
        )
    }

    fun disconnect() {
        readLoopJob?.cancel()
        readLoopJob = null
        runCatching { socket?.close() }
        socket = null
    }

    /** Keepalive + config-message handling that has to keep running for the life of the connection. */
    private suspend fun readLoop() {
        val s = socket ?: return
        while (true) {
            val msg = try {
                withContext(Dispatchers.IO) { RemoteMessage.parseDelimitedFrom(s.inputStream) }
            } catch (e: Exception) {
                break
            } ?: break
            if (msg.hasRemotePingRequest()) {
                runCatching {
                    writeOne(
                        s,
                        remoteMessage {
                            setRemotePingResponse(
                                RemotePingResponse.newBuilder()
                                    .setVal1(msg.remotePingRequest.val1)
                            )
                        }
                    )
                }
            }
            // remote_set_volume_level / remote_ime_* etc. are informational only for this
            // proof of concept — not consumed yet, no UI surfaces volume level/current app.
        }
    }

    private suspend fun writeOne(s: SSLSocket, msg: RemoteMessage) {
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                msg.writeDelimitedTo(s.outputStream)
                s.outputStream.flush()
            }
        }
    }

    private fun readOne(s: SSLSocket): RemoteMessage =
        RemoteMessage.parseDelimitedFrom(s.inputStream)
            ?: throw TvRemoteControlException("Connection closed during handshake")

    private inline fun remoteMessage(block: RemoteMessage.Builder.() -> Unit): RemoteMessage =
        RemoteMessage.newBuilder().apply(block).build()
}
