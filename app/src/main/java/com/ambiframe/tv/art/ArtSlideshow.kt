package com.ambiframe.tv.art

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * What the screensaver asks for the next picture.
 *
 * [next] returns a work whose image is already in the disk cache, so the caller can show it right
 * away, and starts downloading the one after it in [scope]. When the network is down, works that
 * aren't cached are skipped: the show carries on with what is cached, trying the network once per
 * slide until it comes back. The list is refreshed in the background every [refreshInterval], or
 * every [retryInterval] while the last refresh had to use the offline catalog.
 */
class ArtSlideshow(
    private val repository: ArtRepository,
    private val images: ImageCache,
    private val scope: CoroutineScope,
    random: Random = Random.Default,
    private val refreshInterval: Duration = 12.hours,
    private val retryInterval: Duration = 15.minutes,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val playlist = ArtPlaylist(emptyList(), random)
    private val lock = Mutex()
    private val refreshing = Mutex()
    private var current: Artwork? = null
    private var lastRefreshAt: Long? = null

    /** The works in the current rotation, in play order for this pass. */
    suspend fun works(): List<Artwork> = lock.withLock { playlist.works }

    suspend fun next(): Artwork? {
        if (lock.withLock { playlist.size } == 0) {
            refresh()
        } else if (refreshDue()) {
            scope.launch { refresh() }
        }

        val work = lock.withLock { pickReady() } ?: return null
        prefetchUpcoming()
        return work
    }

    /** Fetches a fresh list now. Returns how many works are in the rotation afterwards. */
    suspend fun refresh(): Int {
        if (!refreshing.tryLock()) return lock.withLock { playlist.size }
        try {
            val works = repository.refresh()
            lastRefreshAt = clock()
            return lock.withLock {
                // Offline results are only the cached subset of the saved catalog. A list already in
                // rotation is kept instead, since pickReady() skips what isn't cached anyway and the
                // rest becomes available again as soon as the network is back.
                val keepCurrent = works.isEmpty() || (repository.isOffline && playlist.size > 0)
                if (!keepCurrent) playlist.replace(works, current)
                playlist.size
            }
        } finally {
            refreshing.unlock()
        }
    }

    private fun refreshDue(): Boolean {
        val last = lastRefreshAt ?: return true
        val interval = if (repository.isOffline) retryInterval else refreshInterval
        return clock() - last >= interval.inWholeMilliseconds
    }

    /** Walks at most one pass looking for a work whose image is cached or downloads now. */
    private suspend fun pickReady(): Artwork? {
        var networkTries = 1
        repeat(playlist.size) {
            val work = playlist.next() ?: return null
            val ready = images.isCached(work.imageUrl) ||
                (networkTries-- > 0 && images.prefetch(work.imageUrl))
            if (ready) {
                current = work
                return work
            }
        }
        return null
    }

    private fun prefetchUpcoming() {
        scope.launch {
            val upcoming = lock.withLock { playlist.peek() } ?: return@launch
            if (!images.isCached(upcoming.imageUrl)) images.prefetch(upcoming.imageUrl)
        }
    }
}
