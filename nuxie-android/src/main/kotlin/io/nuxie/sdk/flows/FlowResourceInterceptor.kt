package io.nuxie.sdk.flows

import android.net.Uri
import android.webkit.WebResourceResponse
import io.nuxie.sdk.logging.NuxieLogger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.io.File

/**
 * WebView resource interception to enable cache-first bundle loading and the `nuxie-font://` scheme.
 *
 * Android WebView cannot consume iOS-style WebArchives. Instead:
 * - Bundle files are downloaded to disk via [FlowBundleStore].
 * - WebView loads remote bundle URLs, but requests are intercepted and served from disk when cached.
 */
class FlowResourceInterceptor(
  private val flow: Flow,
  private val fontStore: FontStore,
) {
  @Volatile
  private var bundleDir: File? = null

  private val base = flow.url.toHttpUrlOrNull()
  private val contentTypeByPath: Map<String, String> = flow.manifest.files.associate { it.path.trimStart('/') to it.contentType }

  fun setBundleDir(dir: File?) {
    bundleDir = dir
  }

  fun intercept(uri: Uri): WebResourceResponse? {
    val scheme = uri.scheme?.lowercase()

    // Fonts: nuxie-font://<id>
    if (scheme == "nuxie-font") {
      val fontId = parseFontId(uri) ?: return null
      val payload = fontStore.fontPayloadBlocking(fontId) ?: return null
      return WebResourceResponse(payload.mimeType, null, ByteArrayInputStream(payload.bytes))
    }

    val baseUrl = base ?: return null
    val dir = bundleDir ?: return null

    val req = uri.toString().toHttpUrlOrNull() ?: return null
    if (req.scheme != baseUrl.scheme || req.host != baseUrl.host || req.port != baseUrl.port) return null

    val basePath = baseUrl.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
    val reqPath = req.encodedPath
    if (!reqPath.startsWith(basePath)) return null

    val relative = reqPath.removePrefix(basePath).trimStart('/')
    if (relative.isBlank()) return null

    val file = File(dir, relative)
    if (!file.exists() || !file.isFile) return null

    val mime = contentTypeByPath[relative] ?: guessMimeType(relative)
    val encoding = if (isUtf8Text(mime)) "UTF-8" else null

    return try {
      WebResourceResponse(mime, encoding, file.inputStream())
    } catch (t: Throwable) {
      NuxieLogger.debug("FlowResourceInterceptor failed to serve $relative: ${t.message}", t)
      null
    }
  }

  private fun parseFontId(uri: Uri): String? {
    val host = uri.host
    if (!host.isNullOrBlank()) return host
    val path = uri.path?.trim('/') ?: return null
    return if (path.isBlank()) null else path
  }

  private fun isUtf8Text(mimeType: String): Boolean {
    val m = mimeType.lowercase()
    return m.startsWith("text/") || m.contains("javascript") || m.contains("css") || m.contains("json")
  }

  private fun guessMimeType(path: String): String {
    val lower = path.lowercase()
    return when {
      lower.endsWith(".html") -> "text/html"
      lower.endsWith(".js") -> "application/javascript"
      lower.endsWith(".css") -> "text/css"
      lower.endsWith(".json") -> "application/json"
      lower.endsWith(".png") -> "image/png"
      lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
      lower.endsWith(".svg") -> "image/svg+xml"
      lower.endsWith(".woff2") -> "font/woff2"
      lower.endsWith(".woff") -> "font/woff"
      lower.endsWith(".ttf") -> "font/ttf"
      lower.endsWith(".otf") -> "font/otf"
      else -> "application/octet-stream"
    }
  }
}

