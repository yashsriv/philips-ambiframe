package org.yashsriv.ambiframe.ambilight

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class JointSpaceClientTest {

    private val loopback = InetAddress.getByName("127.0.0.1")

    /** Self-signed, no SAN for 127.0.0.1: like the TV, it would fail normal hostname checks. */
    private val tvCertificate = HeldCertificate.Builder().commonName("Philips TV").build()
    private val tvFingerprint = PinnedCertificateTrustManager.sha256Fingerprint(tvCertificate.certificate)

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(tvCertificate).build()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start(loopback, 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(
        credentials: JointSpaceCredentials? = null,
        fingerprint: String? = null,
        logger: JointSpaceLogger = JointSpaceLogger.NONE,
    ) = JointSpaceClient(
        host = "127.0.0.1",
        secure = true,
        credentials = credentials,
        certificateFingerprint = fingerprint,
        httpsPort = server.port,
        httpPort = server.port,
        logger = logger,
    )

    @Test
    fun diagnosticsLogPairingWithoutSecrets() = runTest {
        server.enqueue(json(pairResponse))
        server.enqueue(digestChallenge())
        server.enqueue(json("""{"error_id":"INVALID_PIN"}""").setResponseCode(400))
        server.enqueue(json("""{"error_id":"SUCCESS"}"""))
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = client(logger = JointSpaceLogger { lines += it })

        client.pairRequest()
        client.pairGrant("9876")

        val log = lines.joinToString("\n")
        assertTrue(log, log.contains("/6/pair/request -> 200"))
        assertTrue(log, log.contains("timestamp=73120 (number)"))
        assertTrue(log, log.contains("""challenge=Digest realm="XTV""""))
        assertTrue(log, log.contains("-> 400"))
        assertTrue(log, log.contains("INVALID_PIN"))
        assertTrue(log, log.contains("variant suborb rejected"))
        assertTrue(log, log.contains("accepted signature variant suborb-wrapped"))
        assertTrue(log, log.contains("TLS TLSv1."))
        assertTrue(log, log.contains(tvFingerprint))
        assertFalse("auth_key leaked:\n$log", log.contains("secretKey123"))
        // Request bodies are never logged; fingerprints are random hex, so match the JSON key.
        assertFalse("PIN leaked:\n$log", log.contains("\"pin\"") || log.contains("pin=9876"))
        assertFalse("signature leaked:\n$log", log.contains(Signature.sign("73120", "9876")))
        assertFalse("Authorization header leaked:\n$log", log.contains("response="))
    }

    private fun digestChallenge() = MockResponse()
        .setResponseCode(401)
        .addHeader(
            "WWW-Authenticate",
            """Digest realm="XTV", qop="auth,auth-int", nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093", opaque="5ccc069c403ebaf9f0171e9517f40e41"""",
        )

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun RecordedRequest.jsonBody(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

    private val pairResponse =
        """{"error_id":"SUCCESS","error_text":"Authorization required","auth_key":"secretKey123","timestamp":73120,"timeout":60}"""

    @Test
    fun pairRequestSendsDeviceSpecAndPinsCertificate() = runTest {
        server.enqueue(json(pairResponse))
        val client = client()

        val session = client.pairRequest(deviceName = "Living room")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/6/pair/request", request.path)
        assertNull(request.getHeader("Authorization"))
        val body = request.jsonBody()
        assertEquals(listOf("read", "write", "control"), body["scope"]!!.jsonArray.map { it.jsonPrimitive.content })
        val device = body["device"]!!.jsonObject
        assertEquals("Living room", device["device_name"]!!.jsonPrimitive.content)
        assertEquals("Android", device["device_os"]!!.jsonPrimitive.content)
        assertEquals("native", device["type"]!!.jsonPrimitive.content)
        val id = device["id"]!!.jsonPrimitive.content
        assertTrue("device id '$id'", id.matches(Regex("[A-Za-z0-9]{16}")))

        assertEquals("secretKey123", session.authKey)
        assertEquals(73120L, session.timestamp.long)
        assertEquals(60, session.timeoutSeconds)
        assertEquals(id, session.device.id)
        assertEquals(session, client.pendingPairing)
        assertEquals(tvFingerprint, client.certificateFingerprint)
    }

    @Test
    fun pairRequestErrorIsReported() = runTest {
        server.enqueue(json("""{"error_id":"CONCURRENT_PAIRING","error_text":"Another pairing is in progress"}"""))
        try {
            client().pairRequest()
            fail("expected PairingRejected")
        } catch (e: JointSpaceException.PairingRejected) {
            assertEquals("CONCURRENT_PAIRING", e.errorId)
        }
    }

    @Test
    fun pairGrantAnswersDigestChallengeWithPairingCredentials() = runTest {
        server.enqueue(json(pairResponse))
        server.enqueue(digestChallenge())
        server.enqueue(json("""{"error_id":"SUCCESS","error_text":"Pairing completed"}"""))
        val client = client()
        val session = client.pairRequest()
        server.takeRequest()

        val result = client.pairGrant(" 1234 ")

        val challenged = server.takeRequest()
        assertEquals("/6/pair/grant", challenged.path)
        val answered = server.takeRequest()
        assertEquals("/6/pair/grant", answered.path)
        val authorization = answered.getHeader("Authorization")!!
        assertTrue(authorization, authorization.startsWith("Digest "))
        assertTrue(authorization, authorization.contains("""username="${session.device.id}""""))
        assertTrue(authorization, authorization.contains("""realm="XTV""""))
        assertTrue(authorization, authorization.contains("""uri="/6/pair/grant""""))

        val body = answered.jsonBody()
        val auth = body["auth"]!!.jsonObject
        assertEquals("1", auth["auth_AppId"]!!.jsonPrimitive.content)
        assertEquals("1234", auth["pin"]!!.jsonPrimitive.content)
        // Timestamp goes back exactly as the TV sent it: a number, not a string.
        assertFalse(auth["auth_timestamp"]!!.jsonPrimitive.isString)
        assertEquals(73120L, auth["auth_timestamp"]!!.jsonPrimitive.long)
        assertEquals(Signature.sign("73120", "1234"), auth["auth_signature"]!!.jsonPrimitive.content)
        assertEquals(session.device.id, body["device"]!!.jsonObject["id"]!!.jsonPrimitive.content)

        assertEquals(JointSpaceCredentials(session.device.id, "secretKey123"), result.credentials)
        assertEquals(tvFingerprint, result.certificateFingerprint)
        assertEquals("suborb", result.signatureVariant)
        assertNull(client.pendingPairing)
        assertEquals(result.credentials, client.credentials)
    }

    @Test
    fun pairGrantFallsBackToNextSignatureVariant() = runTest {
        server.enqueue(json(pairResponse))
        server.enqueue(digestChallenge())
        server.enqueue(json("""{"error_id":"INVALID_PIN","error_text":"Invalid pin"}""").setResponseCode(400))
        server.enqueue(json("""{"error_id":"SUCCESS"}"""))
        val client = client()
        client.pairRequest()
        server.takeRequest()

        val result = client.pairGrant("1234")

        server.takeRequest() // challenge
        server.takeRequest() // suborb, rejected
        val second = server.takeRequest()
        assertNotNull(second.getHeader("Authorization"))
        val signature = second.jsonBody()["auth"]!!.jsonObject["auth_signature"]!!.jsonPrimitive.content
        assertEquals("b'${Signature.sign("73120", "1234")}'", signature)
        assertEquals("suborb-wrapped", result.signatureVariant)
    }

    @Test
    fun wrongPinIsReportedAfterAllVariants() = runTest {
        server.enqueue(json(pairResponse))
        server.enqueue(digestChallenge())
        repeat(Signature.variants.size) {
            server.enqueue(json("""{"error_id":"INVALID_PIN","error_text":"Invalid pin"}"""))
        }
        val client = client()
        client.pairRequest()
        try {
            client.pairGrant("0000")
            fail("expected PairingRejected")
        } catch (e: JointSpaceException.PairingRejected) {
            assertEquals("INVALID_PIN", e.errorId)
        }
        assertNull(client.credentials)
        assertEquals(2 + Signature.variants.size, server.requestCount)
    }

    @Test
    fun pairGrantWithoutRequestFails() = runTest {
        try {
            client().pairGrant("1234")
            fail("expected NoPairingInProgress")
        } catch (_: JointSpaceException.NoPairingInProgress) {
        }
    }

    @Test
    fun ambilightPowerUsesDigestAuth() = runTest {
        server.enqueue(digestChallenge())
        server.enqueue(json("""{"power":"On"}"""))
        server.enqueue(MockResponse().setResponseCode(200))
        val client = client(JointSpaceCredentials("dev1234567890abc", "key"), tvFingerprint)

        assertTrue(client.getAmbilightPower())
        client.setAmbilightPower(false)

        assertEquals("/6/ambilight/power", server.takeRequest().path)
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertTrue(get.getHeader("Authorization")!!.contains("""username="dev1234567890abc""""))
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/6/ambilight/power", post.path)
        // Auth cache answers preemptively, so no second challenge round-trip.
        assertNotNull(post.getHeader("Authorization"))
        assertEquals("Off", post.jsonBody()["power"]!!.jsonPrimitive.content)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun configurationRoundTripsAllFields() = runTest {
        server.enqueue(digestChallenge())
        server.enqueue(
            json(
                """{"styleName":"FOLLOW_VIDEO","isExpert":false,"menuSetting":"NATURAL","algorithm":"MANUAL_HUE",
                   |"colorSettings":{"color":{"hue":10,"saturation":20,"brightness":30}},"someNewField":1}""".trimMargin(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200))
        val client = client(JointSpaceCredentials("dev1234567890abc", "key"), tvFingerprint)

        val configuration = client.getConfiguration()
        assertEquals("FOLLOW_VIDEO", configuration.styleName)
        assertEquals(false, configuration.isExpert)
        assertEquals("NATURAL", configuration.menuSetting)
        client.setConfiguration(configuration)

        server.takeRequest()
        assertEquals("/6/ambilight/currentconfiguration", server.takeRequest().path)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/6/ambilight/currentconfiguration", post.path)
        val sent = post.jsonBody()
        assertEquals("FOLLOW_VIDEO", sent["styleName"]!!.jsonPrimitive.content)
        assertFalse(sent["isExpert"]!!.jsonPrimitive.boolean)
        assertEquals("NATURAL", sent["menuSetting"]!!.jsonPrimitive.content)
        assertEquals("MANUAL_HUE", sent["algorithm"]!!.jsonPrimitive.content)
        assertNotNull(sent["colorSettings"]!!.jsonObject["color"])
        assertFalse("nulls are omitted", sent.containsKey("stringValue"))
    }

    @Test
    fun wrongCredentialsAreUnauthorized() = runTest {
        server.enqueue(digestChallenge())
        server.enqueue(digestChallenge())
        server.enqueue(digestChallenge())
        val client = client(JointSpaceCredentials("dev1234567890abc", "wrong"), tvFingerprint)
        try {
            client.getAmbilightPower()
            fail("expected Unauthorized")
        } catch (_: JointSpaceException.Unauthorized) {
        }
    }

    @Test
    fun changedCertificateIsRejected() = runTest {
        server.enqueue(json("""{"power":"On"}"""))
        val client = client(JointSpaceCredentials("dev1234567890abc", "key"), "AA:BB:" + "00".repeat(30))
        try {
            client.getAmbilightPower()
            fail("expected CertificateChanged")
        } catch (e: JointSpaceException.CertificateChanged) {
            assertEquals(tvFingerprint, e.actual)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun pinnedFingerprintIsAcceptedInColonForm() = runTest {
        server.enqueue(json("""{"power":"Off"}"""))
        val colonForm = tvFingerprint.uppercase().chunked(2).joinToString(":")
        val client = client(JointSpaceCredentials("dev1234567890abc", "key"), colonForm)
        assertFalse(client.getAmbilightPower())
    }

    @Test
    fun unreachableHostIsReported() = runTest {
        val freePort = ServerSocket(0, 1, loopback).use { it.localPort }
        val client = JointSpaceClient(host = "127.0.0.1", httpPort = freePort, httpsPort = freePort)
        try {
            client.system()
            fail("expected Unreachable")
        } catch (e: JointSpaceException.Unreachable) {
            assertEquals("127.0.0.1", e.host)
        }
    }
}

class JointSpaceSystemTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val systemJson = """{"menulanguage":"English","name":"65OLED806/12","country":"Germany",
        |"api_version":{"Major":6,"Minor":4,"Patch":0},"os_type":"MSAF_2019_P",
        |"featuring":{"systemfeatures":{"tvtype":"consumer","pairing_type":"%s","secured_transport":"true"}}}""".trimMargin()

    @Test
    fun systemOverPlainHttpSelectsHttpsWhenDigestPairing() = runTest {
        server.enqueue(MockResponse().setBody(systemJson.format("digest_auth_pairing")))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val client = JointSpaceClient("127.0.0.1", secure = false, httpPort = server.port)

        val info = client.system()

        assertEquals("/6/system", server.takeRequest().path)
        assertEquals("65OLED806/12", info.name)
        assertEquals(6, info.apiVersion?.major)
        assertTrue(info.requiresDigestPairing)
        assertTrue(client.secure)
    }

    /** Reproduces a 65OLED707 (API 6.1): 1925 answers 200 with an empty body, 1926 has the data. */
    @Test
    fun emptyReplyOnPlainPortFallsBackToHttps() = runTest {
        val certificate = HeldCertificate.Builder().commonName("restfultv.tpvision.com").build()
        val https = MockWebServer()
        https.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        https.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json; charset=UTF-8")
                    .setHeader("Content-Length", "0"),
            )
            https.enqueue(MockResponse().setBody(systemJson.format("digest_auth_pairing")))
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val client = JointSpaceClient("127.0.0.1", secure = false, httpPort = server.port, httpsPort = https.port)

            val info = client.system()

            assertEquals("/6/system", server.takeRequest().path)
            assertEquals("/6/system", https.takeRequest().path)
            assertEquals("65OLED806/12", info.name)
            assertTrue(client.secure)
            assertEquals(PinnedCertificateTrustManager.sha256Fingerprint(certificate.certificate), client.certificateFingerprint)
        } finally {
            https.shutdown()
        }
    }

    @Test
    fun emptyReplyAndNoHttpsReportsTheHttpsFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Length", "0"))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val client = JointSpaceClient("127.0.0.1", secure = false, httpPort = server.port, httpsPort = closedPort)
        try {
            client.system()
            fail("expected a JointSpaceException")
        } catch (_: JointSpaceException.Unreachable) {
        }
    }

    @Test
    fun systemWithoutDigestPairingStaysOnHttp() = runTest {
        server.enqueue(MockResponse().setBody(systemJson.format("none")))
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val client = JointSpaceClient("127.0.0.1", secure = true, httpPort = server.port)

        assertFalse(client.system().requiresDigestPairing)
        assertFalse(client.secure)
    }
}

class PinnedHostnameVerifierTest {
    @Test
    fun acceptsOnlyTheConfiguredHost() {
        val verifier = PinnedHostnameVerifier("192.168.1.20")
        assertTrue(verifier.verify("192.168.1.20", null))
        assertFalse(verifier.verify("192.168.1.21", null))
        assertFalse(verifier.verify("example.com", null))
        assertFalse(verifier.verify(null, null))
    }

    @Test
    fun normalisesIpv6Literals() {
        val verifier = PinnedHostnameVerifier("::1")
        assertTrue(verifier.verify("0:0:0:0:0:0:0:1", null))
        assertTrue(verifier.verify("[::1]", null))
    }
}
