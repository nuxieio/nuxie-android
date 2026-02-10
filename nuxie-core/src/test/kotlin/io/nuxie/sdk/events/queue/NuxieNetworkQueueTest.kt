package io.nuxie.sdk.events.queue

import io.nuxie.sdk.network.NuxieApi
import io.nuxie.sdk.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeClock(var now: Long) : Clock {
  override fun nowEpochMillis(): Long = now
}

class NuxieNetworkQueueTest {
  @Test
  fun flush_success_deletes_events_from_store() = runBlocking {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"status\":\"ok\",\"processed\":2,\"failed\":0,\"total\":2}"),
    )
    server.start()
    try {
      val store = InMemoryEventQueueStore()
      val api = NuxieApi(apiKey = "k", baseUrl = server.url("/").toString().removeSuffix("/"))
      val queue = NuxieNetworkQueue(
        store = store,
        api = api,
        scope = CoroutineScope(Dispatchers.Unconfined),
        clock = FakeClock(0),
        flushAt = 999,
        flushIntervalSeconds = 999,
        maxQueueSize = 1000,
        maxBatchSize = 50,
        maxRetries = 1,
        baseRetryDelaySeconds = 1,
      )

      store.enqueue(QueuedEvent(name = "e1", distinctId = "d1", timestamp = "t", properties = kotlinx.serialization.json.JsonObject(emptyMap())))
      store.enqueue(QueuedEvent(name = "e2", distinctId = "d1", timestamp = "t", properties = kotlinx.serialization.json.JsonObject(emptyMap())))

      val flushed = queue.flush(forceSend = true)
      assertTrue(flushed)
      assertEquals(0, store.size())

      val req = server.takeRequest()
      assertEquals("/batch", req.path)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun http_400_drops_events() = runBlocking {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(400)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"message\":\"bad\"}"),
    )
    server.start()
    try {
      val store = InMemoryEventQueueStore()
      val api = NuxieApi(apiKey = "k", baseUrl = server.url("/").toString().removeSuffix("/"))
      val queue = NuxieNetworkQueue(
        store = store,
        api = api,
        scope = CoroutineScope(Dispatchers.Unconfined),
        clock = FakeClock(0),
        flushAt = 999,
        flushIntervalSeconds = 999,
        maxQueueSize = 1000,
        maxBatchSize = 50,
        maxRetries = 1,
        baseRetryDelaySeconds = 1,
      )

      store.enqueue(QueuedEvent(name = "e1", distinctId = "d1", timestamp = "t", properties = kotlinx.serialization.json.JsonObject(emptyMap())))
      queue.flush(forceSend = true)
      assertEquals(0, store.size())
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun retry_backoff_prevents_immediate_retry() = runBlocking {
    val server = MockWebServer()
    server.enqueue(
      MockResponse()
        .setResponseCode(500)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"message\":\"boom\"}"),
    )
    // Second attempt succeeds.
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"status\":\"ok\",\"processed\":1,\"failed\":0,\"total\":1}"),
    )
    server.start()
    try {
      val clock = FakeClock(0)
      val store = InMemoryEventQueueStore()
      val api = NuxieApi(apiKey = "k", baseUrl = server.url("/").toString().removeSuffix("/"))
      val queue = NuxieNetworkQueue(
        store = store,
        api = api,
        scope = CoroutineScope(Dispatchers.Unconfined),
        clock = clock,
        flushAt = 999,
        flushIntervalSeconds = 999,
        maxQueueSize = 1000,
        maxBatchSize = 50,
        maxRetries = 2,
        baseRetryDelaySeconds = 10,
      )

      store.enqueue(QueuedEvent(name = "e1", distinctId = "d1", timestamp = "t", properties = kotlinx.serialization.json.JsonObject(emptyMap())))

      // First flush fails, sets backoff.
      queue.flush(forceSend = true)
      assertEquals(1, store.size())
      assertEquals(1, server.requestCount)

      // Immediate retry should be skipped due to backoff.
      queue.flush(forceSend = true)
      assertEquals(1, server.requestCount)

      // Advance clock beyond backoff and retry.
      clock.now += 10_000
      queue.flush(forceSend = true)
      assertEquals(0, store.size())
      assertEquals(2, server.requestCount)
    } finally {
      server.shutdown()
    }
  }
}

