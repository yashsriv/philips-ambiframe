package org.yashsriv.ambiframe.art

import android.util.Log
import kotlinx.coroutines.CancellationException

/** Where the images live. [CoilImageCache] is the real one. */
interface ImageCache {
    /** True when the image is in the disk cache and can be shown offline. */
    suspend fun isCached(url: String): Boolean

    /** Downloads the image into the disk cache. Returns false on failure. */
    suspend fun prefetch(url: String): Boolean
}

/**
 * Builds the list of works to show.
 *
 * Each enabled provider is fetched and passed through [LandscapeGate]. If any non-default source
 * fails or yields nothing, the default provider (AIC) is used as well, so opting in to the
 * unofficial feed can never leave the screen empty. A good result is saved to [catalog]; when every
 * source fails, the saved catalog is used, limited to works whose images are in the disk cache.
 */
class ArtRepository(
    private val providers: List<ArtProvider>,
    private val defaultProvider: ArtProvider,
    private val gate: LandscapeGate,
    private val catalog: CatalogStore,
    private val images: ImageCache,
    /** Which sources are enabled; read on every refresh so a settings change applies next time. */
    private val enabledSources: () -> Set<ArtSource> = { setOf(defaultProvider.source) },
) {
    /** Whether the last [refresh] came from the saved catalog instead of the network. */
    var isOffline: Boolean = false
        private set

    suspend fun refresh(): List<Artwork> {
        val enabled = enabledSources()
        val selected = providers.filter { it.source in enabled }.ifEmpty { listOf(defaultProvider) }

        val results = selected.associateWith { load(it) }
        val works = results.values.flatMap { it.orEmpty() }.toMutableList()
        val needsFallback = defaultProvider !in selected && results.values.any { it.isNullOrEmpty() }
        if (needsFallback) {
            Log.i(TAG, "Falling back to ${defaultProvider.source}")
            load(defaultProvider)?.let(works::addAll)
        }

        val unique = works.distinctBy { it.id }
        if (unique.isNotEmpty()) {
            isOffline = false
            catalog.save(unique)
            return unique
        }

        isOffline = true
        return catalog.load().filter { images.isCached(it.imageUrl) }
    }

    /** The provider's landscape works, or null if it failed. */
    private suspend fun load(provider: ArtProvider): List<Artwork>? = try {
        gate.filter(provider.fetch(), provider::probeUrl).also {
            Log.i(TAG, "${provider.source}: ${it.size} landscape works")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "${provider.source} failed", e)
        null
    }
}
