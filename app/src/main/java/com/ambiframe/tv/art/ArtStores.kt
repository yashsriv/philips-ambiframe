package com.ambiframe.tv.art

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** The last good list of works, attribution included, for use when every source is offline. */
interface CatalogStore {
    suspend fun load(): List<Artwork>
    suspend fun save(works: List<Artwork>)
}

/** A JSON file written atomically (temp file + rename). A missing or corrupt file reads as [empty]. */
internal class JsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val empty: () -> T,
) {
    private val mutex = Mutex()

    suspend fun read(): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext empty()
            try {
                json.decodeFromString(serializer, file.readText())
            } catch (e: Exception) {
                Log.w(TAG, "Ignoring unreadable ${file.name}", e)
                empty()
            }
        }
    }

    suspend fun write(value: T): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json.encodeToString(serializer, value))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    if (!tmp.renameTo(file)) throw IOException("rename failed")
                }
            } catch (e: IOException) {
                Log.w(TAG, "Could not write ${file.name}", e)
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

class FileCatalogStore(file: File) : CatalogStore {
    private val store = JsonFile(file, ListSerializer(Artwork.serializer())) { emptyList() }
    override suspend fun load() = store.read()
    override suspend fun save(works: List<Artwork>) = store.write(works)
}

class FileProbeCache(file: File) : ProbeCache {
    private val store = JsonFile(file, ProbeResults.serializer()) { ProbeResults() }
    override suspend fun load() = store.read()
    override suspend fun save(results: ProbeResults) = store.write(results)
}

internal const val TAG = "AmbiFrameArt"
