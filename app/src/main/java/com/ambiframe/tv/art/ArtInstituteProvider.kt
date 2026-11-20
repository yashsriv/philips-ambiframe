package com.ambiframe.tv.art

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Public-domain Impressionist and Post-Impressionist works from the Art Institute of Chicago's
 * public API (https://api.artic.edu/docs/). This is the default source.
 */
class ArtInstituteProvider(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val pageSize: Int = 100,
    private val maxPages: Int = 10,
) : ArtProvider {

    override val source = ArtSource.ART_INSTITUTE_OF_CHICAGO

    override suspend fun fetch(): List<Artwork> = withContext(Dispatchers.IO) {
        val works = mutableListOf<Artwork>()
        var page = 1
        do {
            val response = searchPage(page)
            response.data.mapNotNullTo(works) { it.toArtwork() }
            val totalPages = response.pagination?.totalPages ?: page
            page++
        } while (page <= totalPages && page <= maxPages)
        works
    }

    private fun searchPage(page: Int): SearchResponse {
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("api/v1/artworks/search").build())
            .header("AIC-User-Agent", AIC_USER_AGENT)
            .post(searchBody(page).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("AIC search failed: HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("AIC search returned no body")
            return json.decodeFromString(SearchResponse.serializer(), body)
        }
    }

    /** Elasticsearch query: public domain, has an image, style is Impressionism or Post-Impressionism. */
    internal fun searchBody(page: Int) = buildJsonObject {
        putJsonObject("query") {
            putJsonObject("bool") {
                putJsonArray("must") {
                    addJsonObject { putJsonObject("term") { put("is_public_domain", true) } }
                    addJsonObject { putJsonObject("exists") { put("field", "image_id") } }
                    addJsonObject {
                        putJsonObject("terms") {
                            putJsonArray("style_id") { STYLE_IDS.forEach { add(it) } }
                        }
                    }
                }
            }
        }
        putJsonArray("fields") { FIELDS.forEach { add(it) } }
        put("limit", pageSize)
        put("page", page)
    }

    @Serializable
    internal data class SearchResponse(
        val pagination: Pagination? = null,
        val data: List<Item> = emptyList(),
    )

    @Serializable
    internal data class Pagination(
        val total: Int? = null,
        @SerialName("total_pages") val totalPages: Int? = null,
    )

    @Serializable
    internal data class Item(
        val id: Long,
        val title: String? = null,
        @SerialName("artist_display") val artistDisplay: String? = null,
        @SerialName("date_display") val dateDisplay: String? = null,
        @SerialName("image_id") val imageId: String? = null,
        val thumbnail: Thumbnail? = null,
        @SerialName("credit_line") val creditLine: String? = null,
    ) {
        fun toArtwork(): Artwork? {
            val image = imageId?.takeIf { it.isNotBlank() } ?: return null
            return Artwork(
                id = "aic:$id",
                imageUrl = iiifUrl(image),
                width = thumbnail?.width,
                height = thumbnail?.height,
                title = title?.trim().orEmpty().ifEmpty { "Untitled" },
                artist = artistName(artistDisplay),
                date = dateDisplay?.trim().orEmpty(),
                credit = listOfNotNull(MUSEUM, creditLine?.trim()?.takeIf { it.isNotEmpty() })
                    .joinToString(", "),
                source = ArtSource.ART_INSTITUTE_OF_CHICAGO,
            )
        }
    }

    @Serializable
    internal data class Thumbnail(val width: Int? = null, val height: Int? = null)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.artic.edu/"
        const val MUSEUM = "The Art Institute of Chicago"

        /**
         * Style category terms, looked up on 2026-11-20 via
         * `/api/v1/category-terms/search?q=Impressionism` and `?q=Post-Impressionism` (subtype `style`).
         */
        const val STYLE_IMPRESSIONISM = "TM-7543"
        const val STYLE_POST_IMPRESSIONISM = "TM-7547"
        val STYLE_IDS = listOf(STYLE_IMPRESSIONISM, STYLE_POST_IMPRESSIONISM)

        val FIELDS = listOf(
            "id", "title", "artist_display", "date_display", "image_id", "thumbnail", "credit_line",
        )

        /** The AIC docs ask clients to identify themselves. Generic app name only. */
        const val AIC_USER_AGENT = "AmbiFrame-TV (Android TV art screensaver)"

        /** 1686 px is the largest width AIC recommends requesting from IIIF. */
        fun iiifUrl(imageId: String) = "https://www.artic.edu/iiif/2/$imageId/full/1686,/0/default.jpg"

        /** `"Claude Monet\nFrench, 1840-1926"` or `"Vincent van Gogh (Dutch, 1853–1890)"` → the name. */
        internal fun artistName(display: String?): String {
            val firstLine = display?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            return firstLine.replace(Regex("""\s*\([^()]*\)\s*$"""), "").trim()
        }

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}
