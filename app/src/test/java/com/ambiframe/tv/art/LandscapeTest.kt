package com.ambiframe.tv.art

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeRuleTest {
    @Test
    fun acceptsOnlyAspectRatiosFrom1_6To2_0() {
        assertTrue(LandscapeRule.accepts(1920, 1080)) // 1.78
        assertTrue(LandscapeRule.accepts(1600, 1000)) // 1.6, inclusive
        assertTrue(LandscapeRule.accepts(2000, 1000)) // 2.0, inclusive
        assertFalse(LandscapeRule.accepts(1599, 1000))
        assertFalse(LandscapeRule.accepts(2001, 1000))
        assertFalse(LandscapeRule.accepts(4000, 3000)) // 4:3
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
        assertEquals(setOf("square"), cache.results.rejected)
        assertEquals(ImageSizeEntry(1800, 1000), cache.results.accepted["wide"])
        assertFalse("failed probes are not cached", "broken" in cache.results.rejected)
    }

    @Test
    fun cachedResultsAreNotProbedAgain() = runTest {
        gate.filter(listOf(wide, square, broken), probeUrl)
        probe.probed.clear()

        val kept = gate.filter(listOf(wide, square, broken), probeUrl)

        assertEquals(listOf("wide"), kept.map { it.id })
        assertEquals("only the failed probe is retried", listOf("probe:broken"), probe.probed)
    }
}
