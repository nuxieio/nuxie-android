package io.nuxie.sdk.events

import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.events.queue.InMemoryEventQueueStore
import io.nuxie.sdk.events.queue.NuxieNetworkQueue
import io.nuxie.sdk.events.store.InMemoryEventHistoryStore
import io.nuxie.sdk.identity.DefaultIdentityService
import io.nuxie.sdk.network.NuxieApi
import io.nuxie.sdk.session.DefaultSessionService
import io.nuxie.sdk.storage.InMemoryKeyValueStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EventServiceTest {
  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun track_enqueues_event_and_adds_session_id_when_missing() = runTest {
    val config = NuxieConfiguration(apiKey = "k").apply {
      // Disable hooks for this test.
      beforeSend = null
    }
    val identity = DefaultIdentityService(InMemoryKeyValueStore())
    val session = DefaultSessionService()
    val store = InMemoryEventQueueStore()

    val api = NuxieApi(apiKey = "k", baseUrl = "https://example.com")
    val queue = NuxieNetworkQueue(
      store = store,
      api = api,
      scope = this,
      flushAt = 999,
      flushIntervalSeconds = 999,
      maxQueueSize = 1000,
      maxBatchSize = 50,
      maxRetries = 0,
      baseRetryDelaySeconds = 1,
    )

    val service = EventService(
      identityService = identity,
      sessionService = session,
      configuration = config,
      api = api,
      store = store,
      historyStore = InMemoryEventHistoryStore(),
      networkQueue = queue,
      scope = this,
    )

    service.track("test_event", properties = mapOf("a" to 1))
    advanceUntilIdle()

    val queued = store.peek(1).first()
    assertEquals("test_event", queued.name)
    assertEquals(identity.getDistinctId(), queued.distinctId)
    assertNotNull(queued.properties["\$session_id"])
  }
}
