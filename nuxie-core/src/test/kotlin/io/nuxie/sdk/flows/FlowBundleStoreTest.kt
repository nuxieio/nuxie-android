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
import java.io.File
import java.nio.file.Files

class FlowBundleStoreTest {

  private fun sampleFlow(baseUrl: String): Flow {
    val manifest = BuildManifest(
      totalFiles = 2,
      totalSize = 20,
      contentHash = "sha256:abc",
      files = listOf(
        BuildManifestFile(path = "index.html", size = 10, contentType = "text/html"),
        BuildManifestFile(path = "main.js", size = 10, contentType = "application/javascript"),
      ),
    )
    val rf = RemoteFlow(
      id = "flow_1",
      bundle = FlowBundleRef(url = baseUrl, manifest = manifest),
      screens = listOf(RemoteFlowScreen(id = "screen_1")),
      interactions = emptyMap(),
      viewModels = emptyList(),
    )
    return Flow(remoteFlow = rf)
  }

  @Test
  fun preloadBundle_downloads_and_is_cache_first_afterwards() = runTest {
    val server = MockWebServer()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        return when (request.path) {
          "/bundle/index.html" -> MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/html")
            .setBody("<!doctype html><html><head></head><body>ok</body></html>")
          "/bundle/main.js" -> MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/javascript")
            .setBody("console.log('ok');")
          else -> MockResponse().setResponseCode(404)
        }
      }
    }
    server.start()

    val cacheDir = Files.createTempDirectory("nuxie_flow_cache").toFile()
    try {
      val flow = sampleFlow(server.url("/bundle/").toString())
      val store = FlowBundleStore(cacheDirectory = cacheDir, maxConcurrentDownloads = 2, downloadTimeoutSeconds = 5)

      val dir = store.preloadBundle(flow)
      assertTrue(File(dir, "index.html").exists())
      assertTrue(File(dir, "main.js").exists())

      assertNotNull(store.getCachedBundleDir(flow))
      assertEquals("index.html", store.resolveMainFile(flow.manifest)?.path)

      // Second call should be cache-only (no new HTTP requests).
      val before = server.requestCount
      val dir2 = store.preloadBundle(flow)
      assertEquals(dir.absolutePath, dir2.absolutePath)
      assertEquals(before, server.requestCount)
    } finally {
      server.shutdown()
      cacheDir.deleteRecursively()
    }
  }

  @Test(expected = FlowError.InvalidManifest::class)
  fun preloadBundle_rejects_path_traversal() = runTest {
    val server = MockWebServer()
    server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
    server.start()

    val cacheDir = Files.createTempDirectory("nuxie_flow_cache").toFile()
    try {
      val manifest = BuildManifest(
        totalFiles = 1,
        totalSize = 1,
        contentHash = "sha256:bad",
        files = listOf(
          BuildManifestFile(path = "../secrets.txt", size = 1, contentType = "text/plain"),
        ),
      )
      val rf = RemoteFlow(
        id = "flow_bad",
        bundle = FlowBundleRef(url = server.url("/bundle/").toString(), manifest = manifest),
        screens = listOf(RemoteFlowScreen(id = "screen_1")),
        interactions = emptyMap(),
        viewModels = emptyList(),
      )
      val flow = Flow(remoteFlow = rf)
      val store = FlowBundleStore(cacheDirectory = cacheDir)

      store.preloadBundle(flow)
    } finally {
      server.shutdown()
      cacheDir.deleteRecursively()
    }
  }
}

