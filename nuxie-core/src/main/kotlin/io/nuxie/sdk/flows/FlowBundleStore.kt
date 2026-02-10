package io.nuxie.sdk.flows

import io.nuxie.sdk.logging.NuxieLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Disk cache for Flow web bundles.
 *
 * iOS builds and caches a WebArchive. On Android we cache the bundle files on disk
 * and serve them to WebView via request interception (cache-first, remote fallback).
 */
class FlowBundleStore(
  private val cacheDirectory: File,
  okHttpClient: OkHttpClient? = null,
  private val maxConcurrentDownloads: Int = 4,
  private val downloadTimeoutSeconds: Long = 30,
) {
  private val client: OkHttpClient = okHttpClient ?: OkHttpClient.Builder()
    .callTimeout(downloadTimeoutSeconds, TimeUnit.SECONDS)
    .connectTimeout(downloadTimeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(downloadTimeoutSeconds, TimeUnit.SECONDS)
    .writeTimeout(downloadTimeoutSeconds, TimeUnit.SECONDS)
    .build()

  private val mutex = Mutex()
  private val pendingByKey = mutableMapOf<String, CompletableDeferred<File>>()

  init {
    if (!cacheDirectory.exists()) {
      cacheDirectory.mkdirs()
    }
  }

  /**
   * Returns the cached bundle directory for [flow], if present and valid.
   */
  fun getCachedBundleDir(flow: Flow): File? {
    val dir = bundleDir(flowId = flow.id, contentHash = flow.manifest.contentHash)
    if (!dir.exists() || !dir.isDirectory) return null
    val main = resolveMainFile(flow.manifest) ?: return null
    val mainFile = File(dir, main.path)
    return if (mainFile.exists()) dir else null
  }

  /**
   * Cache-first download of the flow bundle.
   *
   * Returns the canonical on-disk directory holding the bundle files.
   */
  suspend fun preloadBundle(flow: Flow): File {
    getCachedBundleDir(flow)?.let { return it }

    val key = downloadKey(flow.id, flow.manifest.contentHash)
    val deferred: CompletableDeferred<File>
    val shouldDownload: Boolean
    mutex.withLock {
      val existing = pendingByKey[key]
      if (existing != null) {
        deferred = existing
        shouldDownload = false
      } else {
        deferred = CompletableDeferred()
        pendingByKey[key] = deferred
        shouldDownload = true
      }
    }

    if (shouldDownload) {
      try {
        val dir = downloadToDisk(flow)
        deferred.complete(dir)
      } catch (t: Throwable) {
        deferred.completeExceptionally(t)
        throw t
      } finally {
        mutex.withLock { pendingByKey.remove(key) }
      }
    }

    return deferred.await()
  }

  suspend fun removeBundles(flowId: String) {
    withContext(Dispatchers.IO) {
      if (!cacheDirectory.exists()) return@withContext
      cacheDirectory.listFiles()?.forEach { file ->
        if (file.isDirectory && file.name.startsWith("flow_${sanitize(flowId)}_")) {
          file.deleteRecursively()
        }
      }
    }
  }

  suspend fun clearAll() {
    withContext(Dispatchers.IO) {
      if (cacheDirectory.exists()) {
        cacheDirectory.deleteRecursively()
      }
      cacheDirectory.mkdirs()
    }
  }

  fun resolveMainFile(manifest: BuildManifest): BuildManifestFile? {
    val byIndex = manifest.files.firstOrNull { it.path.contains("index.html") }
    if (byIndex != null) return byIndex
    val byHtml = manifest.files.firstOrNull { it.contentType.contains("html", ignoreCase = true) }
    if (byHtml != null) return byHtml
    return manifest.files.firstOrNull()
  }

  private fun bundleDir(flowId: String, contentHash: String): File {
    val hashKey = contentHash.removePrefix("sha256:")
    return File(cacheDirectory, "flow_${sanitize(flowId)}_${sanitize(hashKey)}")
  }

  private fun downloadKey(flowId: String, contentHash: String): String = "${sanitize(flowId)}|$contentHash"

  private suspend fun downloadToDisk(flow: Flow): File = withContext(Dispatchers.IO) {
    val base = flow.url.toHttpUrlOrNull() ?: throw IOException("Invalid bundle url: ${flow.url}")
    val targetDir = bundleDir(flow.id, flow.manifest.contentHash)
    val tmpDir = File(cacheDirectory, "tmp_${UUID.randomUUID()}")
    tmpDir.mkdirs()

    try {
      // Ensure the bundle has an identifiable entrypoint.
      resolveMainFile(flow.manifest) ?: throw FlowError.InvalidManifest

      // Download all manifest files with bounded concurrency.
      val semaphore = Semaphore(maxConcurrentDownloads)
      coroutineScope {
        for (file in flow.manifest.files) {
          async(Dispatchers.IO) {
            semaphore.acquire()
            try {
              downloadFile(base, file, tmpDir)
            } finally {
              semaphore.release()
            }
          }
        }
      }

      // Replace any existing cache for this id/hash (best-effort).
      if (targetDir.exists()) {
        targetDir.deleteRecursively()
      }
      if (!tmpDir.renameTo(targetDir)) {
        // Fallback: copy if rename fails (should be rare).
        tmpDir.copyRecursively(targetDir, overwrite = true)
        tmpDir.deleteRecursively()
      }

      NuxieLogger.debug("Flow bundle cached for ${flow.id} at ${targetDir.absolutePath}")
      return@withContext targetDir
    } catch (t: Throwable) {
      NuxieLogger.warning("Flow bundle download failed for ${flow.id}: ${t.message}", t)
      tmpDir.deleteRecursively()
      if (t is FlowError) throw t
      throw FlowError.DownloadFailed
    }
  }

  private fun downloadFile(base: okhttp3.HttpUrl, file: BuildManifestFile, root: File) {
    // Avoid path traversal and absolute paths.
    if (file.path.startsWith("/") || file.path.contains("..")) {
      throw FlowError.InvalidManifest
    }

    val url = base.newBuilder().addPathSegments(file.path.trimStart('/')).build()
    val req = Request.Builder().url(url).get().build()
    val call = client.newCall(req)
    val res = call.execute()
    res.use { response ->
      if (!response.isSuccessful) {
        throw IOException("HTTP ${response.code} for ${file.path}")
      }
      val bytes = response.body?.bytes() ?: ByteArray(0)
      val outFile = File(root, file.path)

      // Ensure the output file stays under the root directory.
      val canonicalRoot = root.canonicalFile
      val canonicalOut = outFile.canonicalFile
      if (!canonicalOut.path.startsWith(canonicalRoot.path + File.separator)) {
        throw FlowError.InvalidManifest
      }

      canonicalOut.parentFile?.mkdirs()
      canonicalOut.writeBytes(bytes)
    }
  }

  private fun sanitize(value: String): String {
    return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
  }
}
