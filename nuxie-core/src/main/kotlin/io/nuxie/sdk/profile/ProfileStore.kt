package io.nuxie.sdk.profile

import io.nuxie.sdk.errors.NuxieError
import io.nuxie.sdk.logging.NuxieLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

interface CachedProfileStore {
  suspend fun store(item: CachedProfile, forKey: String)
  suspend fun retrieve(forKey: String, allowStale: Boolean = true): CachedProfile?
  suspend fun remove(forKey: String)
  suspend fun clearAll()
  suspend fun cleanupExpired(nowEpochMillis: Long): Int
  suspend fun getAllKeys(): List<String>
}

class InMemoryCachedProfileStore : CachedProfileStore {
  private val storage: MutableMap<String, CachedProfile> = mutableMapOf()

  override suspend fun store(item: CachedProfile, forKey: String) {
    storage[forKey] = item
  }

  override suspend fun retrieve(forKey: String, allowStale: Boolean): CachedProfile? {
    return storage[forKey]
  }

  override suspend fun remove(forKey: String) {
    storage.remove(forKey)
  }

  override suspend fun clearAll() {
    storage.clear()
  }

  override suspend fun cleanupExpired(nowEpochMillis: Long): Int {
    // Caller controls expiry policy; in-memory store doesn't do TTL.
    return 0
  }

  override suspend fun getAllKeys(): List<String> = storage.keys.toList()
}

/**
 * Simple file-based profile cache.
 *
 * This is intentionally small and single-purpose: it caches `CachedProfile` JSON blobs keyed by
 * distinctId. It's not a general DiskCache implementation.
 */
class FileCachedProfileStore(
  private val directory: File,
  private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
  private val ttlMillis: Long,
) : CachedProfileStore {

  init {
    if (!directory.exists()) {
      directory.mkdirs()
    }
  }

  override suspend fun store(item: CachedProfile, forKey: String) {
    val file = fileForKey(forKey)
    val encoded = try {
      json.encodeToString(CachedProfile.serializer(), item)
    } catch (e: Exception) {
      throw NuxieError.StorageError(e)
    }

    try {
      withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.writeText(encoded)
      }
    } catch (e: Exception) {
      throw NuxieError.StorageError(e)
    }
  }

  override suspend fun retrieve(forKey: String, allowStale: Boolean): CachedProfile? {
    val file = fileForKey(forKey)
    if (!file.exists()) return null
    return try {
      val raw = withContext(Dispatchers.IO) { file.readText() }
      val decoded = json.decodeFromString(CachedProfile.serializer(), raw)
      if (!allowStale) {
        val age = System.currentTimeMillis() - decoded.cachedAtEpochMillis
        if (age > ttlMillis) {
          return null
        }
      }
      decoded
    } catch (e: Exception) {
      NuxieLogger.debug("Failed to decode cached profile; treating as miss: ${e.message}")
      null
    }
  }

  override suspend fun remove(forKey: String) {
    val file = fileForKey(forKey)
    withContext(Dispatchers.IO) {
      runCatching { file.delete() }
    }
  }

  override suspend fun clearAll() {
    withContext(Dispatchers.IO) {
      directory.listFiles()?.forEach { it.delete() }
    }
  }

  override suspend fun cleanupExpired(nowEpochMillis: Long): Int {
    var removed = 0
    withContext(Dispatchers.IO) {
      directory.listFiles()?.forEach { file ->
        val raw = runCatching { file.readText() }.getOrNull() ?: return@forEach
        val decoded = runCatching { json.decodeFromString(CachedProfile.serializer(), raw) }.getOrNull()
          ?: return@forEach
        val age = nowEpochMillis - decoded.cachedAtEpochMillis
        if (age > ttlMillis) {
          if (file.delete()) removed += 1
        }
      }
    }
    return removed
  }

  override suspend fun getAllKeys(): List<String> {
    // We can't reverse hashed filenames to distinct IDs; we store key inside JSON.
    val keys = mutableListOf<String>()
    withContext(Dispatchers.IO) {
      directory.listFiles()?.forEach { file ->
        val raw = runCatching { file.readText() }.getOrNull() ?: return@forEach
        val decoded = runCatching { json.decodeFromString(CachedProfile.serializer(), raw) }.getOrNull()
          ?: return@forEach
        keys += decoded.distinctId
      }
    }
    return keys
  }

  private fun fileForKey(key: String): File {
    return File(directory, "${sha256Hex(key)}.json")
  }

  private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
      sb.append(((b.toInt() and 0xff) + 0x100).toString(16).substring(1))
    }
    return sb.toString()
  }
}

