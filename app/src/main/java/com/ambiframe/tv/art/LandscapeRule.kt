package com.ambiframe.tv.art

/**
 * The landscape-only rule shared by every provider.
 *
 * The screen is 16:9 (1.78). A work between 1.6 and 2.0 fills it with `ContentScale.Crop` and
 * loses at most about 11% at the edges, which keeps the composition intact.
 */
object LandscapeRule {
    const val MIN_ASPECT = 1.6
    const val MAX_ASPECT = 2.0

    fun accepts(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val aspect = width.toDouble() / height
        return aspect in MIN_ASPECT..MAX_ASPECT
    }

    /** `null` when the work carries no size and has to be probed. */
    fun accepts(artwork: Artwork): Boolean? {
        val w = artwork.width ?: return null
        val h = artwork.height ?: return null
        return accepts(w, h)
    }
}
