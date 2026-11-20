package com.ambiframe.tv.ambilight

import android.annotation.SuppressLint
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust-on-first-use for the TV's self-signed certificate on port 1926.
 *
 * The TV's certificate can't be validated against a CA, so instead we pin the SHA-256
 * fingerprint of its leaf certificate. If [pinnedFingerprint] is null (not yet paired), the
 * first certificate seen is accepted and pinned for the lifetime of this instance, so the
 * pair request and the grant are guaranteed to talk to the same TV. After pairing, the
 * fingerprint is persisted and passed back in, and any other certificate is rejected with
 * [CertificateMismatchException].
 *
 * One instance is created per [JointSpaceClient], which only ever talks to one host. This never
 * changes the platform's default trust.
 */
// Lint: a custom trust manager is the point here; it pins one fingerprint and never trusts all.
@SuppressLint("CustomX509TrustManager")
class PinnedCertificateTrustManager(pinnedFingerprint: String? = null) : X509TrustManager {

    private val pinned = AtomicReference(pinnedFingerprint?.let(::normalizeFingerprint))

    /** The pinned fingerprint: the one passed in, or the first one seen. */
    val fingerprint: String? get() = pinned.get()

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("TV sent no certificate")
        val actual = sha256Fingerprint(chain[0])
        if (pinned.compareAndSet(null, actual)) return
        val expected = pinned.get() ?: return
        if (expected != actual) throw CertificateMismatchException(expected, actual)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    fun sslSocketFactory(): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@PinnedCertificateTrustManager), null) }
            .socketFactory

    companion object {
        fun sha256Fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** Accepts `AB:CD:…` or `abcd…` and returns lowercase hex without separators. */
        fun normalizeFingerprint(value: String): String =
            value.filter { it.isLetterOrDigit() }.lowercase()
    }
}

class CertificateMismatchException(val expected: String, val actual: String) :
    CertificateException("TV certificate changed: expected $expected, got $actual")

/**
 * Hostname check for the pinned connection. The TV's self-signed certificate never names its
 * IP address, so the usual check would always fail. Identity is established by the fingerprint
 * pin instead; this only makes sure the connection is to the one host this client is for. It
 * accepts IP literals, including IPv6 in any textual form.
 */
class PinnedHostnameVerifier(private val expectedHost: String) : HostnameVerifier {
    override fun verify(hostname: String?, session: javax.net.ssl.SSLSession?): Boolean {
        if (hostname == null) return false
        return canonicalHost(hostname) == canonicalHost(expectedHost)
    }

    private fun canonicalHost(host: String): String {
        val bare = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        // Normalise IPv6 literals ("::1" vs "0:0:0:0:0:0:0:1") without DNS lookups.
        if (bare.contains(':')) {
            return runCatching { java.net.InetAddress.getByName(bare).hostAddress!!.lowercase() }
                .getOrDefault(bare)
        }
        return bare
    }
}
