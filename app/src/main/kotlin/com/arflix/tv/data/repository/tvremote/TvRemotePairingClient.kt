package com.arflix.tv.data.repository.tvremote

import com.arflix.tv.remoteservice.proto.Configuration
import com.arflix.tv.remoteservice.proto.OuterMessage
import com.arflix.tv.remoteservice.proto.Options
import com.arflix.tv.remoteservice.proto.PairingRequest
import com.arflix.tv.remoteservice.proto.Secret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class TvRemotePairingException(message: String) : Exception(message)

/**
 * One-time (per TV) pairing handshake — see the plan doc for the full protocol writeup. Two
 * phases matching the reference implementation's own split: [start] does the three-message
 * negotiation up through the TV showing its 6-digit code on screen, then [finish] verifies the
 * code the user typed back in and completes pairing. A fresh instance per pairing attempt; not
 * reused for the control channel afterward.
 */
class TvRemotePairingClient(
    private val certManager: TvRemoteCertManager,
    private val clientName: String,
) {
    private var socket: SSLSocket? = null

    suspend fun start(host: String, port: Int = 6467) {
        withContext(Dispatchers.IO) {
            certManager.ensureKeyPair(clientName)
            val sslContext = certManager.buildSslContext()
            val s = sslContext.socketFactory.createSocket(host, port) as SSLSocket
            s.startHandshake()
            socket = s

            send(
                outerMessage {
                    setPairingRequest(
                        PairingRequest.newBuilder()
                            .setServiceName("atvremote")
                            .setClientName(clientName)
                    )
                }
            )
            val ack = receive()
            if (!ack.hasPairingRequestAck()) {
                throw TvRemotePairingException("Expected pairing_request_ack, got: $ack")
            }

            send(
                outerMessage {
                    setOptions(
                        Options.newBuilder()
                            .setPreferredRole(Options.RoleType.ROLE_TYPE_INPUT)
                            .addInputEncodings(
                                Options.Encoding.newBuilder()
                                    .setType(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                                    .setSymbolLength(6)
                            )
                    )
                }
            )
            val optionsResponse = receive()
            if (!optionsResponse.hasOptions()) {
                throw TvRemotePairingException("Expected options, got: $optionsResponse")
            }

            send(
                outerMessage {
                    setConfiguration(
                        Configuration.newBuilder()
                            .setClientRole(Options.RoleType.ROLE_TYPE_INPUT)
                            .setEncoding(
                                Options.Encoding.newBuilder()
                                    .setType(Options.Encoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                                    .setSymbolLength(6)
                            )
                    )
                }
            )
            val configAck = receive()
            if (!configAck.hasConfigurationAck()) {
                throw TvRemotePairingException("Expected configuration_ack, got: $configAck")
            }
            // The TV is now showing a 6-digit hex code on screen — caller prompts the user for it.
        }
    }

    /** @param pairingCode the 6-hex-digit code shown on the TV. */
    suspend fun finish(pairingCode: String) {
        withContext(Dispatchers.IO) {
            val s = socket ?: throw TvRemotePairingException("start() must be called first")
            if (pairingCode.length != 6 || pairingCode.any { it.digitToIntOrNull(16) == null }) {
                throw TvRemotePairingException("Pairing code must be exactly 6 hex digits")
            }

            val (clientMod, clientExp) = certManager.clientModulusAndExponent()
            val serverCert = s.session.peerCertificates[0] as X509Certificate
            val serverPub = serverCert.publicKey as RSAPublicKey

            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(hexToBytes(clientMod.toString(16).uppercase()))
            digest.update(hexToBytes("0" + clientExp.toString(16).uppercase()))
            digest.update(hexToBytes(serverPub.modulus.toString(16).uppercase()))
            digest.update(hexToBytes("0" + serverPub.publicExponent.toString(16).uppercase()))
            digest.update(hexToBytes(pairingCode.substring(2)))
            val hashResult = digest.digest()

            val expectedFirstByte = pairingCode.substring(0, 2).toInt(16)
            if ((hashResult[0].toInt() and 0xFF) != expectedFirstByte) {
                throw TvRemotePairingException("Pairing code didn't match — check the code shown on the TV")
            }

            send(
                outerMessage {
                    setSecret(Secret.newBuilder().setSecret(com.google.protobuf.ByteString.copyFrom(hashResult)))
                }
            )
            val secretAck = receive()
            if (!secretAck.hasSecretAck()) {
                throw TvRemotePairingException("Pairing rejected: $secretAck")
            }
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun send(msg: OuterMessage) {
        val s = socket ?: throw TvRemotePairingException("Not connected")
        msg.writeDelimitedTo(s.outputStream)
        s.outputStream.flush()
    }

    private fun receive(): OuterMessage {
        val s = socket ?: throw TvRemotePairingException("Not connected")
        val msg = OuterMessage.parseDelimitedFrom(s.inputStream)
            ?: throw TvRemotePairingException("Connection closed while waiting for a response")
        if (msg.status != OuterMessage.Status.STATUS_OK) {
            throw TvRemotePairingException("TV returned status ${msg.status}")
        }
        return msg
    }

    private inline fun outerMessage(block: OuterMessage.Builder.() -> Unit): OuterMessage =
        OuterMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(OuterMessage.Status.STATUS_OK)
            .apply(block)
            .build()

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 != 0) "0$hex" else hex
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
