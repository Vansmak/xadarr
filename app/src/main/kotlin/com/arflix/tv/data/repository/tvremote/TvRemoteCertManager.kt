package com.arflix.tv.data.repository.tvremote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * One client identity for the whole app, reused across every Android TV we pair with — matches
 * how the reference implementation (and the official Google TV app) does it: pairing is a
 * trust-on-first-use handshake per TV, not a fresh identity per TV.
 *
 * **Deliberately a software key, NOT AndroidKeyStore.** Two earlier attempts stored this in
 * AndroidKeyStore and both failed the TLS handshake on real hardware with
 * `error:04000044:RSA routines:OPENSSL_internal:internal error` — first with only PKCS1 padding
 * authorized, then again after also authorizing RSA-PSS and every SHA digest. Captured logcat
 * from the TV's own pairing service showed the handshake completing with *no client certificate
 * presented at all* (`bnv: No local certificate for TLSv1.3 TLS_AES_128_GCM_SHA256`), while
 * Unimote pairing against the same TV, same TLS version, same cipher suite, worked fine — so the
 * blocker was the phone's hardware-backed keystore refusing the signature the handshake needs,
 * not the protocol or the TV. The reference Python client uses a plain PEM key file for exactly
 * this reason. Security-wise this is a LAN remote-control identity whose whole trust model is the
 * PIN shown on the TV screen, not the key's storage: hardware backing buys nothing here.
 */
@Singleton
class TvRemoteCertManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val DIR = "tvremote"
        private const val KEY_FILE = "client_key.pk8"
        private const val CERT_FILE = "client_cert.der"
        private const val VALID_DAYS = 3650L
    }

    private var cached: Pair<PrivateKey, X509Certificate>? = null

    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    /** Loads the persisted identity, generating and storing one on first use. The identity must
     * be stable across restarts — the pairing hash the TV verified is tied to this exact key. */
    @Synchronized
    fun ensureKeyPair(clientName: String) {
        if (cached != null) return
        loadExisting()?.let { cached = it; return }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp: KeyPair = kpg.generateKeyPair()
        val cert = SelfSignedCert.generate(clientName, kp, VALID_DAYS)

        File(dir, KEY_FILE).writeBytes(kp.private.encoded)
        File(dir, CERT_FILE).writeBytes(cert.encoded)
        cached = kp.private to cert
    }

    private fun loadExisting(): Pair<PrivateKey, X509Certificate>? = runCatching {
        val keyBytes = File(dir, KEY_FILE).takeIf { it.exists() }?.readBytes() ?: return null
        val certBytes = File(dir, CERT_FILE).takeIf { it.exists() }?.readBytes() ?: return null
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        key to cert
    }.getOrNull()

    private fun identity(): Pair<PrivateKey, X509Certificate> =
        cached ?: error("ensureKeyPair() must be called before using the client identity")

    fun clientCertificate(): X509Certificate = identity().second

    fun clientModulusAndExponent(): Pair<BigInteger, BigInteger> {
        val pub = clientCertificate().publicKey as RSAPublicKey
        return pub.modulus to pub.publicExponent
    }

    /**
     * TLS context presenting our client cert, with an accept-all trust manager for the *server's*
     * cert. This isn't a security downgrade specific to us — it's exactly how the reference
     * implementation and the official Google TV app do it too (verify_mode=CERT_NONE): there's no
     * CA to validate against since every cert here is self-signed, and trust is instead
     * established by the pairing handshake itself (the PIN shown on the TV proves physical
     * possession, and the SHA-256 hash ties the displayed code to the exact certs exchanged in
     * this session, defeating a MITM that doesn't also control the TV's screen).
     */
    fun buildSslContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").apply {
            init(arrayOf(AlwaysOurCertKeyManager()), arrayOf(trustAll), SecureRandom())
        }
    }

    /**
     * Always presents our one client identity, no matter what the server's CertificateRequest
     * asks for. The default KeyManager filters candidates by the key types and issuer DNs the
     * server advertises, and our self-signed cert is in nobody's accepted-issuer list — so it
     * could silently decline to send anything, which the TV rejects (it needs both certs to
     * compute the pairing hash). We have exactly one identity and it's the only thing the pairing
     * protocol will ever accept, so selecting it unconditionally is correct.
     */
    private inner class AlwaysOurCertKeyManager : X509ExtendedKeyManager() {
        private val alias = "xadarr"
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = alias
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun getCertificateChain(alias: String?) = arrayOf(identity().second)
        override fun getPrivateKey(alias: String?): PrivateKey = identity().first
    }
}

