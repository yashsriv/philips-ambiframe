package com.ambiframe.tv.ambilight

import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Authenticator
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Digest credentials issued by the TV at pairing: username = device id, password = auth key. */
data class JointSpaceCredentials(val deviceId: String, val authKey: String) {
    override fun toString(): String = "JointSpaceCredentials(deviceId=$deviceId, authKey=***)"
}

/** State between `pair/request` and `pair/grant`. */
data class PairingSession(
    val device: DeviceSpec,
    val authKey: String,
    val timestamp: JsonPrimitive,
    /** Seconds the TV shows the PIN for, if it said. */
    val timeoutSeconds: Int?,
)

/** What a successful grant yields; everything needed to talk to the TV later. */
data class PairingResult(
    val credentials: JointSpaceCredentials,
    /** SHA-256 of the TV's certificate, to pin from now on. Null on plain-HTTP TVs. */
    val certificateFingerprint: String?,
    val signatureVariant: String,
)

/**
 * Client for the Philips JointSpace API (v6), limited to what the screensaver needs: discovery,
 * pairing and Ambilight power/style.
 *
 * Each instance talks to exactly one [host]. On TVs that use digest pairing, calls go to
 * `https://host:1926` with Digest auth and the TV's self-signed certificate is pinned with
 * [PinnedCertificateTrustManager] (trust on first use when [certificateFingerprint] is null).
 *
 * @param secure initial transport; [system] corrects it from what the TV reports.
 */
