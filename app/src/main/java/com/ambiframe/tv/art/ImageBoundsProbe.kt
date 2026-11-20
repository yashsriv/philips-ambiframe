package com.ambiframe.tv.art

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import java.io.IOException

data class ImageSize(val width: Int, val height: Int)

/** Finds an image's pixel size without decoding it. Returns `null` when the size can't be read. */
fun interface ImageBoundsProbe {
    suspend fun probe(url: String): ImageSize?
}

/**
 * Streams the start of the image over HTTP and reads the size from the file header. It closes the
 * connection as soon as the size is known, so a probe usually costs a few kilobytes.
 */
class HttpImageBoundsProbe(private val client: OkHttpClient) : ImageBoundsProbe {
    override suspend fun probe(url: String): ImageSize? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.source()?.let(ImageHeaderParser::parse)
            }
        } catch (e: IOException) {
            null
        }
    }
}

/**
 * Reads width and height from JPEG, PNG, GIF and WebP headers.
 *
 * JPEG EXIF orientation is not applied. Museum images are stored upright, so a rotated file would be
 * rare; it would be measured with width and height swapped.
 */
object ImageHeaderParser {
    private val PNG = "89504e470d0a1a0a".decodeHex()
    private val GIF = "GIF8".encodeToByteArray()
    private val RIFF = "RIFF".encodeToByteArray()
    private val WEBP = "WEBP".encodeToByteArray()

    fun parse(source: BufferedSource): ImageSize? = try {
        when {
            source.rangeEquals(0, PNG) -> parsePng(source)
            source.request(2) && source.buffer[0] == 0xFF.toByte() && source.buffer[1] == 0xD8.toByte() ->
                parseJpeg(source)
            source.startsWith(GIF) -> parseGif(source)
            source.startsWith(RIFF) && source.startsWith(WEBP, offset = 8) -> parseWebp(source)
            else -> null
        }
    } catch (e: IOException) {
        null
    }?.takeIf { it.width > 0 && it.height > 0 }

    private fun BufferedSource.startsWith(bytes: ByteArray, offset: Long = 0): Boolean =
        request(offset + bytes.size) && bytes.indices.all { buffer[offset + it] == bytes[it] }

    private fun parsePng(s: BufferedSource): ImageSize? {
        // Signature (8), IHDR length (4), "IHDR" (4), width (4), height (4).
        s.skip(12)
        if (s.readUtf8(4) != "IHDR") return null
        return ImageSize(s.readInt(), s.readInt())
    }

    private fun parseGif(s: BufferedSource): ImageSize {
        s.skip(6)
        return ImageSize(s.readShortLe().toInt() and 0xFFFF, s.readShortLe().toInt() and 0xFFFF)
    }

    private fun parseWebp(s: BufferedSource): ImageSize? {
        s.skip(12)
        return when (s.readUtf8(4)) {
            "VP8 " -> {
                // Chunk size (4), frame tag (3), start code (3), then 14-bit width and height.
                s.skip(10)
                ImageSize(s.readShortLe().toInt() and 0x3FFF, s.readShortLe().toInt() and 0x3FFF)
            }
            "VP8L" -> {
                s.skip(5) // Chunk size (4), signature byte 0x2F.
                val bits = s.readIntLe()
                ImageSize((bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)
            }
            "VP8X" -> {
                s.skip(8) // Chunk size (4), flags (4).
                ImageSize(s.readUInt24Le() + 1, s.readUInt24Le() + 1)
            }
            else -> null
        }
    }

    private fun BufferedSource.readUInt24Le(): Int {
        val b0 = readByte().toInt() and 0xFF
        val b1 = readByte().toInt() and 0xFF
        val b2 = readByte().toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16)
    }

    private fun parseJpeg(s: BufferedSource): ImageSize? {
        s.skip(2) // SOI
        while (true) {
            if (s.readByte() != 0xFF.toByte()) return null
            var marker = s.readByte().toInt() and 0xFF
            while (marker == 0xFF) marker = s.readByte().toInt() and 0xFF // Fill bytes.
            when (marker) {
                0x01, in 0xD0..0xD7 -> continue // Markers without a length.
                0xD9, 0xDA -> return null // End of image, or scan data before any frame header.
            }
            val length = s.readShort().toInt() and 0xFFFF
            if (length < 2) return null
            val isFrameHeader = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrameHeader) {
                s.skip(1) // Sample precision.
                val height = s.readShort().toInt() and 0xFFFF
                val width = s.readShort().toInt() and 0xFFFF
                return ImageSize(width, height)
            }
            s.skip(length - 2L)
        }
    }
}
