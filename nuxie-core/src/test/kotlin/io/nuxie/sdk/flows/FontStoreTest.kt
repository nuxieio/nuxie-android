package io.nuxie.sdk.flows

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FontStoreTest {

  @Test
  fun prefetchFonts_downloads_and_serves_payload() = runTest {
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        return when (request.path) {
          "/fonts/font_1.woff2" -> MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "font/woff2")
            .setBody("FAKEFONT")
          else -> MockResponse().setResponseCode(404)
        }
      }
    }
    server.start()

    val cacheDir = Files.createTempDirectory("nuxie_font_cache").toFile()
    try {
      val store = FontStore(cacheDirectory = cacheDir, downloadTimeoutSeconds = 5)
      val entry = FontManifestEntry(
        id = "font_1",
        family = "Inter",
        style = "normal",
        weight = "400",
        format = "woff2",
        contentHash = "sha256:hash1",
        assetUrl = server.url("/fonts/font_1.woff2").toString(),
      )

      store.prefetchFonts(listOf(entry))

      val payload = store.fontPayload("font_1")
      assertNotNull(payload)
      assertEquals("font/woff2", payload!!.mimeType)
      assertTrue(payload.bytes.isNotEmpty())
    } finally {
      server.shutdown()
      cacheDir.deleteRecursively()
    }
  }

  @Test
  fun fontPayloadBlocking_fetches_if_missing() = runTest {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "font/woff2")
        .setBody("FAKEFONT"),
    )
    server.start()

    val cacheDir = Files.createTempDirectory("nuxie_font_cache").toFile()
    try {
      val store = FontStore(cacheDirectory = cacheDir, downloadTimeoutSeconds = 5)
      val entry = FontManifestEntry(
        id = "font_1",
        family = "Inter",
        style = "normal",
        weight = "400",
        format = "woff2",
        contentHash = "sha256:hash1",
        assetUrl = server.url("/font.woff2").toString(),
      )

      store.registerFonts(listOf(entry))

      val payload = store.fontPayloadBlocking("font_1")
      assertNotNull(payload)
      assertEquals("font/woff2", payload!!.mimeType)
      assertTrue(payload.bytes.isNotEmpty())
    } finally {
      server.shutdown()
      cacheDir.deleteRecursively()
    }
  }
}

