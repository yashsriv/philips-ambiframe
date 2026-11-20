package org.yashsriv.ambiframe.art

import android.content.Context
import androidx.core.content.edit
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

/** [ImageCache] on Coil's disk cache. The cache key is the image URL. */
class CoilImageCache(
    private val context: Context,
    private val imageLoader: ImageLoader,
) : ImageCache {
    override suspend fun isCached(url: String): Boolean =
        imageLoader.diskCache?.openSnapshot(url)?.use { true } ?: false

    override suspend fun prefetch(url: String): Boolean {
        val request = ImageRequest.Builder(context)
            .data(url)
            // Only the disk copy matters here. Decoding at a tiny size keeps the prefetch cheap;
            // the full-size bitmap is decoded when the image is actually shown.
            .memoryCachePolicy(CachePolicy.DISABLED)
            .size(PREFETCH_DECODE_SIZE)
            .build()
        return imageLoader.execute(request) is SuccessResult
    }

    private companion object {
        const val PREFETCH_DECODE_SIZE = 64
    }
}

/** Which art sources are on. Google Arts & Culture is off unless the user opts in. */
class ArtSettings(context: Context) {
    private val prefs = context.getSharedPreferences("art_settings", Context.MODE_PRIVATE)

    var googleArtsCultureEnabled: Boolean
        get() = prefs.getBoolean(KEY_GOOGLE, false)
        set(value) = prefs.edit { putBoolean(KEY_GOOGLE, value) }

    fun enabledSources(): Set<ArtSource> = buildSet {
        add(ArtSource.ART_INSTITUTE_OF_CHICAGO)
        if (googleArtsCultureEnabled) add(ArtSource.GOOGLE_ARTS_AND_CULTURE)
    }

    private companion object {
        const val KEY_GOOGLE = "google_arts_culture_enabled"
    }
}

/** Process-wide wiring for the art package. */
class ArtModule private constructor(context: Context) {
    private val appContext = context.applicationContext

    val settings = ArtSettings(appContext)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val imageLoader: ImageLoader = ImageLoader.Builder(appContext)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { httpClient })) }
        .diskCache {
            DiskCache.Builder()
                .directory(appContext.cacheDir.resolve("art_images").toOkioPath())
                .maxSizeBytes(IMAGE_CACHE_BYTES)
                .build()
        }
        .build()

    val imageCache: ImageCache = CoilImageCache(appContext, imageLoader)

    private val artInstitute = ArtInstituteProvider(httpClient)
    private val googleArtsCulture = GoogleArtsCultureProvider(httpClient)

    val repository = ArtRepository(
        providers = listOf(artInstitute, googleArtsCulture),
        defaultProvider = artInstitute,
        gate = LandscapeGate(
            probe = HttpImageBoundsProbe(httpClient),
            cache = FileProbeCache(appContext.filesDir.resolve("art/probe_results.json")),
        ),
        catalog = FileCatalogStore(appContext.filesDir.resolve("art/catalog.json")),
        images = imageCache,
        enabledSources = settings::enabledSources,
    )

    /** A slideshow whose prefetches run in [scope] (the dream's scope, for example). */
    fun slideshow(scope: CoroutineScope) = ArtSlideshow(repository, imageCache, scope)

    companion object {
        /** About 120 images at 1686–3840 px wide. */
        private const val IMAGE_CACHE_BYTES = 256L * 1024 * 1024

        @Volatile private var instance: ArtModule? = null

        fun get(context: Context): ArtModule =
            instance ?: synchronized(this) { instance ?: ArtModule(context).also { instance = it } }
    }
}
