package com.ambiframe.tv.art

/**
 * The landscape-only rule shared by every provider.
 *
 * The screen is 16:9 (1.78) and works fill it with `ContentScale.Crop`. At the lower bound (4:3)
 * up to about 25% of the height is cut off top and bottom; at the upper bound (2.0) about 11% of
 * the width is cut off at the sides.
 *
 * Counts on 2026-11-20 for 1.33–2.0: AIC 69 of 241 works, Google Arts & Culture 213 of 349
 * (at 1.6–2.0 it was 15 and 61).
 *
 * The bounds can change freely: [LandscapeGate] caches measured sizes, not verdicts.
 */
object LandscapeRule {
    /** 4:3, rounded down slightly so canvases a pixel short of 4:3 still count. */
    const val MIN_ASPECT = 1.33
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
