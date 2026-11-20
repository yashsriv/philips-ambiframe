package com.ambiframe.tv.art

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/** Probe results that survive restarts, so each image is measured at most once. */
@Serializable
data class ProbeResults(
    /** IDs of works whose image is not landscape. */
    val rejected: Set<String> = emptySet(),
    /** Measured sizes of works that passed. */
    val accepted: Map<String, ImageSizeEntry> = emptyMap(),
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
 * Works that carry a size are checked directly. Works without one are probed (a few at a time),
 * and the outcome is cached by ID: rejected IDs are never probed again, and accepted works get
 * their measured size filled in. A failed probe is not cached, so the work is retried next time.
 */
class LandscapeGate(
    private val probe: ImageBoundsProbe,
    private val cache: ProbeCache,
    private val maxConcurrentProbes: Int = 4,
) {
    suspend fun filter(works: List<Artwork>, probeUrl: (Artwork) -> String): List<Artwork> {
        val known = cache.load()

        val unknown = works.filter {
            LandscapeRule.accepts(it) == null && it.id !in known.rejected && it.id !in known.accepted
        }
        val permits = Semaphore(maxConcurrentProbes)
        val measured: Map<String, ImageSize> = coroutineScope {
            unknown.map { work ->
                async { permits.withPermit { work.id to probe.probe(probeUrl(work)) } }
            }.awaitAll()
        }.mapNotNull { (id, size) -> size?.let { id to it } }.toMap()

        if (measured.isNotEmpty()) {
            val (pass, fail) = measured.entries.partition { LandscapeRule.accepts(it.value.width, it.value.height) }
            cache.save(
                ProbeResults(
                    rejected = known.rejected + fail.map { it.key },
                    accepted = known.accepted + pass.associate { it.key to ImageSizeEntry(it.value.width, it.value.height) },
                ),
            )
        }

        return works.mapNotNull { work ->
            val size = when {
                work.width != null && work.height != null -> ImageSize(work.width, work.height)
                work.id in known.rejected -> null
                else -> known.accepted[work.id]?.let { ImageSize(it.width, it.height) } ?: measured[work.id]
            }
            size?.takeIf { LandscapeRule.accepts(it.width, it.height) }
                ?.let { work.copy(width = it.width, height = it.height) }
        }
    }
}
