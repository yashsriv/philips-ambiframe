package com.ambiframe.tv.art

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class ArtPlaylistTest {
    private val works = (1..5).map { artwork("w$it") }

    @Test
    fun everyWorkOncePerPassAndNoBackToBackRepeats() {
        val playlist = ArtPlaylist(works, Random(42))
        val played = List(5 * 40) { playlist.next()!!.id }

        played.chunked(5).forEach { pass -> assertEquals(works.map { it.id }.toSet(), pass.toSet()) }
        played.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test
    fun passesAreShuffledDifferently() {
        val playlist = ArtPlaylist(works, Random(7))
        val passes = List(10) { List(5) { playlist.next()!!.id } }
        assertTrue(passes.toSet().size > 1)
    }

    @Test
    fun peekMatchesNextAcrossPassBoundaries() {
        val playlist = ArtPlaylist(works, Random(1))
        repeat(23) {
            val peeked = playlist.peek()
            assertEquals(peeked, playlist.next())
        }
    }

    @Test
    fun replaceAvoidsTheCurrentWorkFirst() {
        val playlist = ArtPlaylist(works, Random(3))
        repeat(50) {
            val current = playlist.next()!!
            playlist.replace(works, current)
            assertNotEquals(current.id, playlist.peek()!!.id)
        }
    }

    @Test
    fun emptyAndSingle() {
        assertNull(ArtPlaylist(emptyList()).next())
        val single = ArtPlaylist(listOf(works[0]))
        assertEquals("w1", single.next()!!.id)
        assertEquals("w1", single.next()!!.id)
    }
}

class ArtRepositoryTest {
    private val aicWorks = listOf(artwork("aic:1"), artwork("aic:2"), artwork("aic:tall", width = 1000, height = 1500))
    private val aic = FakeProvider(ArtSource.ART_INSTITUTE_OF_CHICAGO) { aicWorks }
    private val gac = FakeProvider(ArtSource.GOOGLE_ARTS_AND_CULTURE) {
        listOf(artwork("gac:1", source = ArtSource.GOOGLE_ARTS_AND_CULTURE))
    }
    private val catalog = MemoryCatalog()
    private val images = FakeImageCache()
    private val gate = LandscapeGate(FakeProbe(emptyMap()), MemoryProbeCache())

    private fun repository(enabled: Set<ArtSource>, aicProvider: FakeProvider = aic, gacProvider: FakeProvider = gac) =
        ArtRepository(
            providers = listOf(aicProvider, gacProvider),
            defaultProvider = aicProvider,
            gate = gate,
            catalog = catalog,
            images = images,
            enabledSources = { enabled },
        )

    @Test
    fun defaultIsArtInstituteOnlyAndAppliesTheLandscapeRule() = runTest {
        val repo = repository(setOf(ArtSource.ART_INSTITUTE_OF_CHICAGO))
        assertEquals(listOf("aic:1", "aic:2"), repo.refresh().map { it.id })
        assertEquals(0, gac.calls)
        assertEquals("good results are saved with attribution", listOf("aic:1", "aic:2"), catalog.works.map { it.id })
        assertEquals("Museum aic:1", catalog.works.first().credit)
        assertFalse(repo.isOffline)
    }

    @Test
    fun googleOnlyWhenItWorks() = runTest {
        val repo = repository(setOf(ArtSource.GOOGLE_ARTS_AND_CULTURE))
        assertEquals(listOf("gac:1"), repo.refresh().map { it.id })
        assertEquals(0, aic.calls)
    }

    @Test
    fun googleFailureFallsBackToArtInstitute() = runTest {
        val repo = repository(
            setOf(ArtSource.GOOGLE_ARTS_AND_CULTURE),
            gacProvider = FakeProvider.failing(ArtSource.GOOGLE_ARTS_AND_CULTURE),
        )
        assertEquals(listOf("aic:1", "aic:2"), repo.refresh().map { it.id })
        assertFalse(repo.isOffline)
    }

    @Test
    fun emptyGoogleFeedFallsBackToArtInstitute() = runTest {
        val repo = repository(
            setOf(ArtSource.GOOGLE_ARTS_AND_CULTURE),
            gacProvider = FakeProvider(ArtSource.GOOGLE_ARTS_AND_CULTURE) { emptyList() },
        )
        assertEquals(listOf("aic:1", "aic:2"), repo.refresh().map { it.id })
    }

    @Test
    fun bothSourcesAreMerged() = runTest {
        val repo = repository(setOf(ArtSource.ART_INSTITUTE_OF_CHICAGO, ArtSource.GOOGLE_ARTS_AND_CULTURE))
        assertEquals(setOf("aic:1", "aic:2", "gac:1"), repo.refresh().map { it.id }.toSet())
    }

    @Test
    fun offlineUsesSavedCatalogLimitedToCachedImages() = runTest {
        catalog.works = listOf(artwork("aic:old1"), artwork("aic:old2"))
        images.cached += artwork("aic:old2").imageUrl
        val repo = repository(
            setOf(ArtSource.GOOGLE_ARTS_AND_CULTURE),
            aicProvider = FakeProvider.failing(ArtSource.ART_INSTITUTE_OF_CHICAGO),
            gacProvider = FakeProvider.failing(ArtSource.GOOGLE_ARTS_AND_CULTURE),
        )

        assertEquals(listOf("aic:old2"), repo.refresh().map { it.id })
        assertTrue(repo.isOffline)
        assertEquals("the saved catalog is kept", 2, catalog.works.size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ArtSlideshowTest {
    private val works = (1..4).map { artwork("aic:$it") }
    private val provider = FakeProvider(ArtSource.ART_INSTITUTE_OF_CHICAGO) { works }
    private val catalog = MemoryCatalog()

    private fun repository(p: FakeProvider = provider, images: ImageCache) = ArtRepository(
        providers = listOf(p),
        defaultProvider = p,
        gate = LandscapeGate(FakeProbe(emptyMap()), MemoryProbeCache()),
        catalog = catalog,
        images = images,
    )

    @Test
    fun returnsReadyWorkAndPrefetchesTheNextOne() = runTest {
        val images = FakeImageCache()
        val slideshow = ArtSlideshow(repository(images = images), images, this, Random(5))

        val first = slideshow.next()!!
        assertTrue(images.isCached(first.imageUrl))
        advanceUntilIdle()

        assertEquals(2, images.prefetched.size)
        val second = slideshow.next()!!
        assertEquals("the prefetched work is shown next", images.prefetched[1], second.imageUrl)
        assertEquals(1, provider.calls)
    }

    @Test
    fun offlineRotatesThroughCachedWorksOnly() = runTest {
        val cachedUrls = setOf(works[0].imageUrl, works[2].imageUrl)
        val images = FakeImageCache(cached = cachedUrls, online = false)
        catalog.works = works
        val offlineProvider = FakeProvider.failing(ArtSource.ART_INSTITUTE_OF_CHICAGO)
        val slideshow = ArtSlideshow(repository(offlineProvider, images), images, this, Random(9))

        val shown = List(12) { slideshow.next()!!.imageUrl }

        assertEquals(cachedUrls, shown.toSet())
    }

    @Test
    fun offlineKeepsTheInMemoryListAndRecoversWhenOnline() = runTest {
        val images = FakeImageCache(cached = setOf(works[0].imageUrl))
        var now = 0L
        val slideshow = ArtSlideshow(
            repository(images = images), images, this, Random(2), clock = { now },
        )
        slideshow.next() // Online: list loaded, works downloaded as they come up.
        advanceUntilIdle()

        images.online = false
        provider.result = { throw java.io.IOException("offline") }
        now += 13 * 3_600_000L // Past the refresh interval: refresh fails in the background.
        repeat(6) { slideshow.next(); advanceUntilIdle() }
        assertEquals("rotation keeps the list it had", 4, slideshow.works().size)

        images.online = true
        val uncached = works.map { it.imageUrl }.filterNot { images.isCached(it) }
        repeat(8) { slideshow.next(); advanceUntilIdle() }
        assertTrue("downloads resume once online", uncached.all { images.isCached(it) })
    }

    @Test
    fun nothingAvailableGivesNull() = runTest {
        val images = FakeImageCache(online = false)
        val slideshow = ArtSlideshow(
            repository(FakeProvider.failing(ArtSource.ART_INSTITUTE_OF_CHICAGO), images), images, this,
        )
        assertNull(slideshow.next())
    }
}

class FileStoresTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun catalogRoundTripsWithAttribution() = runTest {
        val store = FileCatalogStore(tmp.root.resolve("art/catalog.json"))
        assertEquals(emptyList<Artwork>(), store.load())

        val works = listOf(artwork("aic:1"), artwork("gac:2", width = null, height = null, source = ArtSource.GOOGLE_ARTS_AND_CULTURE))
        store.save(works)
        assertEquals(works, FileCatalogStore(tmp.root.resolve("art/catalog.json")).load())
    }

    @Test
    fun probeCacheRoundTripsAndToleratesCorruption() = runTest {
        val file = tmp.root.resolve("probe.json")
        val results = ProbeResults(setOf("gac:tall"), mapOf("gac:wide" to ImageSizeEntry(1800, 1000)))
        FileProbeCache(file).save(results)
        assertEquals(results, FileProbeCache(file).load())

        file.writeText("{not json")
        assertEquals(ProbeResults(), FileProbeCache(file).load())
    }
}
