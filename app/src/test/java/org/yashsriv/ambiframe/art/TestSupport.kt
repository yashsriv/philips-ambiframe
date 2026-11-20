package org.yashsriv.ambiframe.art

import okio.Buffer
import java.io.IOException

internal fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.classLoader?.getResource("art/$name")) { "missing fixture $name" }.readText()

internal fun artwork(
    id: String,
    width: Int? = 1700,
    height: Int? = 1000,
    source: ArtSource = ArtSource.ART_INSTITUTE_OF_CHICAGO,
) = Artwork(
    id = id,
    imageUrl = "https://img.example/$id.jpg",
    width = width,
    height = height,
    title = "Title $id",
    artist = "Artist $id",
    date = "1890",
    credit = "Museum $id",
    source = source,
)

/** Minimal PNG header: signature + IHDR. */
internal fun pngHeader(width: Int, height: Int): Buffer = Buffer()
    .write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    .writeInt(13).writeUtf8("IHDR").writeInt(width).writeInt(height)
    .write(byteArrayOf(8, 2, 0, 0, 0))

/** JPEG with APP0 and DQT segments ahead of the SOF0 frame header, like a real encoder writes. */
internal fun jpegHeader(width: Int, height: Int, sofMarker: Int = 0xC0): Buffer = Buffer()
    .writeShort(0xFFD8)
    .writeShort(0xFFE0).writeShort(16).writeUtf8("JFIF").writeByte(0).write(ByteArray(9))
    .writeShort(0xFFDB).writeShort(67).write(ByteArray(65))
    .writeShort(0xFF00 or sofMarker).writeShort(17).writeByte(8).writeShort(height).writeShort(width)
    .write(ByteArray(10))
    .writeShort(0xFFD9)

internal class FakeProbe(private val sizes: Map<String, ImageSize?>) : ImageBoundsProbe {
    val probed = mutableListOf<String>()
    override suspend fun probe(url: String): ImageSize? {
        synchronized(probed) { probed += url }
        return sizes[url]
    }
}

internal class MemoryProbeCache(var results: ProbeResults = ProbeResults()) : ProbeCache {
    var saves = 0
    override suspend fun load() = results
    override suspend fun save(results: ProbeResults) {
        saves++
        this.results = results
    }
}

internal class MemoryCatalog(var works: List<Artwork> = emptyList()) : CatalogStore {
    override suspend fun load() = works
    override suspend fun save(works: List<Artwork>) {
        this.works = works
    }
}

internal class FakeImageCache(
    cached: Set<String> = emptySet(),
    var online: Boolean = true,
) : ImageCache {
    val cached = cached.toMutableSet()
    val prefetched = mutableListOf<String>()

    override suspend fun isCached(url: String) = url in cached

    override suspend fun prefetch(url: String): Boolean {
        prefetched += url
        if (online) cached += url
        return online
    }
}

internal class FakeProvider(
    override val source: ArtSource,
    var result: () -> List<Artwork>,
) : ArtProvider {
    var calls = 0
    override suspend fun fetch(): List<Artwork> {
        calls++
        return result()
    }

    companion object {
        fun failing(source: ArtSource) = FakeProvider(source) { throw IOException("offline") }
    }
}