class JointSpaceClient(
    val host: String,
    secure: Boolean = true,
    credentials: JointSpaceCredentials? = null,
    certificateFingerprint: String? = null,
    private val httpPort: Int = DEFAULT_HTTP_PORT,
    private val httpsPort: Int = DEFAULT_HTTPS_PORT,
    baseClient: OkHttpClient = sharedBaseClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** True for HTTPS on [httpsPort] with Digest auth, false for plain HTTP on [httpPort]. */
    @Volatile
    var secure: Boolean = secure
        private set

    @Volatile
    var credentials: JointSpaceCredentials? = credentials
        private set

    /** The last pair request, consumed by [pairGrant]. */
    @Volatile
    var pendingPairing: PairingSession? = null
        private set

    private val trustManager = PinnedCertificateTrustManager(certificateFingerprint)

    /** The pinned certificate fingerprint, known after the first HTTPS call. */
    val certificateFingerprint: String? get() = trustManager.fingerprint

    private val authCache = ConcurrentHashMap<String, CachingAuthenticator>()

    private val http: OkHttpClient = baseClient.newBuilder()
        .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
        .hostnameVerifier(PinnedHostnameVerifier(host))
        // Older Philips firmware only offers TLS 1.2 ciphers outside OkHttp's MODERN set.
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        .authenticator(digestAuthenticator())
        .addInterceptor(AuthenticationCacheInterceptor(authCache))
        .build()

    /**
     * `GET http://host:1925/6/system`, falling back to `https://host:1926/6/system` for
     * firmwares that closed 1925. Sets [secure] from the TV's `pairing_type`.
     */
    suspend fun system(): SystemInfo {
        val (info, viaHttps) = try {
            getJson<SystemInfo>(url(secure = false, path = "system"), authenticated = false) to false
        } catch (plain: JointSpaceException.Unreachable) {
            try {
                getJson<SystemInfo>(url(secure = true, path = "system"), authenticated = false) to true
            } catch (_: JointSpaceException) {
                throw plain
            }
        }
        secure = viaHttps || info.requiresDigestPairing
        return info
    }

    /**
     * `POST /6/pair/request`. On success the TV shows a PIN, and the returned session (also kept
     * in [pendingPairing]) is what [pairGrant] needs.
     */
    suspend fun pairRequest(deviceName: String = DEFAULT_DEVICE_NAME): PairingSession {
        val device = DeviceSpec(deviceName = deviceName, id = randomDeviceId())
        val text = post(url(path = "pair/request"), PairRequestBody(device = device).encode(), authenticated = false)
        val response = decode<PairRequestResponse>(text)
        val authKey = response.authKey
        val timestamp = response.timestamp
        if (response.errorId != SUCCESS || authKey.isNullOrEmpty() || timestamp == null) {
            throw JointSpaceException.PairingRejected(response.errorId, response.errorText)
        }
        return PairingSession(device, authKey, timestamp, response.timeout).also { pendingPairing = it }
    }

    /**
     * `POST /6/pair/grant` with the [pin] shown on screen. Tries each [Signature.variants] in turn
     * because firmwares differ in which one they accept. On success, [credentials] are set on
     * this client and returned along with the certificate fingerprint to persist.
     */
    suspend fun pairGrant(pin: String, session: PairingSession? = pendingPairing): PairingResult {
        if (session == null) throw JointSpaceException.NoPairingInProgress()
        val trimmedPin = pin.trim()
        setCredentials(JointSpaceCredentials(session.device.id, session.authKey))
        var lastFailure: JointSpaceException? = null
        for (variant in Signature.variants) {
            val body = PairGrantBody(
                auth = GrantAuth(
                    pin = trimmedPin,
                    authTimestamp = session.timestamp,
                    authSignature = Signature.sign(session.timestamp.content, trimmedPin, variant),
                ),
                device = session.device,
            )
            try {
                val text = post(url(path = "pair/grant"), body.encode(), authenticated = true)
                val errorId = decodeErrorId(text)
                if (errorId == null || errorId == SUCCESS) {
                    pendingPairing = null
                    return PairingResult(credentials!!, certificateFingerprint, variant.name)
                }
                lastFailure = JointSpaceException.PairingRejected(errorId, decodeOrNull<ErrorResponse>(text)?.errorText)
            } catch (e: JointSpaceException.HttpError) {
                val error = decodeOrNull<ErrorResponse>(e.body.orEmpty())
                lastFailure = if (error?.errorId != null) {
                    JointSpaceException.PairingRejected(error.errorId, error.errorText)
                } else {
                    e
                }
            } catch (e: JointSpaceException.Unauthorized) {
                lastFailure = e
            }
        }
        setCredentials(null)
        throw lastFailure ?: JointSpaceException.BadResponse("Grant failed")
    }

    /** `GET /6/ambilight/power`: true when Ambilight is on. */
    suspend fun getAmbilightPower(): Boolean =
        getJson<AmbilightPowerBody>(url(path = "ambilight/power")).power.equals(POWER_ON, ignoreCase = true)

    /** `POST /6/ambilight/power`. */
    suspend fun setAmbilightPower(on: Boolean) {
        post(url(path = "ambilight/power"), AmbilightPowerBody(if (on) POWER_ON else POWER_OFF).encode())
    }

    /** `GET /6/ambilight/currentconfiguration`: the active style, to restore later. */
    suspend fun getConfiguration(): AmbilightConfiguration =
        getJson(url(path = "ambilight/currentconfiguration"))

    /** `POST /6/ambilight/currentconfiguration`: re-applies a style saved with [getConfiguration]. */
    suspend fun setConfiguration(configuration: AmbilightConfiguration) {
        post(url(path = "ambilight/currentconfiguration"), JointSpaceJson.encodeToString(AmbilightConfiguration.serializer(), configuration))
    }

    // --- plumbing ---------------------------------------------------------------------------

    private fun setCredentials(value: JointSpaceCredentials?) {
        credentials = value
        authCache.clear()
    }

    private fun url(path: String, secure: Boolean = this.secure): HttpUrl =
        HttpUrl.Builder()
            .scheme(if (secure) "https" else "http")
            .host(host)
            .port(if (secure) httpsPort else httpPort)
            .addPathSegment(API_VERSION)
            .addPathSegments(path)
            .build()

    private suspend inline fun <reified T> getJson(url: HttpUrl, authenticated: Boolean = true): T =
        decode(execute(Request.Builder().url(url).get().build(), authenticated))

    private suspend fun post(url: HttpUrl, json: String, authenticated: Boolean = true): String =
        execute(Request.Builder().url(url).post(json.toRequestBody(JSON_MEDIA_TYPE)).build(), authenticated)

    private suspend fun execute(request: Request, authenticated: Boolean): String = withContext(ioDispatcher) {
        val tagged = request.newBuilder().tag(AuthMode::class.java, if (authenticated) AuthMode.DIGEST else AuthMode.NONE).build()
        try {
            http.newCall(tagged).execute().use { response -> readBody(response) }
        } catch (e: JointSpaceException) {
            throw e
        } catch (e: SSLException) {
            val mismatch = e.causes().filterIsInstance<CertificateMismatchException>().firstOrNull()
            if (mismatch != null) {
                throw JointSpaceException.CertificateChanged(mismatch.expected, mismatch.actual, e)
            }
            throw JointSpaceException.Unreachable(host, e)
        } catch (e: IOException) {
            throw JointSpaceException.Unreachable(host, e)
        }
    }

    private fun readBody(response: Response): String {
        val body = response.body?.string().orEmpty()
        return when {
            response.isSuccessful -> body
            response.code == 401 -> throw JointSpaceException.Unauthorized()
            else -> throw JointSpaceException.HttpError(response.code, body)
        }
    }

    private fun digestAuthenticator(): Authenticator = Authenticator { route, response ->
        val creds = credentials
        val wantsAuth = response.request.tag(AuthMode::class.java) == AuthMode.DIGEST
        if (creds == null || !wantsAuth || response.priorCount() >= MAX_AUTH_ATTEMPTS) {
            return@Authenticator null
        }
        val digest = DigestAuthenticator(Credentials(creds.deviceId, creds.authKey))
        CachingAuthenticatorDecorator(digest, authCache).authenticate(route, response)
    }

    private enum class AuthMode { NONE, DIGEST }

    companion object {
        const val DEFAULT_HTTP_PORT = 1925
        const val DEFAULT_HTTPS_PORT = 1926
        const val DEFAULT_DEVICE_NAME = "AmbiFrame"
        private const val API_VERSION = "6"
        private const val SUCCESS = "SUCCESS"
        private const val POWER_ON = "On"
        private const val POWER_OFF = "Off"
        private const val MAX_AUTH_ATTEMPTS = 2
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DEVICE_ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val random = SecureRandom()

        /** Shared connection pool and dispatcher; each client derives its own TLS settings. */
        val sharedBaseClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .writeTimeout(6, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        /** A 16-character `[A-Za-z0-9]` id, as the reference implementations use. */
        fun randomDeviceId(): String =
            (1..16).map { DEVICE_ID_ALPHABET[random.nextInt(DEVICE_ID_ALPHABET.length)] }.joinToString("")
    }
}

private inline fun <reified T> T.encode(): String where T : Any =
    JointSpaceJson.encodeToString(kotlinx.serialization.serializer<T>(), this)

private inline fun <reified T> decode(text: String): T = try {
    JointSpaceJson.decodeFromString(kotlinx.serialization.serializer<T>(), text)
} catch (e: SerializationException) {
    throw JointSpaceException.BadResponse("Unexpected response from the TV", e)
} catch (e: IllegalArgumentException) {
    throw JointSpaceException.BadResponse("Unexpected response from the TV", e)
}

private inline fun <reified T> decodeOrNull(text: String): T? =
    if (text.isBlank()) null else runCatching { decode<T>(text) }.getOrNull()

/** The grant answers with an empty body or `{"error_id":"SUCCESS",…}` depending on firmware. */
private fun decodeErrorId(text: String): String? = decodeOrNull<ErrorResponse>(text)?.errorId

private fun Response.priorCount(): Int {
    var count = 0
    var prior = priorResponse
    while (prior != null) {
        count++
        prior = prior.priorResponse
    }
    return count
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }
