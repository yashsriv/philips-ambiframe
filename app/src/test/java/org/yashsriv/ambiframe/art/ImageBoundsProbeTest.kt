package org.yashsriv.ambiframe.art

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageHeaderParserTest {
    @Test
    fun png() = assertEquals(ImageSize(1686, 1000), ImageHeaderParser.parse(pngHeader(1686, 1000)))

    @Test
    fun jpegBaselineAfterOtherSegments() =
        assertEquals(ImageSize(2871, 2081), ImageHeaderParser.parse(jpegHeader(2871, 2081)))

    @Test
    fun jpegProgressive() =
        assertEquals(ImageSize(1686, 999), ImageHeaderParser.parse(jpegHeader(1686, 999, sofMarker = 0xC2)))

    @Test
    fun gif() {
        val gif = Buffer().writeUtf8("GIF89a").writeShortLe(640).writeShortLe(360).write(ByteArray(8))
        assertEquals(ImageSize(640, 360), ImageHeaderParser.parse(gif))
    }

    @Test
    fun webpLossy() {
        val webp = Buffer().writeUtf8("RIFF").writeIntLe(0).writeUtf8("WEBPVP8 ").writeIntLe(0)
            .write(byteArrayOf(0, 0, 0, 0x9D.toByte(), 0x01, 0x2A)).writeShortLe(1920).writeShortLe(1080)
        assertEquals(ImageSize(1920, 1080), ImageHeaderParser.parse(webp))
    }

    @Test
    fun webpExtended() {
        val webp = Buffer().writeUtf8("RIFF").writeIntLe(0).writeUtf8("WEBPVP8X").writeIntLe(10).writeIntLe(0)
            .write(byteArrayOf((1919 and 0xFF).toByte(), (1919 shr 8).toByte(), 0))
            .write(byteArrayOf((1079 and 0xFF).toByte(), (1079 shr 8).toByte(), 0))
        assertEquals(ImageSize(1920, 1080), ImageHeaderParser.parse(webp))
    }

    @Test
    fun garbageAndTruncatedInput() {
        assertNull(ImageHeaderParser.parse(Buffer().writeUtf8("<html>not an image</html>")))
        assertNull(ImageHeaderParser.parse(Buffer().write(jpegHeader(10, 10).readByteArray(30))))
    }
}

class HttpImageBoundsProbeTest {
    private val server = MockWebServer()
    private val probe = HttpImageBoundsProbe(OkHttpClient())

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun readsSizeOverHttp() = runTest {
        server.enqueue(MockResponse().setBody(jpegHeader(1800, 1000)))
        assertEquals(ImageSize(1800, 1000), probe.probe(server.url("/img.jpg").toString()))
    }

    @Test
    fun httpErrorGivesNull() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(probe.probe(server.url("/missing.jpg").toString()))
    }
}
