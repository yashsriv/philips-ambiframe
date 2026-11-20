package com.ambiframe.tv.art

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ArtInstituteProviderTest {
    private val server = MockWebServer()
    private val provider = ArtInstituteProvider(OkHttpClient(), baseUrl = server.url("/"), pageSize = 4)

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun fetchesAllPagesAndMapsFields() = runTest {
        server.enqueue(MockResponse().setBody(fixture("aic_search_page1.json")))
        server.enqueue(MockResponse().setBody(fixture("aic_search_page2.json")))

        val works = provider.fetch()

        assertEquals(2, server.requestCount)
        // 7 results, one without an image_id.
        assertEquals(6, works.size)
        val wheat = works.single { it.id == "aic:64818" }
        assertEquals("Stacks of Wheat (End of Summer)", wheat.title)
        assertEquals("Claude Monet", wheat.artist)
        assertEquals("1890–91", wheat.date)
        assertEquals("The Art Institute of Chicago, Gift of Arthur M. Wood, Sr. in memory of Pauline Palmer Wood", wheat.credit)
        assertEquals(6884, wheat.width)
        assertEquals(4068, wheat.height)
        assertEquals(
            "https://www.artic.edu/iiif/2/a38e2828-ec6f-ece1-a30f-70243449197b/full/1686,/0/default.jpg",
            wheat.imageUrl,
        )
        assertEquals(ArtSource.ART_INSTITUTE_OF_CHICAGO, wheat.source)
    }

    @Test
    fun sendsTheElasticsearchQuery() = runTest {
        server.enqueue(MockResponse().setBody(fixture("aic_search_page1.json").replace("\"total_pages\": 2", "\"total_pages\": 1")))

        provider.fetch()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/artworks/search", request.path)
        assertEquals(ArtInstituteProvider.AIC_USER_AGENT, request.getHeader("AIC-User-Agent"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val must = body["query"]!!.jsonObject["bool"]!!.jsonObject["must"]!!.jsonArray.toString()
        assertTrue(must, "\"is_public_domain\":true" in must)
        assertTrue(must, "{\"exists\":{\"field\":\"image_id\"}}" in must)
        assertTrue(must, "\"style_id\":[\"TM-7543\",\"TM-7547\"]" in must)
        assertEquals(
            ArtInstituteProvider.FIELDS,
            body["fields"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(1, body["page"]!!.jsonPrimitive.int)
    }

    @Test
    fun landscapeRuleKeepsOnlyWideWorks() = runTest {
        server.enqueue(MockResponse().setBody(fixture("aic_search_page1.json")))
        server.enqueue(MockResponse().setBody(fixture("aic_search_page2.json")))
        val gate = LandscapeGate(FakeProbe(emptyMap()), MemoryProbeCache())

        val kept = gate.filter(provider.fetch(), provider::probeUrl)

        assertEquals(setOf("aic:64818", "aic:25865", "aic:61603"), kept.map { it.id }.toSet())
    }

    @Test
    fun httpErrorThrows() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            provider.fetch()
            fail("expected IOException")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun artistNameDropsNationalityAndDates() {
        assertEquals("Vincent van Gogh", ArtInstituteProvider.artistName("Vincent van Gogh (Dutch, 1853–1890)"))
        assertEquals("Edgar Degas", ArtInstituteProvider.artistName("Edgar Degas\nFrench, 1834-1917"))
        assertEquals("", ArtInstituteProvider.artistName(null))
    }
}

class GoogleArtsCultureProviderTest {
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    private fun provider() = GoogleArtsCultureProvider(OkHttpClient(), feedUrl = server.url("/feed.json").toString())

    @Test
    fun parsesFeed() = runTest {
        server.enqueue(MockResponse().setBody(fixture("google_feed.json")))

        val works = provider().fetch()

        assertEquals(4, works.size)
        val poppies = works.first()
        assertEquals("gac:-wH1uoTAMs2EsQ", poppies.id)
        assertEquals("Poppies", poppies.title)
        assertEquals("Robert William Vonnoh", poppies.artist)
        assertEquals("Indianapolis Museum of Art at Newfields", poppies.credit)
        assertEquals("", poppies.date)
        assertEquals(null, poppies.width)
        assertEquals(
            "https://lh3.ggpht.com/ci/AL18g_RMlmMMrHYJXXNc7DABW7aQIZi_4XAh-Ogjw7LGlleqcEEK2chiFUDXQ3AoJ_2rfbpscMz5cA=w3840",
            poppies.imageUrl,
        )
        assertEquals("High Museum of Art", works[1].credit) // trailing space trimmed
        assertEquals(ArtSource.GOOGLE_ARTS_AND_CULTURE, poppies.source)
    }

    @Test
    fun probeUrlAsksForASmallRendition() {
        val work = artwork("gac:x", source = ArtSource.GOOGLE_ARTS_AND_CULTURE)
            .copy(imageUrl = "https://lh3.ggpht.com/ci/ABC=w3840")
        assertEquals("https://lh3.ggpht.com/ci/ABC=s256", provider().probeUrl(work))
    }

    @Test
    fun malformedFeedThrows() = runTest {
        server.enqueue(MockResponse().setBody("{\"not\":\"a list\"}"))
        try {
            provider().fetch()
            fail("expected IOException")
        } catch (expected: IOException) {
        }
    }

    @Test
    fun missingFeedThrows() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            provider().fetch()
            fail("expected IOException")
        } catch (expected: IOException) {
        }
    }

    /** Feed → probe → landscape rule, all over HTTP. */
    @Test
    fun probesImagesAndKeepsLandscapeOnes() = runTest {
        val base = server.url("/ci/").toString()
        val feed = """[
            {"title":"Wide","creator":"A","attribution":"M","image":"${base}wide","link":"asset/wide"},
            {"title":"Tall","creator":"B","attribution":"M","image":"${base}tall","link":"asset/tall"}
        ]"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/feed.json" -> MockResponse().setBody(feed)
                "/ci/wide=s256" -> MockResponse().setBody(jpegHeader(256, 144))
                "/ci/tall=s256" -> MockResponse().setBody(jpegHeader(171, 256))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val provider = provider()
        val cache = MemoryProbeCache()
        val gate = LandscapeGate(HttpImageBoundsProbe(OkHttpClient()), cache)

        val kept = gate.filter(provider.fetch(), provider::probeUrl)

        assertEquals(listOf("gac:wide"), kept.map { it.id })
        assertEquals(setOf("gac:tall"), cache.results.rejected)
    }
}
