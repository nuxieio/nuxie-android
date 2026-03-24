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
import org.junit.Assert.assertNull
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

  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun track_applies_user_properties_before_background_enqueue_can_race_with_identify() = runTest {
    val config = NuxieConfiguration(apiKey = "k").apply {
      beforeSend = null
    }
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("user_a") }
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

    service.track("test_event", userProperties = mapOf("plan" to "pro"))

    assertEquals("pro", identity.getUserProperties()["plan"]?.toString()?.trim('"'))

    identity.setDistinctId("user_b")
    advanceUntilIdle()

    assertNull(identity.getUserProperties()["plan"])

    identity.setDistinctId("user_a")
    assertEquals("pro", identity.getUserProperties()["plan"]?.toString()?.trim('"'))
    assertEquals("user_a", store.peek(1).first().distinctId)
  }

  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun enqueuePreparedEvent_still_applies_user_properties_for_direct_callers() = runTest {
    val config = NuxieConfiguration(apiKey = "k").apply {
      beforeSend = null
    }
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("user_a") }
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

    val event = service.prepareTriggerEvent(
      event = "test_event",
      userProperties = mapOf("plan" to "pro"),
    )

    service.enqueuePreparedEvent(event, persistToHistory = true)

    assertEquals("pro", identity.getUserProperties()["plan"]?.toString()?.trim('"'))
  }
}
