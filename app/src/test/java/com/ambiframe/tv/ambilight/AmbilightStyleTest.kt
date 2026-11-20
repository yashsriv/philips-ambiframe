package com.ambiframe.tv.ambilight

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class AmbilightStyleTest {

    /** What a 65OLED707 reported in "Vivid" mode. */
    private val vivid = """{"styleName":"FOLLOW_VIDEO","isExpert":false,"menuSetting":"VIVID","stringValue":"Vivid"}"""

    private val server = MockWebServer()
    private lateinit var client: JointSpaceClient

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().commonName("restfultv.tpvision.com").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        client = JointSpaceClient(
            host = "127.0.0.1",
            credentials = JointSpaceCredentials("dev1234567890abc", "key"),
            httpsPort = server.port,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setBody(body)
    private fun ok() = MockResponse().setResponseCode(200)
    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject
    private fun RecordedRequest.line() = "$method $path"

    @Test
    fun switchOffCapturesStyleFirst() = runTest {
        server.enqueue(json("""{"power":"On"}"""))
        server.enqueue(json(vivid))
        server.enqueue(ok())

        val before = client.switchOffKeepingStyle()

        assertEquals("GET /6/ambilight/power", server.takeRequest().line())
        assertEquals("GET /6/ambilight/currentconfiguration", server.takeRequest().line())
        val off = server.takeRequest()
        assertEquals("POST /6/ambilight/power", off.line())
        assertEquals("Off", off.json()["power"]!!.jsonPrimitive.content)
        assertTrue(before.on)
        assertEquals("VIVID", before.configuration?.menuSetting)
        assertEquals("Vivid", before.configuration?.stringValue)
    }

    @Test
    fun switchOffWhenAlreadyOffOnlyReads() = runTest {
        server.enqueue(json("""{"power":"Off"}"""))

        val before = client.switchOffKeepingStyle()

        assertFalse(before.on)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun restorePostsSavedStyleInsteadOfPowerOn() = runTest {
        val saved = AmbilightState(true, JointSpaceJson.decodeFromString(AmbilightConfiguration.serializer(), vivid))
        server.enqueue(ok())
        server.enqueue(json("""{"power":"On"}"""))

        client.restoreAmbilight(saved)

        val post = server.takeRequest()
        assertEquals("POST /6/ambilight/currentconfiguration", post.line())
        val body = post.json()
        assertEquals("FOLLOW_VIDEO", body["styleName"]!!.jsonPrimitive.content)
        assertEquals("VIVID", body["menuSetting"]!!.jsonPrimitive.content)
        assertEquals("false", body["isExpert"]!!.jsonPrimitive.content)
        assertEquals("GET /6/ambilight/power", server.takeRequest().line())
        assertEquals("no power-on that would reset the style", 2, server.requestCount)
    }

    @Test
    fun restorePowersOnAndReappliesStyleIfStillOff() = runTest {
        val saved = AmbilightState(true, JointSpaceJson.decodeFromString(AmbilightConfiguration.serializer(), vivid))
        server.enqueue(ok())
        server.enqueue(json("""{"power":"Off"}"""))
        server.enqueue(ok())
        server.enqueue(ok())

        client.restoreAmbilight(saved)

        assertEquals("POST /6/ambilight/currentconfiguration", server.takeRequest().line())
        assertEquals("GET /6/ambilight/power", server.takeRequest().line())
        val on = server.takeRequest()
        assertEquals("POST /6/ambilight/power", on.line())
        assertEquals("On", on.json()["power"]!!.jsonPrimitive.content)
        assertEquals("POST /6/ambilight/currentconfiguration", server.takeRequest().line())
    }

    @Test
    fun restoreRetriesWithoutDisplayLabelIfRejected() = runTest {
        val saved = AmbilightState(true, JointSpaceJson.decodeFromString(AmbilightConfiguration.serializer(), vivid))
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(ok())
        server.enqueue(json("""{"power":"On"}"""))

        client.restoreAmbilight(saved)

        assertTrue(server.takeRequest().json().containsKey("stringValue"))
        val retry = server.takeRequest()
        assertEquals("POST /6/ambilight/currentconfiguration", retry.line())
        val body = retry.json()
        assertFalse(body.containsKey("stringValue"))
        assertEquals("VIVID", body["menuSetting"]!!.jsonPrimitive.content)
    }

    @Test
    fun restoreOfOffStateDoesNothing() = runTest {
        client.restoreAmbilight(AmbilightState(false, null))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun stateSurvivesSerialization() {
        val state = AmbilightState(true, JointSpaceJson.decodeFromString(AmbilightConfiguration.serializer(), vivid))
        val text = JointSpaceJson.encodeToString(AmbilightState.serializer(), state)
        assertEquals(state, JointSpaceJson.decodeFromString(AmbilightState.serializer(), text))
    }
}