/**
 * Minimal DER X.509 v3 self-signed certificate builder.
 *
 * Hand-rolled rather than pulling in BouncyCastle (~9MB of dependency for one certificate, in an
 * APK already flagged as too large). Only the fields the pairing protocol actually looks at are
 * emitted — it reads the public modulus/exponent and nothing else. Verified on the JVM before
 * shipping: the output parses via CertificateFactory, self-verifies, and round-trips its key.
 */
internal object SelfSignedCert {
    private const val OID_SHA256_RSA = "1.2.840.113549.1.1.11"
    private const val OID_COMMON_NAME = "2.5.4.3"

    fun generate(commonName: String, keyPair: KeyPair, days: Long): X509Certificate {
        val dayMs = 24L * 60 * 60 * 1000
        // Backdated a day so a TV whose clock runs slightly behind ours doesn't see a not-yet-valid cert.
        val notBefore = Date(System.currentTimeMillis() - dayMs)
        val notAfter = Date(System.currentTimeMillis() + days * dayMs)

        val tbs = seq(
            explicit(0, integer(BigInteger.valueOf(2))),          // version: v3
            integer(BigInteger.valueOf(1000)),                    // serialNumber
            algSha256Rsa(),                                       // signature algorithm
            commonNameRdn(commonName),                            // issuer (== subject, self-signed)
            seq(utcTime(notBefore), utcTime(notAfter)),           // validity
            commonNameRdn(commonName),                            // subject
            keyPair.public.encoded,                               // already a DER SubjectPublicKeyInfo
        )

        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }

        val der = seq(tbs, algSha256Rsa(), bitString(signature))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    // ---- minimal DER primitives ----

    private fun derLength(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        val v = BigInteger.valueOf(n.toLong()).toByteArray().let {
            if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }
        return byteArrayOf((0x80 or v.size).toByte()) + v
    }

    private fun tlv(tag: Int, body: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(body.size) + body

    private fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.reduce { a, b -> a + b })
    private fun set(body: ByteArray): ByteArray = tlv(0x31, body)
    private fun integer(v: BigInteger): ByteArray = tlv(0x02, v.toByteArray())
    private fun utf8(s: String): ByteArray = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    private fun explicit(n: Int, body: ByteArray): ByteArray = tlv(0xA0 or n, body)
    private fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    /** Leading 0x00 is the count of unused bits in the final octet — always 0 for whole bytes. */
    private fun bitString(data: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0x00) + data)

    private fun utcTime(d: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return tlv(0x17, f.format(d).toByteArray(Charsets.US_ASCII))
    }

    private fun oid(dotted: String): ByteArray {
        val parts = dotted.split(".").map { it.toLong() }
        val out = ArrayList<Byte>()
        out.add((parts[0] * 40 + parts[1]).toByte())
        for (i in 2 until parts.size) {
            var v = parts[i]
            val chunk = ArrayList<Byte>()
            do {
                chunk.add((v and 0x7F).toByte())
                v = v shr 7
            } while (v > 0)
            // base-128 big-endian; high bit set on every byte except the last
            for (j in chunk.indices.reversed()) {
                out.add(if (j == 0) chunk[j] else (chunk[j].toInt() or 0x80).toByte())
            }
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun algSha256Rsa(): ByteArray = seq(oid(OID_SHA256_RSA), nullValue())

    private fun commonNameRdn(cn: String): ByteArray = seq(set(seq(oid(OID_COMMON_NAME), utf8(cn))))
}
