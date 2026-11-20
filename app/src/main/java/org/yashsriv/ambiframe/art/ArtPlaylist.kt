package org.yashsriv.ambiframe.art

import kotlin.random.Random

/**
 * Shuffled rotation: every work is shown once per pass, then the list is reshuffled. The first work
 * of a new pass is never the one that ended the previous pass, so nothing repeats back to back.
 */
class ArtPlaylist(works: List<Artwork>, private val random: Random = Random.Default) {
    private var order: List<Artwork> = works.distinctBy { it.id }.shuffled(random)
    private var index = 0

    val size: Int get() = order.size
    val works: List<Artwork> get() = order

    /** The work [next] will return, without advancing. */
    fun peek(): Artwork? = order.getOrNull(index)

    fun next(): Artwork? {
        val work = order.getOrNull(index) ?: return null
        index++
        if (index == order.size) {
            order = reshuffled(previous = work)
            index = 0
        }
        return work
    }

    /** Swaps in a new list (after a refresh) and starts a new pass, avoiding [current] first. */
    fun replace(works: List<Artwork>, current: Artwork? = null) {
        order = works.distinctBy { it.id }.shuffled(random)
        index = 0
        if (current != null) order = avoidFirst(order, current)
    }

    private fun reshuffled(previous: Artwork) = avoidFirst(order.shuffled(random), previous)

    private fun avoidFirst(list: List<Artwork>, work: Artwork): List<Artwork> {
        if (list.size < 2 || list.first().id != work.id) return list
        val swapWith = 1 + random.nextInt(list.size - 1)
        return list.toMutableList().apply { this[0] = this[swapWith].also { this[swapWith] = this[0] } }
    }
}
