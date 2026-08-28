package com.arflix.tv.data.repository.tvremote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Guards the hand-rolled DER encoder in [SelfSignedCert]. It exists to avoid a ~9MB BouncyCastle
 * dependency, but hand-rolled ASN.1 is exactly the kind of thing that silently produces a cert
 * that *looks* fine and gets rejected on the wire, so pin the properties the pairing handshake
 * actually depends on.
 */
class SelfSignedCertTest {

    private fun newKeyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun `generates a certificate that parses and self-verifies`() {
        val kp = newKeyPair()
        val cert = SelfSignedCert.generate("Xadarr", kp, 3650)

        assertEquals("CN=Xadarr", cert.subjectX500Principal.name)
        assertEquals("CN=Xadarr", cert.issuerX500Principal.name)
        assertEquals("SHA256withRSA", cert.sigAlgName)
        cert.checkValidity()
        // Throws if the signature we encoded doesn't verify against the embedded public key.
        cert.verify(kp.public)
    }

    @Test
    fun `encoded form round-trips through CertificateFactory unchanged`() {
        val kp = newKeyPair()
        val cert = SelfSignedCert.generate("Xadarr", kp, 3650)

        val reparsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(cert.encoded)) as X509Certificate

        assertArrayEquals(cert.encoded, reparsed.encoded)
    }

    @Test
    fun `public modulus and exponent survive encoding`() {
        // The pairing hash is computed over these exact values on both ends — if the DER encoding
        // mangled the SubjectPublicKeyInfo, pairing would fail with a confusing code mismatch.
        val kp = newKeyPair()
        val cert = SelfSignedCert.generate("Xadarr", kp, 3650)

        val fromCert = cert.publicKey as RSAPublicKey
        val original = kp.public as RSAPublicKey
        assertEquals(original.modulus, fromCert.modulus)
        assertEquals(original.publicExponent, fromCert.publicExponent)
    }

    @Test
    fun `private key round-trips through PKCS8 persistence`() {
        // How the identity is persisted between app launches; a pairing is bound to this key, so
        // a restore that produced a different usable key would silently break every pairing.
        val kp = newKeyPair()
        val cert = SelfSignedCert.generate("Xadarr", kp, 3650)

        val restored = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(kp.private.encoded))

        val payload = "pairing".toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(restored); update(payload); sign()
        }
        val verified = Signature.getInstance("SHA256withRSA").run {
            initVerify(cert.publicKey); update(payload); verify(sig)
        }
        assertTrue(verified)
    }

    @Test
    fun `handles a long common name crossing the DER multi-byte length boundary`() {
        // Names under 128 bytes encode their length in one byte; longer ones need the 0x80|n form.
        // Getting that wrong is the classic hand-rolled-ASN1 bug, and it only shows up past 127.
        val longName = "X".repeat(200)
        val kp = newKeyPair()
        val cert = SelfSignedCert.generate(longName, kp, 3650)

        assertEquals("CN=$longName", cert.subjectX500Principal.name)
        cert.verify(kp.public)
    }
}
