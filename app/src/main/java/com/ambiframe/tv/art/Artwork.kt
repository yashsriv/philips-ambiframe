package com.ambiframe.tv.art

import kotlinx.serialization.Serializable

/** Where a work came from. [label] is the short name the attribution line can show. */
@Serializable
enum class ArtSource(val label: String) {
    ART_INSTITUTE_OF_CHICAGO("Art Institute of Chicago"),
    GOOGLE_ARTS_AND_CULTURE("Google Arts & Culture"),
}

/**
 * One work of art plus everything needed to attribute it on screen.
 *
 * [id] is unique across sources (it carries a source prefix such as `aic:` or `gac:`).
 * [width]/[height] are the image's pixel size (or anything with the same aspect ratio), when known.
 * [credit] is the holding museum, optionally followed by its credit line.
 */
@Serializable
data class Artwork(
    val id: String,
    val imageUrl: String,
    val width: Int? = null,
    val height: Int? = null,
    val title: String,
    val artist: String,
    val date: String,
    val credit: String,
    val source: ArtSource,
)

/** A source of artwork. Implementations return raw works; [LandscapeGate] applies the landscape rule. */
interface ArtProvider {
    val source: ArtSource

    /** Fetches the provider's current list. Throws on network or format errors. */
    suspend fun fetch(): List<Artwork>

    /**
     * URL to use when the image size must be probed. Providers whose image host can serve a small
     * rendition should return it here, so probing costs a few KB instead of a full image.
     */
    fun probeUrl(artwork: Artwork): String = artwork.imageUrl
}
