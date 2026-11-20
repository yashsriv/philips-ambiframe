package com.ambiframe.tv.art

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Works from Google Arts & Culture. Opt-in.
 *
 * UNOFFICIAL: this reads the JSON feed behind the Google Arts & Culture new-tab browser extension.
 * Google does not document or promise it, so it can change or disappear at any time. [fetch] throws
 * on any problem and [ArtRepository] then falls back to the Art Institute of Chicago.
 *
 * The feed has no image sizes, so every work goes through the landscape probe. Image URLs are
 * `lh3.ggpht.com/ci/…` and take a size suffix: checked on 2026-11-20, `=w3840` and `=s3840` both
 * work and return the image at its native size when that is smaller (e.g. 2871×2081). With no
 * suffix the server returns a 512 px rendition.
 */
class GoogleArtsCultureProvider(
    private val client: OkHttpClient,
    private val feedUrl: String = FEED_URL,
) : ArtProvider {

    override val source = ArtSource.GOOGLE_ARTS_AND_CULTURE

    override suspend fun fetch(): List<Artwork> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(feedUrl).build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Arts & Culture feed failed: HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Arts & Culture feed returned no body")
        }
        val entries = try {
            json.decodeFromString(ListSerializer(Entry.serializer()), body)
        } catch (e: IllegalArgumentException) { // SerializationException is a subclass.
            throw IOException("Arts & Culture feed is malformed", e)
        }
        entries.filterNot { it.isStreetArt() }.mapNotNull { it.toArtwork() }.distinctBy { it.id }
    }

    /** Aspect ratio is the same at every size, so probe a small rendition. */
    override fun probeUrl(artwork: Artwork): String = sized(baseImageUrl(artwork.imageUrl), PROBE_SUFFIX)

    @Serializable
    internal data class Entry(
        val title: String? = null,
        val creator: String? = null,
        val attribution: String? = null,
        @SerialName("attribution_link") val attributionLink: String? = null,
        val image: String? = null,
        val link: String? = null,
        @SerialName("artist_link") val artistLink: String? = null,
        val source: String? = null,
    ) {
        /**
         * The feed mixes in photos of murals and graffiti from Google's Street Art Project. It has no
         * category field, so they are recognised by partner, or by street-art words for partners
         * added after [STREET_ART_PARTNERS] was compiled.
         */
        fun isStreetArt(): Boolean {
            val partner = attributionLink?.trim()?.removePrefix("partner/")
            if (partner in STREET_ART_PARTNERS) return true
            return listOfNotNull(attribution, creator, title).any { STREET_ART_WORDS.containsMatchIn(it) }
        }

        fun toArtwork(): Artwork? {
            val image = image?.trim()?.takeIf { it.startsWith("http") } ?: return null
            val key = link?.trim()?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
                ?: baseImageUrl(image).substringAfterLast('/')
            return Artwork(
                id = "gac:$key",
                imageUrl = sized(baseImageUrl(image), DISPLAY_SUFFIX),
                title = title?.trim().orEmpty().ifEmpty { "Untitled" },
                artist = creator?.trim().orEmpty(),
                date = "", // The feed carries no dates.
                credit = attribution?.trim().orEmpty().ifEmpty { ArtSource.GOOGLE_ARTS_AND_CULTURE.label },
                source = ArtSource.GOOGLE_ARTS_AND_CULTURE,
            )
        }
    }

    companion object {
        const val FEED_URL = "https://www.gstatic.com/culturalinstitute/tabext/imax_2_2.json"
        const val DISPLAY_SUFFIX = "=w3840"
        const val PROBE_SUFFIX = "=s256"

        /**
         * `attribution_link` partner slugs whose works in the feed are street art. Compiled from the
         * live feed on 2026-11-20 (13 of 349 works). Pera Museum is a regular museum, but its only
         * work in the feed is a graffiti piece (Suiko's "Scorpion").
         */
        internal val STREET_ART_PARTNERS = setOf(
            "do-art-foundation",
            "dulwich-outdoor-gallery",
            "east-side-gallery",
            "emergence",
            "galeria-de-arte-urbana",
            "graffitimundo",
            "museum-of-public-art-in-louisiana",
            "pera-museum",
            "south-austin-popular-culture-center",
            "street-art-museum-amsterdam",
            "the-red-line-d-c-project",
            "wall-therapy",
            "wool-festival",
        )

        /** Matches no fine-art work in the 2026-11-20 feed. */
        internal val STREET_ART_WORDS = Regex("""street[\s-]?art|graffiti|urban art|arte urbana""", RegexOption.IGNORE_CASE)

        /** Drops any existing `=…` size suffix from a ggpht URL. */
        internal fun baseImageUrl(url: String): String {
            val slash = url.lastIndexOf('/')
            val eq = url.indexOf('=', startIndex = slash + 1)
            return if (eq >= 0) url.substring(0, eq) else url
        }

        internal fun sized(baseUrl: String, suffix: String) = baseUrl + suffix

        private val json = Json { ignoreUnknownKeys = true }
    }
}
