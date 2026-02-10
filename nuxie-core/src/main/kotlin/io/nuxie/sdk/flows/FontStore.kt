package io.nuxie.sdk.flows

import io.nuxie.sdk.logging.NuxieLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class FontStore(
  private val cacheDirectory: File,
  okHttpClient: OkHttpClient? = null,
  private val downloadTimeoutSeconds: Long = 30,
) {
  data class FontPayload(
    val bytes: ByteArray,
    val mimeType: String,
  )

  private val client: OkHttpClient = okHttpClient ?: OkHttpClient.Builder()
    .callTimeout(downloadTimeoutSeconds, TimeUnit.SECONDS)
    .connectTimeout(downloadTimeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(downloadTimeoutSeconds, TimeUnit.SECONDS)
    .writeTimeout(downloadTimeoutSeconds, TimeUnit.SECONDS)
    .build()

  private val mutex = Mutex()
  private val entriesById = mutableMapOf<String, FontManifestEntry>()
  private val pendingByHash = mutableMapOf<String, CompletableDeferred<File?>>()

  init {
    if (!cacheDirectory.exists()) {
      cacheDirectory.mkdirs()
    }
  }

  suspend fun registerFonts(entries: List<FontManifestEntry>) {
    if (entries.isEmpty()) return
    mutex.withLock {
      for (entry in entries) {
        entriesById[entry.id] = entry
      }
    }
  }

  suspend fun registerManifest(manifest: FontManifest?) {
    val entries = manifest?.fonts ?: return
    registerFonts(entries)
  }

  suspend fun prefetchFonts(entries: List<FontManifestEntry>) {
    if (entries.isEmpty()) return
    registerFonts(entries)
    coroutineScope {
      for (entry in entries) {
        async(Dispatchers.IO) {
          fetchFontIfNeeded(entry)
        }
      }
    }
  }

  suspend fun fontPayload(id: String): FontPayload? {
    val entry = mutex.withLock { entriesById[id] } ?: return null
    val mime = mimeTypeFor(entry.format)
    val cached = cachedFile(entry)
    if (cached != null) {
      val bytes = runCatching { cached.readBytes() }.getOrNull() ?: return null
      return FontPayload(bytes = bytes, mimeType = mime)
    }
    val fetched = fetchFontIfNeeded(entry) ?: return null
    val bytes = runCatching { fetched.readBytes() }.getOrNull() ?: return null
    return FontPayload(bytes = bytes, mimeType = mime)
  }

  /**
   * Synchronous variant used by WebView request interception.
   *
   * This performs best-effort disk read and (if needed) a blocking network fetch.
   */
  fun fontPayloadBlocking(id: String): FontPayload? {
    val entry = runBlocking { mutex.withLock { entriesById[id] } } ?: return null
    val mime = mimeTypeFor(entry.format)
    val cached = cachedFile(entry)
    if (cached != null) {
      val bytes = runCatching { cached.readBytes() }.getOrNull() ?: return null
      return FontPayload(bytes = bytes, mimeType = mime)
    }

    val file = runCatching { fetchFontBlocking(entry) }.getOrNull() ?: return null
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    return FontPayload(bytes = bytes, mimeType = mime)
  }

  suspend fun clearCache() {
    withContext(Dispatchers.IO) {
      if (cacheDirectory.exists()) {
        cacheDirectory.deleteRecursively()
      }
      cacheDirectory.mkdirs()
    }
    mutex.withLock {
      entriesById.clear()
      for ((_, pending) in pendingByHash) {
        pending.cancel()
      }
      pendingByHash.clear()
    }
  }

  private fun cachedFile(entry: FontManifestEntry): File? {
    val f = fileFor(entry)
    return if (f.exists()) f else null
  }

  private fun fileFor(entry: FontManifestEntry): File {
    val hashKey = entry.contentHash.removePrefix("sha256:")
    val ext = entry.format.ifBlank { "woff2" }
    return File(cacheDirectory, "${sanitize(hashKey)}.${sanitize(ext)}")
  }

  private suspend fun fetchFontIfNeeded(entry: FontManifestEntry): File? {
    cachedFile(entry)?.let { return it }

    val hashKey = entry.contentHash.removePrefix("sha256:")
    val deferred: CompletableDeferred<File?>
    val shouldFetch: Boolean
    mutex.withLock {
      val existing = pendingByHash[hashKey]
      if (existing != null) {
        deferred = existing
        shouldFetch = false
      } else {
        deferred = CompletableDeferred()
        pendingByHash[hashKey] = deferred
        shouldFetch = true
      }
    }

    if (shouldFetch) {
      try {
        val out = fetchFontBlocking(entry)
        deferred.complete(out)
      } catch (t: Throwable) {
        deferred.complete(null)
        NuxieLogger.warning("Font download failed for ${entry.family} ${entry.weight}: ${t.message}", t)
      } finally {
        mutex.withLock { pendingByHash.remove(hashKey) }
      }
    }

    return deferred.await()
  }

  private fun fetchFontBlocking(entry: FontManifestEntry): File {
    val req = Request.Builder().url(entry.assetUrl).get().build()
    val call = client.newCall(req)
    val res = call.execute()
    res.use { response ->
      if (!response.isSuccessful) {
        throw IOException("HTTP ${response.code}")
      }
      val bytes = response.body?.bytes() ?: ByteArray(0)
      val out = fileFor(entry)
      out.parentFile?.mkdirs()
      out.writeBytes(bytes)
      return out
    }
  }

  private fun mimeTypeFor(format: String): String {
    return when (format.trim().lowercase()) {
      "woff2" -> "font/woff2"
      "woff" -> "font/woff"
      "ttf", "truetype" -> "font/ttf"
      "otf", "opentype" -> "font/otf"
      else -> "application/octet-stream"
    }
  }

  private fun sanitize(value: String): String {
    return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
  }
}
