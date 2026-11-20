package org.yashsriv.ambiframe.art

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeRuleTest {
    @Test
    fun acceptsOnlyAspectRatiosFrom4_3To2_0() {
        assertTrue(LandscapeRule.accepts(1920, 1080)) // 1.78
        assertTrue(LandscapeRule.accepts(1600, 1000)) // 1.6
        assertTrue(LandscapeRule.accepts(4000, 3000)) // 4:3, inclusive
        assertTrue(LandscapeRule.accepts(1330, 1000)) // 1.33, inclusive
        assertTrue(LandscapeRule.accepts(2000, 1000)) // 2.0, inclusive
        assertFalse(LandscapeRule.accepts(1329, 1000))
        assertFalse(LandscapeRule.accepts(2001, 1000))
        assertFalse(LandscapeRule.accepts(5000, 4000)) // 5:4
        assertFalse(LandscapeRule.accepts(1080, 1920)) // portrait
        assertFalse(LandscapeRule.accepts(0, 0))
    }

    @Test
    fun unknownSizeNeedsProbe() {
        assertNull(LandscapeRule.accepts(artwork("a", width = null, height = null)))
        assertNull(LandscapeRule.accepts(artwork("a", width = 100, height = null)))
    }
}

class LandscapeGateTest {
    private val wide = artwork("wide", width = null, height = null)
    private val square = artwork("square", width = null, height = null)
    private val broken = artwork("broken", width = null, height = null)
    private val probe = FakeProbe(
        mapOf(
            "probe:wide" to ImageSize(1800, 1000),
            "probe:square" to ImageSize(1000, 1000),
            "probe:broken" to null,
        ),
    )
    private val cache = MemoryProbeCache()
    private val gate = LandscapeGate(probe, cache)
    private val probeUrl = { w: Artwork -> "probe:${w.id}" }

    @Test
    fun knownSizesAreFilteredWithoutProbing() = runTest {
        val works = listOf(artwork("a", 1700, 1000), artwork("b", 1000, 1500), artwork("c", 3000, 1000))
        assertEquals(listOf("a"), gate.filter(works, probeUrl).map { it.id })
        assertTrue(probe.probed.isEmpty())
        assertEquals(0, cache.saves)
    }

    @Test
    fun unknownSizesAreProbedAndCached() = runTest {
        val kept = gate.filter(listOf(wide, square, broken), probeUrl)

        assertEquals(listOf("wide"), kept.map { it.id })
        assertEquals(1800, kept.single().width) // measured size is filled in
        assertEquals(
            "sizes are cached for rejected works too; failed probes are not cached",
            mapOf("wide" to ImageSizeEntry(1800, 1000), "square" to ImageSizeEntry(1000, 1000)),
            cache.results.sizes,
        )
    }

    @Test
    fun cachedResultsAreNotProbedAgain() = runTest {
        gate.filter(listOf(wide, square, broken), probeUrl)
        probe.probed.clear()

        val kept = gate.filter(listOf(wide, square, broken), probeUrl)

        assertEquals(listOf("wide"), kept.map { it.id })
        assertEquals("only the failed probe is retried", listOf("probe:broken"), probe.probed)
    }

    @Test
    fun cachedSizesAreJudgedByTheCurrentRule() = runTest {
        // Cached under earlier, stricter bounds: 1.5 would have been rejected at 1.6.
        cache.results = ProbeResults(mapOf("old" to ImageSizeEntry(1500, 1000)))
        val kept = gate.filter(listOf(artwork("old", width = null, height = null)), probeUrl)

        assertEquals(listOf("old"), kept.map { it.id })
        assertTrue(probe.probed.isEmpty())
    }
}
