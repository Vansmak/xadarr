package com.arflix.tv.data.repository.tvremote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * One client identity for the whole app, reused across every Android TV we pair with — matches
 * how the reference implementation (and the official Google TV app) does it: pairing is a
 * trust-on-first-use handshake per TV, not a fresh identity per TV. The key never leaves
 * AndroidKeyStore; TLS uses it directly via KeyManagerFactory, and we only ever need to read
 * its public modulus/exponent (for the pairing-code hash) and the generated self-signed cert.
 */
@Singleton
class TvRemoteCertManager @Inject constructor() {
    companion object {
        private const val ALIAS = "xadarr_tv_remote_client"
        private const val PROVIDER = "AndroidKeyStore"
    }

    private val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** Generates the client keypair/cert on first use. No-op if already present. */
    fun ensureKeyPair(clientName: String) {
        if (keyStore.containsAlias(ALIAS)) return
        val now = Date()
        val notAfter = Date(now.time + TimeUnit.DAYS.toMillis(10 * 365))
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=$clientName"))
            .setCertificateSerialNumber(BigInteger.valueOf(1000))
            .setCertificateNotBefore(now)
            .setCertificateNotAfter(notAfter)
            .build()
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER)
        kpg.initialize(spec)
        kpg.generateKeyPair()
    }

    fun clientCertificate(): X509Certificate = keyStore.getCertificate(ALIAS) as X509Certificate

    fun clientModulusAndExponent(): Pair<BigInteger, BigInteger> {
        val pub = clientCertificate().publicKey as RSAPublicKey
        return pub.modulus to pub.publicExponent
    }

    /**
     * TLS context presenting our AndroidKeyStore-backed client cert, with an accept-all trust
     * manager for the *server's* cert. This isn't a security downgrade specific to us — it's
     * exactly how the reference implementation and the official Google TV app do it too
     * (verify_mode=CERT_NONE): there's no CA to validate against since every cert here is
     * self-signed, and trust is instead established by the pairing handshake itself (the PIN
     * shown on the TV proves physical possession, and the SHA-256 hash ties the displayed code
     * to the exact certs exchanged in this session, defeating a MITM that doesn't also control
     * the TV's screen).
     */
    fun buildSslContext(): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, null)
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf(trustAll), SecureRandom())
        }
    }
}
