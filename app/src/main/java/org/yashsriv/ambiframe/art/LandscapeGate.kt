package org.yashsriv.ambiframe.art

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/**
 * Probe results that survive restarts, so each image is measured at most once.
 *
 * Sizes are kept for every probed work, rejected ones included, and [LandscapeRule] is applied when
 * they are read. A work rejected under one set of bounds can pass after the bounds change, without
 * clearing the cache or probing again.
 */
@Serializable
data class ProbeResults(
    val sizes: Map<String, ImageSizeEntry> = emptyMap(),
)

@Serializable
data class ImageSizeEntry(val width: Int, val height: Int)

interface ProbeCache {
    suspend fun load(): ProbeResults
    suspend fun save(results: ProbeResults)
}

/**
 * Applies [LandscapeRule] to a provider's works.
 *
 * Works that carry a size are checked directly. Works without one are probed (a few at a time) and
 * the measured size is cached by ID, so a rejected work is never probed again and an accepted one
 * gets its size filled in. A failed probe is not cached, so the work is retried next time.
 */
class LandscapeGate(
    private val probe: ImageBoundsProbe,
    private val cache: ProbeCache,
    private val maxConcurrentProbes: Int = 4,
) {
    suspend fun filter(works: List<Artwork>, probeUrl: (Artwork) -> String): List<Artwork> {
        val known = cache.load().sizes

        val unknown = works.filter { LandscapeRule.accepts(it) == null && it.id !in known }
        val permits = Semaphore(maxConcurrentProbes)
        val measured: Map<String, ImageSizeEntry> = coroutineScope {
            unknown.map { work ->
                async { permits.withPermit { work.id to probe.probe(probeUrl(work)) } }
            }.awaitAll()
        }.mapNotNull { (id, size) -> size?.let { id to ImageSizeEntry(it.width, it.height) } }.toMap()

        if (measured.isNotEmpty()) cache.save(ProbeResults(known + measured))

        return works.mapNotNull { work ->
            val size = if (work.width != null && work.height != null) {
                ImageSizeEntry(work.width, work.height)
            } else {
                known[work.id] ?: measured[work.id]
            }
            size?.takeIf { LandscapeRule.accepts(it.width, it.height) }
                ?.let { work.copy(width = it.width, height = it.height) }
        }
    }
}
